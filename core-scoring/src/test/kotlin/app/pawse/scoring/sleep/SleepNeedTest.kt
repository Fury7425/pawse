package app.pawse.scoring.sleep

import app.pawse.scoring.config.SleepConfig
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.Test
import kotlin.math.exp

class SleepNeedTest {

    private val config = SleepConfig()

    /**
     * The one closed form any vendor has actually published for sleep need:
     * f1(i) = 1.7 / (1 + e^((17 - i)/3.5)), patent US 9,538,923.
     * Recomputed here independently of the implementation.
     */
    private fun publishedStrainAdder(strain: Double) = 1.7 / (1.0 + exp((17.0 - strain) / 3.5))

    @Test
    fun `strain adder matches the published logistic exactly`() {
        listOf(0.0, 5.0, 10.0, 14.0, 17.0, 18.0, 21.0).forEach { strain ->
            val need = SleepNeedCalculator.compute(SleepContext(strainWhoop21 = strain), 0, config)
            need.strainAdderHours shouldBe (publishedStrainAdder(strain) plusOrMinus 1e-12)
        }
    }

    @Test
    fun `at the logistic midpoint the adder is exactly half its maximum`() {
        val need = SleepNeedCalculator.compute(SleepContext(strainWhoop21 = 17.0), 0, config)
        need.strainAdderHours shouldBe (config.strainAdderMaxHours / 2.0 plusOrMinus 1e-12)
    }

    @Test
    fun `the adder never exceeds its published ceiling`() {
        val need = SleepNeedCalculator.compute(SleepContext(strainWhoop21 = 21.0), 0, config)
        need.strainAdderHours shouldBeLessThan config.strainAdderMaxHours
    }

    @Test
    fun `need is never a static eight hours`() {
        val rest = SleepNeedCalculator.compute(SleepContext(strainWhoop21 = 2.0), 0, config)
        val hard = SleepNeedCalculator.compute(SleepContext(strainWhoop21 = 18.0), 0, config)
        hard.totalHours shouldBeGreaterThan rest.totalHours
        // A day at strain 18 asks for roughly an extra hour over a rest day.
        (hard.totalHours - rest.totalHours) shouldBeGreaterThan 0.9
    }

    @Test
    fun `debt is repaid a fraction at a time and capped`() {
        val small = SleepNeedCalculator.compute(SleepContext(sleepDebtHours = 2.0), 0, config)
        small.debtAdderHours shouldBe (1.0 plusOrMinus 1e-12)

        val huge = SleepNeedCalculator.compute(SleepContext(sleepDebtHours = 40.0), 0, config)
        huge.debtAdderHours shouldBe (config.debtCarryoverCapHours plusOrMinus 1e-12)
    }

    @Test
    fun `negative debt cannot shorten the night`() {
        val need = SleepNeedCalculator.compute(SleepContext(sleepDebtHours = -5.0), 0, config)
        need.debtAdderHours shouldBe (0.0 plusOrMinus 1e-12)
    }

    @Test
    fun `naps subtract directly, as the patent has them`() {
        val without = SleepNeedCalculator.compute(SleepContext(), 0, config)
        val with = SleepNeedCalculator.compute(SleepContext(), 45, config)
        (without.totalHours - with.totalHours) shouldBe (0.75 plusOrMinus 1e-12)
    }

    @Test
    fun `a very long nap cannot drive tonight's need to nothing`() {
        val need = SleepNeedCalculator.compute(SleepContext(), 600, config)
        need.totalHours shouldBe (config.minimumNeedHours plusOrMinus 1e-12)
    }

    @Test
    fun `a learned personal baseline overrides the starting default`() {
        val need = SleepNeedCalculator.compute(
            SleepContext(personalBaselineNeedHours = 7.1),
            0,
            config,
        )
        need.baselineHours shouldBe (7.1 plusOrMinus 1e-12)
        need.totalHours shouldBeLessThan config.baselineNeedHours
    }

    @Test
    fun `the breakdown adds up to the total`() {
        // The UI shows the sum, so the sum has to be real.
        val need = SleepNeedCalculator.compute(
            SleepContext(personalBaselineNeedHours = 7.5, strainWhoop21 = 16.0, sleepDebtHours = 1.6),
            30,
            config,
        )
        val recomposed = need.baselineHours + need.strainAdderHours + need.debtAdderHours - need.napCreditHours
        need.totalHours shouldBe (recomposed plusOrMinus 1e-12)
    }

    @Test
    fun `sleep performance is obtained over needed, the patent's own definition`() {
        val need = SleepNeedCalculator.compute(SleepContext(personalBaselineNeedHours = 8.0), 0, config)
        // 8.0 h baseline plus a hair of strain adder at strain 0.
        SleepNeedCalculator.performancePercent(need.totalMinutes, need) shouldBe (100.0 plusOrMinus 1e-9)
        SleepNeedCalculator.performancePercent(need.totalMinutes / 2.0, need) shouldBe (50.0 plusOrMinus 1e-9)
    }
}
