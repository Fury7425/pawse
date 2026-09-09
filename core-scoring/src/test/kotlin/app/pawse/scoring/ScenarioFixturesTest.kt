package app.pawse.scoring

import app.pawse.scoring.config.ScoringConfig
import app.pawse.scoring.fixtures.Fixtures
import app.pawse.scoring.model.Band
import app.pawse.scoring.model.DailyInputs
import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.Score
import app.pawse.scoring.model.ScoreUnavailable
import app.pawse.scoring.model.ScoringOutcome
import app.pawse.scoring.recovery.RecoveryScorer
import app.pawse.scoring.sleep.SleepScorer
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test

/**
 * The six synthetic nights, run through the real nightly pipeline: sleep first,
 * then last night's sleep score fed forward into recovery as one of its four
 * continuous terms.
 *
 * These tests assert bands and orderings rather than exact integers. The exact
 * arithmetic is pinned next to each component; what belongs here is the question
 * the app exists to answer, which is whether a night that physiology says was bad
 * comes out red and says honestly why.
 */
class ScenarioFixturesTest {

    private val config = ScoringConfig.DEFAULT
    private val sleepScorer = SleepScorer(config)
    private val recoveryScorer = RecoveryScorer(config)

    /**
     * The pipeline. Sleep is scored first because Recovery consumes it; that
     * ordering is the reason [Metric.SLEEP_SCORE] is an input metric at all.
     */
    private fun run(night: Fixtures.Night): Pair<ScoringOutcome, ScoringOutcome> {
        val sleep = sleepScorer.score(night.inputs)
        val withSleep = sleep.scoreOrNull?.let { s ->
            night.inputs.copy(values = night.inputs.values + (Metric.SLEEP_SCORE to s.value.toDouble()))
        } ?: night.inputs
        return sleep to recoveryScorer.score(withSleep, night.histories)
    }

    private fun scored(outcome: ScoringOutcome): Score = (outcome as ScoringOutcome.Scored).score
    private fun unavailable(outcome: ScoringOutcome) = (outcome as ScoringOutcome.NotScored).unavailable

    @Test
    fun `a night at your own baseline sits in the middle of your own scale`() {
        val (sleep, recovery) = run(Fixtures.typicalGoodNight)
        val s = scored(sleep)
        val r = scored(recovery)

        s.band shouldBe Band.HIGH
        r.band shouldBe Band.MODERATE
        // Cardiac signals are exactly at baseline; only the better-than-usual
        // sleep term lifts it, and only slightly.
        (r.value in 45..62) shouldBe true
        r.dataCoverage.toDouble() shouldBe (1.0 plusOrMinus 1e-6)
        r.degraded shouldBe false
        r.warmingUp shouldBe false
    }

    @Test
    fun `the alcohol night is the disagreement the reports describe`() {
        // grok.txt §12: "Alcohol can look like a fine duration Apple night and a
        // red Whoop Recovery." Same eight hours, two honest numbers, sixty points
        // apart. If this test ever stops failing to agree, the engine has stopped
        // modelling the thing it was built to model.
        val (sleep, recovery) = run(Fixtures.alcoholNight)
        val s = scored(sleep)
        val r = scored(recovery)

        s.band shouldNotBe Band.LOW
        (s.value >= 75) shouldBe true

        r.band shouldBe Band.LOW
        (r.value <= 33) shouldBe true
        (s.value - r.value >= 40) shouldBe true
    }

    @Test
    fun `HRV carries the alcohol night, as every report says it should`() {
        val (_, recovery) = run(Fixtures.alcoholNight)
        val contributions = scored(recovery).contributions.filter { !it.anomalyFlag && it.present }
        val worst = contributions.minByOrNull { it.points }!!
        worst.metric shouldBe Metric.HRV_RMSSD
        // And it is our number, not a vendor's, and says so.
        worst.provenance shouldBe config.recovery.weights.getValue(Metric.HRV_RMSSD).provenance
    }

    @Test
    fun `both bounded flags fire on the flagged night and cost exactly their cap`() {
        val (_, recovery) = run(Fixtures.flaggedNight)
        val r = scored(recovery)

        val temp = r.contributions.single { it.metric == Metric.SKIN_TEMPERATURE }
        val spo2 = r.contributions.single { it.metric == Metric.SPO2 }

        temp.anomalyFlag shouldBe true
        spo2.anomalyFlag shouldBe true
        temp.points shouldBe (-config.recovery.tempAnomalyPenalty plusOrMinus 1e-12)
        spo2.points shouldBe (-config.recovery.spo2AnomalyPenalty plusOrMinus 1e-12)

        // Flags are bounded: together they can take ten points and no more.
        (temp.points + spo2.points) shouldBe (-10.0 plusOrMinus 1e-12)
        r.band shouldBe Band.LOW
    }

