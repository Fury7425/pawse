package app.pawse.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pawse.core.data.profile.Appearance
import app.pawse.core.data.profile.UserProfilePreferences
import app.pawse.core.data.scoring.ScoringRepository
import app.pawse.core.data.scoring.ScoringSnapshot
import app.pawse.core.data.scoring.ScoringState
import app.pawse.core.data.sync.SyncCoordinator
import app.pawse.core.data.sync.SyncFailure
import app.pawse.core.data.sync.SyncStatus
import app.pawse.scoring.model.BiologicalSex
import app.pawse.scoring.model.DayTag
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Ready(val snapshot: ScoringSnapshot, val sync: SyncStatus) : HomeUiState {
        val syncing: Boolean get() = sync is SyncStatus.Running

        /** Every grant the cards rest on is off. History stays; nothing new can come. */
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
            is ScoringState.Ready -> HomeUiState.Ready(scoringState.snapshot, status)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), initialState())

    /** The last read's failure, in words, until dismissed. */
    val error: StateFlow<String?> = combine(coordinator.status, dismissedAt) { status, dismissed ->
        val failed = status as? SyncStatus.Failed ?: return@combine null
        if (failed.atEpochMs <= dismissed) return@combine null
        when (failed.reason) {
            SyncFailure.READ_FAILED -> "Health Connect didn't answer at ${ScoreCopy.clock(failed.atEpochMs)}. " +
                "Nothing new was read, so these are the last numbers we had."
            SyncFailure.UNAVAILABLE -> "Health Connect isn't available on this phone, so nothing new can be read."
            SyncFailure.ACCESS_OFF -> null
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    init {
        viewModelScope.launch { coordinator.runIfStale() }
    }

    private fun initialState(): HomeUiState = when (val current = scoring.state.value) {
        is ScoringState.Ready -> HomeUiState.Ready(current.snapshot, coordinator.status.value)
        is ScoringState.Failed -> HomeUiState.Failed(current.message)
        ScoringState.Loading -> HomeUiState.Loading
    }

    fun refresh() {
        viewModelScope.launch { scoring.refresh() }
    }

    fun syncNow() {
        viewModelScope.launch { coordinator.run() }
    }

    fun dismissError() {
        dismissedAt.update { System.currentTimeMillis() }
    }

    /** One tap, 1..5. Tapping the same answer again clears it. */
    fun checkIn(feeling: Int) {
        val snapshot = (state.value as? HomeUiState.Ready)?.snapshot ?: return
        viewModelScope.launch {
            scoring.setCheckIn(snapshot.date, feeling.takeIf { it != snapshot.checkIn })
        }
    }

    fun toggleTag(tag: DayTag) {
        val snapshot = (state.value as? HomeUiState.Ready)?.snapshot ?: return
        viewModelScope.launch { scoring.toggleTag(snapshot.date, tag) }
    }

    fun resetModel() {
        viewModelScope.launch { scoring.resetCalibration() }
    }

    val appearance: StateFlow<Appearance> = profilePreferences.appearance
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), Appearance.SYSTEM)

    fun setAppearance(appearance: Appearance) {
        viewModelScope.launch { profilePreferences.setAppearance(appearance) }
    }

    fun setShowNumbers(show: Boolean) {
        viewModelScope.launch {
            profilePreferences.setShowNumbers(show)
            scoring.refresh()
        }
    }

    /** Age, sex and a measured maximum feed Banister's TRIMP; saving rescores at once. */
    fun saveProfile(ageYears: Int?, sex: BiologicalSex, measuredMaxHeartRate: Double?) {
        viewModelScope.launch {
            profilePreferences.set(ageYears, sex, measuredMaxHeartRate)
            scoring.refresh()
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
