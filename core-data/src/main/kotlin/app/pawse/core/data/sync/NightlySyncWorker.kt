package app.pawse.core.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nightly recompute. Runs after the sleep session has closed and the watch has had
 * time to upload, plus on demand from the pull-to-refresh on Home.
 */
@HiltWorker
class NightlySyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: HealthSyncRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = when (repository.sync()) {
        is SyncResult.Success -> Result.success()
        // Nothing to retry against: Health Connect is missing or out of date.
        SyncResult.Unavailable -> Result.success()
        is SyncResult.Failed -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    companion object {
        const val PERIODIC_NAME = "pawse_nightly_sync"
        const val ONE_SHOT_NAME = "pawse_sync_now"
        private const val MAX_ATTEMPTS = 4
    }
}

@Singleton
class SyncScheduler @Inject constructor(
    private val workManager: WorkManager,
) {

    /**
     * Fires once a day, first run shortly after the [TARGET_HOUR] local wake window.
     *
     * WorkManager's minimum periodic interval is 15 minutes and its scheduling is
     * inexact by design, so this is a floor, not an alarm. On-demand [syncNow] is
     * what makes the app feel current; this exists so a user who never opens the
     * app before noon still has a score waiting.
     */
    fun schedule(zone: ZoneId = ZoneId.systemDefault()) {
        val request = PeriodicWorkRequestBuilder<NightlySyncWorker>(Duration.ofHours(24))
            .setInitialDelay(delayUntilNextRun(zone))
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()

        workManager.enqueueUniquePeriodicWork(
            NightlySyncWorker.PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun syncNow() {
        workManager.enqueueUniqueWork(
            NightlySyncWorker.ONE_SHOT_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<NightlySyncWorker>().build(),
        )
    }

    fun cancel() {
        workManager.cancelUniqueWork(NightlySyncWorker.PERIODIC_NAME)
    }

    private fun delayUntilNextRun(zone: ZoneId): Duration {
        val now = ZonedDateTime.now(zone)
        var target = now.with(LocalTime.of(TARGET_HOUR, 0))
        if (!target.isAfter(now)) target = target.plusDays(1)
        return Duration.between(now, target).coerceAtLeast(Duration.ofMinutes(15))
    }

    private companion object {
        /** Late enough that most watches have uploaded the night, early enough to be there on waking. */
        const val TARGET_HOUR = 9
    }
}
