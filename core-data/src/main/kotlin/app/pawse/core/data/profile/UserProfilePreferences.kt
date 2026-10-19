package app.pawse.core.data.profile

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.pawse.scoring.model.BiologicalSex
import app.pawse.scoring.model.UserProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private val Context.profileDataStore by preferencesDataStore(name = "pawse_profile")

/**
 * The two facts Health Connect cannot tell us, and a few display preferences.
 *
 * Age and biological sex are inputs to published equations — Tanaka's HRmax
 * estimate and Banister's two sex-specific TRIMP curves — and there is no record
 * type for either. Without them a workout still gets a load, from session effort.
 */
@Singleton
class UserProfilePreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store = context.profileDataStore

    private val ageKey = intPreferencesKey("age_years")
    private val sexKey = stringPreferencesKey("biological_sex")
    private val maxHrKey = doublePreferencesKey("measured_max_hr")
    private val showNumbersKey = booleanPreferencesKey("show_numbers")
    private val calibrationSinceKey = stringPreferencesKey("calibration_since")

    val profile: Flow<UserProfile> = store.data.map { prefs ->
        UserProfile(
            ageYears = prefs[ageKey],
            biologicalSex = prefs[sexKey]
                ?.let { runCatching { BiologicalSex.valueOf(it) }.getOrNull() }
                ?: BiologicalSex.UNSPECIFIED,
            // Not stored: the engine derives a better number from your own nights.
            restingHeartRate = null,
            measuredMaxHeartRate = prefs[maxHrKey],
        )
    }

    suspend fun current(): UserProfile = profile.first()

    suspend fun set(ageYears: Int?, sex: BiologicalSex, measuredMaxHeartRate: Double?) {
        store.edit { prefs ->
            if (ageYears == null) prefs.remove(ageKey) else prefs[ageKey] = ageYears
            prefs[sexKey] = sex.name
            if (measuredMaxHeartRate == null) prefs.remove(maxHrKey) else prefs[maxHrKey] = measuredMaxHeartRate
        }
    }

    /** Band word first; the exact number can be hidden. On by default. */
    val showNumbers: Flow<Boolean> = store.data.map { it[showNumbersKey] ?: true }

    suspend fun setShowNumbers(show: Boolean) {
        store.edit { it[showNumbersKey] = show }
    }

    /** Check-ins before this date are ignored by calibration: the "reset my model" button. */
    val calibrationSince: Flow<LocalDate?> = store.data.map { prefs ->
        prefs[calibrationSinceKey]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    }

    suspend fun resetCalibration(today: LocalDate = LocalDate.now()) {
        store.edit { it[calibrationSinceKey] = today.toString() }
    }

    private val appearanceKey = stringPreferencesKey("appearance")

    /** Follows the phone unless the user picks; bedside is the near-black night palette. */
    val appearance: Flow<Appearance> = store.data.map { prefs ->
        prefs[appearanceKey]?.let { runCatching { Appearance.valueOf(it) }.getOrNull() } ?: Appearance.SYSTEM
    }

    suspend fun setAppearance(appearance: Appearance) {
        store.edit { it[appearanceKey] = appearance.name }
    }
}

enum class Appearance { SYSTEM, DARK, LIGHT, BEDSIDE }
