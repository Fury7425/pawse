package app.pawse.core.data.scoring

import app.pawse.core.data.db.SleepSessionEntity
import app.pawse.core.data.db.SleepStageEntity
import java.time.Instant
import java.time.ZoneOffset

/**
 * One night, reduced to the quantities the sleep engine consumes.
 *
 * Everything optional here is optional because some real writer app omits it. A
 * null is never a zero: a night with no stage data has `remMinutes = null`, and
 * the scorer drops the term and renormalises rather than scoring the user as
 * having had no REM sleep.
 */
data class NightSummary(
    /** Wake date. A session is attributed to the morning it ends on. */
    val date: String,
    val hasSession: Boolean,
    /** True only when real stage blocks (light / deep / REM) were present. */
    val hasStages: Boolean,
    val inBedMinutes: Double,
    val asleepMinutes: Double,
    val remMinutes: Double?,
    val deepMinutes: Double?,
    val wasoMinutes: Double?,
    val awakenings: Int?,
    /** Wake blocks long enough to remember, which is what Apple's bucket counts. */
    val interruptions: Int?,
    val latencyMinutes: Double?,
    /** Local clock time of lights-out, minutes since midnight. */
    val bedtimeMinutes: Double?,
    /** Local clock time of the sleep midpoint, minutes since midnight. */
    val midpointMinutes: Double?,
    val napMinutes: Int,
    /** Non-zero when the night crossed a UTC-offset change. Suppresses circadian terms. */
    val timezoneShiftMinutes: Int,
    val startEpochMs: Long?,
    val endEpochMs: Long?,
) {
    val efficiencyPercent: Double?
        get() = if (inBedMinutes <= 0.0) null else 100.0 * asleepMinutes / inBedMinutes

    companion object {
        fun empty(date: String, napMinutes: Int = 0): NightSummary = NightSummary(
            date = date,
            hasSession = false,
            hasStages = false,
            inBedMinutes = 0.0,
            asleepMinutes = 0.0,
            remMinutes = null,
            deepMinutes = null,
            wasoMinutes = null,
            awakenings = null,
            interruptions = null,
            latencyMinutes = null,
            bedtimeMinutes = null,
            midpointMinutes = null,
            napMinutes = napMinutes,
            timezoneShiftMinutes = 0,
            startEpochMs = null,
            endEpochMs = null,
        )
    }
}

/**
 * Sessions and stage blocks to a [NightSummary].
 *
 * Three decisions, each of which changes a number a user will read:
 *
 * **Split sleep is one night with a hole in it.** A 03:10 wake and a 04:05 return
 * arrive as two sessions on the same wake date. Time in bed spans first bedtime to
 * final wake, and the gap between them is counted as time awake — not deleted, and
 * not counted as sleep. Summing the two sessions and calling it time in bed would
 * report 96% efficiency for a night the user remembers as broken.
 *
 * **Stages are trusted for time asleep only when they cover the night.** Several
 * writer apps emit a handful of stage blocks inside a much longer session. If the
 * staged minutes fall far short of the session envelope, the envelope wins and the
 * night is treated as unstaged, because the alternative is reporting four hours of
 * sleep for an eight-hour night.
 *
 * **Latency needs `AWAKE_IN_BED`.** Without stages we do not know when the user lay
 * down versus when they fell asleep, so latency is null rather than zero. Zero
 * would score as "fell asleep instantly", which is itself a signal in the engine.
 */
object NightAssembler {

    /** A wake block at least this long is one Apple would call an interruption. */
    const val INTERRUPTION_MINUTES: Double = 5.0

    /**
     * Below this fraction of the session envelope, staged minutes are treated as
     * partial coverage rather than as the night's true sleep time.
     */
    const val STAGE_COVERAGE_FLOOR: Double = 0.5

