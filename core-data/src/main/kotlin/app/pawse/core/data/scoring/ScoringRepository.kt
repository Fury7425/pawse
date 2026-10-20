package app.pawse.core.data.scoring

import app.pawse.core.data.db.DayNoteDao
import app.pawse.core.data.db.DayNoteEntity
import app.pawse.core.data.db.ExerciseDao
import app.pawse.core.data.db.FoodLogDao
import app.pawse.core.data.db.MetricSampleDao
import app.pawse.core.data.db.SleepDao
import app.pawse.core.data.profile.UserProfilePreferences
import app.pawse.core.data.sync.NightSignalEnricher
import app.pawse.core.data.sync.SyncPreferences
import app.pawse.core.data.sync.SyncStep
import app.pawse.core.data.sync.WorkoutHeartRateEnricher
import app.pawse.scoring.PawseEngine
import app.pawse.scoring.config.ScoringConfig
import app.pawse.scoring.model.Calibration
import app.pawse.scoring.model.DayReport
import app.pawse.scoring.model.DayTag
import app.pawse.scoring.model.EnergyForecast
import app.pawse.scoring.model.Insight
import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.NightInput
import app.pawse.scoring.model.UserProfile
import app.pawse.scoring.tr
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
import kotlin.math.exp
import javax.inject.Singleton

/**
 * What the Today and explain screens read: one guidance line, three cards, the
 * energy curve, and everything behind them. The UI recomputes nothing.
 */
data class ScoringSnapshot(
    /** The morning these numbers belong to. Not necessarily today. */
    val date: LocalDate,
    val today: DayReport,
    /** Up to ninety days ending on [date], oldest first: the explain screen's trends. */
    val history: List<DayReport>,
    /** Each night's stored figures by wake date, over the same days. */
    val nights: Map<LocalDate, NightRecord>,
    val energy: EnergyForecast?,
    val insights: List<Insight>,
    val calibration: Calibration,
    val profile: UserProfile,
    val checkIn: Int?,
    val tags: Set<DayTag>,
    val showNumbers: Boolean,
    val computedAtEpochMs: Long,
    val lastSyncEpochMs: Long,
    val scoringVersion: String,
    val configHash: String,
) {
    /** Up to seven days ending on [date], oldest first. */
    val week: List<DayReport>
        get() = history.takeLast(7)

    /** Banister needs an HRmax; without age or a measured one, sessions fall back to effort. */
    val needsProfile: Boolean
        get() = profile.ageYears == null && profile.measuredMaxHeartRate == null
}

/**
 * One night as it was stored and fed to the engine: the sleep it was assembled
 * into and the nightly signals. The Sleep tab prints these, never its own sums.
 */
data class NightRecord(
    val sleep: NightSummary,
    val signals: NightInput,
    /** Share of the night with heart-rate data, 0..1. Null until the night's series was read. */
    val hrCoverage: Double?,
    /** The app that wrote the sleep session. */
    val source: String,
) {
    /** The night's median RMSSD, which the signal layer keeps as its log. */
    val hrvMs: Double? get() = signals.lnHrv?.value?.let(::exp)
}

sealed interface ScoringState {
    data object Loading : ScoringState
    data class Ready(val snapshot: ScoringSnapshot) : ScoringState
    data class Failed(val message: String) : ScoringState
}

/**
 * Storage in, guidance out.
 *
 * **Everything is recomputed on demand from stored samples.** The filter state,
 * baselines, calibration and insights are all functions of the same rows, so
 * replaying 120 days is cheaper than keeping a second copy that can drift from
 * them. It also means v1 and v2 can be replayed on the same stored data.
 */
