package app.pawse.core.model

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private val Context.modelDataStore by preferencesDataStore(name = "pawse_model")

/** Which engine writes: LFM3 through llama.cpp, downloaded, or Gemini Nano already on the phone. */
enum class Engine { LLAMA, NANO }

/** A morning's summary as kept until tomorrow: the model's checked sentences, or Pawse's own line. */
data class Summary(val date: LocalDate, val text: String, val written: Boolean, val writtenAtEpochMs: Long)

/**
 * The on-device model's switch and what it keeps. Off by default: nothing is
 * downloaded and nothing is written until the user has read what it is and said yes.
 */
@Singleton
class ModelPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val store = context.modelDataStore

    private val enabledKey = booleanPreferencesKey("enabled")
    private val engineKey = stringPreferencesKey("engine")
    private val readySinceKey = stringPreferencesKey("ready_since")
    private val summaryDateKey = stringPreferencesKey("summary_date")
    private val summaryTextKey = stringPreferencesKey("summary_text")
    private val summaryWrittenKey = booleanPreferencesKey("summary_written")
    private val summaryAtKey = longPreferencesKey("summary_at")
    private val risenKey = stringPreferencesKey("risen_date")
    private val droppedKey = intPreferencesKey("dropped")

    val enabled: Flow<Boolean> = store.data.map { it[enabledKey] ?: false }

    val engine: Flow<Engine> = store.data.map { p ->
        p[engineKey]?.let { runCatching { Engine.valueOf(it) }.getOrNull() } ?: Engine.LLAMA
    }

    /** The day the model became usable. Summaries start the morning after. */
    val readySince: Flow<LocalDate?> = store.data.map { p -> p[readySinceKey]?.let(::date) }

    val summary: Flow<Summary?> = store.data.map { p ->
        val day = p[summaryDateKey]?.let(::date) ?: return@map null
        Summary(day, p[summaryTextKey].orEmpty(), p[summaryWrittenKey] ?: false, p[summaryAtKey] ?: 0L)
    }

    /** The morning the summary last rose. It rises once a morning. */
    val risen: Flow<LocalDate?> = store.data.map { p -> p[risenKey]?.let(::date) }

    /** Sentences the number check left out, counted on this phone and shown in settings. */
    val dropped: Flow<Int> = store.data.map { it[droppedKey] ?: 0 }

    suspend fun turnOn(engine: Engine, readySince: LocalDate?) {
        store.edit {
            it[enabledKey] = true
            it[engineKey] = engine.name
            if (readySince == null) it.remove(readySinceKey) else it[readySinceKey] = readySince.toString()
        }
    }

    suspend fun setReady(day: LocalDate) {
        store.edit { it[readySinceKey] = day.toString() }
    }

    /** Off keeps only the dropped count. */
    suspend fun turnOff() {
        store.edit { p ->
            listOf(enabledKey, engineKey, readySinceKey, summaryDateKey, summaryTextKey, summaryWrittenKey, summaryAtKey, risenKey)
                .forEach { p.remove(it) }
        }
    }

    suspend fun setSummary(summary: Summary) {
        store.edit {
            it[summaryDateKey] = summary.date.toString()
            it[summaryTextKey] = summary.text
            it[summaryWrittenKey] = summary.written
            it[summaryAtKey] = summary.writtenAtEpochMs
        }
    }

    suspend fun markRisen(day: LocalDate) {
        store.edit { it[risenKey] = day.toString() }
    }

    suspend fun addDropped(count: Int) {
        if (count > 0) store.edit { it[droppedKey] = (it[droppedKey] ?: 0) + count }
    }
}

private fun date(text: String): LocalDate? = runCatching { LocalDate.parse(text) }.getOrNull()
