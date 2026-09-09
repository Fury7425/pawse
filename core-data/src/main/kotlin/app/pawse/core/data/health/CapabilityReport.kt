package app.pawse.core.data.health

import app.pawse.scoring.config.SleepProfile
import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.ScoreType

/**
 * What the probe found, phrased so the onboarding screen can be honest rather than
 * optimistic. Every field here exists because some real writer app breaks an
 * assumption an iOS-only app is allowed to make.
 */
data class CapabilityReport(
    val probedDays: Int,
    val nightsWithSleep: Int,
    val metrics: List<MetricCapability>,
    val scores: List<ScoreCapability>,
    /** Package names of every app that wrote any record in the window. */
    val writerApps: Set<String>,
    /** Record types written by more than one app, i.e. duplicate risk. */
    val contestedTypes: Set<String>,
    val sleepStageCoverage: Float,
    val hrvShape: HrvShape,
) {
    val usable: Boolean get() = scores.any { it.computable }
}

data class MetricCapability(
    val metric: Metric,
    /** Days in the window with at least one usable value, over probed days. */
    val coverage: Float,
    val daysWithData: Int,
    val writerApps: Set<String>,
    val note: String? = null,
) {
    val present: Boolean get() = daysWithData > 0
}

data class ScoreCapability(
    val type: ScoreType,
    val computable: Boolean,
    /** Fraction of nominal weight mass available, from the metrics actually present. */
    val expectedCoverage: Float,
    val degraded: Boolean,
    /** Which sleep profile the probe would select. Null for non-sleep scores. */
    val selectedProfile: SleepProfile? = null,
    val missing: List<Metric> = emptyList(),
    /** One sentence, plain language, shown under the score name at onboarding. */
    val explanation: String,
)

/**
 * How the writer app actually delivers HRV, which varies far more on Android than
 * on iOS. Garmin Connect and Zepp tend to write a nightly overnight value; some
 * apps write only sparse daytime spot readings, which are useless for a
 * sleep-window-only Recovery score and must not be silently substituted.
 */
enum class HrvShape {
    /** No HRV records at all. Recovery loses its dominant term. */
    ABSENT,

    /** Values inside sleep windows on most nights. What Recovery needs. */
    OVERNIGHT_SERIES,

    /** Values exist, but mostly outside sleep windows. Not a recovery signal. */
    DAYTIME_SPOT_ONLY,

    /** Some overnight values, but on a minority of nights. Recovery runs degraded. */
    SPARSE_OVERNIGHT,
}
