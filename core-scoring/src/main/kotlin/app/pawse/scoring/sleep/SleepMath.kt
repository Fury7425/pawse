package app.pawse.scoring.sleep

import app.pawse.scoring.config.SleepConfig
import app.pawse.scoring.math.Stats
import app.pawse.scoring.model.SleepInterval
import app.pawse.scoring.model.SleepNight
import app.pawse.scoring.model.SleepScoreParts
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

data class SleepNeed(val hours: Double, val learned: Boolean, val freeNights: Int)

/**
 * Sleep is a behaviour, reported in real units: hours of debt and how regular
 * your timing is. "You're 3 h 10 m behind" tells you what to do; "Sleep 71" does not.
 */
object SleepMath {

    /**
     * N: the median sleep on free nights (nights before a day off) over 60 days,
     * clamped to 7-9 h, 8 h until there are ten of them.
     *
     * ponytail: "day off" is Saturday and Sunday mornings. The app has no
     * calendar; a user-marked day off is the upgrade if shift workers need it.
     */
    fun need(nights: Map<LocalDate, SleepNight>, day: LocalDate, config: SleepConfig): SleepNeed {
        val free = nights.filter { (date, _) ->
            !date.isAfter(day) && date.isAfter(day.minusDays(config.needWindowDays.toLong())) &&
                (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY)
        }.values.map { it.asleepMinutes / 60.0 }
        if (free.size < config.minFreeNights) return SleepNeed(config.defaultNeedHours, false, free.size)
        val hours = Stats.median(free)!!.coerceIn(config.minNeedHours, config.maxNeedHours)
        return SleepNeed(hours, true, free.size)
    }

    /**
     * D_t = max(0, sum_k 0.85^k clip(N - TST_t-k, -1 h, N)), k = 0..13.
     *
     * Recent nights count more, and one long lie-in repays at most an hour, so a
     * 12-hour Sunday cannot erase a week of 5-hour nights. A night the watch
     * missed is skipped, not counted as zero sleep.
     */
    fun debtHours(nights: Map<LocalDate, SleepNight>, day: LocalDate, needHours: Double, config: SleepConfig): Double? {
        var sum = 0.0
        var any = false
        for (k in 0 until config.debtNights) {
            val night = nights[day.minusDays(k.toLong())] ?: continue
            any = true
            val gap = (needHours - night.asleepMinutes / 60.0).coerceIn(-config.maxRepayHours, needHours)
            sum += config.debtDecay.pow(k) * gap
        }
        return if (any) sum.coerceAtLeast(0.0) else null
    }

    /**
     * Sleep Regularity Index: the chance you are in the same state (asleep or
     * awake) at two moments exactly 24 hours apart, scaled to -100..100, minute by
     * minute over the last 14 days, naps included (Windred et al., 2024).
     *
     * Only minutes whose noon-to-noon day had a recorded night take part: a night
     * the watch missed is unknown, not a night spent awake.
     */
    fun sri(
        intervals: List<SleepInterval>,
        endEpochMs: Long,
        offsetSeconds: Int,
        recordedNights: Set<LocalDate>,
        config: SleepConfig,
    ): Double? {
        val minutes = config.sriDays * DAY
        val startMs = endEpochMs - minutes * MINUTE_MS
        val asleep = BooleanArray(minutes)
        for (iv in intervals) {
            if (iv.endEpochMs <= startMs || iv.startEpochMs >= endEpochMs) continue
            val from = ((iv.startEpochMs - startMs) / MINUTE_MS).toInt().coerceAtLeast(0)
            val to = ((iv.endEpochMs - startMs) / MINUTE_MS).toInt().coerceAtMost(minutes)
            for (i in from until to) asleep[i] = true
        }
        // The wake date a minute belongs to: shift by the offset, then by twelve
        // hours, so everything from noon to noon lands on the morning it ends.
        fun sleepDay(i: Int): Long =
            floor((startMs + i * MINUTE_MS + offsetSeconds * 1000L + HALF_DAY_MS) / DAY_MS.toDouble()).toLong()

        val observedDays = (0 until minutes step DAY).map { sleepDay(it) }.toSet() + sleepDay(minutes - 1)
        val recorded = recordedNights.map { it.toEpochDay() }.toSet()
        if (observedDays.count { it in recorded } < config.sriMinNights) return null

        var same = 0
        var total = 0
        for (i in 0 until minutes - DAY) {
            if (sleepDay(i) !in recorded || sleepDay(i + DAY) !in recorded) continue
            total++
            if (asleep[i] == asleep[i + DAY]) same++
        }
        if (total == 0) return null
        return 200.0 * same / total - 100.0
    }

    /**
     * Optional Sleep score, for people who want one number. Stages get no weight.
     *
     *   Sleep = 100 (0.45 min(1, TST/N) + 0.35 clip((SRI - 50)/40, 0, 1) + 0.20 Q)
     *
     * Q is continuity: half efficiency against 90%, half WASO against 60 minutes.
     * A part that cannot be measured is dropped and the rest renormalised.
     */
    fun score(
        asleepMinutes: Double,
        needHours: Double,
        sri: Double?,
        efficiencyPercent: Double?,
        wasoMinutes: Double?,
        config: SleepConfig,
    ): Pair<Int, SleepScoreParts> {
        val duration = min(1.0, asleepMinutes / 60.0 / needHours)
        val regularity = sri?.let { ((it - 50.0) / 40.0).coerceIn(0.0, 1.0) }
        val continuityParts = listOfNotNull(
            efficiencyPercent?.let { (it / config.efficiencyTargetPercent).coerceIn(0.0, 1.0) },
            wasoMinutes?.let { (1.0 - it / config.wasoLimitMinutes).coerceIn(0.0, 1.0) },
        )
        val continuity = if (continuityParts.isEmpty()) null else continuityParts.average()

        var weighted = config.scoreDurationWeight * duration
        var weights = config.scoreDurationWeight
        regularity?.let { weighted += config.scoreRegularityWeight * it; weights += config.scoreRegularityWeight }
        continuity?.let { weighted += config.scoreContinuityWeight * it; weights += config.scoreContinuityWeight }
        val value = (100.0 * weighted / weights).roundToInt().coerceIn(0, 100)
        return value to SleepScoreParts(duration, regularity, continuity)
    }

    private const val DAY = 1440
    private const val MINUTE_MS = 60_000L
    private const val DAY_MS = 86_400_000L
    private const val HALF_DAY_MS = 43_200_000L
}
