package app.pawse.scoring.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Every tunable number in the v2 engine, versioned, serialisable, hashable.
 *
 * Spec rule: every number marked as a default here is a reasoned starting point,
 * not a validated value. The validation plan in PLAN.md is how each one earns the
 * right to stay. Nothing in the engine hard-codes a coefficient.
 */

@Serializable
enum class BaselineMode {
    /** Exponentially recency-weighted mean and SD. */
    MEAN_SD,

    /** Median and MAD-derived sigma. Robust to a single wild night or a bad seal. */
    MEDIAN_MAD,
}

/** The Body tab's usual-range engine. Scale data, not part of any score. */
@Serializable
data class BaselineConfig(
    val windowDays: Int = 60,
    val halfLifeDays: Double = 14.0,
    val mode: BaselineMode = BaselineMode.MEAN_SD,
    val warmupSamples: Int = 14,
    val zClamp: Double = 3.0,
) {
    init {
        require(windowDays in 14..90) { "baseline window must be 14..90 days" }
    }
}

/** Signal layer: one definition per nightly metric. */
@Serializable
data class SignalConfig(
    /** HRV is ln(median) of the night's records only with at least this many. */
    val hrvMinRecords: Int = 3,
    /** HeartRateVariabilityRmssdRecord's valid range. Anything outside is dropped. */
    val hrvMinMs: Double = 1.0,
    val hrvMaxMs: Double = 200.0,
    /** Quality penalty when HRV came from too few records (vendor fallback). */
    val fewRecordsPenalty: Double = 0.5,
    /** Quality penalty when some HRV records fell outside the valid range. */
    val outOfRangePenalty: Double = 0.75,
    /** Sleeping HR needs one sample every this many minutes... */
    val hrBinMinutes: Int = 2,
    /** ...across at least this share of the night. Below it, the vendor's resting HR is used. */
    val hrDenseShare: Double = 0.8,
    val hrWindowMinutes: Int = 30,
    /** Coverage for night quality is measured in coarser bins than density. */
    val coverageBinMinutes: Int = 10,
    /** Quality coverage when the night has no heart-rate series at all. */
    val unknownCoverage: Double = 0.5,
    val minQuality: Double = 0.05,
    /** Breathing rate and SpO2 need this many records in the night. */
    val minRecordsOvernight: Int = 3,
)

/** Body State engine: a Kalman filter per signal, then one combined z. */
@Serializable
data class BodyConfig(
    /** sigma_w^2 / sigma_v^2. 0.25 gives a steady gain of about 0.39. */
    val processToNoise: Double = 0.25,
    /** Night-to-night noise used until a user has enough nights of their own. */
    val defaultNoiseLnHrv: Double = 0.15,
    val defaultNoiseSleepingHr: Double = 2.5,
    val minNightsForNoise: Int = 5,
    /** A night further than this many spreads from the estimate counts at half weight. */
    val outlierSpreads: Double = 3.0,
    /** Baseline: median of the filtered state over this many days... */
    val baselineDays: Int = 60,
    /** ...ending this many days ago, so today never grades itself. */
    val baselineLagDays: Int = 7,
    /** No score with fewer valid nights than this. */
    val minValidNights: Int = 7,
    /** HRV's share of the combined signal; sleeping HR has the rest. */
    val hrvWeight: Double = 0.6,
    /** The HRV-vs-sleeping-HR correlation is pulled toward this until there are nights. */
    val rhoPrior: Double = 0.5,
    val rhoPriorNights: Double = 30.0,
    /** Range shown with every score: +/- this many SDs of the filter, a 50% interval. */
    val rangeZ: Double = 0.674,
    val highConfidenceSd: Double = 0.7,
    val mediumConfidenceSd: Double = 1.0,
    /** A Body score needs a valid night at most this many days old. */
    val maxNightAgeDays: Int = 2,
    val zClamp: Double = 3.0,
    /** A weekly reading needs this many valid nights in seven (Plews 2014). */
    val weeklyMinNights: Int = 3,
    /** CV7 counts as higher or lower than usual beyond this fraction of the usual CV7. */
    val cvBandRatio: Double = 0.25,
    /** A reason is mentioned when its signal sits this many spreads from normal. */
    val reasonZ: Double = 0.5,
)

