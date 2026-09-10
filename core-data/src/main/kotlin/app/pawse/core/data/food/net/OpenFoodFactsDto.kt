package app.pawse.core.data.food.net

import app.pawse.core.data.food.FoodProduct
import app.pawse.core.data.food.FoodSource
import app.pawse.core.data.food.Nutrients
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Open Food Facts, as it actually arrives.
 *
 * The nutriments block is kept as a raw [JsonObject] rather than a typed class,
 * and that is a considered choice. Open Food Facts is a wiki: the same field
 * comes back as `12.5`, as `"12.5"`, and occasionally as `""`, depending on who
 * typed it and through which importer. A strict data class turns one bad field
 * into a failed lookup for the whole product, which is a poor trade when the user
 * is standing in a shop holding the tin.
 */
@Serializable
data class OffResponse(
    val code: String? = null,
    val status: JsonElement? = null,
    val product: OffProduct? = null,
)

@Serializable
data class OffProduct(
    @SerialName("product_name") val productName: String? = null,
    @SerialName("product_name_ko") val productNameKo: String? = null,
    @SerialName("generic_name") val genericName: String? = null,
    val brands: String? = null,
    @SerialName("serving_size") val servingSize: String? = null,
    @SerialName("serving_quantity") val servingQuantity: JsonElement? = null,
    val nutriments: JsonObject? = null,
)

/**
 * Response to product, or null when there is nothing usable in it.
 *
 * "Usable" is deliberately strict about one thing and lenient about everything
 * else: a product with no name is useless whatever else it carries, but a product
 * with a name and only an energy figure is worth logging. Anything missing stays
 * missing rather than being filled with a zero.
 */
object OpenFoodFactsMapper {

    fun toProduct(response: OffResponse, barcode: String, fetchedAtEpochMs: Long): FoodProduct? {
        val product = response.product ?: return null
        val name = firstNonBlank(product.productNameKo, product.productName, product.genericName)
            ?: return null

        val nutriments = product.nutriments
        val per100g = Nutrients(
            kcal = energyKcalPer100g(nutriments),
            proteinGrams = nutriments.number("proteins_100g"),
            carbsGrams = nutriments.number("carbohydrates_100g"),
            fatGrams = nutriments.number("fat_100g"),
            saturatedFatGrams = nutriments.number("saturated-fat_100g"),
            sugarGrams = nutriments.number("sugars_100g"),
            fiberGrams = nutriments.number("fiber_100g"),
            sodiumMilligrams = sodiumMgPer100g(nutriments),
        )
        if (per100g.isEmpty) return null

        return FoodProduct(
            barcode = barcode,
            name = name,
            brand = firstNonBlank(product.brands)?.substringBefore(',')?.trim(),
            per100g = per100g,
            servingGrams = servingGrams(product),
            servingLabel = firstNonBlank(product.servingSize),
            source = FoodSource.OPEN_FOOD_FACTS,
            sourceDetail = "Open Food Facts, barcode $barcode",
            fetchedAtEpochMs = fetchedAtEpochMs,
        )
    }

    /**
     * Energy in kcal.
     *
     * European labels are stated in kilojoules and Open Food Facts carries whichever
     * the contributor entered, so a kJ figure is converted rather than dropped.
     * 1 kcal = 4.184 kJ.
     */
    private fun energyKcalPer100g(nutriments: JsonObject?): Double? {
        nutriments.number("energy-kcal_100g")?.let { return it }
        nutriments.number("energy-kj_100g")?.let { return it / KJ_PER_KCAL }
        // The bare `energy_100g` field is in whatever `energy_unit` says, and that
        // is kJ far more often than not. Only used as a last resort.
        val energy = nutriments.number("energy_100g") ?: return null
        val unit = (nutriments?.get("energy_unit") as? JsonPrimitive)?.content?.lowercase()
        return if (unit == "kcal") energy else energy / KJ_PER_KCAL
    }

    /** Open Food Facts states sodium in grams per 100 g; labels and Health Connect use mg. */
    private fun sodiumMgPer100g(nutriments: JsonObject?): Double? {
        nutriments.number("sodium_100g")?.let { return it * 1000.0 }
        // Salt, not sodium: 1 g of salt is about 0.4 g of sodium.
        val salt = nutriments.number("salt_100g") ?: return null
        return salt * SODIUM_PER_SALT * 1000.0
    }

    private fun servingGrams(product: OffProduct): Double? {
        (product.servingQuantity as? JsonPrimitive)?.let { primitive ->
            primitive.doubleOrNull?.let { return it }
            primitive.content.toDoubleOrNull()?.let { return it }
        }
        // Fall back to parsing the label: "30 g", "250ml", "1 컵 (240 ml)".
        val label = product.servingSize ?: return null
        val match = SERVING_PATTERN.find(label) ?: return null
        return match.groupValues[1].replace(',', '.').toDoubleOrNull()
    }

    /**
     * A number from a field that may be a number, a numeric string, or junk.
     * Junk returns null, which the caller treats as "not stated".
     */
    private fun JsonObject?.number(key: String): Double? {
        val primitive = this?.get(key) as? JsonPrimitive ?: return null
        primitive.doubleOrNull?.let { return it }
        return primitive.content.trim().replace(',', '.').toDoubleOrNull()
    }

    private fun firstNonBlank(vararg candidates: String?): String? =
        candidates.firstOrNull { !it.isNullOrBlank() }?.trim()

    private const val KJ_PER_KCAL = 4.184

    /** Sodium as a fraction of salt by mass. */
    private const val SODIUM_PER_SALT = 0.393

    /**
     * Leading quantity in a serving label. Millilitres are treated as grams, which
     * is right for water and near enough for milk, juice and soup — and far closer
     * than refusing to log the item at all.
     */
    private val SERVING_PATTERN = Regex("""(\d+[.,]?\d*)\s*(?:g|ml|그램|밀리리터)""", RegexOption.IGNORE_CASE)
}
