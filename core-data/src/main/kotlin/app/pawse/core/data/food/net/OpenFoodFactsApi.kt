package app.pawse.core.data.food.net

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The one endpoint this app talks to.
 *
 * What leaves the phone is a barcode and nothing else: no identifier, no account,
 * no history, no sequence of previous scans. That is the whole request, and it is
 * what the consent screen says it is.
 *
 * `fields` is not an optimisation. Asking for eleven named fields instead of the
 * default response keeps a shop-aisle lookup to a few kilobytes over mobile data,
 * and it means an Open Food Facts schema change can add fields without changing
 * what we parse.
 */
interface OpenFoodFactsApi {

    @GET("api/v2/product/{barcode}.json")
    suspend fun product(
        @Path("barcode") barcode: String,
        @Query("fields") fields: String = FIELDS,
    ): OffResponse

    companion object {
        const val BASE_URL = "https://world.openfoodfacts.org/"

        const val FIELDS = "code,product_name,product_name_ko,generic_name,brands," +
            "serving_size,serving_quantity,nutriments"
    }
}
