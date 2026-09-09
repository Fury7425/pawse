package app.pawse.scoring.energy

import app.pawse.scoring.config.ScoringConfig
import app.pawse.scoring.model.Band
import app.pawse.scoring.model.Contribution
import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.Provenance
import app.pawse.scoring.model.Score
import app.pawse.scoring.model.ScoreType
import app.pawse.scoring.model.ScoreUnavailable
import app.pawse.scoring.model.ScoringOutcome
import kotlin.math.pow
import kotlin.math.roundToInt

/** What the Energy Bank needs to know about a day. */
data class EnergyBankInputs(
    val date: String,
    /** Yesterday's closing level. Null on the first day, which is flagged. */
    val previousLevel: Double? = null,
    /** This morning's Recovery, 1-99. */
    val recoveryScore: Int? = null,
    /** Last night's Sleep Score, 0-100. */
    val sleepScore: Int? = null,
    /** Today's Strain on the canonical 0-100 scale. */
    val dayStrain: Int? = null,
    val napMinutes: Int = 0,
)

/** The day's four phases, in the order they happened. */
data class EnergyBankTrace(
    val carryover: Double,
    /** Level after the overnight recharge. Almost always the day's high point. */
    val morningPeak: Double,
    val afterDrain: Double,
    val closing: Double,
    val rechargePoints: Double,
    val drainPoints: Double,
    val napPoints: Double,
    /** Recharge the night would have produced before edge compression bit. */
    val requestedRecharge: Double,
    val requestedDrain: Double,
)

/**
 * Energy Bank.
 *
 * The one score in the app that is a running integral rather than a snapshot.
 * Garmin's Body Battery is the reference behaviour and Bevel's Energy Bank is the
 * shape we copy: start from yesterday's leftover, add the night, spend the day,
 * and make both ends of the gauge progressively harder to reach.
 *
 * Two things the reports are explicit about, and this implements:
 *
 * **Overnight recharge is correlated with Recovery, not equal to it.** grok.txt §7
 * says so directly of Bevel, and gemini.txt has Garmin parameterising recharge by
 * both the Sleep Score and nocturnal RMSSD amplitude. So the recharge blends
 * Recovery and Sleep and passes through a gain below one. A red-recovery morning
 * still charges something, because you did sleep.
 *
 * **The extremes resist.** Charging above the soft ceiling and draining below the
 * soft floor both get progressively harder, which is why a great night after a
 * great night does not read 100, and why an already-empty gauge does not go
 * negative when you train anyway. That resistance is integrated in small steps
 * rather than applied once, because applying a large delta at its starting
 * resistance would sail straight through the ceiling it is supposed to respect.
 *
 * Ordering matters and is not cosmetic: recharge, then drain, then nap credit.
 * Applying the drain first would let a hard day pull the gauge into the floor's
 * resistance zone before the night's charge had been counted, and the morning peak
 * — the number Garmin users actually read — would vanish.
 */
class EnergyBankScorer(private val config: ScoringConfig) {

