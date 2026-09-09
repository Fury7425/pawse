package app.pawse.scoring.recovery

import app.pawse.scoring.baseline.BaselineEngine
import app.pawse.scoring.config.BaselineConfig
import app.pawse.scoring.config.RecoveryCombiner
import app.pawse.scoring.config.ScoringConfig
import app.pawse.scoring.model.Band
import app.pawse.scoring.model.DailyInputs
import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.MetricHistory
import app.pawse.scoring.model.Score
import app.pawse.scoring.model.ScoreType
import app.pawse.scoring.model.ScoreUnavailable
import app.pawse.scoring.model.ScoringOutcome
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.Test
import kotlin.math.abs

class RecoveryScorerTest {

    /**
     * A flat-weighted baseline so that every z-score in this file is a number the
     * test can state rather than a number the engine happens to produce.
     */
    private val baselineConfig = BaselineConfig(windowDays = 60, halfLifeDays = 1e9, warmupSamples = 3)
    private val config = ScoringConfig.DEFAULT.copy(baseline = baselineConfig)
    private val engine = BaselineEngine(baselineConfig)
    private val scorer = RecoveryScorer(config, engine)

    private val means = mapOf(
        Metric.HRV_RMSSD to 60.0,
        Metric.RESTING_HEART_RATE to 52.0,
        Metric.SLEEP_SCORE to 85.0,
        Metric.RESPIRATORY_RATE to 14.5,
        Metric.SKIN_TEMPERATURE to 0.0,
        Metric.SPO2 to 96.0,
    )
    private val spreads = mapOf(
        Metric.HRV_RMSSD to 10.0,
        Metric.RESTING_HEART_RATE to 3.0,
        Metric.SLEEP_SCORE to 6.0,
        Metric.RESPIRATORY_RATE to 0.8,
        Metric.SKIN_TEMPERATURE to 0.25,
        Metric.SPO2 to 1.2,
    )

    /** A symmetric 20-night history centred exactly on the mean. */
    private fun history(metric: Metric): MetricHistory {
        val mean = means.getValue(metric)
        val spread = spreads.getValue(metric)
        val offsets = (-5..5).filter { it != 0 }.map { it / 5.0 } + List(10) { 0.0 }
        return MetricHistory(
            metric,
            offsets.mapIndexed { i, o -> MetricHistory.Sample(i + 1, mean + spread * o) },
        )
    }

    private val histories = means.keys.associateWith { history(it) }

    /** The raw value that produces exactly this z-score for this metric. */
    private fun rawForZ(metric: Metric, z: Double): Double {
        val baseline = engine.baseline(histories.getValue(metric))!!
        return baseline.mean + metric.direction * z * baseline.sigma
    }

    private fun night(zScores: Map<Metric, Double>, date: String = "2026-04-01") = DailyInputs(
        date = date,
        values = zScores.mapValues { (metric, z) -> rawForZ(metric, z) },
        hasSleepStages = true,
        hasSleepSession = true,
    )

    private fun scored(outcome: ScoringOutcome) = (outcome as ScoringOutcome.Scored).score
    private fun unavailable(outcome: ScoringOutcome) = (outcome as ScoringOutcome.NotScored).unavailable

    private val allContinuous = config.recovery.weights.keys.toList()

    @Test
    fun `sitting exactly on your own baseline reads fifty, not fifty-eight`() {
        // Some open clones shift z0 so baseline maps to ~58 to match Whoop's
        // stated population average. A personal baseline belongs in the middle of
        // a personal scale, so ours does not (PLAN.md, assumptions).
        val score = scored(scorer.score(night(allContinuous.associateWith { 0.0 }), histories))
        score.type shouldBe ScoreType.RECOVERY
        score.value shouldBe 50
        score.band shouldBe Band.MODERATE
        score.dataCoverage.toDouble() shouldBe (1.0 plusOrMinus 1e-6)
        score.degraded shouldBe false
    }

    @Test
    fun `a uniform one-sigma composite lands where the logistic says it should`() {
        // 100 / (1 + e^(-1.1 * -1)) = 24.97
        val down = scored(scorer.score(night(allContinuous.associateWith { -1.0 }), histories))
        down.value shouldBe 25
        // 100 / (1 + e^(-1.1 * 1)) = 75.03
        val up = scored(scorer.score(night(allContinuous.associateWith { 1.0 }), histories))
        up.value shouldBe 75
    }

