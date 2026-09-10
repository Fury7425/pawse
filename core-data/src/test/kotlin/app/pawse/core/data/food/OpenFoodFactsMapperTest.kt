package app.pawse.core.data.food

import app.pawse.core.data.food.net.OffResponse
import app.pawse.core.data.food.net.OpenFoodFactsMapper
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * The mapper, against the shapes Open Food Facts actually returns.
 *
 * It is a wiki with a decade of importers behind it: the same field arrives as a
 * number, as a numeric string, and occasionally as an empty string, and energy is
 * stated in kilojoules about as often as in kilocalories. A parser that assumes
 * one shape fails in a shop, holding the tin.
 */
class OpenFoodFactsMapperTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private fun parse(body: String) = json.decodeFromString(OffResponse.serializer(), body)

    private val tolerance = 1e-6

    @Test
    fun `a well-formed product maps to per-100g figures`() {
        val response = parse(
            """
            {"code":"8801043032117","status":1,"product":{
              "product_name":"Shin Ramyun",
              "brands":"Nongshim, Nong Shim",
              "serving_size":"120 g",
              "serving_quantity":120,
              "nutriments":{
                "energy-kcal_100g":420.5,
                "proteins_100g":9.2,
                "carbohydrates_100g":63.0,
                "fat_100g":14.5,
                "saturated-fat_100g":7.0,
                "sugars_100g":3.1,
                "fiber_100g":2.4,
                "sodium_100g":1.6
              }}}
            """.trimIndent(),
        )

        val product = OpenFoodFactsMapper.toProduct(response, "8801043032117", 1_000L)!!

        product.name shouldBe "Shin Ramyun"
        // Only the first brand: Open Food Facts stores a comma-joined list.
        product.brand shouldBe "Nongshim"
        product.per100g.kcal!! shouldBe (420.5 plusOrMinus tolerance)
        product.per100g.proteinGrams!! shouldBe (9.2 plusOrMinus tolerance)
        // Sodium is grams there and milligrams everywhere a person reads it.
        product.per100g.sodiumMilligrams!! shouldBe (1600.0 plusOrMinus tolerance)
        product.servingGrams!! shouldBe (120.0 plusOrMinus tolerance)
        product.source shouldBe FoodSource.OPEN_FOOD_FACTS
    }

    @Test
    fun `numbers that arrived as strings still parse`() {
        val response = parse(
            """
            {"product":{"product_name":"Typed by hand","nutriments":{
              "energy-kcal_100g":"312","proteins_100g":"7,5","fat_100g":""}}}
            """.trimIndent(),
        )

        val product = OpenFoodFactsMapper.toProduct(response, "1", 0L)!!

        product.per100g.kcal!! shouldBe (312.0 plusOrMinus tolerance)
        // A European decimal comma, which is what a French contributor typed.
        product.per100g.proteinGrams!! shouldBe (7.5 plusOrMinus tolerance)
        // An empty string is not a zero.
        product.per100g.fatGrams.shouldBeNull()
    }

    @Test
    fun `kilojoules are converted rather than dropped`() {
        val response = parse(
            """
            {"product":{"product_name":"European biscuit","nutriments":{"energy-kj_100g":2100}}}
            """.trimIndent(),
        )

        val product = OpenFoodFactsMapper.toProduct(response, "1", 0L)!!
        product.per100g.kcal!! shouldBe (2100.0 / 4.184 plusOrMinus 1e-3)
    }

    @Test
    fun `salt is converted to sodium when sodium itself is missing`() {
        val response = parse(
            """
            {"product":{"product_name":"Crisps","nutriments":{"energy-kcal_100g":500,"salt_100g":1.0}}}
            """.trimIndent(),
        )

        val product = OpenFoodFactsMapper.toProduct(response, "1", 0L)!!
        product.per100g.sodiumMilligrams!! shouldBe (393.0 plusOrMinus tolerance)
    }

    @Test
    fun `a Korean name is preferred when the product carries one`() {
        val response = parse(
            """
            {"product":{"product_name":"Shin Ramyun","product_name_ko":"신라면",
             "nutriments":{"energy-kcal_100g":420}}}
            """.trimIndent(),
        )

        OpenFoodFactsMapper.toProduct(response, "1", 0L)!!.name shouldBe "신라면"
    }

    @Test
    fun `serving grams are read off the label when the numeric field is missing`() {
        val response = parse(
            """
            {"product":{"product_name":"Yoghurt","serving_size":"1 컵 (240ml)",
             "nutriments":{"energy-kcal_100g":60}}}
            """.trimIndent(),
        )

        OpenFoodFactsMapper.toProduct(response, "1", 0L)!!.servingGrams!! shouldBe (240.0 plusOrMinus tolerance)
    }

    @Test
    fun `a product with no name is no product`() {
        val response = parse("""{"product":{"nutriments":{"energy-kcal_100g":420}}}""")
        OpenFoodFactsMapper.toProduct(response, "1", 0L).shouldBeNull()
    }

    @Test
    fun `a named product with no figures at all is no use either`() {
        val response = parse("""{"product":{"product_name":"Mystery","nutriments":{}}}""")
        OpenFoodFactsMapper.toProduct(response, "1", 0L).shouldBeNull()
    }

    @Test
    fun `an unknown barcode comes back as no product`() {
        val response = parse("""{"code":"1","status":0,"status_verbose":"product not found"}""")
        OpenFoodFactsMapper.toProduct(response, "1", 0L).shouldBeNull()
    }
}

class NutrientsTest {

    private val tolerance = 1e-9

    @Test
    fun `scaling a per-100g figure to a portion`() {
        val per100g = Nutrients(kcal = 420.0, proteinGrams = 9.0, fatGrams = null)
        val portion = per100g.forGrams(120.0)

        portion.kcal!! shouldBe (504.0 plusOrMinus tolerance)
        portion.proteinGrams!! shouldBe (10.8 plusOrMinus tolerance)
        // Unknown scaled by anything is still unknown.
        portion.fatGrams.shouldBeNull()
    }

    @Test
    fun `summing keeps unknown separate from zero`() {
        val a = Nutrients(kcal = 200.0, proteinGrams = 10.0)
        val b = Nutrients(kcal = 150.0, fatGrams = 4.0)
        val total = a + b

        total.kcal!! shouldBe (350.0 plusOrMinus tolerance)
        // Present on one side only: contributes what it has.
        total.proteinGrams!! shouldBe (10.0 plusOrMinus tolerance)
        total.fatGrams!! shouldBe (4.0 plusOrMinus tolerance)
        // Absent on both: still absent, never zero.
        total.fiberGrams.shouldBeNull()
    }

    @Test
    fun `Atwater catches an energy figure that cannot be right`() {
        val misread = Nutrients(kcal = 620.0, proteinGrams = 3.0, carbsGrams = 30.0, fatGrams = 8.0)
        misread.energyDisagreesWithMacros() shouldBe true

        val sensible = misread.copy(kcal = 204.0)
        sensible.energyDisagreesWithMacros() shouldBe false
    }

    @Test
    fun `silence is not disagreement`() {
        Nutrients(kcal = 200.0).energyDisagreesWithMacros().shouldBeNull()
        Nutrients(proteinGrams = 10.0).energyDisagreesWithMacros().shouldBeNull()
    }
}
