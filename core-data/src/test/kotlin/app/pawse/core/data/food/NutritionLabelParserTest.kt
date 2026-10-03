package app.pawse.core.data.food

import app.pawse.core.data.food.ocr.LabelBasis
import app.pawse.core.data.food.ocr.LabelWarning
import app.pawse.core.data.food.ocr.NutritionLabelParser
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * The label parser, against panels shaped like the ones on a Korean shelf.
 *
 * These fixtures are the point of the whole OCR path: no product database has
 * every corner-shop item, but every packet has this printed on the back. Each test
 * below is a way the naive version of this parser gets it wrong.
 */
class NutritionLabelParserTest {

    private val tolerance = 1e-6

    @Test
    fun `a Korean panel, per serving`() {
        val reading = NutritionLabelParser.parse(
            listOf(
                "영양정보",
                "총 내용량 90g",
                "1회 제공량 45g",
                "45g당",
                "열량 210kcal",
                "나트륨 320mg 16%",
                "탄수화물 30g 9%",
                "당류 12g 12%",
                "지방 8g 15%",
                "트랜스지방 0g",
                "포화지방 4g 27%",
                "콜레스테롤 5mg 2%",
                "단백질 3g 6%",
            ),
        )

        reading.nutrients.kcal!! shouldBe (210.0 plusOrMinus tolerance)
        reading.nutrients.sodiumMilligrams!! shouldBe (320.0 plusOrMinus tolerance)
        reading.nutrients.carbsGrams!! shouldBe (30.0 plusOrMinus tolerance)
        reading.nutrients.sugarGrams!! shouldBe (12.0 plusOrMinus tolerance)
        reading.nutrients.proteinGrams!! shouldBe (3.0 plusOrMinus tolerance)

        // The trap: 지방 sits inside both 트랜스지방 and 포화지방.
        reading.nutrients.fatGrams!! shouldBe (8.0 plusOrMinus tolerance)
        reading.nutrients.saturatedFatGrams!! shouldBe (4.0 plusOrMinus tolerance)

        reading.basis shouldBe LabelBasis.PER_SERVING
        reading.basisGrams!! shouldBe (45.0 plusOrMinus tolerance)
        reading.servingGrams!! shouldBe (45.0 plusOrMinus tolerance)
        reading.packageGrams!! shouldBe (90.0 plusOrMinus tolerance)
    }

    @Test
    fun `a daily-value percentage is never mistaken for an amount`() {
        // The failure this prevents: logging a 16 mg sodium biscuit.
        val reading = NutritionLabelParser.parse(listOf("나트륨 320mg 16%"))
        reading.nutrients.sodiumMilligrams!! shouldBe (320.0 plusOrMinus tolerance)
    }

    @Test
    fun `two nutrients on one OCR line each keep their own number`() {
        val reading = NutritionLabelParser.parse(listOf("탄수화물 30g 9% 당류 12g 12%"))
        reading.nutrients.carbsGrams!! shouldBe (30.0 plusOrMinus tolerance)
        reading.nutrients.sugarGrams!! shouldBe (12.0 plusOrMinus tolerance)
    }

    @Test
    fun `per 100 g is recognised and needs no conversion`() {
        val reading = NutritionLabelParser.parse(
            listOf("100g당", "열량 450kcal", "단백질 9g", "탄수화물 60g", "지방 20g"),
        )
        reading.basis shouldBe LabelBasis.PER_100G
        reading.per100g()!!.kcal!! shouldBe (450.0 plusOrMinus tolerance)
    }

    @Test
    fun `figures stated per package are rebased to 100 g`() {
        val reading = NutritionLabelParser.parse(
            listOf("총 내용량 200g", "200g당", "열량 400kcal", "단백질 10g", "지방 12g"),
        )

        reading.basis shouldBe LabelBasis.PER_PACKAGE
        // 400 kcal in 200 g is 200 kcal per 100 g. Assuming the panel was already
        // per 100 g would double the user's day.
        reading.per100g()!!.kcal!! shouldBe (200.0 plusOrMinus tolerance)
        reading.per100g()!!.proteinGrams!! shouldBe (5.0 plusOrMinus tolerance)
    }