@Singleton
class ScoringRepository @Inject constructor(
    private val metricDao: MetricSampleDao,
    private val sleepDao: SleepDao,
    private val exerciseDao: ExerciseDao,
    private val foodLogDao: FoodLogDao,
    private val dayNoteDao: DayNoteDao,
    private val syncPreferences: SyncPreferences,
    private val profilePreferences: UserProfilePreferences,
    private val workoutEnricher: WorkoutHeartRateEnricher,
    private val nightEnricher: NightSignalEnricher,
) {

    private val config: ScoringConfig = ScoringConfig.DEFAULT
    private val engine = PawseEngine(config)
    private val mutex = Mutex()

    private val _state = MutableStateFlow<ScoringState>(ScoringState.Loading)
    val state: StateFlow<ScoringState> = _state.asStateFlow()

    private var lastComputedFor: LocalDate? = null

    /** Recompute only if what we are holding is stale. */
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

    suspend fun refresh(
        today: LocalDate = LocalDate.now(),
        windowDays: Int = DEFAULT_WINDOW_DAYS,
        onStep: (SyncStep) -> Unit = {},
    ): ScoringState = mutex.withLock {
        val result = runCatching { compute(today, windowDays, onStep) }
        val next = result.fold(
            onSuccess = { ScoringState.Ready(it) },
            onFailure = { ScoringState.Failed(it.message ?: tr("Could not work out today", "오늘 값을 계산하지 못했어요")) },
        )
        _state.value = next
        next
    }

    // --- What the user tells us ------------------------------------------------

    /** "How do you feel?" 1..5. Calibrates tomorrow; never moves today's Body. */
    suspend fun setCheckIn(date: LocalDate, feeling: Int?) {
        withContext(Dispatchers.IO) {
            val note = dayNoteDao.get(date.toString())
            dayNoteDao.upsert(
                DayNoteEntity(date.toString(), feeling, note?.tags.orEmpty(), System.currentTimeMillis()),
            )
        }
        refresh()
    }

    suspend fun toggleTag(date: LocalDate, tag: DayTag) {
        withContext(Dispatchers.IO) {
            val note = dayNoteDao.get(date.toString())
            val tags = note?.tags.orEmpty().split(',').filter { it.isNotBlank() }.toMutableSet()
            if (!tags.remove(tag.name)) tags += tag.name
            dayNoteDao.upsert(
                DayNoteEntity(date.toString(), note?.feeling, tags.joinToString(","), System.currentTimeMillis()),
            )
        }
        refresh()
    }

    /** One button back to the default model. Check-ins before today stop counting. */
    suspend fun resetCalibration() {
        profilePreferences.resetCalibration()
        refresh()
    }

    private suspend fun compute(today: LocalDate, windowDays: Int, onStep: (SyncStep) -> Unit): ScoringSnapshot =
        withContext(Dispatchers.IO) {
            onStep(SyncStep.COMPARE)
            val from = today.minusDays(windowDays.toLong())
            val fromKey = from.toString()
            val toKey = today.toString()

            // Workouts arrive without a heart rate, nights without Pawse's own sleeping HR.
            runCatching { workoutEnricher.enrich(fromKey, toKey) }
            runCatching { nightEnricher.enrich(fromKey, toKey) }

            val samples = LOADED_METRICS.flatMap { metricDao.range(it.key, fromKey, toKey) }
            val sessions = sleepDao.sessionsBetween(fromKey, toKey)
            val stagesBySession = sessions.associate { it.id to sleepDao.stagesFor(it.id) }
            val nights = sessions.groupBy { it.localDate }
                .mapValues { (date, forDate) -> NightAssembler.summarise(date, forDate, stagesBySession) }
            val notes = dayNoteDao.between(fromKey, toKey)
            val profile = profilePreferences.current()

            val dates = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.toList()
            val input = DailyInputAssembler(config, syncPreferences.sourcePriority.first()).assemble(
                dates = dates,
                samples = samples,
                nights = nights,
                sessions = sessions,
                exercises = exerciseDao.between(fromKey, toKey),
                // The evening before the first night matters too.
                food = foodLogDao.between(from.minusDays(1).toString(), toKey),
                notes = notes,
                profile = profile,
                calibrationSince = profilePreferences.calibrationSince.first(),
            )

            onStep(SyncStep.SCORE)
            val result = engine.run(input)

            // The most recent morning with a night behind it, then the most recent
            // day at all. At half past midnight "today" has no night yet, and a
            // screen that went blank until waking would hide a real reading.
            val newest = result.days.lastOrNull { it.body != null || it.sleep.lastNightMinutes != null }
                ?: result.days.last()
            val history = result.days.filter { !it.date.isAfter(newest.date) }.takeLast(HISTORY)
            val note = notes.firstOrNull { it.localDate == newest.date.toString() }
            val coverage = samples.filter { it.metric == Metric.NIGHT_HR_COVERAGE.key }.associate { it.localDate to it.value }
            val writers = sessions.filter { !it.isNap }.associate { it.localDate to it.sourceApp }
            val nightRecords = history.mapNotNull { day ->
                val key = day.date.toString()
                val sleep = nights[key]?.takeIf { it.hasSession } ?: return@mapNotNull null
                val signals = input.nights[day.date] ?: return@mapNotNull null
                day.date to NightRecord(sleep, signals, coverage[key], writers[key].orEmpty())
            }.toMap()

            lastComputedFor = today
            ScoringSnapshot(
                date = newest.date,
                today = newest,
                history = history,
                nights = nightRecords,
                energy = result.energy.takeIf { newest.date == result.days.last().date },
                insights = result.insights,
                calibration = result.calibration,
                profile = profile,
                checkIn = note?.feeling,
                tags = note?.tags.orEmpty().split(',')
                    .mapNotNull { runCatching { DayTag.valueOf(it.trim()) }.getOrNull() }.toSet(),
                showNumbers = profilePreferences.showNumbers.first(),
                computedAtEpochMs = System.currentTimeMillis(),
                lastSyncEpochMs = syncPreferences.lastSyncEpochMs.first(),
                scoringVersion = result.scoringVersion,
                configHash = result.configHash,
            )
        }

    companion object {
        /** Dose-response looks back 120 days; the baseline needs 67. */
        const val DEFAULT_WINDOW_DAYS = 120

        const val STALE_AFTER_MS = 120_000L

        /** The explain screen's longest trend. */
        private const val HISTORY = 90

        private val LOADED_METRICS = listOf(
            Metric.HRV_RMSSD,
            Metric.RESTING_HEART_RATE,
            Metric.RESPIRATORY_RATE,
            Metric.SPO2,
            Metric.SKIN_TEMPERATURE,
            Metric.SLEEPING_HEART_RATE,
            Metric.HR_NADIR,
            Metric.NIGHT_HR_COVERAGE,
            Metric.MENSTRUATION,
        )
    }
}