    fun score(inputs: EnergyBankInputs): ScoringOutcome {
        val bank = config.energyBank

        val rechargeShareAvailable =
            (if (inputs.recoveryScore != null) bank.recoveryShare else 0.0) +
                (if (inputs.sleepScore != null) bank.sleepShare else 0.0)
        val rechargeShareTotal = bank.recoveryShare + bank.sleepShare

        val coverage = (
            bank.coverageWeightRecharge *
                (if (rechargeShareTotal > 0.0) (rechargeShareAvailable / rechargeShareTotal).toFloat() else 0f) +
                bank.coverageWeightStrain * (if (inputs.dayStrain != null) 1f else 0f) +
                bank.coverageWeightCarryover * (if (inputs.previousLevel != null) 1f else 0f)
            ).coerceIn(0f, 1f)

        if (inputs.recoveryScore == null && inputs.sleepScore == null && inputs.previousLevel == null) {
            return notScored(
                inputs.date,
                ScoreUnavailable.Reason.NO_REQUIRED_INPUT,
                listOf(Metric.OVERNIGHT_RECHARGE, Metric.CARRYOVER),
            )
        }
        if (coverage < bank.refuseBelowCoverage) {
            return notScored(inputs.date, ScoreUnavailable.Reason.COVERAGE_TOO_LOW, missingOf(inputs))
        }

        val trace = project(inputs)
        val warmingUp = inputs.previousLevel == null

        // These points are exact, not marginal: they are the realised change in the
        // gauge at each phase, after edge resistance. Carryover plus the three
        // deltas is the closing level, and the UI can print that sum.
        val contributions = listOf(
            Contribution(
                metric = Metric.CARRYOVER,
                raw = trace.carryover,
                baselineMean = null,
                baselineSd = null,
                baselineWindowDays = 0,
                baselineSampleCount = 0,
                z = null,
                weight = 0.0,
                nominalWeight = 0.0,
                points = trace.carryover,
                provenance = if (warmingUp) Provenance.OUR_CHOICE else Provenance.INFERRED,
                citation = if (warmingUp) {
                    "Seeded at ${bank.seedLevel.roundToInt()} because there is no previous " +
                        "day yet. The gauge is a running integral, so its first value has to " +
                        "come from somewhere; after tomorrow it comes from you."
                } else {
                    "Yesterday's closing level. The gauge does not reset at midnight, which " +
                        "is what makes it a battery rather than a daily score (grok.txt §2)."
                },
                present = inputs.previousLevel != null,
            ),
            Contribution(
                metric = Metric.OVERNIGHT_RECHARGE,
                raw = trace.requestedRecharge,
                baselineMean = null,
                baselineSd = null,
                baselineWindowDays = 0,
                baselineSampleCount = 0,
                z = null,
                weight = bank.recoveryShare,
                nominalWeight = bank.recoveryShare,
                points = trace.rechargePoints,
                provenance = Provenance.OUR_CHOICE,
                citation = "Our default. Gain ${bank.overnightRechargeGain} on a blend of " +
                    "${bank.recoveryShare} Recovery and ${bank.sleepShare} Sleep. Bevel says " +
                    "the overnight charge correlates with Recovery but is not equal to it " +
                    "(grok.txt §7); a good night lands in the 40-60 point range Garmin " +
                    "documents for Body Battery (grok.txt §2).",
                present = rechargeShareAvailable > 0.0,
            ),
            Contribution(
                metric = Metric.STRAIN_DRAIN,
                raw = trace.requestedDrain,
                baselineMean = null,
                baselineSd = null,
                baselineWindowDays = 0,
                baselineSampleCount = 0,
                z = null,
                weight = bank.strainDrainGain,
                nominalWeight = bank.strainDrainGain,
                points = trace.drainPoints,
                provenance = Provenance.OUR_CHOICE,
                citation = "Our default. ${bank.strainDrainGain} points per point of day " +
                    "strain. Strain already includes the passive term, so there is no " +
                    "separate charge for being awake and nothing is counted twice.",
                present = inputs.dayStrain != null,
            ),
            Contribution(
                metric = Metric.NAP_CREDIT,
                raw = inputs.napMinutes.toDouble(),
                baselineMean = null,
                baselineSd = null,
                baselineWindowDays = 0,
                baselineSampleCount = 0,
                z = null,
                weight = 0.0,
                nominalWeight = 0.0,
                points = trace.napPoints,
                provenance = Provenance.OUR_CHOICE,
                citation = "Our default. ${bank.napRechargePerHour} points per hour. Naps " +
                    "recharge the gauge in both Bevel and Garmin; neither publishes by how much.",
                present = inputs.napMinutes > 0,
            ),
        )

        val value = trace.closing.roundToInt()
            .coerceIn(bank.displayFloor.roundToInt(), bank.displayCeiling.roundToInt())

        return ScoringOutcome.Scored(
            Score(
                type = ScoreType.ENERGY_BANK,
                date = inputs.date,
                value = value,
                // Same thresholds as Recovery: this is a 0-100 gauge where more is
                // better, and giving it its own boundaries would be a distinction
                // without a difference.
                band = Band.ofRecovery(value),
                contributions = contributions,
                dataCoverage = coverage,
                degraded = coverage < bank.degradedBelowCoverage || warmingUp,
                warmingUp = warmingUp,
                scoringVersion = ScoringConfig.SCORING_VERSION,
                configHash = config.configHash,
            ),
        )
    }

