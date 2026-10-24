package app.pawse.scoring.model

import java.time.LocalDate

// ---------------------------------------------------------------------------
// Inputs. Already reduced from storage by the data layer; the engine touches no
// clock, no database and no Health Connect.
// ---------------------------------------------------------------------------

/** One nightly value and the app that wrote it. Baselines are keyed by [source]. */
data class Signal(val value: Double, val source: String)

/** The main sleep of one night, attributed to the morning it ends. */
data class SleepNight(
    val startEpochMs: Long,
    val endEpochMs: Long,
    /** UTC offset at bedtime and at waking, seconds. Differ across a flight or DST. */
    val startOffsetSeconds: Int,
    val endOffsetSeconds: Int,
    val asleepMinutes: Double,
    val inBedMinutes: Double,
    val wasoMinutes: Double? = null,
    /** Stages are shown, never scored: wrist staging is the least reliable thing a watch measures. */
    val remMinutes: Double? = null,
    val deepMinutes: Double? = null,
) {
    val efficiencyPercent: Double? get() = if (inBedMinutes > 0.0) 100.0 * asleepMinutes / inBedMinutes else null

    /** Local clock minute of lights-out. */
    val bedtimeMinute: Double get() = localMinute(startEpochMs, startOffsetSeconds)
    val wakeMinute: Double get() = localMinute(endEpochMs, endOffsetSeconds)
}

/** Anything asleep, naps included. The Sleep Regularity Index is built from these. */
data class SleepInterval(val startEpochMs: Long, val endEpochMs: Long, val offsetSeconds: Int)

/** One night's signal layer output, keyed by wake date. */
data class NightInput(
    val date: LocalDate,
    /** ln of the median overnight RMSSD. */
    val lnHrv: Signal? = null,
    /** Lowest 30-minute rolling mean of overnight heart rate, or the vendor's resting HR. */
    val sleepingHr: Signal? = null,
    /** The vendor's daily resting HR, used as HRrest for TRIMP. */
    val restingHr: Double? = null,
    /** 0..1: how much to trust this night. */
    val quality: Double = 1.0,
    val breathingRate: Double? = null,
    /** Mean skin-temperature delta in the night, degC, as the device reports it. */
    val skinTempDelta: Double? = null,
    val spo2: Double? = null,
    val hrNadirFraction: Double? = null,
    val sleep: SleepNight? = null,
)

/** One thing logged in the barcode log, reduced to what the engine reads. */
data class Intake(
    val epochMs: Long,
    val kcal: Double? = null,
    val caffeineMg: Double? = null,
    val alcoholGrams: Double? = null,
)

/** One-tap tags. They explain odd days and hold back alerts. */
enum class DayTag { SICK, TRAVEL, STRESS, TROUBLE_SLEEPING, OTHER }

data class EngineInput(
    /** Contiguous, oldest first. The last one is the day being shown. */
    val days: List<LocalDate>,
    val nights: Map<LocalDate, NightInput> = emptyMap(),
    val sleepIntervals: List<SleepInterval> = emptyList(),
    /** Training done on each calendar day. */
    val workouts: Map<LocalDate, List<WorkoutSample>> = emptyMap(),
    val intakes: List<Intake> = emptyList(),
    val tags: Map<LocalDate, Set<DayTag>> = emptyMap(),
    /** Morning check-in, 1..5. Never feeds a score, only calibration. */
    val checkIns: Map<LocalDate, Int> = emptyMap(),
    val periodDays: Set<LocalDate> = emptySet(),
    val profile: UserProfile = UserProfile(),
    /** Check-ins before this date are ignored: the "reset my model" button. */
    val calibrationSince: LocalDate? = null,
)

// ---------------------------------------------------------------------------
// Outputs.
// ---------------------------------------------------------------------------

/** The three cards. Also the explain screen's route argument. */
enum class ScoreType { BODY, SLEEP, LOAD }

