package app.pawse.core.data.sync

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import app.pawse.core.data.db.ExerciseSessionEntity
import app.pawse.core.data.db.MetricSampleEntity
import app.pawse.core.data.db.SleepSessionEntity
import app.pawse.core.data.db.SleepStageEntity
import app.pawse.scoring.model.Metric
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Health Connect records to storage rows.
 *
 * Two rules run through all of it:
 *
 *  1. Local date is resolved with the offset actually in effect at that instant,
 *     from the record's own zoneOffset when the writer supplied one. A night that
 *     crosses a DST boundary or a flight then still lands on one wake date instead
 *     of splitting or vanishing.
 *  2. A sleep session is attributed to the date it ENDS. Bedtime at 01:00 belongs
 *     to that morning's score, not the previous one.
 */
object RecordMapper {

    /** Sessions shorter than this in the middle of the day are naps, not nights. */
    private val NAP_MAX = Duration.ofMinutes(180)
    private const val NIGHT_START_HOUR = 19
    private const val NIGHT_END_HOUR = 11

    fun localDate(instant: Instant, offset: ZoneOffset?, fallback: ZoneId): String =
        (offset?.let { instant.atOffset(it).toLocalDate() } ?: instant.atZone(fallback).toLocalDate())
            .toString()

    fun offsetSeconds(instant: Instant, offset: ZoneOffset?, fallback: ZoneId): Int =
        offset?.totalSeconds ?: fallback.rules.getOffset(instant).totalSeconds

    fun hrv(record: HeartRateVariabilityRmssdRecord, zone: ZoneId): MetricSampleEntity =
        MetricSampleEntity(
            metric = Metric.HRV_RMSSD.key,
            value = record.heartRateVariabilityMillis,
            startEpochMs = record.time.toEpochMilli(),
            endEpochMs = record.time.toEpochMilli(),
            localDate = localDate(record.time, record.zoneOffset, zone),
            zoneOffsetSeconds = offsetSeconds(record.time, record.zoneOffset, zone),
            sourceApp = record.metadata.dataOrigin.packageName,
            originId = record.metadata.id,
        )

    fun restingHeartRate(record: RestingHeartRateRecord, zone: ZoneId): MetricSampleEntity =
        MetricSampleEntity(
            metric = Metric.RESTING_HEART_RATE.key,
            value = record.beatsPerMinute.toDouble(),
            startEpochMs = record.time.toEpochMilli(),
            endEpochMs = record.time.toEpochMilli(),
            localDate = localDate(record.time, record.zoneOffset, zone),
            zoneOffsetSeconds = offsetSeconds(record.time, record.zoneOffset, zone),
            sourceApp = record.metadata.dataOrigin.packageName,
            originId = record.metadata.id,
        )

    fun respiratoryRate(record: RespiratoryRateRecord, zone: ZoneId): MetricSampleEntity =
        MetricSampleEntity(
            metric = Metric.RESPIRATORY_RATE.key,
            value = record.rate,
            startEpochMs = record.time.toEpochMilli(),
            endEpochMs = record.time.toEpochMilli(),
            localDate = localDate(record.time, record.zoneOffset, zone),
            zoneOffsetSeconds = offsetSeconds(record.time, record.zoneOffset, zone),
            sourceApp = record.metadata.dataOrigin.packageName,
            originId = record.metadata.id,
        )

    fun spo2(record: OxygenSaturationRecord, zone: ZoneId): MetricSampleEntity =
        MetricSampleEntity(
            metric = Metric.SPO2.key,
            value = record.percentage.value,
            startEpochMs = record.time.toEpochMilli(),
            endEpochMs = record.time.toEpochMilli(),
            localDate = localDate(record.time, record.zoneOffset, zone),
            zoneOffsetSeconds = offsetSeconds(record.time, record.zoneOffset, zone),
            sourceApp = record.metadata.dataOrigin.packageName,
            originId = record.metadata.id,
        )

    /**
     * Skin temperature arrives as a baseline plus signed deltas. We store the mean
     * delta in Celsius, because the score is built on deviation from the user's own
     * baseline anyway, and a writer app that omits the absolute baseline is common.
     */
    fun skinTemperature(record: SkinTemperatureRecord, zone: ZoneId): MetricSampleEntity? {
        val deltas = record.deltas.map { it.delta.inCelsius }
        if (deltas.isEmpty()) return null
        return MetricSampleEntity(
            metric = Metric.SKIN_TEMPERATURE.key,
            value = deltas.average(),
            startEpochMs = record.startTime.toEpochMilli(),
            endEpochMs = record.endTime.toEpochMilli(),
            localDate = localDate(record.endTime, record.endZoneOffset, zone),
            zoneOffsetSeconds = offsetSeconds(record.endTime, record.endZoneOffset, zone),
            sourceApp = record.metadata.dataOrigin.packageName,
            originId = record.metadata.id,
        )
    }

    fun sleepSession(record: SleepSessionRecord, zone: ZoneId): Pair<SleepSessionEntity, List<SleepStageEntity>> {
        val session = SleepSessionEntity(
            startEpochMs = record.startTime.toEpochMilli(),
            endEpochMs = record.endTime.toEpochMilli(),
            localDate = localDate(record.endTime, record.endZoneOffset, zone),
            zoneOffsetSeconds = offsetSeconds(record.endTime, record.endZoneOffset, zone),
            startZoneOffsetSeconds = offsetSeconds(record.startTime, record.startZoneOffset, zone),
            sourceApp = record.metadata.dataOrigin.packageName,
            originId = record.metadata.id,
            hasStages = record.stages.isNotEmpty(),
            isNap = isNap(record, zone),
        )
        val stages = record.stages.map {
            SleepStageEntity(
                sessionId = 0,
                stageType = it.stage,
                startEpochMs = it.startTime.toEpochMilli(),
                endEpochMs = it.endTime.toEpochMilli(),
            )
        }
        return session to stages
    }

    /**
     * A nap is short and starts in daylight hours. Getting this wrong in either
     * direction is costly: a nap counted as a night wrecks the sleep baseline, and
     * a genuine short night counted as a nap silently deletes it from history.
     */
    private fun isNap(record: SleepSessionRecord, zone: ZoneId): Boolean {
        val duration = Duration.between(record.startTime, record.endTime)
        if (duration > NAP_MAX) return false
        val startHour = record.startZoneOffset
            ?.let { record.startTime.atOffset(it).hour }
            ?: record.startTime.atZone(zone).hour
        return startHour in NIGHT_END_HOUR until NIGHT_START_HOUR
    }

    fun exercise(
        record: ExerciseSessionRecord,
        zone: ZoneId,
        meanHeartRate: Double?,
        maxHeartRate: Double?,
        activeKcal: Double?,
    ): ExerciseSessionEntity = ExerciseSessionEntity(
        exerciseType = record.exerciseType,
        title = record.title,
        startEpochMs = record.startTime.toEpochMilli(),
        endEpochMs = record.endTime.toEpochMilli(),
        localDate = localDate(record.startTime, record.startZoneOffset, zone),
        zoneOffsetSeconds = offsetSeconds(record.startTime, record.startZoneOffset, zone),
        sourceApp = record.metadata.dataOrigin.packageName,
        originId = record.metadata.id,
        meanHeartRate = meanHeartRate,
        maxHeartRate = maxHeartRate,
        activeKcal = activeKcal,
    )
}