    /** The integral, exposed because the UI wants the morning peak as well as the close. */
    fun project(inputs: EnergyBankInputs): EnergyBankTrace {
        val bank = config.energyBank
        val carryover = (inputs.previousLevel ?: bank.seedLevel)
            .coerceIn(bank.displayFloor, bank.displayCeiling)

        val rechargeShareAvailable =
            (if (inputs.recoveryScore != null) bank.recoveryShare else 0.0) +
                (if (inputs.sleepScore != null) bank.sleepShare else 0.0)
        val blended = if (rechargeShareAvailable <= 0.0) {
            0.0
        } else {
            (
                bank.recoveryShare * (inputs.recoveryScore?.toDouble() ?: 0.0) +
                    bank.sleepShare * (inputs.sleepScore?.toDouble() ?: 0.0)
                ) / rechargeShareAvailable
        }
        val requestedRecharge = bank.overnightRechargeGain * blended
        val requestedDrain = bank.strainDrainGain * (inputs.dayStrain?.toDouble() ?: 0.0)
        val requestedNap = bank.napRechargePerHour * (inputs.napMinutes.coerceAtLeast(0) / 60.0)

        val morningPeak = applyDelta(carryover, requestedRecharge)
        val afterDrain = applyDelta(morningPeak, -requestedDrain)
        val closing = applyDelta(afterDrain, requestedNap)

        return EnergyBankTrace(
            carryover = carryover,
            morningPeak = morningPeak,
            afterDrain = afterDrain,
            closing = closing,
            rechargePoints = morningPeak - carryover,
            drainPoints = afterDrain - morningPeak,
            napPoints = closing - afterDrain,
            requestedRecharge = requestedRecharge,
            requestedDrain = requestedDrain,
        )
    }

    /**
     * Integrate one signed delta through the edge resistance in small steps.
     *
     * The step count is config, not a constant, because it trades fidelity against
     * nothing much: fifty steps puts the result within a fraction of a point of the
     * continuous solution and costs fifty multiplications.
     */
    private fun applyDelta(start: Double, delta: Double): Double {
        val bank = config.energyBank
        if (delta == 0.0 || bank.integrationSteps <= 0) return start
        val step = delta / bank.integrationSteps
        var level = start
        repeat(bank.integrationSteps) {
            level = (level + step * resistance(level, step))
                .coerceIn(bank.displayFloor, bank.displayCeiling)
        }
        return level
    }

    /**
     * How much of a step actually lands, 0..1. Full effect in the middle of the
     * gauge, tapering to nothing at either end.
     */
    private fun resistance(level: Double, step: Double): Double {
        val bank = config.energyBank
        return when {
            step > 0.0 && level > bank.softCeiling -> {
                val headroom = (bank.displayCeiling - level) / (bank.displayCeiling - bank.softCeiling)
                headroom.coerceIn(0.0, 1.0).pow(bank.compressionExponent)
            }

            step < 0.0 && level < bank.softFloor -> {
                val room = (level - bank.displayFloor) / (bank.softFloor - bank.displayFloor)
                room.coerceIn(0.0, 1.0).pow(bank.compressionExponent)
            }

            else -> 1.0
        }
    }

    private fun missingOf(inputs: EnergyBankInputs): List<Metric> = buildList {
        if (inputs.recoveryScore == null && inputs.sleepScore == null) add(Metric.OVERNIGHT_RECHARGE)
        if (inputs.dayStrain == null) add(Metric.STRAIN_DRAIN)
        if (inputs.previousLevel == null) add(Metric.CARRYOVER)
    }

    private fun notScored(date: String, reason: ScoreUnavailable.Reason, missing: List<Metric>) =
        ScoringOutcome.NotScored(
            ScoreUnavailable(type = ScoreType.ENERGY_BANK, date = date, reason = reason, missing = missing),
        )
}
