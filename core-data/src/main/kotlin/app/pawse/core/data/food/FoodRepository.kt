package app.pawse.core.data.food

import android.content.Context
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import app.pawse.core.data.db.FoodLogDao
import app.pawse.core.data.db.FoodLogEntryEntity
import app.pawse.core.data.db.FoodProductDao
import app.pawse.core.data.food.resolver.FoodResolverChain
import app.pawse.core.data.health.HealthConnectGateway
import app.pawse.scoring.tr
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
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
    private val gateway: HealthConnectGateway,
    @ApplicationContext private val context: Context,
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
        /** Caffeine counts only once the user has confirmed the product's figure. */
        countCaffeine: Boolean = true,
        eatenAt: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): FoodLogEntry = log(
        productId = product.id.takeIf { it > 0 },
        name = product.name,
        brand = product.brand,
        nutrients = product.per100g.forGrams(grams).let { if (countCaffeine) it else it.copy(caffeineMg = null) },
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

    /** Seven days ending on [end], oldest first, empty days included: a gap is a record too. */
    fun observeWeek(end: LocalDate): Flow<List<FoodDay>> {
        val start = end.minusDays(WEEK_DAYS - 1L)
        return logDao.observeBetween(start.toString(), end.toString()).map { rows ->
            val byDate = rows.map(FoodLogMapper::toEntry).groupBy { it.localDate }
            (0 until WEEK_DAYS).map { offset ->
                val date = start.plusDays(offset.toLong())
                val entries = byDate[date.toString()].orEmpty()
                FoodDay(date = date, entries = entries, totals = FoodLogMapper.totals(date.toString(), entries))
            }
        }
    }

    /**
     * Removes an entry from the log at once and holds its Health Connect copy back
     * until [flushPendingRemovals], so Undo can put it back without a second write.
     */
    suspend fun remove(entry: FoodLogEntry) = withContext(Dispatchers.IO) {
        // Another app's record is not ours to delete, and the next import would bring it back.
        if (entry.source == FoodSource.HEALTH_CONNECT) return@withContext
        entry.healthConnectId?.let { preferences.addPendingDelete(it) }
        logDao.byId(entry.id)?.let { logDao.delete(it) }
    }

    /** Undo: the same row, same id, same frozen figures, same Health Connect record. */
    suspend fun restore(entry: FoodLogEntry, zone: ZoneId = ZoneId.systemDefault()) = withContext(Dispatchers.IO) {
        val offset = zone.rules.getOffset(Instant.ofEpochMilli(entry.eatenAtEpochMs)).totalSeconds
        logDao.insert(FoodLogMapper.toEntity(entry, offset))
        entry.healthConnectId?.let { preferences.removePendingDelete(it) }
    }

    /** Deletes the mirrors of removals whose Undo window has closed. Kept for a retry if one fails. */
    suspend fun flushPendingRemovals() = withContext(Dispatchers.IO) {
        for (recordId in preferences.pendingDeletes()) {
            if (nutritionWriter.delete(recordId)) preferences.removePendingDelete(recordId)
        }
    }

    /**
     * What to offer under "Again": the most recently eaten distinct foods. Most
     * meals repeat, so yesterday's breakfast should be one tap, not one scan.
     */
    suspend fun again(today: LocalDate = LocalDate.now(), limit: Int = 4): List<FoodLogEntry> =
        withContext(Dispatchers.IO) {
            logDao.between(today.minusDays(AGAIN_LOOKBACK_DAYS).toString(), today.toString())
                .map(FoodLogMapper::toEntry)
                .sortedByDescending { it.eatenAtEpochMs }
                .distinctBy { it.name.lowercase() to it.brand?.lowercase() }
                .take(limit)
        }

    /** Logs [entry] again now, with the figures it was frozen with. */
    suspend fun relog(entry: FoodLogEntry, meal: Meal = Meal.SNACK): FoodLogEntry = log(
        productId = entry.productId,
        name = entry.name,
        brand = entry.brand,
        nutrients = entry.nutrients,
        meal = meal,
        grams = entry.quantityGrams,
        servings = entry.servings,
        // Logged again here, it is ours: mirrored out, and not reconciled away by the import.
        source = if (entry.source == FoodSource.HEALTH_CONNECT) FoodSource.MANUAL else entry.source,
        eatenAt = Instant.now(),
        zone = ZoneId.systemDefault(),
    )

    /**
     * Brings food logged in other apps — Google Health, a calorie counter — into the
     * log, so the day's total is the whole day and not only what was typed in here.
     *
     * Each import reconciles the window against Health Connect: new records are
     * added, edited ones updated, and ones removed in their own app removed here.
     * Our own records are skipped by package, since the local row is already the
     * original. A failed read changes nothing; an empty answer would otherwise
     * look like every imported meal had been deleted.
     */
    suspend fun importFromHealthConnect(zone: ZoneId = ZoneId.systemDefault()): Int = withContext(Dispatchers.IO) {
        val today = LocalDate.now(zone)
        val from = today.minusDays(IMPORT_LOOKBACK_DAYS)
        val records = try {
            gateway.read(NutritionRecord::class, from.atStartOfDay(zone).toInstant(), Instant.now())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            return@withContext 0
        }

        val foreign = records.filter { it.metadata.dataOrigin.packageName != context.packageName }
        val existing = logDao.importedBetween(from.toString(), today.toString()).associateBy { it.healthConnectId }
        val seen = foreign.mapTo(HashSet()) { it.metadata.id }
        for ((recordId, row) in existing) if (recordId !in seen) logDao.delete(row)

        var changed = 0
        for (record in foreign) {
            val row = importedRow(record, zone, id = existing[record.metadata.id]?.id ?: 0)
            when {
                row.id == 0L -> logDao.insert(row)
                row != existing[record.metadata.id] -> logDao.update(row)
                else -> continue
            }
            changed++
        }
        changed
    }

    private fun importedRow(record: NutritionRecord, zone: ZoneId, id: Long): FoodLogEntryEntity {
        val offset = record.startZoneOffset ?: zone.rules.getOffset(record.startTime)
        val nutrients = Nutrients(
            kcal = record.energy?.inKilocalories,
            proteinGrams = record.protein?.inGrams,
            carbsGrams = record.totalCarbohydrate?.inGrams,
            fatGrams = record.totalFat?.inGrams,
            saturatedFatGrams = record.saturatedFat?.inGrams,
            sugarGrams = record.sugar?.inGrams,
            fiberGrams = record.dietaryFiber?.inGrams,
            sodiumMilligrams = record.sodium?.inMilligrams,
            caffeineMg = record.caffeine?.inMilligrams,
        )
        val entry = FoodLogEntry(
            id = id,
            productId = null,
            name = record.name?.takeIf { it.isNotBlank() } ?: tr("Food", "음식"),
            brand = null,
            localDate = record.startTime.atOffset(offset).toLocalDate().toString(),
            eatenAtEpochMs = record.startTime.toEpochMilli(),
            meal = when (record.mealType) {
                MealType.MEAL_TYPE_BREAKFAST -> Meal.BREAKFAST
                MealType.MEAL_TYPE_LUNCH -> Meal.LUNCH
                MealType.MEAL_TYPE_DINNER -> Meal.DINNER
                else -> Meal.SNACK
            },
            // ponytail: Health Connect has no portion size; 0 g means "not stated" and the row hides it.
            quantityGrams = 0.0,
            servings = null,
            nutrients = nutrients,
            source = FoodSource.HEALTH_CONNECT,
            healthConnectId = record.metadata.id,
        )
        return FoodLogMapper.toEntity(entry, offset.totalSeconds)
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

    private companion object {
        const val WEEK_DAYS = 7
        const val AGAIN_LOOKBACK_DAYS = 30L
        const val IMPORT_LOOKBACK_DAYS = 30L
    }
}
