package app.pawse.core.data.scoring

import app.pawse.core.data.db.ExerciseSessionEntity
import app.pawse.core.data.db.MetricSampleEntity
import app.pawse.scoring.config.ScoringConfig
import app.pawse.scoring.model.DayActivity
import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.WorkoutSample
import app.pawse.scoring.sleep.SleepSubScores
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * One day of everything the engine needs, keyed by wake date.
 *
 * [values] deliberately does not contain [Metric.SLEEP_SCORE]: that one is an
 * engine output fed back in as a Recovery input, so it is added by the pipeline
 * after Sleep has run and never by the assembler.
 */
data class DayBundle(
    val date: LocalDate,
    val values: Map<Metric, Double>,
    val night: NightSummary,
    val activity: DayActivity,
)

/**
 * Storage rows to engine inputs.
 *
 * This is the layer where Android's real data mess is dealt with, so that
 * `:core-scoring` never has to know about it. Four rules run through it:
 *
 * **Duplicate writers resolve by priority, never by averaging.** Two apps
 * mirroring one watch write overlapping records with slightly different values.
 * Averaging them invents a third number that no device ever measured; picking the
 * highest-priority source that has data for that metric on that day does not.
 *
 * **An overnight metric is only overnight if it was measured overnight.** HRV,
 * respiratory rate and SpO2 are read inside the sleep window when we have one.
 * Failing that they are accepted from the night hours around it, because several
 * apps stamp their nightly aggregate at wake time or at midnight. A midday HRV
 * spot reading is dropped, which is the whole point of the probe's
 * `DAYTIME_SPOT_ONLY` verdict: the alternative is a Recovery score built on a
 * measurement taken while the user was in a meeting.
 *
 * **Contributors that need history are computed here, not in the engine.**
 * Restfulness, timing and bedtime consistency cannot be derived from one night in
 * isolation, so they are built with the public helpers in [SleepSubScores] and
 * handed in pre-normalised, exactly as `:core-scoring` documents.
 *
 * **Absent stays absent.** No population defaults, no carrying yesterday's value
 * forward, no zeros standing in for nulls.
 */
