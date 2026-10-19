package app.pawse.scoring.learning

import app.pawse.scoring.body.BodyMath
import app.pawse.scoring.config.LearningConfig
import app.pawse.scoring.math.Stats
import app.pawse.scoring.model.Insight
import app.pawse.scoring.model.Predictor
import kotlin.math.sqrt

/**
 * Personal dose-response: what predicts the next morning's raw Body signal?
 *
 *   z_t+1 = b0 + b1 ln(1 + L_t / L~) + b2 D_t + b3 Alc_t + b4 Caf_bed,t + b5 LateMeal_t + e
 *
 * Ridge regression keeps each beta small unless the data clearly say otherwise.
 * An insight appears only after enough exposure days and when its 90% interval
 * excludes zero. These are associations in one person's data, not causes.
 */
object DoseResponse {

    /**
     * @param x one row per morning, columns in [Predictor] order, each already in
     *   the unit an insight is reported in (one drink, one hour of debt...).
     * @param y the unfiltered Body signal that morning, in SDs.
     */
    fun fit(x: List<DoubleArray>, y: List<Double>, config: LearningConfig): List<Insight> {
        val n = x.size
        val p = Predictor.entries.size
        if (n < config.minRows || n != y.size) return emptyList()

        val means = DoubleArray(p) { j -> x.sumOf { it[j] } / n }
        val sds = DoubleArray(p) { j -> sqrt(x.sumOf { (it[j] - means[j]).let { d -> d * d } } / n) }
        val my = y.average()
        // A predictor that never varied carries no information and is left at zero.
        val xs = x.map { row -> DoubleArray(p) { j -> if (sds[j] > 1e-9) (row[j] - means[j]) / sds[j] else 0.0 } }
        val yc = y.map { it - my }

        val xtx = Array(p) { i -> DoubleArray(p) { j -> xs.sumOf { it[i] * it[j] } } }
        val a = Array(p) { i -> DoubleArray(p) { j -> xtx[i][j] + if (i == j) config.ridgeLambda else 0.0 } }
        val aInv = Stats.invert(a) ?: return emptyList()
        val xty = DoubleArray(p) { j -> xs.indices.sumOf { r -> xs[r][j] * yc[r] } }
        val beta = DoubleArray(p) { i -> (0 until p).sumOf { j -> aInv[i][j] * xty[j] } }

        val rss = xs.indices.sumOf { r ->
            val fitted = (0 until p).sumOf { j -> xs[r][j] * beta[j] }
            (yc[r] - fitted).let { it * it }
        }
        val sigma2 = rss / (n - p - 1).coerceAtLeast(1)
        // Cov(beta) = sigma^2 A^-1 X'X A^-1
        val middle = Array(p) { i -> DoubleArray(p) { j -> (0 until p).sumOf { k -> aInv[i][k] * xtx[k][j] } } }
        val cov = Array(p) { i -> DoubleArray(p) { j -> sigma2 * (0 until p).sumOf { k -> middle[i][k] * aInv[k][j] } } }

        return Predictor.entries.mapNotNull { predictor ->
            val j = predictor.ordinal
            if (sds[j] <= 1e-9) return@mapNotNull null
            val exposure = x.count { it[j] > EXPOSED[j] }
            if (exposure < config.minExposureDays) return@mapNotNull null
            val b = beta[j] / sds[j]
            val se = sqrt(cov[j][j].coerceAtLeast(0.0)) / sds[j]
            val low = b - config.intervalZ * se
            val high = b + config.intervalZ * se
            if (low <= 0.0 && high >= 0.0) return@mapNotNull null
            Insight(predictor, b, low, high, exposure)
        }
    }

    /**
     * What counts as an exposure day, per predictor, in its reporting unit: any
     * training, over an hour of debt, any drink, 25 mg of caffeine left at bedtime,
     * 100 kcal within three hours of sleep.
     */
    private val EXPOSED = doubleArrayOf(0.0, 1.0, 0.0, 0.25, 0.2)
}

/**
 * Check-in calibration. The check-in never feeds a score, only these two numbers,
 * so Body cannot turn into a mirror of your mood.
 *
 *   w_user = clip((n w_fit + 30 x 0.6) / (n + 30), 0.4, 0.8)
 */
object CheckInCalibration {

    /** HRV's weight, refit to whatever best tracks this user's check-ins, then shrunk. */
    fun hrvWeight(
        zh: List<Double?>,
        zr: List<Double?>,
        rho: List<Double>,
        checkIns: List<Double>,
        default: Double,
        config: LearningConfig,
    ): Double {
        val n = checkIns.size
        if (n < config.calibrationMinCheckIns) return default
        val fit = (0..20).map { it / 20.0 }.maxByOrNull { w ->
            val pairs = checkIns.indices.mapNotNull { i ->
                BodyMath.combine(zh[i], zr[i], w, rho[i])?.let { it to checkIns[i] }
            }
            Stats.pearson(pairs.map { it.first }, pairs.map { it.second }) ?: -1.0
        } ?: default
        return shrink(fit, default, n, config).coerceIn(config.wMin, config.wMax)
    }

    /**
     * How strongly sleep debt lowers the energy curve: the slope of check-in on
     * debt, in curve units, shrunk toward the default the same way.
     */
    fun kappa(debts: List<Double>, checkIns: List<Double>, default: Double, config: LearningConfig): Double {
        val n = checkIns.size
        if (n < config.calibrationMinCheckIns) return default
        val md = debts.average()
        val mc = checkIns.average()
        val sxx = debts.sumOf { (it - md) * (it - md) }
        if (sxx <= 1e-9) return default
        val slope = debts.indices.sumOf { (debts[it] - md) * (checkIns[it] - mc) } / sxx
        val fit = maxOf(0.0, -slope) * config.curveUnitsPerCheckInPoint
        return shrink(fit, default, n, config).coerceIn(0.0, 3 * default)
    }

    private fun shrink(fit: Double, default: Double, n: Int, config: LearningConfig): Double =
        (n * fit + config.priorCheckIns * default) / (n + config.priorCheckIns)
}
