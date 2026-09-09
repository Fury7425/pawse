package app.pawse.scoring.baseline

import app.pawse.scoring.config.BaselineConfig
import app.pawse.scoring.config.BaselineMode
import app.pawse.scoring.model.Baseline
import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.MetricHistory
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Personal rolling baselines.
 *
 * This is the one piece every report agrees on. grok.txt closes with it as the
 * rule that survives every vendor: "Overnight HRV and RHR versus *your* baseline".
 * claude.md §11 lists the weighting functions as the universal secret and the
 * baseline as the universal method. So the baseline is not an implementation
 * detail here; it is the product.
 *
 * Three decisions worth stating, because they are all reversible in config and
 * all defensible in either direction:
 *
 *  1. **Tonight is excluded.** Only samples at `daysAgo >= 1` form the baseline.
 *     Including tonight would drag the mean toward the value being judged and
 *     shrink every z-score, which is exactly wrong on the nights that matter.
 *  2. **Recency weighted, exponentially.** A 60-day window with a 14-day
 *     half-life keeps two months of context without letting a training block
 *     from March judge a night in May. Vendor windows in the reports run 14 to 60
 *     days (Whoop ~30, Bevel/Athlytic 60, Oura 14-day vs ~2-month, Polar 28);
 *     60 with decay covers the family rather than picking one member.
 *  3. **A degenerate sigma yields no z, not an infinite one.** A user whose HRV
 *     was identical for sixty nights is a sensor fault, not a superhuman. The
 *     term is dropped and the weight is renormalised away.
 */
class BaselineEngine(private val config: BaselineConfig) {

    /**
     * @return null when the window holds no usable sample at all. A baseline that
     *   exists but is under [BaselineConfig.warmupSamples] is returned with
     *   [Baseline.warmingUp] set — the caller decides whether to drop it, and both
     *   scorers do.
     */
    fun baseline(history: MetricHistory): Baseline? {
        val samples = history.samples
            .filter { it.daysAgo in 1..config.windowDays }
            // One value per day. Two writer apps mirroring the same watch would
            // otherwise double the weight of whichever day they both covered.
            .groupBy { it.daysAgo }
            .map { (daysAgo, dupes) -> daysAgo to dupes.map { it.value }.average() }
            .sortedBy { it.first }
        if (samples.isEmpty()) return null

        val decay = ln(2.0) / config.halfLifeDays
        val weights = samples.map { exp(-decay * it.first) }
        val values = samples.map { it.second }

        val (centre, sigma) = when (config.mode) {
            BaselineMode.MEAN_SD -> meanAndSd(values, weights)
            BaselineMode.MEDIAN_MAD -> medianAndMadSigma(values, weights)
        }

        return Baseline(
            metric = history.metric,
            mean = centre,
            sigma = sigma,
            sampleCount = samples.size,
            windowDays = config.windowDays,
            warmingUp = samples.size < config.warmupSamples,
        )
    }

    fun baselines(histories: Collection<MetricHistory>): Map<Metric, Baseline> =
        histories.mapNotNull { baseline(it)?.let { b -> it.metric to b } }.toMap()

    /**
     * Signed, direction-applied, clamped z-score. Null when the baseline cannot
     * support one.
     *
     * Sign convention: positive is always "better than your baseline", whatever
     * the metric's physical direction. A resting heart rate 8 bpm above your
     * baseline returns a negative number, and so does a wrist temperature 0.4 °C
     * *below* it, because [Metric.deviationOnly] metrics are bad in both
     * directions. That inversion is the reason the UI never labels a z-score with
     * a raw unit.
     */
    fun z(baseline: Baseline, raw: Double): Double? {
        if (!baseline.sigma.isFinite() || baseline.sigma <= SIGMA_FLOOR) return null
        val deviation = raw - baseline.mean
        val signed = if (baseline.metric.deviationOnly) {
            -abs(deviation) / baseline.sigma
        } else {
            baseline.metric.direction * deviation / baseline.sigma
        }
        return signed.coerceIn(-config.zClamp, config.zClamp)
    }

    /**
     * Unsigned magnitude of tonight's deviation, in sigmas. Used by the anomaly
     * flags, which fire on distance from baseline rather than on direction.
     */
    fun deviationSigma(baseline: Baseline, raw: Double): Double? {
        if (!baseline.sigma.isFinite() || baseline.sigma <= SIGMA_FLOOR) return null
        return abs(raw - baseline.mean) / baseline.sigma
    }

    private fun meanAndSd(values: List<Double>, weights: List<Double>): Pair<Double, Double> {
        val sumW = weights.sum()
        val mean = values.indices.sumOf { values[it] * weights[it] } / sumW
        if (values.size < 2) return mean to 0.0
        val sumW2 = weights.sumOf { it * it }
        val ss = values.indices.sumOf { weights[it] * (values[it] - mean).let { d -> d * d } }
        // Reliability-weighted unbiased variance. Falls back to the biased form
        // when the effective sample size is degenerate (one dominant weight).
        val denominator = sumW - sumW2 / sumW
        val variance = if (denominator > 1e-9) ss / denominator else ss / sumW
        return mean to sqrt(variance)
    }

    /**
     * Median and MAD, both weighted. sigma = 1.4826 x MAD is the standard
     * consistency constant that makes MAD-derived sigma match SD for normal data.
     *
     * This mode exists for the loose-strap night. A single 10 ms RMSSD reading
     * from a slipped watch (grok.txt §12 warns about exactly this) moves a mean
     * and barely moves a median.
     */
    private fun medianAndMadSigma(values: List<Double>, weights: List<Double>): Pair<Double, Double> {
        val median = weightedMedian(values, weights)
        if (values.size < 2) return median to 0.0
        val deviations = values.map { abs(it - median) }
        val mad = weightedMedian(deviations, weights)
        return median to MAD_TO_SIGMA * mad
    }

    private fun weightedMedian(values: List<Double>, weights: List<Double>): Double {
        val order = values.indices.sortedBy { values[it] }
        val half = weights.sum() / 2.0
        var running = 0.0
        for (i in order) {
            running += weights[i]
            if (running >= half) return values[i]
        }
        return values[order.last()]
    }

    private companion object {
        /** Below this, sigma is a sensor artefact and no z-score is meaningful. */
        const val SIGMA_FLOOR = 1e-6
        const val MAD_TO_SIGMA = 1.4826
    }
}
