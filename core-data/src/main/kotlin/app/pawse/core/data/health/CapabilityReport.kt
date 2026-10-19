package app.pawse.core.data.health

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
    /** Days in the window a scale wrote a weight. The Body tab, not a score. */
    val weighIns: Int = 0,
    /** Which body measurements the scale writes besides weight: "body fat", "lean mass"... */
    val bodyParts: List<String> = emptyList(),
    /**
     * Samsung Health shares HRV, skin temperature, breathing rate and resting HR
     * with Health Connect only when each is allowed in Samsung Health's own
     * settings. Null when Samsung Health is not a writer.
     */
    val samsungChecklist: List<Pair<String, Boolean>>? = null,
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
    /** Share of what the card reads that was actually found. */
    val expectedCoverage: Float,
    val degraded: Boolean,
    val missing: List<Metric> = emptyList(),
    /** One sentence, plain language, shown under the card name at onboarding. */
    val explanation: String,
)

/**
 * How the writer app actually delivers HRV, which varies far more on Android than
 * on iOS. Some apps write only sparse daytime spot readings, which are useless for
 * an overnight signal and must not be silently substituted.
 */
enum class HrvShape {
    ABSENT,
    OVERNIGHT_SERIES,
    DAYTIME_SPOT_ONLY,
    SPARSE_OVERNIGHT,
}
