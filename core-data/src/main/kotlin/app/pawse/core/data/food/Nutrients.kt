package app.pawse.core.data.food

import kotlin.math.roundToInt

/**
 * A nutrition figure set, with every field optional.
 *
 * Optional is the point. A barcode lookup returns energy and macros far more often
 * than it returns fibre, and a label photographed at an angle may yield four
 * numbers out of eight. The same rule the scoring engine runs on applies here: an
 * absent value is absent, never a zero. Zero grams of protein is a claim about the
 * food; null is a claim about what we know.
 */
data class Nutrients(
    val kcal: Double? = null,
    val proteinGrams: Double? = null,
    val carbsGrams: Double? = null,
    val fatGrams: Double? = null,
    val saturatedFatGrams: Double? = null,
    val sugarGrams: Double? = null,
    val fiberGrams: Double? = null,
    val sodiumMilligrams: Double? = null,
) {

    val isEmpty: Boolean
        get() = kcal == null && proteinGrams == null && carbsGrams == null && fatGrams == null &&
            saturatedFatGrams == null && sugarGrams == null && fiberGrams == null &&
            sodiumMilligrams == null

    val hasEnergy: Boolean get() = kcal != null

    /** Linear scaling, for converting a per-100g figure to a portion. */
    fun scaled(factor: Double): Nutrients = Nutrients(
        kcal = kcal?.times(factor),
        proteinGrams = proteinGrams?.times(factor),
        carbsGrams = carbsGrams?.times(factor),
        fatGrams = fatGrams?.times(factor),
        saturatedFatGrams = saturatedFatGrams?.times(factor),
        sugarGrams = sugarGrams?.times(factor),
        fiberGrams = fiberGrams?.times(factor),
        sodiumMilligrams = sodiumMilligrams?.times(factor),
    )

    /** What [grams] of a food whose figures are per 100 g contains. */
    fun forGrams(grams: Double): Nutrients = scaled(grams / 100.0)

    /**
     * Summed for a meal or a day.
     *
     * Two nulls stay null; a null on one side contributes nothing rather than
     * poisoning the total. That is the pragmatic choice for a running total, and it
     * is why [DayTotals] separately counts the entries that had no energy figure at
     * all — a day total is only honest next to how much of the day it covers.
     */
    operator fun plus(other: Nutrients): Nutrients = Nutrients(
        kcal = add(kcal, other.kcal),
        proteinGrams = add(proteinGrams, other.proteinGrams),
        carbsGrams = add(carbsGrams, other.carbsGrams),
        fatGrams = add(fatGrams, other.fatGrams),
        saturatedFatGrams = add(saturatedFatGrams, other.saturatedFatGrams),
        sugarGrams = add(sugarGrams, other.sugarGrams),
        fiberGrams = add(fiberGrams, other.fiberGrams),
        sodiumMilligrams = add(sodiumMilligrams, other.sodiumMilligrams),
    )

    /**
     * Energy implied by the macros, in kcal: Atwater's 4/4/9.
     *
     * Used only to sanity-check a figure we did not measure — a label whose OCR read
     * "열량 620" against macros implying 210 was misread, and it is better to flag
     * that than to log it.
     */
    fun impliedKcal(): Double? {
        if (proteinGrams == null && carbsGrams == null && fatGrams == null) return null
        return 4.0 * (proteinGrams ?: 0.0) + 4.0 * (carbsGrams ?: 0.0) + 9.0 * (fatGrams ?: 0.0)
    }

    /**
     * Whether the stated energy and the macros disagree by more than [tolerance].
     *
     * Null when either side is unknown: silence is not disagreement.
     */
    fun energyDisagreesWithMacros(tolerance: Double = ATWATER_TOLERANCE): Boolean? {
        val stated = kcal ?: return null
        val implied = impliedKcal() ?: return null
        if (stated <= 0.0 && implied <= 0.0) return false
        val reference = maxOf(stated, implied, 1.0)
        return kotlin.math.abs(stated - implied) / reference > tolerance
    }

    companion object {
        val EMPTY = Nutrients()

        /**
         * How far the Atwater estimate may sit from a stated energy before we say
         * so. Generous on purpose: rounding on a label, sugar alcohols, fibre
         * counted differently between jurisdictions and the 4/4/9 approximation
         * itself all move it a little, and a warning that fires on every second
         * product is a warning nobody reads.
         */
        const val ATWATER_TOLERANCE = 0.25

        private fun add(a: Double?, b: Double?): Double? = when {
            a == null && b == null -> null
            else -> (a ?: 0.0) + (b ?: 0.0)
        }
    }
}

/** One day of logged food, with the caveats that make the totals honest. */
data class DayTotals(
    val localDate: String,
    val totals: Nutrients,
    val entryCount: Int,
    /**
     * Entries carrying no energy figure. Printed next to the total, for the same
     * reason every score prints its coverage: a 1,400 kcal day with three unknown
     * items is not a 1,400 kcal day.
     */
    val entriesWithoutEnergy: Int,
) {
    val complete: Boolean get() = entriesWithoutEnergy == 0

    val kcalRounded: Int? get() = totals.kcal?.roundToInt()
}
