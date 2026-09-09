package app.pawse.scoring

import app.pawse.scoring.config.ScoringConfig
import app.pawse.scoring.energy.EnergyBankInputs
import app.pawse.scoring.energy.EnergyBankScorer
import app.pawse.scoring.fixtures.Fixtures
import app.pawse.scoring.load.LoadRatioScorer
import app.pawse.scoring.model.Band
import app.pawse.scoring.model.DailyLoad
import app.pawse.scoring.model.DayActivity
import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.Score
import app.pawse.scoring.model.ScoringOutcome
import app.pawse.scoring.recovery.RecoveryScorer
import app.pawse.scoring.sleep.SleepScorer
import app.pawse.scoring.strain.StrainScorer
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * The load half of the engine, run as the nightly job runs it.
 *
 * Order matters and is a real dependency chain, not an implementation detail:
 * Sleep feeds Recovery, Sleep and Recovery feed the Energy Bank's overnight
 * recharge, Strain feeds the Bank's drain, and the raw daily loads that Strain
 * saturates feed the Load Ratio. Four of the five scores in the app cannot be
 * computed in any other sequence.
 *
 * The two dates used here line up with the night fixtures on purpose: a good night
 * followed by a rest day, then the alcohol night followed by a hard session. That
 * pairing is the case worth testing, because it is the one where the gauge has to
 * do something a single-day score cannot.
 */
class LoadPipelineTest {

    private val config = ScoringConfig.DEFAULT
    private val sleepScorer = SleepScorer(config)
    private val recoveryScorer = RecoveryScorer(config)
    private val strainScorer = StrainScorer(config)
    private val bankScorer = EnergyBankScorer(config)
    private val ratioScorer = LoadRatioScorer(config)

    private fun scored(outcome: ScoringOutcome): Score = (outcome as ScoringOutcome.Scored).score

    /** One day, end to end. Returns sleep, recovery, strain and the bank. */
    private fun runDay(
        night: Fixtures.Night,
        activity: DayActivity,
        previousBankLevel: Double?,
    ): Quad {
        val sleep = scored(sleepScorer.score(night.inputs))
        val withSleep = night.inputs.copy(
            values = night.inputs.values + (Metric.SLEEP_SCORE to sleep.value.toDouble()),
        )
        val recovery = scored(recoveryScorer.score(withSleep, night.histories))
        val strain = scored(strainScorer.score(activity, Fixtures.profile))
        val bank = scored(
            bankScorer.score(
                EnergyBankInputs(
                    date = night.inputs.date,
                    previousLevel = previousBankLevel,
                    recoveryScore = recovery.value,
                    sleepScore = sleep.value,
                    dayStrain = strain.value,
                ),
            ),
        )
        return Quad(sleep, recovery, strain, bank)
    }

    private data class Quad(val sleep: Score, val recovery: Score, val strain: Score, val bank: Score)

    @Test
    fun `a good night and a rest day fill the bank, then a bad night and a hard session empty it`() {
        val dayOne = runDay(Fixtures.typicalGoodNight, Fixtures.restDay, previousBankLevel = null)
        val dayTwo = runDay(
            Fixtures.alcoholNight,
            Fixtures.hardSessionDay,
            previousBankLevel = dayOne.bank.value.toDouble(),
        )

        // Day one: charged well, spent little.
        dayOne.bank.value shouldBeGreaterThanInt 50
        dayOne.strain.band shouldBe Band.LOW

        // Day two: charged badly on a red recovery, spent a lot.
        dayTwo.recovery.band shouldBe Band.LOW
        dayTwo.strain.value shouldBeGreaterThanInt dayOne.strain.value
        dayTwo.bank.value shouldBeLessThanInt dayOne.bank.value
    }

