package app.pawse.core.data.scoring

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Statistics on clock times, which are angles rather than numbers.
 *
 * Bedtimes of 23:50 and 00:10 are twenty minutes apart, but their arithmetic mean
 * is midday and their arithmetic standard deviation is ten hours. Every "bedtime
 * consistency" feature that ever reported a night-owl as chaotic did so by
 * treating minutes-since-midnight as a scalar.
 *
 * The usual workaround is to rotate the day so the wrap falls somewhere nobody
 * sleeps — 18:00, say — which fixes the common case and silently breaks for shift
 * workers, who are precisely the users whose consistency is worth measuring
 * correctly. So this does the real thing instead: map each time onto the unit
 * circle, average the vectors, and read the mean direction back off.
 *
 * The dispersion measure is the standard circular SD, sqrt(-2 ln R), where R is
 * the resultant length. It agrees with the linear SD for tightly clustered times
 * and saturates rather than exploding as they scatter.
 */
object CircularClock {

    const val MINUTES_PER_DAY: Double = 1440.0

    private const val TWO_PI = 2.0 * PI

    /** Mean clock time of a set of times-of-day, in minutes since local midnight. */
    fun meanMinutes(minutes: List<Double>): Double? {
        if (minutes.isEmpty()) return null
        var x = 0.0
        var y = 0.0
        for (m in minutes) {
            val angle = m / MINUTES_PER_DAY * TWO_PI
            x += cos(angle)
            y += sin(angle)
        }
        if (abs(x) < 1e-12 && abs(y) < 1e-12) return null // perfectly opposed times: no mean direction
        val mean = atan2(y / minutes.size, x / minutes.size)
        return normalise(mean / TWO_PI * MINUTES_PER_DAY)
    }

    /**
     * Circular standard deviation in minutes.
     *
     * Saturates near 1440 / (2 pi) * sqrt(-2 ln 0) rather than running to infinity,
     * so a completely irregular schedule reports a large number and not a NaN.
     */
    fun sdMinutes(minutes: List<Double>): Double? {
        if (minutes.size < 2) return null
        var x = 0.0
        var y = 0.0
        for (m in minutes) {
            val angle = m / MINUTES_PER_DAY * TWO_PI
            x += cos(angle)
            y += sin(angle)
        }
        val r = sqrt(x * x + y * y) / minutes.size
        if (r <= 1e-9) return MINUTES_PER_DAY / 2.0
        if (r >= 1.0) return 0.0
        return sqrt(-2.0 * ln(r)) / TWO_PI * MINUTES_PER_DAY
    }

    /**
     * Signed shortest distance from [from] to [to], in minutes, in -720..720.
     * Positive means [to] is later on the clock face.
     */
    fun difference(from: Double, to: Double): Double {
        var delta = normalise(to) - normalise(from)
        if (delta > MINUTES_PER_DAY / 2.0) delta -= MINUTES_PER_DAY
        if (delta < -MINUTES_PER_DAY / 2.0) delta += MINUTES_PER_DAY
        return delta
    }

    fun normalise(minutes: Double): Double {
        val m = minutes % MINUTES_PER_DAY
        return if (m < 0) m + MINUTES_PER_DAY else m
    }
}
