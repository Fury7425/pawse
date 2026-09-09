package app.pawse.scoring.sleep

import app.pawse.scoring.config.ScoringConfig
import app.pawse.scoring.config.SleepProfile
import app.pawse.scoring.fixtures.Fixtures
import app.pawse.scoring.model.Band
import app.pawse.scoring.model.DailyInputs
import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.ScoreType
import app.pawse.scoring.model.ScoreUnavailable
import app.pawse.scoring.model.ScoringOutcome
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import kotlin.math.roundToInt

class SleepScorerTest {

    private val config = ScoringConfig.DEFAULT
    private val scorer = SleepScorer(config)

    private fun scored(outcome: ScoringOutcome) =
        (outcome as ScoringOutcome.Scored).score

    private fun unavailable(outcome: ScoringOutcome) =
        (outcome as ScoringOutcome.NotScored).unavailable

    @Test
    fun `a good night under the Oura profile, computed by hand`() {
        // need = 8 h + f1(0) = 8.0131 h = 480.79 min
        // duration 445/480.79 -> 97.78, efficiency 89 -> 88, REM 21.3% -> 100,
        // deep 13.9% -> 100, latency 18 -> 100, restfulness 80, timing 84
        // 0.35*97.78 + 0.15*88 + 0.10*(100+100+100+80+84) = 93.8
        val score = scored(scorer.score(Fixtures.typicalGoodNight.inputs))
        score.type shouldBe ScoreType.SLEEP
        score.value shouldBe 94
        score.band shouldBe Band.HIGH
        score.dataCoverage.toDouble() shouldBe (1.0 plusOrMinus 1e-6)
        score.degraded shouldBe false
        score.warmingUp shouldBe false
    }

    @Test
    fun `sleep points add up exactly, unlike recovery's`() {
        // A weighted sum of 0-100 sub-scores really is a sum, so the
        // explainability screen can print the terms and the total together
        // without a footnote about marginal attribution.
        val score = scored(scorer.score(Fixtures.typicalGoodNight.inputs))
        score.contributions.sumOf { it.points }.roundToInt() shouldBe score.value
    }

    @Test
    fun `every contribution carries its provenance and citation`() {
        val score = scored(scorer.score(Fixtures.typicalGoodNight.inputs))
        score.contributions.forEach { it.citation shouldNotBe "" }
        // Oura's table is the one sleep combiner anyone reproduced, so it is
        // RECOVERED and must never be relabelled as ours.
        score.contributions.forEach {
            it.provenance shouldBe config.sleep.ouraWeights.getValue(it.metric).provenance
        }
    }

    @Test
    fun `an absolute-target combiner reports no baseline, rather than a fake one`() {
        val score = scored(scorer.score(Fixtures.typicalGoodNight.inputs))
        score.contributions.forEach {
            it.baselineMean shouldBe null
            it.baselineSd shouldBe null
            it.z shouldBe null
        }
    }

    @Test
    fun `no stage data falls back to Apple's published split`() {
        val score = scored(scorer.score(Fixtures.stagelessNight.inputs))
        // duration 430/480.79 -> 95.54, consistency 82, interruptions 2 -> 70
        // 0.5*95.54 + 0.3*82 + 0.2*70 = 86.4
        score.value shouldBe 86
        score.dataCoverage.toDouble() shouldBe (1.0 plusOrMinus 1e-6)
        score.contributions.map { it.metric }.toSet() shouldBe
            setOf(Metric.TIME_ASLEEP, Metric.BEDTIME_CONSISTENCY, Metric.SLEEP_INTERRUPTIONS)
    }

    @Test
    fun `choosing Apple explicitly keeps Apple even when stages exist`() {
        val appleOnly = SleepScorer(
            config.copy(sleep = config.sleep.copy(profile = SleepProfile.APPLE_PUBLISHED)),
        )
        val score = scored(appleOnly.score(Fixtures.typicalGoodNight.inputs))
        score.contributions.map { it.metric } shouldBe
            config.sleep.appleWeights.keys.toList()
    }

