package app.pawse.core.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pawse.core.ui.theme.PawseTheme
import app.pawse.core.ui.theme.PawseType

/**
 * A state dot. [hollow] draws the "no score" ring; a null [color] with no ring
 * draws nothing but keeps its place, as a day before Pawse does.
 */
@Composable
fun Mark(color: Color?, modifier: Modifier = Modifier, hollow: Boolean = false, diameter: Dp = 8.dp) {
    val ring = MaterialTheme.colorScheme.onSurfaceVariant
    val s = PawseTheme.semantics
    // With shapes on, the band colours also carry a shape; every other colour stays a dot.
    val shape = if (LocalShapes.current) {
        when (color) {
            s.bandHigh -> 1
            s.bandModerate -> -1
            s.bandLow -> 2
            else -> 0
        }
    } else {
        0
    }
    Canvas(modifier.size(diameter)) {
        val w = this.size.width
        val h = this.size.height
        when {
            hollow -> drawCircle(ring, radius = this.size.minDimension / 2f - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
            color == null -> Unit
            shape == 1 -> drawPath(Path().apply { moveTo(w / 2f, 0f); lineTo(w, h); lineTo(0f, h); close() }, color)
            shape == -1 -> drawPath(Path().apply { moveTo(0f, 0f); lineTo(w, 0f); lineTo(w / 2f, h); close() }, color)
            shape == 2 -> drawRoundRect(color, cornerRadius = CornerRadius(1.dp.toPx()))
            else -> drawCircle(color)
        }
    }
}

/** One day on the week strip. A null [mark] and no [hollow] draws no dot: a day before Pawse. */
data class WeekDay(
    val name: String,
    val number: String,
    val mark: Color?,
    val hollow: Boolean,
    val description: String,
    val enabled: Boolean = true,
)

/**
 * The week strip Scores and Food share: seven tiles, the picked one on a raised
 * pill that slides 240 ms. The dot is that day's band; hollow means no score.
 */
@Composable
fun WeekStrip(days: List<WeekDay>, selected: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val raised = MaterialTheme.colorScheme.surfaceContainerHigh
    val faint = PawseTheme.semantics.faint
    val reduce = LocalReduceMotion.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = 4.dp
        val tile = (maxWidth - gap * (days.size - 1)) / days.size
        val x by animateDpAsState(
            (tile + gap) * selected.coerceAtLeast(0),
            if (reduce) snap() else tween(PawseMotion.SHEET, easing = EaseInOut),
            label = "day",
        )
        if (selected >= 0) {
            Box(Modifier.offset(x = x).size(tile, 64.dp).background(raised, RoundedCornerShape(12.dp)))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            days.forEachIndexed { i, d ->
                Column(
                    Modifier
                        .weight(1f)
                        .height(64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .pressable(enabled = d.enabled, role = Role.Tab) { onPick(i) }
                        .clearAndSetSemantics {
                            contentDescription = d.description
                            this.selected = i == selected
                            role = Role.Tab
                        }
                        .padding(top = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(d.name, style = PawseType.Micro, color = faint)
                    Text(d.number, style = PawseType.Heading)
                    Spacer(Modifier.height(4.dp))
                    Mark(d.mark, hollow = d.hollow, diameter = 6.dp)
                }
            }
        }
    }
}

/**
 * The design's track: the range of possible values, your usual as a band on it,
 * and either a fill from the left (so far today) or a marker (this morning).
 * Every value is a 0..1 share of the track.
 */
@Composable
fun RangeTrack(
    band: ClosedFloatingPointRange<Float>?,
    modifier: Modifier = Modifier,
    fill: Float? = null,
    marker: Float? = null,
    markerColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val semantics = PawseTheme.semantics
    val fillColor = MaterialTheme.colorScheme.onSurfaceVariant
    val card = MaterialTheme.colorScheme.surfaceContainer
    Canvas(modifier.fillMaxWidth().height(16.dp)) {
        val bar = 6.dp.toPx()
        val top = 5.dp.toPx()
        val r = CornerRadius(bar / 2f)
        drawRoundRect(semantics.baselineOuter, Offset(0f, top), Size(size.width, bar), r)
        band?.let {
            val from = it.start.coerceIn(0f, 1f)
            drawRoundRect(semantics.baselineBand, Offset(from * size.width, top), Size((it.endInclusive.coerceIn(0f, 1f) - from) * size.width, bar), r)
        }
        fill?.takeIf { it > 0f }?.let { drawRoundRect(fillColor, Offset(0f, top), Size(it.coerceIn(0f, 1f) * size.width, bar), r) }
        marker?.let {
            val c = Offset(it.coerceIn(0f, 1f) * size.width, size.height / 2f)
            drawCircle(card, 6.dp.toPx(), c)
            drawCircle(markerColor, 4.dp.toPx(), c)
        }
    }
}

/**
 * A selectable chip: 36 tall inside a 48 dp hit area, the one selection recipe.
 * Tags, portion presets and the compare chip all use it.
 */
@Composable
fun SelectChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val semantics = PawseTheme.semantics
    val fill by animateColorAsState(
        if (selected) semantics.selection else MaterialTheme.colorScheme.surfaceContainerHigh,
        tween(PawseMotion.PRESS_UP, easing = EaseOut),
        label = "chip",
    )
    val ink by animateColorAsState(
        if (selected) semantics.onSelection else MaterialTheme.colorScheme.onSurfaceVariant,
        tween(PawseMotion.PRESS_UP, easing = EaseOut),
        label = "chipInk",
    )
    Box(
        modifier
            .heightIn(min = 48.dp)
            .pressable(role = Role.Checkbox, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = text
                this.selected = selected
                role = Role.Checkbox
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = if (selected) PawseType.Label.copy(fontWeight = PawseType.BodyStrong.fontWeight) else PawseType.Label,
            color = ink,
            maxLines = 1,
            modifier = Modifier.height(36.dp).background(fill, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

/** A label row: the name on the left, an aside on the right, on one baseline. */
@Composable
fun SplitRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom, content = content)
}
