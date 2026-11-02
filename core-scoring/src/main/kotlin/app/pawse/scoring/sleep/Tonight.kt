package app.pawse.scoring.sleep

import app.pawse.scoring.config.TonightConfig
import app.pawse.scoring.math.Stats

/** Tonight's need and the two clock times it sets, minutes since midnight. */
data class TonightPlan(val needMinutes: Double, val bedMinute: Double, val windDownMinute: Double)

/**
 * Sleep need that moves: v2's base need plus a bump for a hard day or a possible
 * illness, minus credit for an afternoon nap.
 *
 *   N_tonight = N + clip(4 min x (P_today - P~), 0, 30 min) + 30 min x 1[illness] - nap / 2
 *   t_bed     = t_wake - N_tonight - latency,   t_wind = t_bed - 50 min
 *
 * The evidence for how much one hard day raises need is thin, so 4 minutes a point,
 * the 30-minute illness bump and the 50-minute wind-down are defaults for the
 * check-in calibration to tune. Sleep debt stays internal: it feeds the energy
 * curve and the "why" lines but is never shown as a counter.
 */
object Tonight {

    /**
     * [typicalPoints] is P~, the median daily load points over 28 days, rest days
     * included. [napMinutes] is today's afternoon nap; under 20 minutes earns nothing.
     */
    fun plan(
        baseNeedMinutes: Double,
        todayPoints: Double,
        typicalPoints: Double,
        illness: Boolean,
        napMinutes: Double,
        usualWakeMinute: Double,
        latencyMinutes: Double,
        config: TonightConfig,
    ): TonightPlan {
        val load = (config.minutesPerPoint * (todayPoints - typicalPoints)).coerceIn(0.0, config.maxLoadMinutes)
        val sick = if (illness) config.illnessMinutes else 0.0
        val nap = if (napMinutes >= config.napMinMinutes) (config.napCreditShare * napMinutes).coerceAtMost(config.napMaxCreditMinutes) else 0.0
        val need = (baseNeedMinutes + load + sick - nap).coerceAtMost(baseNeedMinutes + config.maxAboveNeedMinutes)
        val bed = wrap(usualWakeMinute - need - latencyMinutes)
        return TonightPlan(need, bed, wrap(bed - config.windDownMinutes))
    }

    /** Median minutes to fall asleep over the last 14 nights. */
    fun latency(minutesToSleep: List<Double>, config: TonightConfig): Double =
        Stats.median(minutesToSleep.takeLast(config.latencyNights)) ?: 0.0

    private fun wrap(minute: Double): Double = ((minute % DAY) + DAY) % DAY

    private const val DAY = 1440.0
}