    @Test
    fun `the Bevel profile scores the heart-rate dip the other two throw away`() {
        val bevel = SleepScorer(
            config.copy(sleep = config.sleep.copy(profile = SleepProfile.BEVEL_STYLE)),
        )
        val score = scored(bevel.score(Fixtures.typicalGoodNight.inputs))
        score.dataCoverage.toDouble() shouldBe (1.0 plusOrMinus 1e-6)
        val dip = score.contributions.single { it.metric == Metric.HEART_RATE_DIP }
        dip.present shouldBe true
        dip.points shouldBe (0.15 * 100.0 plusOrMinus 1e-9)
    }

    @Test
    fun `a night that crossed a timezone loses its circadian terms instead of failing them`() {
        // The midpoint moved because the clock did. Punishing the user for
        // flying would be a bug, not a finding.
        val flown = Fixtures.typicalGoodNight.inputs.copy(timezoneShiftMinutes = -60)
        val score = scored(scorer.score(flown))

        val timing = score.contributions.single { it.metric == Metric.SLEEP_TIMING }
        timing.present shouldBe false
        timing.points shouldBe (0.0 plusOrMinus 1e-12)
        score.dataCoverage.toDouble() shouldBe (0.9 plusOrMinus 1e-6)
        // The remaining terms renormalise, so a suppressed term does not read as a zero.
        score.value shouldBe 95
    }

    @Test
    fun `duration alone is never enough to print a sleep score`() {
        val thin = DailyInputs(
            date = "2026-03-07",
            values = mapOf(Metric.TIME_ASLEEP to 450.0, Metric.REM_MINUTES to 100.0),
            hasSleepStages = true,
            hasSleepSession = true,
        )
        val reason = unavailable(scorer.score(thin))
        reason.reason shouldBe ScoreUnavailable.Reason.COVERAGE_TOO_LOW
        reason.type shouldBe ScoreType.SLEEP
        reason.missing.contains(Metric.SLEEP_EFFICIENCY) shouldBe true
    }

    @Test
    fun `no sleep session means no score and a reason that says so`() {
        val awake = Fixtures.typicalGoodNight.inputs.copy(hasSleepSession = false)
        unavailable(scorer.score(awake)).reason shouldBe ScoreUnavailable.Reason.NO_SLEEP_SESSION
    }

    @Test
    fun `a session with no measured sleep time is refused, not scored as zero`() {
        val empty = Fixtures.typicalGoodNight.inputs.copy(
            values = Fixtures.typicalGoodNight.inputs.values - Metric.TIME_ASLEEP,
        )
        val reason = unavailable(scorer.score(empty))
        reason.reason shouldBe ScoreUnavailable.Reason.NO_REQUIRED_INPUT
        reason.missing shouldBe listOf(Metric.TIME_ASLEEP)
    }

    @Test
    fun `a hard training day raises the need, which lowers the same night's score`() {
        val rested = scored(scorer.score(Fixtures.typicalGoodNight.inputs, SleepContext(strainWhoop21 = 0.0)))
        val hammered = scored(
            scorer.score(Fixtures.typicalGoodNight.inputs, SleepContext(strainWhoop21 = 19.0)),
        )
        // Same night, more need. This is the whole reason sleep need is not a
        // static eight hours.
        (hammered.value < rested.value) shouldBe true
    }

    @Test
    fun `a nap lowers tonight's need, which raises the same night's score`() {
        val noNap = scored(scorer.score(Fixtures.typicalGoodNight.inputs))
        val withNap = scored(
            scorer.score(Fixtures.typicalGoodNight.inputs.copy(napMinutes = 60)),
        )
        (withNap.value >= noNap.value) shouldBe true
    }

    @Test
    fun `the alcohol night still sleeps respectably, which is the point`() {
        // grok.txt §12: alcohol looks like a fine-duration night to a
        // duration-led score and a red morning to an HRV-led one. Sleep is not
        // supposed to catch this; Recovery is.
        val score = scored(scorer.score(Fixtures.alcoholNight.inputs))
        (score.value >= 75) shouldBe true
        score.band shouldNotBe Band.LOW
    }

    @Test
    fun `the score carries the config hash that produced it`() {
        val score = scored(scorer.score(Fixtures.typicalGoodNight.inputs))
        score.configHash shouldBe config.configHash
        score.scoringVersion shouldBe ScoringConfig.SCORING_VERSION
    }
}