    fun summarise(
        date: String,
        sessions: List<SleepSessionEntity>,
        stagesBySession: Map<Long, List<SleepStageEntity>>,
    ): NightSummary {
        val naps = sessions.filter { it.isNap }
        val napMinutes = naps.sumOf { minutesBetween(it.startEpochMs, it.endEpochMs) }.toInt()

        val nights = sessions.filter { !it.isNap }.sortedBy { it.startEpochMs }
        if (nights.isEmpty()) return NightSummary.empty(date, napMinutes)

        val start = nights.first().startEpochMs
        val end = nights.maxOf { it.endEpochMs }
        val inBed = minutesBetween(start, end)

        // Gaps between sessions are time awake, in bed or otherwise.
        val gaps = nights.zipWithNext()
            .map { (a, b) -> minutesBetween(a.endEpochMs, b.startEpochMs) }
            .filter { it > 0.0 }

        val blocks = nights.flatMap { session ->
            stagesBySession[session.id].orEmpty().map { stage ->
                StageBlock(
                    kind = SleepStages.of(stage.stageType),
                    startEpochMs = stage.startEpochMs,
                    minutes = minutesBetween(stage.startEpochMs, stage.endEpochMs),
                )
            }
        }.sortedBy { it.startEpochMs }

        val stagedMinutes = blocks.filter { it.kind.isStaged }.sumOf { it.minutes }
        val asleepFromStages = blocks.filter { it.kind.isAsleep }.sumOf { it.minutes }
        val sessionMinutes = nights.sumOf { minutesBetween(it.startEpochMs, it.endEpochMs) }

        val stagesCoverNight = stagedMinutes > 0.0 &&
            sessionMinutes > 0.0 &&
            asleepFromStages / sessionMinutes >= STAGE_COVERAGE_FLOOR

        val asleep = if (asleepFromStages > 0.0 && stagesCoverNight) asleepFromStages else sessionMinutes

        val latency = if (stagesCoverNight) leadingAwakeMinutes(blocks) else null

        val wakeBlocks: List<Double> = if (stagesCoverNight) {
            wakeBlocksAfterOnset(blocks) + gaps
        } else {
            gaps
        }
        val waso = if (stagesCoverNight || gaps.isNotEmpty()) wakeBlocks.sum() else null
        val awakenings = if (stagesCoverNight || gaps.isNotEmpty()) wakeBlocks.count { it > 0.0 } else null
        val interruptions = if (stagesCoverNight || gaps.isNotEmpty()) {
            wakeBlocks.count { it >= INTERRUPTION_MINUTES }
        } else {
            // No stages and no gaps: an unbroken envelope. Reporting zero
            // interruptions here is a claim the data does not support.
            null
        }

        val startOffset = ZoneOffset.ofTotalSeconds(nights.first().startZoneOffsetSeconds)
        val wakeOffset = ZoneOffset.ofTotalSeconds(nights.last().zoneOffsetSeconds)

        return NightSummary(
            date = date,
            hasSession = true,
            hasStages = stagesCoverNight,
            inBedMinutes = inBed,
            asleepMinutes = asleep,
            remMinutes = if (stagesCoverNight) blocks.filter { it.kind == SleepStageKind.REM }.sumOf { it.minutes } else null,
            deepMinutes = if (stagesCoverNight) blocks.filter { it.kind == SleepStageKind.DEEP }.sumOf { it.minutes } else null,
            wasoMinutes = waso,
            awakenings = awakenings,
            interruptions = interruptions,
            latencyMinutes = latency,
            bedtimeMinutes = localMinuteOfDay(start, startOffset),
            midpointMinutes = localMinuteOfDay(start + (end - start) / 2, wakeOffset),
            napMinutes = napMinutes,
            timezoneShiftMinutes = (nights.last().zoneOffsetSeconds - nights.first().startZoneOffsetSeconds) / 60,
            startEpochMs = start,
            endEpochMs = end,
        )
    }

    /**
     * Awake-in-bed time before the first asleep block. Plain `AWAKE` at the very
     * start counts too: some apps use it where others use `AWAKE_IN_BED`, and the
     * distinction is not worth losing a real latency over.
     */
    private fun leadingAwakeMinutes(blocks: List<StageBlock>): Double? {
        val firstAsleep = blocks.indexOfFirst { it.kind.isAsleep }
        if (firstAsleep <= 0) return if (firstAsleep == 0) 0.0 else null
        return blocks.take(firstAsleep).filter { it.kind.isAwake }.sumOf { it.minutes }
    }

    private fun wakeBlocksAfterOnset(blocks: List<StageBlock>): List<Double> {
        val firstAsleep = blocks.indexOfFirst { it.kind.isAsleep }
        if (firstAsleep < 0) return emptyList()
        return blocks.drop(firstAsleep)
            .filter { it.kind.isAwake && it.minutes > 0.0 }
            .map { it.minutes }
    }

    private fun localMinuteOfDay(epochMs: Long, offset: ZoneOffset): Double {
        val time = Instant.ofEpochMilli(epochMs).atOffset(offset).toLocalTime()
        return time.hour * 60.0 + time.minute + time.second / 60.0
    }

    private fun minutesBetween(startMs: Long, endMs: Long): Double = (endMs - startMs) / 60_000.0

    private data class StageBlock(val kind: SleepStageKind, val startEpochMs: Long, val minutes: Double)
}
