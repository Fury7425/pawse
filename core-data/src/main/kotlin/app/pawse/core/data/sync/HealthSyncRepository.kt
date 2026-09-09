package app.pawse.core.data.sync

import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import app.pawse.core.data.db.ExerciseSessionEntity
import app.pawse.core.data.db.ExerciseDao
import app.pawse.core.data.db.MetricSampleDao
import app.pawse.core.data.db.MetricSampleEntity
import app.pawse.core.data.db.SleepDao
import app.pawse.core.data.health.HealthConnectAvailability
import app.pawse.core.data.health.HealthConnectGateway
import app.pawse.scoring.model.Metric
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

sealed interface SyncResult {
    data class Success(val upserted: Int, val deleted: Int, val fullResync: Boolean) : SyncResult
    data object Unavailable : SyncResult
    data class Failed(val cause: Throwable) : SyncResult
}

/**
 * Incremental Health Connect sync.
 *
 * Change tokens are the mechanism: after the first bounded backfill we ask only
 * for what changed. Tokens expire (roughly 30 days, and immediately after a
 * provider data reset), and an expired token must not be treated as "nothing
 * changed" — it triggers a bounded full re-read instead.
 */
@Singleton
class HealthSyncRepository @Inject constructor(
    private val gateway: HealthConnectGateway,
    private val prefs: SyncPreferences,
    private val metricDao: MetricSampleDao,
    private val sleepDao: SleepDao,
    private val exerciseDao: ExerciseDao,
) {

    /**
     * Types followed by change token.
     *
     * HeartRateRecord is deliberately absent. A continuously-sampling watch writes
     * hundreds of thousands of heart-rate samples a month, and draining that
     * through change tokens on every sync would cost far more than it returns. It
     * is instead read on demand over narrow windows — a sleep session, a workout —
     * by the strain and energy-bank scorers in step 3. [persist] still knows how to
     * map it, so those reads land in the same table.
     */
    private val trackedTypes: List<KClass<out Record>> = listOf(
        HeartRateVariabilityRmssdRecord::class,
        RestingHeartRateRecord::class,
        RespiratoryRateRecord::class,
        OxygenSaturationRecord::class,
        SkinTemperatureRecord::class,
        SleepSessionRecord::class,
        ExerciseSessionRecord::class,
    )

    suspend fun sync(
        zone: ZoneId = ZoneId.systemDefault(),
        backfillDays: Int = DEFAULT_BACKFILL_DAYS,
    ): SyncResult = withContext(Dispatchers.IO) {
        if (gateway.availability() !is HealthConnectAvailability.Available) return@withContext SyncResult.Unavailable

        runCatching {
            var upserted = 0
            var deleted = 0
            var fullResync = false

            for (type in trackedTypes) {
                val name = type.simpleName ?: continue
                val token = prefs.changeToken(name)

                if (token == null) {
                    upserted += backfill(type, zone, backfillDays)
                    fullResync = true
                } else {
                    val outcome = runCatching { drainChanges(type, token, zone) }
                    outcome.onSuccess { (up, del, nextToken, expired) ->
                        if (expired) {
                            upserted += backfill(type, zone, backfillDays)
                            fullResync = true
                        } else {
                            upserted += up
                            deleted += del
                        }
                        prefs.setChangeToken(name, nextToken)
                    }.onFailure {
                        // A token the provider no longer recognises throws rather
                        // than flagging expiry. Same remedy: re-read, re-token.
                        upserted += backfill(type, zone, backfillDays)
                        fullResync = true
                    }
                }

                if (prefs.changeToken(name) == null) {
                    prefs.setChangeToken(name, freshToken(type))
                }
            }

            prefs.setLastSync(System.currentTimeMillis())
            SyncResult.Success(upserted, deleted, fullResync)
        }.getOrElse { SyncResult.Failed(it) }
    }

    private suspend fun freshToken(type: KClass<out Record>): String? =
        gateway.client?.getChangesToken(ChangesTokenRequest(recordTypes = setOf(type)))

    private suspend fun backfill(type: KClass<out Record>, zone: ZoneId, days: Int): Int {
        val end = Instant.now()
        val start = end.minusSeconds(days * 86_400L)
        val records = gateway.readOrEmpty(type, start, end)
        persist(records, zone)
        return records.size
    }

    private data class Drain(
        val upserted: Int,
        val deleted: Int,
        val nextToken: String?,
        val expired: Boolean,
    )

    private suspend fun drainChanges(type: KClass<out Record>, token: String, zone: ZoneId): Drain {
        val client = gateway.client ?: return Drain(0, 0, null, expired = true)
        var current = token
        var upserted = 0
        var deleted = 0

        while (true) {
            val response = client.getChanges(current)
            if (response.changesTokenExpired) return Drain(upserted, deleted, null, expired = true)

            val upserts = response.changes.filterIsInstance<UpsertionChange>().map { it.record }
            val deletions = response.changes.filterIsInstance<DeletionChange>().map { it.recordId }

            if (upserts.isNotEmpty()) {
                persist(upserts, zone)
                upserted += upserts.size
            }
            if (deletions.isNotEmpty()) {
                metricDao.deleteByOriginIds(deletions)
                sleepDao.deleteByOriginIds(deletions)
                exerciseDao.deleteByOriginIds(deletions)
                deleted += deletions.size
            }

            current = response.nextChangesToken
            if (!response.hasMore) return Drain(upserted, deleted, current, expired = false)
        }
    }

    private suspend fun persist(records: List<Record>, zone: ZoneId) {
        val samples = mutableListOf<MetricSampleEntity>()
        val exercises = mutableListOf<ExerciseSessionEntity>()

        for (record in records) {
            when (record) {
                is HeartRateVariabilityRmssdRecord -> samples += RecordMapper.hrv(record, zone)
                is RestingHeartRateRecord -> samples += RecordMapper.restingHeartRate(record, zone)
                is RespiratoryRateRecord -> samples += RecordMapper.respiratoryRate(record, zone)
                is OxygenSaturationRecord -> samples += RecordMapper.spo2(record, zone)
                is SkinTemperatureRecord -> RecordMapper.skinTemperature(record, zone)?.let { samples += it }
                is SleepSessionRecord -> {
                    val (session, stages) = RecordMapper.sleepSession(record, zone)
                    sleepDao.insertSessionWithStages(session, stages)
                }
                is ExerciseSessionRecord -> exercises += RecordMapper.exercise(
                    record = record,
                    zone = zone,
                    meanHeartRate = null,
                    maxHeartRate = null,
                    activeKcal = null,
                )
                is HeartRateRecord -> samples += record.samples.map {
                    MetricSampleEntity(
                        metric = Metric.HEART_RATE.key,
                        value = it.beatsPerMinute.toDouble(),
                        startEpochMs = it.time.toEpochMilli(),
                        endEpochMs = it.time.toEpochMilli(),
                        localDate = RecordMapper.localDate(it.time, record.startZoneOffset, zone),
                        zoneOffsetSeconds = RecordMapper.offsetSeconds(it.time, record.startZoneOffset, zone),
                        sourceApp = record.metadata.dataOrigin.packageName,
                        originId = record.metadata.id,
                    )
                }
            }
        }

        if (samples.isNotEmpty()) metricDao.insertAll(samples)
        if (exercises.isNotEmpty()) exerciseDao.insertAll(exercises)
    }

    companion object {
        /**
         * 90 days. The default baseline window is 60 and the maximum is 90, so a
         * first sync must reach far enough back to fill the widest window a user
         * can configure without a second pass.
         */
        const val DEFAULT_BACKFILL_DAYS = 90
    }
}
