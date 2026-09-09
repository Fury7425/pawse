package app.pawse.scoring.strain

import app.pawse.scoring.config.StrainConfig
import app.pawse.scoring.model.MaxHeartRate
import app.pawse.scoring.model.Provenance
import app.pawse.scoring.model.UserProfile

/**
 * Heart-rate reserve, and the HRmax that anchors it.
 *
 * Every load model in the reports runs on a fraction of something, and the
 * something is almost always heart-rate reserve. Banister's x is HR reserve;
 * Fitbit's and Apple's zone multipliers are HR-reserve bands; Edwards' zones are
 * %HRmax. So the accuracy of the whole strain side rests on two numbers the user
 * may not have given us.
 *
 * When HRmax has to be estimated, the estimate is labelled. Tanaka is a
 * population regression, and a real HRmax can sit twenty beats either side of it.
 * The reports are explicit about how much that matters: Firstbeat reports that a
 * 15 bpm HRmax error moves an estimated VO2max by 7-9% (claude.md §1), and the
 * same sensitivity runs straight through TRIMP.
 */
object HeartRateModel {

    /**
     * Tanaka, Monahan & Seals 2001: HRmax = 208 - 0.7 x age. PUBLISHED.
     * Used only when the user has entered no measured maximum.
     */
    fun tanakaMax(ageYears: Int, config: StrainConfig): Double =
        config.tanakaIntercept - config.tanakaSlope * ageYears

    /**
     * @param observedMaxHeartRate the highest heart rate actually recorded in the
     *   data we are scoring. Used only to raise a Tanaka estimate that is provably
     *   too low: if the user hit 191 bpm, their maximum is at least 191, whatever
     *   the population regression says. It never lowers an estimate, because
     *   nobody's absence of a maximal effort is evidence of a low ceiling.
     * @return null when neither a measured maximum nor an age is available. There
     *   is no third option: guessing an age would be inventing the input the whole
     *   load model divides by.
     */
    fun maxHeartRate(
        profile: UserProfile,
        config: StrainConfig,
        observedMaxHeartRate: Double? = null,
    ): MaxHeartRate? {
        profile.measuredMaxHeartRate?.let {
            return MaxHeartRate(
                bpm = it,
                provenance = Provenance.PUBLISHED,
                citation = "Measured maximum you entered.",
                estimated = false,
            )
        }
        val age = profile.ageYears ?: return null
        val tanaka = tanakaMax(age, config)
        if (observedMaxHeartRate != null && observedMaxHeartRate > tanaka) {
            return MaxHeartRate(
                bpm = observedMaxHeartRate,
                provenance = Provenance.INFERRED,
                citation = "Your age-based estimate was ${tanaka.toInt()}, but your watch has " +
                    "recorded ${observedMaxHeartRate.toInt()}, so we use the higher number. " +
                    "Enter a measured maximum in settings for a better one.",
                estimated = true,
            )
        }
        return MaxHeartRate(
            bpm = tanaka,
            provenance = Provenance.PUBLISHED,
            citation = "Estimated from your age: Tanaka 2001, HRmax = " +
                "${config.tanakaIntercept.toInt()} - ${config.tanakaSlope} x age. " +
                "A real maximum can sit well either side of this.",
            estimated = true,
        )
    }

    /**
     * Fraction of heart-rate reserve: x = (HRex - HRrest) / (HRmax - HRrest).
     *
     * Clamped to 0..1. Below zero means the session's mean heart rate sat under
     * the user's resting rate, which is a sensor or baseline problem rather than a
     * workout; above one means the mean exceeded the maximum, which means the
     * maximum is wrong. Both clamp rather than throw, because the alternative is
     * dropping a real session over a bad denominator.
     */
    fun reserveFraction(meanHeartRate: Double, restingHeartRate: Double, maxHeartRate: Double): Double? {
        val reserve = maxHeartRate - restingHeartRate
        if (reserve <= 0.0) return null
        return ((meanHeartRate - restingHeartRate) / reserve).coerceIn(0.0, 1.0)
    }

    /**
     * Edwards zone index 1..5 for a heart rate, by %HRmax band:
     * 50-60, 60-70, 70-80, 80-90, 90-100. Anything under 50% is zone 0 and
     * carries no load. PUBLISHED.
     */
    fun edwardsZone(heartRate: Double, maxHeartRate: Double): Int {
        if (maxHeartRate <= 0.0) return 0
        val percent = 100.0 * heartRate / maxHeartRate
        return when {
            percent < 50.0 -> 0
            percent < 60.0 -> 1
            percent < 70.0 -> 2
            percent < 80.0 -> 3
            percent < 90.0 -> 4
            else -> 5
        }
    }
}
