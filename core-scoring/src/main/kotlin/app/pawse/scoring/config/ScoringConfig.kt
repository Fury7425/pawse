package app.pawse.scoring.config

import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.Provenance
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The whole weight table, versioned, serialisable, hashable.
 *
 * Nothing in the engine hard-codes a coefficient. Everything the user can tune in
 * the advanced panel lives here, ships as JSON, and is hashed into every score row
 * so history stays reproducible when weights change.
 */

@Serializable
data class Weight(
    val weight: Double,
    val provenance: Provenance,
    val citation: String,
)

@Serializable
enum class RecoveryCombiner {
    /**
     * z-score -> weighted sum -> logistic squash. The standard open-clone form,
     * given identically by grok.txt §11 and gemini.txt (Bevel section):
     *   z_i = (x_i - mu_i) / sigma_i ; Z = sum(w_i * z_i) ; Score = 100 / (1 + e^(-k(Z - z0)))
     */
    LOGISTIC_Z,

    /**
     * Whoop's documented alternative: "consider the magnitude of the differences
     * between 7-day moving averages and 3-day moving averages" of the same
     * readings (patent language quoted in claude.md §2). Selectable, not default.
     */
    MOVING_AVERAGE_DELTA,
}

@Serializable
enum class SleepProfile {
    /**
     * Oura-recovered contributor weights. Best-documented sleep combiner in
     * existence: independent regression on exported contributor sub-scores
     * reconstructs the official score at R^2 ~= 0.99 (grok.txt §8).
     * Requires stages.
     */
    OURA_RECOVERED,

    /**
     * Apple's published point split, the only one any vendor states numerically:
     * duration <=50 + bedtime consistency <=30 + interruptions <=20
     * (grok.txt §4; claude.md §3). Uses no stages and no HRV — which is exactly
     * why it is the stage-less fallback.
     */
    APPLE_PUBLISHED,

    /** Bevel-style: time asleep vs need, stage balance, HR dip, efficiency, continuity. */
    BEVEL_STYLE,
}

@Serializable
enum class BaselineMode {
    /** Exponentially recency-weighted mean and SD. */
    MEAN_SD,

    /** Median and MAD-derived sigma. Robust to a single wild night or a bad seal. */
    MEDIAN_MAD,
}

@Serializable
enum class StrainScale {
    /** S_max = 100, Bevel's scale. Soft-uncapped: a brutal day can exceed 100. */
    BEVEL_100,

    /** S_max = 21, Whoop's scale. Same underlying load, different display. */
    WHOOP_21,
}

@Serializable
enum class TrimpModel {
    /** Banister 1991. Sex-specific published coefficients. */
    BANISTER,

    /** Edwards five-zone weighted sum, multipliers 1..5. */
    EDWARDS,
}

@Serializable
data class BaselineConfig(
    /**
     * 60 days matches Bevel, Athlytic and Training Today (claude.md §9).
     * Configurable 14..90 per the range the reports cover.
     */
    val windowDays: Int = 60,
    /** Recency weight half-life in days for the exponential weighting. */
    val halfLifeDays: Double = 14.0,
    val mode: BaselineMode = BaselineMode.MEAN_SD,
    /**
     * Below this many samples the metric is in warm-up and contributes nothing.
     * Reports put vendor calibration at 2-6 weeks; ~14 nights is the floor at
     * which a personal SD means anything (claude.md §9, Bevel "2-6 week calibration").
     */
    val warmupSamples: Int = 14,
    val zClamp: Double = 3.0,
) {
    init {
        require(windowDays in 14..90) { "baseline window must be 14..90 days" }
    }
}

@Serializable
data class RecoveryConfig(
    val combiner: RecoveryCombiner = RecoveryCombiner.LOGISTIC_Z,
    /**
     * Logistic steepness. OUR_CHOICE: with weights summing to 1.0 and z clamped
     * to +/-3, k = 1.1 puts a 1-SD composite drop at roughly 25 points, which
     * lands a bad night in the yellow band rather than instantly red.
     */
    val k: Double = 1.1,
    /**
     * Logistic midpoint. z0 = 0 maps "exactly at your baseline" to 50.
     * Note the alternative in grok.txt §3: some open clones shift z0 so that
     * baseline maps to ~58, matching Whoop's stated population-average recovery.
     * We do not, because a personal baseline should read as the middle of a
     * personal scale. OUR_CHOICE.
     */
    val z0: Double = 0.0,
    val weights: Map<Metric, Weight> = defaultRecoveryWeights,
    /**
     * SpO2 and temperature enter as bounded anomaly penalties, not continuous
     * terms. Reports treat both as illness/overreach flags on newer hardware
     * (grok.txt §3; claude.md §2), never as linear score drivers.
     */
    val tempAnomalySigma: Double = 1.5,
    val tempAnomalyPenalty: Double = 6.0,
    val spo2AnomalySigma: Double = 1.5,
    val spo2AnomalyPenalty: Double = 4.0,
    /** Below this coverage the score is marked degraded in the UI. */
    val degradedBelowCoverage: Float = 0.7f,
    /** Below this coverage we refuse to print a number at all. */
    val refuseBelowCoverage: Float = 0.4f,
)

