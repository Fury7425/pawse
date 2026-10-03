package app.pawse.core.data.scoring

import app.pawse.core.data.db.ExerciseSessionEntity
import app.pawse.core.data.db.MetricSampleEntity
import app.pawse.core.data.db.SleepSessionEntity
import app.pawse.core.data.db.SleepStageEntity
import app.pawse.scoring.model.Metric
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Storage rows, built by hand.
 *
 * Everything here is stamped in UTC so that a test asserting "bedtime was 23:00"
 * is asserting about the code under test rather than about the timezone the CI
 * runner happened to boot in.
 */
object Rows {

    const val UTC = 0

    fun at(date: String, time: String, offsetSeconds: Int = UTC): Long =
        LocalDateTime.parse("${date}T$time")
            .toInstant(ZoneOffset.ofTotalSeconds(offsetSeconds))
            .toEpochMilli()

    fun sample(
        metric: Metric,
        value: Double,
        date: String,
        time: String,
        source: String = "com.example.watch",
        offsetSeconds: Int = UTC,
    ): MetricSampleEntity {
        val epoch = at(date, time, offsetSeconds)
        return MetricSampleEntity(
            metric = metric.key,
            value = value,
            startEpochMs = epoch,
            endEpochMs = epoch,
            localDate = date,
            zoneOffsetSeconds = offsetSeconds,
            sourceApp = source,
            originId = "$metric-$date-$time-$source",
        )
    }

    /**
     * A night, attributed to the date it ends on.
     *
     * [bedDate] is the calendar date of lights-out, which is normally the day
     * before [wakeDate] — the attribution rule the whole app runs on.
     */
    fun night(
        id: Long,
        bedDate: String,
        bedTime: String,
        wakeDate: String,
        wakeTime: String,
        hasStages: Boolean = false,
        source: String = "com.example.watch",
        offsetSeconds: Int = UTC,
        startOffsetSeconds: Int = offsetSeconds,
    ): SleepSessionEntity = SleepSessionEntity(
        id = id,
        startEpochMs = at(bedDate, bedTime, startOffsetSeconds),
        endEpochMs = at(wakeDate, wakeTime, offsetSeconds),
        localDate = wakeDate,
        zoneOffsetSeconds = offsetSeconds,
        startZoneOffsetSeconds = startOffsetSeconds,
        sourceApp = source,
        originId = "night-$id",
        hasStages = hasStages,
        isNap = false,
    )

    fun nap(id: Long, date: String, from: String, to: String): SleepSessionEntity = SleepSessionEntity(
        id = id,
        startEpochMs = at(date, from),
        endEpochMs = at(date, to),
        localDate = date,
        zoneOffsetSeconds = UTC,
        startZoneOffsetSeconds = UTC,
        sourceApp = "com.example.watch",
        originId = "nap-$id",
        hasStages = false,
        isNap = true,
    )

    fun stage(
        sessionId: Long,
        stageType: Int,
        fromDate: String,
        from: String,
        toDate: String,
        to: String,
    ): SleepStageEntity = SleepStageEntity(
        sessionId = sessionId,
        stageType = stageType,
        startEpochMs = at(fromDate, from),
        endEpochMs = at(toDate, to),
    )

    fun workout(
        id: Long,
        date: String,
        from: String,
        to: String,
        meanHeartRate: Double?,
        maxHeartRate: Double? = null,
        title: String? = "Run",
    ): ExerciseSessionEntity = ExerciseSessionEntity(
        id = id,
        exerciseType = 56,
        title = title,
        startEpochMs = at(date, from),
        endEpochMs = at(date, to),
        localDate = date,
        zoneOffsetSeconds = UTC,
        sourceApp = "com.example.watch",
        originId = "workout-$id",
        meanHeartRate = meanHeartRate,
        maxHeartRate = maxHeartRate,
        activeKcal = null,
    )
}
