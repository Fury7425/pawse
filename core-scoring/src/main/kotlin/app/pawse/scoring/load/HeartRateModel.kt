package app.pawse.scoring.load

import app.pawse.scoring.config.LoadConfig
import app.pawse.scoring.model.MaxHeartRate
import app.pawse.scoring.model.Provenance
import app.pawse.scoring.model.UserProfile

/**
 * Heart-rate reserve, and the HRmax that anchors it. When HRmax is estimated the
 * estimate is labelled: a real maximum can sit twenty beats either side of Tanaka.
 */
object HeartRateModel {

    /** Tanaka, Monahan & Seals 2001: HRmax = 208 - 0.7 x age. */
    fun tanakaMax(ageYears: Int, config: LoadConfig): Double =
        config.tanakaIntercept - config.tanakaSlope * ageYears

    /**
     * @param observedMaxHeartRate the highest heart rate actually recorded. It can
     *   raise an age estimate that is provably too low, never lower one.
     * @return null with neither a measured maximum nor an age.
     */
    fun maxHeartRate(
        profile: UserProfile,
        config: LoadConfig,
        observedMaxHeartRate: Double? = null,
    ): MaxHeartRate? {
        profile.measuredMaxHeartRate?.let {
            return MaxHeartRate(it, Provenance.PUBLISHED, "Measured maximum you entered.", estimated = false)
        }
        val age = profile.ageYears ?: return null
        val tanaka = tanakaMax(age, config)
        if (observedMaxHeartRate != null && observedMaxHeartRate > tanaka) {
            return MaxHeartRate(
                bpm = observedMaxHeartRate,
                provenance = Provenance.INFERRED,
                citation = "Your age-based estimate was ${tanaka.toInt()}, but your watch has recorded " +
                    "${observedMaxHeartRate.toInt()}, so the higher number is used.",
                estimated = true,
            )
        }
        return MaxHeartRate(
            bpm = tanaka,
            provenance = Provenance.PUBLISHED,
            citation = "Estimated from your age: Tanaka 2001, HRmax = " +
                "${config.tanakaIntercept.toInt()} - ${config.tanakaSlope} x age.",
            estimated = true,
        )
    }

    /** x = (HRex - HRrest) / (HRmax - HRrest), clamped to 0..1. Null when the reserve is not positive. */
    fun reserveFraction(meanHeartRate: Double, restingHeartRate: Double, maxHeartRate: Double): Double? {
        val reserve = maxHeartRate - restingHeartRate
        if (reserve <= 0.0) return null
        return ((meanHeartRate - restingHeartRate) / reserve).coerceIn(0.0, 1.0)
    }
}
