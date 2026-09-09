package app.pawse.scoring.sleep

import app.pawse.scoring.config.SleepTargets
import kotlin.math.abs
import kotlin.math.pow

/**
 * Sleep contributor sub-scores, each 0-100.
 *
 * Both shipping profiles combine sub-scores, not raw minutes: Oura's recovered
 * table is "each term already a 0-100 subscore" (grok.txt §11) and Apple's
 * 50/30/20 is a point allocation over three buckets. So the shape of these
 * functions is the shape the reports describe; the knee positions are in
 * [SleepTargets] and every one of them is cited there.
 *
 * These are pure functions over doubles on purpose. They are the only part of the
 * sleep engine with no baselines, no config lookup chain and no nullability, which
 * makes them the part worth testing exhaustively.
 */
object SleepSubScores {

    /**
     * Duration against tonight's need. Nonlinear and accelerating: the first hour
     * lost is cheap, the third is not.
     */
    fun duration(asleepMinutes: Double, needMinutes: Double, t: SleepTargets): Double {
        if (needMinutes <= 0.0) return 0.0
        val ratio = asleepMinutes / needMinutes
        if (ratio >= t.durationFullCreditRatio) return 100.0
        if (ratio <= t.durationZeroRatio) return 0.0
        val span = t.durationFullCreditRatio - t.durationZeroRatio
        val shortfall = (t.durationFullCreditRatio - ratio) / span
        return 100.0 * (1.0 - shortfall.pow(t.durationExponent))
    }

    /**
     * Efficiency, asleep over time in bed, as a percent.
     * Two segments meeting at Oura's stated 85% "peaceful" mark.
     */
    fun efficiency(percent: Double, t: SleepTargets): Double = when {
        percent >= t.efficiencyFullCredit -> 100.0
        percent >= t.efficiencyKnee ->
            lerp(percent, t.efficiencyKnee, t.efficiencyFullCredit, t.efficiencyKneeScore, 100.0)
        percent >= t.efficiencyZero ->
            lerp(percent, t.efficiencyZero, t.efficiencyKnee, 0.0, t.efficiencyKneeScore)
        else -> 0.0
    }

    /** REM as a percent of total sleep time. Full credit inside the target band. */
    fun remShare(percentOfTst: Double, t: SleepTargets): Double =
        band(percentOfTst, t.remTargetLowPct, t.remTargetHighPct, t.remZeroLowPct, t.remZeroHighPct)

    /** Deep (slow-wave) as a percent of total sleep time. */
    fun deepShare(percentOfTst: Double, t: SleepTargets): Double =
        band(percentOfTst, t.deepTargetLowPct, t.deepTargetHighPct, t.deepZeroLowPct, t.deepZeroHighPct)

    /**
     * Latency in minutes. Both tails cost points: too long is trouble falling
     * asleep, near-instant is sleep pressure.
     */
    fun latency(minutes: Double, t: SleepTargets): Double = when {
        minutes in t.latencyIdealLowMinutes..t.latencyIdealHighMinutes -> 100.0
        minutes < t.latencyIdealLowMinutes ->
            lerp(minutes, 0.0, t.latencyIdealLowMinutes, t.latencyInstantScore, 100.0)
        minutes >= t.latencyZeroMinutes -> 0.0
        else -> lerp(minutes, t.latencyIdealHighMinutes, t.latencyZeroMinutes, 100.0, 0.0)
    }

    /**
     * Restfulness from wake-after-sleep-onset and awakening count.
     *
     * Two components rather than one because they fail differently: forty minutes
     * awake in a single block and forty one-minute stirs are not the same night,
     * and a WASO-only term cannot tell them apart.
     */
    fun restfulness(wasoMinutes: Double, awakenings: Int, t: SleepTargets): Double {
        val wasoPenalty = clamp01(wasoMinutes / t.restfulnessWasoZeroMinutes)
        val wakePenalty = clamp01(awakenings.toDouble() / t.restfulnessAwakeningsZero)
        val share = t.restfulnessWasoShare
        return 100.0 * (1.0 - (share * wasoPenalty + (1.0 - share) * wakePenalty))
    }

    /**
     * Circadian placement: how far tonight's sleep midpoint drifted from the
     * user's own habitual midpoint. Not from any clock-time ideal — a night shift
     * worker who sleeps at the same wrong-looking hour every day is consistent,
     * and this app has no business telling them otherwise.
     */
    fun timing(midpointDriftMinutes: Double, t: SleepTargets): Double =
        100.0 * (1.0 - clamp01(abs(midpointDriftMinutes) / t.timingZeroDriftMinutes))

    /**
     * Apple's bedtime-consistency bucket. Input is the standard deviation of
     * bedtime over the trailing window (Apple uses about two weeks of context,
     * grok.txt §4).
     */
    fun bedtimeConsistency(bedtimeSdMinutes: Double, t: SleepTargets): Double =
        100.0 * (1.0 - clamp01(bedtimeSdMinutes / t.consistencyZeroSdMinutes))

    /** Apple's interruptions bucket. Memorable wake periods, not micro-arousals. */
    fun interruptions(count: Int, t: SleepTargets): Double =
        (100.0 - t.interruptionPenaltyPoints * count.coerceAtLeast(0)).coerceAtLeast(0.0)

    /**
     * Nocturnal heart-rate dip, as a percent drop from daytime resting HR to
     * sleeping HR. Bevel's distinctive contributor; a steep dip is parasympathetic
     * dominance and efficient cardiovascular recovery (gemini.txt, Fitbit
     * "Restoration" bucket, which uses the same signal).
     */
    fun heartRateDip(percentDrop: Double, t: SleepTargets): Double =
        100.0 * clamp01((percentDrop - t.hrDipZeroPct) / (t.hrDipFullCreditPct - t.hrDipZeroPct))

    private fun band(x: Double, lo: Double, hi: Double, zeroLo: Double, zeroHi: Double): Double = when {
        x in lo..hi -> 100.0
        x < lo -> 100.0 * clamp01((x - zeroLo) / (lo - zeroLo))
        else -> 100.0 * clamp01((zeroHi - x) / (zeroHi - hi))
    }

    private fun lerp(x: Double, x0: Double, x1: Double, y0: Double, y1: Double): Double =
        if (x1 == x0) y1 else y0 + (y1 - y0) * ((x - x0) / (x1 - x0))

    private fun clamp01(v: Double): Double = v.coerceIn(0.0, 1.0)
}