    @Test
    fun `flags carry zero continuous weight, so they cannot dominate the composite`() {
        val (_, recovery) = run(Fixtures.flaggedNight)
        scored(recovery).contributions.filter { it.anomalyFlag }.forEach {
            it.weight shouldBe (0.0 plusOrMinus 1e-12)
            it.nominalWeight shouldBe (0.0 plusOrMinus 1e-12)
        }
    }

    @Test
    fun `week one produces a sleep score and no recovery score`() {
        // Sleep is an absolute-target combiner and works from night one. Recovery
        // is baseline-relative and cannot, so it refuses and says why rather than
        // printing a number built on six samples.
        val (sleep, recovery) = run(Fixtures.warmUpNight)
        scored(sleep).value shouldBe 94
        unavailable(recovery).reason shouldBe ScoreUnavailable.Reason.BASELINE_WARMUP
    }

    @Test
    fun `a writer app with no stages still gets both scores`() {
        val (sleep, recovery) = run(Fixtures.stagelessNight)
        val s = scored(sleep)

        // Apple's split took over; no stage metric appears anywhere in the terms.
        s.contributions.map { it.metric }.none {
            it == Metric.REM_MINUTES || it == Metric.DEEP_MINUTES
        } shouldBe true
        s.dataCoverage.toDouble() shouldBe (1.0 plusOrMinus 1e-6)

        val r = scored(recovery)
        (r.value > 50) shouldBe true
        // No temperature or SpO2 hardware, so neither flag is present and neither
        // is silently treated as normal.
        r.contributions.single { it.metric == Metric.SKIN_TEMPERATURE }.present shouldBe false
        r.contributions.single { it.metric == Metric.SPO2 }.present shouldBe false
    }

    @Test
    fun `an HRV-only strap gets a degraded recovery score and no sleep score at all`() {
        val (sleep, recovery) = run(Fixtures.partialCoverageNight)
        unavailable(sleep).reason shouldBe ScoreUnavailable.Reason.NO_SLEEP_SESSION

        val r = scored(recovery)
        r.dataCoverage.toDouble() shouldBe (0.60 plusOrMinus 1e-6)
        r.degraded shouldBe true
        r.band shouldBe Band.LOW
        // The one present term absorbed the whole weight, and the contribution
        // says so rather than pretending four signals agreed.
        r.contributions.single { it.metric == Metric.HRV_RMSSD }.weight shouldBe (1.0 plusOrMinus 1e-9)
    }

    @Test
    fun `every fixture that scores prints its coverage, version and hash`() {
        Fixtures.all.forEach { night ->
            val (sleep, recovery) = run(night)
            listOfNotNull(sleep.scoreOrNull, recovery.scoreOrNull).forEach { score ->
                (score.dataCoverage in 0f..1f) shouldBe true
                score.scoringVersion shouldBe ScoringConfig.SCORING_VERSION
                score.configHash shouldBe config.configHash
                (score.value in 0..100) shouldBe true
            }
        }
    }

    @Test
    fun `every contribution in every fixture is traceable to a source`() {
        // Ground rule: no coefficient without a provenance tag and a citation the
        // explainability sheet can print verbatim.
        Fixtures.all.forEach { night ->
            val (sleep, recovery) = run(night)
            listOfNotNull(sleep.scoreOrNull, recovery.scoreOrNull).forEach { score ->
                score.contributions.forEach {
                    it.citation.isNotBlank() shouldBe true
                }
            }
        }
    }

    @Test
    fun `retuning a weight writes a different hash, so history is never rewritten`() {
        val tuned = config.copy(
            recovery = config.recovery.copy(
                weights = config.recovery.weights + (
                    Metric.HRV_RMSSD to config.recovery.weights
                        .getValue(Metric.HRV_RMSSD).copy(weight = 0.55)
                    ),
            ),
        )
        val night = Fixtures.typicalGoodNight
        val before = scored(recoveryScorer.score(night.inputs, night.histories))
        val after = scored(RecoveryScorer(tuned).score(night.inputs, night.histories))

        after.configHash shouldNotBe before.configHash
        // Same engine structure, so the version does not move; only the hash does.
        after.scoringVersion shouldBe before.scoringVersion
    }

    @Test
    fun `a night with no data at all is a refusal in both scorers`() {
        val nothing = DailyInputs(
            date = "2026-05-01",
            values = emptyMap(),
            hasSleepStages = false,
            hasSleepSession = false,
        )
        unavailable(sleepScorer.score(nothing)).reason shouldBe ScoreUnavailable.Reason.NO_SLEEP_SESSION
        unavailable(recoveryScorer.score(nothing, Fixtures.histories())).reason shouldBe
            ScoreUnavailable.Reason.NO_REQUIRED_INPUT
    }
}