    @Test
    fun `a missing input redistributes its weight instead of defaulting to a population value`() {
        // HRV two sigma up, alone: it carries the whole composite.
        val alone = scored(scorer.score(night(mapOf(Metric.HRV_RMSSD to 2.0)), histories))
        alone.value shouldBe 90 // 100 / (1 + e^(-2.2))
        alone.dataCoverage.toDouble() shouldBe (0.60 plusOrMinus 1e-6)
        alone.degraded shouldBe true

        // The same HRV reading with the other three at baseline: 0.6 * 2 = 1.2.
        val full = scored(
            scorer.score(
                night(allContinuous.associateWith { 0.0 } + (Metric.HRV_RMSSD to 2.0)),
                histories,
            ),
        )
        full.value shouldBe 79 // 100 / (1 + e^(-1.32))
        full.dataCoverage.toDouble() shouldBe (1.0 plusOrMinus 1e-6)
        full.degraded shouldBe false
    }

    @Test
    fun `resting heart rate is sign-flipped, so a higher reading lowers the score`() {
        val baseline = engine.baseline(histories.getValue(Metric.RESTING_HEART_RATE))!!
        val elevated = night(allContinuous.associateWith { 0.0 }).let {
            it.copy(values = it.values + (Metric.RESTING_HEART_RATE to baseline.mean + 2 * baseline.sigma))
        }
        val score = scored(scorer.score(elevated, histories))
        val term = score.contributions.single { it.metric == Metric.RESTING_HEART_RATE }
        term.z!! shouldBe (-2.0 plusOrMinus 1e-9)
        // 0.20 x -2 = -0.40 composite -> 100 / (1 + e^0.44) = 39
        score.value shouldBe 39
    }

    @Test
    fun `HRV moves the score further than resting heart rate at the same deviation`() {
        // Direction is published everywhere; the ordering is the only part of the
        // weight table any report supports, and it has to hold.
        val score = scored(
            scorer.score(
                night(
                    allContinuous.associateWith { 0.0 } +
                        mapOf(Metric.HRV_RMSSD to -1.5, Metric.RESTING_HEART_RATE to -1.5),
                ),
                histories,
            ),
        )
        val hrv = score.contributions.single { it.metric == Metric.HRV_RMSSD }
        val rhr = score.contributions.single { it.metric == Metric.RESTING_HEART_RATE }
        abs(hrv.points) shouldBeGreaterThan abs(rhr.points)
    }

    @Test
    fun `points are marginal and signed, and do not pretend to add up`() {
        val score = scored(
            scorer.score(
                night(allContinuous.associateWith { 0.0 } + (Metric.HRV_RMSSD to -2.0)),
                histories,
            ),
        )
        val hrv = score.contributions.single { it.metric == Metric.HRV_RMSSD }
        hrv.points shouldBeLessThan 0.0
        // The documented contract: a term's points is how far removing it alone
        // would move the score, so the terms cannot sum to the total.
        val sum = score.contributions.sumOf { it.points }
        abs(sum - score.value) shouldBeGreaterThan 1.0
    }

    @Test
    fun `an absent term is reported as absent, with zero weight and zero points`() {
        val score = scored(scorer.score(night(mapOf(Metric.HRV_RMSSD to 0.5)), histories))
        val sleep = score.contributions.single { it.metric == Metric.SLEEP_SCORE }
        sleep.present shouldBe false
        sleep.weight shouldBe (0.0 plusOrMinus 1e-12)
        sleep.points shouldBe (0.0 plusOrMinus 1e-12)
        // The nominal weight is still reported, so the UI can say what was lost.
        sleep.nominalWeight shouldBe (0.13 plusOrMinus 1e-12)
    }

    /** A night where every continuous term sits on baseline, plus one override. */
    private fun quietNightWith(metric: Metric, raw: Double): DailyInputs =
        night(allContinuous.associateWith { 0.0 }).let { it.copy(values = it.values + (metric to raw)) }

