package app.pawse.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pawse.core.data.profile.Appearance
import app.pawse.core.data.profile.UserProfilePreferences
import app.pawse.core.data.scoring.ScoreTrend
import app.pawse.core.data.scoring.ScoringRepository
import app.pawse.core.data.scoring.ScoringSnapshot
import app.pawse.core.data.scoring.ScoringState
import app.pawse.core.data.scoring.Warmup
import app.pawse.core.data.sync.SyncCoordinator
import app.pawse.core.data.sync.SyncFailure
import app.pawse.core.data.sync.SyncStatus
import app.pawse.scoring.config.StrainScale
import app.pawse.scoring.model.BiologicalSex
import app.pawse.scoring.model.Metric
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
    val trends: Map<ScoreType, ScoreTrend> = emptyMap(),
    val warmup: Warmup? = null,
    val nightValues: Map<Metric, List<Double>> = emptyMap(),
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
    data class Ready(val snapshot: HomeSnapshot, val sync: SyncStatus) : HomeUiState {
        val syncing: Boolean get() = sync is SyncStatus.Running

        /** Every grant the scores rest on is off. Scored history stays; nothing new can come. */
        val accessOff: Boolean get() = (sync as? SyncStatus.Failed)?.reason == SyncFailure.ACCESS_OFF
    }
    data class Failed(val message: String) : HomeUiState
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val scoring: ScoringRepository,
    private val coordinator: SyncCoordinator,
    private val profilePreferences: UserProfilePreferences,
) : ViewModel() {

    private val dismissedAt = MutableStateFlow(0L)

    val state: StateFlow<HomeUiState> = combine(scoring.state, coordinator.status) { scoringState, status ->
        when (scoringState) {
            is ScoringState.Loading -> HomeUiState.Loading
            is ScoringState.Failed -> HomeUiState.Failed(scoringState.message)
            is ScoringState.Ready -> HomeUiState.Ready(scoringState.snapshot.toHomeSnapshot(), status)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), initialState())

    /**
     * The last read's failure, in words, until dismissed. Access-off is not here:
     * it replaces the screen's lead rather than sitting in a notice.
     */
    val error: StateFlow<String?> = combine(coordinator.status, dismissedAt) { status, dismissed ->
        val failed = status as? SyncStatus.Failed ?: return@combine null
        if (failed.atEpochMs <= dismissed) return@combine null
        when (failed.reason) {
            SyncFailure.READ_FAILED -> "Health Connect didn't answer at ${ScoreCopy.clock(failed.atEpochMs)}. " +
                "Nothing new was scored, so these are the last numbers we had."
            SyncFailure.UNAVAILABLE -> "Health Connect isn't available on this phone, so nothing new can be read."
            SyncFailure.ACCESS_OFF -> null
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    init {
        // Lazy on purpose: the explainability screen creates a second view model over
        // the same repository, and that is not a reason to read Health Connect again.
        viewModelScope.launch { coordinator.runIfStale() }
    }

    /**
     * Seeded from whatever the repository already holds, so opening the
     * explainability screen over an existing score does not flash a spinner at a
     * user who is looking at that very number.
     */
    private fun initialState(): HomeUiState = when (val current = scoring.state.value) {
        is ScoringState.Ready -> HomeUiState.Ready(current.snapshot.toHomeSnapshot(), coordinator.status.value)
        is ScoringState.Failed -> HomeUiState.Failed(current.message)
        ScoringState.Loading -> HomeUiState.Loading
    }

    /** Recompute from what is already stored. Cheap, and the app's normal path. */
    fun refresh() {
        viewModelScope.launch { scoring.refresh() }
    }

    /**
     * Go and ask Health Connect for anything new, then recompute. The numbers on
     * screen stay put while it works and change in place when it is done.
     */
    fun syncNow() {
        viewModelScope.launch { coordinator.run() }
    }

    fun dismissError() {
        dismissedAt.update { System.currentTimeMillis() }
    }

    val appearance: StateFlow<Appearance> = profilePreferences.appearance
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), Appearance.SYSTEM)

    fun setAppearance(appearance: Appearance) {
        viewModelScope.launch { profilePreferences.setAppearance(appearance) }
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
            trends = trends,
            warmup = warmup,
            nightValues = nightValues,
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
