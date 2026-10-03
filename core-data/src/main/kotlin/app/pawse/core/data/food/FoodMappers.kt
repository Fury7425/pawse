package app.pawse.core.data.food

import app.pawse.core.data.db.FoodLogEntryEntity
import app.pawse.core.data.db.FoodProductEntity

/**
 * Storage rows to food objects and back.
 *
 * Enum names are stored as text rather than as ordinals. An ordinal is a promise
 * never to reorder an enum, and this one will grow: a row written today as
 * `LABEL_OCR` must still read as `LABEL_OCR` after a source is inserted above it.
 * An unrecognised name falls back to [FoodSource.MANUAL], which is the honest
 * answer for a row written by a version that knew something this one does not.
 */
object FoodProductMapper {

    fun toProduct(entity: FoodProductEntity): FoodProduct = FoodProduct(
        id = entity.id,
        barcode = entity.barcode,
        name = entity.name,
        brand = entity.brand,
        per100g = Nutrients(
            kcal = entity.kcalPer100g,
            proteinGrams = entity.proteinPer100g,
            carbsGrams = entity.carbsPer100g,
            fatGrams = entity.fatPer100g,
            saturatedFatGrams = entity.saturatedFatPer100g,
            sugarGrams = entity.sugarPer100g,
            fiberGrams = entity.fiberPer100g,
            sodiumMilligrams = entity.sodiumMgPer100g,
        ),
        servingGrams = entity.servingGrams,
        servingLabel = entity.servingLabel,
        source = sourceOf(entity.source),
        sourceDetail = entity.sourceDetail,
        fetchedAtEpochMs = entity.fetchedAtEpochMs,
    )

    fun toEntity(product: FoodProduct): FoodProductEntity = FoodProductEntity(
        id = product.id,
        barcode = product.barcode,
        name = product.name,
        brand = product.brand,
        kcalPer100g = product.per100g.kcal,
        proteinPer100g = product.per100g.proteinGrams,
        carbsPer100g = product.per100g.carbsGrams,
        fatPer100g = product.per100g.fatGrams,
        saturatedFatPer100g = product.per100g.saturatedFatGrams,
        sugarPer100g = product.per100g.sugarGrams,
        fiberPer100g = product.per100g.fiberGrams,
        sodiumMgPer100g = product.per100g.sodiumMilligrams,
        servingGrams = product.servingGrams,
        servingLabel = product.servingLabel,
        source = product.source.name,
        sourceDetail = product.sourceDetail,
        fetchedAtEpochMs = product.fetchedAtEpochMs,
    )

    fun sourceOf(name: String): FoodSource =
        runCatching { FoodSource.valueOf(name) }.getOrDefault(FoodSource.MANUAL)
}

object FoodLogMapper {

    fun toEntry(entity: FoodLogEntryEntity): FoodLogEntry = FoodLogEntry(
        id = entity.id,
        productId = entity.productId,
        name = entity.name,
        brand = entity.brand,
        localDate = entity.localDate,
        eatenAtEpochMs = entity.eatenAtEpochMs,
        meal = runCatching { Meal.valueOf(entity.meal) }.getOrDefault(Meal.SNACK),
        quantityGrams = entity.quantityGrams,
        servings = entity.servings,
        nutrients = Nutrients(
            kcal = entity.kcal,
            proteinGrams = entity.proteinGrams,
            carbsGrams = entity.carbsGrams,
            fatGrams = entity.fatGrams,
            saturatedFatGrams = entity.saturatedFatGrams,
            sugarGrams = entity.sugarGrams,
            fiberGrams = entity.fiberGrams,
            sodiumMilligrams = entity.sodiumMilligrams,
        ),
        source = FoodProductMapper.sourceOf(entity.source),
        healthConnectId = entity.healthConnectId,
    )

    fun toEntity(entry: FoodLogEntry, zoneOffsetSeconds: Int): FoodLogEntryEntity = FoodLogEntryEntity(
        id = entry.id,
        productId = entry.productId,
        name = entry.name,
        brand = entry.brand,
        localDate = entry.localDate,
        eatenAtEpochMs = entry.eatenAtEpochMs,
        zoneOffsetSeconds = zoneOffsetSeconds,
        meal = entry.meal.name,
        quantityGrams = entry.quantityGrams,
        servings = entry.servings,
        kcal = entry.nutrients.kcal,
        proteinGrams = entry.nutrients.proteinGrams,
        carbsGrams = entry.nutrients.carbsGrams,
        fatGrams = entry.nutrients.fatGrams,
        saturatedFatGrams = entry.nutrients.saturatedFatGrams,
        sugarGrams = entry.nutrients.sugarGrams,
        fiberGrams = entry.nutrients.fiberGrams,
        sodiumMilligrams = entry.nutrients.sodiumMilligrams,
        source = entry.source.name,
        healthConnectId = entry.healthConnectId,
    )

    /** A day's entries, summed, with the caveat count that makes the sum honest. */
    fun totals(localDate: String, entries: List<FoodLogEntry>): DayTotals = DayTotals(
        localDate = localDate,
        totals = entries.fold(Nutrients.EMPTY) { acc, entry -> acc + entry.nutrients },
        entryCount = entries.size,
        entriesWithoutEnergy = entries.count { !it.nutrients.hasEnergy },
    )
}
