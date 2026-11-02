package app.pawse.feature.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import app.pawse.core.data.activity.MovingRepository
import app.pawse.core.data.body.BodyReading
import app.pawse.core.data.body.BodyRepository
import app.pawse.core.data.body.EnergyToday
import app.pawse.core.data.food.FoodDay
import app.pawse.core.data.food.FoodLogEntry
import app.pawse.core.data.food.FoodPreferences
import app.pawse.core.data.food.FoodRepository
import app.pawse.core.data.food.Meal
import app.pawse.core.data.health.HealthConnectAvailability
import app.pawse.core.data.health.HealthConnectGateway
import app.pawse.core.data.health.HealthPermissions
import app.pawse.core.data.scoring.ScoringRepository
import app.pawse.core.data.scoring.ScoringState
import app.pawse.scoring.body.WeighInPoint
import app.pawse.scoring.body.Weight
import app.pawse.scoring.config.ScoringConfig
import app.pawse.scoring.energy.EnergyBalance
import app.pawse.scoring.energy.LoggedDay
import app.pawse.scoring.model.Metric
import app.pawse.scoring.tr
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import javax.inject.Inject

/** What the Undo bar says, and what pressing it puts back. */
data class UndoBar(val text: String, val undo: () -> Unit)

/** Food's two burned figures. [byBedtime] is projected, so the screen marks it with "~". */
data class Burned(val soFar: Double, val byBedtime: Double?)

@HiltViewModel
class FoodLogViewModel @Inject constructor(
    private val repository: FoodRepository,
    private val preferences: FoodPreferences,
    private val body: BodyRepository,
    private val gateway: HealthConnectGateway,
    private val scoring: ScoringRepository,
    private val moving: MovingRepository,
) : ViewModel() {

    private val _burned = MutableStateFlow<Burned?>(null)

    /**
     * Burned so far and by bedtime, from the Energy engine: resting burn spread
     * over the clock, the watch's active energy and digestion; bedtime adds resting
     * burn to bed and your usual activity for the rest of the day. Both are scaled
     * by k, the weekly check of what you ate against how your trend weight moved.
     */
    val burned: StateFlow<Burned?> = _burned.asStateFlow()

    /** Days marked as not fully logged. */
    val incomplete: StateFlow<Set<String>> = preferences.incompleteDays
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptySet())

    fun setIncomplete(date: LocalDate, incomplete: Boolean) {
        viewModelScope.launch {
            preferences.setIncomplete(date.toString(), incomplete)
            reloadSideFacts()
        }
    }

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
            // Food logged in other apps since the last open. Lands through the same
            // Room flows, so the day and the week strip update on their own.
            repository.importFromHealthConnect()
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
            val inHc = entry.healthConnectId != null
            showUndo(
                tr(
                    "Removed ${entry.name}" + if (inHc) ", here and in Health Connect" else "",
                    "${entry.name} 삭제됨" + if (inHc) " (여기와 헬스 커넥트에서)" else "",
                ),
            ) { repository.restore(entry) }
        }
    }

    /** A new portion or meal from the edit sheet, with Undo putting the old one back. */
    fun edit(entry: FoodLogEntry, factor: Double, meal: Meal) {
        if (factor == 1.0 && meal == entry.meal) return
        viewModelScope.launch {
            val edited = repository.edit(entry, factor, meal)
            showUndo(tr("${entry.name} updated", "${entry.name} 수정됨")) {
                repository.remove(edited)
                repository.restore(entry)
                repository.flushPendingRemovals()
            }
            reloadSideFacts()
        }
    }

    /** One tap under "Again": the same food, same figures, logged now as a snack. */
    fun logAgain(entry: FoodLogEntry) {
        viewModelScope.launch {
            val logged = repository.relog(entry)
            val kcal = logged.nutrients.kcal?.let { " · ${it.toInt()} kcal" }.orEmpty()
            showUndo(tr("Added ${logged.name} to Snack$kcal", "간식에 ${logged.name} 추가함$kcal")) {
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
        val now = ZonedDateTime.now()
        val snapshot = runCatching { body.snapshot(now) }.getOrNull()
        _energy.value = snapshot?.energy
        val bmr = snapshot?.bmr?.kcalPerDay ?: run {
            _burned.value = null
            return
        }
        val scores = (scoring.state.value as? ScoringState.Ready)?.snapshot
        val bed = scores?.energy?.bedtimeMinute?.toDouble()
        val wakes = scores?.nights.orEmpty().mapNotNull { (d, n) -> n.signals.sleep?.endEpochMs?.let { d to it } }.toMap()
        val usualRest = bed?.let { runCatching { moving.read(now, wakes, it) }.getOrNull()?.usualActiveUntilBed }
        val minute = now.hour * 60.0 + now.minute + now.second / 60.0
        val config = ScoringConfig.DEFAULT.balance
        val k = runCatching { weightCheck(now, bmr, snapshot.series[Metric.BODY_WEIGHT].orEmpty()) }.getOrDefault(1.0)
        val soFar = EnergyBalance.burnedSoFar(bmr, minute, snapshot.energy.activeKcal ?: 0.0, snapshot.energy.eatenKcal, k, config)
        _burned.value = Burned(soFar, bed?.let { EnergyBalance.byBedtime(soFar, bmr, minute, it, usualRest ?: 0.0, k) })
    }

    /**
     * k over the last 28 days: fully logged days (two meals or more, not marked
     * partly logged) against the trend weight's change. Stays 1 until there are 14
     * such days and 8 weigh-ins.
     */
    private suspend fun weightCheck(now: ZonedDateTime, bmr: Double, weights: List<BodyReading>): Double {
        val config = ScoringConfig.DEFAULT.balance
        val today = now.toLocalDate()
        val from = today.minusDays(config.checkWindowDays.toLong())
        val to = today.minusDays(1)
        val active = gateway.slices(
            setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
            from.atStartOfDay(now.zone).toInstant(),
            today.atStartOfDay(now.zone).toInstant(),
            Duration.ofDays(1),
        ).associate { it.startTime.atZone(now.zone).toLocalDate() to (it.result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0) }
        val marked = preferences.incompleteDays.first()
        val days = repository.days(from, to).map { d ->
            LoggedDay(
                eatenKcal = d.totals.totals.kcal ?: 0.0,
                meals = d.entries.map { it.meal }.distinct().size,
                incomplete = d.date.toString() in marked,
                burnedKcal = bmr + (active[d.date] ?: 0.0),
            )
        }
        val trend = Weight.trend(weights.map { WeighInPoint(it.date.toEpochDay(), it.value) }, ScoringConfig.DEFAULT.weight)
        fun at(day: LocalDate) = trend.lastOrNull { it.day <= day.toEpochDay() }?.trend
        val start = at(from)
        val end = at(to)
        val change = if (start != null && end != null) end - start else null
        val weighIns = weights.count { !it.date.isBefore(from) && !it.date.isAfter(to) }
        return EnergyBalance.weightCheck(days, change, config.checkWindowDays, weighIns, config)
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
