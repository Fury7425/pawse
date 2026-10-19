package app.pawse.feature.home

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldEndWith
import org.junit.Test
import java.time.LocalDateTime

/** What the weigh-in screen lets through, since it is written to Health Connect as typed. */
class WeighInUiStateTest {

    @Test
    fun `a weight outside 20 to 300 kg can't be saved`() {
        WeighInUiState(weightText = "15").canSave shouldBe false
        WeighInUiState(weightText = "15").weightBad shouldBe true
        WeighInUiState(weightText = "72,4").weightKg shouldBe 72.4
        WeighInUiState(weightText = "72.4").canSave shouldBe true
    }

    @Test
    fun `a blank figure stays blank and a bad one blocks the save`() {
        val ok = WeighInUiState(weightText = "72.4", figures = mapOf(BodyFigure.FAT to "21", BodyFigure.LEAN to ""))
        ok.filledFigures shouldBe 1
        ok.toWeighIn().leanKg shouldBe null
        ok.saveLabel shouldEndWith "kg and 1 more"

        val bad = ok.copy(figures = mapOf(BodyFigure.FAT to "80"))
        bad.figureError(BodyFigure.FAT) shouldNotBe null
        bad.canSave shouldBe false
    }

    @Test
    fun `lean mass can't outweigh the person`() {
        WeighInUiState(weightText = "60", figures = mapOf(BodyFigure.LEAN to "65")).figureError(BodyFigure.LEAN) shouldNotBe null
    }

    @Test
    fun `a weigh-in in the future is refused`() {
        WeighInUiState(weightText = "72.4", earlier = true, earlierAt = LocalDateTime.now().plusHours(2)).canSave shouldBe false
    }
}
