package app.pawse.core.data.sync

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.syncDataStore by preferencesDataStore(name = "pawse_sync")

/**
 * Sync bookkeeping: Health Connect change tokens, onboarding state, and the source
 * priority list used to resolve duplicate overlapping records.
 */
@Singleton
class SyncPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store = context.syncDataStore

    /**
     * One change token per record type. Health Connect invalidates a token after
     * about 30 days, and after a provider data reset; on invalidation we fall back
     * to a bounded full re-read rather than silently syncing nothing.
     */
    suspend fun changeToken(recordType: String): String? =
        store.data.first()[stringPreferencesKey("token_$recordType")]

    suspend fun setChangeToken(recordType: String, token: String?) {
        store.edit { prefs ->
            val key = stringPreferencesKey("token_$recordType")
            if (token == null) prefs.remove(key) else prefs[key] = token
        }
    }

    suspend fun clearAllChangeTokens() {
        store.edit { prefs ->
            prefs.asMap().keys.filter { it.name.startsWith("token_") }
                .forEach { prefs.remove(stringPreferencesKey(it.name)) }
        }
    }

    val onboardingComplete: Flow<Boolean> =
        store.data.map { it[booleanPreferencesKey("onboarding_complete")] ?: false }

    suspend fun setOnboardingComplete(complete: Boolean) {
        store.edit { it[booleanPreferencesKey("onboarding_complete")] = complete }
    }

    val lastSyncEpochMs: Flow<Long> =
        store.data.map { it[longPreferencesKey("last_sync_ms")] ?: 0L }

    suspend fun setLastSync(epochMs: Long) {
        store.edit { it[longPreferencesKey("last_sync_ms")] = epochMs }
    }

    /**
     * Ordered source priority, highest first.
     *
     * When two apps write overlapping records for the same watch, we do not average
     * them and we do not pick at random: the earlier package in this list wins for
     * that time range. The onboarding probe reports which types are contested, and
     * the user picks the order once.
     */
    val sourcePriority: Flow<List<String>> =
        store.data.map { it[stringPreferencesKey("source_priority")]?.split('\n')?.filter { s -> s.isNotBlank() } ?: emptyList() }

    suspend fun setSourcePriority(packages: List<String>) {
        store.edit { it[stringPreferencesKey("source_priority")] = packages.joinToString("\n") }
    }

    /** Record types the user has seen a duplicate-source warning for. */
    val acknowledgedContested: Flow<Set<String>> =
        store.data.map { it[stringSetPreferencesKey("acked_contested")] ?: emptySet() }

    suspend fun acknowledgeContested(types: Set<String>) {
        store.edit { it[stringSetPreferencesKey("acked_contested")] = types }
    }
}
