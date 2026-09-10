package app.pawse.feature.food

/**
 * Routes, owned by the feature rather than by the host.
 *
 * A product reaches the add screen as an id rather than as an object, and that is
 * on purpose: anything scanned or read off a label is written to the local product
 * cache first, so the add screen survives process death, and the same scan resolves
 * offline the next time it is seen.
 */
object FoodDestinations {

    const val LOG = "food"
    const val SCAN = "food/scan"
    const val MANUAL = "food/manual"

    const val PRODUCT_ARG = "productId"
    const val ADD = "food/add/{$PRODUCT_ARG}"

    fun add(productId: Long): String = "food/add/$productId"
}
