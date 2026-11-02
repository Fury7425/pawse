package app.pawse.core.data.activity

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import app.pawse.core.data.health.HealthConnectGateway
import app.pawse.scoring.activity.MovingDay
import app.pawse.scoring.activity.MovingReading
import app.pawse.scoring.activity.MovingToday
import app.pawse.scoring.config.ScoringConfig
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** One hour of heart rate since midnight: lowest, highest and mean. Shown, never scored. */
data class HeartHour(val hour: Int, val low: Long, val high: Long, val mean: Long)

/** Moving today as the card draws it. */
data class MovingSnapshot(
    val steps: MovingReading,
    val energy: MovingReading,
    val wakeEpochMs: Long,
    /** False when no main sleep ended today, so "by now" counts from midnight. */
    val wakeKnown: Boolean,
    val heart: List<HeartHour>,
    val asOfEpochMs: Long,
    /** Your usual active energy from now to bed, for Food's "burned by bedtime". Null without a bed. */
    val usualActiveUntilBed: Double? = null,
)

/**
 * Moving today: Health Connect's own 15-minute totals for the last 28 days, read
 * when Scores opens and kept in memory only. 28 days stays inside Health
 * Connect's default 30-day window, so no history permission is needed.
 */
@Singleton
class MovingRepository @Inject constructor(private val gateway: HealthConnectGateway) {

    /**
     * [wakes]: each day's main-sleep end, by wake date. [bedMinute], minutes after
     * today's midnight, also works out the usual active energy until bed. Null when
     * nothing was written at all.
     */
    suspend fun read(now: ZonedDateTime, wakes: Map<LocalDate, Long>, bedMinute: Double? = null): MovingSnapshot? {
        val config = ScoringConfig.DEFAULT.moving
        val zone = now.zone
        val today = now.toLocalDate()
        val start = today.minusDays(config.windowDays.toLong()).atStartOfDay(zone).toInstant()
        val end = now.toInstant()
        val slice = Duration.ofMinutes(15)

        val steps = gateway.slices(setOf(StepsRecord.COUNT_TOTAL), start, end, slice)
            .associate { it.startTime.toEpochMilli() to (it.result[StepsRecord.COUNT_TOTAL]?.toDouble() ?: 0.0) }
        val energy = gateway.slices(setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL), start, end, slice)
            .associate { it.startTime.toEpochMilli() to (it.result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0) }
        if (steps.isEmpty() && energy.isEmpty()) return null

        val midnight = today.atStartOfDay(zone).toInstant()
        val wake = wakes[today]?.takeIf { it in midnight.toEpochMilli()..end.toEpochMilli() }
        val heart = gateway.slices(
            setOf(HeartRateRecord.BPM_MIN, HeartRateRecord.BPM_MAX, HeartRateRecord.BPM_AVG),
            midnight,
            end,
            Duration.ofHours(1),
        ).mapNotNull { g ->
            val mean = g.result[HeartRateRecord.BPM_AVG] ?: return@mapNotNull null
            HeartHour(g.startTime.atZone(zone).hour, g.result[HeartRateRecord.BPM_MIN] ?: mean, g.result[HeartRateRecord.BPM_MAX] ?: mean, mean)
        }

        val past = (config.windowDays downTo 1).map { today.minusDays(it.toLong()) }
        val from = wake ?: midnight.toEpochMilli()
        val sinceWake = (end.toEpochMilli() - from) / 60_000.0
        val weekend = MovingAssembler.weekend(today)
        fun reading(buckets: Map<Long, Double>) = MovingToday.reading(
            today = MovingAssembler.since(buckets, from, end.toEpochMilli()),
            minutesSinceWake = sinceWake,
            weekend = weekend,
            days = MovingAssembler.days(buckets, past, wakes, zone),
            config = config,
        )
        val untilBed = bedMinute?.let { bed ->
            val bedSinceWake = (midnight.toEpochMilli() + (bed * 60_000).toLong() - from) / 60_000.0
            MovingToday.usualRest(sinceWake, bedSinceWake, weekend, MovingAssembler.days(energy, past, wakes, zone), config)
        }
        return MovingSnapshot(reading(steps), reading(energy), from, wake != null, heart, end.toEpochMilli(), untilBed)
    }
}

/** Buckets to [MovingDay]s. Pure, so it is tested without a device. */
object MovingAssembler {

    private const val SLICE_MS = 15 * 60_000L
    private const val DAY_SLICES = 96

    /**
     * Each past day from its own wake, as 15-minute running totals. A day with
     * nothing written is left out: a watch left on the charger is not a still day.
     *
     * ponytail: wake is floored to the 15-minute grid, so "by now" can be up to 14
     * minutes generous. Fine for a grey band; per-minute reads if it ever is not.
     */
    fun days(buckets: Map<Long, Double>, days: List<LocalDate>, wakes: Map<LocalDate, Long>, zone: ZoneId): List<MovingDay> =
        days.mapNotNull { d ->
            val midnight = d.atStartOfDay(zone).toInstant().toEpochMilli()
            val wake = floor(wakes[d] ?: midnight, midnight)
            val running = (0 until DAY_SLICES).map { buckets[wake + it * SLICE_MS] ?: 0.0 }.runningReduce { a, b -> a + b }
            running.takeIf { it.last() > 0.0 }?.let { MovingDay(weekend(d), it) }
        }

    /** Today's total from [fromEpochMs] to now. */
    fun since(buckets: Map<Long, Double>, fromEpochMs: Long, nowEpochMs: Long): Double =
        buckets.filterKeys { it + SLICE_MS > fromEpochMs && it < nowEpochMs }.values.sum()

    fun weekend(d: LocalDate): Boolean = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY

    private fun floor(t: Long, midnight: Long): Long = midnight + Math.floorDiv(t - midnight, SLICE_MS) * SLICE_MS
}
