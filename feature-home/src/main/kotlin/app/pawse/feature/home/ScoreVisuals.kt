package app.pawse.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.pawse.core.ui.component.BaselineRowState
import app.pawse.core.ui.component.DeviationDirection
import app.pawse.core.ui.theme.PawseTheme
import app.pawse.scoring.model.BodyBand
import app.pawse.scoring.model.IllnessLevel
import app.pawse.scoring.model.SignalDetail
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Where a band becomes a colour. A rule, not styling: red is reserved for an
 * illness alert, so no single night is ever painted red.
 */
@Composable
fun bodyColor(band: BodyBand?, illness: IllnessLevel = IllnessLevel.NONE): Color {
    val s = PawseTheme.semantics
    return when {
        illness == IllnessLevel.ALERT -> s.bandLow
        band == BodyBand.ABOVE_NORMAL -> s.bandHigh
        band == BodyBand.BELOW_NORMAL || band == BodyBand.STRAINED -> s.bandModerate
        else -> s.neutral
    }
}

/** One of Body's two signals, as a row on the usual-range band. */
fun SignalDetail.toRowState(hrv: Boolean): BaselineRowState {
    val unit = if (hrv) "ms" else "bpm"
    val spreadText = if (hrv) {
        "± ${((exp(spread) - 1.0) * 100).roundToInt()}%"
    } else {
        String.format(Locale.getDefault(), "± %.1f bpm", spread)
    }
    return BaselineRowState(
        label = if (hrv) "Heart rate variability" else "Sleeping heart rate",
        valueText = tonight?.let { "${it.roundToInt()} $unit" } ?: "Not recorded",
        baselineText = "Usually ${baseline.roundToInt()} $unit $spreadText",
        deltaText = when {
            abs(z) < ScoreCopy.NEUTRAL_Z -> "About your normal"
            z > 0 -> "Better than your normal"
            else -> "Below your normal"
        },
        z = z.toFloat(),
        direction = when {
            abs(z) < ScoreCopy.NEUTRAL_Z -> DeviationDirection.NEUTRAL
            z > 0 -> DeviationDirection.BETTER
            else -> DeviationDirection.WORSE
        },
    )
}
