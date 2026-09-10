package app.pawse.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pawse.core.data.profile.UserProfilePreferences
import app.pawse.core.data.scoring.ScoringRepository
import app.pawse.core.data.scoring.ScoringSnapshot
import app.pawse.core.data.scoring.ScoringState
import app.pawse.core.data.sync.HealthSyncRepository
import app.pawse.core.data.sync.SyncResult
import app.pawse.scoring.config.StrainScale
import app.pawse.scoring.model.BiologicalSex
import app.pawse.scoring.model.Score
import app.pawse.scoring.model.ScoreType
import app.pawse.scoring.model.ScoreUnavailable
import app.pawse.scoring.model.UserProfile
import app.pawse.scoring.strain.SessionLoad
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * The snapshot in the shape the screens read it, with the strain scale already
 * applied and the sleep need already unpacked.
 *
 * The conversion happens once, here, rather than in each composable: the canonical
 * strain value is what is stored and charted, and the scale toggle is a display
 * concern that must never reach the persistence layer.
 */
data class HomeSnapshot(
    val date: LocalDate,
    val scores: Map<ScoreType, Score>,
    val unavailable: Map<ScoreType, ScoreUnavailable>,
    val sleepNeedHours: Double?,
    val sleepNeedBaselineHours: Double?,
    val sleepNeedStrainAdderHours: Double?,
    val sleepNeedDebtAdderHours: Double?,
    val sleepNeedNapCreditHours: Double?,
    val strainDisplay: Double?,
    val strainTargetDisplay: Double?,
    val strainTypicalDisplay: Double?,
    val strainScale: StrainScale,
    val sessionLoads: List<SessionLoad>,
    val profile: UserProfile,
    val needsProfile: Boolean,
    val nightsInWindow: Int,
    val lastSyncEpochMs: Long,
) {
    fun score(type: ScoreType): Score? = scores[type]

    /** Recovery leads the screen when it exists; otherwise the best thing we have. */
    val heroType: ScoreType
        get() = when {
            scores.containsKey(ScoreType.RECOVERY) -> ScoreType.RECOVERY
            scores.containsKey(ScoreType.SLEEP) -> ScoreType.SLEEP
            scores.containsKey(ScoreType.ENERGY_BANK) -> ScoreType.ENERGY_BANK
            else -> ScoreType.RECOVERY
        }
}

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Ready(val snapshot: HomeSnapshot, val syncing: Boolean) : HomeUiState
    data class Failed(val message: String) : HomeUiState
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val scoring: ScoringRepository,
    private val sync: HealthSyncRepository,
    private val profilePreferences: UserProfilePreferences,
) : ViewModel() {

    private val syncing = MutableStateFlow(false)
    private val syncError = MutableStateFlow<String?>(null)

    val state: StateFlow<HomeUiState> = combine(scoring.state, syncing) { scoringState, isSyncing ->
        when (scoringState) {
            is ScoringState.Loading -> HomeUiState.Loading
            is ScoringState.Failed -> HomeUiState.Failed(scoringState.message)
            is ScoringState.Ready -> HomeUiState.Ready(scoringState.snapshot.toHomeSnapshot(), isSyncing)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), initialState())

    /** Last sync failure, if the user asked for one and it did not work. */
    val error: StateFlow<String?> = syncError

    init {
        // Lazy on purpose: navigating to an explainability screen creates a second
        // view model over the same repository, and that is not a reason to rebuild
        // ninety days of history.
        viewModelScope.launch { scoring.ensureFresh() }
    }

    /**
     * Seeded from whatever the repository already holds, so opening the
     * explainability screen over an existing score does not flash a spinner at a
     * user who is looking at that very number.
     */
    private fun initialState(): HomeUiState = when (val current = scoring.state.value) {
        is ScoringState.Ready -> HomeUiState.Ready(current.snapshot.toHomeSnapshot(), syncing = false)
        is ScoringState.Failed -> HomeUiState.Failed(current.message)
        ScoringState.Loading -> HomeUiState.Loading
    }

    /** Recompute from what is already stored. Cheap, and the app's normal path. */
    fun refresh() {
        viewModelScope.launch { scoring.refresh() }
    }

    /**
     * Go and ask Health Connect for anything new, then recompute.
     *
     * Done in the foreground rather than by enqueueing the worker, because a user
     * who taps refresh is watching the screen and WorkManager's scheduling is
     * inexact by design.
     */
    fun syncNow() {
        viewModelScope.launch {
            syncing.value = true
            syncError.value = null
            when (val result = sync.sync()) {
                is SyncResult.Failed -> syncError.value = result.cause.message ?: "Could not read Health Connect"
                SyncResult.Unavailable -> syncError.value = "Health Connect is not available on this phone"
                is SyncResult.Success -> Unit
            }
            scoring.refresh()
            syncing.value = false
        }
    }

    fun dismissError() {
        syncError.update { null }
    }

    /**
     * The two facts no record type carries. Saving them changes what the engine can
     * do — without an age there is no estimated maximum heart rate, and without one
     * of those a workout has no heart-rate reserve to be scored against — so this
     * recomputes immediately rather than waiting for tonight.
     */
    fun saveProfile(ageYears: Int?, sex: BiologicalSex, measuredMaxHeartRate: Double?) {
        viewModelScope.launch {
            profilePreferences.set(ageYears, sex, measuredMaxHeartRate)
            scoring.refresh()
        }
    }

    fun setStrainScale(scale: StrainScale) {
        viewModelScope.launch {
            profilePreferences.setStrainScale(scale)
            scoring.refresh()
        }
    }

    private fun ScoringSnapshot.toHomeSnapshot(): HomeSnapshot {
        fun toScale(canonical: Double?): Double? = canonical?.let {
            if (strainScale == StrainScale.WHOOP_21) it * 21.0 / 100.0 else it
        }
        return HomeSnapshot(
            date = date,
            scores = scores,
            unavailable = unavailable,
            sleepNeedHours = sleepNeed?.totalHours,
            sleepNeedBaselineHours = sleepNeed?.baselineHours,
            sleepNeedStrainAdderHours = sleepNeed?.strainAdderHours,
            sleepNeedDebtAdderHours = sleepNeed?.debtAdderHours,
            sleepNeedNapCreditHours = sleepNeed?.napCreditHours,
            strainDisplay = strainDisplayValue,
            strainTargetDisplay = toScale(strainTarget?.target),
            strainTypicalDisplay = toScale(strainTarget?.typicalStrain),
            strainScale = strainScale,
            sessionLoads = sessionLoads,
            profile = profile,
            needsProfile = needsProfile,
            nightsInWindow = nightsInWindow,
            lastSyncEpochMs = lastSyncEpochMs,
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