    @Test
    fun `a label that never says what its figures are per says so`() {
        val reading = NutritionLabelParser.parse(
            listOf("열량 210kcal", "단백질 3g", "탄수화물 30g", "지방 8g"),
        )

        reading.basis shouldBe LabelBasis.UNKNOWN
        reading.warnings shouldContain LabelWarning.NO_BASIS
        // Refusing to convert is the point: guessing 100 g is a factor-of-two error
        // on a 45 g packet, and the user is the one who can see the packet.
        reading.per100g().shouldBeNull()
    }

    @Test
    fun `an English panel reads too`() {
        val reading = NutritionLabelParser.parse(
            listOf(
                "Nutrition Facts",
                "Serving size 55 g",
                "Amount per serving",
                "Calories 240",
                "Total Fat 12g 15%",
                "Saturated Fat 3.5g 18%",
                "Sodium 210mg 9%",
                "Total Carbohydrate 28g 10%",
                "Dietary Fiber 2g 7%",
                "Total Sugars 9g",
                "Protein 5g",
            ),
        )

        reading.nutrients.kcal!! shouldBe (240.0 plusOrMinus tolerance)
        reading.nutrients.fatGrams!! shouldBe (12.0 plusOrMinus tolerance)
        reading.nutrients.saturatedFatGrams!! shouldBe (3.5 plusOrMinus tolerance)
        reading.nutrients.fiberGrams!! shouldBe (2.0 plusOrMinus tolerance)
        reading.nutrients.sodiumMilligrams!! shouldBe (210.0 plusOrMinus tolerance)
        reading.basis shouldBe LabelBasis.PER_SERVING
        reading.basisGrams!! shouldBe (55.0 plusOrMinus tolerance)
    }

    @Test
    fun `sodium stated in grams becomes milligrams`() {
        val reading = NutritionLabelParser.parse(listOf("나트륨 0.32g"))
        reading.nutrients.sodiumMilligrams!! shouldBe (320.0 plusOrMinus tolerance)
    }

    @Test
    fun `composed unit glyphs from a phone camera are understood`() {
        // ㎎ and ㎉ are single characters, and a Korean package is full of them.
        val reading = NutritionLabelParser.parse(listOf("열량 １８０㎉", "나트륨 ４５０㎎"))
        reading.nutrients.kcal!! shouldBe (180.0 plusOrMinus tolerance)
        reading.nutrients.sodiumMilligrams!! shouldBe (450.0 plusOrMinus tolerance)
    }

    @Test
    fun `energy that contradicts the macros is flagged rather than logged quietly`() {
        // 3 g protein, 30 g carbs and 8 g fat imply about 204 kcal, not 620.
        val reading = NutritionLabelParser.parse(
            listOf("45g당", "열량 620kcal", "단백질 3g", "탄수화물 30g", "지방 8g"),
        )
        reading.warnings shouldContain LabelWarning.ENERGY_DISAGREES_WITH_MACROS
    }

    @Test
    fun `a panel that agrees with itself raises no warning`() {
        val reading = NutritionLabelParser.parse(
            listOf("45g당", "열량 210kcal", "단백질 3g", "탄수화물 30g", "지방 8g"),
        )
        reading.warnings shouldNotContain LabelWarning.ENERGY_DISAGREES_WITH_MACROS
        reading.usable shouldBe true
    }

    @Test
    fun `a blurred photo that yielded almost nothing says so`() {
        val reading = NutritionLabelParser.parse(listOf("영양정보", "열량 210kcal"))
        reading.usable shouldBe false
        reading.warnings shouldContain LabelWarning.TOO_FEW_FIELDS
    }
}
