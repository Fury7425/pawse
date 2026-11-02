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
import app.pawse.core.model.ModelState
import app.pawse.core.model.PawseModel
import app.pawse.core.model.Summary
import app.pawse.core.model.Turn
import app.pawse.scoring.model.BiologicalSex
import app.pawse.scoring.model.DayTag
import app.pawse.scoring.tr
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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

/** The on-device model's part of Scores: this morning's summary, and Ask Pawse. */
data class AssistantUi(val ready: Boolean, val summary: Summary?, val rises: Boolean, val turns: List<Turn>) {
    companion object {
        val NONE = AssistantUi(ready = false, summary = null, rises = false, turns = emptyList())
    }
}

/** The switch in settings and what sits under it. [unsupported]: the last turn-on was refused. */
data class ModelSettings(val state: ModelState, val dropped: Int, val unsupported: Boolean, val room: Boolean)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val scoring: ScoringRepository,
    private val coordinator: SyncCoordinator,
    private val profilePreferences: UserProfilePreferences,
    private val nights: NightRepository,
    private val model: PawseModel,
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
        // The morning summary, once per morning: after the read has finished, when
        // there is a night behind it and the model is ready. Written while Pawse is
        // open, never in the background, and never announced by a notification.
        viewModelScope.launch {
            combine(scoring.state, coordinator.status, model.state) { s, sync, m ->
                (s as? ScoringState.Ready)?.snapshot?.takeIf { sync !is SyncStatus.Running && m is ModelState.Ready }
            }
                .filterNotNull()
                .filter { it.today.sleep.lastNightMinutes != null || it.today.body != null }
                .distinctUntilChangedBy { it.date }
                .collect { snapshot ->
                    model.writeSummary(snapshot.date, snapshot.factSheet())
                    model.release()
                }
        }
    }

    private val day = state.map { (it as? HomeUiState.Ready)?.snapshot?.date }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    val assistant: StateFlow<AssistantUi> = combine(model.state, day) { s, d -> d.takeIf { s is ModelState.Ready } }
        .distinctUntilChanged()
        .flatMapLatest { d ->
            if (d == null) {
                flowOf(AssistantUi.NONE)
            } else {
                combine(model.summary(d), model.rises(d), model.conversation(d)) { summary, rises, turns ->
                    AssistantUi(ready = true, summary = summary, rises = rises, turns = turns)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AssistantUi.NONE)

    private val modelRefused = MutableStateFlow(false)

    val modelSettings: StateFlow<ModelSettings> = combine(model.state, model.dropped, modelRefused) { s, dropped, refused ->
        ModelSettings(s, dropped, refused, model.hasRoom())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ModelSettings(ModelState.Off, 0, false, true))

    fun setModel(on: Boolean) {
        viewModelScope.launch {
            if (on) modelRefused.value = !model.turnOn() else model.turnOff()
        }
    }

    fun downloadModel(mobileData: Boolean) = model.download(mobileData)

    fun ask(question: String) {
        val snapshot = (state.value as? HomeUiState.Ready)?.snapshot ?: return
        viewModelScope.launch { model.ask(snapshot.date, question, snapshot.factSheet()) }
    }

    /** The sheet closed: the model's memory goes back to the phone. */
    fun closeAsk() {
        viewModelScope.launch { model.release() }
    }

    fun summaryRose() {
        val date = assistant.value.summary?.date ?: return
        viewModelScope.launch { model.markRisen(date) }
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
