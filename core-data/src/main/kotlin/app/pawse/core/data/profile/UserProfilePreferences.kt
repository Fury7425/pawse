package app.pawse.core.data.profile

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.pawse.scoring.config.StrainScale
import app.pawse.scoring.model.BiologicalSex
import app.pawse.scoring.model.UserProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.profileDataStore by preferencesDataStore(name = "pawse_profile")

/**
 * The two facts Health Connect cannot tell us, and one display preference.
 *
 * Age and biological sex are inputs to published equations — Tanaka's HRmax
 * estimate and Banister's two sex-specific TRIMP curves — and there is no record
 * type for either. Everything else the app knows about a user is measured; these
 * are the only things it has to ask for, so it asks for as little as possible and
 * runs without them where it can.
 *
 * Without an age there is no HRmax, without an HRmax there is no heart-rate
 * reserve, and without heart-rate reserve a workout cannot be scored at all. That
 * is why the Home screen surfaces the gap instead of quietly reporting rest days.
 */
@Singleton
class UserProfilePreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store = context.profileDataStore

    private val ageKey = intPreferencesKey("age_years")
    private val sexKey = stringPreferencesKey("biological_sex")
    private val maxHrKey = doublePreferencesKey("measured_max_hr")
    private val strainScaleKey = stringPreferencesKey("strain_scale")

    val profile: Flow<UserProfile> = store.data.map { prefs ->
        UserProfile(
            ageYears = prefs[ageKey],
            biologicalSex = prefs[sexKey]
                ?.let { runCatching { BiologicalSex.valueOf(it) }.getOrNull() }
                ?: BiologicalSex.UNSPECIFIED,
            // Resting heart rate is deliberately not stored: the engine is handed
            // the rolling baseline the BaselineEngine already computes, which is a
            // better number than anything a user would type.
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

    /**
     * Whoop's 0-21 or Bevel's 0-100. Same curve, different ceiling, and the stored
     * score never changes — only the label does.
     */
    val strainScale: Flow<StrainScale> = store.data.map { prefs ->
        prefs[strainScaleKey]?.let { runCatching { StrainScale.valueOf(it) }.getOrNull() }
            ?: StrainScale.BEVEL_100
    }

    suspend fun setStrainScale(scale: StrainScale) {
        store.edit { it[strainScaleKey] = scale.name }
    }
}
