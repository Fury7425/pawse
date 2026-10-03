package app.pawse.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A product we have figures for, cached so a barcode is looked up once and then
 * never again.
 *
 * The cache is what makes the network step optional rather than load-bearing: a
 * shelf of food you buy regularly resolves offline after the first scan of each
 * item, and a user who declines network lookup entirely still accumulates a
 * private library from labels they photographed.
 *
 * Figures are stored per 100 g because that is the only basis every source can be
 * converted to, and because a serving size is a property of the packaging rather
 * than of the food.
 */
@Entity(
    tableName = "food_product",
    indices = [
        Index(value = ["barcode"], unique = true),
        Index(value = ["name"]),
    ],
)
data class FoodProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Null for a food that was never scanned: a manual entry or a photographed label. */
    val barcode: String?,
    val name: String,
    val brand: String?,
    val kcalPer100g: Double?,
    val proteinPer100g: Double?,
    val carbsPer100g: Double?,
    val fatPer100g: Double?,
    val saturatedFatPer100g: Double?,
    val sugarPer100g: Double?,
    val fiberPer100g: Double?,
    val sodiumMgPer100g: Double?,
    val servingGrams: Double?,
    val servingLabel: String?,
    /** [app.pawse.core.data.food.FoodSource] name. */
    val source: String,
    val sourceDetail: String?,
    val fetchedAtEpochMs: Long,
)

/**
 * One logged item, with its figures frozen at the moment it was logged.
 *
 * [healthConnectId] is the handle on the mirrored NutritionRecord. Keeping it is
 * what makes deletion honest: removing a meal here removes it from Health Connect
 * too, rather than leaving an orphan the user has to go and find in another app.
 */
@Entity(
    tableName = "food_log_entry",
    indices = [
        Index(value = ["localDate"]),
        Index(value = ["productId"]),
    ],
)
data class FoodLogEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long?,
    val name: String,
    val brand: String?,
    val localDate: String,
    val eatenAtEpochMs: Long,
    val zoneOffsetSeconds: Int,
    /** [app.pawse.core.data.food.Meal] name. */
    val meal: String,
    val quantityGrams: Double,
    val servings: Double?,
    val kcal: Double?,
    val proteinGrams: Double?,
    val carbsGrams: Double?,
    val fatGrams: Double?,
    val saturatedFatGrams: Double?,
    val sugarGrams: Double?,
    val fiberGrams: Double?,
    val sodiumMilligrams: Double?,
    /** [app.pawse.core.data.food.FoodSource] name, copied from the product. */
    val source: String,
    val healthConnectId: String?,
)
