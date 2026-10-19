package app.pawse.scoring.body

import app.pawse.scoring.config.BodyConfig
import app.pawse.scoring.math.Stats
import app.pawse.scoring.model.Confidence
import app.pawse.scoring.model.Signal
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One signal on one day, after the filter. Model units: ln ms for HRV, bpm for HR. */
data class SignalState(
    val source: String,
    /** Filtered state. */
    val x: Double,
    /** Filter variance today. Grows on its own across missed nights. */
    val p: Double,
    /** Last night's raw value from this source, if there was one. */
    val tonight: Double?,
    /** Your normal: median filtered state over the baseline window. */
    val baseline: Double?,
    /** Robust spread of nightly values over the same window. */
    val spread: Double?,
    val baselineNights: Int,
    val lastNightDay: Long,
) {
    /** (x - B) / sigma, before the direction sign. Null while there is no baseline. */
    fun z(clamp: Double): Double? {
        val b = baseline ?: return null
        val s = spread ?: return null
        return ((x - b) / s).coerceIn(-clamp, clamp)
    }

    fun rawZ(clamp: Double): Double? {
        val t = tonight ?: return null
        val b = baseline ?: return null
        val s = spread ?: return null
        return ((t - b) / s).coerceIn(-clamp, clamp)
    }

    /** Variance of z implied by the filter's own uncertainty. */
    val zVariance: Double? get() = spread?.let { p / (it * it) }
}

/**
 * Body State: a Kalman filter that treats each night as a noisy reading of your
 * true state and trusts it only as much as its quality allows.
 *
 *   P-_t = P_t-1 + sigma_w^2
 *   K_t  = P-_t / (P-_t + sigma_v^2 / q_t)
 *   x_t  = x_t-1 + K_t (y_t - x_t-1)
 *   P_t  = (1 - K_t) P-_t
 *
 * Like a phone's GPS dot: it does not teleport on a jumpy reading, and it widens
 * its circle when the signal is weak. That circle is the range and the
 * confidence, which therefore can never disagree with the score's own maths.
 *
 * One track per data source. Devices disagree by whole units (Oura's HRV ran
 * 9 ms above Fitbit's in Terra's comparison), so a baseline is only ever built
 * from one source's nights, and a new watch starts its own.
 */
