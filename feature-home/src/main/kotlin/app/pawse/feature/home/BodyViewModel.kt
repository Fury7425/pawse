package app.pawse.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pawse.core.data.body.BodyRepository
import app.pawse.core.data.body.BodySnapshot
import app.pawse.core.data.body.InBodyExtra
import app.pawse.core.data.body.InBodyStore
import app.pawse.core.data.profile.UserProfilePreferences
import app.pawse.core.data.sync.SyncCoordinator
import app.pawse.core.data.sync.SyncStatus
import app.pawse.scoring.tr
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class BodyRange(val days: Long, private val en: String, private val ko: String) {
    DAYS_30(30, "30 days", "30일"),
    DAYS_90(90, "90 days", "90일"),
    YEAR(365, "1 year", "1년"),
    ;

    val label: String get() = tr(en, ko)
}

@HiltViewModel
class BodyViewModel @Inject constructor(
    private val body: BodyRepository,
    private val profile: UserProfilePreferences,
    coordinator: SyncCoordinator,
    inBodyStore: InBodyStore,
) : ViewModel() {

    /** Skeletal muscle and visceral fat from InBody sheets, kept in Pawse only. */
    val inBody: StateFlow<List<InBodyExtra>> = inBodyStore.results
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Sets or clears the goal weight, then redraws with it. */
    fun setGoal(kg: Double?) {
        viewModelScope.launch {
            profile.setGoalKg(kg)
            reload()
        }
    }

    private val _snapshot = MutableStateFlow<BodySnapshot?>(null)
    val snapshot: StateFlow<BodySnapshot?> = _snapshot.asStateFlow()

    private val _range = MutableStateFlow(BodyRange.DAYS_30)
    val range: StateFlow<BodyRange> = _range.asStateFlow()

    init {
        // Step on the scale and it updates here on its own, after the next read.
        coordinator.status.filterIsInstance<SyncStatus.Done>().onEach { reload() }.launchIn(viewModelScope)
    }

    fun setRange(range: BodyRange) {
        _range.value = range
    }

    /** Called each time the screen is shown, so a weigh-in typed in a moment ago is there. */
    fun reload() {
        viewModelScope.launch { _snapshot.value = runCatching { body.snapshot() }.getOrNull() ?: _snapshot.value }
    }
}
