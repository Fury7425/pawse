package app.pawse.core.data.body

import app.pawse.core.data.db.FoodLogDao
import app.pawse.core.data.db.MetricSampleDao
import app.pawse.core.data.db.MetricSampleEntity
import app.pawse.core.data.food.FoodLogMapper
import app.pawse.core.data.health.HealthConnectGateway
import app.pawse.core.data.profile.UserProfilePreferences
import app.pawse.scoring.baseline.BaselineEngine
import app.pawse.scoring.body.Bmr
import app.pawse.scoring.body.BmrResult
import app.pawse.scoring.config.BaselineConfig
import app.pawse.scoring.model.Baseline
import app.pawse.scoring.model.BiologicalSex
import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.MetricHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** One day's reading of one body metric: the last weigh-in of that day. */
data class BodyReading(
    val value: Double,
    val atEpochMs: Long,
    val date: LocalDate,
    /** Package that wrote it. This app's own package means it was typed in here. */
    val sourceApp: String = "",
)

/** A body metric against the user's own usual range. Never coloured: up is not good or bad. */
data class BodyRow(
    val metric: Metric,
    val latest: BodyReading,
    /** Null while there are too few weigh-ins for a range to mean anything. */
    val baseline: Baseline?,
    /** Signed sigmas, positive above usual. Null without a baseline. */
    val z: Double?,
)

/** What the latest weight is made of, from the same morning's scale readings. */
data class BodyComposition(
    val weightKg: Double,
    val fatKg: Double?,
    /** Health Connect's lean mass: muscle, water and bone together. */
    val leanKg: Double?,
    val waterKg: Double?,
    val boneKg: Double?,
) {
    /** Lean mass that is neither water nor bone. Null when there is no lean figure. */
    val otherLeanKg: Double?
        get() = leanKg?.let { (it - (waterKg ?: 0.0) - (boneKg ?: 0.0)).coerceAtLeast(0.0) }
}

/** Eaten against burned, so far today. A plain difference, never a target. */
data class EnergyToday(
    val eatenKcal: Double,
    val itemsWithoutEnergy: Int,
    /** BMR spread across the hours gone so far. Null without any BMR. */
    val restingKcal: Double?,
    /** Health Connect's aggregate. Null without a grant or a writer. */
    val activeKcal: Double?,
    val asOfEpochMs: Long,
) {
    val burnedKcal: Double?
        get() = if (restingKcal == null && activeKcal == null) null else (restingKcal ?: 0.0) + (activeKcal ?: 0.0)

    /** Positive when more was burned than eaten. */
    val differenceKcal: Double? get() = burnedKcal?.minus(eatenKcal)
}

data class DayValue(val date: LocalDate, val value: Double)

data class BodySnapshot(
    val today: LocalDate,
    val weight: BodyReading?,
    /** One value per day with a weigh-in, oldest first, spanning up to a year. */
    val weightSeries: List<DayValue>,
    /** The 30-day usual range the latest weigh-in is judged against. */
    val weightUsual: Baseline?,
    /** The weigh-in nearest a year before the latest one, if there is one close enough. */
    val weightYearAgo: Double?,
    val weighInsLast30Days: Int,
    val composition: BodyComposition?,
    val rows: List<BodyRow>,
    val bmr: BmrResult?,
    val energy: EnergyToday,
) {
    val weighedToday: Boolean get() = weight?.date == today
    val daysSinceWeighIn: Long? get() = weight?.let { ChronoUnit.DAYS.between(it.date, today) }
    val hasAnyData: Boolean get() = weight != null || rows.isNotEmpty()
}

/**
 * The Body tab, as data.
 *
 * Everything comes from what a scale already wrote to Health Connect, except
 * today's eaten total (the food log) and active energy (aggregated live). Nothing
 * here feeds a score, and nothing here has a direction that counts as "better".
 */
@Singleton
class BodyRepository @Inject constructor(
    private val metricDao: MetricSampleDao,
    private val foodLogDao: FoodLogDao,
    private val gateway: HealthConnectGateway,
    private val profilePreferences: UserProfilePreferences,
) {

    suspend fun snapshot(now: ZonedDateTime = ZonedDateTime.now()): BodySnapshot = withContext(Dispatchers.IO) {
        val today = now.toLocalDate()
        val from = today.minusDays(HISTORY_DAYS).toString()
        val samples = BodyAssembler.METRICS.associateWith { metricDao.range(it.key, from, today.toString()) }

        val food = foodLogDao.day(today.toString()).map(FoodLogMapper::toEntry)
        val totals = FoodLogMapper.totals(today.toString(), food)
        val startOfDay = today.atStartOfDay(now.zone)
        val active = gateway.activeKcal(startOfDay.toInstant(), now.toInstant())
        val profile = profilePreferences.current()

        BodyAssembler.assemble(
            samples = samples,
            today = today,
            ageYears = profile.ageYears,
            sex = profile.biologicalSex,
            eatenKcal = totals.totals.kcal ?: 0.0,
            itemsWithoutEnergy = totals.entriesWithoutEnergy,
            activeKcal = active,
            fractionOfDay = ChronoUnit.SECONDS.between(startOfDay, now) / 86_400.0,
            nowEpochMs = now.toInstant().toEpochMilli(),
        )
    }

    private companion object {
        /** A year for the chart, plus the slack the year-ago comparison looks into. */
        const val HISTORY_DAYS = 400L
    }
}

/** Storage rows to a [BodySnapshot]. Pure, so it is tested without a device. */
object BodyAssembler {

