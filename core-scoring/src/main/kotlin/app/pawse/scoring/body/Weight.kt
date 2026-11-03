package app.pawse.scoring.body

import app.pawse.scoring.config.WeightConfig
import app.pawse.scoring.math.Stats
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

/** One day's weigh-in: the day, the kilograms, and the local clock minute it was taken. */
data class WeighInPoint(val day: Long, val kg: Double, val minuteOfDay: Double? = null)

/** The trend after one weigh-in, and whether that reading counted at half weight. */
data class TrendPoint(val day: Long, val kg: Double, val trend: Double, val likelyWater: Boolean)

/** Median and robust spread of one device's readings: "Usually 18.6% ± 0.4 · 22 weigh-ins". */
data class BodyUsual(val median: Double, val spread: Double, val readings: Int)

sealed interface GoalEstimate {
    /** "About 2 months", shown as [low] to [high] when they differ. Null [high]: no upper end. */
    data class Months(val months: Int, val low: Int, val high: Int?) : GoalEstimate

    /** Needs 3 weeks and 8 weigh-ins first. */
    data object TooEarly : GoalEstimate

    /** "No clear trend toward your goal yet." */
    data object NoClearTrend : GoalEstimate

    /** Falling faster than common guidance: one neutral line, no date. */
    data object TooFast : GoalEstimate
}

/**
 * Weight: the trend is the headline, the scale reading appears once.
 *
 *   alpha = 1 - 0.9^dt,   T <- T + alpha (W - T)
 *
 * With daily weigh-ins alpha is 0.1, the design's rule. After a 3-day gap it is
 * 0.27, so one reading after a weekend away moves the trend about as much as three
 * daily ones would have, and no daily points are invented between weigh-ins.
 */
object Weight {

    /**
     * A reading more than 2.5% from the trend, or more than 4 hours from your usual
     * weigh-in time, counts at half weight. The next one off in the same direction
     * counts fully, so a real change is never held back. After more than a week
     * away the trend is stale, so the first reading back counts fully too.
     */
    fun trend(points: List<WeighInPoint>, config: WeightConfig): List<TrendPoint> {
        val sorted = points.sortedBy { it.day }
        if (sorted.isEmpty()) return emptyList()
        val first = sorted.first()
        val out = mutableListOf(TrendPoint(first.day, first.kg, first.kg, false))
        var t = first.kg
        var lastDay = first.day
        var lastOdd = 0.0
        val minutes = mutableListOf<Double>()
        first.minuteOfDay?.let { minutes += it }
        for (p in sorted.drop(1)) {
            val dt = (p.day - lastDay).coerceAtLeast(1L).toDouble()
            var alpha = 1.0 - config.trendKeep.pow(dt)
            val gap = p.kg - t
            val usualMinute = Stats.circularMeanMinutes(minutes)
            val offHour = p.minuteOfDay != null && usualMinute != null && clockGap(p.minuteOfDay, usualMinute) > config.oddHours * 60.0
            val odd = dt <= config.oddMaxGapDays && (abs(gap) > config.oddShare * t || offHour)
            val held = odd && sign(gap) != lastOdd
            if (held) alpha /= 2.0
            lastOdd = if (odd) sign(gap) else 0.0
            t += alpha * gap
            lastDay = p.day
            p.minuteOfDay?.let { minutes += it }
            out += TrendPoint(p.day, p.kg, t, held)
        }
        return out
    }

    /** Body fat, lean mass and muscle move slower than weight and skip the odd-reading rule. */
    fun slowTrend(points: List<WeighInPoint>, config: WeightConfig): List<TrendPoint> {
        val sorted = points.sortedBy { it.day }
        var t = sorted.firstOrNull()?.kg ?: return emptyList()
        var lastDay = sorted.first().day
        return sorted.map { p ->
            t += (1.0 - config.compositionKeep.pow((p.day - lastDay).toDouble())) * (p.kg - t)
            lastDay = p.day
            TrendPoint(p.day, p.kg, t, false)
        }
    }

