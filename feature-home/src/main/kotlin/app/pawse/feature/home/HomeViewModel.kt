package app.pawse.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pawse.core.data.activity.MovingRepository
import app.pawse.core.data.activity.MovingSnapshot
import app.pawse.core.data.profile.Appearance
import app.pawse.core.data.profile.NotificationSettings
import app.pawse.core.data.profile.NotifyKind
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZonedDateTime
import javax.inject.Inject

/** The cards below Scores' rings that the profile menu can reorder or hide. */
enum class ScoresCard {
    MOVING, WORTH, YOUR_DAY, TONIGHT, TODAYS_LOAD, CHECK_IN;

    val label: String
        get() = when (this) {
            MOVING -> tr("Moving today", "오늘의 움직임")
            WORTH -> tr("Worth knowing", "알아둘 점")
            YOUR_DAY -> tr("Your day", "오늘의 흐름")
            TONIGHT -> tr("Tonight", "오늘 밤")
            TODAYS_LOAD -> tr("Today’s load", "오늘의 부하")
            CHECK_IN -> tr("How do you feel?", "오늘 컨디션은 어때요?")
        }

    companion object {
        /** Automatic: mornings lead with what is worth knowing, evenings with tonight. */
        fun automatic(evening: Boolean): List<ScoresCard> =
            if (evening) listOf(MOVING, TONIGHT, TODAYS_LOAD, CHECK_IN) else listOf(MOVING, WORTH, YOUR_DAY, CHECK_IN)

        /** A stored order plus any card added since it was saved, at the end. */
        fun complete(order: List<ScoresCard>): List<ScoresCard> = order.distinct() + entries.filter { it !in order }
    }
}

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
    private val movingRepository: MovingRepository,
    private val notificationSettings: NotificationSettings,
) : ViewModel() {

    val reduceMotion: StateFlow<Boolean> = profilePreferences.reduceMotion
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)
    val shapes: StateFlow<Boolean> = profilePreferences.shapes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)
    val notifications: StateFlow<Map<NotifyKind, Boolean>> = notificationSettings.state

    fun setReduceMotion(on: Boolean) {
        viewModelScope.launch { profilePreferences.setReduceMotion(on) }
    }

    fun setShapes(on: Boolean) {
        viewModelScope.launch { profilePreferences.setShapes(on) }
    }

    fun setNotify(kind: NotifyKind, on: Boolean) = notificationSettings.set(kind, on)

    /** Null until read, so a once-ever moment never flashes on a slow first frame. */
    val coachSeen: StateFlow<Boolean?> = profilePreferences.coachSeen
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)
    val firstWeek: StateFlow<Int?> = profilePreferences.firstWeek
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Hide scores today: true only on the day it was chosen. */
    val hiddenToday: StateFlow<Boolean> = profilePreferences.scoresHiddenOn
        .map { it == LocalDate.now() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    fun setHiddenToday(hidden: Boolean) {
        viewModelScope.launch { profilePreferences.setScoresHiddenOn(if (hidden) LocalDate.now() else null) }
    }

    fun dismissCoach() {
        viewModelScope.launch { profilePreferences.setCoachSeen() }
    }

    fun setFirstWeek(stage: Int) {
        viewModelScope.launch { profilePreferences.setFirstWeek(stage) }
    }

    /** Scores' cards: null is Automatic, otherwise the user's order. */
    val cardOrder: StateFlow<List<ScoresCard>?> = profilePreferences.cardOrder
        .map { names -> names?.mapNotNull { n -> ScoresCard.entries.firstOrNull { it.name == n } }?.let(ScoresCard::complete) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val hiddenCards: StateFlow<Set<ScoresCard>> = profilePreferences.hiddenCards
        .map { names -> ScoresCard.entries.filter { it.name in names }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptySet())

    fun setCardOrder(order: List<ScoresCard>?) {
        viewModelScope.launch { profilePreferences.setCardOrder(order?.map { it.name }) }
    }

    fun setHidden(card: ScoresCard, hidden: Boolean) {
        viewModelScope.launch { profilePreferences.setHidden(card.name, hidden) }
    }

    /**
     * Moving today, read from Health Connect whenever a fresh snapshot lands and
     * held in memory only. Null until read, or when nothing was written.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val moving: StateFlow<MovingSnapshot?> = scoring.state
        .mapLatest { s ->
            val snapshot = (s as? ScoringState.Ready)?.snapshot ?: return@mapLatest null
            val wakes = snapshot.nights.mapNotNull { (d, n) -> n.signals.sleep?.endEpochMs?.let { d to it } }.toMap()
            runCatching { movingRepository.read(ZonedDateTime.now(), wakes) }.getOrNull()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

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
                "Health Connect didn’t answer. This is usually brief. Your last scores are below.",
                "헬스 커넥트가 응답하지 않았어요. 보통 잠깐이에요. 마지막 점수는 아래에 있어요.",
            )
            SyncFailure.UNAVAILABLE -> tr(
                "Health Connect isn't available on this phone, so nothing new can be read.",
                "이 휴대폰에서는 헬스 커넥트를 쓸 수 없어서 새 데이터를 읽을 수 없어요.",
            )
            SyncFailure.NEEDS_UPDATE -> tr(
                "Pawse reads everything through Health Connect. Update it from the Play Store, then come back.",
                "Pawse는 모든 데이터를 헬스 커넥트로 읽어요. Play 스토어에서 업데이트한 뒤 돌아오세요.",
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
