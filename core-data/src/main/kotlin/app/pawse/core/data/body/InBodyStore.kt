package app.pawse.core.data.body

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private val Context.inBodyDataStore by preferencesDataStore(name = "pawse_inbody")

/** The two InBody figures Health Connect has no record for. A blank stays null, never a guess. */
data class InBodyExtra(val date: LocalDate, val skeletalMuscleKg: Double?, val visceralFat: Int?)

/**
 * Skeletal muscle and visceral fat, kept in Pawse only. Weight, body fat, lean mass
 * and water go to Health Connect with the weigh-in; these two have nowhere there.
 */
@Singleton
class InBodyStore @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.inBodyDataStore
    private val key = stringPreferencesKey("results")

    /** Oldest first. ponytail: one string; fine for years of weekly results, a table if it ever isn't. */
    val results: Flow<List<InBodyExtra>> = store.data.map { prefs ->
        prefs[key].orEmpty().split(';').mapNotNull(::parse)
    }

    /** One result a day: a second sheet the same day replaces the first. */
    suspend fun add(extra: InBodyExtra) {
        if (extra.skeletalMuscleKg == null && extra.visceralFat == null) return
        store.edit { prefs ->
            val kept = prefs[key].orEmpty().split(';').mapNotNull(::parse).filter { it.date != extra.date }
            prefs[key] = (kept + extra).sortedBy { it.date }.joinToString(";") {
                "${it.date},${it.skeletalMuscleKg ?: ""},${it.visceralFat ?: ""}"
            }
        }
    }

    private fun parse(row: String): InBodyExtra? {
        val parts = row.split(',')
        if (parts.size != 3) return null
        val date = runCatching { LocalDate.parse(parts[0]) }.getOrNull() ?: return null
        return InBodyExtra(date, parts[1].toDoubleOrNull(), parts[2].toIntOrNull())
    }
}
