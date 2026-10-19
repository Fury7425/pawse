package app.pawse.feature.home

import app.pawse.scoring.model.BodyBand
import app.pawse.scoring.model.BodyReason
import app.pawse.scoring.model.Guidance
import app.pawse.scoring.model.Insight
import app.pawse.scoring.model.Predictor
import app.pawse.scoring.model.ReasonKind
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test

/** The app's voice is a feature, so it gets tests. */
class ScoreCopyTest {

    @Test
    fun `never poor or bad`() {
        (BodyBand.entries.map(ScoreCopy::bandWord) + Guidance.entries.map(ScoreCopy::guidance)).forEach {
            it.lowercase() shouldNotContain "poor"
            it.lowercase() shouldNotContain "bad"
        }
        ScoreCopy.bandWord(BodyBand.BELOW_NORMAL) shouldBe "Below your normal"
    }

    @Test
    fun `reasons read like the spec's example`() {
        val text = ScoreCopy.reasons(
            listOf(BodyReason(ReasonKind.SLEEPING_HR_ABOVE, 4.2), BodyReason(ReasonKind.DRINKS, 2.0)),
        )
        text shouldBe "Sleeping HR 4 bpm above normal; 2 drinks logged"
    }

    @Test
    fun `sleep debt is said in hours`() {
        ScoreCopy.debt(3.1667) shouldBe "3 h 10 behind"
        ScoreCopy.debt(0.1) shouldBe "No sleep debt"
    }

    @Test
    fun `check-in and Body disagreeing by two bands is called out`() {
        ScoreCopy.disagrees(1, BodyBand.TYPICAL) shouldBe true
        ScoreCopy.disagrees(3, BodyBand.TYPICAL) shouldBe false
        ScoreCopy.disagrees(5, BodyBand.BELOW_NORMAL) shouldBe true
    }

    @Test
    fun `insights show size, uncertainty and how often`() {
        val text = ScoreCopy.insight(Insight(Predictor.ALCOHOL, -0.8, -1.1, -0.5, 11))
        text shouldContain "0.8 SD lower"
        text shouldContain "(0.5 to 1.1)"
        text shouldContain "11 days"
    }

    @Test
    fun `clock minutes wrap past midnight`() {
        ScoreCopy.minuteOfDay(22 * 60 + 30) shouldBe "22:30"
        ScoreCopy.minuteOfDay(24 * 60 + 15) shouldBe "00:15"
    }
}
