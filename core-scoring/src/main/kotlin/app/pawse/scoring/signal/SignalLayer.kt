package app.pawse.scoring.signal

import app.pawse.scoring.config.SignalConfig
import app.pawse.scoring.math.Stats
import kotlin.math.ln

/** ln of the night's median RMSSD, and how far to trust it. */
data class HrvNight(val lnRmssd: Double, val records: Int, val penalty: Double)

/** What the raw heart-rate series of one night yields. */
data class HeartRateNight(
    /** Lowest 30-minute rolling mean. Null when the series is too sparse to trust. */
    val sleepingBpm: Double?,
    /** When that window sat, 0 = lights-out, 1 = waking. */
    val nadirFraction: Double?,
    /** Share of the night with any heart-rate data, in coarse bins. */
    val coverage: Double,
)

/**
 * Signal layer: one definition per nightly metric, computed by Pawse itself from
 * raw Health Connect series when they are dense enough, the watch's own value
 * otherwise. Pure functions; the data layer feeds them.
 */
object SignalLayer {

    /**
     * h_t = ln(median{RMSSD_i in night}). The log treats 30 -> 40 ms as the bigger
     * deal it is; the median ignores the reading taken while you rolled over.
     *
     * @param inNight every RMSSD record inside the night window.
     * @param fallback the vendor's single nightly value, used under [SignalConfig.hrvMinRecords].
     */
    fun hrv(inNight: List<Double>, fallback: Double?, config: SignalConfig = SignalConfig()): HrvNight? {
        val valid = inNight.filter { it in config.hrvMinMs..config.hrvMaxMs }
        val rangePenalty = if (valid.size < inNight.size) config.outOfRangePenalty else 1.0
        if (valid.size >= config.hrvMinRecords) {
            return HrvNight(ln(Stats.median(valid)!!), valid.size, rangePenalty)
        }
        val single = fallback?.takeIf { it in config.hrvMinMs..config.hrvMaxMs } ?: Stats.median(valid)
            ?: return null
        return HrvNight(ln(single), valid.size, rangePenalty * config.fewRecordsPenalty)
    }

    /**
     * Sleeping HR as the lowest 30-minute rolling mean, and when it happened.
     *
     * Needs a sample every [SignalConfig.hrBinMinutes] minutes across most of the
     * night. Galaxy watches have been reported to sample every ten minutes when
     * inactive, which fails this on purpose: the caller then falls back to the
     * vendor's RestingHeartRateRecord rather than reading a nadir out of six points.
     */
    fun heartRate(
        samples: List<Pair<Long, Double>>,
        startMs: Long,
        endMs: Long,
        config: SignalConfig = SignalConfig(),
    ): HeartRateNight {
        val nightMs = endMs - startMs
        if (nightMs <= 0) return HeartRateNight(null, null, 0.0)
        val inside = samples.filter { it.first in startMs until endMs && it.second > 0.0 }

        val coarseMs = config.coverageBinMinutes * 60_000L
        val coarseBins = ((nightMs + coarseMs - 1) / coarseMs).toInt()
        val coverage = inside.map { ((it.first - startMs) / coarseMs).toInt() }.toSet().size.toDouble() / coarseBins

        val binMs = config.hrBinMinutes * 60_000L
        val binCount = ((nightMs + binMs - 1) / binMs).toInt()
        val sums = DoubleArray(binCount)
        val counts = IntArray(binCount)
        for ((t, bpm) in inside) {
            val i = ((t - startMs) / binMs).toInt().coerceIn(0, binCount - 1)
            sums[i] += bpm
            counts[i]++
        }
        val filled = counts.count { it > 0 }
        if (filled.toDouble() / binCount < config.hrDenseShare) return HeartRateNight(null, null, coverage)

        val window = (config.hrWindowMinutes / config.hrBinMinutes).coerceAtLeast(1)
        var best: Double? = null
        var bestCentre = 0
        for (start in 0..(binCount - window)) {
            val bins = (start until start + window).filter { counts[it] > 0 }
            // A window needs most of its bins to be a 30-minute mean at all.
            if (bins.size < window * 0.8) continue
            val m = bins.sumOf { sums[it] / counts[it] } / bins.size
            if (best == null || m < best) {
                best = m
                bestCentre = start + window / 2
            }
        }
        return HeartRateNight(
            sleepingBpm = best,
            nadirFraction = best?.let { (bestCentre * binMs).toDouble() / nightMs },
            coverage = coverage,
        )
    }

    /**
     * q in 0..1: the share of the night with heart-rate data, times the HRV
     * penalty. A night with no heart-rate series at all gets a neutral coverage
     * rather than zero, so a watch that never writes raw heart rate still scores.
     */
    fun quality(hrCoverage: Double?, hrv: HrvNight?, config: SignalConfig = SignalConfig()): Double {
        val coverage = hrCoverage?.takeIf { it > 0.0 } ?: config.unknownCoverage
        return (coverage * (hrv?.penalty ?: 1.0)).coerceIn(config.minQuality, 1.0)
    }

    /** Median of an overnight series (breathing, SpO2), or null under the minimum count. */
    fun overnightMedian(values: List<Double>, config: SignalConfig = SignalConfig()): Double? =
        if (values.size >= config.minRecordsOvernight) Stats.median(values) else null
}
