package app.pawse.scoring.sleep

import app.pawse.scoring.config.SleepConfig
import app.pawse.scoring.model.Provenance
import kotlin.math.exp

/**
 * Tonight's sleep need, decomposed.
 *
 * Every field is kept rather than just the total, because the honest answer to
 * "why is my target 9 h 10 tonight?" is a sum, and the UI shows the sum.
 */
data class SleepNeed(
    val baselineHours: Double,
    val strainAdderHours: Double,
    val debtAdderHours: Double,
    val napCreditHours: Double,
    val totalHours: Double,
) {
    val totalMinutes: Double get() = totalHours * 60.0
}

/** What the caller knows about the day that produced tonight. */
data class SleepContext(
    /**
     * The user's learned rest-day need. Null falls back to
     * [SleepConfig.baselineNeedHours], which is a starting point, not a target.
     */
    val personalBaselineNeedHours: Double? = null,
    /**
     * Yesterday's strain, on the 0-21 Whoop scale, because the published logistic
     * is defined on that scale. Callers displaying Bevel's 0-100 scale convert
     * before calling; the engine will not guess which scale a bare number is on.
     */
    val strainWhoop21: Double = 0.0,
    /** Accumulated sleep debt in hours, non-negative. */
    val sleepDebtHours: Double = 0.0,
)

/**
 * Whoop Sleep Need, patent US 9,538,923.
 *
 *   SleepNeed = Baseline + f1(strain) + f2(debt) - Naps
 *   f1(i) = 1.7 / (1 + e^((17 - i) / 3.5))     hours
 *
 * The logistic is [Provenance.PUBLISHED] and is not tuned. What surrounds it is
 * not: the patent caps debt carryover without saying at what, and learns the
 * baseline from physiology without publishing the model. Those two are ours and
 * are labelled as such.
 *
 * The point of the whole thing, per every report: sleep need is never a static
 * eight hours. A day at strain 18 asks for roughly 1.4 h more than a rest day.
 */
object SleepNeedCalculator {

    fun compute(context: SleepContext, napMinutes: Int, config: SleepConfig): SleepNeed {
        val baseline = context.personalBaselineNeedHours ?: config.baselineNeedHours

        // PUBLISHED: f1(i) = 1.7 / (1 + e^((17 - i)/3.5))
        val strainAdder = config.strainAdderMaxHours /
            (1.0 + exp((config.strainAdderMidpoint - context.strainWhoop21) / config.strainAdderSteepness))

        // OUR_CHOICE: repay a fraction of the debt tonight, capped.
        val debtAdder = (context.sleepDebtHours.coerceAtLeast(0.0) * config.debtCarryoverFraction)
            .coerceAtMost(config.debtCarryoverCapHours)

        val napCredit = (napMinutes.coerceAtLeast(0) / 60.0)

        val total = (baseline + strainAdder + debtAdder - napCredit)
            .coerceAtLeast(config.minimumNeedHours)

        return SleepNeed(
            baselineHours = baseline,
            strainAdderHours = strainAdder,
            debtAdderHours = debtAdder,
            napCreditHours = napCredit,
            totalHours = total,
        )
    }

    /** Sleep Performance %, the patent's own definition: obtained / needed. */
    fun performancePercent(asleepMinutes: Double, need: SleepNeed): Double =
        if (need.totalMinutes <= 0.0) 0.0 else 100.0 * asleepMinutes / need.totalMinutes
}
