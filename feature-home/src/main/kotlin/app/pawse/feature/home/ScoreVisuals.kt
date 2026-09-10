package app.pawse.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.pawse.core.ui.component.BaselineRowState
import app.pawse.core.ui.component.DeviationDirection
import app.pawse.core.ui.theme.PawseTheme
import app.pawse.scoring.model.Band
import app.pawse.scoring.model.Contribution
import app.pawse.scoring.model.ScoreType
import kotlin.math.abs

/**
 * Where a band becomes a colour, and a contribution becomes a row.
 *
 * Both mappings live here rather than in the composables because both are rules
 * rather than styling. In particular: [bandColor] refuses to paint Strain with the
 * Recovery palette. Strain's bands are magnitude — a hard day is a lot of load,
 * not bad news — and reusing the red-means-stop colours would tell the user
 * something the engine never said.
 */
@Composable
fun bandColor(type: ScoreType, band: Band): Color {
    val semantics = PawseTheme.semantics
    return when (type) {
        // Neutral throughout. The question "was that appropriate?" is the Target
        // Strain comparison, and it is answered in words, not in hue.
        ScoreType.STRAIN -> semantics.neutral

        ScoreType.LOAD_RATIO -> when (band) {
            Band.HIGH -> semantics.bandHigh
            Band.MODERATE -> semantics.bandModerate
            Band.LOW -> semantics.bandLow
        }

        else -> when (band) {
            Band.LOW -> semantics.bandLow
            Band.MODERATE -> semantics.bandModerate
            Band.HIGH -> semantics.bandHigh
        }
    }
}

/** A scored term, in the shape the baseline-band component wants. */
fun Contribution.toRowState(): BaselineRowState = BaselineRowState(
    label = ScoreCopy.metricLabel(metric),
    valueText = raw?.let { ScoreCopy.formatRaw(metric, it) } ?: "—",
    baselineText = ScoreCopy.baselineText(this),
    deltaText = ScoreCopy.deltaText(this),
    pointsText = ScoreCopy.pointsText(this),
    z = z?.toFloat(),
    direction = direction(),
)

private fun Contribution.direction(): DeviationDirection {
    val value = z ?: return DeviationDirection.UNKNOWN
    if (!present) return DeviationDirection.UNKNOWN
    return when {
        abs(value) < ScoreCopy.NEUTRAL_Z -> DeviationDirection.NEUTRAL
        value > 0 -> DeviationDirection.BETTER
        else -> DeviationDirection.WORSE
    }
}
