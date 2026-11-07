package app.pawse.core.data.activity

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import app.pawse.core.data.health.HealthConnectGateway
import app.pawse.scoring.activity.MovingDay
import app.pawse.scoring.activity.MovingReading
import app.pawse.scoring.activity.MovingToday
import app.pawse.scoring.config.MovingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    /** The Activity detail's "Through the day" and "Last 7 days", one per figure. */
    val stepsDay: ThroughDay? = null,
    val energyDay: ThroughDay? = null,
    /** The package whose steps are shown: the writer that counted the most. */
    val stepsWriter: String? = null,
    /** False when steps were declined in Health Connect: the card says so instead of reading zero. */
    val stepsShared: Boolean = true,
)

/**
 * One figure through today, from midnight, against the same clock hours on your
 * recent days of the same type. Hours run 0..24; [today] stops at now.
 */
data class ThroughDay(
    /** Running total at each hour mark since midnight, up to now (the last one is now). */
    val today: List<Double>,
    /** Today's amount in each hour so far. */
    val perHour: List<Double>,
    /** Your usual running total at each hour mark 0..24: [Q25, Q75]. Empty while learning. */
    val usualLow: List<Double>,
    val usualHigh: List<Double>,
    /** The last seven days, oldest first, today's so far last. */
    val week: List<Pair<LocalDate, Double>>,
)

/**
 * Moving today: Health Connect's own 15-minute totals for the last 28 days, read
 * when Scores opens and kept in memory only. 28 days stays inside Health
 * Connect's default 30-day window, so no history permission is needed.
 */
@Singleton
class MovingRepository @Inject constructor(private val gateway: HealthConnectGateway) {

    /** The last reading, so a screen opened from Scores starts with what Scores showed. Memory only. */
    @Volatile
    var last: MovingSnapshot? = null
        private set

    /**
     * [wakes]: each day's main-sleep end, by wake date. [bedMinute], minutes after
     * today's midnight, also works out the usual active energy until bed. Null when
     * nothing was written at all.
     */
    // Off the main thread: a month of 15-minute buckets is summed hour by hour for the detail.
    suspend fun read(now: ZonedDateTime, wakes: Map<LocalDate, Long>, bedMinute: Double? = null): MovingSnapshot? = withContext(Dispatchers.Default) {
        val config = ScoringConfig.DEFAULT.moving
        val zone = now.zone
        val today = now.toLocalDate()
        val start = today.minusDays(config.windowDays.toLong()).atStartOfDay(zone).toInstant()
        val end = now.toInstant()
        val slice = Duration.ofMinutes(15)

        suspend fun stepSlices(origins: Set<String> = emptySet()) = gateway.slices(setOf(StepsRecord.COUNT_TOTAL), start, end, slice, origins)
            .associate { it.startTime.toEpochMilli() to (it.result[StepsRecord.COUNT_TOTAL]?.toDouble() ?: 0.0) }
        // One writer for steps, the one that counted the most over the last two days.
        // Health Connect's own total follows its priority list, which on a phone with a
        // watch app, a fitness app and the phone's own counter can be the one that
        // counted least, or none of them at all (every hour zero). Read alone, the
        // fullest writer gives the day Samsung Health or the watch app shows.
        val stepsWriter = gateway.readOrEmpty(StepsRecord::class, end.minus(Duration.ofDays(2)), end)
            .groupBy { it.metadata.dataOrigin.packageName }
            .mapValues { (_, records) -> records.sumOf { it.count } }
            .maxByOrNull { it.value }?.key
        var steps = stepSlices(setOfNotNull(stepsWriter))
        var shownWriter = stepsWriter
        if (stepsWriter != null && steps.values.none { it > 0.0 }) {
            steps = stepSlices()
            shownWriter = null
        }
        val energy = gateway.slices(setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL), start, end, slice)
            .associate { it.startTime.toEpochMilli() to (it.result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0) }
        if (steps.isEmpty() && energy.isEmpty()) return@withContext null

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
        fun through(buckets: Map<Long, Double>) = MovingAssembler.throughDay(buckets, today, end.toEpochMilli(), past, zone, weekend, config)
        MovingSnapshot(
            reading(steps), reading(energy), from, wake != null, heart, end.toEpochMilli(), untilBed,
            stepsDay = through(steps),
            energyDay = through(energy),
            stepsWriter = shownWriter,
            stepsShared = HealthPermission.getReadPermission(StepsRecord::class) in gateway.grantedPermissions(),
        ).also { last = it }
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

    /**
     * Today by the clock, hour by hour, against the same hours on past days of the
     * same type (all days when there are too few of it): the Activity detail's chart
     * and its last seven days. A past day with nothing written is left out.
     */
    fun throughDay(
        buckets: Map<Long, Double>,
        today: LocalDate,
        nowEpochMs: Long,
        past: List<LocalDate>,
        zone: ZoneId,
        weekend: Boolean,
        config: MovingConfig,
    ): ThroughDay {
        fun hourly(d: LocalDate, until: Long = Long.MAX_VALUE): List<Double> {
            val midnight = d.atStartOfDay(zone).toInstant().toEpochMilli()
            return (0 until 24).map { h ->
                val from = midnight + h * HOUR_MS
                buckets.filterKeys { it >= from && it < from + HOUR_MS && it < until }.values.sum()
            }
        }
        fun running(perHour: List<Double>) = perHour.runningFold(0.0) { a, b -> a + b }
        val midnight = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val nowHour = ((nowEpochMs - midnight) / HOUR_MS).toInt().coerceIn(0, 23)
        val perHour = hourly(today, nowEpochMs).take(nowHour + 1)

        val days = past.map { it to running(hourly(it)) }.filter { (_, r) -> r.last() > 0.0 }.takeLast(config.windowDays)
        val same = days.filter { (d, _) -> weekend(d) == weekend }
        val pool = (if (same.size >= config.minSameType) same else days).map { it.second }
        val learning = days.size < config.minDays
        fun band(q: Double) = if (learning) emptyList() else (0..24).map { h -> MovingToday.quantile(pool.map { it[h] }, q) }

        val totals = past.associateWith { d -> hourly(d).sum() }
        val week = (6 downTo 1).map { today.minusDays(it.toLong()) }.map { it to (totals[it] ?: 0.0) } + (today to perHour.sum())
        return ThroughDay(running(perHour), perHour, band(config.lowQuantile), band(config.highQuantile), week)
    }

    private const val HOUR_MS = 60 * 60_000L

    private fun floor(t: Long, midnight: Long): Long = midnight + Math.floorDiv(t - midnight, SLICE_MS) * SLICE_MS
}