    @Test
    fun `temperature fires in both directions and costs exactly its capped points`() {
        val r = config.recovery
        val quiet = scored(scorer.score(night(allContinuous.associateWith { 0.0 }), histories))
        val base = means.getValue(Metric.SKIN_TEMPERATURE)
        val sigma = engine.baseline(histories.getValue(Metric.SKIN_TEMPERATURE))!!.sigma

        val warm = scored(scorer.score(quietNightWith(Metric.SKIN_TEMPERATURE, base + 2 * sigma), histories))
        val cold = scored(scorer.score(quietNightWith(Metric.SKIN_TEMPERATURE, base - 2 * sigma), histories))

        fun tempTerm(s: Score) = s.contributions.single { it.metric == Metric.SKIN_TEMPERATURE }

        tempTerm(warm).points shouldBe (-r.tempAnomalyPenalty plusOrMinus 1e-12)
        tempTerm(cold).points shouldBe (-r.tempAnomalyPenalty plusOrMinus 1e-12)
        tempTerm(warm).anomalyFlag shouldBe true
        // Both directions read as a negative z, because temperature has no good one.
        tempTerm(warm).z!! shouldBeLessThan 0.0
        tempTerm(cold).z!! shouldBeLessThan 0.0
        warm.value shouldBe (quiet.value - r.tempAnomalyPenalty.toInt())
        cold.value shouldBe warm.value
    }

    @Test
    fun `a temperature just inside the band costs nothing`() {
        val base = means.getValue(Metric.SKIN_TEMPERATURE)
        val sigma = engine.baseline(histories.getValue(Metric.SKIN_TEMPERATURE))!!.sigma
        val score = scored(scorer.score(quietNightWith(Metric.SKIN_TEMPERATURE, base + 1.4 * sigma), histories))
        score.contributions.single { it.metric == Metric.SKIN_TEMPERATURE }
            .points shouldBe (0.0 plusOrMinus 1e-12)
        score.value shouldBe 50
    }

    @Test
    fun `SpO2 fires only downward, because a high saturation carries no signal`() {
        val r = config.recovery
        val base = means.getValue(Metric.SPO2)
        val sigma = engine.baseline(histories.getValue(Metric.SPO2))!!.sigma

        scored(scorer.score(quietNightWith(Metric.SPO2, base - 2 * sigma), histories)).contributions
            .single { it.metric == Metric.SPO2 }.points shouldBe (-r.spo2AnomalyPenalty plusOrMinus 1e-12)
        scored(scorer.score(quietNightWith(Metric.SPO2, base + 2 * sigma), histories)).contributions
            .single { it.metric == Metric.SPO2 }.points shouldBe (0.0 plusOrMinus 1e-12)
    }

    @Test
    fun `the score is reported one to ninety-nine, never zero and never a hundred`() {
        val base = means.getValue(Metric.SKIN_TEMPERATURE)
        val tSigma = engine.baseline(histories.getValue(Metric.SKIN_TEMPERATURE))!!.sigma
        val spo2Base = means.getValue(Metric.SPO2)
        val sSigma = engine.baseline(histories.getValue(Metric.SPO2))!!.sigma

        val disaster = night(allContinuous.associateWith { -3.0 }).let {
            it.copy(
                values = it.values +
                    (Metric.SKIN_TEMPERATURE to base + 3 * tSigma) +
                    (Metric.SPO2 to spo2Base - 3 * sSigma),
            )
        }
        scored(scorer.score(disaster, histories)).value shouldBe config.recovery.displayFloor

        val perfect = night(allContinuous.associateWith { 3.0 })
        scored(scorer.score(perfect, histories)).value shouldBe 96
    }

    @Test
    fun `six nights of history is a refusal, not a score`() {
        val young = histories.mapValues { (metric, h) ->
            MetricHistory(metric, h.samples.filter { it.daysAgo <= 6 })
        }
        val warmupScorer = RecoveryScorer(
            ScoringConfig.DEFAULT.copy(baseline = baselineConfig.copy(warmupSamples = 14)),
        )
        val reason = unavailable(
            warmupScorer.score(night(allContinuous.associateWith { 0.0 }), young),
        )
        reason.reason shouldBe ScoreUnavailable.Reason.BASELINE_WARMUP
        reason.type shouldBe ScoreType.RECOVERY
    }

