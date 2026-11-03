package app.pawse.core.data.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.metadata.DataOrigin
import kotlinx.coroutines.CancellationException
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResultGroupedByDuration
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

sealed interface HealthConnectAvailability {
    data object Available : HealthConnectAvailability
    /** Health Connect app is installed but out of date, or absent on Play-enabled devices. */
    data class NeedsUpdate(val providerPackage: String) : HealthConnectAvailability
    /** No Health Connect on this device at all. The app is read-only useless here; say so plainly. */
    data object Unsupported : HealthConnectAvailability
}

/**
 * Thin wrapper over [HealthConnectClient]. Everything above this line is suspend
 * functions returning plain records; no scoring, no interpretation, no caching.
 */
@Singleton
class HealthConnectGateway @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * The Health Connect provider package.
     *
     * Spelled out rather than read from [HealthConnectClient.DEFAULT_PROVIDER_PACKAGE_NAME],
     * which is internal to the library and so is not ours to depend on. The app
     * manifest already has to name the same package in its <queries> block for
     * getSdkStatus to see it at all, so this is the second of two places it is
     * written down, not the first.
     */
    val providerPackage: String = HEALTH_CONNECT_PACKAGE

    fun availability(): HealthConnectAvailability =
        when (HealthConnectClient.getSdkStatus(context, providerPackage)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.Available
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                HealthConnectAvailability.NeedsUpdate(providerPackage)
            else -> HealthConnectAvailability.Unsupported
        }

    val client: HealthConnectClient?
        get() = if (availability() is HealthConnectAvailability.Available) {
            HealthConnectClient.getOrCreate(context, providerPackage)
        } else {
            null
        }

    private fun requireClient(): HealthConnectClient =
        client ?: error("Health Connect unavailable: ${availability()}")

    suspend fun grantedPermissions(): Set<String> = withContext(Dispatchers.IO) {
        client?.permissionController?.getGrantedPermissions().orEmpty()
    }

    suspend fun hasAllPermissions(): Boolean =
        grantedPermissions().containsAll(HealthPermissions.ALL)

    /**
     * Reads every page of one record type in a window.
     *
     * Paging matters more here than it looks: a month of a continuously-sampling
     * watch's HeartRateRecord is tens of thousands of rows, and Health Connect
     * caps a page at 5000.
     */
    suspend fun <T : Record> read(
        type: KClass<T>,
        start: Instant,
        end: Instant,
        pageSize: Int = 2000,
    ): List<T> = withContext(Dispatchers.IO) {
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val response = requireClient().readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageSize = pageSize,
                    pageToken = token,
                ),
            )
            out += response.records
            token = response.pageToken
        } while (token != null)
        out
    }

    /**
     * Same read, but never throws on a missing grant. A metric the user declined
     * is indistinguishable from a metric their watch does not write, and both are
     * handled the same way: the term is dropped.
     */
    suspend fun <T : Record> readOrEmpty(
        type: KClass<T>,
        start: Instant,
        end: Instant,
    ): List<T> = runCatching { read(type, start, end) }.getOrDefault(emptyList())

    /**
     * Active energy in a window, aggregated by Health Connect itself.
     *
     * Aggregated rather than synced: a watch can write an interval every minute, and
     * Health Connect already de-duplicates overlapping writers by the user's own
     * priority order, which is the answer we want. Null when there is no grant or
     * nothing was written.
     */
    suspend fun activeKcal(start: Instant, end: Instant): Double? = withContext(Dispatchers.IO) {
        runCatching {
            requireClient().aggregate(
                AggregateRequest(
                    metrics = setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                ),
            )[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
        }.getOrNull()
    }

    /**
     * [metrics] totalled per [slice] across a window, by Health Connect itself, so
     * overlapping writers are de-duplicated as above. Empty without a grant.
     */
    suspend fun slices(
        metrics: Set<AggregateMetric<*>>,
        start: Instant,
        end: Instant,
        slice: Duration,
        /** Only these writers. Empty: Health Connect's own priority order decides. */
        origins: Set<String> = emptySet(),
    ): List<AggregationResultGroupedByDuration> = withContext(Dispatchers.IO) {
        runCatching {
            requireClient().aggregateGroupByDuration(
                AggregateGroupByDurationRequest(
                    metrics,
                    TimeRangeFilter.between(start, end),
                    slice,
                    dataOriginFilter = origins.mapTo(HashSet()) { DataOrigin(it) },
                ),
            )
        }.onFailure {
            if (it is CancellationException) throw it
            // A silent empty answer is undebuggable: say why in logcat.
            Log.w(TAG, "Aggregate of ${metrics.joinToString()} failed", it)
        }.getOrDefault(emptyList())
    }

    /** The apps that wrote [type] between [start] and [end], by how many records each wrote. */
    suspend fun <T : Record> writers(type: KClass<T>, start: Instant, end: Instant): Map<String, Int> =
        readOrEmpty(type, start, end).groupingBy { it.metadata.dataOrigin.packageName }.eachCount()

    companion object {
        const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"
        private const val TAG = "PawseHealth"
    }
}