/** Sleep: debt in hours, regularity, an optional score. */
@Serializable
data class SleepConfig(
    val defaultNeedHours: Double = 8.0,
    val minNeedHours: Double = 7.0,
    val maxNeedHours: Double = 9.0,
    /** Need is learned from free nights only once there are this many. */
    val minFreeNights: Int = 10,
    val needWindowDays: Int = 60,
    val debtNights: Int = 14,
    val debtDecay: Double = 0.85,
    /** One long lie-in repays at most this much. */
    val maxRepayHours: Double = 1.0,
    val sriDays: Int = 14,
    val sriMinNights: Int = 7,
    val scoreDurationWeight: Double = 0.45,
    val scoreRegularityWeight: Double = 0.35,
    val scoreContinuityWeight: Double = 0.20,
    val efficiencyTargetPercent: Double = 90.0,
    val wasoLimitMinutes: Double = 60.0,
    /** Debt above this for [doctorDays] days in a row suggests talking to a doctor. */
    val doctorDebtHours: Double = 5.0,
    val doctorDays: Int = 14,
)

/** The two-process energy forecast and caffeine. */
@Serializable
data class EnergyConfig(
    /** Textbook time constants of the two-process model. */
    val wakeTauHours: Double = 18.2,
    val sleepTauHours: Double = 4.2,
    val circadianAmplitude: Double = 0.25,
    /**
     * A 12-hour harmonic at this share of the circadian amplitude, after the
     * Akerstedt-Folkard three-process model's ultradian term (0.5 / 2.5). Without
     * it a single cosine has no afternoon dip to label.
     */
    val ultradianShare: Double = 0.2,
    /** The clock's peak sits this long before habitual bedtime. */
    val peakBeforeBedtimeHours: Double = 2.0,
    /** The 12-hour trough sits this long before the clock's peak. */
    val dipBeforePeakHours: Double = 6.0,
    /** How far each hour of sleep debt lowers the whole curve. */
    val kappa: Double = 0.02,
    val caffeineHalfLifeHours: Double = 5.0,
    val windDownBeforeBedMinutes: Int = 60,
    /** Sleep inertia: no focus window this soon after waking. */
    val inertiaMinutes: Int = 60,
    val focusWindowMinutes: Int = 120,
    /** Time-zone changes move the clock anchor by at most this much a day. */
    val maxShiftMinutesPerDay: Int = 60,
    val habitNights: Int = 14,
    val stepMinutes: Int = 15,
)

/** Load: per-session TRIMP, fitness, fatigue, form and today's range. */
@Serializable
data class LoadConfig(
    /** Banister 1991. Published; do not tune. */
    val banisterMaleA: Double = 0.64,
    val banisterMaleB: Double = 1.92,
    val banisterFemaleA: Double = 0.86,
    val banisterFemaleB: Double = 1.67,
    /** Tanaka 2001, used only without a measured HRmax. */
    val tanakaIntercept: Double = 208.0,
    val tanakaSlope: Double = 0.7,
    /** Edwards five-zone multipliers by %HRmax band. Published. */
    val edwardsZoneWeights: Map<Int, Double> = mapOf(1 to 1.0, 2 to 2.0, 3 to 3.0, 4 to 4.0, 5 to 5.0),
    val minSessionMinutes: Double = 3.0,
    /** Cardio counts only above this share of heart-rate reserve. */
    val minReserveFraction: Double = 0.30,
    /** Session RPE assumed for a session with no usable heart rate. */
    val defaultRpe: Double = 5.0,
    /** TRIMP per RPE-minute before a user has cardio sessions to calibrate against. */
    val defaultLoadPerRpeMinute: Double = 0.167,
    val fitnessDays: Double = 42.0,
    val fatigueDays: Double = 7.0,
    val seedDays: Int = 14,
    /** Form above this reads as fresh... */
    val freshAbove: Double = -0.10,
    /** ...below this as heavy. Never labelled as injury risk. */
    val heavyBelow: Double = -0.30,
    /** Typical training day: median of non-zero days over this window. */
    val typicalWindowDays: Int = 42,
    val rangeLow: Double = 0.75,
    val rangeHigh: Double = 1.25,
    /** Yesterday's load at this multiple of typical is called a hard day. */
    val hardDayMultiple: Double = 1.5,
)

