package app.pawse.core.data.scoring

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.Test

class CircularClockTest {

    private val tolerance = 1e-6

    @Test
    fun `mean of times either side of midnight is midnight, not midday`() {
        // 23:50 and 00:10. The arithmetic mean of 1430 and 10 is 720 — midday —
        // which is the bug this whole class exists to avoid.
        val mean = CircularClock.meanMinutes(listOf(1430.0, 10.0))!!
        // Compared on the clock face rather than on the number line: midnight is
        // reachable as either 0 or 1440, and both are the right answer.
        CircularClock.difference(mean, 0.0) shouldBe (0.0 plusOrMinus 1e-3)
    }

    @Test
    fun `a consistent night-owl schedule reports a small spread`() {
        // Bedtimes clustered around midnight, ten minutes either side.
        val sd = CircularClock.sdMinutes(listOf(1430.0, 1440.0 - 1.0, 5.0, 10.0, 0.0))!!
        sd shouldBeLessThan 15.0
    }

    @Test
    fun `a scattered schedule reports a large spread`() {
        val sd = CircularClock.sdMinutes(listOf(1320.0, 60.0, 180.0, 1200.0, 300.0))!!
        sd shouldBeGreaterThan 120.0
    }

    @Test
    fun `difference takes the short way round the clock`() {
        // 23:30 to 00:30 is one hour forward, not twenty-three back.
        CircularClock.difference(from = 1410.0, to = 30.0) shouldBe (60.0 plusOrMinus tolerance)
        CircularClock.difference(from = 30.0, to = 1410.0) shouldBe (-60.0 plusOrMinus tolerance)
    }

    @Test
    fun `identical times have no spread and no drift`() {
        // Not compared at machine epsilon on purpose. sqrt(-2 ln R) is exquisitely
        // sensitive as R approaches one — a resultant length a single ULP under
        // unity comes back as a few microseconds of spread — and a test that
        // demanded exactly zero would be asserting about floating point rather than
        // about a schedule. Anything under a second is the same bedtime.
        CircularClock.sdMinutes(listOf(90.0, 90.0, 90.0))!! shouldBe (0.0 plusOrMinus 1.0 / 60.0)
        CircularClock.difference(90.0, 90.0) shouldBe (0.0 plusOrMinus tolerance)
    }

    @Test
    fun `a single sample has a mean but no spread`() {
        CircularClock.meanMinutes(listOf(400.0))!! shouldBe (400.0 plusOrMinus 1e-3)
        CircularClock.sdMinutes(listOf(400.0)) shouldBe null
    }
}
