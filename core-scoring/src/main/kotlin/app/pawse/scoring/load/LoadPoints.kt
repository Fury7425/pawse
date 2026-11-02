package app.pawse.scoring.load

import app.pawse.scoring.config.LoadConfig
import app.pawse.scoring.config.PointsConfig
import app.pawse.scoring.math.Stats
import app.pawse.scoring.model.BiologicalSex
import app.pawse.scoring.model.PointsRange
import kotlin.math.roundToInt

/** Walking minutes by cadence band, for stretches with too little heart rate. */
data class CadenceMinutes(val moderate: Double, val vigorous: Double)

/**
 * Load points: the ring's scale, which v2 never defined. TRIMP is scaled so your
 * own typical active day is 10:
 *
 *   P = 10 L / L_ref
 *
 * L_ref is your median TRIMP on active days over 60 days, 60 until there are 10
 * active days (roughly a 45-minute moderate session). The design's range rule then
 * becomes personal without changing.
 */
object LoadPoints {

    fun points(trimp: Double, reference: Double, config: PointsConfig): Double =
        config.pointsAtReference * trimp / reference

    /** [activeDayLoads]: TRIMP on each active day in the window, oldest first. */
    fun reference(activeDayLoads: List<Double>, config: PointsConfig): Double =
        activeDayLoads.takeLast(config.referenceDays)
            .takeIf { it.size >= config.minActiveDays }
            ?.let { Stats.median(it) }
            ?.takeIf { it > 0.0 }
            ?: config.defaultReference

    /**
     * r = round(5 + Body / 20), range r to r + 5: Body 50 gives 8-13 around your usual
     * 10, Body 78 gives 9-14. With the illness alert on, or Body Strained two mornings
     * running, the range drops to 0-5 and the guidance says rest or easy.
     */
    fun range(body: Int?, illnessAlert: Boolean, strainedRun: Int, config: PointsConfig): PointsRange {
        if (illnessAlert || strainedRun >= 2) return PointsRange(0, config.restHigh, true)
        val r = (config.rangeBase + (body ?: 50) / config.rangeBodyDivisor).roundToInt()
        return PointsRange(r, r + config.rangeWidth, false)
    }

    /** The ring's fill: the track runs to 16; past that the arc stays full and the number keeps counting. */
    fun ringFraction(points: Double, config: PointsConfig): Float =
        (points / config.ringPoints).coerceIn(0.0, 1.0).toFloat()

    /**
     * Walking with no heart-rate coverage counts by cadence: about 100 steps a minute
     * marks moderate intensity in adults, and early evidence puts vigorous above 130
     * (Tudor-Locke et al. 2018). Those minutes go through Banister's TRIMP at an
     * assumed heart-rate reserve, so 40 minutes at 100-130 is about 27 TRIMP for a man.
     * Minutes inside a session with heart rate never also get step credit; the caller
     * passes only uncovered minutes.
     */
    fun cadenceTrimp(minutes: CadenceMinutes, sex: BiologicalSex, points: PointsConfig, load: LoadConfig): Double =
        Trimp.banister(minutes.moderate, points.moderateReserve, sex, load).load +
            Trimp.banister(minutes.vigorous, points.vigorousReserve, sex, load).load

    /** Minutes by cadence band from per-minute step counts. Under 100 a minute does not count. */
    fun cadenceMinutes(stepsPerMinute: List<Double>, config: PointsConfig): CadenceMinutes = CadenceMinutes(
        moderate = stepsPerMinute.count { it >= config.moderateCadence && it <= config.vigorousCadence }.toDouble(),
        vigorous = stepsPerMinute.count { it > config.vigorousCadence }.toDouble(),
    )
}
