package app.pawse.scoring.strain

import app.pawse.scoring.config.StrainConfig
import app.pawse.scoring.model.BiologicalSex
import app.pawse.scoring.model.Provenance
import app.pawse.scoring.model.UserProfile
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test
import kotlin.math.exp

class TrimpTest {

    private val config = StrainConfig()

    /** Banister 1991, recomputed independently of the implementation. */
    private fun banisterReference(duration: Double, x: Double, a: Double, b: Double) =
        duration * x * (a * exp(b * x))

    @Test
    fun `Banister's male curve is the published one`() {
        val result = Trimp.banister(60.0, 0.70, BiologicalSex.MALE, config)
        result.load shouldBe (banisterReference(60.0, 0.70, 0.64, 1.92) plusOrMinus 1e-9)
        result.provenance shouldBe Provenance.PUBLISHED
    }

    @Test
    fun `Banister's female curve is the published one`() {
        val result = Trimp.banister(60.0, 0.70, BiologicalSex.FEMALE, config)
        result.load shouldBe (banisterReference(60.0, 0.70, 0.86, 1.67) plusOrMinus 1e-9)
        result.provenance shouldBe Provenance.PUBLISHED
    }

    @Test
    fun `an unspecified sex takes the midpoint and admits it is not published`() {
        val male = Trimp.banister(60.0, 0.70, BiologicalSex.MALE, config).load
        val female = Trimp.banister(60.0, 0.70, BiologicalSex.FEMALE, config).load
        val unspecified = Trimp.banister(60.0, 0.70, BiologicalSex.UNSPECIFIED, config)

        unspecified.load shouldBe ((male + female) / 2.0 plusOrMinus 1e-9)
        // The important half of this test: the midpoint of two published curves is
        // not itself published, and must never be labelled as if it were.
        unspecified.provenance shouldBe Provenance.OUR_CHOICE
    }

    @Test
    fun `the gap between the two published curves narrows as intensity rises`() {
        // This is the documented cost of not knowing the user's sex. If the numbers
        // move, the doc comment on Trimp.banister is wrong and should be corrected
        // rather than the assertion loosened.
        fun gap(x: Double): Double {
            val male = Trimp.banister(60.0, x, BiologicalSex.MALE, config).load
            val female = Trimp.banister(60.0, x, BiologicalSex.FEMALE, config).load
            return kotlin.math.abs(male - female) / maxOf(male, female)
        }
        gap(0.4) shouldBeLessThan 0.20
        gap(0.4) shouldBeGreaterThan 0.15
        gap(1.0) shouldBeLessThan 0.05
        // Monotone narrowing across the range.
        listOf(0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0).zipWithNext().forEach { (lower, higher) ->
            gap(higher) shouldBeLessThan gap(lower)
        }
    }

    @Test
    fun `load rises with duration and with intensity, and intensity rises faster`() {
        val oneHourEasy = Trimp.banister(60.0, 0.50, BiologicalSex.MALE, config).load
        val twoHoursEasy = Trimp.banister(120.0, 0.50, BiologicalSex.MALE, config).load
        val oneHourHard = Trimp.banister(60.0, 0.90, BiologicalSex.MALE, config).load

        // Duration is linear.
        twoHoursEasy shouldBe (2.0 * oneHourEasy plusOrMinus 1e-9)
        // Intensity is not: this is the exponential's whole job, stopping three
        // easy hours from outweighing forty hard minutes.
        (oneHourHard / oneHourEasy) shouldBeGreaterThan 2.0
    }

    @Test
    fun `zero intensity is zero load`() {
        Trimp.banister(90.0, 0.0, BiologicalSex.MALE, config).load shouldBe (0.0 plusOrMinus 1e-12)
    }

    @Test
    fun `Edwards sums minutes times the published zone multipliers`() {
        val zones = mapOf(1 to 10.0, 2 to 15.0, 3 to 20.0, 4 to 12.0, 5 to 3.0)
        val result = Trimp.edwards(zones, config)
        // 10 + 30 + 60 + 48 + 15
        result.load shouldBe (163.0 plusOrMinus 1e-9)
        result.provenance shouldBe Provenance.PUBLISHED
    }

    @Test
    fun `Edwards ignores zones outside one to five and negative minutes`() {
        Trimp.edwards(mapOf(0 to 500.0, 9 to 500.0), config).load shouldBe (0.0 plusOrMinus 1e-12)
        Trimp.edwards(mapOf(3 to -20.0), config).load shouldBe (0.0 plusOrMinus 1e-12)
    }

