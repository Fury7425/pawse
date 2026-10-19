package app.pawse.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pawse.core.data.body.BodyRepository
import app.pawse.core.data.body.BodySnapshot
import app.pawse.core.data.sync.SyncCoordinator
import app.pawse.core.data.sync.SyncStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class BodyRange(val days: Long, val label: String) {
    DAYS_30(30, "30 days"),
    DAYS_90(90, "90 days"),
    YEAR(365, "1 year"),
}

@HiltViewModel
class BodyViewModel @Inject constructor(
    private val body: BodyRepository,
    coordinator: SyncCoordinator,
) : ViewModel() {

    private val _snapshot = MutableStateFlow<BodySnapshot?>(null)
    val snapshot: StateFlow<BodySnapshot?> = _snapshot.asStateFlow()

    private val _range = MutableStateFlow(BodyRange.DAYS_30)
    val range: StateFlow<BodyRange> = _range.asStateFlow()

    init {
        reload()
        // Step on the scale and it updates here on its own, after the next read.
        coordinator.status.filterIsInstance<SyncStatus.Done>().onEach { reload() }.launchIn(viewModelScope)
    }

    fun setRange(range: BodyRange) {
        _range.value = range
    }

    private fun reload() {
        viewModelScope.launch { _snapshot.value = runCatching { body.snapshot() }.getOrNull() ?: _snapshot.value }
    }
}
