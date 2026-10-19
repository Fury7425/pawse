package app.pawse.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One normalised physiological value.
 *
 * The unique index is the duplicate guard. Two source apps mirroring the same
 * watch (Samsung Health plus a third-party sync, say) write records that overlap
 * in time but differ in origin; we keep both rows here and resolve to one value at
 * read time by source priority, rather than throwing data away at ingest.
 */
@Entity(
    tableName = "metric_sample",
    indices = [
        Index(value = ["metric", "startEpochMs", "endEpochMs", "sourceApp"], unique = true),
        Index(value = ["metric", "localDate"]),
    ],
)
data class MetricSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val metric: String,
    val value: Double,
    val startEpochMs: Long,
    val endEpochMs: Long,
    /** Local date the sample is attributed to, resolved with the offset in effect then. */
    val localDate: String,
    /** UTC offset in seconds at the sample's own time. Kept so a DST night stays reconstructable. */
    val zoneOffsetSeconds: Int,
    val sourceApp: String,
    /** Health Connect record id, so an updated or deleted upstream record can be followed. */
    val originId: String,
)

@Entity(
    tableName = "sleep_session",
    indices = [
        Index(value = ["startEpochMs", "endEpochMs", "sourceApp"], unique = true),
        Index(value = ["localDate"]),
    ],
)
data class SleepSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startEpochMs: Long,
    val endEpochMs: Long,
    /** Wake date. A session is attributed to the day it ends, so a 01:00 bedtime lands right. */
    val localDate: String,
    val zoneOffsetSeconds: Int,
    /** Offset at bedtime, which differs from the wake offset across a DST boundary. */
    val startZoneOffsetSeconds: Int,
    val sourceApp: String,
    val originId: String,
    val hasStages: Boolean,
    /** True when the session is short and daytime: scored as a nap credit, not a night. */
    val isNap: Boolean,
)

@Entity(
    tableName = "sleep_stage",
    indices = [Index(value = ["sessionId"])],
)
data class SleepStageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    /** Health Connect STAGE_TYPE_* constant. */
    val stageType: Int,
    val startEpochMs: Long,
    val endEpochMs: Long,
)

@Entity(
    tableName = "exercise_session",
    indices = [
        Index(value = ["startEpochMs", "endEpochMs", "sourceApp"], unique = true),
        Index(value = ["localDate"]),
    ],
)
data class ExerciseSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val exerciseType: Int,
    val title: String?,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val localDate: String,
    val zoneOffsetSeconds: Int,
    val sourceApp: String,
    val originId: String,
    val meanHeartRate: Double?,
    val maxHeartRate: Double?,
    val activeKcal: Double?,
)

/**
 * What the user told us about one day: the one-tap morning check-in and tags.
 *
 * The check-in never feeds a score, only the calibration, so Body cannot turn into
 * a mirror of mood. Tags explain odd days and hold back alerts.
 */
@Entity(tableName = "day_note")
data class DayNoteEntity(
    @PrimaryKey val localDate: String,
    /** "How do you feel?" 1..5, or null when not answered. */
    val feeling: Int?,
    /** Comma-separated [app.pawse.scoring.model.DayTag] names. */
    val tags: String,
    val updatedAtEpochMs: Long,
)
