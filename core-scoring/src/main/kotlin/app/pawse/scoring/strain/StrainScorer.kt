package app.pawse.scoring.strain

import app.pawse.scoring.config.ScoringConfig
import app.pawse.scoring.config.StrainScale
import app.pawse.scoring.config.TrimpModel
import app.pawse.scoring.model.Band
import app.pawse.scoring.model.Contribution
import app.pawse.scoring.model.DayActivity
import app.pawse.scoring.model.LoadResult
import app.pawse.scoring.model.MaxHeartRate
import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.Provenance
import app.pawse.scoring.model.Score
import app.pawse.scoring.model.ScoreType
import app.pawse.scoring.model.ScoreUnavailable
import app.pawse.scoring.model.ScoringOutcome
import app.pawse.scoring.model.UserProfile
import app.pawse.scoring.model.WorkoutSample
import kotlin.math.roundToInt

/** One session's contribution to the day, kept for the UI rather than persisted. */
data class SessionLoad(
    val id: String,
    val title: String?,
    val durationMinutes: Double,
    /** Fraction of heart-rate reserve, when Banister was used. */
    val reserveFraction: Double?,
    val load: Double,
    /**
     * What this session alone would have scored. Always less than its share of the
     * day, because the saturation curve is concave — which is the honest way to
     * show a user why their second ride "added less".
     */
    val strainIfAlone: Double,
    val provenance: Provenance,
    val citation: String,
    val scored: Boolean,
    val skippedReason: String? = null,
)

/** Target Strain, and what moved it. */
data class StrainTarget(
    val target: Double,
    val typicalStrain: Double,
    val recoveryFactor: Double,
    val windowDays: Int,
    val sampleCount: Int,
)

/**
 * Strain.
 *
 *   L     = sum of per-session TRIMP + a flat passive term
 *   Strain = 100 x (1 - e^(-L / k))
 *
 * The load models are published and untouched; the saturation constant and the
 * passive term are ours and say so. What makes this scorer worth reading is what
 * it refuses to do:
 *
 * **It computes on one scale and displays on two.** Whoop's 0-21 and Bevel's 0-100
 * are the same curve with a different ceiling, so [Score.value] is always the
 * canonical 0-100 and [displayValue] converts. Flipping the toggle changes a label,
 * never a stored number, and never the shape of a history chart.
 *
 * **It refuses a day it could only half-see.** A two-hour ride with no heart-rate
 * data and no age on file cannot be scored, and quietly reporting the rest-day
 * strain for that day would be worse than reporting nothing. Coverage here is the
 * fraction of logged workout *minutes* that produced a load, which is the only
 * definition that makes the number mean something.
 *
 * **It has no special case for two workouts in one day.** Non-additivity is the
 * curve's job. grok.txt §3 and gemini.txt both make the point that a 10 plus a 5
 * is not a 15; that falls out of concavity, and any code that reached in to enforce
 * it would be a bug.
 */
class StrainScorer(private val config: ScoringConfig) {

