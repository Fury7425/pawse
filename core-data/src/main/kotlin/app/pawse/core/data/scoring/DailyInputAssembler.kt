package app.pawse.core.data.scoring

import app.pawse.core.data.db.DayNoteEntity
import app.pawse.core.data.db.ExerciseSessionEntity
import app.pawse.core.data.db.FoodLogEntryEntity
import app.pawse.core.data.db.MetricSampleEntity
import app.pawse.core.data.db.SleepSessionEntity
import app.pawse.scoring.config.ScoringConfig
import app.pawse.scoring.math.Stats
import app.pawse.scoring.model.DayTag
import app.pawse.scoring.model.EngineInput
import app.pawse.scoring.model.Intake
import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.NightInput
import app.pawse.scoring.model.Signal
import app.pawse.scoring.model.SleepInterval
import app.pawse.scoring.model.SleepNight
import app.pawse.scoring.model.UserProfile
import app.pawse.scoring.model.WorkoutSample
import app.pawse.scoring.signal.SignalLayer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Storage rows to the engine's input.
 *
 * This is where Android's real data mess is dealt with, so `:core-scoring` never
 * has to know about it:
 *
 * **One definition and one source per nightly number.** Duplicate writers resolve
 * by priority, never by averaging. Pawse's own sleeping HR (from the raw series)
 * and the vendor's resting-HR fallback are different definitions, so they carry
 * different source keys and never share a baseline.
 *
 * **An overnight metric has to have been measured overnight.** HRV, breathing and
 * SpO2 are read inside the sleep window, or failing that from the night hours
 * around it, because many apps stamp the night's aggregate at wake time. A midday
 * spot reading is dropped.
 *
 * **Absent stays absent.** No population defaults, no zeros standing in for nulls.
 */
