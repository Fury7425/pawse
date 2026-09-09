package app.pawse.scoring.model

/**
 * Inputs for the load side of the engine: Strain, Energy Bank, Load Ratio.
 *
 * Same rule as [DailyInputs]. Nothing here touches Health Connect, a clock, or a
 * database. A null is "the writer app did not provide it" and is never replaced by
 * a population default.
 */

/**
 * Needed only by Banister's TRIMP, which is published as two sex-specific curves.
 *
 * [BiologicalSex.UNSPECIFIED] is a first-class case, not an oversight. Health
 * Connect has no sex record type and asking is intrusive, so the common state is
 * "we do not know". What the engine does about it is spelled out in
 * [app.pawse.scoring.strain.Trimp].
 */
enum class BiologicalSex { MALE, FEMALE, UNSPECIFIED }

data class UserProfile(
    val ageYears: Int? = null,
    val biologicalSex: BiologicalSex = BiologicalSex.UNSPECIFIED,
    /**
     * Long-run resting heart rate, for the HR-reserve fraction. Prefer the
     * personal baseline the BaselineEngine already computes over a spot reading.
     */
    val restingHeartRate: Double? = null,
    /**
     * A measured HRmax the user entered. Preferred over the Tanaka estimate, and
     * worth prompting for: Firstbeat quantifies the sensitivity, reporting that a
     * 15 bpm HRmax error moves an estimated VO2max by 7-9% (claude.md §1).
     */
    val measuredMaxHeartRate: Double? = null,
)

/** One logged exercise session, already reduced to what TRIMP needs. */
data class WorkoutSample(
    /** Stable id, so the UI can tie a session row back to the source record. */
    val id: String,
    val durationMinutes: Double,
    val meanHeartRate: Double?,
    val maxHeartRate: Double?,
    /**
     * Minutes per Edwards zone 1..5, when the writer app supplied a heart-rate
     * series we could bin. Empty is the common case and is fine: Banister needs
     * only the mean.
     */
    val zoneMinutes: Map<Int, Double> = emptyMap(),
    val title: String? = null,
)

/** One day of activity. */
data class DayActivity(
    val date: String,
    val workouts: List<WorkoutSample> = emptyList(),
    /**
     * Hours awake, feeding the passive-load term. Defaults to sixteen rather than
     * being derived, because deriving it from sleep sessions would make a day with
     * no sleep tracking look like a day with no life in it.
     */
    val wakingHours: Double = 16.0,
)

/** One day's total load, for the acute:chronic ratio. daysAgo = 0 is today. */
data class DailyLoad(val daysAgo: Int, val load: Double)

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
