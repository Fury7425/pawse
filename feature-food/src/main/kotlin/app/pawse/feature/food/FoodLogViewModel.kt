package app.pawse.feature.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pawse.core.data.body.BodyRepository
import app.pawse.core.data.body.EnergyToday
import app.pawse.core.data.food.FoodDay
import app.pawse.core.data.food.FoodLogEntry
import app.pawse.core.data.food.FoodPreferences
import app.pawse.core.data.food.FoodRepository
import app.pawse.core.data.health.HealthConnectAvailability
import app.pawse.core.data.health.HealthConnectGateway
import app.pawse.core.data.health.HealthPermissions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** What the Undo bar says, and what pressing it puts back. */
data class UndoBar(val text: String, val undo: () -> Unit)

@HiltViewModel
class FoodLogViewModel @Inject constructor(
    private val repository: FoodRepository,
    private val preferences: FoodPreferences,
    private val body: BodyRepository,
    private val gateway: HealthConnectGateway,
) : ViewModel() {

    private val _date = MutableStateFlow(LocalDate.now())
    val date: StateFlow<LocalDate> = _date.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val day: StateFlow<FoodDay?> = _date
        .flatMapLatest { repository.observeDay(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** The strip of seven days above the total, ending today. */
    val week: StateFlow<List<FoodDay>> = repository.observeWeek(LocalDate.now())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val _again = MutableStateFlow<List<FoodLogEntry>>(emptyList())
    val again: StateFlow<List<FoodLogEntry>> = _again.asStateFlow()

    private val _energy = MutableStateFlow<EnergyToday?>(null)
    val energy: StateFlow<EnergyToday?> = _energy.asStateFlow()

    private val _undo = MutableStateFlow<UndoBar?>(null)
    val undo: StateFlow<UndoBar?> = _undo.asStateFlow()
    private var undoTimeout: Job? = null

    val networkEnabled: StateFlow<Boolean> = preferences.networkLookupEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    val mirroring: StateFlow<Boolean> = preferences.mirrorToHealthConnect
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), true)

    /**
     * Health Connect is there but has not allowed the food write. Onboarding asks
     * once, so anyone who onboarded before food existed, or said no, lands here
     * and every entry silently stays "only on this phone".
     */
    private val _writeBlocked = MutableStateFlow(false)
    val writeBlocked: StateFlow<Boolean> = _writeBlocked.asStateFlow()

    init {
        viewModelScope.launch {
            // A removal whose Undo window closed while the app was killed finishes
            // here. Then entries logged while Health Connect was unreachable get
            // another chance. Both are one cheap query in the normal case.
            repository.flushPendingRemovals()
            checkWriteGrant()
            if (!_writeBlocked.value) repository.retryMirroring()
            reloadSideFacts()
        }
    }

    /** After the system sheet: whatever was granted, catch the log up. */
    fun onWriteGrantResult() {
        viewModelScope.launch {
            checkWriteGrant()
            if (!_writeBlocked.value) repository.retryMirroring()
        }
    }

    private suspend fun checkWriteGrant() {
        _writeBlocked.value = gateway.availability() is HealthConnectAvailability.Available &&
            runCatching { !gateway.grantedPermissions().containsAll(HealthPermissions.NUTRITION_WRITE) }.getOrDefault(false)
    }

    fun showDay(date: LocalDate) {
        if (!date.isAfter(LocalDate.now())) _date.value = date
    }

    fun previousDay() {
        _date.value = _date.value.minusDays(1)
    }

    /** Never past today: there is nothing to log in the future. */
    fun nextDay() = showDay(_date.value.plusDays(1))

    /** Removes now; Health Connect follows when the Undo bar goes away. */
    fun delete(entry: FoodLogEntry) {
        viewModelScope.launch {
            repository.remove(entry)
            val where = if (entry.healthConnectId != null) ", here and in Health Connect" else ""
            showUndo("Removed ${entry.name}$where") { repository.restore(entry) }
        }
    }

    /** One tap under "Again": the same food, same figures, logged now as a snack. */
    fun logAgain(entry: FoodLogEntry) {
        viewModelScope.launch {
            val logged = repository.relog(entry)
            val kcal = logged.nutrients.kcal?.let { " · ${it.toInt()} kcal" }.orEmpty()
            showUndo("Added ${logged.name} to Snack$kcal") {
                repository.remove(logged)
                repository.flushPendingRemovals()
            }
            reloadSideFacts()
        }
    }

    fun dismissUndo() {
        undoTimeout?.cancel()
        _undo.value = null
        viewModelScope.launch { repository.flushPendingRemovals() }
    }

    fun setNetworkLookup(enabled: Boolean) {
        viewModelScope.launch { preferences.setNetworkLookup(enabled) }
    }

    fun setMirroring(enabled: Boolean) {
        viewModelScope.launch { preferences.setMirrorToHealthConnect(enabled) }
    }

    private fun showUndo(text: String, revert: suspend () -> Unit) {
        undoTimeout?.cancel()
        _undo.value = UndoBar(text) {
            undoTimeout?.cancel()
            _undo.value = null
            viewModelScope.launch {
                revert()
                reloadSideFacts()
            }
        }
        undoTimeout = viewModelScope.launch {
            delay(UNDO_WINDOW_MS)
            dismissUndo()
        }
    }

    private suspend fun reloadSideFacts() {
        _again.value = repository.again()
        _energy.value = runCatching { body.snapshot().energy }.getOrNull()
    }

    override fun onCleared() {
        // viewModelScope is gone by now; the next screen open flushes instead.
        undoTimeout?.cancel()
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val UNDO_WINDOW_MS = 6_000L
    }
}
