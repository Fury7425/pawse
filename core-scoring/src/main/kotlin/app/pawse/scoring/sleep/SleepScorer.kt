package app.pawse.scoring.sleep

import app.pawse.scoring.config.ScoringConfig
import app.pawse.scoring.config.SleepProfile
import app.pawse.scoring.config.Weight
import app.pawse.scoring.model.Band
import app.pawse.scoring.model.Contribution
import app.pawse.scoring.model.DailyInputs
import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.Score
import app.pawse.scoring.model.ScoreType
import app.pawse.scoring.model.ScoreUnavailable
import app.pawse.scoring.model.ScoringOutcome
import kotlin.math.roundToInt

/**
 * Sleep Score.
 *
 * Three profiles, selected in config, with one automatic override: a night with
 * no stage data cannot be scored by a stage-weighted combiner, so it falls back
 * to [SleepProfile.APPLE_PUBLISHED], which uses no stages by design. That is not
 * a workaround — it is the reason Apple's split is in the app at all. grok.txt §4
 * makes the point that Apple's score deliberately ignores stages and HRV, which
 * is exactly what you want from a writer app that only reports asleep/awake.
 *
 * Unlike Recovery, this is an absolute-target combiner: contributors are scored
 * against physiological targets, not against the user's own sixty nights. Both
 * Oura and Apple work that way, and mixing the two philosophies inside one number
 * would make the explainability screen incoherent.
 *
 * Points here are exact. Sleep is a weighted sum of 0-100 sub-scores, so
 * `points = weight x subScore` really does add up to the final value, and the
 * explainability screen can say so without a footnote. Recovery cannot make that
 * claim, and does not.
 */
class SleepScorer(private val config: ScoringConfig) {

    fun score(inputs: DailyInputs, context: SleepContext = SleepContext()): ScoringOutcome {
        if (!inputs.hasSleepSession) {
            return notScored(inputs, ScoreUnavailable.Reason.NO_SLEEP_SESSION, emptyList())
        }
        val asleep = inputs[Metric.TIME_ASLEEP]
        if (asleep == null || asleep <= 0.0) {
            return notScored(inputs, ScoreUnavailable.Reason.NO_REQUIRED_INPUT, listOf(Metric.TIME_ASLEEP))
        }

        val sleep = config.sleep
        val profile = resolveProfile(inputs)
        val weights = weightsFor(profile)
        val need = SleepNeedCalculator.compute(context, inputs.napMinutes, sleep)

        val nominalTotal = weights.values.sumOf { it.weight }
        val evaluated = weights.mapValues { (metric, _) -> subScore(metric, inputs, asleep, need) }
        val presentTotal = weights.entries
            .filter { evaluated[it.key] != null }
            .sumOf { it.value.weight }

        val coverage = if (nominalTotal <= 0.0) 0f else (presentTotal / nominalTotal).toFloat()
        if (coverage < sleep.refuseBelowCoverage) {
            return notScored(
                inputs,
                ScoreUnavailable.Reason.COVERAGE_TOO_LOW,
                weights.keys.filter { evaluated[it] == null },
            )
        }

        val contributions = weights.map { (metric, weight) ->
            val sub = evaluated[metric]
            val effective = if (sub == null || presentTotal <= 0.0) 0.0 else weight.weight / presentTotal
            Contribution(
                metric = metric,
                raw = inputs[metric],
                // An absolute-target combiner has no baseline to report. Leaving
                // these null is the honest answer; filling them with the target
                // would read as "your usual" and be a lie.
                baselineMean = null,
                baselineSd = null,
                baselineWindowDays = 0,
                baselineSampleCount = 0,
                z = null,
                weight = effective,
                nominalWeight = weight.weight,
                points = if (sub == null) 0.0 else effective * sub,
                provenance = weight.provenance,
                citation = weight.citation,
                present = sub != null,
            )
        }

        val value = contributions.sumOf { it.points }.roundToInt().coerceIn(0, 100)
        return ScoringOutcome.Scored(
            Score(
                type = ScoreType.SLEEP,
                date = inputs.date,
                value = value,
                band = Band.ofSleep(value),
                contributions = contributions,
                dataCoverage = coverage.coerceIn(0f, 1f),
                degraded = coverage < sleep.degradedBelowCoverage,
                // Sleep uses no rolling baseline, so it is never warming up. The
                // flag stays false rather than being borrowed from Recovery.
                warmingUp = false,
                scoringVersion = ScoringConfig.SCORING_VERSION,
                configHash = config.configHash,
            ),
        )
    }

