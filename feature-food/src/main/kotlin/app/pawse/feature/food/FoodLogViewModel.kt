package app.pawse.feature.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pawse.core.data.food.FoodDay
import app.pawse.core.data.food.FoodLogEntry
import app.pawse.core.data.food.FoodPreferences
import app.pawse.core.data.food.FoodRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class FoodLogViewModel @Inject constructor(
    private val repository: FoodRepository,
    private val preferences: FoodPreferences,
) : ViewModel() {

    private val _date = MutableStateFlow(LocalDate.now())
    val date: StateFlow<LocalDate> = _date.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val day: StateFlow<FoodDay?> = _date
        .flatMapLatest { repository.observeDay(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val networkEnabled: StateFlow<Boolean> = preferences.networkLookupEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    val mirroring: StateFlow<Boolean> = preferences.mirrorToHealthConnect
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), true)

    init {
        // Entries logged while Health Connect was unavailable or the write grant was
        // missing get another chance here. Costs one indexed query in the normal
        // case, where it returns nothing.
        viewModelScope.launch { repository.retryMirroring() }
    }

    fun showDay(date: LocalDate) {
        _date.value = date
    }

    fun previousDay() {
        _date.value = _date.value.minusDays(1)
    }

    /** Never past today: there is nothing to log in the future. */
    fun nextDay() {
        val next = _date.value.plusDays(1)
        if (!next.isAfter(LocalDate.now())) _date.value = next
    }

    fun delete(entry: FoodLogEntry) {
        viewModelScope.launch { repository.delete(entry) }
    }

    fun setNetworkLookup(enabled: Boolean) {
        viewModelScope.launch { preferences.setNetworkLookup(enabled) }
    }

    fun setMirroring(enabled: Boolean) {
        viewModelScope.launch { preferences.setMirrorToHealthConnect(enabled) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