/** Body bands. Words first; the number can be hidden. */
enum class BodyBand {
    ABOVE_NORMAL, TYPICAL, BELOW_NORMAL, STRAINED;

    companion object {
        /** 70-100, 31-69, 16-30, 0-15: Body = 100 x Phi(z) at z = +0.5, -0.5, -1. */
        fun of(score: Int): BodyBand = when {
            score >= 70 -> ABOVE_NORMAL
            score >= 31 -> TYPICAL
            score >= 16 -> BELOW_NORMAL
            else -> STRAINED
        }
    }
}

enum class Confidence { HIGH, MEDIUM, LOW }

/** One of the two physiological signals, in display units. */
data class SignalDetail(
    /** Last night's raw value: ms for HRV, bpm for sleeping HR. Null on a missed night. */
    val tonight: Double?,
    /** The filter's estimate of the true state, display units. */
    val filtered: Double,
    /** Your normal: median filtered state over the baseline window, display units. */
    val baseline: Double,
    /** Robust spread of nightly values, model units (ln for HRV, bpm for HR). */
    val spread: Double,
    /** Higher is better for both. */
    val z: Double,
    /** Unfiltered tonight vs normal. What dose-response and the illness detector read. */
    val rawZ: Double?,
    val source: String,
    val baselineNights: Int,
    val cycleAdjusted: Boolean = false,
)

enum class ReasonKind {
    SLEEPING_HR_ABOVE, SLEEPING_HR_BELOW, HRV_BELOW, HRV_ABOVE,
    ILLNESS_WATCH, SICK_TAG, DRINKS, SHORT_NIGHT, SLEEP_DEBT, HARD_TRAINING,
    LATE_MEAL, CAFFEINE_AT_BED, TRAVEL_TAG, STRESS_TAG, CYCLE,
}

/** A reason, with its size in the reason's own unit (bpm, %, drinks, hours, kcal, mg, x typical). */
data class BodyReason(val kind: ReasonKind, val amount: Double)

data class BodyScore(
    val score: Int,
    val low: Int,
    val high: Int,
    val band: BodyBand,
    val confidence: Confidence,
    val z: Double,
    /** The filter's SD on z, which the range and confidence come from. */
    val zSd: Double,
    val hrv: SignalDetail?,
    val sleepingHr: SignalDetail?,
    val rho: Double,
    val hrvWeight: Double,
    /** Combined unfiltered signal for last night. Null when neither signal was measured. */
    val rawZ: Double?,
    /** Top two, physiology first. */
    val reasons: List<BodyReason>,
    val validNights: Int,
)

enum class BodyStatus { READY, WARMING_UP, NO_RECENT_NIGHT }

enum class WeeklyPattern { STEADY, ADAPTING, STRAINED, POSSIBLE_OVERREACHING, MIXED }

data class WeeklyReading(
    val pattern: WeeklyPattern,
    /** Seven-day mean HRV, ms. */
    val hrv7: Double,
    /** Day-to-day variation of lnRMSSD over seven days, percent. */
    val cv7: Double,
    val usualCv7: Double?,
    val nights: Int,
)

data class SleepScoreParts(
    /** Each 0..1 before weighting. Null when not measurable. */
    val duration: Double,
    val regularity: Double?,
    val continuity: Double?,
)

data class SleepReading(
    val needHours: Double,
    /** False while need is still the 8 h default. */
    val needLearned: Boolean,
    val freeNights: Int,
    val debtHours: Double?,
    /** Sleep Regularity Index, -100..100. */
    val sri: Double?,
    val lastNightMinutes: Double?,
    val score: Int?,
    val parts: SleepScoreParts?,
    val efficiencyPercent: Double?,
    val wasoMinutes: Double?,
    val remMinutes: Double?,
    val deepMinutes: Double?,
    val suggestDoctor: Boolean,
)

enum class LoadMethod { BANISTER, EDWARDS, RPE }