/**
 * DEFAULT RECOVERY WEIGHTS — every one of these is OUR_CHOICE.
 *
 * No vendor publishes a recovery weight table. What the reports do establish is
 * a set of ranges and an ordering, and these defaults sit inside them:
 *
 *  - HRV dominant, 0.5-0.8. grok.txt §11 states w_HRV is "usually 0.5-0.8" across
 *    open clones; gemini.txt puts nocturnal RMSSD at ~56-60% of Whoop Recovery's
 *    daily variance; claude.md §2 quotes Whoop's analytics chief that Recovery is
 *    "driven primarily by HRV".
 *  - RHR next, sign-flipped. Every report lists it second.
 *  - Sleep smaller. claude.md §2: "sleep a smaller weight".
 *  - Respiratory rate small. Added later by Whoop (Podcast 84, claude.md §2).
 *  - SpO2 and temperature as anomaly flags, weight 0 in the continuous sum.
 *
 * The one open clone that publishes a full table (grok.txt §3) uses
 * 0.55/0.20/0.15/0.05/0.05. Ours is close to it and deliberately not identical —
 * presenting that table as "Whoop's" would be exactly the thing ground rule 1
 * forbids.
 */
val defaultRecoveryWeights: Map<Metric, Weight> = mapOf(
    Metric.HRV_RMSSD to Weight(
        weight = 0.60,
        provenance = Provenance.OUR_CHOICE,
        citation = "Our default. Range 0.5-0.8 supported by grok.txt §11; " +
            "gemini.txt puts HRV at ~56-60% of Whoop Recovery variance.",
    ),
    Metric.RESTING_HEART_RATE to Weight(
        weight = 0.20,
        provenance = Provenance.OUR_CHOICE,
        citation = "Our default. Second-ranked input in every report; direction " +
            "(lower is better) is published, weight is not.",
    ),
    Metric.SLEEP_SCORE to Weight(
        weight = 0.13,
        provenance = Provenance.OUR_CHOICE,
        citation = "Our default. claude.md §2: sleep carries 'a smaller weight' " +
            "than HRV and RHR in Whoop's model.",
    ),
    Metric.RESPIRATORY_RATE to Weight(
        weight = 0.07,
        provenance = Provenance.OUR_CHOICE,
        citation = "Our default. Small term. Whoop added respiratory rate later " +
            "(claude.md §2); gemini.txt notes penalties above +1.0 breaths/min.",
    ),
    // SpO2 and SKIN_TEMPERATURE carry weight 0.0 here on purpose — they are
    // applied as bounded anomaly penalties after the logistic, not as z terms.
)

@Serializable
data class SleepConfig(
    val profile: SleepProfile = SleepProfile.OURA_RECOVERED,
    /** Used automatically when the writer app supplies no sleep stages. */
    val stagelessFallback: SleepProfile = SleepProfile.APPLE_PUBLISHED,
    val ouraWeights: Map<Metric, Weight> = ouraRecoveredWeights,
    val appleWeights: Map<Metric, Weight> = applePublishedWeights,
    /**
     * Whoop Sleep Need, patent US 9,538,923 (claude.md §2):
     *   SleepNeed = Baseline + f1(strain) + f2(debt) - Naps
     *   f1(i) = 1.7 / (1 + e^((17 - i)/3.5))   hours, i = strain on the 0-21 scale
     * Never a static 8 hours.
     */
    val strainAdderMaxHours: Double = 1.7,
    val strainAdderMidpoint: Double = 17.0,
    val strainAdderSteepness: Double = 3.5,
    /** Debt carryover is capped in the patent; cap value is ours. */
    val debtCarryoverCapHours: Double = 2.0,
)

/**
 * Oura Sleep Score contributor weights. RECOVERED, not guessed.
 * Independent linear regression on exported contributor scores, plus later
 * firmware analysis, repeatedly lands on weights summing to 100 that reconstruct
 * the official score at R^2 ~= 0.99 (grok.txt §8 gives the table; claude.md §6
 * confirms the contributor list from Oura's own docs and patents US 12,165,771 /
 * US 12,309,944).
 */
val ouraRecoveredWeights: Map<Metric, Weight> = mapOf(
    Metric.TIME_ASLEEP to Weight(0.35, Provenance.RECOVERED, "Oura total sleep, 35/100 (grok.txt §8)"),
    Metric.SLEEP_EFFICIENCY to Weight(0.15, Provenance.RECOVERED, "Oura efficiency, 15/100 (grok.txt §8)"),
    Metric.REM_MINUTES to Weight(0.10, Provenance.RECOVERED, "Oura REM, 10/100 (grok.txt §8)"),
    Metric.DEEP_MINUTES to Weight(0.10, Provenance.RECOVERED, "Oura deep, 10/100 (grok.txt §8)"),
    Metric.SLEEP_LATENCY to Weight(0.10, Provenance.RECOVERED, "Oura latency, 10/100 (grok.txt §8)"),
    Metric.RESTFULNESS to Weight(0.10, Provenance.RECOVERED, "Oura restfulness, 10/100 (grok.txt §8)"),
    Metric.SLEEP_TIMING to Weight(0.10, Provenance.RECOVERED, "Oura timing, 10/100 (grok.txt §8)"),
)

