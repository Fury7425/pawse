package app.pawse.core.data.sync

import app.pawse.core.data.scoring.ScoringRepository
import app.pawse.core.data.scoring.ScoringSnapshot
import app.pawse.core.data.scoring.ScoringState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** The three steps a read names while it works: read, compare, score. */
enum class SyncStep { READ, COMPARE, SCORE }

/** Why the last read produced nothing new. Each one gets its own sentence on screen. */
enum class SyncFailure {
    /** No Health Connect, or one too old to talk to. */
    UNAVAILABLE,

    /** Every grant the scores rest on was turned off. */
    ACCESS_OFF,

    /** Health Connect was there and did not answer. Yesterday's numbers stand. */
    READ_FAILED,
}

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data class Running(val step: SyncStep) : SyncStatus
    data class Done(val atEpochMs: Long) : SyncStatus
    data class Failed(val reason: SyncFailure, val atEpochMs: Long) : SyncStatus
}

/**
 * Where the app's score surfaces outside the app — the widget and the morning
 * notification — are told a read finished. Implemented in :app, which is the only
 * module that knows about either.
 */
interface ScoreSurfaces {
    fun onScored(snapshot: ScoringSnapshot, inBackground: Boolean)
}

/**
 * One read, start to finish: Health Connect, then the engine, then whatever shows
 * the result outside the app.
 *
 * Both the refresh control and the nightly worker come through here, so the screen
 * and the notification can never disagree about which step is running, and two
 * reads can never interleave.
 *
 * The scores on screen are never cleared while this runs. [status] says what is
 * happening; the numbers change in place when it is done.
 */
@Singleton
class SyncCoordinator @Inject constructor(
    private val sync: HealthSyncRepository,
    private val scoring: ScoringRepository,
    private val prefs: SyncPreferences,
    private val surfaces: dagger.Lazy<ScoreSurfaces>,
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    suspend fun run(inBackground: Boolean = false): SyncStatus = mutex.withLock {
        _status.value = SyncStatus.Running(SyncStep.READ)
        val result = sync.sync()

        // Rescored whatever happened. A failed read still leaves yesterday's samples
        // in the store, and the screen should show those as yesterday's rather than
        // nothing at all.
        val state = scoring.refresh(onStep = { _status.value = SyncStatus.Running(it) })
        val now = System.currentTimeMillis()

        if (state is ScoringState.Ready) {
            runCatching { surfaces.get().onScored(state.snapshot, inBackground && result is SyncResult.Success) }
        }

        val next = when (result) {
            is SyncResult.Success -> SyncStatus.Done(now)
            SyncResult.Unavailable -> SyncStatus.Failed(SyncFailure.UNAVAILABLE, now)
            SyncResult.AccessOff -> SyncStatus.Failed(SyncFailure.ACCESS_OFF, now)
            is SyncResult.Failed -> SyncStatus.Failed(SyncFailure.READ_FAILED, now)
        }
        _status.value = next
        next
    }

    /**
     * Opening the app reads Health Connect when the last read is older than
     * [maxAgeMs], and otherwise only recomputes from what is stored. WorkManager's
     * nightly run is inexact, so this is what makes a morning open feel current.
     */
    suspend fun runIfStale(maxAgeMs: Long = STALE_AFTER_MS) {
        val last = prefs.lastSyncEpochMs.first()
        if (System.currentTimeMillis() - last > maxAgeMs) run() else scoring.ensureFresh()
    }

    private companion object {
        const val STALE_AFTER_MS = 30 * 60 * 1000L
    }
}
