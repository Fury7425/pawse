package app.pawse.scoring.fixtures

import app.pawse.scoring.config.BaselineConfig
import app.pawse.scoring.model.BiologicalSex
import app.pawse.scoring.model.DailyInputs
import app.pawse.scoring.model.DailyLoad
import app.pawse.scoring.model.DayActivity
import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.MetricHistory
import app.pawse.scoring.model.UserProfile
import app.pawse.scoring.model.WorkoutSample
import kotlin.math.exp
import kotlin.math.ln

/**
 * Six synthetic nights.
 *
 * Every magnitude here comes from a report rather than from whatever made the
 * assertions pass. The alcohol night in particular is lifted straight out of
 * gemini.txt's "Single Signal, Multiple Masks" section — nocturnal RMSSD down
 * about 30%, resting heart rate up about 8 bpm — because that is the one worked
 * example any of the five reports gives with numbers attached, and it is also the
 * case grok.txt §12 uses to explain why four devices disagree about one night.
 *
 * The fixtures exist to pin behaviour, not arithmetic. Exact-value assertions
 * belong in the unit tests next to each component; what these nights are for is
 * the question the whole app answers: does a night that physiology says was bad
 * come out red, and does the score say honestly why.
 */
object Fixtures {

    /** The baseline config every fixture history is centred against. */
    val BASELINE: BaselineConfig = BaselineConfig()

    /**
     * A fixed ten-day z-pattern, mean 0 and population SD 1. Deterministic on
     * purpose: a random generator in a fixture is a flaky test waiting for a
     * CI machine.
     */
    private val PATTERN = listOf(
        -1.823, -1.042, -0.391, 0.130, 0.521,
        -0.651, 1.172, 1.693, -0.260, 0.651,
    )

    /**
     * A history whose recency-weighted mean is exactly [mean].
     *
     * The centring step matters. Without it the engine's exponential weighting
     * pulls the baseline toward whichever pattern value happens to land on
     * yesterday, and every fixture's z-score becomes an accident of the phase of
     * a hard-coded list. With it, "tonight is 8 bpm above baseline" means exactly
     * that, and the test can say so.
     */
    fun history(
        metric: Metric,
        mean: Double,
        sd: Double,
        days: Int = 60,
        config: BaselineConfig = BASELINE,
    ): MetricHistory {
        val decay = ln(2.0) / config.halfLifeDays
        val daysAgo = (1..days).toList()
        val z = daysAgo.map { PATTERN[(it - 1) % PATTERN.size] }
        val w = daysAgo.map { exp(-decay * it) }
        val weightedMeanZ = z.indices.sumOf { z[it] * w[it] } / w.sum()
        return MetricHistory(
            metric = metric,
            samples = daysAgo.mapIndexed { i, d ->
                MetricHistory.Sample(daysAgo = d, value = mean + sd * (z[i] - weightedMeanZ))
            },
        )
    }

    // --- Personal baselines shared by every night --------------------------
    // Chosen near Whoop's published population averages where one exists:
    // sleeping HRV ~65 ms and ~58% average recovery (claude.md §2).

    const val HRV_MEAN = 65.0
    const val HRV_SD = 12.0
    const val RHR_MEAN = 52.0
    const val RHR_SD = 3.5
    const val RESP_MEAN = 14.5
    const val RESP_SD = 0.8
    const val SLEEP_SCORE_MEAN = 88.0
    const val SLEEP_SCORE_SD = 6.0
    /** Skin temperature is stored as a signed delta in Celsius, so the mean is 0. */
    const val TEMP_MEAN = 0.0
    const val TEMP_SD = 0.25
    const val SPO2_MEAN = 96.0
    const val SPO2_SD = 1.2

    fun histories(days: Int = 60): Map<Metric, MetricHistory> = listOf(
        history(Metric.HRV_RMSSD, HRV_MEAN, HRV_SD, days),
        history(Metric.RESTING_HEART_RATE, RHR_MEAN, RHR_SD, days),
        history(Metric.RESPIRATORY_RATE, RESP_MEAN, RESP_SD, days),
        history(Metric.SLEEP_SCORE, SLEEP_SCORE_MEAN, SLEEP_SCORE_SD, days),
        history(Metric.SKIN_TEMPERATURE, TEMP_MEAN, TEMP_SD, days),
        history(Metric.SPO2, SPO2_MEAN, SPO2_SD, days),
    ).associateBy { it.metric }

    /** One fixture: the night, its history, and why it is in the suite. */
    data class Night(
        val name: String,
        val note: String,
        val inputs: DailyInputs,
        val histories: Map<Metric, MetricHistory>,
    )

