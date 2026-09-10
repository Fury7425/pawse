package app.pawse.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MetricSampleDao {

    /** IGNORE, not REPLACE: re-syncing the same record must not churn row ids. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(samples: List<MetricSampleEntity>)

    @Query("DELETE FROM metric_sample WHERE originId IN (:originIds)")
    suspend fun deleteByOriginIds(originIds: List<String>)

    @Query(
        """
        SELECT * FROM metric_sample
        WHERE metric = :metric AND localDate BETWEEN :from AND :to
        ORDER BY startEpochMs
        """,
    )
    suspend fun range(metric: String, from: String, to: String): List<MetricSampleEntity>

    @Query(
        """
        SELECT * FROM metric_sample
        WHERE metric = :metric AND startEpochMs >= :startMs AND endEpochMs <= :endMs
        ORDER BY startEpochMs
        """,
    )
    suspend fun window(metric: String, startMs: Long, endMs: Long): List<MetricSampleEntity>

    @Query("SELECT DISTINCT sourceApp FROM metric_sample WHERE metric = :metric")
    suspend fun sourcesFor(metric: String): List<String>

    @Query("SELECT COUNT(DISTINCT localDate) FROM metric_sample WHERE metric = :metric")
    suspend fun daysWithData(metric: String): Int
}

@Dao
interface SleepDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSession(session: SleepSessionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStages(stages: List<SleepStageEntity>)

    @Transaction
    suspend fun insertSessionWithStages(session: SleepSessionEntity, stages: List<SleepStageEntity>) {
        val id = insertSession(session)
        if (id > 0) insertStages(stages.map { it.copy(sessionId = id) })
    }

    @Query("SELECT * FROM sleep_session WHERE localDate = :date ORDER BY startEpochMs")
    suspend fun sessionsOn(date: String): List<SleepSessionEntity>

    /**
     * Split sleep is normal, not an error: a 03:00 wake and a 05:00 return are two
     * sessions on one wake date. Callers merge them; the store keeps both.
     */
    @Query("SELECT * FROM sleep_session WHERE localDate BETWEEN :from AND :to ORDER BY startEpochMs")
    suspend fun sessionsBetween(from: String, to: String): List<SleepSessionEntity>

    @Query("SELECT * FROM sleep_stage WHERE sessionId = :sessionId ORDER BY startEpochMs")
    suspend fun stagesFor(sessionId: Long): List<SleepStageEntity>

    @Query("DELETE FROM sleep_session WHERE originId IN (:originIds)")
    suspend fun deleteByOriginIds(originIds: List<String>)

    @Query("SELECT COUNT(*) FROM sleep_session WHERE isNap = 0")
    suspend fun nightCount(): Int
}

@Dao
interface ExerciseDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(sessions: List<ExerciseSessionEntity>)

    @Query("SELECT * FROM exercise_session WHERE localDate BETWEEN :from AND :to ORDER BY startEpochMs")
    suspend fun between(from: String, to: String): List<ExerciseSessionEntity>

    /**
     * Sessions we have not yet been able to attach a heart rate to.
     *
     * Health Connect writes the exercise envelope and the heart-rate series as
     * separate records, so a freshly synced session has no mean heart rate and
     * cannot be scored. These are the rows the enricher goes back for.
     */
    @Query(
        """
        SELECT * FROM exercise_session
        WHERE meanHeartRate IS NULL AND localDate BETWEEN :from AND :to
        ORDER BY startEpochMs
        """,
    )
    suspend fun withoutHeartRate(from: String, to: String): List<ExerciseSessionEntity>

    @Query("UPDATE exercise_session SET meanHeartRate = :mean, maxHeartRate = :max WHERE id = :id")
    suspend fun setHeartRate(id: Long, mean: Double?, max: Double?)

    @Query("DELETE FROM exercise_session WHERE originId IN (:originIds)")
    suspend fun deleteByOriginIds(originIds: List<String>)
}

@Dao
interface ScoreDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(score: ScoreEntity)

    /** Latest score for a type on a date under the config currently in force. */
    @Query(
        """
        SELECT * FROM score
        WHERE type = :type AND localDate = :date
          AND scoringVersion = :version AND configHash = :configHash
        """,
    )
    suspend fun get(type: String, date: String, version: String, configHash: String): ScoreEntity?

    @Query(
        """
        SELECT * FROM score
        WHERE type = :type AND scoringVersion = :version AND configHash = :configHash
          AND localDate BETWEEN :from AND :to
        ORDER BY localDate
        """,
    )
    fun history(
        type: String,
        from: String,
        to: String,
        version: String,
        configHash: String,
    ): Flow<List<ScoreEntity>>

    /** Every stored variant of one day, including ones computed under older weights. */
    @Query("SELECT * FROM score WHERE type = :type AND localDate = :date ORDER BY computedAtEpochMs DESC")
    suspend fun allVariants(type: String, date: String): List<ScoreEntity>

    /** Every type at once, for the Home screen's one read. */
    @Query(
        """
        SELECT * FROM score
        WHERE scoringVersion = :version AND configHash = :configHash
          AND localDate BETWEEN :from AND :to
        ORDER BY localDate
        """,
    )
    fun between(from: String, to: String, version: String, configHash: String): Flow<List<ScoreEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(scores: List<ScoreEntity>)
}
