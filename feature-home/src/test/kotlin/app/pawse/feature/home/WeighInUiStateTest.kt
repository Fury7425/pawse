package app.pawse.feature.home

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.Test

/** What the weigh-in screen writes, since it goes to Health Connect as typed. */
class WeighInUiStateTest {

    private val sheet = WeighInUiState(
        inBody = true,
        rows = mapOf(InBodyRow.WEIGHT to 72.4, InBodyRow.MUSCLE to 33.0, InBodyRow.FAT to 9.6, InBodyRow.VISCERAL to 6.0),
    )

    @Test
    fun `fat percent, lean mass and BMR follow from weight and fat mass`() {
        sheet.fatPercent!! shouldBe (13.26 plusOrMinus 0.01)
        sheet.leanKg!! shouldBe (62.8 plusOrMinus 1e-9)
        sheet.bmr!! shouldBe (370.0 + 21.6 * 62.8 plusOrMinus 1e-9)
        sheet.toWeighIn().fatPercent!! shouldBe (13.26 plusOrMinus 0.01)
    }

    @Test
    fun `a Don't know row is left out and so is anything worked out from it`() {
        val noFat = sheet.copy(unknown = setOf(InBodyRow.FAT, InBodyRow.VISCERAL))
        noFat.toWeighIn().fatPercent shouldBe null
        noFat.toWeighIn().leanKg shouldBe null
        noFat.bmr shouldBe null
        noFat.extra()!!.visceralFat shouldBe null
        noFat.extra()!!.skeletalMuscleKg shouldBe 33.0
    }

    @Test
    fun `fat can't reach the weight and a scale reading carries weight alone`() {
        sheet.max(InBodyRow.FAT) shouldBe (71.4 plusOrMinus 1e-9)
        val scale = WeighInUiState(kg = 70.2)
        scale.toWeighIn().weightKg shouldBe 70.2
        scale.toWeighIn().fatPercent shouldBe null
        scale.extra() shouldBe null
    }
}
