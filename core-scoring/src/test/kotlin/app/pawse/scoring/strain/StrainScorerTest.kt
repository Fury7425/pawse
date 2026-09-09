package app.pawse.scoring.strain

import app.pawse.scoring.config.ScoringConfig
import app.pawse.scoring.config.StrainScale
import app.pawse.scoring.config.TrimpModel
import app.pawse.scoring.fixtures.Fixtures
import app.pawse.scoring.model.Band
import app.pawse.scoring.model.BiologicalSex
import app.pawse.scoring.model.DayActivity
import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.Provenance
import app.pawse.scoring.model.ScoreType
import app.pawse.scoring.model.ScoreUnavailable
import app.pawse.scoring.model.ScoringOutcome
import app.pawse.scoring.model.UserProfile
import app.pawse.scoring.model.WorkoutSample
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class StrainScorerTest {

    private val config = ScoringConfig.DEFAULT
    private val scorer = StrainScorer(config)
    private val profile = Fixtures.profile

    private fun scored(outcome: ScoringOutcome) = (outcome as ScoringOutcome.Scored).score
    private fun unavailable(outcome: ScoringOutcome) = (outcome as ScoringOutcome.NotScored).unavailable

    /** The whole chain recomputed independently: Tanaka, reserve, Banister, saturation. */
    private fun expectedStrain(day: DayActivity, profile: UserProfile): Double {
        val s = config.strain
        val maxHr = profile.measuredMaxHeartRate
            ?: (s.tanakaIntercept - s.tanakaSlope * profile.ageYears!!)
        val restingHr = profile.restingHeartRate!!
        val workoutLoad = day.workouts.sumOf { w ->
            val x = ((w.meanHeartRate!! - restingHr) / (maxHr - restingHr)).coerceIn(0.0, 1.0)
            Trimp.banister(w.durationMinutes, x, profile.biologicalSex, s).load
        }
        val passive = s.passiveLoadPerWakingHour * day.wakingHours
        return Trimp.saturate(workoutLoad + passive, s)
    }

    @Test
    fun `one hard hour, computed end to end from the published equations`() {
        val score = scored(scorer.score(Fixtures.hardSessionDay, profile))
        score.type shouldBe ScoreType.STRAIN
        score.value shouldBe expectedStrain(Fixtures.hardSessionDay, profile).roundToInt()
        score.dataCoverage.toDouble() shouldBe (1.0 plusOrMinus 1e-6)
        score.degraded shouldBe false
        // Strain is absolute, so it never warms up.
        score.warmingUp shouldBe false
    }

    @Test
    fun `two identical sessions score well under twice one, with no special case`() {
        // grok.txt §3 and gemini.txt both make this point about Whoop's scale: a
        // Strain of 10 plus a Strain of 5 is not 15. It falls out of the curve's
        // concavity; there is no code anywhere that checks for a second workout.
        val single = scored(scorer.score(Fixtures.hardSessionDay, profile)).value
        val double = scored(scorer.score(Fixtures.doubleSessionDay, profile)).value

        double shouldBeGreaterThanInt single
        (double < 2 * single) shouldBe true
    }

    @Test
    fun `a session's strain if taken alone is less than the day it sat in`() {
        // The honest way to answer "why did my second ride add so little".
        val day = scored(scorer.score(Fixtures.doubleSessionDay, profile)).value
        scorer.sessionLoads(Fixtures.doubleSessionDay, profile).forEach {
            it.strainIfAlone shouldBeLessThan day.toDouble()
        }
    }

    @Test
    fun `a rest day is passive load only, and that is not zero`() {
        val score = scored(scorer.score(Fixtures.restDay, profile))
        score.value shouldBeGreaterThanInt 0
        score.band shouldBe Band.LOW
        score.dataCoverage.toDouble() shouldBe (1.0 plusOrMinus 1e-6)

        val workout = score.contributions.single { it.metric == Metric.WORKOUT_LOAD }
        val passive = score.contributions.single { it.metric == Metric.PASSIVE_LOAD }
        workout.present shouldBe false
        passive.present shouldBe true
        passive.raw!! shouldBe (config.strain.passiveLoadPerWakingHour * 16.0 plusOrMinus 1e-9)
    }

    @Test
    fun `turning the passive term off makes a rest day score exactly zero`() {
        val pure = StrainScorer(config.copy(strain = config.strain.copy(passiveLoadPerWakingHour = 0.0)))
        scored(pure.score(Fixtures.restDay, profile)).value shouldBe 0
    }

    @Test
    fun `points are marginal, so they do not add up`() {
        val score = scored(scorer.score(Fixtures.doubleSessionDay, profile))
        val sum = score.contributions.sumOf { it.points }
        abs(sum - score.value) shouldBeGreaterThan 1.0
        // But each is signed the right way and bounded by the total.
        score.contributions.forEach {
            it.points shouldBeGreaterThan -1e-9
            it.points shouldBeLessThan score.value + 1.0
        }
    }

    @Test
    fun `a day half of which had no heart rate is refused, not silently halved`() {
        // Sixty of a hundred and fifty logged minutes scored: printing the
        // remaining day as if the ride had not happened would be worse than
        // printing nothing.
        val reason = unavailable(scorer.score(Fixtures.heartRatelessDay, profile))
        reason.reason shouldBe ScoreUnavailable.Reason.COVERAGE_TOO_LOW
        reason.type shouldBe ScoreType.STRAIN
        reason.missing shouldBe listOf(Metric.WORKOUT_LOAD)
    }

    @Test
    fun `a mostly-covered day scores and says it is degraded`() {
        val day = DayActivity(
            date = "2026-03-07",
            workouts = listOf(
                WorkoutSample("a", 60.0, 150.0, 176.0),
                WorkoutSample("b", 20.0, null, null),
            ),
            wakingHours = 16.0,
        )
        val score = scored(scorer.score(day, profile))
        score.dataCoverage.toDouble() shouldBe (0.75 plusOrMinus 1e-6)
        score.degraded shouldBe true
    }

    @Test
    fun `a session too short to mean anything is dropped with a reason`() {
        val day = DayActivity(
            date = "2026-03-08",
            workouts = listOf(WorkoutSample("blip", 1.0, 150.0, 160.0)),
            wakingHours = 16.0,
        )
        val session = scorer.sessionLoads(day, profile).single()
        session.scored shouldBe false
        (session.skippedReason != null) shouldBe true
        session.load shouldBe (0.0 plusOrMinus 1e-12)
    }

    @Test
    fun `a profile with nothing filled in cannot use Banister at all`() {
        val session = scorer.sessionLoads(Fixtures.hardSessionDay, Fixtures.anonymousProfile).single()
        session.scored shouldBe false
        // The message names what to go and enter, rather than saying "no data".
        (session.skippedReason!!.contains("resting heart rate")) shouldBe true
        (session.skippedReason!!.contains("age")) shouldBe true
    }

    @Test
    fun `a baseline resting heart rate stands in when the profile has none`() {
        val noResting = profile.copy(restingHeartRate = null)
        val without = scorer.sessionLoads(Fixtures.hardSessionDay, noResting).single()
        val with = scorer.sessionLoads(Fixtures.hardSessionDay, noResting, restingHeartRateBaseline = 52.0).single()

        without.scored shouldBe false
        with.scored shouldBe true
        with.load shouldBe (
            scorer.sessionLoads(Fixtures.hardSessionDay, profile).single().load plusOrMinus 1e-9
            )
    }

    @Test
    fun `Edwards rescues a user who never told us their age, when zone data exists`() {
        // Sex-neutral and HRmax-free. This is why the alternative model is in the
        // app: it needs more data from the watch and less from the user.
        val session = scorer.sessionLoads(Fixtures.zonedDay, Fixtures.anonymousProfile).single()
        session.scored shouldBe true
        session.load shouldBe (163.0 plusOrMinus 1e-9)
        session.provenance shouldBe Provenance.PUBLISHED
        session.reserveFraction shouldBe null
    }

    @Test
    fun `Banister still wins by default when both models could run`() {
        val session = scorer.sessionLoads(Fixtures.zonedDay, profile).single()
        session.reserveFraction shouldBe (98.0 / 131.5 plusOrMinus 1e-9)

        val edwardsFirst = StrainScorer(config.copy(strain = config.strain.copy(trimpModel = TrimpModel.EDWARDS)))
        edwardsFirst.sessionLoads(Fixtures.zonedDay, profile).single().load shouldBe (163.0 plusOrMinus 1e-9)
    }

    @Test
    fun `an estimated HRmax downgrades the day's provenance to inferred`() {
        // The user gave an age, not a measured maximum, so the load rests on a
        // population regression and the aggregate must say so.
        val score = scored(scorer.score(Fixtures.hardSessionDay, profile))
        score.contributions.single { it.metric == Metric.WORKOUT_LOAD }
            .provenance shouldBe Provenance.INFERRED

        val measured = profile.copy(measuredMaxHeartRate = 186.0)
        scored(scorer.score(Fixtures.hardSessionDay, measured)).contributions
            .single { it.metric == Metric.WORKOUT_LOAD }.provenance shouldBe Provenance.PUBLISHED
    }

    @Test
    fun `an unspecified sex downgrades the day's provenance to our own choice`() {
        val unknown = profile.copy(
            biologicalSex = BiologicalSex.UNSPECIFIED,
            measuredMaxHeartRate = 186.0,
        )
        scored(scorer.score(Fixtures.hardSessionDay, unknown)).contributions
            .single { it.metric == Metric.WORKOUT_LOAD }.provenance shouldBe Provenance.OUR_CHOICE
    }

    @Test
    fun `the scale is a display toggle and never changes the stored number`() {
        val bevel = StrainScorer(config.copy(strain = config.strain.copy(scale = StrainScale.BEVEL_100)))
        val whoop = StrainScorer(config.copy(strain = config.strain.copy(scale = StrainScale.WHOOP_21)))

        val onBevel = scored(bevel.score(Fixtures.hardSessionDay, profile))
        val onWhoop = scored(whoop.score(Fixtures.hardSessionDay, profile))
        // Same canonical value. Only the label moves, so a history chart does not
        // change shape when a user flips the toggle.
        onWhoop.value shouldBe onBevel.value

        scorer.displayValue(onBevel.value, StrainScale.BEVEL_100) shouldBe
            (onBevel.value.toDouble() plusOrMinus 1e-9)
        scorer.displayValue(onBevel.value, StrainScale.WHOOP_21) shouldBe
            (onBevel.value * 21.0 / 100.0 plusOrMinus 1e-9)
    }

    @Test
    fun `strain bands are Whoop's zone names rescaled, and are magnitude not verdict`() {
        Band.ofStrainMagnitude(0) shouldBe Band.LOW
        Band.ofStrainMagnitude(43) shouldBe Band.LOW
        Band.ofStrainMagnitude(44) shouldBe Band.MODERATE
        Band.ofStrainMagnitude(66) shouldBe Band.MODERATE
        Band.ofStrainMagnitude(67) shouldBe Band.HIGH
        Band.ofStrainMagnitude(100) shouldBe Band.HIGH
    }

    @Test
    fun `Target Strain is two weeks of typical strain, moved by Recovery`() {
        val recent = List(14) { 40.0 }

        val neutral = scorer.target(recent, recoveryScore = 50)
        neutral.recoveryFactor shouldBe (1.0 plusOrMinus 1e-12)
        neutral.target shouldBe (40.0 plusOrMinus 1e-9)

        val green = scorer.target(recent, recoveryScore = 90)
        green.recoveryFactor shouldBe (1.0 + config.strain.targetStrainRecoveryAdjust * 0.8 plusOrMinus 1e-12)
        green.target shouldBeGreaterThan neutral.target

        val red = scorer.target(recent, recoveryScore = 15)
        red.target shouldBeLessThan neutral.target
    }

    @Test
    fun `Target Strain without a Recovery score does not invent one`() {
        val target = scorer.target(List(14) { 40.0 }, recoveryScore = null)
        target.recoveryFactor shouldBe (1.0 plusOrMinus 1e-12)
        target.target shouldBe (40.0 plusOrMinus 1e-9)
    }

    @Test
    fun `Target Strain looks no further back than its window`() {
        val recent = List(30) { if (it < 14) 40.0 else 100.0 }
        val target = scorer.target(recent, recoveryScore = 50)
        target.sampleCount shouldBe config.strain.targetStrainWindowDays
        target.typicalStrain shouldBe (40.0 plusOrMinus 1e-9)
    }

    @Test
    fun `day load is what the acute-chronic ratio consumes`() {
        val load = scorer.dayLoad(Fixtures.hardSessionDay, profile)
        val passive = config.strain.passiveLoadPerWakingHour * 16.0
        load shouldBeGreaterThan passive
        // And it is a load in TRIMP units, not a saturated strain.
        load shouldBeGreaterThan 100.0
    }

    @Test
    fun `a day with no activity and no waking hours has nothing to score`() {
        val nothing = DayActivity(date = "2026-03-09", workouts = emptyList(), wakingHours = 0.0)
        unavailable(scorer.score(nothing, profile)).reason shouldBe
            ScoreUnavailable.Reason.NO_REQUIRED_INPUT
    }

    @Test
    fun `the score carries the version and config hash that produced it`() {
        val score = scored(scorer.score(Fixtures.hardSessionDay, profile))
        score.scoringVersion shouldBe ScoringConfig.SCORING_VERSION
        score.configHash shouldBe config.configHash
    }

    private infix fun Int.shouldBeGreaterThanInt(other: Int) {
        check(this > other) { "expected $this > $other" }
    }
}
