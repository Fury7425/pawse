package app.pawse.core.data.food

import app.pawse.core.data.db.FoodLogDao
import app.pawse.core.data.db.FoodProductDao
import app.pawse.core.data.food.resolver.FoodResolverChain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** One day of food, grouped the way a person eats rather than the way it is stored. */
data class FoodDay(
    val date: LocalDate,
    val entries: List<FoodLogEntry>,
    val totals: DayTotals,
) {
    val byMeal: Map<Meal, List<FoodLogEntry>>
        get() = Meal.entries.associateWith { meal -> entries.filter { it.meal == meal } }
            .filterValues { it.isNotEmpty() }
}

/**
 * The food log.
 *
 * Two rules run through everything here, and both are the same rule the scoring
 * side runs on:
 *
 * **Figures are frozen at log time.** An entry copies the numbers it was created
 * with instead of pointing at the product. Open Food Facts is a wiki and OCR can
 * be re-run, so a product record is a live thing; what you ate on Tuesday is not.
 *
 * **The local row is the real one.** Health Connect is a mirror, written after the
 * fact and best-effort. A denied write grant, a missing Health Connect, an offline
 * phone — none of them can lose a logged meal, and none of them block the UI.
 */
@Singleton
class FoodRepository @Inject constructor(
    private val productDao: FoodProductDao,
    private val logDao: FoodLogDao,
    private val resolverChain: FoodResolverChain,
    private val preferences: FoodPreferences,
    private val nutritionWriter: NutritionWriter,
) {

    // --- Looking food up -------------------------------------------------------

    suspend fun lookup(barcode: String): FoodLookupResult = resolverChain.resolve(barcode)

    suspend fun recentProducts(limit: Int = 30): List<FoodProduct> = withContext(Dispatchers.IO) {
        productDao.recent(limit).map(FoodProductMapper::toProduct)
    }

    suspend fun searchProducts(query: String): List<FoodProduct> = withContext(Dispatchers.IO) {
        if (query.isBlank()) emptyList() else productDao.search(query.trim()).map(FoodProductMapper::toProduct)
    }

    suspend fun productById(id: Long): FoodProduct? = withContext(Dispatchers.IO) {
        productDao.byId(id)?.let(FoodProductMapper::toProduct)
    }

    /**
     * Saves a product the user established themselves — photographed from a label
     * or typed in — so the next scan of the same barcode resolves from the cache
     * with no network step at all.
     */
    suspend fun saveProduct(product: FoodProduct): FoodProduct = withContext(Dispatchers.IO) {
        val existingId = product.barcode?.let { productDao.byBarcode(it)?.id } ?: product.id
        val id = productDao.upsert(FoodProductMapper.toEntity(product.copy(id = existingId)))
        product.copy(id = if (id > 0) id else existingId)
    }

    // --- The log ---------------------------------------------------------------

    fun observeDay(date: LocalDate): Flow<FoodDay> {
        val key = date.toString()
        return logDao.observeDay(key).map { rows ->
            val entries = rows.map(FoodLogMapper::toEntry)
            FoodDay(date = date, entries = entries, totals = FoodLogMapper.totals(key, entries))
        }
    }

    suspend fun day(date: LocalDate): FoodDay = withContext(Dispatchers.IO) {
        val key = date.toString()
        val entries = logDao.day(key).map(FoodLogMapper::toEntry)
        FoodDay(date = date, entries = entries, totals = FoodLogMapper.totals(key, entries))
    }

    /**
     * Logs a portion of a known product.
     *
     * [grams] is the single source of truth for portion size even when the user
     * thought in servings: servings are converted once, here, and the grams are
     * what gets stored. Storing both and trusting whichever is non-null is how a
     * "2 servings" entry ends up disagreeing with its own gram figure.
     */
    suspend fun logProduct(
        product: FoodProduct,
        meal: Meal,
        grams: Double,
        servings: Double? = null,
        eatenAt: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): FoodLogEntry = log(
        productId = product.id.takeIf { it > 0 },
        name = product.name,
        brand = product.brand,
        nutrients = product.per100g.forGrams(grams),
        meal = meal,
        grams = grams,
        servings = servings,
        source = product.source,
        eatenAt = eatenAt,
        zone = zone,
    )

    /** Logs something with figures but no product behind it: a one-off manual entry. */
    suspend fun logOneOff(
        name: String,
        nutrients: Nutrients,
        meal: Meal,
        grams: Double,
        brand: String? = null,
        eatenAt: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): FoodLogEntry = log(
        productId = null,
        name = name,
        brand = brand,
        nutrients = nutrients,
        meal = meal,
        grams = grams,
        servings = null,
        source = FoodSource.MANUAL,
        eatenAt = eatenAt,
        zone = zone,
    )

    private suspend fun log(
        productId: Long?,
        name: String,
        brand: String?,
        nutrients: Nutrients,
        meal: Meal,
        grams: Double,
        servings: Double?,
        source: FoodSource,
        eatenAt: Instant,
        zone: ZoneId,
    ): FoodLogEntry = withContext(Dispatchers.IO) {
        val offsetSeconds = zone.rules.getOffset(eatenAt).totalSeconds
        val localDate = eatenAt.atZone(zone).toLocalDate().toString()

        val entry = FoodLogEntry(
            productId = productId,
            name = name,
            brand = brand,
            localDate = localDate,
            eatenAtEpochMs = eatenAt.toEpochMilli(),
            meal = meal,
            quantityGrams = grams,
            servings = servings,
            nutrients = nutrients,
            source = source,
            healthConnectId = null,
        )

        val id = logDao.insert(FoodLogMapper.toEntity(entry, offsetSeconds))
        val saved = entry.copy(id = id)

        // The mirror runs after the local write and never gates it.
        val mirrored = if (preferences.isMirroringEnabled()) nutritionWriter.write(saved) else null
        if (mirrored != null) logDao.setHealthConnectId(id, mirrored)

        saved.copy(healthConnectId = mirrored)
    }

    suspend fun delete(entry: FoodLogEntry) = withContext(Dispatchers.IO) {
        entry.healthConnectId?.let { nutritionWriter.delete(it) }
        logDao.byId(entry.id)?.let { logDao.delete(it) }
    }

    /**
     * Mirrors entries that were logged while Health Connect was unavailable or the
     * write grant was missing. Called when the food screen opens, cheaply: the
     * query returns nothing in the normal case.
     */
    suspend fun retryMirroring(): Int = withContext(Dispatchers.IO) {
        if (!preferences.isMirroringEnabled()) return@withContext 0
        var mirrored = 0
        for (row in logDao.notMirrored()) {
            val id = nutritionWriter.write(FoodLogMapper.toEntry(row)) ?: continue
            logDao.setHealthConnectId(row.id, id)
            mirrored++
        }
        mirrored
    }
}
