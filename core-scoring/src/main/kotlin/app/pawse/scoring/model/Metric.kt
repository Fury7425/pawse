package app.pawse.scoring.model

/**
 * Every physiological input the engine can consume.
 *
 * [direction] is the sign applied to the z-score before weighting: +1 when
 * higher-than-baseline is better, -1 when higher-than-baseline is worse.
 * The reports agree on direction for every one of these even where they cannot
 * agree on weight.
 *
 * [deviationOnly] metrics are scored on |x - mu| rather than signed z, because
 * both directions are bad. Temperature is the canonical case: the reports treat
 * a wrist-temperature excursion in either direction as an anomaly flag, never as
 * a continuous "warmer is worse" term.
 */
enum class Metric(
    val direction: Int,
    val deviationOnly: Boolean = false,
    val unit: String,
) {
    /**
     * Overnight RMSSD. Health Connect exposes HRV only as
     * HeartRateVariabilityRmssdRecord — there is no SDNN record type — so RMSSD
     * is the sole path on Android. This matches Bevel's default and every
     * recovery-relevant recommendation in the reports (claude.md §9, §12:
     * "RMSSD during stable sleep is the most reliable window").
     */
    HRV_RMSSD(direction = +1, unit = "ms"),

    /** Sleeping / resting heart rate. Elevated vs baseline is worse. */
    RESTING_HEART_RATE(direction = -1, unit = "bpm"),

    /** Last night's Sleep Score, fed forward into Recovery as one term. */
    SLEEP_SCORE(direction = +1, unit = "pts"),

    /**
     * Overnight respiratory rate. Whoop penalises elevations >1.0 breaths/min
     * above baseline as an early respiratory-illness signal (gemini.txt).
     * We never call it illness — copy says "outside your usual range".
     */
    RESPIRATORY_RATE(direction = -1, unit = "br/min"),

    /** Wrist / skin temperature. Anomaly in either direction. */
    SKIN_TEMPERATURE(direction = -1, deviationOnly = true, unit = "degC"),

    /** Overnight SpO2. Low is the anomaly; high saturates and carries no signal. */
    SPO2(direction = +1, unit = "%"),

    // --- Inputs used by Sleep, Strain, Energy Bank rather than Recovery ---
    TIME_ASLEEP(direction = +1, unit = "min"),
    SLEEP_EFFICIENCY(direction = +1, unit = "%"),
    SLEEP_LATENCY(direction = -1, unit = "min"),
    REM_MINUTES(direction = +1, unit = "min"),
    DEEP_MINUTES(direction = +1, unit = "min"),
    RESTFULNESS(direction = +1, unit = "pts"),
    SLEEP_TIMING(direction = +1, unit = "pts"),
    BEDTIME_CONSISTENCY(direction = +1, unit = "pts"),
    SLEEP_INTERRUPTIONS(direction = -1, unit = "count"),
    HEART_RATE_DIP(direction = +1, unit = "%"),
    ;

    val key: String get() = name
}
