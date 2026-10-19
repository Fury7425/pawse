package app.pawse.scoring.body

import app.pawse.scoring.model.BiologicalSex

/** Where a basal metabolic rate came from. The UI says which, every time. */
enum class BmrSource {
    /** Written to Health Connect by the user's scale. Used as it is. */
    MEASURED,

    /** Katch-McArdle, 370 + 21.6 x lean kg. Published; needs only lean mass. */
    KATCH_MCARDLE,

    /** Mifflin-St Jeor from weight, height and age. Published fallback. */
    MIFFLIN_ST_JEOR,
}

data class BmrResult(val kcalPerDay: Double, val source: BmrSource)

/**
 * Picks the most direct BMR available, in a fixed order: measured, then the
 * formula that needs the fewest assumptions, then the one that needs the most.
 * Returns null rather than inventing a number when nothing usable is known.
 */
object Bmr {

    fun select(
        measuredKcal: Double?,
        leanMassKg: Double?,
        weightKg: Double?,
        heightM: Double?,
        ageYears: Int?,
        sex: BiologicalSex,
    ): BmrResult? {
        if (measuredKcal != null && measuredKcal > 0) return BmrResult(measuredKcal, BmrSource.MEASURED)
        if (leanMassKg != null && leanMassKg > 0) {
            return BmrResult(370.0 + 21.6 * leanMassKg, BmrSource.KATCH_MCARDLE)
        }
        if (weightKg == null || heightM == null || ageYears == null || weightKg <= 0 || heightM <= 0) return null
        // ponytail: an unspecified sex takes the midpoint of the two published
        // constants (+5 and -161), the same rule the strain curves follow.
        val sexTerm = when (sex) {
            BiologicalSex.MALE -> 5.0
            BiologicalSex.FEMALE -> -161.0
            BiologicalSex.UNSPECIFIED -> -78.0
        }
        val kcal = 10.0 * weightKg + 6.25 * heightM * 100.0 - 5.0 * ageYears + sexTerm
        return BmrResult(kcal, BmrSource.MIFFLIN_ST_JEOR)
    }

    /**
     * Resting energy burned so far today: BMR spread evenly across the day.
     * Comparing a whole day of BMR with half a day of eating would make every
     * morning look like a large deficit.
     */
    fun restingSoFar(bmrKcalPerDay: Double, fractionOfDay: Double): Double =
        bmrKcalPerDay * fractionOfDay.coerceIn(0.0, 1.0)
}
