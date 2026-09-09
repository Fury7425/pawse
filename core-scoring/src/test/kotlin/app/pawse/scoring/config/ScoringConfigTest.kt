package app.pawse.scoring.config

import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.Provenance
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import kotlin.math.abs

class ScoringConfigTest {

    @Test
    fun `recovery continuous weights sum to one`() {
        val sum = ScoringConfig.DEFAULT.recovery.weights.values.sumOf { it.weight }
        // SpO2 and temperature are anomaly flags with no continuous weight, so the
        // continuous terms must still form a complete unit of weight mass.
        check(abs(sum - 1.0) < 1e-9) { "recovery weights sum to $sum, expected 1.0" }
    }

    @Test
    fun `HRV dominates recovery and sits in the range the reports support`() {
        val hrv = ScoringConfig.DEFAULT.recovery.weights.getValue(Metric.HRV_RMSSD).weight
        check(hrv in 0.5..0.8) { "HRV weight $hrv outside the 0.5-0.8 range from grok.txt §11" }
        val rhr = ScoringConfig.DEFAULT.recovery.weights.getValue(Metric.RESTING_HEART_RATE).weight
        hrv shouldBeGreaterThan rhr
    }

    @Test
    fun `every recovery weight is tagged OUR_CHOICE, never presented as a vendor formula`() {
        ScoringConfig.DEFAULT.recovery.weights.forEach { (metric, w) ->
            check(w.provenance == Provenance.OUR_CHOICE) {
                "$metric is tagged ${w.provenance}; no vendor publishes recovery weights"
            }
            check(w.citation.isNotBlank()) { "$metric has no citation" }
        }
    }

    @Test
    fun `oura sleep weights are RECOVERED and sum to one`() {
        val weights = ScoringConfig.DEFAULT.sleep.ouraWeights
        check(abs(weights.values.sumOf { it.weight } - 1.0) < 1e-9)
        weights.values.forEach { it.provenance shouldBe Provenance.RECOVERED }
    }

    @Test
    fun `apple sleep weights are PUBLISHED and match the 50-30-20 split`() {
        val w = ScoringConfig.DEFAULT.sleep.appleWeights
        w.getValue(Metric.TIME_ASLEEP).weight shouldBe 0.50
        w.getValue(Metric.BEDTIME_CONSISTENCY).weight shouldBe 0.30
        w.getValue(Metric.SLEEP_INTERRUPTIONS).weight shouldBe 0.20
        w.values.forEach { it.provenance shouldBe Provenance.PUBLISHED }
    }

    @Test
    fun `banister coefficients are the published sex-specific pair`() {
        val s = ScoringConfig.DEFAULT.strain
        s.banisterMaleA shouldBe 0.64
        s.banisterMaleB shouldBe 1.92
        s.banisterFemaleA shouldBe 0.86
        s.banisterFemaleB shouldBe 1.67
    }

    @Test
    fun `config hash is stable across round trips and changes when a weight changes`() {
        val a = ScoringConfig.DEFAULT
        val b = ScoringConfig.fromJson(ScoringConfig.toJson(a))
        b.configHash shouldBe a.configHash

        val tuned = a.copy(
            recovery = a.recovery.copy(
                weights = a.recovery.weights + (Metric.HRV_RMSSD to
                    a.recovery.weights.getValue(Metric.HRV_RMSSD).copy(weight = 0.55)),
            ),
        )
        tuned.configHash shouldNotBe a.configHash
    }

    @Test
    fun `baseline window is constrained to the range the reports cover`() {
        BaselineConfig(windowDays = 60)
        runCatching { BaselineConfig(windowDays = 7) }.isFailure shouldBe true
        runCatching { BaselineConfig(windowDays = 120) }.isFailure shouldBe true
    }

    @Test
    fun `bevel sleep weights sum to one and are tagged as ours, because Bevel publishes none`() {
        val weights = ScoringConfig.DEFAULT.sleep.bevelWeights
        check(abs(weights.values.sumOf { it.weight } - 1.0) < 1e-9)
        weights.values.forEach { it.provenance shouldBe Provenance.OUR_CHOICE }
    }

    @Test
    fun `Edwards zone multipliers are the published one through five`() {
        val zones = ScoringConfig.DEFAULT.strain.edwardsZoneWeights
        (1..5).forEach { zones.getValue(it) shouldBe it.toDouble() }
        zones.size shouldBe 5
    }

    @Test
    fun `the strain saturation constant is positive, or the curve inverts`() {
        ScoringConfig.DEFAULT.strain.saturationK shouldBeGreaterThan 0.0
    }

    @Test
    fun `the energy bank recharge shares form a complete unit of weight`() {
        val bank = ScoringConfig.DEFAULT.energyBank
        check(abs(bank.recoveryShare + bank.sleepShare - 1.0) < 1e-9)
        // Recovery leads, as every report has it lead the overnight signal.
        bank.recoveryShare shouldBeGreaterThan bank.sleepShare
    }

    @Test
    fun `the energy bank coverage weights form a complete unit of weight`() {
        val bank = ScoringConfig.DEFAULT.energyBank
        val sum = bank.coverageWeightRecharge + bank.coverageWeightStrain + bank.coverageWeightCarryover
        check(abs(sum - 1.0f) < 1e-6f) { "coverage weights sum to $sum" }
    }

    @Test
    fun `the energy bank soft edges sit inside its hard edges`() {
        val bank = ScoringConfig.DEFAULT.energyBank
        bank.softFloor shouldBeGreaterThan bank.displayFloor
        bank.softCeiling shouldBeGreaterThan bank.softFloor
        bank.displayCeiling shouldBeGreaterThan bank.softCeiling
    }

    @Test
    fun `the load ratio thresholds are ordered, and the acute window is inside the chronic one`() {
        val ratio = ScoringConfig.DEFAULT.loadRatio
        ratio.sweetSpotHigh shouldBeGreaterThan ratio.sweetSpotLow
        ratio.elevatedAbove shouldBeGreaterThan ratio.sweetSpotHigh
        check(ratio.acuteDays < ratio.chronicDays)
        check(ratio.minimumChronicDays <= ratio.chronicDays)
    }

    @Test
    fun `every coverage threshold refuses lower than it degrades`() {
        // Getting these backwards would mark a score degraded and then print it
        // anyway, or refuse one it had just called fine.
        val config = ScoringConfig.DEFAULT
        check(config.recovery.refuseBelowCoverage < config.recovery.degradedBelowCoverage)
        check(config.sleep.refuseBelowCoverage < config.sleep.degradedBelowCoverage)
        check(config.strain.refuseBelowCoverage < config.strain.degradedBelowCoverage)
        check(config.energyBank.refuseBelowCoverage < config.energyBank.degradedBelowCoverage)
    }
}