    fun score(
        day: DayActivity,
        profile: UserProfile,
        /** Baseline resting heart rate, used when the profile carries none. */
        restingHeartRateBaseline: Double? = null,
    ): ScoringOutcome {
        val strain = config.strain
        val sessions = sessionLoads(day, profile, restingHeartRateBaseline)

        val totalWorkoutMinutes = day.workouts.sumOf { it.durationMinutes }
        val scoredMinutes = sessions.filter { it.scored }.sumOf { it.durationMinutes }
        val coverage = when {
            // No workouts logged: nothing was lost, so nothing is missing. A rest
            // day is fully covered, not 0% covered.
            totalWorkoutMinutes <= 0.0 -> 1.0f
            else -> (scoredMinutes / totalWorkoutMinutes).toFloat()
        }

        if (day.workouts.isEmpty() && day.wakingHours <= 0.0) {
            return notScored(day.date, ScoreUnavailable.Reason.NO_REQUIRED_INPUT, emptyList())
        }
        if (coverage < strain.refuseBelowCoverage) {
            return notScored(day.date, ScoreUnavailable.Reason.COVERAGE_TOO_LOW, listOf(Metric.WORKOUT_LOAD))
        }

        val workoutLoad = sessions.filter { it.scored }.sumOf { it.load }
        val passiveLoad = strain.passiveLoadPerWakingHour * day.wakingHours.coerceAtLeast(0.0)
        val totalLoad = workoutLoad + passiveLoad
        val value = Trimp.saturate(totalLoad, strain)

        // Marginal attribution, for the same reason Recovery uses it: a saturating
        // curve is not a sum, so "how far would the day move without this" is the
        // only decomposition that is true.
        fun marginal(without: Double) = value - Trimp.saturate(totalLoad - without, strain)

        val scored = sessions.filter { it.scored }
        // Weakest link wins. One session whose load rests on an estimated HRmax
        // downgrades the day's aggregate to INFERRED, because the aggregate is no
        // better than its shakiest term and rounding that up would be the whole
        // failure mode the provenance system exists to prevent.
        val workoutProvenance = when {
            scored.isEmpty() -> Provenance.PUBLISHED
            scored.any { it.provenance == Provenance.OUR_CHOICE } -> Provenance.OUR_CHOICE
            scored.any { it.provenance == Provenance.INFERRED } -> Provenance.INFERRED
            else -> Provenance.PUBLISHED
        }

        val contributions = listOf(
            Contribution(
                metric = Metric.WORKOUT_LOAD,
                raw = workoutLoad,
                baselineMean = null,
                baselineSd = null,
                baselineWindowDays = 0,
                baselineSampleCount = scored.size,
                z = null,
                weight = if (totalLoad > 0.0) workoutLoad / totalLoad else 0.0,
                nominalWeight = 1.0,
                points = marginal(workoutLoad),
                provenance = workoutProvenance,
                citation = scored.firstOrNull()?.citation
                    ?: "Banister 1991 TRIMP (claude.md §10). No session produced a load today.",
                present = workoutLoad > 0.0,
            ),
            Contribution(
                metric = Metric.PASSIVE_LOAD,
                raw = passiveLoad,
                baselineMean = null,
                baselineSd = null,
                baselineWindowDays = 0,
                baselineSampleCount = 0,
                z = null,
                weight = if (totalLoad > 0.0) passiveLoad / totalLoad else 0.0,
                nominalWeight = 1.0,
                points = marginal(passiveLoad),
                provenance = Provenance.OUR_CHOICE,
                citation = "Our default. ${strain.passiveLoadPerWakingHour} TRIMP per waking " +
                    "hour, standing in for Bevel's passive strain (grok.txt §7). Health " +
                    "Connect gives us no reliable all-day heart-rate series, so this is a " +
                    "flat term, not a measurement.",
                present = passiveLoad > 0.0,
            ),
        )

        val rounded = value.roundToInt().coerceIn(0, Trimp.CANONICAL_MAX.toInt())
        return ScoringOutcome.Scored(
            Score(
                type = ScoreType.STRAIN,
                date = day.date,
                value = rounded,
                band = Band.ofStrainMagnitude(rounded),
                contributions = contributions,
                dataCoverage = coverage.coerceIn(0f, 1f),
                degraded = coverage < strain.degradedBelowCoverage,
                // Strain is absolute: it needs no rolling baseline and so never warms up.
                warmingUp = false,
                scoringVersion = ScoringConfig.SCORING_VERSION,
                configHash = config.configHash,
            ),
        )
    }

    /**
     * Per-session breakdown.
     *
     * Derived on demand rather than persisted, because everything it needs is
     * already in the exercise-session table and recomputing is cheaper than
     * storing a second copy that can drift from it.
     */
    fun sessionLoads(
        day: DayActivity,
        profile: UserProfile,
        restingHeartRateBaseline: Double? = null,
    ): List<SessionLoad> {
        val strain = config.strain
        // The day's own peak can only raise an age-based estimate, never lower it.
        val observedMax = day.workouts.mapNotNull { it.maxHeartRate }.maxOrNull()
        val maxHr = HeartRateModel.maxHeartRate(profile, strain, observedMax)
        val restingHr = profile.restingHeartRate ?: restingHeartRateBaseline

        return day.workouts.map { workout ->
            sessionLoad(workout, profile, maxHr, restingHr)
        }
    }

