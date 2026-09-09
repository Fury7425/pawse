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
    /**
     * Windows for [RecoveryCombiner.MOVING_AVERAGE_DELTA]. Whoop's patent language
     * is "the magnitude of the differences between 7-day moving averages and
     * 3-day moving averages" (claude.md §2), so these two numbers are PUBLISHED
     * even though what Whoop then does with the difference is not.
     */
    val maFastDays: Int = 3,
    val maSlowDays: Int = 7,
    /** A moving-average term with fewer real samples than this in either window is dropped. */
    val maMinFastSamples: Int = 2,
    val maMinSlowSamples: Int = 4,
    /**
     * Recovery is reported 1-99, never 0 or 100. Whoop's own scale stops at those
     * bounds (grok.txt §3, gemini.txt) and for the same reason: a logistic
     * asymptote should not be printed as certainty.
     */
    val displayFloor: Int = 1,
    val displayCeiling: Int = 99,
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
    val bevelWeights: Map<Metric, Weight> = bevelStyleWeights,
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
    /**
     * Fraction of accumulated debt repaid tonight rather than all at once.
     * OUR_CHOICE: the patent says carryover is capped but not how it is split,
     * and demanding a full 3-hour repayment in one night is not a target anyone
     * can hit, which would peg Sleep Performance at "failed" for a week.
     */
    val debtCarryoverFraction: Double = 0.5,
    /**
     * Personal rest-day need before any adder. The patent learns this from
     * physiology, age, sex, height and weight; we start every user at the middle
     * of the AASM 7-9 h adult range and let the adders move it nightly. This is
     * the *starting* baseline, not a fixed 8-hour target: the moment a user has
     * history, the caller passes their own learned baseline instead.
     */
    val baselineNeedHours: Double = 8.0,
    /** Floor after nap credit, so a long nap cannot drive tonight's need to zero. */
    val minimumNeedHours: Double = 4.0,
    /** Below this contributor coverage the sleep score is marked degraded. */
    val degradedBelowCoverage: Float = 0.8f,
    /**
     * Below this we refuse. Set so that duration alone is never enough: 0.50 under
     * Apple's split and 0.35 under Oura's both fall through, and a single-number
     * "sleep score" built only on hours is precisely the thing this app exists to
     * not be.
     */
    val refuseBelowCoverage: Float = 0.55f,
    val targets: SleepTargets = SleepTargets(),
)

/**
 * Absolute targets for the sleep sub-scores.
 *
 * Both shipping profiles are *absolute-target* combiners, not baseline-relative
 * ones: Oura and Apple both score a night against a physiological target rather
 * than against your own last sixty nights. So these are the only numbers in the
 * engine that are not z-scores, and every one of them is written down here rather
 * than inlined in a scorer.
 *
 * Provenance is mixed and is called out per field. Where a report states a range,
 * the range is the field; where no report states anything, the field is ours.
 */