    // ----------------------------------------------------------------------
    // 1. A good, unremarkable night. Everything cardiac sits on the baseline.
    // ----------------------------------------------------------------------
    val typicalGoodNight = Night(
        name = "typical good night",
        note = "Cardiac signals exactly at baseline, sleep a little better than usual. " +
            "The control case: Recovery should land near the middle of the scale.",
        inputs = DailyInputs(
            date = "2026-03-02",
            values = mapOf(
                Metric.HRV_RMSSD to HRV_MEAN,
                Metric.RESTING_HEART_RATE to RHR_MEAN,
                Metric.RESPIRATORY_RATE to RESP_MEAN,
                Metric.SKIN_TEMPERATURE to TEMP_MEAN,
                Metric.SPO2 to SPO2_MEAN,
                Metric.TIME_ASLEEP to 445.0,
                Metric.SLEEP_EFFICIENCY to 89.0,
                Metric.REM_MINUTES to 95.0,
                Metric.DEEP_MINUTES to 62.0,
                Metric.SLEEP_LATENCY to 18.0,
                Metric.RESTFULNESS to 80.0,
                Metric.SLEEP_TIMING to 84.0,
                Metric.BEDTIME_CONSISTENCY to 86.0,
                Metric.SLEEP_INTERRUPTIONS to 1.0,
                Metric.HEART_RATE_DIP to 16.0,
            ),
            hasSleepStages = true,
            hasSleepSession = true,
        ),
        histories = histories(),
    )

    // ----------------------------------------------------------------------
    // 2. The alcohol night. The one worked example the reports actually quantify.
    // ----------------------------------------------------------------------
    val alcoholNight = Night(
        name = "alcohol night",
        note = "gemini.txt: two drinks late drops nocturnal RMSSD by ~30% and lifts " +
            "resting HR by ~8 bpm. Duration is untouched. This is the night where " +
            "Apple's duration-only score and Whoop's HRV-led score disagree by " +
            "sixty points about the same eight hours (grok.txt §12).",
        inputs = DailyInputs(
            date = "2026-03-03",
            values = mapOf(
                Metric.HRV_RMSSD to HRV_MEAN * 0.70,
                Metric.RESTING_HEART_RATE to RHR_MEAN + 8.0,
                Metric.RESPIRATORY_RATE to RESP_MEAN + 0.6,
                Metric.SKIN_TEMPERATURE to TEMP_MEAN + 0.20,
                Metric.SPO2 to SPO2_MEAN - 0.5,
                Metric.TIME_ASLEEP to 440.0,
                Metric.SLEEP_EFFICIENCY to 84.0,
                Metric.REM_MINUTES to 80.0,
                Metric.DEEP_MINUTES to 40.0,
                Metric.SLEEP_LATENCY to 25.0,
                Metric.RESTFULNESS to 62.0,
                Metric.SLEEP_TIMING to 78.0,
                Metric.BEDTIME_CONSISTENCY to 84.0,
                Metric.SLEEP_INTERRUPTIONS to 3.0,
                Metric.HEART_RATE_DIP to 7.0,
            ),
            hasSleepStages = true,
            hasSleepSession = true,
        ),
        histories = histories(),
    )

    // ----------------------------------------------------------------------
    // 3. Both bounded flags fire. Never described as illness anywhere in the app.
    // ----------------------------------------------------------------------
    val flaggedNight = Night(
        name = "flagged night",
        note = "Respiratory rate up 1.6 br/min, wrist temperature ~2 sigma off, SpO2 " +
            "~2 sigma low. Both anomaly flags should fire and take exactly their " +
            "capped points, and the copy must say 'outside your usual range'.",
        inputs = DailyInputs(
            date = "2026-03-04",
            values = mapOf(
                Metric.HRV_RMSSD to HRV_MEAN - 10.0,
                Metric.RESTING_HEART_RATE to RHR_MEAN + 4.0,
                Metric.RESPIRATORY_RATE to RESP_MEAN + 1.6,
                Metric.SKIN_TEMPERATURE to TEMP_MEAN + 0.55,
                Metric.SPO2 to SPO2_MEAN - 2.5,
                Metric.TIME_ASLEEP to 470.0,
                Metric.SLEEP_EFFICIENCY to 88.0,
                Metric.REM_MINUTES to 88.0,
                Metric.DEEP_MINUTES to 66.0,
                Metric.SLEEP_LATENCY to 14.0,
                Metric.RESTFULNESS to 74.0,
                Metric.SLEEP_TIMING to 88.0,
                Metric.BEDTIME_CONSISTENCY to 88.0,
                Metric.SLEEP_INTERRUPTIONS to 2.0,
                Metric.HEART_RATE_DIP to 12.0,
            ),
            hasSleepStages = true,
            hasSleepSession = true,
        ),
        histories = histories(),
    )

    // ----------------------------------------------------------------------
    // 4. Day six of ownership. There is no baseline yet and no score.
    // ----------------------------------------------------------------------
    val warmUpNight = Night(
        name = "warm-up night",
        note = "Six nights of history against a 14-night warm-up floor. Recovery must " +
            "refuse rather than print a number derived from six samples. Reports put " +
            "vendor calibration at 2-6 weeks; a z-score over six nights is noise.",
        inputs = typicalGoodNight.inputs.copy(date = "2026-01-08"),
        histories = histories(days = 6),
    )