class DailyInputAssembler(
    private val config: ScoringConfig = ScoringConfig.DEFAULT,
    private val sourcePriority: List<String> = emptyList(),
) {

    /**
     * Nights of history needed before the two circadian contributors mean anything.
     * Below this we report them absent rather than computing a drift against three
     * nights and calling it habitual.
     */
    private val minNightsForCircadian = 5

    /** Trailing nights the circadian terms look back over. Apple uses about two weeks. */
    private val circadianWindowNights = 14

    fun assemble(
        dates: List<LocalDate>,
        samples: List<MetricSampleEntity>,
        nights: Map<String, NightSummary>,
        exercises: List<ExerciseSessionEntity>,
    ): List<DayBundle> {
        val samplesByMetricDate: Map<Pair<String, String>, List<MetricSampleEntity>> =
            samples.groupBy { it.metric to it.localDate }
        val exercisesByDate: Map<String, List<ExerciseSessionEntity>> = exercises.groupBy { it.localDate }

        val ordered = dates.sorted()
        val bundles = mutableListOf<DayBundle>()

        for (date in ordered) {
            val key = date.toString()
            val night = nights[key] ?: NightSummary.empty(key)
            val values = mutableMapOf<Metric, Double>()

            // --- Overnight physiology ------------------------------------------
            nightlyValue(Metric.HRV_RMSSD, key, night, samplesByMetricDate)?.let { values[Metric.HRV_RMSSD] = it }
            nightlyValue(Metric.RESPIRATORY_RATE, key, night, samplesByMetricDate)
                ?.let { values[Metric.RESPIRATORY_RATE] = it }
            nightlyValue(Metric.SPO2, key, night, samplesByMetricDate)?.let { values[Metric.SPO2] = it }

            // Resting heart rate and skin temperature arrive as daily aggregates
            // from most writers, so they are taken for the day rather than for the
            // sleep window.
            dailyValue(Metric.RESTING_HEART_RATE, key, samplesByMetricDate)
                ?.let { values[Metric.RESTING_HEART_RATE] = it }
            dailyValue(Metric.SKIN_TEMPERATURE, key, samplesByMetricDate)
                ?.let { values[Metric.SKIN_TEMPERATURE] = it }

            // --- Sleep architecture ---------------------------------------------
            if (night.hasSession) {
                values[Metric.TIME_ASLEEP] = night.asleepMinutes
                night.efficiencyPercent?.let { values[Metric.SLEEP_EFFICIENCY] = it }
                night.remMinutes?.let { values[Metric.REM_MINUTES] = it }
                night.deepMinutes?.let { values[Metric.DEEP_MINUTES] = it }
                night.latencyMinutes?.let { values[Metric.SLEEP_LATENCY] = it }
                night.interruptions?.let { values[Metric.SLEEP_INTERRUPTIONS] = it.toDouble() }

                if (night.wasoMinutes != null && night.awakenings != null) {
                    values[Metric.RESTFULNESS] = SleepSubScores.restfulness(
                        wasoMinutes = night.wasoMinutes,
                        awakenings = night.awakenings,
                        t = config.sleep.targets,
                    )
                }

                heartRateDip(key, night, samplesByMetricDate, values[Metric.RESTING_HEART_RATE])
                    ?.let { values[Metric.HEART_RATE_DIP] = it }
            }

            // --- Circadian contributors, which need the nights before this one ----
            val history = trailingNights(date, nights)
            if (history.size >= minNightsForCircadian && night.timezoneShiftMinutes == 0) {
                val habitualMidpoint = CircularClock.meanMinutes(history.mapNotNull { it.midpointMinutes })
                if (habitualMidpoint != null && night.midpointMinutes != null) {
                    values[Metric.SLEEP_TIMING] = SleepSubScores.timing(
                        midpointDriftMinutes = CircularClock.difference(habitualMidpoint, night.midpointMinutes),
                        t = config.sleep.targets,
                    )
                }
                val bedtimes = (history.mapNotNull { it.bedtimeMinutes } + listOfNotNull(night.bedtimeMinutes))
                val bedtimeSd = CircularClock.sdMinutes(bedtimes)
                if (bedtimeSd != null) {
                    values[Metric.BEDTIME_CONSISTENCY] =
                        SleepSubScores.bedtimeConsistency(bedtimeSd, config.sleep.targets)
                }
            }

            bundles += DayBundle(
                date = date,
                values = values,
                night = night,
                activity = DayActivity(
                    date = key,
                    workouts = exercisesByDate[key].orEmpty().map(::toWorkout),
                    wakingHours = wakingHours(night),
                ),
            )
        }

        return bundles
    }

    /**
     * Hours awake, for the passive-load term.
     *
     * Derived from the night when there is one and left at the engine's default of
     * sixteen when there is not — a day with no sleep tracking is not a day the
     * user spent unconscious.
     */
    private fun wakingHours(night: NightSummary): Double =
        if (night.hasSession && night.inBedMinutes > 0.0) {
            (24.0 - night.inBedMinutes / 60.0).coerceIn(4.0, 22.0)
        } else {
            16.0
        }

    private fun toWorkout(entity: ExerciseSessionEntity): WorkoutSample = WorkoutSample(
        id = entity.originId.ifBlank { entity.id.toString() },
        durationMinutes = (entity.endEpochMs - entity.startEpochMs) / 60_000.0,
        meanHeartRate = entity.meanHeartRate,
        maxHeartRate = entity.maxHeartRate,
        title = entity.title,
    )

    private fun trailingNights(date: LocalDate, nights: Map<String, NightSummary>): List<NightSummary> =
        (1..circadianWindowNights)
            .mapNotNull { back -> nights[date.minusDays(back.toLong()).toString()] }
            .filter { it.hasSession }

    /**
     * Percent drop from the day's resting heart rate to the sleeping heart rate.
     *
     * Only computable when raw heart rate happens to be in the store for the sleep
     * window. The sync layer does not pull continuous heart rate by default — a
     * month of it is hundreds of thousands of rows — so on most devices this
     * contributor is simply absent, which the Bevel profile handles by
     * renormalising it away.
     */
    private fun heartRateDip(
        date: String,
        night: NightSummary,
        samples: Map<Pair<String, String>, List<MetricSampleEntity>>,
        restingHeartRate: Double?,
    ): Double? {
        if (restingHeartRate == null || restingHeartRate <= 0.0) return null
        val start = night.startEpochMs ?: return null
        val end = night.endEpochMs ?: return null
        val inWindow = (samples[Metric.HEART_RATE.key to date].orEmpty() +
            samples[Metric.HEART_RATE.key to previousDay(date)].orEmpty())
            .filter { it.startEpochMs in start..end }
        if (inWindow.isEmpty()) return null
        val sleeping = inWindow.map { it.value }.average()
        return 100.0 * (restingHeartRate - sleeping) / restingHeartRate
    }

    private fun previousDay(date: String): String = LocalDate.parse(date).minusDays(1).toString()

    /**
     * A value measured during the night, or close enough to it to be a nightly
     * aggregate. Null when the only readings were taken during the day.
     */
    private fun nightlyValue(
        metric: Metric,
        date: String,
        night: NightSummary,
        samples: Map<Pair<String, String>, List<MetricSampleEntity>>,
    ): Double? {
        val candidates = samples[metric.key to date].orEmpty() +
            samples[metric.key to previousDay(date)].orEmpty()
        if (candidates.isEmpty()) return null

        val preferred = resolveSource(candidates)

        val start = night.startEpochMs
        val end = night.endEpochMs
        if (start != null && end != null) {
            val inWindow = preferred.filter { it.startEpochMs in start..end }
            if (inWindow.isNotEmpty()) return inWindow.map { it.value }.average()
        }

        // No sleep window, or nothing inside it. Accept readings stamped in the
        // night hours — many apps write the night's aggregate at wake time, some at
        // midnight — and drop anything else rather than feeding a daytime spot
        // reading into a score that claims to be about your night.
        val previous = previousDay(date)
        val nightHours = preferred.filter { row ->
            val hour = localHour(row)
            (row.localDate == date && hour < NIGHT_END_HOUR) ||
                (row.localDate == previous && hour >= NIGHT_START_HOUR)
        }
        return if (nightHours.isEmpty()) null else nightHours.map { it.value }.average()
    }

    private fun localHour(sample: MetricSampleEntity): Int =
        Instant.ofEpochMilli(sample.startEpochMs)
            .atOffset(ZoneOffset.ofTotalSeconds(sample.zoneOffsetSeconds))
            .hour

    private fun dailyValue(
        metric: Metric,
        date: String,
        samples: Map<Pair<String, String>, List<MetricSampleEntity>>,
    ): Double? {
        val rows = samples[metric.key to date].orEmpty()
        if (rows.isEmpty()) return null
        return resolveSource(rows).map { it.value }.average()
    }

    /**
     * Duplicate resolution. The first source in the user's priority list that wrote
     * anything wins the whole day for that metric; without a list, the sources are
     * ranked by the order they appear, which is the order the probe reported them.
     */
    private fun resolveSource(rows: List<MetricSampleEntity>): List<MetricSampleEntity> {
        val bySource = rows.groupBy { it.sourceApp }
        if (bySource.size <= 1) return rows
        val winner = sourcePriority.firstOrNull { bySource.containsKey(it) } ?: bySource.keys.first()
        return bySource[winner] ?: rows
    }

    private companion object {
        /** Evening boundary for "this reading belongs to the night". */
        const val NIGHT_START_HOUR = 20

        /** Morning boundary. Generous, because watches upload late. */
        const val NIGHT_END_HOUR = 11
    }
}
