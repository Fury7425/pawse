package app.pawse.core.data.health

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import app.pawse.scoring.config.ScoringConfig
import app.pawse.scoring.config.SleepProfile
import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.ScoreType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Onboarding capability probe.
 *
 * The premise: on Android the inputs vary wildly by writer app. Samsung Health,
 * Garmin Connect, Fitbit, Zepp, Polar Flow and Mi Fitness each write a different
 * subset. Many write sleep sessions with no stages. Many write no HRV at all.
 * Some write HRV as sparse daytime spot readings rather than an overnight series.
 *
 * So instead of assuming a data shape and producing a confident wrong number, we
 * read 30 days back once, measure what is actually there, and tell the user which
 * scores we can compute and at what confidence. Nothing here fabricates a value:
 * an absent input is reported absent.
 */
@Singleton
class CapabilityProbe @Inject constructor(
    private val gateway: HealthConnectGateway,
) {

    suspend fun probe(
        days: Int = DEFAULT_PROBE_DAYS,
        zone: ZoneId = ZoneId.systemDefault(),
        config: ScoringConfig = ScoringConfig.DEFAULT,
    ): CapabilityReport = withContext(Dispatchers.Default) {
        val end = Instant.now()
        val start = end.minusSeconds(days * 86_400L)

        val sleep = gateway.readOrEmpty(SleepSessionRecord::class, start, end)
        val hrv = gateway.readOrEmpty(HeartRateVariabilityRmssdRecord::class, start, end)
        val rhr = gateway.readOrEmpty(RestingHeartRateRecord::class, start, end)
        val hr = gateway.readOrEmpty(HeartRateRecord::class, start, end)
        val resp = gateway.readOrEmpty(RespiratoryRateRecord::class, start, end)
        val spo2 = gateway.readOrEmpty(OxygenSaturationRecord::class, start, end)
        val temp = gateway.readOrEmpty(SkinTemperatureRecord::class, start, end)
        val exercise = gateway.readOrEmpty(ExerciseSessionRecord::class, start, end)

        val sleepDays = sleep.map { it.endTime.localDate(zone) }.toSet()
        val nightsWithSleep = sleepDays.size

        // A sleep session with an empty stages list is common: several writer apps
        // publish only the session envelope. That is the single most consequential
        // gap on Android, because it decides which sleep profile can run at all.
        val staged = sleep.count { it.stages.isNotEmpty() }
        val stageCoverage = if (sleep.isEmpty()) 0f else staged.toFloat() / sleep.size

        val sleepWindows = sleep.map { it.startTime to it.endTime }
        val hrvOvernight = hrv.count { rec -> sleepWindows.any { (s, e) -> rec.time >= s && rec.time <= e } }
        val hrvShape = classifyHrv(
            total = hrv.size,
            overnight = hrvOvernight,
            nightsCovered = hrv.filter { rec -> sleepWindows.any { (s, e) -> rec.time >= s && rec.time <= e } }
                .map { it.time.localDate(zone) }.toSet().size,
            nights = nightsWithSleep,
        )

        val metrics = listOf(
            capability(Metric.HRV_RMSSD, hrv, days, zone) { it.time }
                .copy(note = hrvShape.note()),
            capability(Metric.RESTING_HEART_RATE, rhr, days, zone) { it.time },
            capability(Metric.RESPIRATORY_RATE, resp, days, zone) { it.time },
            capability(Metric.SPO2, spo2, days, zone) { it.time },
            capability(Metric.SKIN_TEMPERATURE, temp, days, zone) { it.startTime },
            capability(Metric.TIME_ASLEEP, sleep, days, zone) { it.endTime },
            // Raw continuous heart rate, which is what Strain and the Energy Bank
            // need. The sleep heart-rate dip is derived from it rather than being
            // the same quantity, so the two have separate metrics.
            capability(Metric.HEART_RATE, hr, days, zone) { it.startTime },
        )

        val writerApps = buildSet {
            addAll(sleep.map { it.metadata.dataOrigin.packageName })
            addAll(hrv.map { it.metadata.dataOrigin.packageName })
            addAll(rhr.map { it.metadata.dataOrigin.packageName })
            addAll(hr.map { it.metadata.dataOrigin.packageName })
            addAll(exercise.map { it.metadata.dataOrigin.packageName })
        }.filter { it.isNotBlank() }.toSet()

        // Two apps writing the same record type is the duplicate-overlap case:
        // e.g. Galaxy Watch via Samsung Health and the same watch mirrored by a
        // third-party sync app. The sync layer resolves it by source priority.
        val contested = buildSet {
            if (sleep.distinctOrigins() > 1) add("SleepSession")
            if (hrv.distinctOrigins() > 1) add("HeartRateVariabilityRmssd")
            if (rhr.distinctOrigins() > 1) add("RestingHeartRate")
            if (hr.distinctOrigins() > 1) add("HeartRate")
            if (exercise.distinctOrigins() > 1) add("ExerciseSession")
        }

        CapabilityReport(
            probedDays = days,
            nightsWithSleep = nightsWithSleep,
            metrics = metrics,
            scores = scoreCapabilities(metrics, stageCoverage, hrvShape, nightsWithSleep, exercise.size, config),
            writerApps = writerApps,
            contestedTypes = contested,
            sleepStageCoverage = stageCoverage,
            hrvShape = hrvShape,
        )
    }

    private fun scoreCapabilities(
        metrics: List<MetricCapability>,
        stageCoverage: Float,
        hrvShape: HrvShape,
        nightsWithSleep: Int,
        exerciseSessions: Int,
        config: ScoringConfig,
    ): List<ScoreCapability> {
        val byMetric = metrics.associateBy { it.metric }
        fun present(m: Metric) = byMetric[m]?.present == true

        // Recovery coverage is weight mass available, not metric count: losing HRV
        // costs 0.60 of the score, losing respiratory rate costs 0.07.
        val nominal = config.recovery.weights
        val available = nominal.entries.sumOf { (metric, w) ->
            val ok = when (metric) {
                Metric.HRV_RMSSD -> hrvShape == HrvShape.OVERNIGHT_SERIES || hrvShape == HrvShape.SPARSE_OVERNIGHT
                Metric.SLEEP_SCORE -> nightsWithSleep > 0
                else -> present(metric)
            }
            if (ok) w.weight else 0.0
        }
        val recoveryCoverage = (available / nominal.values.sumOf { it.weight }).toFloat()
        val recoveryMissing = nominal.keys.filter { m ->
            when (m) {
                Metric.HRV_RMSSD -> hrvShape == HrvShape.ABSENT || hrvShape == HrvShape.DAYTIME_SPOT_ONLY
                Metric.SLEEP_SCORE -> nightsWithSleep == 0
                else -> !present(m)
            }
        }

        val sleepProfile = when {
            nightsWithSleep == 0 -> null
            stageCoverage >= STAGE_COVERAGE_FOR_OURA -> SleepProfile.OURA_RECOVERED
            else -> SleepProfile.APPLE_PUBLISHED
        }

        return listOf(
            ScoreCapability(
                type = ScoreType.RECOVERY,
                computable = recoveryCoverage >= config.recovery.refuseBelowCoverage,
                expectedCoverage = recoveryCoverage,
                degraded = recoveryCoverage < config.recovery.degradedBelowCoverage,
                missing = recoveryMissing,
                explanation = when {
                    hrvShape == HrvShape.ABSENT ->
                        "Your device does not write heart rate variability to Health Connect, " +
                            "so Recovery would be missing the signal it leans on most."
                    hrvShape == HrvShape.DAYTIME_SPOT_ONLY ->
                        "Your device writes heart rate variability only as occasional daytime " +
                            "readings. Recovery needs overnight values, so we will not fake one."
                    recoveryCoverage >= config.recovery.degradedBelowCoverage ->
                        "We can compute Recovery from your overnight data."
                    else ->
                        "We can compute Recovery, but from a reduced set of inputs. " +
                            "The score will be marked as lower confidence."
                },
            ),
            ScoreCapability(
                type = ScoreType.SLEEP,
                computable = nightsWithSleep > 0,
                expectedCoverage = if (nightsWithSleep == 0) 0f else maxOf(stageCoverage, 0.6f),
                degraded = stageCoverage < STAGE_COVERAGE_FOR_OURA && nightsWithSleep > 0,
                selectedProfile = sleepProfile,
                explanation = when {
                    nightsWithSleep == 0 -> "No sleep sessions found, so there is nothing to score yet."
                    sleepProfile == SleepProfile.OURA_RECOVERED ->
                        "Your device records sleep stages, so we can use the detailed sleep model."
                    else ->
                        "Your device records sleep without stage detail, so we will use the " +
                            "duration, consistency and interruption model instead."
                },
            ),
            ScoreCapability(
                type = ScoreType.STRAIN,
                computable = present(Metric.HEART_RATE) || exerciseSessions > 0,
                expectedCoverage = if (exerciseSessions > 0) 1f else 0.5f,
                degraded = exerciseSessions == 0,
                explanation = if (exerciseSessions > 0) {
                    "Workouts and continuous heart rate are available, so Strain covers both " +
                        "training and the rest of your day."
                } else {
                    "No workouts found yet. Strain will start from your all-day heart rate only."
                },
            ),
            ScoreCapability(
                type = ScoreType.ENERGY_BANK,
                computable = present(Metric.HEART_RATE) && nightsWithSleep > 0,
                expectedCoverage = if (present(Metric.HEART_RATE)) 1f else 0f,
                degraded = !present(Metric.HEART_RATE),
                explanation = if (present(Metric.HEART_RATE) && nightsWithSleep > 0) {
                    "Continuous heart rate is available, so the Energy Bank can run through the day."
                } else {
                    "The Energy Bank needs all-day heart rate, which your device is not writing."
                },
            ),
            ScoreCapability(
                type = ScoreType.LOAD_RATIO,
                computable = exerciseSessions > 0,
                expectedCoverage = if (exerciseSessions > 0) 1f else 0f,
                degraded = false,
                explanation = if (exerciseSessions > 0) {
                    "Enough workout history to compare this week against the last month."
                } else {
                    "No workouts recorded, so there is no training load to compare."
                },
            ),
        )
    }

    private fun <T : Record> capability(
        metric: Metric,
        records: List<T>,
        days: Int,
        zone: ZoneId,
        timeOf: (T) -> Instant,
    ): MetricCapability {
        val daysWithData = records.map { timeOf(it).localDate(zone) }.toSet().size
        return MetricCapability(
            metric = metric,
            coverage = daysWithData.toFloat() / days,
            daysWithData = daysWithData,
            writerApps = records.map { it.metadata.dataOrigin.packageName }.filter { it.isNotBlank() }.toSet(),
        )
    }

    private fun classifyHrv(total: Int, overnight: Int, nightsCovered: Int, nights: Int): HrvShape = when {
        total == 0 -> HrvShape.ABSENT
        overnight == 0 -> HrvShape.DAYTIME_SPOT_ONLY
        nights > 0 && nightsCovered.toFloat() / nights >= HRV_NIGHTS_FOR_SERIES -> HrvShape.OVERNIGHT_SERIES
        overnight.toFloat() / total < DAYTIME_DOMINANCE && nightsCovered <= 2 -> HrvShape.DAYTIME_SPOT_ONLY
        else -> HrvShape.SPARSE_OVERNIGHT
    }

    private fun HrvShape.note(): String = when (this) {
        HrvShape.ABSENT -> "No HRV records from any app."
        HrvShape.OVERNIGHT_SERIES -> "Overnight HRV on most nights."
        HrvShape.DAYTIME_SPOT_ONLY -> "Daytime spot readings only, not usable for Recovery."
        HrvShape.SPARSE_OVERNIGHT -> "Overnight HRV on some nights only."
    }

    private fun List<Record>.distinctOrigins(): Int =
        map { it.metadata.dataOrigin.packageName }.filter { it.isNotBlank() }.distinct().size

    private fun Instant.localDate(zone: ZoneId): LocalDate = atZone(zone).toLocalDate()

    companion object {
        const val DEFAULT_PROBE_DAYS = 30

        /** Below this share of staged nights the Oura-recovered profile is not honest. */
        const val STAGE_COVERAGE_FOR_OURA = 0.5f

        /** Nights with an in-sleep HRV value needed to call it a real overnight series. */
        const val HRV_NIGHTS_FOR_SERIES = 0.5f

        const val DAYTIME_DOMINANCE = 0.2f
    }
}