    // ----------------------------------------------------------------------
    // 5. A writer app that reports asleep/awake and nothing else.
    // ----------------------------------------------------------------------
    val stagelessNight = Night(
        name = "stageless night",
        note = "No stage data, so the Oura combiner cannot run and Apple's published " +
            "50/30/20 takes over — which is the reason that profile is in the app.",
        inputs = DailyInputs(
            date = "2026-03-05",
            values = mapOf(
                Metric.HRV_RMSSD to HRV_MEAN + 6.0,
                Metric.RESTING_HEART_RATE to RHR_MEAN - 1.0,
                Metric.RESPIRATORY_RATE to RESP_MEAN,
                Metric.TIME_ASLEEP to 430.0,
                Metric.SLEEP_EFFICIENCY to 91.0,
                Metric.BEDTIME_CONSISTENCY to 82.0,
                Metric.SLEEP_INTERRUPTIONS to 2.0,
            ),
            hasSleepStages = false,
            hasSleepSession = true,
        ),
        histories = histories(),
    )

    // ----------------------------------------------------------------------
    // 6. A chest strap worn overnight for HRV, with no sleep tracking at all.
    // ----------------------------------------------------------------------
    val partialCoverageNight = Night(
        name = "partial coverage night",
        note = "HRV only, no sleep session. Weight renormalises onto the one term " +
            "present, coverage reports 0.60, and the score is marked degraded " +
            "rather than quietly presented as if six signals had agreed. Sleep " +
            "refuses outright: there is no night to score.",
        inputs = DailyInputs(
            date = "2026-03-06",
            values = mapOf(
                Metric.HRV_RMSSD to HRV_MEAN - 14.0,
            ),
            hasSleepStages = false,
            hasSleepSession = false,
        ),
        histories = histories(),
    )

    val all: List<Night> = listOf(
        typicalGoodNight,
        alcoholNight,
        flaggedNight,
        warmUpNight,
        stagelessNight,
        partialCoverageNight,
    )

    // ======================================================================
    // Activity fixtures, for Strain / Energy Bank / Load Ratio.
    // ======================================================================

    /**
     * A user who told us enough for Banister's published coefficients.
     * Resting heart rate matches [RHR_MEAN], so the strain and recovery sides of
     * the engine are describing the same person.
     */
    val profile = UserProfile(
        ageYears = 35,
        biologicalSex = BiologicalSex.MALE,
        restingHeartRate = RHR_MEAN,
    )

    /** The same person, with nothing filled in. Tanaka cannot run without an age. */
    val anonymousProfile = UserProfile()

    /** Nothing logged. Passive load only, which is still not zero. */
    val restDay = DayActivity(date = "2026-03-02", workouts = emptyList(), wakingHours = 16.0)

    /** One hard hour. The single-session reference case. */
    val hardSessionDay = DayActivity(
        date = "2026-03-03",
        workouts = listOf(
            WorkoutSample(
                id = "w-1",
                durationMinutes = 60.0,
                meanHeartRate = 150.0,
                maxHeartRate = 176.0,
                title = "Threshold run",
            ),
        ),
        wakingHours = 16.0,
    )

    /**
     * Two sessions of identical load. The non-additivity fixture: the day must
     * score well below twice either session, with no special case in the code.
     */
    val doubleSessionDay = DayActivity(
        date = "2026-03-04",
        workouts = listOf(
            WorkoutSample("w-2a", 60.0, 150.0, 176.0, title = "Morning run"),
            WorkoutSample("w-2b", 60.0, 150.0, 176.0, title = "Evening run"),
        ),
        wakingHours = 16.0,
    )

    /** A writer app that logged a ride and no heart rate. Half the day is invisible. */
    val heartRatelessDay = DayActivity(
        date = "2026-03-05",
        workouts = listOf(
            WorkoutSample("w-3a", 60.0, 150.0, 176.0, title = "Run with HR"),
            WorkoutSample("w-3b", 90.0, null, null, title = "Ride, no HR"),
        ),
        wakingHours = 16.0,
    )

    /** A writer app that supplied a heart-rate series we could bin. Edwards applies. */
    val zonedDay = DayActivity(
        date = "2026-03-06",
        workouts = listOf(
            WorkoutSample(
                id = "w-4",
                durationMinutes = 60.0,
                meanHeartRate = 150.0,
                maxHeartRate = 176.0,
                zoneMinutes = mapOf(1 to 10.0, 2 to 15.0, 3 to 20.0, 4 to 12.0, 5 to 3.0),
                title = "Fartlek",
            ),
        ),
        wakingHours = 16.0,
    )

    /**
     * A flat daily-load series. Acute and chronic EWMAs converge on the same
     * number, so the ratio is exactly 1.0 — the control case for the load ratio.
     */
    fun steadyLoads(days: Int = 60, load: Double = 120.0): List<DailyLoad> =
        (0 until days).map { DailyLoad(daysAgo = it, load = load) }

    /**
     * A block: [recentDays] at [recentLoad], everything older at [baseLoad].
     * Ramping up puts the ratio above one, tapering puts it below.
     */
    fun blockLoads(
        days: Int = 60,
        recentDays: Int = 7,
        recentLoad: Double = 240.0,
        baseLoad: Double = 120.0,
    ): List<DailyLoad> = (0 until days).map {
        DailyLoad(daysAgo = it, load = if (it < recentDays) recentLoad else baseLoad)
    }
}
