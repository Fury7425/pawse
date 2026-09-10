package app.pawse.core.data.scoring

import app.pawse.core.data.db.ExerciseDao
import app.pawse.core.data.db.MetricSampleDao
import app.pawse.core.data.db.ScoreDao
import app.pawse.core.data.db.SleepDao
import app.pawse.core.data.profile.UserProfilePreferences
import app.pawse.core.data.sync.SyncPreferences
import app.pawse.core.data.sync.WorkoutHeartRateEnricher
import app.pawse.scoring.config.ScoringConfig
import app.pawse.scoring.config.StrainScale
import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.Score
import app.pawse.scoring.model.ScoreType
import app.pawse.scoring.model.ScoreUnavailable
import app.pawse.scoring.model.ScoringOutcome
import app.pawse.scoring.model.UserProfile
import app.pawse.scoring.sleep.SleepNeed
import app.pawse.scoring.strain.SessionLoad
import app.pawse.scoring.strain.StrainTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the Home and explainability screens read.
 *
 * The [scores] map holds only what actually scored; [unavailable] holds the
 * reasons for everything that did not, because "no number tonight, and here is
 * why" is a state this app treats as a first-class result rather than an error.
 */
data class ScoringSnapshot(
    /** Wake date these numbers belong to. Not necessarily today. */
    val date: LocalDate,
    val scores: Map<ScoreType, Score>,
    val unavailable: Map<ScoreType, ScoreUnavailable>,
    val sleepNeed: SleepNeed?,
    val strainTarget: StrainTarget?,
    val sessionLoads: List<SessionLoad>,
    val strainScale: StrainScale,
    /** Strain on the scale the user is reading, canonical value untouched. */
    val strainDisplayValue: Double?,
    val profile: UserProfile,
    val nightsInWindow: Int,
    val computedAtEpochMs: Long,
    val lastSyncEpochMs: Long,
) {
    val hasAnyScore: Boolean get() = scores.isNotEmpty()

    /** Strain cannot run at all without an age or a measured maximum heart rate. */
    val needsProfile: Boolean
        get() = profile.ageYears == null && profile.measuredMaxHeartRate == null
}

sealed interface ScoringState {
    data object Loading : ScoringState
    data class Ready(val snapshot: ScoringSnapshot) : ScoringState
    data class Failed(val message: String) : ScoringState
}

/**
 * Storage in, scores out.
 *
 * Two things here are worth stating, because both are choices and both are
 * reversible:
 *
 * **Scores are recomputed on demand and persisted afterwards, not read back.**
 * The score table is a record of what was shown, stamped with the config hash and
 * engine version that produced it, and it exists so that retuning a weight
 * tomorrow cannot silently rewrite last week. What the screen shows is the result
 * of running the engine now over the samples we hold now. Recomputing ninety days
 * costs a few milliseconds; getting a stale number because a sync arrived after
 * the last recompute costs the user's trust.
 *
 * **The derived detail is not stored at all.** Sleep need, target strain and the
 * per-session breakdown are functions of the same inputs, so they live in the
 * in-memory [snapshot] and are rebuilt with it rather than being persisted as a
 * second copy that can drift from the score it explains.
 */
