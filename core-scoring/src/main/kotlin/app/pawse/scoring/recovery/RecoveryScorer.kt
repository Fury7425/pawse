package app.pawse.scoring.recovery

import app.pawse.scoring.baseline.BaselineEngine
import app.pawse.scoring.config.RecoveryCombiner
import app.pawse.scoring.config.ScoringConfig
import app.pawse.scoring.model.Band
import app.pawse.scoring.model.Baseline
import app.pawse.scoring.model.Contribution
import app.pawse.scoring.model.DailyInputs
import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.MetricHistory
import app.pawse.scoring.model.Provenance
import app.pawse.scoring.model.Score
import app.pawse.scoring.model.ScoreType
import app.pawse.scoring.model.ScoreUnavailable
import app.pawse.scoring.model.ScoringOutcome
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Recovery Score.
 *
 *   z_i   = direction_i x (x_i - mu_i) / sigma_i,  clamped to +/-3
 *   Z     = sum(w_i' x z_i)                        w' renormalised over present terms
 *   Score = 100 / (1 + e^(-k(Z - z0)))             k = 1.1, z0 = 0
 *   then bounded anomaly penalties for temperature and SpO2 are subtracted.
 *
 * The functional form is the one grok.txt §11 and gemini.txt both write down for
 * this whole family of apps. The weights are ours and are tagged OUR_CHOICE in
 * config; no vendor publishes a recovery weight table, and presenting one as
 * Whoop's or Oura's would be a fabrication. What *is* known and is respected here:
 * HRV dominates, resting heart rate is second and sign-flipped, sleep is smaller,
 * respiratory rate is smaller still, and temperature and SpO2 are flags rather
 * than continuous drivers.
 *
 * ### Two behaviours worth knowing before reading the code
 *
 * **Missing inputs are renormalised away, never defaulted.** A watch that reports
 * no respiratory rate does not get a population average silently inserted; its
 * 0.07 of weight is redistributed across what is present and the loss shows up in
 * `dataCoverage`, which the UI prints next to the number. Below
 * `refuseBelowCoverage` there is no number at all.
 *
 * **Points are marginal, not additive.** A logistic is not a sum, so a term's
 * `points` is the leave-one-out marginal — how far the score moves if that term
 * is removed and everything else held. They will not add to the total, and the
 * explainability copy says so. The alternative, splitting the total in proportion
 * to `w x z`, produces numbers that add up and mean nothing.
 */
class RecoveryScorer(
    private val config: ScoringConfig,
    private val baselineEngine: BaselineEngine = BaselineEngine(config.baseline),
) {

    fun score(inputs: DailyInputs, histories: Map<Metric, MetricHistory>): ScoringOutcome {
        val recovery = config.recovery
        val weights = recovery.weights

        val terms = weights.map { (metric, weight) ->
            val raw = inputs[metric]
            val baseline = histories[metric]?.let(baselineEngine::baseline)
            val z = if (raw != null && baseline != null && !baseline.warmingUp) {
                if (recovery.combiner == RecoveryCombiner.LOGISTIC_Z) {
                    baselineEngine.z(baseline, raw)
                } else {
                    movingAverageDelta(metric, raw, baseline, histories[metric])
                }
            } else {
                null
            }
            Term(
                metric = metric,
                raw = raw,
                baseline = baseline,
                z = z,
                nominalWeight = weight.weight,
                warmingUp = raw != null && baseline != null && baseline.warmingUp,
                provenance = weight.provenance,
                citation = weight.citation,
            )
        }

        val nominalTotal = terms.sumOf { it.nominalWeight }
        val presentTotal = terms.filter { it.z != null }.sumOf { it.nominalWeight }
        val coverage = if (nominalTotal <= 0.0) 0f else (presentTotal / nominalTotal).toFloat()

        if (coverage < recovery.refuseBelowCoverage) {
            return ScoringOutcome.NotScored(
                ScoreUnavailable(
                    type = ScoreType.RECOVERY,
                    date = inputs.date,
                    reason = refusalReason(terms),
                    missing = terms.filter { it.z == null }.map { it.metric },
                ),
            )
        }

        val composite = terms.sumOf { term ->
            val z = term.z ?: return@sumOf 0.0
            (term.nominalWeight / presentTotal) * z
        }
        val base = logistic(composite)

        val anomalies = anomalyContributions(inputs, histories)
        val penalty = anomalies.sumOf { -it.points }

        val value = (base - penalty)
            .roundToInt()
            .coerceIn(recovery.displayFloor, recovery.displayCeiling)

        val contributions = terms.map { term ->
            val effective = if (term.z == null || presentTotal <= 0.0) 0.0 else term.nominalWeight / presentTotal
            // Leave-one-out marginal: how far removing this term alone moves the score.
            val marginal = if (term.z == null) 0.0 else base - logistic(composite - effective * term.z)
            Contribution(
                metric = term.metric,
                raw = term.raw,
                baselineMean = term.baseline?.mean,
                baselineSd = term.baseline?.sigma,
                baselineWindowDays = term.baseline?.windowDays ?: config.baseline.windowDays,
                baselineSampleCount = term.baseline?.sampleCount ?: 0,
                z = term.z,
                weight = effective,
                nominalWeight = term.nominalWeight,
                points = marginal,
                provenance = term.provenance,
                citation = term.citation,
                present = term.z != null,
            )
        } + anomalies

        val warmingUp = terms.any { it.warmingUp }
        return ScoringOutcome.Scored(
            Score(
                type = ScoreType.RECOVERY,
                date = inputs.date,
                value = value,
                band = Band.ofRecovery(value),
                contributions = contributions,
                dataCoverage = coverage.coerceIn(0f, 1f),
                degraded = coverage < recovery.degradedBelowCoverage || warmingUp,
                warmingUp = warmingUp,
                scoringVersion = ScoringConfig.SCORING_VERSION,
                configHash = config.configHash,
            ),
        )
    }

    private fun logistic(composite: Double): Double {
        val r = config.recovery
        return 100.0 / (1.0 + exp(-r.k * (composite - r.z0)))
    }

    /**
     * Whoop's documented alternative, selectable in config: "the magnitude of the
     * differences between 7-day moving averages and 3-day moving averages" of the
     * same readings (patent language, claude.md §2).
     *
     * Tonight is included in the fast window here — that is the whole point of a
     * 3-day average, and it is why this combiner reacts more slowly than
     * [RecoveryCombiner.LOGISTIC_Z] to a single bad night and more decisively to
     * three of them. The difference is still divided by the long-run sigma, so the
     * output stays on the same z-like scale and the same logistic can consume it.
     */
    private fun movingAverageDelta(
        metric: Metric,
        tonight: Double,
        baseline: Baseline,
        history: MetricHistory?,
    ): Double? {
        val r = config.recovery
        val byDay = buildMap<Int, Double> {
            put(0, tonight)
            history?.samples.orEmpty()
                .filter { it.daysAgo > 0 }
                .groupBy { it.daysAgo }
                .forEach { (day, dupes) -> put(day, dupes.map { it.value }.average()) }
        }
        val fast = (0 until r.maFastDays).mapNotNull { byDay[it] }
        val slow = (0 until r.maSlowDays).mapNotNull { byDay[it] }
        if (fast.size < r.maMinFastSamples || slow.size < r.maMinSlowSamples) return null
        if (baseline.sigma <= 0.0 || !baseline.sigma.isFinite()) return null

        val delta = fast.average() - slow.average()
        val signed = if (metric.deviationOnly) {
            -abs(delta) / baseline.sigma
        } else {
            metric.direction * delta / baseline.sigma
        }
        return signed.coerceIn(-config.baseline.zClamp, config.baseline.zClamp)
    }

    /**
     * Temperature and SpO2 as bounded flags.
     *
     * Both reports that discuss them agree they are illness / overreach signals on
     * newer hardware rather than linear score drivers (grok.txt §3, claude.md §2),
     * so they subtract a fixed, small, capped number of points when tonight sits
     * far enough from the user's own range, and contribute nothing otherwise.
     *
     * Temperature fires in both directions; SpO2 only downward, because a
     * saturation reading at the top of its range carries no information.
     *
     * The copy attached to these never says illness. Not a medical device.
     */
    private fun anomalyContributions(
        inputs: DailyInputs,
        histories: Map<Metric, MetricHistory>,
    ): List<Contribution> {
        val r = config.recovery
        val out = mutableListOf<Contribution>()

        val tempRaw = inputs[Metric.SKIN_TEMPERATURE]
        val tempBaseline = histories[Metric.SKIN_TEMPERATURE]?.let(baselineEngine::baseline)
        val tempSigmas = if (tempRaw != null && tempBaseline != null && !tempBaseline.warmingUp) {
            baselineEngine.deviationSigma(tempBaseline, tempRaw)
        } else {
            null
        }
        out += Contribution(
            metric = Metric.SKIN_TEMPERATURE,
            raw = tempRaw,
            baselineMean = tempBaseline?.mean,
            baselineSd = tempBaseline?.sigma,
            baselineWindowDays = tempBaseline?.windowDays ?: config.baseline.windowDays,
            baselineSampleCount = tempBaseline?.sampleCount ?: 0,
            // Reported with the same sign convention as every other z in the app:
            // negative is worse than baseline. Temperature has no good direction,
            // so any excursion reads negative.
            z = tempSigmas?.let { -it.coerceAtMost(config.baseline.zClamp) },
            weight = 0.0,
            nominalWeight = 0.0,
            points = if (tempSigmas != null && tempSigmas >= r.tempAnomalySigma) -r.tempAnomalyPenalty else 0.0,
            provenance = Provenance.OUR_CHOICE,
            citation = "Our default. Bounded flag at +/-${r.tempAnomalySigma} sigma, " +
                "-${r.tempAnomalyPenalty} points. Reports treat wrist temperature as an " +
                "excursion flag, never a continuous term (grok.txt §3; claude.md §2).",
            present = tempSigmas != null,
            anomalyFlag = true,
        )

        val spo2Raw = inputs[Metric.SPO2]
        val spo2Baseline = histories[Metric.SPO2]?.let(baselineEngine::baseline)
        val spo2Z = if (spo2Raw != null && spo2Baseline != null && !spo2Baseline.warmingUp) {
            baselineEngine.z(spo2Baseline, spo2Raw)
        } else {
            null
        }
        out += Contribution(
            metric = Metric.SPO2,
            raw = spo2Raw,
            baselineMean = spo2Baseline?.mean,
            baselineSd = spo2Baseline?.sigma,
            baselineWindowDays = spo2Baseline?.windowDays ?: config.baseline.windowDays,
            baselineSampleCount = spo2Baseline?.sampleCount ?: 0,
            z = spo2Z,
            weight = 0.0,
            nominalWeight = 0.0,
            points = if (spo2Z != null && spo2Z <= -r.spo2AnomalySigma) -r.spo2AnomalyPenalty else 0.0,
            provenance = Provenance.OUR_CHOICE,
            citation = "Our default. Bounded flag at -${r.spo2AnomalySigma} sigma, " +
                "-${r.spo2AnomalyPenalty} points. Low only: a saturation at the top of " +
                "its range carries no signal.",
            present = spo2Z != null,
            anomalyFlag = true,
        )

        return out
    }

    private fun refusalReason(terms: List<Term>): ScoreUnavailable.Reason = when {
        terms.none { it.raw != null } -> ScoreUnavailable.Reason.NO_REQUIRED_INPUT
        terms.filter { it.raw != null }.all { it.baseline == null || it.baseline.warmingUp } ->
            ScoreUnavailable.Reason.BASELINE_WARMUP
        else -> ScoreUnavailable.Reason.COVERAGE_TOO_LOW
    }

    private data class Term(
        val metric: Metric,
        val raw: Double?,
        val baseline: Baseline?,
        val z: Double?,
        val nominalWeight: Double,
        val warmingUp: Boolean,
        val provenance: Provenance,
        val citation: String,
    )
}