/** Context: illness pattern, menstrual cycle, intake. */
@Serializable
data class ContextConfig(
    val cusumSlack: Double = 0.5,
    val watchAbove: Double = 3.0,
    val alertAbove: Double = 5.0,
    val tempRiseC: Double = 0.3,
    val breathingRise: Double = 1.0,
    /** Alcohol or a travel/stress tag this many nights back holds an alert back. */
    val holdBackNights: Int = 3,
    /** WHO standard drink. */
    val gramsPerDrink: Double = 10.0,
    val lateMealHours: Double = 3.0,
    /** Alcohol counts toward a night when drunk within this many hours before bedtime. */
    val eveningHours: Double = 18.0,
    val cycleMinCycles: Int = 2,
    val cycleMinPhaseValues: Int = 5,
    val lutealDays: Int = 14,
    val defaultCycleDays: Int = 28,
    /** A period day with none in this many days before it starts a new cycle. */
    val periodGapDays: Int = 10,
    /** Sustained temperature rise: [tempRiseRun] days above the max of the previous [tempRiseLookback]. */
    val tempRiseLookback: Int = 6,
    val tempRiseRun: Int = 3,
)

/** Per-user learning: dose-response insights and check-in calibration. */
@Serializable
data class LearningConfig(
    val doseWindowDays: Int = 120,
    val ridgeLambda: Double = 5.0,
    val minExposureDays: Int = 8,
    val minRows: Int = 20,
    /** 90% interval. */
    val intervalZ: Double = 1.645,
    val calibrationMinCheckIns: Int = 30,
    val priorCheckIns: Double = 30.0,
    val wMin: Double = 0.4,
    val wMax: Double = 0.8,
    /** Energy-curve units per check-in point, for refitting kappa. */
    val curveUnitsPerCheckInPoint: Double = 0.1,
)

@Serializable
data class ScoringConfig(
    val baseline: BaselineConfig = BaselineConfig(),
    val signal: SignalConfig = SignalConfig(),
    val body: BodyConfig = BodyConfig(),
    val sleep: SleepConfig = SleepConfig(),
    val energy: EnergyConfig = EnergyConfig(),
    val load: LoadConfig = LoadConfig(),
    val context: ContextConfig = ContextConfig(),
    val learning: LearningConfig = LearningConfig(),
) {
    companion object {
        /** Bumped whenever engine *structure* changes, independent of default edits. */
        const val SCORING_VERSION: String = "2.0.0"

        private val json = Json { prettyPrint = false; encodeDefaults = true }

        val DEFAULT: ScoringConfig = ScoringConfig()

        fun toJson(config: ScoringConfig): String = json.encodeToString(config)
        fun fromJson(text: String): ScoringConfig = json.decodeFromString(text)
    }

    /** FNV-1a 64 of the serialised config: no crypto dependency in a pure module. */
    val configHash: String by lazy {
        var h = -0x340d631b7bdddcdbL
        for (b in toJson(this).encodeToByteArray()) {
            h = h xor (b.toLong() and 0xff)
            h *= 0x100000001b3L
        }
        h.toULong().toString(16).padStart(16, '0')
    }
}