    @Test
    fun `saturation is concave, which is where non-additivity comes from`() {
        val one = Trimp.saturate(100.0, config)
        val two = Trimp.saturate(200.0, config)
        // Two identical efforts score less than twice one. grok.txt §3: a Strain of
        // 10 plus a Strain of 5 is not 15.
        two shouldBeLessThan 2.0 * one
        two shouldBeGreaterThan one
    }

    @Test
    fun `saturation is bounded and starts at zero`() {
        Trimp.saturate(0.0, config) shouldBe (0.0 plusOrMinus 1e-12)
        Trimp.saturate(-500.0, config) shouldBe (0.0 plusOrMinus 1e-12)
        Trimp.saturate(1e9, config) shouldBe (Trimp.CANONICAL_MAX plusOrMinus 1e-9)
    }

    @Test
    fun `at one time constant the curve is at one minus one over e`() {
        Trimp.saturate(config.saturationK, config) shouldBe
            (100.0 * (1.0 - exp(-1.0)) plusOrMinus 1e-9)
    }

    @Test
    fun `the inverse of the saturation curve round-trips`() {
        listOf(30.0, 120.0, 260.0, 600.0, 1500.0).forEach { load ->
            val strain = Trimp.saturate(load, config)
            Trimp.loadForStrain(strain, config) shouldBe (load plusOrMinus 1e-6)
        }
    }

    @Test
    fun `Tanaka is the published two-hundred-and-eight minus zero-point-seven-times-age`() {
        HeartRateModel.tanakaMax(35, config) shouldBe (183.5 plusOrMinus 1e-9)
        HeartRateModel.tanakaMax(20, config) shouldBe (194.0 plusOrMinus 1e-9)
    }

    @Test
    fun `a measured maximum beats the estimate and is not flagged as estimated`() {
        val measured = HeartRateModel.maxHeartRate(
            UserProfile(ageYears = 35, measuredMaxHeartRate = 196.0),
            config,
        )!!
        measured.bpm shouldBe (196.0 plusOrMinus 1e-9)
        measured.estimated shouldBe false
    }

    @Test
    fun `without an age or a measured maximum there is no HRmax and no guess`() {
        HeartRateModel.maxHeartRate(UserProfile(), config).shouldBeNull()
    }

    @Test
    fun `an observed peak above the estimate raises it, and below it does not lower it`() {
        val raised = HeartRateModel.maxHeartRate(
            UserProfile(ageYears = 35),
            config,
            observedMaxHeartRate = 191.0,
        )!!
        raised.bpm shouldBe (191.0 plusOrMinus 1e-9)
        raised.provenance shouldBe Provenance.INFERRED

        // Not having gone maximal is not evidence of a low ceiling.
        val unchanged = HeartRateModel.maxHeartRate(
            UserProfile(ageYears = 35),
            config,
            observedMaxHeartRate = 150.0,
        )!!
        unchanged.bpm shouldBe (183.5 plusOrMinus 1e-9)
    }

    @Test
    fun `heart-rate reserve is the published fraction, clamped at both ends`() {
        HeartRateModel.reserveFraction(150.0, 50.0, 190.0)!! shouldBe (100.0 / 140.0 plusOrMinus 1e-9)
        // A mean below resting is a sensor problem, not a negative workout.
        HeartRateModel.reserveFraction(40.0, 50.0, 190.0)!! shouldBe (0.0 plusOrMinus 1e-12)
        // A mean above maximum means the maximum is wrong, not that x exceeds one.
        HeartRateModel.reserveFraction(200.0, 50.0, 190.0)!! shouldBe (1.0 plusOrMinus 1e-12)
        // A non-positive reserve has no fraction at all.
        HeartRateModel.reserveFraction(150.0, 200.0, 190.0).shouldBeNull()
    }

    @Test
    fun `Edwards zones follow the published percent-of-HRmax bands`() {
        val max = 190.0
        HeartRateModel.edwardsZone(90.0, max) shouldBe 0
        HeartRateModel.edwardsZone(100.0, max) shouldBe 1
        HeartRateModel.edwardsZone(120.0, max) shouldBe 2
        HeartRateModel.edwardsZone(140.0, max) shouldBe 3
        HeartRateModel.edwardsZone(160.0, max) shouldBe 4
        HeartRateModel.edwardsZone(180.0, max) shouldBe 5
        HeartRateModel.edwardsZone(200.0, max) shouldBe 5
        HeartRateModel.edwardsZone(150.0, 0.0) shouldBe 0
    }
}