@Serializable
data class SleepTargets(
    // --- Duration vs need -------------------------------------------------
    /** Ratio of asleep-to-need that earns full duration credit. */
    val durationFullCreditRatio: Double = 1.0,
    /** Ratio at which duration credit reaches zero. */
    val durationZeroRatio: Double = 0.5,
    /**
     * Exponent on the normalised shortfall, so the penalty accelerates.
     * OUR_CHOICE, tuned to Apple's described shape: "~2 hours short is about
     * -13 of 50 points; one hour short is milder; another lost hour costs more"
     * (grok.txt §4). At exponent 2.0 and an 8 h need, 7 h scores 94 and 6 h
     * scores 75 — the same accelerating shape.
     */
    val durationExponent: Double = 2.0,

    // --- Efficiency (asleep / time in bed) --------------------------------
    val efficiencyFullCredit: Double = 95.0,
    /** Oura's stated "peaceful" mark (grok.txt §8). */
    val efficiencyKnee: Double = 85.0,
    val efficiencyKneeScore: Double = 80.0,
    val efficiencyZero: Double = 65.0,

    // --- Stage shares, percent of total sleep time ------------------------
    /** REM 20-25% of TST, gemini.txt "Sleep Quality / Stage Composition". */
    val remTargetLowPct: Double = 20.0,
    val remTargetHighPct: Double = 25.0,
    /** Oura allows a very wide 5-50% before zeroing the contributor (grok.txt §8). */
    val remZeroLowPct: Double = 5.0,
    val remZeroHighPct: Double = 50.0,
    /** Deep 13-23% of TST, gemini.txt; grok.txt §8 gives 15-20% for adults. */
    val deepTargetLowPct: Double = 13.0,
    val deepTargetHighPct: Double = 23.0,
    val deepZeroLowPct: Double = 2.0,
    val deepZeroHighPct: Double = 40.0,

    // --- Latency ----------------------------------------------------------
    /** Oura: latency above 20 minutes hurts (grok.txt §8). */
    val latencyIdealLowMinutes: Double = 8.0,
    val latencyIdealHighMinutes: Double = 20.0,
    val latencyZeroMinutes: Double = 60.0,
    /**
     * Falling asleep instantly is a sleep-pressure signal, not a triumph, so a
     * zero-minute latency scores below full credit rather than at it. OUR_CHOICE.
     */
    val latencyInstantScore: Double = 70.0,

    // --- Restfulness ------------------------------------------------------
    val restfulnessWasoZeroMinutes: Double = 90.0,
    val restfulnessAwakeningsZero: Double = 8.0,
    val restfulnessWasoShare: Double = 0.6,

    // --- Timing and consistency ------------------------------------------
    /** Sleep-midpoint drift from your own habitual midpoint that zeroes timing. */
    val timingZeroDriftMinutes: Double = 180.0,
    /** Bedtime SD over the trailing window that zeroes Apple's consistency term. */
    val consistencyZeroSdMinutes: Double = 120.0,

    // --- Apple interruptions ---------------------------------------------
    /**
     * Apple's 20-point bucket counts memorable wake periods, not micro-arousals
     * (grok.txt §4). Points per interruption is ours.
     */
    val interruptionPenaltyPoints: Double = 15.0,

    // --- Bevel heart-rate dip --------------------------------------------
    /** Percent drop from daytime resting HR to sleeping HR earning full credit. */
    val hrDipFullCreditPct: Double = 15.0,
    val hrDipZeroPct: Double = 0.0,
)

/**
 * Bevel-style sleep weights. OUR_CHOICE throughout.
 *
 * Bevel names its contributors — time asleep vs goal, stage balance, heart-rate
 * dip, efficiency, continuity (grok.txt §7; claude.md §9) — and publishes no
 * weights at all. So this profile exists because the heart-rate dip is a real
 * signal the other two profiles throw away, not because anyone reproduced Bevel.
 */
