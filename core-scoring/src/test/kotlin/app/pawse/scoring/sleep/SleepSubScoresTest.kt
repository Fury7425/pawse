package app.pawse.scoring.sleep

import app.pawse.scoring.config.SleepTargets
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.Test

class SleepSubScoresTest {

    private val t = SleepTargets()

    private fun eachSubScore(): List<Double> = listOf(
        SleepSubScores.duration(400.0, 480.0, t),
        SleepSubScores.efficiency(88.0, t),
        SleepSubScores.remShare(22.0, t),
        SleepSubScores.deepShare(17.0, t),
        SleepSubScores.latency(14.0, t),
        SleepSubScores.restfulness(20.0, 3, t),
        SleepSubScores.timing(40.0, t),
        SleepSubScores.bedtimeConsistency(35.0, t),
        SleepSubScores.interruptions(2, t),
        SleepSubScores.heartRateDip(11.0, t),
    )

    @Test
    fun `every sub-score stays inside zero to one hundred`() {
        eachSubScore().forEach {
            it shouldBeGreaterThan -1e-9
            it shouldBeLessThan 100.0 + 1e-9
        }
    }

    @Test
    fun `hitting the need earns full duration credit and overshooting does not earn more`() {
        SleepSubScores.duration(480.0, 480.0, t) shouldBe (100.0 plusOrMinus 1e-9)
        SleepSubScores.duration(700.0, 480.0, t) shouldBe (100.0 plusOrMinus 1e-9)
    }

    @Test
    fun `the duration penalty accelerates, so the third lost hour costs most`() {
        // Apple describes exactly this shape: one hour short is mild, the next
        // costs more (grok.txt §4). Ours is a curve, not their point table, but
        // the ordering has to hold.
        val need = 480.0
        val full = SleepSubScores.duration(480.0, need, t)
        val oneShort = SleepSubScores.duration(420.0, need, t)
        val twoShort = SleepSubScores.duration(360.0, need, t)
        val threeShort = SleepSubScores.duration(300.0, need, t)

        (full - oneShort) shouldBeLessThan (oneShort - twoShort)
        (oneShort - twoShort) shouldBeLessThan (twoShort - threeShort)
    }

    @Test
    fun `half the need scores zero, and nothing goes below it`() {
        SleepSubScores.duration(240.0, 480.0, t) shouldBe (0.0 plusOrMinus 1e-9)
        SleepSubScores.duration(10.0, 480.0, t) shouldBe (0.0 plusOrMinus 1e-9)
    }

    @Test
    fun `a zero need cannot produce a score`() {
        SleepSubScores.duration(400.0, 0.0, t) shouldBe (0.0 plusOrMinus 1e-9)
    }

    @Test
    fun `efficiency hinges on Oura's stated eighty-five percent mark`() {
        SleepSubScores.efficiency(85.0, t) shouldBe (t.efficiencyKneeScore plusOrMinus 1e-9)
        SleepSubScores.efficiency(95.0, t) shouldBe (100.0 plusOrMinus 1e-9)
        SleepSubScores.efficiency(99.0, t) shouldBe (100.0 plusOrMinus 1e-9)
        SleepSubScores.efficiency(65.0, t) shouldBe (0.0 plusOrMinus 1e-9)
        SleepSubScores.efficiency(50.0, t) shouldBe (0.0 plusOrMinus 1e-9)
        SleepSubScores.efficiency(90.0, t) shouldBe (90.0 plusOrMinus 1e-9)
    }

    @Test
    fun `stage bands give full credit inside the target and taper outside it`() {
        SleepSubScores.remShare(22.5, t) shouldBe (100.0 plusOrMinus 1e-9)
        SleepSubScores.remShare(5.0, t) shouldBe (0.0 plusOrMinus 1e-9)
        SleepSubScores.remShare(50.0, t) shouldBe (0.0 plusOrMinus 1e-9)
        SleepSubScores.remShare(12.5, t) shouldBe (50.0 plusOrMinus 1e-9)

        SleepSubScores.deepShare(18.0, t) shouldBe (100.0 plusOrMinus 1e-9)
        SleepSubScores.deepShare(2.0, t) shouldBe (0.0 plusOrMinus 1e-9)
        SleepSubScores.deepShare(40.0, t) shouldBe (0.0 plusOrMinus 1e-9)
    }

