package app.pawse.scoring.model

/**
 * Needed only by Banister's TRIMP, which is published as two sex-specific curves.
 * [UNSPECIFIED] is first-class: Health Connect has no sex record type.
 */
enum class BiologicalSex { MALE, FEMALE, UNSPECIFIED }

data class UserProfile(
    val ageYears: Int? = null,
    val biologicalSex: BiologicalSex = BiologicalSex.UNSPECIFIED,
    /** Overrides the resting heart rate the engine derives from your nights. */
    val restingHeartRate: Double? = null,
    /** A measured HRmax. Preferred over the Tanaka estimate. */
    val measuredMaxHeartRate: Double? = null,
)

/** One logged exercise session, already reduced to what TRIMP needs. */
data class WorkoutSample(
    val id: String,
    val durationMinutes: Double,
    val meanHeartRate: Double?,
    val maxHeartRate: Double?,
    /** Minutes per Edwards zone 1..5, when a series could be binned. Usually empty. */
    val zoneMinutes: Map<Int, Double> = emptyMap(),
    val title: String? = null,
)

/** A coefficient's origin, carried out of the load calculators with the number. */
data class LoadResult(
    val load: Double,
    val provenance: Provenance,
    val citation: String,
)

/** HRmax, and whether it was measured or estimated. Never silently one or the other. */
data class MaxHeartRate(
    val bpm: Double,
    val provenance: Provenance,
    val citation: String,
    val estimated: Boolean,
)
