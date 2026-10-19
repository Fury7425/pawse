package app.pawse.scoring.energy

import app.pawse.scoring.config.EnergyConfig
import app.pawse.scoring.math.Stats
import app.pawse.scoring.model.EnergyForecast
import app.pawse.scoring.model.EnergyPoint
import app.pawse.scoring.model.Intake
import app.pawse.scoring.model.SleepInterval
import app.pawse.scoring.model.SleepNight
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Today's energy forecast: the classic two-process model of sleep.
 *
 * Sleep pressure S is a sand timer, filling while you are awake and draining
 * while you sleep. The body clock C is a tide, rising and falling once a day.
 * Alertness is roughly the tide minus the sand, lowered further by sleep debt:
 *
 *   S_awake(t)  = 1 - (1 - S0) e^(-t / 18.2 h)
 *   S_asleep(t) = S0 e^(-t / 4.2 h)
 *   C(t)        = a cos(2 pi (t - phi) / 24 h) - a u cos(2 pi (t - phi + 6 h) / 12 h)
 *   A(t)        = C(t) - S(t) - kappa D
 *
 * It models typical physiology rather than measuring yours, and is labelled a
 * forecast for that reason.
 */
object EnergyForecastModel {

    fun forecast(
        lastNight: SleepNight,
        /** Earlier main nights, newest last, with their wake dates. */
        recentNights: List<Pair<LocalDate, SleepNight>>,
        day: LocalDate,
        intervals: List<SleepInterval>,
        debtHours: Double,
        kappa: Double,
        intakes: List<Intake>,
        config: EnergyConfig,
    ): EnergyForecast {
        val wake = lastNight.wakeMinute.roundToInt()
        val (habitualBed, lag) = clockAnchor(recentNights + (day to lastNight), lastNight.endOffsetSeconds, day, config)
        // Bedtime on the same axis as the wake minute, so it lands later the same day.
        var bed = habitualBed
        while (bed <= wake + 6 * 60) bed += 1440
        val peak = bed - config.peakBeforeBedtimeHours * 60
        val dipCentre = peak - config.dipBeforePeakHours * 60
        val s0 = pressureAtWake(intervals, lastNight.endEpochMs, config)
        val a = config.circadianAmplitude

        fun alertness(m: Int): Double {
            val hoursAwake = (m - wake) / 60.0
            val s = 1.0 - (1.0 - s0) * exp(-hoursAwake / config.wakeTauHours)
            val c = a * cos(2 * PI * (m - peak) / 1440.0) -
                a * config.ultradianShare * cos(4 * PI * (m - dipCentre) / 1440.0)
            return c - s - kappa * debtHours
        }

        val doses = intakes.filter { (it.caffeineMg ?: 0.0) > 0.0 }
        fun caffeine(m: Int): Double {
            val at = lastNight.endEpochMs + (m - wake) * 60_000L
            return doses.filter { it.epochMs <= at && at - it.epochMs < 48 * 3_600_000L }.sumOf {
                it.caffeineMg!! * 0.5.pow((at - it.epochMs) / 3_600_000.0 / config.caffeineHalfLifeHours)
            }
        }

        val end = bed + 60
        val points = (wake..end step config.stepMinutes).map { m -> EnergyPoint(m, alertness(m), caffeine(m)) }

        val windDown = bed - config.windDownBeforeBedMinutes
        val focusFrom = wake + config.inertiaMinutes
        val focusLength = config.focusWindowMinutes
        val focusStart = (focusFrom..(windDown - focusLength).coerceAtLeast(focusFrom) step config.stepMinutes)
            .maxByOrNull { start ->
                (start..start + focusLength step config.stepMinutes).map(::alertness).average()
            } ?: focusFrom
        // The dip is looked for five to ten hours after waking, which is early
        // afternoon for a 07:00 riser and follows a shift worker's day too.
        val dip = (wake + 300..min(wake + 600, windDown) step config.stepMinutes)
            .minByOrNull(::alertness) ?: (wake + 420)

        return EnergyForecast(
            points = points,
            wakeMinute = wake,
            focusStart = focusStart,
            focusEnd = focusStart + focusLength,
            dipMinute = dip,
            windDownMinute = windDown,
            bedtimeMinute = bed,
            caffeineAtBedMg = caffeine(bed),
            clockLagMinutes = lag,
        )
    }

    /**
     * Habitual bedtime on today's clock, and how far the body clock still lags.
     *
     * A time-zone change in the records moves the anchor about an hour a day
     * rather than all at once: the body does not land when the plane does.
     */
    fun clockAnchor(
        nights: List<Pair<LocalDate, SleepNight>>,
        todayOffsetSeconds: Int,
        day: LocalDate,
        config: EnergyConfig,
    ): Pair<Int, Int> {
        val recent = nights.takeLast(config.habitNights)
        val changeIndex = recent.indexOfLast { it.second.startOffsetSeconds != todayOffsetSeconds }
        if (changeIndex < 0) {
            val h = Stats.circularMeanMinutes(recent.map { it.second.bedtimeMinute }) ?: 23 * 60.0
            return h.roundToInt() to 0
        }
        val homeOffset = recent[changeIndex].second.startOffsetSeconds
        val home = recent.filter { it.second.startOffsetSeconds == homeOffset }
        val h = Stats.circularMeanMinutes(home.map { it.second.bedtimeMinute }) ?: 23 * 60.0
        val delta = (todayOffsetSeconds - homeOffset) / 60
        val days = (day.toEpochDay() - recent[changeIndex].first.toEpochDay()).coerceAtLeast(1L)
        val adjusted = min(abs(delta).toLong(), config.maxShiftMinutesPerDay * days).toInt()
        val residual = delta - delta.sign * adjusted
        return Math.floorMod(h.roundToInt() + residual, 1440) to residual
    }

    /** S at waking, walked forward through the last two days of sleep from a neutral 0.5. */
    private fun pressureAtWake(intervals: List<SleepInterval>, wakeMs: Long, config: EnergyConfig): Double {
        var cursor = wakeMs - 48 * 3_600_000L
        var s = 0.5
        for (iv in intervals.filter { it.endEpochMs > cursor && it.startEpochMs < wakeMs }.sortedBy { it.startEpochMs }) {
            val start = maxOf(iv.startEpochMs, cursor)
            if (start > cursor) s = 1.0 - (1.0 - s) * exp(-(start - cursor) / 3_600_000.0 / config.wakeTauHours)
            val end = minOf(iv.endEpochMs, wakeMs)
            if (end > start) s *= exp(-(end - start) / 3_600_000.0 / config.sleepTauHours)
            cursor = maxOf(cursor, end)
        }
        if (wakeMs > cursor) s = 1.0 - (1.0 - s) * exp(-(wakeMs - cursor) / 3_600_000.0 / config.wakeTauHours)
        return s
    }
}