/**
 * Apple Sleep Score. PUBLISHED — the only numeric point split any vendor states.
 * Duration <=50, bedtime consistency <=30, interruptions <=20 (watchOS 26).
 * Uses no HRV and no stage percentages, which is why a fragmented 7.5 h night can
 * outscore a solid-feeling 6.5 h one (grok.txt §4).
 */
val applePublishedWeights: Map<Metric, Weight> = mapOf(
    Metric.TIME_ASLEEP to Weight(0.50, Provenance.PUBLISHED, "Apple duration, 50/100 (grok.txt §4; claude.md §3)"),
    Metric.BEDTIME_CONSISTENCY to Weight(0.30, Provenance.PUBLISHED, "Apple bedtime consistency, 30/100"),
    Metric.SLEEP_INTERRUPTIONS to Weight(0.20, Provenance.PUBLISHED, "Apple interruptions, 20/100"),
)

@Serializable
data class StrainConfig(
    val scale: StrainScale = StrainScale.BEVEL_100,
    val trimpModel: TrimpModel = TrimpModel.BANISTER,
    /**
     * Banister 1991 (claude.md §10). PUBLISHED, do not tune:
     *   TRIMP = D * x * (0.64 * e^(1.92x))   men
     *   TRIMP = D * x * (0.86 * e^(1.67x))   women
     *   x = (HRex - HRrest) / (HRmax - HRrest)
     */
    val banisterMaleA: Double = 0.64,
    val banisterMaleB: Double = 1.92,
    val banisterFemaleA: Double = 0.86,
    val banisterFemaleB: Double = 1.67,
    /**
     * Saturating transform, given identically by grok.txt §11 and gemini.txt:
     *   Strain = S_max * (1 - e^(-L / k))
     * Non-additivity across sessions falls out of this curve. There is no
     * special case anywhere in the code for "two workouts in one day".
     * k is OUR_CHOICE — no vendor publishes it.
     */
    val saturationK: Double = 260.0,
    /** Tanaka 2001, used only when the user has not entered a measured HRmax. */
    val tanakaIntercept: Double = 208.0,
    val tanakaSlope: Double = 0.7,
    /** Days of typical strain feeding Target Strain. Bevel says ~2 weeks (grok.txt §7). */
    val targetStrainWindowDays: Int = 14,
)

@Serializable
data class EnergyBankConfig(
    /**
     * Edge compression: charging past this and draining below its mirror both get
     * progressively harder, matching Bevel's documented behaviour (grok.txt §7).
     */
    val softCeiling: Double = 85.0,
    val softFloor: Double = 15.0,
    val compressionExponent: Double = 1.8,
    /** Overnight recharge correlates with Recovery but is explicitly not equal to it. */
    val overnightRechargeGain: Double = 0.55,
)

@Serializable
data class LoadRatioConfig(
    /**
     * ACWR as EWMA over EWMA, not rolling averages. EWMA explains more non-contact
     * injury variance (21-52% vs 17-39%, Murray/Gabbett 2017, claude.md §10).
     */
    val acuteDays: Int = 7,
    val chronicDays: Int = 28,
    val sweetSpotLow: Double = 0.8,
    val sweetSpotHigh: Double = 1.3,
)

@Serializable
data class ScoringConfig(
    val baseline: BaselineConfig = BaselineConfig(),
    val recovery: RecoveryConfig = RecoveryConfig(),
    val sleep: SleepConfig = SleepConfig(),
    val strain: StrainConfig = StrainConfig(),
    val energyBank: EnergyBankConfig = EnergyBankConfig(),
    val loadRatio: LoadRatioConfig = LoadRatioConfig(),
) {
    companion object {
        /** Bumped whenever engine *structure* changes, independent of weight edits. */
        const val SCORING_VERSION: String = "1.0.0"

        private val json = Json { prettyPrint = false; encodeDefaults = true }

        val DEFAULT: ScoringConfig = ScoringConfig()

        fun toJson(config: ScoringConfig): String = json.encodeToString(config)
        fun fromJson(text: String): ScoringConfig = json.decodeFromString(text)
    }

    /**
     * Stable content hash of the config, persisted with every score row.
     * FNV-1a 64: no crypto dependency, and :core-scoring stays pure Kotlin.
     */
    val configHash: String by lazy {
        var h = -0x340d631b7bdddcdbL // FNV-1a 64 offset basis
        for (b in toJson(this).encodeToByteArray()) {
            h = h xor (b.toLong() and 0xff)
            h *= 0x100000001b3L
        }
        h.toULong().toString(16).padStart(16, '0')
    }
}
