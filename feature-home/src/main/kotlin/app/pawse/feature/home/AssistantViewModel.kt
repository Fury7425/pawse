package app.pawse.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pawse.core.data.scoring.ScoringRepository
import app.pawse.core.data.scoring.ScoringState
import app.pawse.core.data.sync.SyncCoordinator
import app.pawse.core.data.sync.SyncStatus
import app.pawse.core.model.ModelState
import app.pawse.core.model.PawseModel
import app.pawse.core.model.Summary
import app.pawse.core.model.Turn
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * The on-device model's part of Scores, today only. [firstDay]: switched on today,
 * so the first summary comes tomorrow. [asking]: the Ask Pawse sheet is open.
 */
data class AssistantUi(
    val ready: Boolean,
    val summary: Summary?,
    val rises: Boolean,
    val turns: List<Turn>,
    val firstDay: Boolean,
    val asking: Boolean,
) {
    companion object {
        val NONE = AssistantUi(ready = false, summary = null, rises = false, turns = emptyList(), firstDay = false, asking = false)
    }
}

/**
 * The switch in settings and what sits under it. [unsupported]: the last turn-on was
 * refused. [canRunLlama]: LFM3 can stand in for Gemini Nano on this phone.
 */
data class ModelSettings(val state: ModelState, val dropped: Int, val unsupported: Boolean, val room: Boolean, val canRunLlama: Boolean)

/**
 * Its own ViewModel so Scores and the settings sheet each hook the model in with one
 * line, and the screens around it can be rebuilt without touching this.
 */
@HiltViewModel
class AssistantViewModel @Inject constructor(
    private val scoring: ScoringRepository,
    coordinator: SyncCoordinator,
    private val model: PawseModel,
) : ViewModel() {

    init {
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

    private val asking = MutableStateFlow(false)
    private val refused = MutableStateFlow(false)

    /** The morning the screen shows, when it is today: yesterday's summary never lingers past midnight. */
    private val today = scoring.state
        .map { (it as? ScoringState.Ready)?.snapshot?.date?.takeIf { d -> d == LocalDate.now() } }
        .distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    val ui: StateFlow<AssistantUi> = combine(model.state, today) { s, d -> (s as? ModelState.Ready)?.let { r -> d?.let { r to it } } }
        .distinctUntilChanged()
        .flatMapLatest { ready ->
            if (ready == null) {
                flowOf(AssistantUi.NONE)
            } else {
                val (state, d) = ready
                combine(model.summary(d), model.rises(d), model.conversation(d), asking) { summary, rises, turns, open ->
                    AssistantUi(true, summary, rises, turns, firstDay = state.since == d, asking = open)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AssistantUi.NONE)

    val settings: StateFlow<ModelSettings> = combine(model.state, model.dropped, refused) { s, dropped, no ->
        ModelSettings(s, dropped, no, model.hasRoom(), model.canRunLlama)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ModelSettings(ModelState.Off, 0, false, true, false))

    fun setModel(on: Boolean) {
        viewModelScope.launch {
            if (on) refused.value = !model.turnOn() else model.turnOff()
        }
    }

    fun download(mobileData: Boolean) = model.download(mobileData)

    /** Gemini Nano gives way to LFM3, whose download starts now. */
    fun useLlama() {
        viewModelScope.launch { if (model.preferLlama()) model.download(mobileData = false) }
    }

    fun summaryRose() {
        val date = ui.value.summary?.date ?: return
        viewModelScope.launch { model.markRisen(date) }
    }

    fun openAsk() {
        asking.value = true
    }

    /** The sheet closed: the model's memory goes back to the phone. */
    fun closeAsk() {
        asking.value = false
        viewModelScope.launch { model.release() }
    }

    fun ask(question: String) {
        val snapshot = (scoring.state.value as? ScoringState.Ready)?.snapshot ?: return
        viewModelScope.launch { model.ask(snapshot.date, question, snapshot.factSheet()) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
