package app.pawse.scoring.context

import app.pawse.scoring.config.ContextConfig
import app.pawse.scoring.math.Stats
import app.pawse.scoring.model.IllnessLevel
import java.time.LocalDate

/**
 * Illness detector: a one-sided CUSUM on the raw sleeping-HR residual.
 *
 *   e_t = (r_t - B_r) / sigma_r,   C_t = max(0, C_t-1 + e_t - 0.5)
 *
 * It adds up small excesses night after night, so three slightly high nights
 * trigger it and one random spike does not. Watch above 3; alert above 5 with a
 * temperature or breathing rise. It never names a disease.
 */
class IllnessDetector(private val config: ContextConfig) {
    var cusum: Double = 0.0
        private set

    /** A missed night leaves the sum where it was. */
    fun step(residual: Double?): Double {
        if (residual != null) cusum = maxOf(0.0, cusum + residual - config.cusumSlack)
        return cusum
    }

    fun level(tempUp: Boolean, breathingUp: Boolean): IllnessLevel = when {
        cusum > config.alertAbove && (tempUp || breathingUp) -> IllnessLevel.ALERT
        cusum > config.watchAbove -> IllnessLevel.WATCH
        else -> IllnessLevel.NONE
    }
}

/** Which days were luteal, and the user's own offsets once two cycles exist. */
data class CycleModel(
    val luteal: Set<LocalDate>,
    /** delta per signal: median(luteal) - median(follicular). Null until learned. */
    val deltaLnHrv: Double?,
    val deltaSleepingHr: Double?,
    val deltaTemp: Double?,
) {
    val learned: Boolean get() = deltaLnHrv != null || deltaSleepingHr != null || deltaTemp != null

    companion object {
        val NONE = CycleModel(emptySet(), null, null, null)
    }
}

/**
 * Menstrual cycle: learn the user's own luteal-phase offsets and remove them
 * before scoring, y_adj = y - delta x 1[luteal]. Without this a perfectly healthy
 * cycle reads as a week of "below normal" (Alzueta et al., 2022).
 *
 * The luteal phase is placed by a sustained temperature rise when temperature
 * data exist, otherwise by the calendar: the last 14 days before the next period.
 */
object Cycle {

    fun model(
        periodDays: Set<LocalDate>,
        today: LocalDate,
        lnHrv: Map<LocalDate, Double>,
        sleepingHr: Map<LocalDate, Double>,
        temp: Map<LocalDate, Double>,
        config: ContextConfig,
    ): CycleModel {
        if (periodDays.isEmpty()) return CycleModel.NONE
        val starts = periodDays.sorted().filter { d ->
            (1..config.periodGapDays).none { periodDays.contains(d.minusDays(it.toLong())) }
        }
        if (starts.isEmpty()) return CycleModel.NONE

        val complete = starts.zipWithNext()
        val lengths = complete.map { (a, b) -> (b.toEpochDay() - a.toEpochDay()).toInt() }
        val typical = Stats.median(lengths.map { it.toDouble() })?.toInt() ?: config.defaultCycleDays

        val luteal = mutableSetOf<LocalDate>()
        val follicular = mutableSetOf<LocalDate>()
        for ((start, next) in complete) {
            val lutealStart = tempRise(start, next.minusDays(1), temp, config) ?: next.minusDays(config.lutealDays.toLong())
            daysBetween(lutealStart, next.minusDays(1)).forEach { luteal += it }
            daysBetween(start, lutealStart.minusDays(1)).forEach { follicular += it }
        }
        val open = starts.last()
        // A period logged months ago says nothing about today.
        if (today.toEpochDay() - open.toEpochDay() <= 2L * typical) {
            val openLuteal = tempRise(open, today, temp, config)
                ?: open.plusDays((typical - config.lutealDays).toLong())
            daysBetween(openLuteal, today).forEach { luteal += it }
        }

        // Offsets come from complete cycles only, and only once there are two.
        val enough = complete.size >= config.cycleMinCycles
        val completeLuteal = luteal.filter { it.isBefore(open) }.toSet()
        fun delta(values: Map<LocalDate, Double>): Double? {
            if (!enough) return null
            val l = completeLuteal.mapNotNull { values[it] }
            val f = follicular.mapNotNull { values[it] }
            if (l.size < config.cycleMinPhaseValues || f.size < config.cycleMinPhaseValues) return null
            return Stats.median(l)!! - Stats.median(f)!!
        }
        // ponytail: offsets use every complete cycle, including ones after an older
        // day being replayed. Today never sees the future; history barely does.
        return CycleModel(luteal, delta(lnHrv), delta(sleepingHr), delta(temp))
    }

    /** First day of [tempRiseRun] days each above the max of the [tempRiseLookback] before them. */
    private fun tempRise(from: LocalDate, to: LocalDate, temp: Map<LocalDate, Double>, config: ContextConfig): LocalDate? {
        var day = from.plusDays(config.tempRiseLookback.toLong())
        while (!day.plusDays((config.tempRiseRun - 1).toLong()).isAfter(to)) {
            val before = (1..config.tempRiseLookback).mapNotNull { temp[day.minusDays(it.toLong())] }
            val run = (0 until config.tempRiseRun).mapNotNull { temp[day.plusDays(it.toLong())] }
            if (before.size >= config.tempRiseLookback - 2 && run.size == config.tempRiseRun && run.all { it > before.max() }) {
                return day
            }
            day = day.plusDays(1)
        }
        return null
    }

    private fun daysBetween(from: LocalDate, to: LocalDate): List<LocalDate> =
        if (to.isBefore(from)) emptyList() else (0..(to.toEpochDay() - from.toEpochDay())).map { from.plusDays(it) }
}