    @Test
    fun `the bank charges less on the alcohol night than the good one, for the same sleep hours`() {
        // The night the reports single out. Duration was almost identical; the
        // autonomic signals were not, and the gauge has to notice.
        val good = scored(sleepScorer.score(Fixtures.typicalGoodNight.inputs))
        val rough = scored(sleepScorer.score(Fixtures.alcoholNight.inputs))

        val goodRecovery = scored(
            recoveryScorer.score(
                Fixtures.typicalGoodNight.inputs.copy(
                    values = Fixtures.typicalGoodNight.inputs.values +
                        (Metric.SLEEP_SCORE to good.value.toDouble()),
                ),
                Fixtures.typicalGoodNight.histories,
            ),
        )
        val roughRecovery = scored(
            recoveryScorer.score(
                Fixtures.alcoholNight.inputs.copy(
                    values = Fixtures.alcoholNight.inputs.values +
                        (Metric.SLEEP_SCORE to rough.value.toDouble()),
                ),
                Fixtures.alcoholNight.histories,
            ),
        )

        val goodCharge = bankScorer.project(
            EnergyBankInputs("a", previousLevel = 40.0, recoveryScore = goodRecovery.value, sleepScore = good.value),
        )
        val roughCharge = bankScorer.project(
            EnergyBankInputs("b", previousLevel = 40.0, recoveryScore = roughRecovery.value, sleepScore = rough.value),
        )

        roughCharge.rechargePoints shouldBeLessThan goodCharge.rechargePoints
        // But it is not zero. You did sleep, and the gauge is not a punishment.
        roughCharge.rechargePoints shouldBeGreaterThan 0.0
    }

    @Test
    fun `the ratio consumes raw load, not saturated strain`() {
        // Two saturated strains of 48 look like a doubling that is not there. The
        // acute:chronic ratio has to see the load underneath, or a hard week reads
        // as flat because the curve already flattened it.
        val rest = strainScorer.dayLoad(Fixtures.restDay, Fixtures.profile)
        val hard = strainScorer.dayLoad(Fixtures.hardSessionDay, Fixtures.profile)
        val double = strainScorer.dayLoad(Fixtures.doubleSessionDay, Fixtures.profile)

        // Load is additive in the workouts, unlike the strain built from it.
        (double - rest) shouldBeGreaterThan 1.9 * (hard - rest)

        val strainHard = scored(strainScorer.score(Fixtures.hardSessionDay, Fixtures.profile)).value
        val strainDouble = scored(strainScorer.score(Fixtures.doubleSessionDay, Fixtures.profile)).value
        (strainDouble.toDouble()) shouldBeLessThan 1.9 * strainHard
    }

    @Test
    fun `a hard week on top of a steady month flags the ramp, and the caveat travels with it`() {
        val steadyLoad = strainScorer.dayLoad(Fixtures.restDay, Fixtures.profile)
        val hardLoad = strainScorer.dayLoad(Fixtures.doubleSessionDay, Fixtures.profile)

        val loads = (0 until 60).map {
            DailyLoad(daysAgo = it, load = if (it < 7) hardLoad else steadyLoad)
        }
        val score = scored(ratioScorer.score("2026-03-10", loads))

        (score.value > 130) shouldBe true
        score.contributions.forEach {
            it.citation.contains("not an injury predictor") shouldBe true
        }
    }

    @Test
    fun `every load score records the config that produced it`() {
        val day = runDay(Fixtures.typicalGoodNight, Fixtures.hardSessionDay, previousBankLevel = 60.0)
        listOf(day.sleep, day.recovery, day.strain, day.bank).forEach {
            it.configHash shouldBe config.configHash
            it.scoringVersion shouldBe ScoringConfig.SCORING_VERSION
            (it.dataCoverage in 0f..1f) shouldBe true
        }
    }

    @Test
    fun `every contribution across all five score types carries a citation`() {
        val day = runDay(Fixtures.typicalGoodNight, Fixtures.hardSessionDay, previousBankLevel = 60.0)
        val ratio = scored(ratioScorer.score("2026-03-10", Fixtures.steadyLoads()))
        listOf(day.sleep, day.recovery, day.strain, day.bank, ratio).forEach { score ->
            score.contributions.forEach { it.citation.isNotBlank() shouldBe true }
        }
    }

    private infix fun Int.shouldBeGreaterThanInt(other: Int) {
        check(this > other) { "expected $this > $other" }
    }

    private infix fun Int.shouldBeLessThanInt(other: Int) {
        check(this < other) { "expected $this < $other" }
    }
}