    /** Exposed so the UI can show the need breakdown next to the score. */
    fun need(inputs: DailyInputs, context: SleepContext = SleepContext()): SleepNeed =
        SleepNeedCalculator.compute(context, inputs.napMinutes, config.sleep)

    private fun resolveProfile(inputs: DailyInputs): SleepProfile {
        val chosen = config.sleep.profile
        val needsStages = chosen == SleepProfile.OURA_RECOVERED || chosen == SleepProfile.BEVEL_STYLE
        return if (needsStages && !inputs.hasSleepStages) config.sleep.stagelessFallback else chosen
    }

    private fun weightsFor(profile: SleepProfile): Map<Metric, Weight> = when (profile) {
        SleepProfile.OURA_RECOVERED -> config.sleep.ouraWeights
        SleepProfile.APPLE_PUBLISHED -> config.sleep.appleWeights
        SleepProfile.BEVEL_STYLE -> config.sleep.bevelWeights
    }

    /**
     * One contributor, 0-100, or null when the input was absent.
     *
     * Metrics whose unit is "pts" arrive already normalised. They are the three
     * that cannot be computed from one night in isolation — restfulness needs the
     * intra-night wake structure, timing needs the habitual midpoint, consistency
     * needs the trailing two weeks — so the caller computes them with the public
     * helpers in [SleepSubScores] and hands the result in. Everything else is
     * scored here from its raw unit.
     */
    private fun subScore(metric: Metric, inputs: DailyInputs, asleepMinutes: Double, need: SleepNeed): Double? {
        val t = config.sleep.targets
        // A night that crossed a UTC-offset change has no meaningful circadian
        // placement: the midpoint moved because the clock did. Suppress rather
        // than punish the user for flying.
        if (inputs.timezoneShiftMinutes != 0 &&
            (metric == Metric.SLEEP_TIMING || metric == Metric.BEDTIME_CONSISTENCY)
        ) {
            return null
        }
        val raw = inputs[metric] ?: return null
        return when (metric) {
            Metric.TIME_ASLEEP -> SleepSubScores.duration(raw, need.totalMinutes, t)
            Metric.SLEEP_EFFICIENCY -> SleepSubScores.efficiency(raw, t)
            Metric.REM_MINUTES -> SleepSubScores.remShare(100.0 * raw / asleepMinutes, t)
            Metric.DEEP_MINUTES -> SleepSubScores.deepShare(100.0 * raw / asleepMinutes, t)
            Metric.SLEEP_LATENCY -> SleepSubScores.latency(raw, t)
            Metric.SLEEP_INTERRUPTIONS -> SleepSubScores.interruptions(raw.roundToInt(), t)
            Metric.HEART_RATE_DIP -> SleepSubScores.heartRateDip(raw, t)
            // Already 0-100 by the "pts" convention above.
            Metric.RESTFULNESS, Metric.SLEEP_TIMING, Metric.BEDTIME_CONSISTENCY -> raw.coerceIn(0.0, 100.0)
            else -> null
        }
    }

    private fun notScored(inputs: DailyInputs, reason: ScoreUnavailable.Reason, missing: List<Metric>) =
        ScoringOutcome.NotScored(
            ScoreUnavailable(type = ScoreType.SLEEP, date = inputs.date, reason = reason, missing = missing),
        )
}
