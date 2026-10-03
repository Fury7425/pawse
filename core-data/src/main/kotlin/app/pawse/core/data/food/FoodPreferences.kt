package app.pawse.core.data.food

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.foodDataStore by preferencesDataStore(name = "pawse_food")

/**
 * The two switches that decide what this app is allowed to do on the user's behalf.
 *
 * Both default to the conservative answer, and the network one defaults to off
 * rather than to "ask later": until the user has read what a lookup sends and said
 * yes, the resolver skips the network step entirely and the barcode is answered
 * from the local cache, a photographed label, or not at all.
 *
 * That is the whole reason this file exists rather than a constant. Everything
 * else in Pawse is local by construction — no account, no server, no telemetry —
 * and a food database is the one place that claim needs an explicit exception the
 * user granted knowingly.
 */
@Singleton
class FoodPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store = context.foodDataStore

    private val networkEnabledKey = booleanPreferencesKey("network_lookup_enabled")
    private val networkAskedKey = booleanPreferencesKey("network_lookup_asked")
    private val mirrorKey = booleanPreferencesKey("mirror_to_health_connect")

    /** Off until the user says otherwise. */
    val networkLookupEnabled: Flow<Boolean> =
        store.data.map { it[networkEnabledKey] ?: false }

    /**
     * Whether the explanation has been shown. Kept apart from the answer so that
     * "not yet asked" and "asked and declined" are different states: the first
     * prompts on the next scan, the second does not nag.
     */
    val networkLookupAsked: Flow<Boolean> =
        store.data.map { it[networkAskedKey] ?: false }

    suspend fun isNetworkLookupEnabled(): Boolean = networkLookupEnabled.first()

    suspend fun setNetworkLookup(enabled: Boolean) {
        store.edit {
            it[networkEnabledKey] = enabled
            it[networkAskedKey] = true
        }
    }

    /**
     * Mirror logged food into Health Connect as a NutritionRecord. On by default:
     * it is the user's own store on the user's own phone, it is the only write this
     * app ever makes, and onboarding says so in as many words.
     */
    val mirrorToHealthConnect: Flow<Boolean> =
        store.data.map { it[mirrorKey] ?: true }

    suspend fun isMirroringEnabled(): Boolean = mirrorToHealthConnect.first()

    suspend fun setMirrorToHealthConnect(enabled: Boolean) {
        store.edit { it[mirrorKey] = enabled }
    }
}