class SignalFilter(
    private val config: BodyConfig,
    /** Night-to-night noise SD to assume until a source has nights of its own. */
    private val defaultNoise: Double,
) {
    private data class Entry(val day: Long, val x: Double, val y: Double)

    private class Track(var x: Double, var p: Double, var lastDay: Long) {
        val history = mutableListOf<Entry>()
    }

    private val tracks = LinkedHashMap<String, Track>()
    private var currentSource: String? = null

    /** Advance to [day], updating with last night's reading when there is one. */
    fun step(day: Long, reading: Signal?, quality: Double): SignalState? {
        if (reading != null) update(day, reading, quality)
        val source = currentSource ?: return null
        val track = tracks.getValue(source)
        val noise = noiseVariance(track, day)
        val p = track.p + config.processToNoise * noise * (day - track.lastDay)

        val window = baselineWindow(track, day)
        return SignalState(
            source = source,
            x = track.x,
            p = p,
            tonight = reading?.takeIf { it.source == source }?.value,
            baseline = window?.let { w -> Stats.median(w.map { it.x }) },
            spread = window?.let { w ->
                Stats.robustSpread(w.map { it.y })?.takeIf { it > 1e-6 } ?: sqrt(noise)
            },
            baselineNights = window?.size ?: 0,
            lastNightDay = track.lastDay,
        )
    }

    /** Raw nightly values of [source] by day, for correlations and weekly readings. */
    fun nights(source: String, from: Long, to: Long): Map<Long, Double> =
        tracks[source]?.history?.filter { it.day in from..to }?.associate { it.day to it.y }.orEmpty()

    private fun update(day: Long, reading: Signal, quality: Double) {
        val y = reading.value
        val track = tracks[reading.source]
        if (track == null) {
            val fresh = Track(x = y, p = defaultNoise * defaultNoise, lastDay = day)
            fresh.history += Entry(day, y, y)
            tracks[reading.source] = fresh
        } else {
            val noise = noiseVariance(track, day)
            val prior = track.p + config.processToNoise * noise * (day - track.lastDay)
            val innovation = y - track.x
            // More than three spreads from the estimate counts at half weight. Its
            // raw value still reaches the illness detector, which reads y, not x.
            val spread = sqrt(prior + noise / quality)
            val q = if (abs(innovation) > config.outlierSpreads * spread) quality / 2.0 else quality
            val gain = prior / (prior + noise / q)
            track.x += gain * innovation
            track.p = (1.0 - gain) * prior
            track.lastDay = day
            track.history += Entry(day, track.x, y)
        }
        currentSource = reading.source
    }

    /**
     * sigma_v^2 from the user's own consecutive nights before [day]:
     * var(y_t - y_t-1) = 2 sigma_v^2 + sigma_w^2, estimated robustly.
     */
    private fun noiseVariance(track: Track, day: Long): Double {
        val recent = track.history.filter { it.day < day && it.day >= day - config.baselineDays }
        val diffs = recent.zipWithNext().filter { (a, b) -> b.day - a.day == 1L }.map { (a, b) -> b.y - a.y }
        if (diffs.size >= config.minNightsForNoise) {
            val s = Stats.robustSpread(diffs)
            if (s != null && s > 1e-6) return s * s / (2.0 + config.processToNoise)
        }
        return defaultNoise * defaultNoise
    }

    /**
     * The 60 days ending a week ago, so today never grades itself. A new user has
     * no nights that old yet, so until the lagged window holds enough nights the
     * window is simply every night before today.
     */
    private fun baselineWindow(track: Track, day: Long): List<Entry>? {
        val lagged = track.history.filter {
            it.day in (day - config.baselineLagDays - config.baselineDays + 1)..(day - config.baselineLagDays)
        }
        if (lagged.size >= config.minValidNights) return lagged
        val recent = track.history.filter { it.day in (day - config.baselineDays)..(day - 1) }
        return recent.takeIf { it.size >= config.minValidNights }
    }
}

/**
 * Combining HRV and sleeping HR without counting the same nervous system twice.
 *
 *   z_B = (w z_h + (1 - w) z_r) / sqrt(w^2 + (1 - w)^2 + 2 w (1 - w) rho)
 *
 * rho = 1 makes them one piece of evidence; rho = 0 makes agreement count for more.
 */
object BodyMath {

    fun combine(zh: Double?, zr: Double?, w: Double, rho: Double): Double? = when {
        zh != null && zr != null -> (w * zh + (1 - w) * zr) / denominator(w, rho)
        zh != null -> zh
        else -> zr
    }

    /** Variance of the combined z, from each filter's own variance. */
    fun combinedVariance(vh: Double?, vr: Double?, w: Double, rho: Double): Double? = when {
        vh != null && vr != null -> {
            val cov = rho * sqrt(vh * vr)
            (w * w * vh + (1 - w) * (1 - w) * vr + 2 * w * (1 - w) * cov) / (denominator(w, rho).let { it * it })
        }
        vh != null -> vh
        else -> vr
    }

    /** Pulled toward the prior until there are nights, clamped to 0..1. */
    fun shrunkRho(observed: Double?, nights: Int, config: BodyConfig): Double {
        val n = if (observed == null) 0.0 else nights.toDouble()
        val r = observed ?: config.rhoPrior
        return ((n * r + config.rhoPriorNights * config.rhoPrior) / (n + config.rhoPriorNights)).coerceIn(0.0, 1.0)
    }

    /** Body = 100 x Phi(z_B). Body 70 is roughly "better than 70% of your normal days". */
    fun score(z: Double): Int = (100.0 * Stats.phi(z)).roundToInt().coerceIn(0, 100)

    fun confidence(zSd: Double, config: BodyConfig): Confidence = when {
        zSd <= config.highConfidenceSd -> Confidence.HIGH
        zSd <= config.mediumConfidenceSd -> Confidence.MEDIUM
        else -> Confidence.LOW
    }

    private fun denominator(w: Double, rho: Double): Double =
        sqrt(w * w + (1 - w) * (1 - w) + 2 * w * (1 - w) * rho)
}