    val METRICS = listOf(
        Metric.BODY_WEIGHT,
        Metric.BODY_FAT,
        Metric.LEAN_MASS,
        Metric.BODY_WATER,
        Metric.BONE_MASS,
        Metric.BODY_HEIGHT,
        Metric.BASAL_METABOLIC_RATE,
    )

    /** The rows under "Against your usual", in the order the design lists them. */
    private val ROW_METRICS = listOf(
        Metric.BODY_FAT,
        Metric.LEAN_MASS,
        Metric.BODY_WATER,
        Metric.BONE_MASS,
        Metric.BASAL_METABOLIC_RATE,
    )

    /**
     * 30 days, because a weigh-in a few times a week fills it and "your 30-day usual"
     * is a sentence a person can check against memory. Five weigh-ins before a range
     * is drawn: fewer than that and the spread is the scale's noise, not the body's.
     */
    private val engine = BaselineEngine(BaselineConfig(windowDays = 30, halfLifeDays = 14.0, warmupSamples = 5))

    fun assemble(
        samples: Map<Metric, List<MetricSampleEntity>>,
        today: LocalDate,
        ageYears: Int?,
        sex: BiologicalSex,
        eatenKcal: Double,
        itemsWithoutEnergy: Int,
        activeKcal: Double?,
        fractionOfDay: Double,
        nowEpochMs: Long,
    ): BodySnapshot {
        val daily = samples.mapValues { (_, rows) -> dailyReadings(rows) }
        val weights = daily[Metric.BODY_WEIGHT].orEmpty()
        val weight = weights.lastOrNull()

        fun on(metric: Metric, date: LocalDate?): Double? =
            date?.let { d -> daily[metric].orEmpty().lastOrNull { it.date == d }?.value }

        val fatPercent = on(Metric.BODY_FAT, weight?.date)
        val fatKg = if (weight != null && fatPercent != null) weight.value * fatPercent / 100.0 else null
        val leanKg = on(Metric.LEAN_MASS, weight?.date) ?: fatKg?.let { weight!!.value - it }
        val composition = if (weight != null && (fatKg != null || leanKg != null)) {
            BodyComposition(
                weightKg = weight.value,
                fatKg = fatKg,
                leanKg = leanKg,
                waterKg = on(Metric.BODY_WATER, weight.date),
                boneKg = on(Metric.BONE_MASS, weight.date),
            )
        } else {
            null
        }

        // A BMR the scale wrote the same morning is used as it is. An older one is
        // a figure for a body that has since been weighed again, so the formula wins.
        val measuredBmr = daily[Metric.BASAL_METABOLIC_RATE].orEmpty().lastOrNull()
            ?.takeIf { weight == null || it.date == weight.date }
            ?.value
        val bmr = Bmr.select(
            measuredKcal = measuredBmr,
            leanMassKg = leanKg,
            weightKg = weight?.value,
            heightM = daily[Metric.BODY_HEIGHT].orEmpty().lastOrNull()?.value,
            ageYears = ageYears,
            sex = sex,
        )

        val rows = ROW_METRICS.mapNotNull { metric ->
            val readings = daily[metric].orEmpty()
            val latest = readings.lastOrNull() ?: return@mapNotNull null
            val baseline = baselineOf(metric, readings)
            BodyRow(metric, latest, baseline, baseline?.let { engine.z(it, latest.value) })
        }

        return BodySnapshot(
            today = today,
            weight = weight,
            weightSeries = weights.map { DayValue(it.date, it.value) },
            weightUsual = baselineOf(Metric.BODY_WEIGHT, weights),
            weightYearAgo = weight?.let { yearAgo(weights, it.date) },
            weighInsLast30Days = weights.count { ChronoUnit.DAYS.between(it.date, today) in 0..29 },
            composition = composition,
            rows = rows,
            bmr = bmr,
            energy = EnergyToday(
                eatenKcal = eatenKcal,
                itemsWithoutEnergy = itemsWithoutEnergy,
                restingKcal = bmr?.let { Bmr.restingSoFar(it.kcalPerDay, fractionOfDay) },
                activeKcal = activeKcal,
                asOfEpochMs = nowEpochMs,
            ),
        )
    }

    /**
     * One reading per day, the day's last. Two weigh-ins in a morning are the same
     * body measured twice; averaging them would invent a value neither scale showed.
     */
    private fun dailyReadings(rows: List<MetricSampleEntity>): List<BodyReading> =
        rows.groupBy { it.localDate }
            .map { (date, sameDay) ->
                val last = sameDay.maxBy { it.startEpochMs }
                BodyReading(last.value, last.startEpochMs, LocalDate.parse(date), last.sourceApp)
            }
            .sortedBy { it.date }

    /** Judged against the days before the latest reading, never including it. */
    private fun baselineOf(metric: Metric, readings: List<BodyReading>): Baseline? {
        val latest = readings.lastOrNull() ?: return null
        val history = MetricHistory(
            metric = metric,
            samples = readings.map { MetricHistory.Sample(ChronoUnit.DAYS.between(it.date, latest.date).toInt(), it.value) },
        )
        return engine.baseline(history)?.takeIf { !it.warmingUp }
    }

    private fun yearAgo(readings: List<BodyReading>, latest: LocalDate): Double? {
        val target = latest.minusYears(1)
        return readings
            .filter { abs(ChronoUnit.DAYS.between(it.date, target)) <= YEAR_AGO_SLACK_DAYS }
            .minByOrNull { abs(ChronoUnit.DAYS.between(it.date, target)) }
            ?.value
    }

    /** "A year ago" may be a fortnight either side of it; further than that it is not. */
    private const val YEAR_AGO_SLACK_DAYS = 14L
}
