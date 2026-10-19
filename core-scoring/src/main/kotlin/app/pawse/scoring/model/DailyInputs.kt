package app.pawse.scoring.model

/** Per-metric history the BaselineEngine consumes. Oldest first. */
data class MetricHistory(
    val metric: Metric,
    /** (daysAgo, value) pairs. daysAgo = 0 is today. Gaps are simply absent. */
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
