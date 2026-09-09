package app.pawse.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pawse.core.data.health.CapabilityProbe
import app.pawse.core.data.health.CapabilityReport
import app.pawse.core.data.health.HealthConnectAvailability
import app.pawse.core.data.health.HealthConnectGateway
import app.pawse.core.data.health.HealthPermissions
import app.pawse.core.data.sync.HealthSyncRepository
import app.pawse.core.data.sync.SyncPreferences
import app.pawse.core.data.sync.SyncResult
import app.pawse.core.data.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class OnboardingStep {
    /** Health Connect present and current? */
    CHECKING,
    UNAVAILABLE,
    NEEDS_UPDATE,

    /** Explain what is read and why, before the system sheet appears. */
    EXPLAIN,

    /** System permission sheet is up. */
    REQUESTING,

    /** Partial grants land here too. Nothing is a hard failure. */
    SYNCING,
    PROBING,

    /** The honest report: what we can compute, and at what confidence. */
    REPORT,
    DONE,
}

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.CHECKING,
    val granted: Set<String> = emptySet(),
    val report: CapabilityReport? = null,
    val syncing: Boolean = false,
    val error: String? = null,
) {
    val missingCoreReads: Set<String> get() = HealthPermissions.CORE_READS - granted
    val hasAnyGrant: Boolean get() = granted.isNotEmpty()
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val gateway: HealthConnectGateway,
    private val probe: CapabilityProbe,
    private val sync: HealthSyncRepository,
    private val scheduler: SyncScheduler,
    private val prefs: SyncPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    val permissionsToRequest: Set<String> = HealthPermissions.ALL

    init {
        refreshAvailability()
    }

    fun refreshAvailability() {
        viewModelScope.launch {
            when (gateway.availability()) {
                is HealthConnectAvailability.Available -> {
                    val granted = gateway.grantedPermissions()
                    _state.update {
                        it.copy(
                            granted = granted,
                            step = if (granted.containsAll(HealthPermissions.CORE_READS)) {
                                OnboardingStep.SYNCING
                            } else {
                                OnboardingStep.EXPLAIN
                            },
                        )
                    }
                    if (_state.value.step == OnboardingStep.SYNCING) runSyncThenProbe()
                }
                is HealthConnectAvailability.NeedsUpdate ->
                    _state.update { it.copy(step = OnboardingStep.NEEDS_UPDATE) }
                HealthConnectAvailability.Unsupported ->
                    _state.update { it.copy(step = OnboardingStep.UNAVAILABLE) }
            }
        }
    }

    fun onRequestStarted() {
        _state.update { it.copy(step = OnboardingStep.REQUESTING) }
    }

    /**
     * Called with whatever the system sheet returned. A partial grant is a normal
     * outcome, not an error: the probe measures what is actually reachable and the
     * scores renormalise around it.
     */
    fun onPermissionResult(granted: Set<String>) {
        _state.update { it.copy(granted = granted, step = OnboardingStep.SYNCING) }
        runSyncThenProbe()
    }

    fun retryProbe() = runSyncThenProbe()

    private fun runSyncThenProbe() {
        viewModelScope.launch {
            _state.update { it.copy(syncing = true, error = null) }

            when (val result = sync.sync()) {
                is SyncResult.Failed ->
                    _state.update { it.copy(error = result.cause.message ?: "Sync failed") }
                else -> Unit
            }

            _state.update { it.copy(step = OnboardingStep.PROBING) }

            runCatching { probe.probe() }
                .onSuccess { report ->
                    _state.update {
                        it.copy(report = report, step = OnboardingStep.REPORT, syncing = false)
                    }
                }
                .onFailure { cause ->
                    _state.update {
                        it.copy(
                            step = OnboardingStep.REPORT,
                            syncing = false,
                            error = cause.message ?: "Could not read your health data",
                        )
                    }
                }
        }
    }

    /** User accepted the report. Turn on the nightly recompute and leave onboarding. */
    fun finish() {
        viewModelScope.launch {
            val contested = _state.value.report?.contestedTypes.orEmpty()
            if (contested.isNotEmpty()) prefs.acknowledgeContested(contested)

            // Default source priority is simply the order the probe saw them. The
            // user can reorder it in settings; the point is that duplicates resolve
            // deterministically rather than by whichever row Room returns first.
            _state.value.report?.writerApps?.let { apps ->
                if (apps.isNotEmpty()) prefs.setSourcePriority(apps.toList())
            }

            prefs.setOnboardingComplete(true)
            scheduler.schedule()
            _state.update { it.copy(step = OnboardingStep.DONE) }
        }
    }
}
