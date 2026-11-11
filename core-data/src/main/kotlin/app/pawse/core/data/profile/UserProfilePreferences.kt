package app.pawse.core.data.profile

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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

    private val reduceMotionKey = booleanPreferencesKey("reduce_motion")
    private val shapesKey = booleanPreferencesKey("shapes_with_colour")

    /** Pawse's own reduce-motion switch, beside the system's all-or-nothing one. */
    val reduceMotion: Flow<Boolean> = store.data.map { it[reduceMotionKey] ?: false }

    suspend fun setReduceMotion(on: Boolean) {
        store.edit { it[reduceMotionKey] = on }
    }

    /** Shapes as well as colour on every band dot, since Android has no Differentiate Without Colour. */
    val shapes: Flow<Boolean> = store.data.map { it[shapesKey] ?: false }

    suspend fun setShapes(on: Boolean) {
        store.edit { it[shapesKey] = on }
    }

    private val cardOrderKey = stringPreferencesKey("card_order")
    private val hiddenCardsKey = stringSetPreferencesKey("hidden_cards")

    /** Scores' cards in the user's order, or null for Automatic (they follow the time of day). */
    val cardOrder: Flow<List<String>?> = store.data.map { prefs -> prefs[cardOrderKey]?.split(',')?.filter { it.isNotBlank() } }

    suspend fun setCardOrder(order: List<String>?) {
        store.edit { if (order == null) it.remove(cardOrderKey) else it[cardOrderKey] = order.joinToString(",") }
    }

    /** Cards hidden from Scores in My order. */
    val hiddenCards: Flow<Set<String>> = store.data.map { it[hiddenCardsKey].orEmpty() }

    suspend fun setHidden(card: String, hidden: Boolean) {
        store.edit { it[hiddenCardsKey] = if (hidden) it[hiddenCardsKey].orEmpty() + card else it[hiddenCardsKey].orEmpty() - card }
    }

    private val goalKey = doublePreferencesKey("goal_kg")

    /** An optional goal weight: a dashed line on the chart and an estimate in whole months. */
    val goalKg: Flow<Double?> = store.data.map { it[goalKey] }

    suspend fun setGoalKg(kg: Double?) {
        store.edit { if (kg == null) it.remove(goalKey) else it[goalKey] = kg }
    }

    private val hiddenOnKey = stringPreferencesKey("scores_hidden_on")

    /** The day Scores' rings were hidden; they come back on their own the next morning. */
    val scoresHiddenOn: Flow<LocalDate?> = store.data.map { prefs ->
        prefs[hiddenOnKey]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    }

    suspend fun setScoresHiddenOn(day: LocalDate?) {
        store.edit { if (day == null) it.remove(hiddenOnKey) else it[hiddenOnKey] = day.toString() }
    }

    private val coachSeenKey = booleanPreferencesKey("coach_seen")
    private val firstWeekKey = intPreferencesKey("first_week")

    /** "Tap any ring to see why": shown on the first Scores visit, once ever. */
    val coachSeen: Flow<Boolean> = store.data.map { it[coachSeenKey] ?: false }

    suspend fun setCoachSeen() {
        store.edit { it[coachSeenKey] = true }
    }

    /**
     * The seventh night, once ever: [FIRST_WEEK_NONE] until Scores shows a learning
     * week, [FIRST_WEEK_SEEN] while it does, [FIRST_WEEK_DONE] once the segments joined.
     * Someone whose watch already had a week of nights never sees it.
     */
    val firstWeek: Flow<Int> = store.data.map { it[firstWeekKey] ?: FIRST_WEEK_NONE }

    suspend fun setFirstWeek(stage: Int) {
        store.edit { it[firstWeekKey] = stage }
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

const val FIRST_WEEK_NONE = 0
const val FIRST_WEEK_SEEN = 1
const val FIRST_WEEK_DONE = 2
