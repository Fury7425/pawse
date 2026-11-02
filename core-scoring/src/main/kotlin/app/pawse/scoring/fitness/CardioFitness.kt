package app.pawse.scoring.fitness

import app.pawse.scoring.config.FitnessConfig
import app.pawse.scoring.model.BiologicalSex
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One VO2max reading from one source, mL/kg/min. */
data class Vo2Reading(val day: Long, val value: Double, val source: String)

/** The filtered VO2max and its spread, from one source. */
data class Vo2Estimate(val value: Double, val sd: Double, val readings: Int, val lastDay: Long, val source: String)

/** "Fitness age 29 · likely 23–34". Shown on Body only, marked temporary, with source and date. */
data class FitnessAge(
    val age: Int,
    val low: Int,
    val high: Int,
    val vo2max: Vo2Estimate,
    /** True when it came from the heart-rate ratio rather than a watch: always low confidence. */
    val fromHeartRate: Boolean,
)

/**
 * Fitness age: the age at which a typical person of your sex has your VO2max.
 *
 * Single watch readings are rough. In 19 adults, Apple Watch Series 7
 * underestimated lab VO2max by 4.5 mL/kg/min on average with about 16% error
 * (Caserman et al. 2024), so VO2max gets its own slow Kalman filter first, the
 * same machinery as Body:
 *
 *   sigma_v = 0.12 x reading,   sigma_w = 0.2 mL/kg/min per sqrt(day)
 *
 * which lets the true value drift about 1 mL/kg/min a month while one odd reading
 * barely moves it.
 */
object CardioFitness {

    /** The filter over one source's readings: the newest source wins, like every v2 baseline. */
    fun filter(readings: List<Vo2Reading>, config: FitnessConfig): Vo2Estimate? {
        val source = readings.maxByOrNull { it.day }?.source ?: return null
        val own = readings.filter { it.source == source }.sortedBy { it.day }
        var x = own.first().value
        var p = (config.readingNoiseShare * x).let { it * it }
        var last = own.first().day
        for (r in own.drop(1)) {
            val prior = p + config.driftPerSqrtDay * config.driftPerSqrtDay * (r.day - last)
            val noise = (config.readingNoiseShare * r.value).let { it * it }
            val gain = prior / (prior + noise)
            x += gain * (r.value - x)
            p = (1.0 - gain) * prior
            last = r.day
        }
        return Vo2Estimate(x, sqrt(p), own.size, last, source)
    }

    /**
     * Shown after 2 readings at least 14 days apart. The range runs the curve at
     * VO2max +/- the filter's spread, at least 3 mL/kg/min.
     */
    fun age(readings: List<Vo2Reading>, sex: BiologicalSex, config: FitnessConfig): FitnessAge? {
        val estimate = filter(readings, config) ?: return null
        val own = readings.filter { it.source == estimate.source }
        if (own.size < config.minReadings || own.maxOf { it.day } - own.minOf { it.day } < config.minSpanDays) return null
        return ageAt(estimate, sex, false, config)
    }

    /**
     * Without a watch VO2max: VO2max ~ 15.3 x HRmax / resting HR (Uth et al. 2004).
     * Approximate, always low confidence, and the caller hides it when the user says
     * medication affects their heart rate.
     */
    fun fromHeartRate(maxHr: Double, restingHr: Double, day: Long, sex: BiologicalSex, config: FitnessConfig): FitnessAge? {
        if (restingHr <= 0.0 || maxHr <= restingHr) return null
        val v = config.hrRatioFactor * maxHr / restingHr
        return ageAt(Vo2Estimate(v, config.readingNoiseShare * v, 0, day, "hr-ratio"), sex, true, config)
    }

    /**
     * Where the reference curve equals V, joining decade midpoints with straight
     * lines, extending past the ends with the nearest slope, clamped to 20-85:
     *
     *   FA = a_i + 10 (N(a_i) - V) / (N(a_i) - N(a_i+1)),  N(a_i) >= V >= N(a_i+1)
     */
    fun ageFor(v: Double, sex: BiologicalSex, config: FitnessConfig): Double {
        val ages = config.referenceAges
        val curve = when (sex) {
            BiologicalSex.MALE -> config.referenceMen
            BiologicalSex.FEMALE -> config.referenceWomen
            // ponytail: no sex given takes the midpoint of the two curves, as TRIMP does.
            BiologicalSex.UNSPECIFIED -> config.referenceMen.zip(config.referenceWomen) { m, w -> (m + w) / 2.0 }
        }
        // Past the fittest end extrapolates with the first segment, past the least fit with the last.
        val i = (0 until ages.size - 1).firstOrNull { v >= curve[it + 1] } ?: (ages.size - 2)
        val a = ages[i] + (ages[i + 1] - ages[i]) * (curve[i] - v) / (curve[i] - curve[i + 1])
        return a.coerceIn(config.minAge, config.maxAge)
    }

    private fun ageAt(e: Vo2Estimate, sex: BiologicalSex, fromHeartRate: Boolean, config: FitnessConfig): FitnessAge {
        val spread = max(e.sd, config.minSpread)
        // Higher VO2max reads younger, so the upper value gives the low age.
        return FitnessAge(
            age = ageFor(e.value, sex, config).roundToInt(),
            low = ageFor(e.value + spread, sex, config).roundToInt(),
            high = ageFor(e.value - spread, sex, config).roundToInt(),
            vo2max = e,
            fromHeartRate = fromHeartRate,
        )
    }
}