data class SessionLoad(
    val id: String,
    val title: String?,
    val durationMinutes: Double,
    val reserveFraction: Double?,
    val load: Double,
    val method: LoadMethod?,
    val provenance: Provenance,
    val citation: String,
    val skippedReason: String? = null,
    val source: String = "",
)

enum class FormBand { FRESH, PRODUCTIVE, HEAVY }

data class LoadReading(
    val todayLoad: Double,
    val sessions: List<SessionLoad>,
    val fitness: Double?,
    val fatigue: Double?,
    /** (Fit - Fat) / Fit. */
    val form: Double?,
    val formBand: FormBand?,
    /** Median non-zero daily load. */
    val typicalDayLoad: Double?,
    val rangeLow: Double?,
    val rangeHigh: Double?,
    /** An illness alert turns today's range into rest. */
    val rest: Boolean,
)

enum class IllnessLevel { NONE, WATCH, ALERT }

data class IllnessState(
    val level: IllnessLevel,
    val cusum: Double,
    val tempUp: Boolean,
    val breathingUp: Boolean,
    /** Logged reasons that held an alert back or likely explain a watch. Never a disease. */
    val likelyCauses: List<ReasonKind>,
)

data class CycleNote(
    val luteal: Boolean,
    /** True once offsets were learned from two cycles and removed before scoring. */
    val adjusted: Boolean,
)

enum class Guidance { WATCH_SYMPTOMS, REST_DAY, KEEP_LIGHT, GO_MODERATE, GOOD_DAY, SETTLING }

data class EnergyPoint(
    /** Minutes since local midnight of the shown day. May run past 1440. */
    val minute: Int,
    val alertness: Double,
    val caffeineMg: Double,
)

data class EnergyForecast(
    val points: List<EnergyPoint>,
    val wakeMinute: Int,
    val focusStart: Int,
    val focusEnd: Int,
    val dipMinute: Int,
    val windDownMinute: Int,
    val bedtimeMinute: Int,
    val caffeineAtBedMg: Double,
    /** Non-zero while the body clock is catching up after a time-zone change. */
    val clockLagMinutes: Int,
)

data class DayReport(
    val date: LocalDate,
    val body: BodyScore?,
    val bodyStatus: BodyStatus,
    /** Valid nights so far, for the warm-up countdown. */
    val validNights: Int,
    val weekly: WeeklyReading?,
    val sleep: SleepReading,
    val load: LoadReading,
    val illness: IllnessState,
    val cycle: CycleNote?,
    val guidance: Guidance,
    /** Consecutive days ending here with Body in Strained. */
    val strainedRun: Int,
)

enum class Predictor { TRAINING, SLEEP_DEBT, ALCOHOL, CAFFEINE, LATE_MEAL }

/**
 * "After nights with a drink, your Body signal ran 0.4 SD lower (0.2 to 0.6),
 * seen 11 times." An association in one person's data, not proof of cause.
 */
data class Insight(
    val predictor: Predictor,
    /** Effect on next morning's Body signal, in SDs, per [Predictor] unit. */
    val effect: Double,
    val low: Double,
    val high: Double,
    val exposureDays: Int,
)

data class ModelChange(val date: LocalDate, val what: String, val from: Double, val to: Double)

data class Calibration(
    val checkIns: Int,
    /** Spearman r between Body and the same morning's check-in. */
    val bodyMatch: Double?,
    val hrvWeight: Double,
    val kappa: Double,
    val changes: List<ModelChange>,
)

data class EngineResult(
    val days: List<DayReport>,
    val energy: EnergyForecast?,
    val insights: List<Insight>,
    val calibration: Calibration,
    val scoringVersion: String,
    val configHash: String,
)

internal fun localMinute(epochMs: Long, offsetSeconds: Int): Double {
    val local = Math.floorMod(epochMs / 1000 + offsetSeconds, 86_400L)
    return local / 60.0
}
