package app.pawse.scoring.activity

import app.pawse.scoring.config.MovingConfig
import kotlin.math.ceil
import kotlin.math.floor

/**
 * One past day of Health Connect's 15-minute totals, read when Scores opens and
 * kept in memory only. [cumulativeFromWake] holds one total per 15 minutes, so
 * element i is the total from wake to wake + 15(i + 1) minutes.
 */
data class MovingDay(val weekend: Boolean, val cumulativeFromWake: List<Double>)

enum class MovingWord { BELOW, ABOUT, ABOVE, LEARNING }

/** "About usual by now", with the usual band behind the bar. Grey only: no goal, no score. */
data class MovingReading(val today: Double, val low: Double?, val high: Double?, val word: MovingWord, val days: Int)

/**
 * "By now" compares today's running total with the same stretch of your recent
 * days, counted from when you woke rather than by the clock, so a late morning
 * never reads as below usual:
 *
 *   usual by now = [Q25, Q75] of { S_d(tau) : d in the last 28 days, same day type }
 *
 * 28 days stays inside Health Connect's default 30-day read window, so this works
 * without the history permission.
 */
object MovingToday {

    fun reading(
        today: Double,
        minutesSinceWake: Double,
        weekend: Boolean,
        days: List<MovingDay>,
        config: MovingConfig,
    ): MovingReading {
        val window = days.takeLast(config.windowDays)
        if (window.size < config.minDays) return MovingReading(today, null, null, MovingWord.LEARNING, window.size)
        val same = window.filter { it.weekend == weekend }
        val pool = if (same.size >= config.minSameType) same else window
        val byNow = pool.map { at(it, minutesSinceWake) }
        val low = quantile(byNow, config.lowQuantile)
        val high = quantile(byNow, config.highQuantile)
        val word = when {
            today < low -> MovingWord.BELOW
            today > high -> MovingWord.ABOVE
            else -> MovingWord.ABOUT
        }
        return MovingReading(today, low, high, word, pool.size)
    }

    /**
     * Median of the rest of the day on past days, wake + now to wake + [untilMinute]:
     * what Energy adds for "Burned by bedtime". Null while learning.
     */
    fun usualRest(minutesSinceWake: Double, untilMinute: Double, weekend: Boolean, days: List<MovingDay>, config: MovingConfig): Double? {
        val window = days.takeLast(config.windowDays)
        if (window.size < config.minDays) return null
        val same = window.filter { it.weekend == weekend }
        val pool = if (same.size >= config.minSameType) same else window
        return quantile(pool.map { (at(it, untilMinute) - at(it, minutesSinceWake)).coerceAtLeast(0.0) }, 0.5)
    }

    /** S_d(tau), interpolated within the 15-minute bucket tau falls in. */
    internal fun at(day: MovingDay, minutes: Double): Double {
        val b = day.cumulativeFromWake
        if (b.isEmpty() || minutes <= 0.0) return 0.0
        val pos = minutes / BUCKET - 1.0
        if (pos >= b.lastIndex) return b.last()
        if (pos < 0.0) return b[0] * (minutes / BUCKET)
        val i = floor(pos).toInt()
        val f = pos - i
        return b[i] + (b[i + 1] - b[i]) * f
    }

    /** Linear-interpolated quantile, the definition spreadsheets use. */
    fun quantile(values: List<Double>, q: Double): Double {
        val s = values.sorted()
        val h = (s.size - 1) * q
        val lo = floor(h).toInt()
        val hi = ceil(h).toInt()
        return s[lo] + (s[hi] - s[lo]) * (h - lo)
    }

    private const val BUCKET = 15.0
}
