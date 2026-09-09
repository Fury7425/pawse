package app.pawse.core.data.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    val providerPackage: String = HealthConnectClient.DEFAULT_PROVIDER_PACKAGE_NAME

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
}