    /**
     * r is the slope, kg a week, of a straight line through the last 28 days of
     * trend, and SE its uncertainty:
     *
     *   months = |T - G| / |r| / 4.35,  range from r +/- SE, rounded to whole months
     */
    fun goal(trend: List<TrendPoint>, goalKg: Double, today: Long, config: WeightConfig): GoalEstimate {
        val latest = trend.lastOrNull() ?: return GoalEstimate.TooEarly
        val window = trend.filter { it.day > today - config.goalWindowDays && it.day <= today }
        if (today - trend.first().day < config.goalMinDays || window.size < config.goalMinWeighIns) return GoalEstimate.TooEarly
        if (tooFast(trend, today, config)) return GoalEstimate.TooFast
        val (perDay, se) = slope(window.map { it.day.toDouble() }, window.map { it.trend }) ?: return GoalEstimate.NoClearTrend
        val r = perDay * 7.0
        val seWeek = se * 7.0
        val gap = goalKg - latest.trend
        if (abs(r) < config.goalMinKgPerWeek || sign(r) != sign(gap)) return GoalEstimate.NoClearTrend
        fun months(rate: Double) = (abs(gap) / rate / config.weeksPerMonth).roundToInt().coerceAtLeast(1)
        val slow = abs(r) - seWeek
        return GoalEstimate.Months(
            months = months(abs(r)),
            low = months(abs(r) + seWeek),
            high = if (slow > 0.0) months(slow) else null,
        )
    }

    /** Falling faster than 1% of body weight a week, each of the last 3 weeks. */
    fun tooFast(trend: List<TrendPoint>, today: Long, config: WeightConfig): Boolean {
        fun at(day: Long) = trend.lastOrNull { it.day <= day }?.trend
        return (0 until config.paceWeeks).all { w ->
            val end = at(today - 7L * w) ?: return false
            val start = at(today - 7L * (w + 1)) ?: return false
            start - end > config.paceShare * start
        }
    }

    /**
     * Your usual for a composition figure: median +/- 1.4826 x MAD over the last 60
     * days from one device, with at least 4 readings. InBody and a smart scale never
     * share it; the caller passes one source's readings.
     */
    fun usual(points: List<WeighInPoint>, today: Long, config: WeightConfig): BodyUsual? {
        val xs = points.filter { it.day > today - config.usualDays && it.day <= today }.map { it.kg }
        if (xs.size < config.usualMinReadings) return null
        return BodyUsual(Stats.median(xs)!!, Stats.robustSpread(xs)!!, xs.size)
    }

    /**
     * The design's ≈ rule: a change is named only when it is bigger than half the SD
     * of reading-to-reading change; otherwise it reads "about the same".
     */
    fun aboutSame(change: Double, points: List<WeighInPoint>): Boolean {
        val steps = points.sortedBy { it.day }.zipWithNext { a, b -> b.kg - a.kg }
        val sd = Stats.sd(steps) ?: return true
        return abs(change) <= 0.5 * sd
    }

    /** Least-squares slope per x unit and its standard error. Null under three points. */
    internal fun slope(x: List<Double>, y: List<Double>): Pair<Double, Double>? {
        val n = x.size
        if (n < 3) return null
        val mx = x.average()
        val my = y.average()
        val sxx = x.sumOf { (it - mx) * (it - mx) }
        if (sxx <= 0.0) return null
        val b = x.indices.sumOf { (x[it] - mx) * (y[it] - my) } / sxx
        val residual = x.indices.sumOf { val e = y[it] - (my + b * (x[it] - mx)); e * e }
        return b to sqrt(residual / (n - 2) / sxx)
    }

    private fun clockGap(a: Double, b: Double): Double {
        val d = abs(a - b) % 1440.0
        return minOf(d, 1440.0 - d)
    }
}
