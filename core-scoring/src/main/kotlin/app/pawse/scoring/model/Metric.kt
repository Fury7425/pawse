package app.pawse.scoring.model

/**
 * Every stored quantity the app reads, keyed into the `metric_sample` table.
 *
 * [direction] is the sign that makes "higher is better" for a z-score: +1 when a
 * value above your usual is good, -1 when it is bad. [deviationOnly] metrics are
 * bad in both directions.
 */
enum class Metric(
    val direction: Int,
    val deviationOnly: Boolean = false,
    val unit: String,
) {
    /** One HeartRateVariabilityRmssdRecord. Health Connect has no SDNN type. */
    HRV_RMSSD(direction = +1, unit = "ms"),

    /** The writer's own daily resting heart rate. The sleeping-HR fallback. */
    RESTING_HEART_RATE(direction = -1, unit = "bpm"),

    RESPIRATORY_RATE(direction = -1, unit = "br/min"),

    /** Skin temperature as a delta from the device's own baseline. */
    SKIN_TEMPERATURE(direction = -1, deviationOnly = true, unit = "degC"),

    SPO2(direction = +1, unit = "%"),

    /** Raw beat-to-beat heart rate. Read on demand over narrow windows, never synced. */
    HEART_RATE(direction = -1, unit = "bpm"),

    // --- Signal layer --------------------------------------------------------
    // Written by Pawse itself, once per night, from the raw heart-rate series.

    /** Lowest 30-minute rolling mean of heart rate inside the night. */
    SLEEPING_HEART_RATE(direction = -1, unit = "bpm"),

    /** When that lowest window happened, as a fraction of the night (0..1). */
    HR_NADIR(direction = +1, unit = "fraction"),

    /** Share of the night with any heart-rate data (0..1). Feeds night quality. */
    NIGHT_HR_COVERAGE(direction = +1, unit = "fraction"),

    /** A MenstruationPeriodRecord interval. Value is always 1. */
    MENSTRUATION(direction = +1, unit = "flag"),

    // --- Body tab ------------------------------------------------------------
    // Written by a smart scale and compared with the user's own usual range. No
    // "better" direction; none of them feeds a score.

    BODY_WEIGHT(direction = +1, unit = "kg"),
    BODY_FAT(direction = +1, unit = "%"),
    LEAN_MASS(direction = +1, unit = "kg"),
    BONE_MASS(direction = +1, unit = "kg"),
    BODY_WATER(direction = +1, unit = "kg"),
    BODY_HEIGHT(direction = +1, unit = "m"),
    BASAL_METABOLIC_RATE(direction = +1, unit = "kcal/day"),
    ACTIVE_ENERGY(direction = +1, unit = "kcal"),
    ;

    val key: String get() = name
}