    @Test
    fun `one small input is not enough to print a recovery number`() {
        val thin = night(mapOf(Metric.RESPIRATORY_RATE to -1.0))
        val reason = unavailable(scorer.score(thin, histories))
        reason.reason shouldBe ScoreUnavailable.Reason.COVERAGE_TOO_LOW
        reason.missing.contains(Metric.HRV_RMSSD) shouldBe true
    }

    @Test
    fun `a night with no inputs at all says so specifically`() {
        val nothing = DailyInputs(
            date = "2026-04-09",
            values = emptyMap(),
            hasSleepStages = false,
            hasSleepSession = false,
        )
        unavailable(scorer.score(nothing, histories)).reason shouldBe
            ScoreUnavailable.Reason.NO_REQUIRED_INPUT
    }

    @Test
    fun `a partly warmed-up user gets a number that admits it is warming up`() {
        val mixed = histories.mapValues { (metric, h) ->
            if (metric == Metric.SLEEP_SCORE || metric == Metric.RESPIRATORY_RATE) {
                MetricHistory(metric, h.samples.filter { it.daysAgo <= 6 })
            } else {
                h
            }
        }
        val strictWarmup = RecoveryScorer(
            ScoringConfig.DEFAULT.copy(baseline = baselineConfig.copy(warmupSamples = 14)),
        )
        val score = scored(strictWarmup.score(night(allContinuous.associateWith { 0.0 }), mixed))
        score.warmingUp shouldBe true
        score.degraded shouldBe true
        score.dataCoverage.toDouble() shouldBe (0.80 plusOrMinus 1e-6)
    }

    @Test
    fun `the moving-average combiner reacts to a single bad night far less than the z combiner`() {
        // Whoop's patent form compares a 3-day average to a 7-day one. That is a
        // different instrument from a same-night z-score, and it should behave
        // like one: three bad nights matter, one does not.
        val badHrv = DailyInputs(
            date = "2026-04-10",
            values = mapOf(Metric.HRV_RMSSD to means.getValue(Metric.HRV_RMSSD) - 25.0),
            hasSleepStages = true,
            hasSleepSession = true,
        )
        val movingAverage = RecoveryScorer(
            config.copy(recovery = config.recovery.copy(combiner = RecoveryCombiner.MOVING_AVERAGE_DELTA)),
            engine,
        )
        val zScore = scored(scorer.score(badHrv, histories)).value
        val maScore = scored(movingAverage.score(badHrv, histories)).value

        zScore shouldBeLessThanInt maScore
        // Both still agree on the direction.
        maScore shouldBeLessThanInt 50
    }

    @Test
    fun `the moving-average combiner refuses a term with too little recent history`() {
        val sparse = mapOf(
            Metric.HRV_RMSSD to MetricHistory(
                Metric.HRV_RMSSD,
                // Nothing inside the seven-day window, so no moving average exists.
                (30..50).map { MetricHistory.Sample(it, means.getValue(Metric.HRV_RMSSD) + (it % 5) - 2) },
            ),
        )
        val movingAverage = RecoveryScorer(
            config.copy(recovery = config.recovery.copy(combiner = RecoveryCombiner.MOVING_AVERAGE_DELTA)),
            engine,
        )
        val outcome = movingAverage.score(
            DailyInputs(
                date = "2026-04-11",
                values = mapOf(Metric.HRV_RMSSD to means.getValue(Metric.HRV_RMSSD)),
                hasSleepStages = false,
                hasSleepSession = true,
            ),
            sparse,
        )
        unavailable(outcome).reason shouldBe ScoreUnavailable.Reason.COVERAGE_TOO_LOW
    }

    @Test
    fun `the score carries the version and config hash that produced it`() {
        val score = scored(scorer.score(night(allContinuous.associateWith { 0.0 }), histories))
        score.scoringVersion shouldBe ScoringConfig.SCORING_VERSION
        score.configHash shouldBe config.configHash
    }

    private infix fun Int.shouldBeLessThanInt(other: Int) {
        check(this < other) { "expected $this < $other" }
    }
}
