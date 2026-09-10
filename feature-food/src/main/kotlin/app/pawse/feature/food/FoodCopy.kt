package app.pawse.feature.food

import app.pawse.core.data.food.DayTotals
import app.pawse.core.data.food.FoodLookupFailure
import app.pawse.core.data.food.FoodSource
import app.pawse.core.data.food.Meal
import app.pawse.core.data.food.Nutrients
import app.pawse.core.data.food.ocr.LabelBasis
import app.pawse.core.data.food.ocr.LabelWarning
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The food side's voice, in one file, for the same reason the score side has one.
 *
 * The rule that matters here is provenance. A figure typed off a packet by hand, a
 * figure read by OCR and a figure from a public database are not equally
 * trustworthy, and every screen that shows one says which it is. The app never
 * launders a guess into a fact.
 */
object FoodCopy {

    fun meal(meal: Meal): String = when (meal) {
        Meal.BREAKFAST -> "Breakfast"
        Meal.LUNCH -> "Lunch"
        Meal.DINNER -> "Dinner"
        Meal.SNACK -> "Snack"
    }

    fun source(source: FoodSource): String = when (source) {
        FoodSource.OPEN_FOOD_FACTS -> "Open Food Facts"
        FoodSource.LABEL_OCR -> "Read from the label"
        FoodSource.MANUAL -> "You typed it"
    }

    /** One line under a product, saying how much to trust it. */
    fun sourceNote(source: FoodSource, fromCache: Boolean): String = when (source) {
        FoodSource.OPEN_FOOD_FACTS ->
            if (fromCache) {
                "From your saved foods, originally Open Food Facts. Check it still matches the packet."
            } else {
                "From Open Food Facts, a public database anyone can edit. Worth a glance at the packet."
            }
        FoodSource.LABEL_OCR ->
            "Read off the label by the camera. Check the numbers before you trust the day's total."
        FoodSource.MANUAL -> "You entered these figures."
    }

    fun lookupFailure(reason: FoodLookupFailure): String = when (reason) {
        FoodLookupFailure.NOT_FOUND ->
            "No match for that barcode. Point the camera at the nutrition panel instead, " +
                "or type it in — either way it is saved, so the next scan of this product is instant."

        FoodLookupFailure.NETWORK_NOT_PERMITTED ->
            "This product is not in your saved foods, and looking it up online is switched off."

        FoodLookupFailure.NETWORK_FAILED ->
            "Could not reach the food database. The nutrition panel on the back works offline."
    }

    /** The one-time explanation, shown before a single byte leaves the phone. */
    const val CONSENT_TITLE = "Look this up online?"

    const val CONSENT_BODY = "Pawse can ask Open Food Facts, a public food database, what a " +
        "barcode is. The request contains the barcode and nothing else: no account, no device " +
        "identifier, no history, and nothing about your health data — which never leaves this " +
        "phone either way.\n\n" +
        "Each product is fetched once and saved, so the same item resolves instantly and offline " +
        "afterwards. You can turn this off at any time, and reading the nutrition panel with the " +
        "camera works without it."

    fun labelBasis(basis: LabelBasis, grams: Double?): String {
        val amount = grams?.let { "${trim(it)} g" }
        return when (basis) {
            LabelBasis.PER_100G -> "The label states these per 100 g."
            LabelBasis.PER_SERVING -> "The label states these per serving${amount?.let { " ($it)" } ?: ""}."
            LabelBasis.PER_PACKAGE -> "The label states these per package${amount?.let { " ($it)" } ?: ""}."
            LabelBasis.UNKNOWN ->
                "The label did not say what these figures are per, so we cannot work it out for you."
        }
    }

    fun labelWarning(warning: LabelWarning): String = when (warning) {
        LabelWarning.NO_BASIS -> "Tell us what the figures are per, below."
        LabelWarning.TOO_FEW_FIELDS -> "Only a few figures came through. Move closer or steady the phone."
        LabelWarning.ENERGY_DISAGREES_WITH_MACROS ->
            "The energy figure does not match the protein, carbs and fat. One of them was probably misread."
    }

    /** "312 kcal · 12 g protein · 30 g carbs · 14 g fat" — only what is actually known. */
    fun summary(nutrients: Nutrients): String {
        val parts = buildList {
            nutrients.kcal?.let { add("${it.roundToInt()} kcal") }
            nutrients.proteinGrams?.let { add("${trim(it)} g protein") }
            nutrients.carbsGrams?.let { add("${trim(it)} g carbs") }
            nutrients.fatGrams?.let { add("${trim(it)} g fat") }
        }
        return if (parts.isEmpty()) "No figures" else parts.joinToString(" · ")
    }

    fun energy(nutrients: Nutrients): String =
        nutrients.kcal?.let { "${it.roundToInt()} kcal" } ?: "— kcal"

    fun grams(value: Double): String = "${trim(value)} g"

    /**
     * The day's total, with the caveat attached rather than beside it.
     *
     * A total that silently omits three items the user logged is worse than no
     * total, so the count of unknowns is part of the sentence.
     */
    fun totalsNote(totals: DayTotals): String = when {
        totals.entryCount == 0 -> "Nothing logged yet."
        totals.entriesWithoutEnergy == 0 -> "${totals.entryCount} items, all with figures."
        totals.entriesWithoutEnergy == 1 -> "${totals.entryCount} items · 1 has no energy figure, so this total is short."
        else ->
            "${totals.entryCount} items · ${totals.entriesWithoutEnergy} have no energy figure, " +
                "so this total is short."
    }

    fun trim(value: Double): String =
        if (value % 1.0 == 0.0) {
            value.roundToInt().toString()
        } else {
            String.format(Locale.getDefault(), "%.1f", value)
        }
}