val bevelStyleWeights: Map<Metric, Weight> = mapOf(
    Metric.TIME_ASLEEP to Weight(0.35, Provenance.OUR_CHOICE, "Our default. Bevel lists time asleep vs goal first (grok.txt §7)."),
    Metric.SLEEP_EFFICIENCY to Weight(0.15, Provenance.OUR_CHOICE, "Our default. Bevel lists efficiency as a contributor; weight unpublished."),
    Metric.HEART_RATE_DIP to Weight(0.15, Provenance.OUR_CHOICE, "Our default. The dip from daytime resting HR to sleeping HR, Bevel's distinctive term."),
    Metric.RESTFULNESS to Weight(0.15, Provenance.OUR_CHOICE, "Our default. Stands in for Bevel's 'continuity'."),
    Metric.REM_MINUTES to Weight(0.10, Provenance.OUR_CHOICE, "Our default. Half of Bevel's 'stage balance'."),
    Metric.DEEP_MINUTES to Weight(0.10, Provenance.OUR_CHOICE, "Our default. Half of Bevel's 'stage balance'."),
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
    /**
     * Edwards five-zone multipliers, by %HRmax band: 50-60, 60-70, 70-80, 80-90,
     * 90-100. PUBLISHED (Edwards, claude.md §10). Used when the writer app
     * supplied a heart-rate series we could bin, and as the sex-neutral fallback.
     */
    val edwardsZoneWeights: Map<Int, Double> = mapOf(
        1 to 1.0, 2 to 2.0, 3 to 3.0, 4 to 4.0, 5 to 5.0,
    ),
    /**
     * Passive load per waking hour, in TRIMP-equivalent units.
     *
     * Bevel splits its Strain into active and passive halves, the passive half
     * being "movement/HR the rest of the day" (grok.txt §7), and Garmin's Body
     * Battery drains all day at rest. We have no reliable all-day heart-rate
     * series through Health Connect, so this is a flat per-hour term rather than
     * a measurement. OUR_CHOICE. Set to 0.0 to score workouts only, which is what
     * a user who wants a pure training-load number should do.
     */
    val passiveLoadPerWakingHour: Double = 3.0,
    /**
     * Sessions shorter than this are dropped. A 40-second "workout" is a
     * mis-tap or an auto-detection artefact, and Banister's duration term makes
     * it worthless anyway.
     */
    val minSessionMinutes: Double = 3.0,
    /**
     * How far Recovery moves Target Strain. Bevel derives its target from about
     * two weeks of typical strain "plus recent Recovery" (grok.txt §7) without
     * saying how much. At 0.35, a green 90 lifts the target by 28% and a red 15
     * cuts it by a quarter. OUR_CHOICE.
     */
    val targetStrainRecoveryAdjust: Double = 0.35,
    /** Below this fraction of workout minutes scored, strain is marked degraded. */
    val degradedBelowCoverage: Float = 0.9f,
    /**
     * Below this we refuse. Printing a rest-day strain for a day that held a
     * two-hour ride we could not score is worse than printing nothing.
     */
    val refuseBelowCoverage: Float = 0.5f,
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
    /**
     * How the overnight recharge is blended before the gain is applied. Bevel says
     * the overnight charge is "correlated with, but not 1:1 with, Recovery"
     * (grok.txt §7) and Garmin parameterises recharge by both the overnight Sleep
     * Score and nocturnal RMSSD amplitude (gemini.txt). So both go in, Recovery
     * leading. Shares are OUR_CHOICE and renormalise when one is missing.
     *
     * Sanity anchor: at these values a good night adds roughly 40 points and a
     * great one just over 50, which is the 40-60 range Garmin's own Body Battery
     * documentation describes (grok.txt §2).
     */
    val recoveryShare: Double = 0.6,
    val sleepShare: Double = 0.4,
    /**
     * Points drained per unit of day strain on the canonical 0-100 strain scale.
     * At 0.85, an all-out day costs 85 points before edge compression. OUR_CHOICE.
     */
    val strainDrainGain: Double = 0.85,
    /** Points added per hour of nap. Naps recharge in Bevel and Garmin both. */
    val napRechargePerHour: Double = 6.0,
    /**
     * The gauge is integrated in steps rather than applied as one jump, so that
     * edge resistance is felt continuously. Without this a single large delta
     * would be scaled by the resistance at its starting level and overshoot.
     *
     * Forward Euler, so the error falls roughly as one over the step count. Two
     * hundred steps sits within a point of the continuous solution and costs two
     * hundred multiplications once a day.
     */
    val integrationSteps: Int = 200,
    /**
     * Garmin's Body Battery floors at 5, not 0 (grok.txt §2), and for a good
     * reason: a gauge that reads empty implies a state the sensor cannot confirm.
     */
    val displayFloor: Double = 5.0,
    val displayCeiling: Double = 100.0,
    /** Day-one seed, before any carryover exists. Flagged as warming up. */
    val seedLevel: Double = 50.0,
    /**
     * Coverage weights across the three input groups. Transparent rather than
     * principled: they exist so the number next to the gauge means something
     * specific. OUR_CHOICE.
     */
    val coverageWeightRecharge: Float = 0.5f,
    val coverageWeightStrain: Float = 0.3f,
    val coverageWeightCarryover: Float = 0.2f,
    /**
     * Deliberately not 0.7. A day with the night but no strain scores exactly
     * 0.5 + 0.2, and a threshold sitting on that sum would decide whether the
     * gauge reads degraded by float rounding. It also happens to be the right
     * answer: a gauge that charged but never watched the day being spent is
     * degraded, whatever the arithmetic says.
     */
    val degradedBelowCoverage: Float = 0.75f,
    val refuseBelowCoverage: Float = 0.3f,
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
    /**
     * Gabbett's elevated-risk threshold. Reported, not endorsed: see the critique
     * shipped alongside the number in [app.pawse.scoring.load.LoadRatioScorer].
     */
    val elevatedAbove: Double = 1.5,
    /**
     * Fewer distinct days than this and there is no chronic load to divide by,
     * so there is no ratio. A 28-day denominator built from nine days is not a
     * conservative estimate, it is a different number wearing the same name.
     */
    val minimumChronicDays: Int = 14,
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