    private fun sessionLoad(
        workout: WorkoutSample,
        profile: UserProfile,
        maxHr: MaxHeartRate?,
        restingHr: Double?,
    ): SessionLoad {
        val strain = config.strain

        fun skipped(reason: String) = SessionLoad(
            id = workout.id,
            title = workout.title,
            durationMinutes = workout.durationMinutes,
            reserveFraction = null,
            load = 0.0,
            strainIfAlone = 0.0,
            provenance = Provenance.PUBLISHED,
            citation = "",
            scored = false,
            skippedReason = reason,
        )

        if (workout.durationMinutes < strain.minSessionMinutes) {
            return skipped("Shorter than ${strain.minSessionMinutes.roundToInt()} minutes.")
        }

        // Edwards when the writer app gave us a series to bin and it is the chosen
        // model, or whenever Banister's inputs are missing but the zones are not.
        val zonesUsable = workout.zoneMinutes.isNotEmpty()
        val banisterUsable = workout.meanHeartRate != null && restingHr != null && maxHr != null
        val useEdwards = zonesUsable && (strain.trimpModel == TrimpModel.EDWARDS || !banisterUsable)

        if (useEdwards) {
            val result = Trimp.edwards(workout.zoneMinutes, strain)
            return SessionLoad(
                id = workout.id,
                title = workout.title,
                durationMinutes = workout.durationMinutes,
                reserveFraction = null,
                load = result.load,
                strainIfAlone = Trimp.saturate(result.load, strain),
                provenance = result.provenance,
                citation = result.citation,
                scored = true,
            )
        }

        if (!banisterUsable) {
            val missing = buildList {
                if (workout.meanHeartRate == null) add("average heart rate")
                if (restingHr == null) add("your resting heart rate")
                if (maxHr == null) add("your age or measured maximum heart rate")
            }
            return skipped("Needs ${missing.joinToString(" and ")}.")
        }

        val x = HeartRateModel.reserveFraction(workout.meanHeartRate!!, restingHr!!, maxHr!!.bpm)
            ?: return skipped("Your resting heart rate is not below your maximum.")

        val result: LoadResult = Trimp.banister(workout.durationMinutes, x, profile.biologicalSex, strain)
        val citation = if (maxHr.estimated) "${result.citation} ${maxHr.citation}" else result.citation
        return SessionLoad(
            id = workout.id,
            title = workout.title,
            durationMinutes = workout.durationMinutes,
            reserveFraction = x,
            load = result.load,
            strainIfAlone = Trimp.saturate(result.load, strain),
            provenance = if (maxHr.estimated) Provenance.INFERRED else result.provenance,
            citation = citation,
            scored = true,
        )
    }

    /** The day's total load, for the acute:chronic ratio. */
    fun dayLoad(
        day: DayActivity,
        profile: UserProfile,
        restingHeartRateBaseline: Double? = null,
    ): Double {
        val workout = sessionLoads(day, profile, restingHeartRateBaseline)
            .filter { it.scored }
            .sumOf { it.load }
        return workout + config.strain.passiveLoadPerWakingHour * day.wakingHours.coerceAtLeast(0.0)
    }

    /**
     * Target Strain: about two weeks of your typical strain, moved by this
     * morning's Recovery. Bevel describes exactly that pairing and publishes
     * neither half of it (grok.txt §7), so the window is theirs and the
     * Recovery coupling strength is ours.
     */
    fun target(recentStrains: List<Double>, recoveryScore: Int?): StrainTarget {
        val strain = config.strain
        val window = recentStrains.take(strain.targetStrainWindowDays)
        val typical = if (window.isEmpty()) 0.0 else window.average()
        val factor = if (recoveryScore == null) {
            1.0
        } else {
            1.0 + strain.targetStrainRecoveryAdjust * ((recoveryScore - 50.0) / 50.0)
        }
        return StrainTarget(
            target = (typical * factor).coerceIn(0.0, Trimp.CANONICAL_MAX),
            typicalStrain = typical,
            recoveryFactor = factor,
            windowDays = strain.targetStrainWindowDays,
            sampleCount = window.size,
        )
    }

    /**
     * Canonical 0-100 strain converted for display.
     *
     * Whoop's ceiling is 21 and Bevel's is 100 (grok.txt §3, §7). Same curve,
     * different label. Bevel's scale is soft-uncapped in their product; ours is
     * not, because a number above its own maximum needs an explanation the home
     * screen has no room for.
     */
    fun displayValue(canonical: Int, scale: StrainScale = config.strain.scale): Double = when (scale) {
        StrainScale.BEVEL_100 -> canonical.toDouble()
        StrainScale.WHOOP_21 -> canonical * 21.0 / Trimp.CANONICAL_MAX
    }

    private fun notScored(date: String, reason: ScoreUnavailable.Reason, missing: List<Metric>) =
        ScoringOutcome.NotScored(
            ScoreUnavailable(type = ScoreType.STRAIN, date = date, reason = reason, missing = missing),
        )
}
