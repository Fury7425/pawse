package app.pawse.scoring.energy

import app.pawse.scoring.config.EnergyBalanceConfig
import kotlin.math.roundToInt

/** One day of the food log, reduced to what the weight check reads. */
data class LoggedDay(
    val eatenKcal: Double,
    val meals: Int,
    /** The user marked the day as not fully logged. */
    val incomplete: Boolean,
    /** What the watch suggested was burned that whole day, before k. */
    val burnedKcal: Double,
)

/**
 * Food's "Burned so far": resting burn spread over the clock, the watch's active
 * energy, and the energy spent digesting, then quietly corrected by the weight
 * trend.
 *
 *   B(t)  = k [BMR t/24h + A(t)] + 0.10 E(t)
 *   B_bed = B(t) + k [BMR (t_bed - t)/24h + usual active energy until bed]
 *
 * k exists because a watch's calories are the weakest thing it reports: in
 * Shcherbina et al. 2017, none of seven wrist devices estimated energy
 * expenditure within 20%.
 */
object EnergyBalance {

    /**
     * [activeKcal] is A(t). When [watchTotal] is true it is the watch's total
     * calories, which already hold resting burn, so BMR is not added twice.
     */
    fun burnedSoFar(
        bmrKcal: Double,
        minuteOfDay: Double,
        activeKcal: Double,
        eatenKcal: Double,
        k: Double,
        config: EnergyBalanceConfig,
        watchTotal: Boolean = false,
    ): Double {
        val resting = if (watchTotal) 0.0 else bmrKcal * minuteOfDay.coerceIn(0.0, DAY) / DAY
        return k * (resting + activeKcal) + config.digestionShare * eatenKcal
    }

    /** [usualActiveUntilBed] comes from Moving today's usual for the rest of the day. */
    fun byBedtime(
        soFarKcal: Double,
        bmrKcal: Double,
        minuteOfDay: Double,
        bedMinute: Double,
        usualActiveUntilBed: Double,
        k: Double,
    ): Double {
        val left = (bedMinute - minuteOfDay).coerceAtLeast(0.0)
        return soFarKcal + k * (bmrKcal * left / DAY + usualActiveUntilBed.coerceAtLeast(0.0))
    }

    /**
     * The weekly weight check. Over the last 28 days, what was eaten on fully
     * logged days against how the trend weight moved:
     *
     *   k_obs = (E - 7700 dT / d) / B
     *   k     = clip((n k_obs + 14) / (n + 14), 0.85, 1.15)
     *
     * The shrinkage pulls k toward 1, so a few badly logged weeks cannot swing it
     * far. k stays 1 until there are 14 logged days and 8 weigh-ins.
     */
    fun weightCheck(
        days: List<LoggedDay>,
        trendChangeKg: Double?,
        spanDays: Int,
        weighIns: Int,
        config: EnergyBalanceConfig,
    ): Double {
        val logged = days.filter { it.meals >= config.minMeals && !it.incomplete && it.burnedKcal > 0.0 }
        val n = logged.size
        if (n < config.minLoggedDays || weighIns < config.minWeighIns || trendChangeKg == null || spanDays <= 0) return 1.0
        val eaten = logged.sumOf { it.eatenKcal } / n
        val burned = logged.sumOf { it.burnedKcal } / n
        val observed = (eaten - config.kcalPerKg * trendChangeKg / spanDays) / burned
        return ((n * observed + config.priorDays) / (n + config.priorDays)).coerceIn(config.kMin, config.kMax)
    }

    /** Shown figures round to 10 kcal; anything projected also gets a "~" in the UI. */
    fun shown(kcal: Double, config: EnergyBalanceConfig): Int =
        (kcal / config.roundKcal).roundToInt() * config.roundKcal

    private const val DAY = 1440.0
}
