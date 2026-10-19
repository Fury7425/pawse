package app.pawse.core.data.body

import android.content.Context
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import app.pawse.core.data.db.MetricSampleDao
import app.pawse.core.data.health.HealthConnectGateway
import app.pawse.core.data.sync.RecordMapper
import app.pawse.scoring.model.Metric
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

/** One weigh-in typed in by hand. Every figure but weight is optional, and a blank stays blank. */
data class WeighIn(
    val at: Instant,
    val weightKg: Double,
    val fatPercent: Double? = null,
    val leanKg: Double? = null,
    val waterKg: Double? = null,
    val boneKg: Double? = null,
)

/** A weigh-in as the "Recent weigh-ins" list shows it. */
data class RecentWeighIn(
    val atEpochMs: Long,
    val weightKg: Double,
    /** Written by this app, so this app may remove it. */
    val typedIn: Boolean,
    /** The writing app's name, when the phone will tell us. */
    val sourceLabel: String?,
)

/**
 * Typed-in weigh-ins, written to Health Connect.
 *
 * Health Connect is the store here, not a mirror: the Body tab reads what the sync
 * copied into Room, so a write is copied into Room straight away with the id Health
 * Connect returned. The next sync sees the same record and the unique index ignores
 * it, and removing it here removes both copies.
 *
 * Every record is tagged as a manual entry, so any reader downstream can tell a
 * number someone typed from a number a scale measured.
 */
@Singleton
class BodyWriter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gateway: HealthConnectGateway,
    private val metricDao: MetricSampleDao,
) {

    /** The write grants a weigh-in needs: only the figures it actually carries. */
    fun permissionsFor(weighIn: WeighIn): Set<String> =
        records(weighIn, ZoneId.systemDefault()).map { HealthPermission.getWritePermission(it::class) }.toSet()

    /** @return false when nothing was saved: no Health Connect, no grant, or a refusal. */
    suspend fun write(weighIn: WeighIn, zone: ZoneId = ZoneId.systemDefault()): Boolean = withContext(Dispatchers.IO) {
        val client = gateway.client ?: return@withContext false
        try {
            val records = records(weighIn, zone)
            val ids = client.insertRecords(records).recordIdsList
            metricDao.insertAll(
                records.zip(ids).mapNotNull { (record, id) ->
                    RecordMapper.body(record, zone)?.copy(sourceApp = context.packageName, originId = id)
                },
            )
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            false
        }
    }

    /**
     * Removes a typed-in weigh-in and the composition saved with it, here and in
     * Health Connect. Another app's readings are never touched.
     *
     * @return what was removed, so Undo can write it back; null when nothing was.
     */
    suspend fun remove(atEpochMs: Long): WeighIn? = withContext(Dispatchers.IO) {
        val client = gateway.client ?: return@withContext null
        val rows = TYPES.keys.associateWith { metric ->
            metricDao.window(metric.key, atEpochMs, atEpochMs).filter { it.sourceApp == context.packageName }
        }
        val weight = rows[Metric.BODY_WEIGHT]?.firstOrNull() ?: return@withContext null
        try {
            for ((metric, sameTime) in rows) {
                if (sameTime.isEmpty()) continue
                val ids = sameTime.map { it.originId }
                client.deleteRecords(TYPES.getValue(metric), recordIdsList = ids, clientRecordIdsList = emptyList())
                metricDao.deleteByOriginIds(ids)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            return@withContext null
        }
        fun value(metric: Metric) = rows[metric]?.firstOrNull()?.value
        WeighIn(
            at = Instant.ofEpochMilli(atEpochMs),
            weightKg = weight.value,
            fatPercent = value(Metric.BODY_FAT),
            leanKg = value(Metric.LEAN_MASS),
            waterKg = value(Metric.BODY_WATER),
            boneKg = value(Metric.BONE_MASS),
        )
    }

    /** The latest weigh-ins from any source, newest first. */
    suspend fun recent(limit: Int = 3, today: LocalDate = LocalDate.now()): List<RecentWeighIn> = withContext(Dispatchers.IO) {
        metricDao.range(Metric.BODY_WEIGHT.key, today.minusDays(RECENT_DAYS).toString(), today.toString())
            .sortedByDescending { it.startEpochMs }
            .take(limit)
            .map {
                val own = it.sourceApp == context.packageName
                RecentWeighIn(it.startEpochMs, it.value, typedIn = own, sourceLabel = if (own) null else labelOf(it.sourceApp))
            }
    }

    /**
     * Visible because the app manifest queries Health Connect's rationale intent,
     * which every app that writes to Health Connect declares.
     */
    private fun labelOf(packageName: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()

    private fun records(weighIn: WeighIn, zone: ZoneId): List<Record> {
        val at = weighIn.at
        val offset = zone.rules.getOffset(at)
        return listOfNotNull(
            WeightRecord(time = at, zoneOffset = offset, weight = Mass.kilograms(weighIn.weightKg), metadata = Metadata.manualEntry()),
            weighIn.fatPercent?.let {
                BodyFatRecord(time = at, zoneOffset = offset, percentage = Percentage(it), metadata = Metadata.manualEntry())
            },
            weighIn.leanKg?.let {
                LeanBodyMassRecord(time = at, zoneOffset = offset, mass = Mass.kilograms(it), metadata = Metadata.manualEntry())
            },
            weighIn.waterKg?.let {
                BodyWaterMassRecord(time = at, zoneOffset = offset, mass = Mass.kilograms(it), metadata = Metadata.manualEntry())
            },
            weighIn.boneKg?.let {
                BoneMassRecord(time = at, zoneOffset = offset, mass = Mass.kilograms(it), metadata = Metadata.manualEntry())
            },
        )
    }

    private companion object {
        const val RECENT_DAYS = 90L

        val TYPES: Map<Metric, KClass<out Record>> = mapOf(
            Metric.BODY_WEIGHT to WeightRecord::class,
            Metric.BODY_FAT to BodyFatRecord::class,
            Metric.LEAN_MASS to LeanBodyMassRecord::class,
            Metric.BODY_WATER to BodyWaterMassRecord::class,
            Metric.BONE_MASS to BoneMassRecord::class,
        )
    }
}
