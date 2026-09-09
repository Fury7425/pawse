package app.pawse.scoring.model

/**
 * One night/day of already-normalised inputs handed to the scorers.
 *
 * The engine never touches Health Connect, a clock, or a database. Everything it
 * needs arrives in this object, which is why the whole of :core-scoring is
 * testable against synthetic fixtures.
 *
 * A null value means "this writer app did not provide it". It does NOT mean zero
 * and it must never be replaced by a population default: the term is dropped and
 * the remaining weights are renormalised.
 */
data class DailyInputs(
    /** Local date the night is attributed to (the wake date). */
    val date: String,
    val values: Map<Metric, Double>,
    /** True when the sleep session carried stage data. Selects the sleep profile. */
    val hasSleepStages: Boolean,
    /** True when a primary sleep session existed at all. */
    val hasSleepSession: Boolean,
    /** Set when the night crossed a UTC-offset change, so timing terms are suppressed. */
    val timezoneShiftMinutes: Int = 0,
    val napMinutes: Int = 0,
) {
    operator fun get(metric: Metric): Double? = values[metric]
    fun has(metric: Metric): Boolean = values.containsKey(metric)
}

/** Per-metric history the BaselineEngine consumes. Oldest first. */
data class MetricHistory(
    val metric: Metric,
    /** (daysAgo, value) pairs. daysAgo = 0 is tonight. Gaps are simply absent. */
    val samples: List<Sample>,
) {
    data class Sample(val daysAgo: Int, val value: Double)
}

/** Output of the BaselineEngine for one metric. */
data class Baseline(
    val metric: Metric,
    val mean: Double,
    val sigma: Double,
    val sampleCount: Int,
    val windowDays: Int,
    val warmingUp: Boolean,
)