    @Test
    fun `too much REM is penalised as well as too little`() {
        // Oura's own contributor allows 5-50% of the night; the wide band is the
        // point, but the tails still cost something.
        SleepSubScores.remShare(40.0, t) shouldBeLessThan 100.0
        SleepSubScores.remShare(40.0, t) shouldBeGreaterThan 0.0
    }

    @Test
    fun `falling asleep instantly scores below full credit, not above it`() {
        SleepSubScores.latency(0.0, t) shouldBe (t.latencyInstantScore plusOrMinus 1e-9)
        SleepSubScores.latency(14.0, t) shouldBe (100.0 plusOrMinus 1e-9)
        SleepSubScores.latency(0.0, t) shouldBeLessThan SleepSubScores.latency(14.0, t)
    }

    @Test
    fun `latency past twenty minutes decays to zero at an hour`() {
        SleepSubScores.latency(20.0, t) shouldBe (100.0 plusOrMinus 1e-9)
        SleepSubScores.latency(40.0, t) shouldBe (50.0 plusOrMinus 1e-9)
        SleepSubScores.latency(60.0, t) shouldBe (0.0 plusOrMinus 1e-9)
        SleepSubScores.latency(180.0, t) shouldBe (0.0 plusOrMinus 1e-9)
    }

    @Test
    fun `restfulness separates one long wake from many short stirs`() {
        val perfect = SleepSubScores.restfulness(0.0, 0, t)
        val oneLongBlock = SleepSubScores.restfulness(40.0, 1, t)
        val manyStirs = SleepSubScores.restfulness(8.0, 8, t)
        perfect shouldBe (100.0 plusOrMinus 1e-9)
        oneLongBlock shouldBeLessThan 100.0
        manyStirs shouldBeLessThan 100.0
        // Same night length awake, different structure, different score. A
        // WASO-only term could not tell these apart.
        oneLongBlock shouldBeGreaterThan manyStirs
    }

    @Test
    fun `timing measures drift from your own midpoint, in either direction`() {
        SleepSubScores.timing(0.0, t) shouldBe (100.0 plusOrMinus 1e-9)
        SleepSubScores.timing(90.0, t) shouldBe (50.0 plusOrMinus 1e-9)
        SleepSubScores.timing(-90.0, t) shouldBe (50.0 plusOrMinus 1e-9)
        SleepSubScores.timing(400.0, t) shouldBe (0.0 plusOrMinus 1e-9)
    }

    @Test
    fun `Apple's interruption bucket costs fixed points and floors at zero`() {
        SleepSubScores.interruptions(0, t) shouldBe (100.0 plusOrMinus 1e-9)
        SleepSubScores.interruptions(1, t) shouldBe (85.0 plusOrMinus 1e-9)
        SleepSubScores.interruptions(7, t) shouldBe (0.0 plusOrMinus 1e-9)
        SleepSubScores.interruptions(-3, t) shouldBe (100.0 plusOrMinus 1e-9)
    }

    @Test
    fun `a steeper heart-rate dip scores higher, and no dip scores zero`() {
        SleepSubScores.heartRateDip(0.0, t) shouldBe (0.0 plusOrMinus 1e-9)
        SleepSubScores.heartRateDip(15.0, t) shouldBe (100.0 plusOrMinus 1e-9)
        SleepSubScores.heartRateDip(30.0, t) shouldBe (100.0 plusOrMinus 1e-9)
        SleepSubScores.heartRateDip(-5.0, t) shouldBe (0.0 plusOrMinus 1e-9)
        SleepSubScores.heartRateDip(7.5, t) shouldBe (50.0 plusOrMinus 1e-9)
    }
}
