package app.pawse.core.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pawse.core.ui.theme.PawseTheme
import app.pawse.core.ui.theme.PawseType

/**
 * Which way tonight fell against the user's own baseline.
 *
 * [UNKNOWN] is not "no change". It is the state where there is no baseline yet, or
 * the input was absent, and it renders as neutral grey with a word that says so.
 */
enum class DeviationDirection { BETTER, WORSE, NEUTRAL, UNKNOWN }

/**
 * One metric, drawn against the range it usually occupies.
 *
 * This is the component the whole app is built around. Every report converges on
 * the same conclusion — grok.txt closes with it, claude.md §11 says the weighting
 * is the secret and the baseline is the method — that a wearable number means
 * nothing except relative to your own history. So the app shows the history and
 * the number's place in it, rather than the number alone.
 *
 * The track spans plus or minus three sigma, which is exactly the engine's z
 * clamp: a marker pinned at either end means "at or beyond the limit we are
 * willing to score", not "off the chart". The inner band is plus or minus one
 * sigma, the outer plus or minus two.
 */
data class BaselineRowState(
    val label: String,
    /** Tonight, in its own unit. "48 ms". */
    val valueText: String,
    /** "Usually 52 ± 6 ms over 43 nights", or why there is no baseline. */
    val baselineText: String,
    /** Signed, in sigmas, in words. Never hue alone. */
    val deltaText: String,
    /** Signed points this term moved the score, already formatted. */
    val pointsText: String? = null,
    /** Direction-applied z, clamped by the engine. Null when there is nothing to place. */
    val z: Float? = null,
    val direction: DeviationDirection = DeviationDirection.UNKNOWN,
)

@Composable
fun BaselineBandRow(
    state: BaselineRowState,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val semantics = PawseTheme.semantics
    val markerColor = when (state.direction) {
        DeviationDirection.BETTER -> semantics.better
        DeviationDirection.WORSE -> semantics.worse
        DeviationDirection.NEUTRAL -> semantics.neutral
        DeviationDirection.UNKNOWN -> semantics.neutral
    }

    val described = buildString {
        append(state.label)
        append(", ")
        append(state.valueText)
        append(". ")
        append(state.deltaText)
        append(". ")
        append(state.baselineText)
        state.pointsText?.let {
            append(". ")
            append(it)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp)
            .clearAndSetSemantics { contentDescription = described },
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = state.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = state.valueText,
                style = PawseType.MetricValue,
                color = MaterialTheme.colorScheme.onSurface,
            )
            state.pointsText?.let {
                Spacer(Modifier.width(12.dp))
                Text(
                    text = it,
                    style = PawseType.Delta,
                    color = markerColor,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        BaselineBand(z = state.z, markerColor = markerColor, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))

        Row {
            Text(
                text = state.deltaText,
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = state.baselineText,
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The band itself, without any text.
 *
 * @param z direction-applied and clamped, so positive is always "better than your
 *   baseline" whatever the metric's physical direction. A resting heart rate above
 *   your usual arrives here negative. That inversion is why the marker is never
 *   labelled with a raw unit.
 */
@Composable
fun BaselineBand(
    z: Float?,
    markerColor: Color,
    modifier: Modifier = Modifier,
    height: Dp = 12.dp,
) {
    val semantics = PawseTheme.semantics
    val outline = MaterialTheme.colorScheme.outline

    Canvas(modifier = modifier.height(height)) {
        val trackHeight = size.height
        val radius = CornerRadius(trackHeight / 2f, trackHeight / 2f)

        // The whole track: plus or minus three sigma, the engine's clamp.
        drawRoundRect(
            color = semantics.baselineOuter,
            topLeft = Offset.Zero,
            size = Size(size.width, trackHeight),
            cornerRadius = radius,
        )

        // Inner band: plus or minus one sigma, which is where two nights in three sit.
        val innerWidth = size.width / 3f
        drawRoundRect(
            color = semantics.baselineBand,
            topLeft = Offset(size.width / 2f - innerWidth / 2f, 0f),
            size = Size(innerWidth, trackHeight),
            cornerRadius = radius,
        )

        // The baseline itself.
        drawLine(
            color = outline,
            start = Offset(size.width / 2f, 0f),
            end = Offset(size.width / 2f, trackHeight),
            strokeWidth = 1.dp.toPx(),
        )

        if (z != null) {
            val clamped = z.coerceIn(-Z_EXTENT, Z_EXTENT)
            val fraction = (clamped + Z_EXTENT) / (2f * Z_EXTENT)
            val markerWidth = 4.dp.toPx()
            val x = (fraction * size.width).coerceIn(markerWidth / 2f, size.width - markerWidth / 2f)
            drawRoundRect(
                color = markerColor,
                topLeft = Offset(x - markerWidth / 2f, 0f),
                size = Size(markerWidth, trackHeight),
                cornerRadius = CornerRadius(markerWidth / 2f, markerWidth / 2f),
            )
        }
    }
}

/** The engine clamps z to plus or minus three; the track shows exactly that range. */
private const val Z_EXTENT = 3f
