package app.pawse.core.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pawse.core.ui.theme.PawseTheme
import kotlin.math.cos
import kotlin.math.sin

/**
 * A score as a ring: the track, the arc, and two hairline ticks at the band
 * boundaries so a third of the way round always means the same thing.
 *
 * Drawn on a 100-unit grid to match the design: radius 42, stroke 7. A [glow] is
 * for band colours only; Strain's ring is grey and gets none. [targetFraction]
 * adds the long tick Strain uses for today's target.
 *
 * With [drawIn] the arc sweeps from zero once, on the first open of the day.
 * Otherwise it is simply there.
 */
@Composable
fun ScoreRing(
    fraction: Float?,
    color: Color,
    size: Dp,
    modifier: Modifier = Modifier,
    glow: Boolean = true,
    targetFraction: Float? = null,
    drawIn: Boolean = false,
    delayMillis: Int = 0,
    content: @Composable BoxScope.() -> Unit,
) {
    val track = PawseTheme.semantics.baselineOuter
    val tick = MaterialTheme.colorScheme.outline
    val target = MaterialTheme.colorScheme.onSurface
    val progress = remember { Animatable(if (drawIn) 0f else 1f) }
    LaunchedEffect(drawIn) {
        if (drawIn) progress.animateTo(1f, tween(760, delayMillis, PawseMotion.EaseOut))
    }

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val unit = this.size.minDimension / 100f
            val stroke = 7f * unit
            val radius = 42f * unit
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2f, radius * 2f)

            drawCircle(track, radius = radius, style = Stroke(stroke))

            val sweep = 360f * (fraction ?: 0f).coerceIn(0f, 1f) * progress.value
            if (sweep > 0f) {
                drawIntoCanvas { canvas ->
                    val paint = Paint().apply {
                        style = PaintingStyle.Stroke
                        strokeWidth = stroke
                        strokeCap = StrokeCap.Round
                        this.color = color
                    }
                    if (glow) {
                        paint.asFrameworkPaint()
                            .setShadowLayer(5.dp.toPx(), 0f, 0f, color.copy(alpha = 0.45f).toArgb())
                    }
                    canvas.drawArc(
                        left = topLeft.x,
                        top = topLeft.y,
                        right = topLeft.x + arcSize.width,
                        bottom = topLeft.y + arcSize.height,
                        startAngle = -90f,
                        sweepAngle = sweep,
                        useCenter = false,
                        paint = paint,
                    )
                }
            }

            radial(1f / 3f, 47f * unit, 49.5f * unit, tick, 1.5f * unit)
            radial(2f / 3f, 47f * unit, 49.5f * unit, tick, 1.5f * unit)
            targetFraction?.let { radial(it.coerceIn(0f, 1f), 35f * unit, 47f * unit, target, 2.5f * unit) }
        }
        content()
    }
}

/** A short line along a radius, [turn] of the way round clockwise from twelve o'clock. */
private fun DrawScope.radial(turn: Float, from: Float, to: Float, color: Color, width: Float) {
    val angle = turn * 2.0 * Math.PI
    val dx = sin(angle).toFloat()
    val dy = -cos(angle).toFloat()
    drawLine(
        color = color,
        start = Offset(center.x + dx * from, center.y + dy * from),
        end = Offset(center.x + dx * to, center.y + dy * to),
        strokeWidth = width,
        cap = StrokeCap.Round,
    )
}

/**
 * Seven days as a line over the shaded usual range; today is the dot. Days with
 * no score break the line rather than being bridged.
 */
@Composable
fun Sparkline(
    values: List<Double?>,
    usualLow: Double?,
    usualHigh: Double?,
    modifier: Modifier = Modifier,
    height: Dp = 36.dp,
) {
    val band = PawseTheme.semantics.baselineBand
    val line = MaterialTheme.colorScheme.onSurfaceVariant
    val last = MaterialTheme.colorScheme.onSurface
    val known = values.filterNotNull() + listOfNotNull(usualLow, usualHigh)
    if (known.isEmpty()) return
    val lo = known.min()
    val hi = known.max().let { if (it - lo < 1e-6) lo + 1.0 else it }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height),
    ) {
        val pad = 4.dp.toPx()
        fun y(v: Double) = ((size.height - pad) - (v - lo) / (hi - lo) * (size.height - 2 * pad)).toFloat()
        fun x(i: Int) = if (values.size < 2) size.width else i * size.width / (values.size - 1)

        if (usualLow != null && usualHigh != null) {
            val top = y(usualHigh)
            drawRoundRect(
                color = band,
                topLeft = Offset(0f, top),
                size = Size(size.width, (y(usualLow) - top).coerceAtLeast(1f)),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
        }

        val path = Path()
        var drawing = false
        values.forEachIndexed { i, v ->
            if (v == null) {
                drawing = false
            } else if (!drawing) {
                path.moveTo(x(i), y(v))
                drawing = true
            } else {
                path.lineTo(x(i), y(v))
            }
        }
        drawPath(
            path = path,
            color = line,
            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        values.indexOfLast { it != null }.takeIf { it >= 0 }?.let { i ->
            drawCircle(last, radius = 3.5.dp.toPx(), center = Offset(x(i), y(values[i]!!)))
        }
    }
}
