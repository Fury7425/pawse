package app.pawse.scoring.energy

import app.pawse.scoring.config.ScoringConfig
import app.pawse.scoring.model.Band
import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.Provenance
import app.pawse.scoring.model.ScoreType
import app.pawse.scoring.model.ScoreUnavailable
import app.pawse.scoring.model.ScoringOutcome
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class EnergyBankScorerTest {

    private val config = ScoringConfig.DEFAULT
    private val bank = config.energyBank
    private val scorer = EnergyBankScorer(config)

    private fun scored(outcome: ScoringOutcome) = (outcome as ScoringOutcome.Scored).score
    private fun unavailable(outcome: ScoringOutcome) = (outcome as ScoringOutcome.NotScored).unavailable

    private val ordinaryDay = EnergyBankInputs(
        date = "2026-03-03",
        previousLevel = 45.0,
        recoveryScore = 70,
        sleepScore = 90,
        dayStrain = 48,
    )

    @Test
    fun `the four phases add up to the closing level, exactly`() {
        // Unlike Recovery and Strain, these points are realised deltas rather than
        // marginals, so the UI can print carryover plus three numbers and the total.
        val trace = scorer.project(ordinaryDay)
        val score = scored(scorer.score(ordinaryDay))

        val sum = score.contributions.sumOf { it.points }
        sum shouldBe (trace.closing plusOrMinus 1e-9)
        score.value shouldBe trace.closing.roundToInt()
        score.type shouldBe ScoreType.ENERGY_BANK
    }

    @Test
    fun `the recharge blends Recovery and Sleep with the published-range result`() {
        val trace = scorer.project(ordinaryDay)
        // 0.55 x (0.6 x 70 + 0.4 x 90) = 42.9
        val expected = bank.overnightRechargeGain *
            (bank.recoveryShare * 70 + bank.sleepShare * 90)
        trace.requestedRecharge shouldBe (expected plusOrMinus 1e-9)
        // Garmin documents a good night adding 40-60 points to Body Battery
        // (grok.txt §2). Ours lands inside that without being tuned to it.
        trace.requestedRecharge shouldBeGreaterThan 40.0
        trace.requestedRecharge shouldBeLessThan 60.0
    }

    @Test
    fun `a red-recovery morning still charges something, because you did sleep`() {
        val rough = ordinaryDay.copy(recoveryScore = 15, sleepScore = 60)
        scorer.project(rough).rechargePoints shouldBeGreaterThan 0.0
    }

    @Test
    fun `a missing input renormalises the blend instead of scoring it as zero`() {
        val recoveryOnly = ordinaryDay.copy(sleepScore = null)
        scorer.project(recoveryOnly).requestedRecharge shouldBe
            (bank.overnightRechargeGain * 70 plusOrMinus 1e-9)

        val sleepOnly = ordinaryDay.copy(recoveryScore = null)
        scorer.project(sleepOnly).requestedRecharge shouldBe
            (bank.overnightRechargeGain * 90 plusOrMinus 1e-9)
    }

    @Test
    fun `the morning peak is the day's high point whenever the day cost anything`() {
        // Ordering is not cosmetic: recharge, then drain, then naps.
        val trace = scorer.project(ordinaryDay)
        trace.morningPeak shouldBeGreaterThan trace.carryover
        trace.morningPeak shouldBeGreaterThan trace.closing
        trace.drainPoints shouldBeLessThan 0.0
    }

    @Test
    fun `charging near the ceiling gets progressively harder`() {
        val fromMiddle = scorer.project(
            EnergyBankInputs("d", previousLevel = 50.0, recoveryScore = 95, sleepScore = 95, dayStrain = 0),
        )
        val fromHigh = scorer.project(
            EnergyBankInputs("d", previousLevel = 95.0, recoveryScore = 95, sleepScore = 95, dayStrain = 0),
        )

        // Same requested recharge, far less of it lands.
        fromHigh.requestedRecharge shouldBe (fromMiddle.requestedRecharge plusOrMinus 1e-9)
        fromHigh.rechargePoints shouldBeLessThan fromMiddle.rechargePoints
        fromHigh.closing shouldBeLessThan bank.displayCeiling + 1e-9
        // And the compression is real, not just a clamp at the top.
        fromMiddle.rechargePoints shouldBeLessThan fromMiddle.requestedRecharge
    }

    @Test
    fun `draining near the floor gets progressively harder, and never goes below it`() {
        val fromMiddle = scorer.project(
            EnergyBankInputs("d", previousLevel = 50.0, recoveryScore = null, sleepScore = 40, dayStrain = 100),
        )
        val fromLow = scorer.project(
            EnergyBankInputs("d", previousLevel = 8.0, recoveryScore = null, sleepScore = 40, dayStrain = 100),
        )

        abs(fromLow.drainPoints) shouldBeLessThan abs(fromMiddle.drainPoints)
        fromLow.closing shouldBeGreaterThan bank.displayFloor - 1e-9
    }

    @Test
    fun `the worst possible day cannot push the gauge below its floor`() {
        // Garmin's Body Battery floors at 5 rather than 0, and for the same reason:
        // a gauge reading empty implies a state the sensor cannot confirm
        // (grok.txt §2). The floor is an asymptote, not a clamp, so an awful day
        // lands just above it rather than exactly on it.
        val flattened = EnergyBankInputs(
            date = "2026-03-04",
            previousLevel = 6.0,
            recoveryScore = 5,
            sleepScore = 10,
            dayStrain = 100,
        )
        val trace = scorer.project(flattened)
        trace.closing shouldBeGreaterThan bank.displayFloor - 1e-9
        trace.closing shouldBeLessThan 12.0

        val score = scored(scorer.score(flattened))
        (score.value >= bank.displayFloor.roundToInt()) shouldBe true
        score.band shouldBe Band.LOW
    }

    @Test
    fun `the gauge cannot exceed a hundred however good the night was`() {
        val brilliant = EnergyBankInputs(
            date = "2026-03-05",
            previousLevel = 99.0,
            recoveryScore = 99,
            sleepScore = 100,
            dayStrain = 0,
        )
        val score = scored(scorer.score(brilliant))
        (score.value <= 100) shouldBe true
        score.band shouldBe Band.HIGH
    }

    @Test
    fun `a nap puts points back during the day`() {
        val noNap = scorer.project(ordinaryDay)
        val napped = scorer.project(ordinaryDay.copy(napMinutes = 120))

        napped.napPoints shouldBeGreaterThan 0.0
        napped.napPoints shouldBe (bank.napRechargePerHour * 2.0 plusOrMinus 1e-9)
        napped.closing shouldBeGreaterThan noNap.closing
    }

    @Test
    fun `day one is seeded and says so`() {
        val firstDay = ordinaryDay.copy(previousLevel = null)
        val score = scored(scorer.score(firstDay))

        score.warmingUp shouldBe true
        score.degraded shouldBe true

        val carryover = score.contributions.single { it.metric == Metric.CARRYOVER }
        carryover.present shouldBe false
        carryover.points shouldBe (bank.seedLevel plusOrMinus 1e-9)
        carryover.provenance shouldBe Provenance.OUR_CHOICE
    }

    @Test
    fun `a carried-over level is not our choice, it is yesterday's number`() {
        val carryover = scored(scorer.score(ordinaryDay)).contributions
            .single { it.metric == Metric.CARRYOVER }
        carryover.present shouldBe true
        carryover.provenance shouldBe Provenance.INFERRED
        carryover.points shouldBe (45.0 plusOrMinus 1e-9)
    }

    @Test
    fun `full inputs report full coverage`() {
        scored(scorer.score(ordinaryDay)).let {
            it.dataCoverage.toDouble() shouldBe (1.0 plusOrMinus 1e-6)
            it.degraded shouldBe false
        }
    }

    @Test
    fun `a morning with only a Recovery score is the thinnest thing we will still score`() {
        val thin = EnergyBankInputs(date = "2026-03-06", recoveryScore = 70)
        val score = scored(scorer.score(thin))
        // 0.5 x (0.6 / 1.0) = 0.30, exactly the refusal threshold.
        score.dataCoverage.toDouble() shouldBe (0.30 plusOrMinus 1e-6)
        score.degraded shouldBe true
        score.warmingUp shouldBe true
    }

    @Test
    fun `yesterday's number alone is not enough to publish a new one`() {
        val stale = EnergyBankInputs(date = "2026-03-07", previousLevel = 60.0)
        val reason = unavailable(scorer.score(stale))
        reason.reason shouldBe ScoreUnavailable.Reason.COVERAGE_TOO_LOW
        reason.missing.contains(Metric.OVERNIGHT_RECHARGE) shouldBe true
        reason.missing.contains(Metric.STRAIN_DRAIN) shouldBe true
    }

    @Test
    fun `a day with nothing at all says so specifically`() {
        val nothing = EnergyBankInputs(date = "2026-03-08")
        unavailable(scorer.score(nothing)).reason shouldBe ScoreUnavailable.Reason.NO_REQUIRED_INPUT
    }

    @Test
    fun `a missing strain drains nothing rather than guessing a day`() {
        val noStrain = ordinaryDay.copy(dayStrain = null)
        val trace = scorer.project(noStrain)
        trace.requestedDrain shouldBe (0.0 plusOrMinus 1e-12)
        trace.closing shouldBe (trace.morningPeak plusOrMinus 1e-9)

        val score = scored(scorer.score(noStrain))
        score.contributions.single { it.metric == Metric.STRAIN_DRAIN }.present shouldBe false
        // And the loss of that input shows up in coverage rather than being hidden.
        score.dataCoverage.toDouble() shouldBeLessThan 1.0
        // A gauge that charged but never watched the day being spent is degraded.
        score.degraded shouldBe true
    }

    @Test
    fun `the integral carries across days rather than resetting at midnight`() {
        // This is what makes it a battery instead of a daily score (grok.txt §2).
        var level: Double? = null
        val closes = (1..4).map { day ->
            val trace = scorer.project(
                EnergyBankInputs(
                    date = "2026-03-1$day",
                    previousLevel = level,
                    recoveryScore = 40,
                    sleepScore = 70,
                    dayStrain = 55,
                ),
            )
            level = trace.closing
            trace.closing
        }
        // Charging less than the day costs, four days running, walks the gauge down.
        // The steps shrink as the floor's resistance takes hold, which is why this
        // asserts non-increasing rather than a fixed decrement.
        closes.zipWithNext().forEach { (earlier, later) ->
            later shouldBeLessThan earlier + 1e-9
        }
        closes.last() shouldBeLessThan closes.first() - 10.0
    }

    @Test
    fun `the default step count is close to the continuous solution`() {
        // Forward Euler, so the error falls as one over the step count. If the
        // default ever drifts far from a very fine integration, the compression
        // curve has stopped meaning what its config says it means.
        val fine = EnergyBankScorer(config.copy(energyBank = bank.copy(integrationSteps = 8000)))
        val near = EnergyBankInputs("d", previousLevel = 80.0, recoveryScore = 95, sleepScore = 95, dayStrain = 0)

        abs(scorer.project(near).closing - fine.project(near).closing) shouldBeLessThan 1.0
    }

    @Test
    fun `a single jump would overshoot the ceiling, which is why it is integrated`() {
        // One step means the whole delta is scaled by the resistance at the
        // starting level, so a gauge at 80 sails straight past the soft ceiling.
        val oneStep = EnergyBankScorer(config.copy(energyBank = bank.copy(integrationSteps = 1)))
        val near = EnergyBankInputs("d", previousLevel = 80.0, recoveryScore = 95, sleepScore = 95, dayStrain = 0)

        oneStep.project(near).closing shouldBeGreaterThan scorer.project(near).closing
    }

    @Test
    fun `the score carries the version and config hash that produced it`() {
        val score = scored(scorer.score(ordinaryDay))
        score.scoringVersion shouldBe ScoringConfig.SCORING_VERSION
        score.configHash shouldBe config.configHash
    }
}
