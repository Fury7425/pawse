package app.pawse.core.data.health

import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.ScoreType
import app.pawse.scoring.tr
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
        val weight = gateway.readOrEmpty(WeightRecord::class, start, end)
        val bodyParts = listOfNotNull(
            tr("body fat", "체지방").takeIf { gateway.readOrEmpty(BodyFatRecord::class, start, end).isNotEmpty() },
            tr("lean mass", "제지방량").takeIf { gateway.readOrEmpty(LeanBodyMassRecord::class, start, end).isNotEmpty() },
            tr("water", "체수분").takeIf { gateway.readOrEmpty(BodyWaterMassRecord::class, start, end).isNotEmpty() },
            tr("bone", "골량").takeIf { gateway.readOrEmpty(BoneMassRecord::class, start, end).isNotEmpty() },
        )

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
            // Raw heart rate, from which Pawse computes its own sleeping HR.
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
            scores = scoreCapabilities(metrics, hrvShape, nightsWithSleep, exercise.size),
            writerApps = writerApps,
            contestedTypes = contested,
            sleepStageCoverage = stageCoverage,
            hrvShape = hrvShape,
            weighIns = weight.map { it.time.localDate(zone) }.toSet().size,
            bodyParts = bodyParts,
            samsungChecklist = if (SAMSUNG_HEALTH in writerApps) {
                listOf(
                    tr("Heart rate variability", "심박 변이도") to (hrv.isNotEmpty()),
                    tr("Skin temperature", "피부 온도") to (temp.isNotEmpty()),
                    tr("Respiratory rate", "호흡수") to (resp.isNotEmpty()),
                    tr("Resting heart rate", "안정 시 심박수") to (rhr.isNotEmpty()),
                )
            } else {
                null
            },
        )
    }

    private fun scoreCapabilities(
        metrics: List<MetricCapability>,
        hrvShape: HrvShape,
        nightsWithSleep: Int,
        exerciseSessions: Int,
    ): List<ScoreCapability> {
        val byMetric = metrics.associateBy { it.metric }
        fun present(m: Metric) = byMetric[m]?.present == true
        val hrvOk = hrvShape == HrvShape.OVERNIGHT_SERIES || hrvShape == HrvShape.SPARSE_OVERNIGHT
        // Body reads two views of one nervous system. Pawse's own sleeping HR needs
        // the raw series; the watch's resting HR stands in without it.
        val hrOk = present(Metric.HEART_RATE) || present(Metric.RESTING_HEART_RATE)

        return listOf(
            ScoreCapability(
                type = ScoreType.BODY,
                computable = hrvOk || hrOk,
                expectedCoverage = listOf(hrvOk, hrOk).count { it } / 2f,
                degraded = !(hrvOk && hrOk),
                missing = listOfNotNull(
                    Metric.HRV_RMSSD.takeIf { !hrvOk },
                    Metric.RESTING_HEART_RATE.takeIf { !hrOk },
                ),
                explanation = when {
                    hrvOk && hrOk -> tr(
                        "Overnight HRV and heart rate are both there, so Body reads both.",
                        "밤사이 HRV와 심박수가 모두 있어서 컨디션이 둘 다 읽어요.",
                    )
                    hrvShape == HrvShape.DAYTIME_SPOT_ONLY -> tr(
                        "Your device writes HRV only as daytime readings. Body needs overnight values, " +
                            "so it will read your sleeping heart rate alone.",
                        "기기가 HRV를 낮 시간 측정값으로만 기록해요. 컨디션에는 밤사이 값이 필요해서 " +
                            "수면 중 심박수만 읽어요.",
                    )
                    hrOk -> tr(
                        "No overnight HRV, so Body reads your sleeping heart rate alone, with a wider range.",
                        "밤사이 HRV가 없어서 컨디션은 수면 중 심박수만 읽고, 범위를 더 넓게 잡아요.",
                    )
                    hrvOk -> tr(
                        "No overnight heart rate, so Body reads HRV alone, with a wider range.",
                        "밤사이 심박수가 없어서 컨디션은 HRV만 읽고, 범위를 더 넓게 잡아요.",
                    )
                    else -> tr(
                        "Neither overnight HRV nor heart rate is written to Health Connect, so Body has nothing to read.",
                        "헬스 커넥트에 밤사이 HRV도 심박수도 기록되지 않아서 컨디션이 읽을 게 없어요.",
                    )
                },
            ),
            ScoreCapability(
                type = ScoreType.SLEEP,
                computable = nightsWithSleep > 0,
                expectedCoverage = if (nightsWithSleep == 0) 0f else 1f,
                degraded = false,
                explanation = if (nightsWithSleep == 0) {
                    tr("No sleep sessions found yet.", "아직 수면 기록을 찾지 못했어요.")
                } else {
                    tr(
                        "Sleep debt in hours and how regular your timing is. Stages are shown, never scored.",
                        "수면 부채를 시간 단위로, 수면 시간이 얼마나 규칙적인지 보여줘요. 수면 단계는 보여주기만 하고 점수에 넣지 않아요.",
                    )
                },
            ),
            ScoreCapability(
                type = ScoreType.LOAD,
                computable = exerciseSessions > 0,
                expectedCoverage = if (exerciseSessions > 0) 1f else 0f,
                degraded = false,
                explanation = if (exerciseSessions > 0) {
                    tr("Workouts found, so Load can build fitness and fatigue from your training.", "운동 기록이 있어서 부하가 훈련으로 체력과 피로를 계산할 수 있어요.")
                } else {
                    tr("No workouts recorded yet. Load fills in from your first one.", "아직 기록된 운동이 없어요. 첫 운동부터 부하가 채워져요.")
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
        HrvShape.ABSENT -> tr("No HRV records from any app.", "어떤 앱에도 HRV 기록이 없어요.")
        HrvShape.OVERNIGHT_SERIES -> tr("Overnight HRV on most nights.", "대부분의 밤에 밤사이 HRV가 있어요.")
        HrvShape.DAYTIME_SPOT_ONLY -> tr("Daytime spot readings only, not usable overnight.", "낮 시간 측정값만 있어서 밤사이 값으로 쓸 수 없어요.")
        HrvShape.SPARSE_OVERNIGHT -> tr("Overnight HRV on some nights only.", "일부 밤에만 밤사이 HRV가 있어요.")
    }

    private fun List<Record>.distinctOrigins(): Int =
        map { it.metadata.dataOrigin.packageName }.filter { it.isNotBlank() }.distinct().size

    private fun Instant.localDate(zone: ZoneId): LocalDate = atZone(zone).toLocalDate()

    companion object {
        const val DEFAULT_PROBE_DAYS = 30

        const val SAMSUNG_HEALTH = "com.sec.android.app.shealth"

        /** Nights with an in-sleep HRV value needed to call it a real overnight series. */
        const val HRV_NIGHTS_FOR_SERIES = 0.5f

        const val DAYTIME_DOMINANCE = 0.2f
    }
}
