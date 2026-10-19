package app.pawse.core.data.sync

import androidx.health.connect.client.records.HeartRateRecord
import app.pawse.core.data.db.MetricSampleDao
import app.pawse.core.data.db.MetricSampleEntity
import app.pawse.core.data.db.SleepDao
import app.pawse.core.data.health.HealthConnectAvailability
import app.pawse.core.data.health.HealthConnectGateway
import app.pawse.scoring.config.ScoringConfig
import app.pawse.scoring.model.Metric
import app.pawse.scoring.signal.SignalLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The signal layer's heart-rate half: Pawse computes each night's sleeping HR
 * itself, from the raw series, instead of trusting three vendors' three
 * definitions of "resting".
 *
 * Heart rate is never synced wholesale (see [HealthSyncRepository]). Each night's
 * window is read once, reduced to three numbers — sleeping HR, when its low point
 * fell, and how much of the night had data — and stored as samples like anything
 * else. The last two nights are recomputed every time, because a watch often
 * uploads the night's series hours after the sleep session itself.
 */
@Singleton
class NightSignalEnricher @Inject constructor(
    private val gateway: HealthConnectGateway,
    private val sleepDao: SleepDao,
    private val metricDao: MetricSampleDao,
) {
    private val config = ScoringConfig.DEFAULT.signal

    /** @return how many nights were (re)computed. */
    suspend fun enrich(from: String, to: String, limit: Int = DEFAULT_LIMIT): Int = withContext(Dispatchers.IO) {
        if (gateway.availability() !is HealthConnectAvailability.Available) return@withContext 0
        val recentFrom = LocalDate.parse(to).minusDays(RECOMPUTE_DAYS).toString()

        val nights = sleepDao.sessionsBetween(from, to)
            .filter { !it.isNap }
            .groupBy { it.localDate }
            .map { (date, sessions) -> Triple(date, sessions.minOf { it.startEpochMs }, sessions.maxOf { it.endEpochMs }) }
            .sortedByDescending { it.first }

        var done = 0
        for ((date, start, end) in nights) {
            if (done >= limit) break
            val known = metricDao.window(Metric.NIGHT_HR_COVERAGE.key, start, end).isNotEmpty()
            if (known && date < recentFrom) continue

            val records = gateway.readOrEmpty(HeartRateRecord::class, Instant.ofEpochMilli(start), Instant.ofEpochMilli(end))
            val samples = records.flatMap { r -> r.samples.map { it.time.toEpochMilli() to it.beatsPerMinute.toDouble() } }
            val night = SignalLayer.heartRate(samples, start, end, config)
            // The writer with the most records owns the night, so its baseline stays its own.
            val source = records.groupingBy { it.metadata.dataOrigin.packageName }.eachCount()
                .maxByOrNull { it.value }?.key ?: NO_SOURCE
            val offset = sleepDao.sessionsOn(date).lastOrNull()?.zoneOffsetSeconds ?: 0

            fun row(metric: Metric, value: Double) = MetricSampleEntity(
                metric = metric.key,
                value = value,
                startEpochMs = start,
                endEpochMs = end,
                localDate = date,
                zoneOffsetSeconds = offset,
                sourceApp = source,
                originId = "pawse-night-$start-${metric.key}",
            )

            val rows = listOfNotNull(
                // Written even when empty, so a watch that never writes heart rate is
                // not asked again for every night on every sync.
                row(Metric.NIGHT_HR_COVERAGE, night.coverage),
                night.sleepingBpm?.let { row(Metric.SLEEPING_HEART_RATE, it) },
                night.nadirFraction?.let { row(Metric.HR_NADIR, it) },
            )
            metricDao.deleteByOriginIds(DERIVED.map { "pawse-night-$start-${it.key}" })
            metricDao.insertAll(rows)
            done++
        }
        done
    }

    private companion object {
        const val DEFAULT_LIMIT = 40
        const val RECOMPUTE_DAYS = 2L
        const val NO_SOURCE = "none"
        val DERIVED = listOf(Metric.NIGHT_HR_COVERAGE, Metric.SLEEPING_HEART_RATE, Metric.HR_NADIR)
    }
}
