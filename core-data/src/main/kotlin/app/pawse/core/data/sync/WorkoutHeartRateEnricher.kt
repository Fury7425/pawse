package app.pawse.core.data.sync

import androidx.health.connect.client.records.HeartRateRecord
import app.pawse.core.data.db.ExerciseDao
import app.pawse.core.data.health.HealthConnectAvailability
import app.pawse.core.data.health.HealthConnectGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Attaches a heart rate to logged workouts, one narrow window at a time.
 *
 * Health Connect stores an exercise session and the heart-rate series recorded
 * during it as separate records, so a synced session arrives with no heart rate
 * and therefore no scoreable load: Banister's TRIMP is duration times a function
 * of heart-rate reserve, and without a mean heart rate there is no reserve
 * fraction and no load.
 *
 * The obvious fix — sync `HeartRateRecord` like everything else — is the one
 * [HealthSyncRepository] explicitly refuses, because a continuously sampling
 * watch writes hundreds of thousands of samples a month and draining that through
 * change tokens costs far more than it returns. So heart rate is read on demand,
 * over the minutes a workout actually lasted, and reduced to two numbers before
 * anything is stored.
 *
 * Sessions that come back empty are left alone rather than marked as processed.
 * A watch that uploads the session first and the heart-rate series a minute later
 * is normal, and giving up permanently on the first attempt would silently drop
 * the workout from every future recompute.
 */
@Singleton
class WorkoutHeartRateEnricher @Inject constructor(
    private val gateway: HealthConnectGateway,
    private val exerciseDao: ExerciseDao,
) {

    /**
     * @return how many sessions gained a heart rate.
     */
    suspend fun enrich(from: String, to: String, limit: Int = DEFAULT_LIMIT): Int =
        withContext(Dispatchers.IO) {
            if (gateway.availability() !is HealthConnectAvailability.Available) return@withContext 0

            val pending = exerciseDao.withoutHeartRate(from, to).takeLast(limit)
            var enriched = 0

            for (session in pending) {
                val records = gateway.readOrEmpty(
                    HeartRateRecord::class,
                    Instant.ofEpochMilli(session.startEpochMs),
                    Instant.ofEpochMilli(session.endEpochMs),
                )
                val beats = records
                    .flatMap { it.samples }
                    .filter { it.time.toEpochMilli() in session.startEpochMs..session.endEpochMs }
                    .map { it.beatsPerMinute.toDouble() }
                if (beats.isEmpty()) continue

                exerciseDao.setHeartRate(
                    id = session.id,
                    mean = beats.average(),
                    max = beats.max(),
                )
                enriched++
            }

            enriched
        }

    private companion object {
        /**
         * Newest sessions first, bounded. A ninety-day backfill on a heavy training
         * history would otherwise issue a hundred separate reads on the first sync,
         * and the recent days are the ones the user is about to look at.
         */
        const val DEFAULT_LIMIT = 40
    }
}