@Singleton
class ScoringRepository @Inject constructor(
    private val metricDao: MetricSampleDao,
    private val sleepDao: SleepDao,
    private val exerciseDao: ExerciseDao,
    private val scoreDao: ScoreDao,
    private val syncPreferences: SyncPreferences,
    private val profilePreferences: UserProfilePreferences,
    private val enricher: WorkoutHeartRateEnricher,
) {

    private val config: ScoringConfig = ScoringConfig.DEFAULT
    private val pipeline = ScoringPipeline(config)
    private val mutex = Mutex()

    private val _state = MutableStateFlow<ScoringState>(ScoringState.Loading)
    val state: StateFlow<ScoringState> = _state.asStateFlow()

    /**
     * The date the current snapshot was computed *for*, which is not always the
     * date it ended up describing: before the night is synced, the newest scoreable
     * day is yesterday.
     */
    private var lastComputedFor: LocalDate? = null

    /**
     * Recompute only if what we are holding is stale.
     *
     * Opening the explainability screen creates a second view model looking at the
     * same repository, and rebuilding ninety days to answer "show me the row I am
     * already looking at" is work nobody asked for. A sync always calls [refresh]
     * directly, so this can afford to be lazy.
     */
    suspend fun ensureFresh(today: LocalDate = LocalDate.now()): ScoringState {
        val current = _state.value
        if (current is ScoringState.Ready &&
            lastComputedFor == today &&
            System.currentTimeMillis() - current.snapshot.computedAtEpochMs < STALE_AFTER_MS
        ) {
            return current
        }
        return refresh(today)
    }

    /**
     * Recompute the window, publish the newest day, and persist every day it
     * produced.
     *
     * @param windowDays how far back to rebuild. The default matches the sync
     *   backfill so the widest configurable baseline window is always full.
     */
    suspend fun refresh(
        today: LocalDate = LocalDate.now(),
        windowDays: Int = DEFAULT_WINDOW_DAYS,
    ): ScoringState = mutex.withLock {
        val result = runCatching { compute(today, windowDays) }
        val next = result.fold(
            onSuccess = { ScoringState.Ready(it) },
            onFailure = { ScoringState.Failed(it.message ?: "Could not compute your scores") },
        )
        _state.value = next
        next
    }

    private suspend fun compute(today: LocalDate, windowDays: Int): ScoringSnapshot =
        withContext(Dispatchers.IO) {
            val from = today.minusDays(windowDays.toLong())
            val fromKey = from.toString()
            val toKey = today.toString()

            // Workouts arrive without a heart rate, so go and fetch one before
            // scoring rather than reporting a rest day for a two-hour ride.
            runCatching { enricher.enrich(fromKey, toKey) }

            val samples = LOADED_METRICS.flatMap { metric ->
                metricDao.range(metric.key, fromKey, toKey)
            }

            val sessions = sleepDao.sessionsBetween(fromKey, toKey)
            val stagesBySession = sessions.associate { it.id to sleepDao.stagesFor(it.id) }
            val nights = sessions.groupBy { it.localDate }
                .mapValues { (date, forDate) -> NightAssembler.summarise(date, forDate, stagesBySession) }

            val exercises = exerciseDao.between(fromKey, toKey)
            val sourcePriority = syncPreferences.sourcePriority.first()
            val profile = profilePreferences.current()
            val strainScale = profilePreferences.strainScale.first()

            val dates = generateSequence(from) { it.plusDays(1) }
                .takeWhile { !it.isAfter(today) }
                .toList()

            val bundles = DailyInputAssembler(config, sourcePriority)
                .assemble(dates, samples, nights, exercises)

            val days = pipeline.run(bundles, profile)
            val now = System.currentTimeMillis()

            persist(days, now)

            // The most recent night we could actually score, then the most recent
            // day with anything at all, then today whatever it holds.
            //
            // The first clause matters at half past midnight: "today" has no night
            // yet, and a screen that went blank between midnight and waking would be
            // hiding a real number behind a calendar boundary. The date is in the
            // header either way, so yesterday's night is shown as yesterday's.
            val newest = days.lastOrNull { day ->
                day.recovery is ScoringOutcome.Scored || day.sleep is ScoringOutcome.Scored
            } ?: days.lastOrNull { day -> day.anyScored() } ?: days.last()

            lastComputedFor = today
            snapshotOf(
                day = newest,
                strainScale = strainScale,
                profile = profile,
                nightsInWindow = nights.values.count { it.hasSession },
                computedAtEpochMs = now,
                lastSyncEpochMs = syncPreferences.lastSyncEpochMs.first(),
            )
        }

    private suspend fun persist(days: List<DayScores>, computedAtEpochMs: Long) {
        val rows = days.flatMap { it.outcomes.values }
            .mapNotNull { it.scoreOrNull }
            .map { ScoreMapper.toEntity(it, computedAtEpochMs) }
        if (rows.isNotEmpty()) scoreDao.upsertAll(rows)
    }

    private fun snapshotOf(
        day: DayScores,
        strainScale: StrainScale,
        profile: UserProfile,
        nightsInWindow: Int,
        computedAtEpochMs: Long,
        lastSyncEpochMs: Long,
    ): ScoringSnapshot {
        val scored = mutableMapOf<ScoreType, Score>()
        val unavailable = mutableMapOf<ScoreType, ScoreUnavailable>()
        for ((type, outcome) in day.outcomes) {
            when (outcome) {
                is ScoringOutcome.Scored -> scored[type] = outcome.score
                is ScoringOutcome.NotScored -> unavailable[type] = outcome.unavailable
            }
        }
        return ScoringSnapshot(
            date = day.date,
            scores = scored,
            unavailable = unavailable,
            sleepNeed = day.sleepNeed,
            strainTarget = day.strainTarget,
            sessionLoads = day.sessionLoads,
            strainScale = strainScale,
            strainDisplayValue = scored[ScoreType.STRAIN]
                ?.let { pipeline.strainDisplayValue(it.value, strainScale) },
            profile = profile,
            nightsInWindow = nightsInWindow,
            computedAtEpochMs = computedAtEpochMs,
            lastSyncEpochMs = lastSyncEpochMs,
        )
    }

    companion object {
        /** Matches the sync backfill, so the widest baseline window fills in one pass. */
        const val DEFAULT_WINDOW_DAYS = 90

        /**
         * How long a computed snapshot stands before [ensureFresh] rebuilds it.
         * Two minutes: long enough to cover navigating around the app, short enough
         * that a sync landing in the background is never far from the screen.
         */
        const val STALE_AFTER_MS = 120_000L

        /**
         * Metrics read from storage. Everything else the engine consumes is derived
         * from sleep sessions and exercise sessions rather than stored as a sample.
         */
        private val LOADED_METRICS = listOf(
            Metric.HRV_RMSSD,
            Metric.RESTING_HEART_RATE,
            Metric.RESPIRATORY_RATE,
            Metric.SPO2,
            Metric.SKIN_TEMPERATURE,
            Metric.HEART_RATE,
        )
    }
}

/** The five outcomes of a day, keyed so callers can iterate rather than branch. */
val DayScores.outcomes: Map<ScoreType, ScoringOutcome>
    get() = mapOf(
        ScoreType.RECOVERY to recovery,
        ScoreType.SLEEP to sleep,
        ScoreType.STRAIN to strain,
        ScoreType.ENERGY_BANK to energyBank,
        ScoreType.LOAD_RATIO to loadRatio,
    )

fun DayScores.anyScored(): Boolean = outcomes.values.any { it is ScoringOutcome.Scored }
