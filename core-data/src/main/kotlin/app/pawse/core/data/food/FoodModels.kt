package app.pawse.core.data.food

/**
 * Where a set of nutrition figures came from.
 *
 * Carried on the product, on the log entry, and shown in the UI — the same
 * discipline the scoring engine applies to its coefficients. "620 kcal" read off a
 * photographed label by OCR and "620 kcal" from a product database are not equally
 * trustworthy, and the app should never present them as though they were.
 */
enum class FoodSource {
    /** A product database lookup over the network. */
    OPEN_FOOD_FACTS,

    /** Read off the nutrition panel by on-device text recognition. */
    LABEL_OCR,

    /** Typed in by the user, who is the authority on their own food. */
    MANUAL,
}

/** A food we can log, however we came to know about it. */
data class FoodProduct(
    val id: Long = 0,
    /** Null for a manual entry that was never a scanned product. */
    val barcode: String?,
    val name: String,
    val brand: String?,
    /** Figures per 100 g, which is the only unit everything can be converted to. */
    val per100g: Nutrients,
    /** Grams in one serving, when the source states it. */
    val servingGrams: Double?,
    /** What the source calls that serving: "1 컵 (240ml)", "2 biscuits". */
    val servingLabel: String?,
    val source: FoodSource,
    /** Free text pinning the figure down: an Open Food Facts id, or "photographed label". */
    val sourceDetail: String?,
    val fetchedAtEpochMs: Long,
) {
    /** What one serving contains, when a serving size is known. */
    val perServing: Nutrients?
        get() = servingGrams?.let { per100g.forGrams(it) }
}

/** The result of asking the resolver chain about a barcode. */
data class FoodResolution(
    val product: FoodProduct,
    /**
     * True when this came from the local cache rather than from its original
     * source. Surfaced because a cached figure can be months old, and because it
     * explains to the user why the same scan was instant this time.
     */
    val fromCache: Boolean,
) {
    val source: FoodSource get() = product.source
}

/** Why a barcode produced nothing, so the UI can offer the right next step. */
enum class FoodLookupFailure {
    /** The chain ran and no source recognised the barcode. */
    NOT_FOUND,

    /** Network lookup is available but the user has not turned it on. */
    NETWORK_NOT_PERMITTED,

    /** Turned on, but the request failed: offline, DNS, a 500. */
    NETWORK_FAILED,
}

sealed interface FoodLookupResult {
    data class Found(val resolution: FoodResolution) : FoodLookupResult
    data class NotFound(val barcode: String, val reason: FoodLookupFailure) : FoodLookupResult
}

enum class Meal { BREAKFAST, LUNCH, DINNER, SNACK }

/**
 * One logged item.
 *
 * The nutrition figures are copied in at log time rather than referenced from the
 * product. A product record can be corrected later — Open Food Facts is a wiki,
 * and OCR can be re-run — and yesterday's lunch must not silently change because
 * someone edited a database entry this morning. Same reason the score table keeps
 * the config hash that produced each row.
 */
data class FoodLogEntry(
    val id: Long = 0,
    val productId: Long?,
    val name: String,
    val brand: String?,
    val localDate: String,
    val eatenAtEpochMs: Long,
    val meal: Meal,
    val quantityGrams: Double,
    /** Servings, when the user thought in servings rather than grams. */
    val servings: Double?,
    val nutrients: Nutrients,
    val source: FoodSource,
    /** Set once the entry has been mirrored into Health Connect, so it can be revoked. */
    val healthConnectId: String?,
)