class DailyInputAssembler(
    private val config: ScoringConfig = ScoringConfig.DEFAULT,
    private val sourcePriority: List<String> = emptyList(),
) {

    fun assemble(
        dates: List<LocalDate>,
        samples: List<MetricSampleEntity>,
        nights: Map<String, NightSummary>,
        sessions: List<SleepSessionEntity> = emptyList(),
        exercises: List<ExerciseSessionEntity> = emptyList(),
        food: List<FoodLogEntryEntity> = emptyList(),
        notes: List<DayNoteEntity> = emptyList(),
        profile: UserProfile = UserProfile(),
        calibrationSince: LocalDate? = null,
    ): EngineInput {
        val byMetricDate = samples.groupBy { it.metric to it.localDate }
        val ordered = dates.sorted()

        val nightInputs = ordered.associateWith { date ->
            val key = date.toString()
            val night = nights[key]?.takeIf { it.hasSession }

            val hrvRows = overnightRows(Metric.HRV_RMSSD, key, night, byMetricDate)
            val hrv = SignalLayer.hrv(
                inNight = hrvRows.inWindow.map { it.value },
                fallback = Stats.median(hrvRows.nightHours.map { it.value }),
                config = config.signal,
            )
            val hrvSource = (hrvRows.inWindow + hrvRows.nightHours).firstOrNull()?.sourceApp

            val series = resolveSource(byMetricDate[Metric.SLEEPING_HEART_RATE.key to key].orEmpty()).firstOrNull()
            val resting = dailyRows(Metric.RESTING_HEART_RATE, key, byMetricDate)
            val sleepingHr = when {
                series != null -> Signal(series.value, "${series.sourceApp}$SERIES")
                resting.isNotEmpty() -> Signal(resting.map { it.value }.average(), "${resting.first().sourceApp}$RESTING")
                else -> null
            }
            val coverage = byMetricDate[Metric.NIGHT_HR_COVERAGE.key to key]?.firstOrNull()?.value

            val breathing = overnightRows(Metric.RESPIRATORY_RATE, key, night, byMetricDate).all
            val spo2 = overnightRows(Metric.SPO2, key, night, byMetricDate).all
            val temp = dailyRows(Metric.SKIN_TEMPERATURE, key, byMetricDate)

            NightInput(
                date = date,
                lnHrv = hrv?.let { Signal(it.lnRmssd, hrvSource ?: UNKNOWN) },
                sleepingHr = sleepingHr,
                restingHr = resting.takeIf { it.isNotEmpty() }?.map { it.value }?.average(),
                quality = SignalLayer.quality(coverage, hrv, config.signal),
                breathingRate = SignalLayer.overnightMedian(breathing.map { it.value }, config.signal),
                skinTempDelta = temp.takeIf { it.isNotEmpty() }?.map { it.value }?.average(),
                spo2 = SignalLayer.overnightMedian(spo2.map { it.value }, config.signal),
                hrNadirFraction = byMetricDate[Metric.HR_NADIR.key to key]?.firstOrNull()?.value,
                sleep = night?.let(::sleepNight),
            )
        }

        val tags = notes.associate { note ->
            LocalDate.parse(note.localDate) to note.tags.split(',')
                .mapNotNull { name -> runCatching { DayTag.valueOf(name.trim()) }.getOrNull() }
                .toSet()
        }.filterValues { it.isNotEmpty() }

        return EngineInput(
            days = ordered,
            nights = nightInputs,
            sleepIntervals = sessions.map { SleepInterval(it.startEpochMs, it.endEpochMs, it.zoneOffsetSeconds) },
            workouts = exercises.groupBy { LocalDate.parse(it.localDate) }.mapValues { (_, list) -> list.map(::toWorkout) },
            intakes = food.map { Intake(it.eatenAtEpochMs, it.kcal, it.caffeineMg, it.alcoholGrams) },
            tags = tags,
            checkIns = notes.mapNotNull { n -> n.feeling?.takeIf { it in 1..5 }?.let { LocalDate.parse(n.localDate) to it } }.toMap(),
            periodDays = periodDays(samples.filter { it.metric == Metric.MENSTRUATION.key }),
            profile = profile,
            calibrationSince = calibrationSince,
        )
    }

    private fun sleepNight(night: NightSummary): SleepNight = SleepNight(
        startEpochMs = night.startEpochMs!!,
        endEpochMs = night.endEpochMs!!,
        startOffsetSeconds = night.startOffsetSeconds,
        endOffsetSeconds = night.endOffsetSeconds,
        asleepMinutes = night.asleepMinutes,
        inBedMinutes = night.inBedMinutes,
        wasoMinutes = night.wasoMinutes,
        remMinutes = night.remMinutes,
        deepMinutes = night.deepMinutes,
    )

    private fun toWorkout(entity: ExerciseSessionEntity): WorkoutSample = WorkoutSample(
        id = entity.originId.ifBlank { entity.id.toString() },
        durationMinutes = (entity.endEpochMs - entity.startEpochMs) / 60_000.0,
        meanHeartRate = entity.meanHeartRate,
        maxHeartRate = entity.maxHeartRate,
        title = entity.title,
    )

    /** Every local date a logged period touches. */
    private fun periodDays(rows: List<MetricSampleEntity>): Set<LocalDate> = rows.flatMap { row ->
        val offset = ZoneOffset.ofTotalSeconds(row.zoneOffsetSeconds)
        val first = Instant.ofEpochMilli(row.startEpochMs).atOffset(offset).toLocalDate()
        val last = Instant.ofEpochMilli(maxOf(row.startEpochMs, row.endEpochMs - 1)).atOffset(offset).toLocalDate()
        (0..(last.toEpochDay() - first.toEpochDay()).coerceIn(0, MAX_PERIOD_DAYS)).map { first.plusDays(it) }
    }.toSet()

    private class Overnight(val inWindow: List<MetricSampleEntity>, val nightHours: List<MetricSampleEntity>) {
        /** In-window readings when there are any, the night-hours aggregate otherwise. */
        val all: List<MetricSampleEntity> get() = inWindow.ifEmpty { nightHours }
    }

    private fun overnightRows(
        metric: Metric,
        date: String,
        night: NightSummary?,
        samples: Map<Pair<String, String>, List<MetricSampleEntity>>,
    ): Overnight {
        val candidates = resolveSource(
            samples[metric.key to date].orEmpty() + samples[metric.key to previousDay(date)].orEmpty(),
        )
        val start = night?.startEpochMs
        val end = night?.endEpochMs
        val inWindow = if (start != null && end != null) candidates.filter { it.startEpochMs in start..end } else emptyList()
        val previous = previousDay(date)
        val nightHours = candidates.filter { row ->
            val hour = Instant.ofEpochMilli(row.startEpochMs).atOffset(ZoneOffset.ofTotalSeconds(row.zoneOffsetSeconds)).hour
            (row.localDate == date && hour < NIGHT_END_HOUR) || (row.localDate == previous && hour >= NIGHT_START_HOUR)
        }
        return Overnight(inWindow, nightHours - inWindow.toSet())
    }

    private fun dailyRows(
        metric: Metric,
        date: String,
        samples: Map<Pair<String, String>, List<MetricSampleEntity>>,
    ): List<MetricSampleEntity> = resolveSource(samples[metric.key to date].orEmpty())

    /**
     * Duplicate resolution. The first source in the user's priority list that wrote
     * anything wins the whole day for that metric; without a list, the first seen.
     */
    private fun resolveSource(rows: List<MetricSampleEntity>): List<MetricSampleEntity> {
        val bySource = rows.groupBy { it.sourceApp }
        if (bySource.size <= 1) return rows
        val winner = sourcePriority.firstOrNull { bySource.containsKey(it) } ?: bySource.keys.first()
        return bySource[winner] ?: rows
    }

    private fun previousDay(date: String): String = LocalDate.parse(date).minusDays(1).toString()

    companion object {
        /** Source suffixes: Pawse's own sleeping HR and the vendor's resting HR never share a baseline. */
        const val SERIES = "#series"
        const val RESTING = "#resting"
        private const val UNKNOWN = "unknown"
        private const val NIGHT_START_HOUR = 20
        private const val NIGHT_END_HOUR = 11
        private const val MAX_PERIOD_DAYS = 14L
    }
}
