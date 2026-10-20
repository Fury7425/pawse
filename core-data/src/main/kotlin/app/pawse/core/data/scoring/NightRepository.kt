package app.pawse.core.data.scoring

import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import app.pawse.core.data.db.MetricSampleDao
import app.pawse.core.data.db.SleepDao
import app.pawse.core.data.health.HealthConnectGateway
import app.pawse.scoring.model.Metric
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** One reading on a night's curve. */
data class CurvePoint(val epochMs: Long, val value: Double)

/**
 * A night opened on the Sleep tab: what the stage chart and the overnight curves
 * draw. Its figures (sleeping HR, HRV, the score) are not here; they are the
 * stored ones in [ScoringSnapshot.nights], so a curve can never disagree with the
 * number printed above it.
 */
data class NightDetail(
    val date: LocalDate,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val spans: List<StageSpan>,
    /** Five-minute means of the raw series. */
    val heartRate: List<CurvePoint>,
    val hrv: List<CurvePoint>,
    val spo2: List<CurvePoint>,
    val breathing: List<CurvePoint>,
    /** Against the device's own baseline, °C. */
    val skinTemp: List<CurvePoint>,
)

/**
 * Reads one night in full when it is opened. Heart rate and skin temperature come
 * straight from Health Connect, because Pawse never stores the raw series; HRV,
 * blood oxygen and breathing are already stored reading by reading.
 */
@Singleton
class NightRepository @Inject constructor(
    private val sleepDao: SleepDao,
    private val metricDao: MetricSampleDao,
    private val gateway: HealthConnectGateway,
) {

    suspend fun read(date: LocalDate): NightDetail? = withContext(Dispatchers.IO) {
        val sessions = sleepDao.sessionsOn(date.toString()).filter { !it.isNap }
        if (sessions.isEmpty()) return@withContext null
        val stages = sessions.associate { it.id to sleepDao.stagesFor(it.id) }
        val staged = NightAssembler.summarise(date.toString(), sessions, stages).hasStages
        val start = sessions.minOf { it.startEpochMs }
        val end = sessions.maxOf { it.endEpochMs }
        val from = Instant.ofEpochMilli(start)
        val to = Instant.ofEpochMilli(end)

        val bin = CURVE_BIN_MINUTES * 60_000L
        val heartRate = gateway.readOrEmpty(HeartRateRecord::class, from, to)
            .flatMap { r -> r.samples.map { it.time.toEpochMilli() to it.beatsPerMinute.toDouble() } }
            .filter { it.first in start until end && it.second > 0.0 }
            .groupBy { (it.first - start) / bin }
            .toSortedMap()
            .map { (i, xs) -> CurvePoint(start + i * bin + bin / 2, xs.map { it.second }.average()) }
        val skinTemp = gateway.readOrEmpty(SkinTemperatureRecord::class, from, to)
            .flatMap { r -> r.deltas.map { CurvePoint(it.time.toEpochMilli(), it.delta.inCelsius) } }
            .filter { it.epochMs in start..end }
            .sortedBy { it.epochMs }

        // One writer per curve, the one that wrote most of the night, so two apps
        // mirroring the same watch never draw a zigzag between them.
        suspend fun stored(metric: Metric): List<CurvePoint> {
            val rows = metricDao.window(metric.key, start, end)
            val writer = rows.groupingBy { it.sourceApp }.eachCount().maxByOrNull { it.value }?.key
            return rows.filter { it.sourceApp == writer }.map { CurvePoint(it.startEpochMs, it.value) }
        }

        NightDetail(
            date = date,
            startEpochMs = start,
            endEpochMs = end,
            spans = NightAssembler.spans(sessions, stages, staged),
            heartRate = heartRate,
            hrv = stored(Metric.HRV_RMSSD),
            spo2 = stored(Metric.SPO2),
            breathing = stored(Metric.RESPIRATORY_RATE),
            skinTemp = skinTemp,
        )
    }

    private companion object {
        const val CURVE_BIN_MINUTES = 5
    }
}
