package app.pawse.scoring.math

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** The small amount of statistics the engine needs. No library, no surprises. */
object Stats {

    const val MAD_TO_SIGMA = 1.4826

    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    /** 1.4826 x MAD: matches the SD for normal data and ignores the one bad night. */
    fun robustSpread(values: List<Double>): Double? {
        val m = median(values) ?: return null
        return median(values.map { abs(it - m) })?.times(MAD_TO_SIGMA)
    }

    fun mean(values: List<Double>): Double? = if (values.isEmpty()) null else values.average()

    fun sd(values: List<Double>): Double? {
        if (values.size < 2) return null
        val m = values.average()
        return sqrt(values.sumOf { (it - m) * (it - m) } / (values.size - 1))
    }

    fun pearson(x: List<Double>, y: List<Double>): Double? {
        if (x.size != y.size || x.size < 3) return null
        val mx = x.average()
        val my = y.average()
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for (i in x.indices) {
            val dx = x[i] - mx
            val dy = y[i] - my
            sxy += dx * dy
            sxx += dx * dx
            syy += dy * dy
        }
        if (sxx <= 0.0 || syy <= 0.0) return null
        return sxy / sqrt(sxx * syy)
    }

    /** Pearson on ranks, ties averaged. */
    fun spearman(x: List<Double>, y: List<Double>): Double? = pearson(ranks(x), ranks(y))

    private fun ranks(values: List<Double>): List<Double> {
        val order = values.indices.sortedBy { values[it] }
        val out = DoubleArray(values.size)
        var i = 0
        while (i < order.size) {
            var j = i
            while (j + 1 < order.size && values[order[j + 1]] == values[order[i]]) j++
            val rank = (i + j) / 2.0 + 1.0
            for (k in i..j) out[order[k]] = rank
            i = j + 1
        }
        return out.toList()
    }

    /** Standard normal CDF, Abramowitz-Stegun 7.1.26 on erf. Error under 1.5e-7. */
    fun phi(z: Double): Double {
        val x = abs(z) / sqrt(2.0)
        val t = 1.0 / (1.0 + 0.3275911 * x)
        val poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))))
        val erf = 1.0 - poly * exp(-x * x)
        return if (z >= 0) 0.5 * (1.0 + erf) else 0.5 * (1.0 - erf)
    }

    /** Mean of clock times in minutes since midnight. 23:50 and 00:10 average to midnight. */
    fun circularMeanMinutes(minutes: List<Double>): Double? {
        if (minutes.isEmpty()) return null
        var s = 0.0
        var c = 0.0
        for (m in minutes) {
            val a = 2 * PI * m / DAY_MINUTES
            s += sin(a)
            c += cos(a)
        }
        if (abs(s) < 1e-12 && abs(c) < 1e-12) return null
        val mean = atan2(s, c) / (2 * PI) * DAY_MINUTES
        return (mean + DAY_MINUTES) % DAY_MINUTES
    }

    /**
     * Solves A x = b by Gauss-Jordan with partial pivoting. Null when singular.
     * The engine only ever solves 5x5 ridge systems, so nothing cleverer is needed.
     */
    fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val inv = invert(a) ?: return null
        return DoubleArray(b.size) { i -> inv[i].indices.sumOf { j -> inv[i][j] * b[j] } }
    }

    fun invert(a: Array<DoubleArray>): Array<DoubleArray>? {
        val n = a.size
        val m = Array(n) { i -> DoubleArray(2 * n) { j -> if (j < n) a[i][j] else if (j - n == i) 1.0 else 0.0 } }
        for (col in 0 until n) {
            val pivot = (col until n).maxByOrNull { abs(m[it][col]) } ?: return null
            if (abs(m[pivot][col]) < 1e-12) return null
            val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
            val p = m[col][col]
            for (j in 0 until 2 * n) m[col][j] /= p
            for (r in 0 until n) {
                if (r == col) continue
                val f = m[r][col]
                if (f == 0.0) continue
                for (j in 0 until 2 * n) m[r][j] -= f * m[col][j]
            }
        }
        return Array(n) { i -> DoubleArray(n) { j -> m[i][j + n] } }
    }

    const val DAY_MINUTES = 1440.0
}
