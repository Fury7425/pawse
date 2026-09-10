package app.pawse.core.data.food

import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import app.pawse.core.data.health.HealthConnectAvailability
import app.pawse.core.data.health.HealthConnectGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only thing this app ever writes.
 *
 * Everything else in Pawse reads: the watch wrote it, we interpret it. Food is the
 * exception, because the user created it here and it belongs in the same store as
 * the rest of their health data rather than trapped in one app.
 *
 * Two properties matter and both are about not making a mess in someone else's
 * store. The record is tagged as a manual entry, because that is what it is and a
 * reader downstream should be able to weigh it accordingly. And the returned id is
 * kept, so deleting a meal here deletes it there too — a mirror that can only add
 * is a mirror that fills the user's Health Connect with food they already removed.
 */
@Singleton
class NutritionWriter @Inject constructor(
    private val gateway: HealthConnectGateway,
) {

    /**
     * @return the Health Connect record id, or null when the write did not happen:
     *   no Health Connect, no write grant, or a failure. A failed mirror is never
     *   fatal — the entry is already saved locally, which is the copy that matters.
     */
    suspend fun write(entry: FoodLogEntry): String? = withContext(Dispatchers.IO) {
        val client = gateway.client ?: return@withContext null
        if (gateway.availability() !is HealthConnectAvailability.Available) return@withContext null

        val eaten = Instant.ofEpochMilli(entry.eatenAtEpochMs)

        // Zone offsets are left null: Health Connect fills them from the device,
        // which is the right answer for a meal logged here and now and a better one
        // than any offset this class could reconstruct after the fact.
        val record = NutritionRecord(
            startTime = eaten,
            startZoneOffset = null,
            // Eating takes a moment, not an instant, but the duration of a meal is
            // not something we asked the user for and not something to invent. A
            // fifteen-minute window centred on the log time would be a fabrication;
            // a zero-length window is simply what we know.
            endTime = eaten,
            endZoneOffset = null,
            energy = entry.nutrients.kcal?.let { Energy.kilocalories(it) },
            protein = entry.nutrients.proteinGrams?.let { Mass.grams(it) },
            totalCarbohydrate = entry.nutrients.carbsGrams?.let { Mass.grams(it) },
            totalFat = entry.nutrients.fatGrams?.let { Mass.grams(it) },
            saturatedFat = entry.nutrients.saturatedFatGrams?.let { Mass.grams(it) },
            sugar = entry.nutrients.sugarGrams?.let { Mass.grams(it) },
            dietaryFiber = entry.nutrients.fiberGrams?.let { Mass.grams(it) },
            sodium = entry.nutrients.sodiumMilligrams?.let { Mass.milligrams(it) },
            name = entry.name,
            mealType = mealTypeOf(entry.meal),
            metadata = Metadata.manualEntry(),
        )

        try {
            client.insertRecords(listOf(record)).recordIdsList.firstOrNull()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // Most often a missing write grant. The local entry stands either way.
            null
        }
    }

    /** Removes the mirrored record. Silent on failure: the row may already be gone. */
    suspend fun delete(recordId: String): Boolean = withContext(Dispatchers.IO) {
        val client = gateway.client ?: return@withContext false
        try {
            client.deleteRecords(
                recordType = NutritionRecord::class,
                recordIdsList = listOf(recordId),
                clientRecordIdsList = emptyList(),
            )
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            false
        }
    }

    private fun mealTypeOf(meal: Meal): Int = when (meal) {
        Meal.BREAKFAST -> MealType.MEAL_TYPE_BREAKFAST
        Meal.LUNCH -> MealType.MEAL_TYPE_LUNCH
        Meal.DINNER -> MealType.MEAL_TYPE_DINNER
        Meal.SNACK -> MealType.MEAL_TYPE_SNACK
    }
}
