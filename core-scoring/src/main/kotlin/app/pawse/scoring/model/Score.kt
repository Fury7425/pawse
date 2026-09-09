package app.pawse.scoring.model

import kotlinx.serialization.Serializable

@Serializable
enum class ScoreType { RECOVERY, SLEEP, STRAIN, ENERGY_BANK, LOAD_RATIO }

@Serializable
enum class Band { LOW, MODERATE, HIGH;
    companion object {
        /** Bevel / Whoop bands, documented identically in grok.txt §3 and §7. */
        fun ofRecovery(value: Int): Band = when {
            value <= 33 -> LOW
            value <= 66 -> MODERATE
            else -> HIGH
        }

        /**
         * Sleep bands. Oura publishes four: 85-100 Optimal, 70-84 Good, 60-69 Fair,
         * 0-59 Pay Attention (grok.txt §8). We collapse Good and Fair into MODERATE
         * because the app only ever paints three states, and because the 69/70
         * boundary is not one any report claims is meaningful.
         */
        fun ofSleep(value: Int): Band = when {
            value < 60 -> LOW
            value < 85 -> MODERATE
            else -> HIGH
        }
    }
}

/**
 * One term of a score, carrying everything the explainability screen needs.
 * Nothing here is recomputed in the UI — if it is shown, it is in this object.
 */
@Serializable
data class Contribution(
    val metric: Metric,
    /** Tonight's raw value in [Metric.unit]. Null when the input was absent. */
    val raw: Double?,
    /** Personal baseline mean over [baselineWindowDays]. Null while warming up. */
    val baselineMean: Double?,
    /** Personal baseline SD (or MAD-derived sigma in robust mode). */
    val baselineSd: Double?,
    val baselineWindowDays: Int,
    val baselineSampleCount: Int,
    /** Signed, direction-applied, clamped to +/-3. Null when absent or warming up. */
    val z: Double?,
    /** Weight actually used, after renormalisation for missing inputs. */
    val weight: Double,
    /** Weight this term would have had with full data. */
    val nominalWeight: Double,
    /** Signed points this term added to or removed from the final score. */
    val points: Double,
    val provenance: Provenance,
    val citation: String,
    val present: Boolean,
    /** True when the term is a discrete anomaly flag rather than a continuous z term. */
    val anomalyFlag: Boolean = false,
)

/**
 * A computed score, reproducible forever.
 *
 * [scoringVersion] and [configHash] are persisted with every row so that changing
 * a weight tomorrow does not silently rewrite yesterday's history. Recomputation
 * is explicit and versioned; the UI can show which config produced a past number.
 */
@Serializable
data class Score(
    val type: ScoreType,
    /** ISO-8601 local date the score belongs to (sleep night's wake date). */
    val date: String,
    val value: Int,
    val band: Band,
    val contributions: List<Contribution>,
    /**
     * Fraction of the nominal weight mass that was actually available, 0..1.
     * Never hidden: every integer this app prints is printed next to its coverage.
     */
    val dataCoverage: Float,
    /** True when coverage fell below the config's degraded threshold, or baselines are warming up. */
    val degraded: Boolean,
    val warmingUp: Boolean,
    val scoringVersion: String,
    val configHash: String,
) {
    init {
        require(dataCoverage in 0f..1f) { "coverage out of range: $dataCoverage" }
    }
}

/** Result of a scorer that could not produce a number at all. */
@Serializable
data class ScoreUnavailable(
    val type: ScoreType,
    val date: String,
    val reason: Reason,
    val missing: List<Metric>,
) {
    @Serializable
    enum class Reason { NO_SLEEP_SESSION, NO_REQUIRED_INPUT, BASELINE_WARMUP, COVERAGE_TOO_LOW }
}
