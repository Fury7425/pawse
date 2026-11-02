package app.pawse.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pawse.core.data.profile.Appearance
import app.pawse.core.data.profile.UserProfilePreferences
import app.pawse.core.data.scoring.NightDetail
import app.pawse.core.data.scoring.NightRepository
import app.pawse.core.data.scoring.ScoringRepository
import app.pawse.core.data.scoring.ScoringSnapshot
import app.pawse.core.data.scoring.ScoringState
import app.pawse.core.data.sync.SyncCoordinator
import app.pawse.core.data.sync.SyncFailure
import app.pawse.core.data.sync.SyncStatus
import app.pawse.scoring.model.BiologicalSex
import app.pawse.scoring.model.DayTag
import app.pawse.scoring.tr
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
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
    private val nights: NightRepository,
) : ViewModel() {

    private val dismissedAt = MutableStateFlow(0L)

    /** The open night, and the snapshot it belongs to, so a fresh sync reads it again. */
    private val nightKey = MutableStateFlow<Pair<LocalDate, Long>?>(null)

    /**
     * The night the Sleep tab has open, read when it is opened. The last one stays
     * until the next arrives, so callers check its date.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val night: StateFlow<NightDetail?> = nightKey
        .mapLatest { key -> key?.let { runCatching { nights.read(it.first) }.getOrNull() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun openNight(date: LocalDate, computedAtEpochMs: Long) {
        nightKey.value = date to computedAtEpochMs
    }

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
            SyncFailure.READ_FAILED -> tr(
                "Health Connect didn't answer at ${ScoreCopy.clock(failed.atEpochMs)}. " +
                    "Nothing new was read, so these are the last numbers we had.",
                "${ScoreCopy.clock(failed.atEpochMs)}에 헬스 커넥트가 응답하지 않았어요. " +
                    "새로 읽은 게 없어서 마지막으로 읽은 값을 보여주고 있어요.",
            )
            SyncFailure.UNAVAILABLE -> tr(
                "Health Connect isn't available on this phone, so nothing new can be read.",
                "이 휴대폰에서는 헬스 커넥트를 쓸 수 없어서 새 데이터를 읽을 수 없어요.",
            )
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
