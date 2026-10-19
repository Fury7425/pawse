package app.pawse.core.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pawse.core.ui.theme.PawseTheme
import app.pawse.core.ui.theme.PawseType
import kotlin.math.cos
import kotlin.math.sin

/**
 * The design canvas's building blocks, so every screen draws the same card, the
 * same pill and the same ring rather than its own near-copy.
 */

/** The canvas's two curves: a quick settle for presses, a slower glide for thumbs. */
val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
val EaseInOut = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)

/**
 * Line icons from the design canvas: 24-unit SVG path data, drawn as round strokes.
 * Kept as path strings so a glyph is copied from the canvas, not redrawn.
 */
object Glyphs {
    const val Sleep = "M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z"
    const val Recovery = "M3 12h4l2-5 4 10 2-5h6"
    const val Strain = "M13 3L5 14h6l-1 7 8-11h-6z"
    const val Energy = "M5 7h12a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2zM21 11v2M7 10v4M10 10v4"
    const val Load = "M5 20v-6M10 20V9M15 20v-8M20 20V5"
    const val Today = "M5 10h14a2 2 0 0 1 0 4H5a2 2 0 0 1 0-4zM15 6.5v11"
    const val Food = "M3 11h18a9 9 0 0 1-18 0zM8 7.5c0-1.4 1-1.8 1-3.2M12 7.5c0-1.4 1-1.8 1-3.2M16 7.5c0-1.4 1-1.8 1-3.2"
    const val Body = "M6 3h12a3 3 0 0 1 3 3v12a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V6a3 3 0 0 1 3-3zM8.5 10a3.5 3.5 0 0 1 7 0M12 10l1.6-1.6"
    const val Scan = "M3 7V5a2 2 0 0 1 2-2h2M17 3h2a2 2 0 0 1 2 2v2M21 17v2a2 2 0 0 1-2 2h-2M7 21H5a2 2 0 0 1-2-2v-2M7 8v8M10 8v8M13.5 8v8M17 8v8"
    const val Check = "M5 12.5l4.5 4.5L19 7"
    const val Refresh = "M20 12a8 8 0 1 1-2.34-5.66M20 4v5h-5"
    const val Person = "M8 8a4 4 0 1 0 8 0a4 4 0 1 0-8 0M4 21c0-4 3.6-7 8-7s8 3 8 7"
    const val ChevronRight = "M9 6l6 6-6 6"
    const val ChevronLeft = "M15 6l-6 6 6 6"
    const val ChevronDown = "M6 9l6 6 6-6"
    const val Arrow = "M5 12h14M13 6l6 6-6 6"
    const val Sliders = "M4 7h9M17 7h3M4 17h3M11 17h9M13 7a2 2 0 1 0 4 0a2 2 0 1 0-4 0M7 17a2 2 0 1 0 4 0a2 2 0 1 0-4 0"
    const val Plus = "M12 5v14M5 12h14"
    const val Pencil = "M4 20h4L19 9l-4-4L4 16z"
    const val Trash = "M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"
    const val Globe = "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM3 12h18M12 3c2.5 2.7 2.5 15.3 0 18M12 3c-2.5 2.7-2.5 15.3 0 18"
    const val Panel = "M6 3h12v18H6zM9 7h6M9 11h6M9 15h4"
    const val Synced = "M20 12a8 8 0 1 1-2.34-5.66M9 12l2 2 4-4"
    const val Shield = "M12 3l7 3v5c0 5-3.5 8.5-7 10-3.5-1.5-7-5-7-10V6z"
    const val Info = "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0M12 11v5M12 8h.01"
    const val Clock = "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 7v5l3 2"
    const val Close = "M6 6l12 12M18 6L6 18"
    const val Carton = "M8 3h8v3l2 3v12H6V9l2-3zM6 13h12"
    const val Heart = "M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z"
    const val Breath = "M3 8h10a3 3 0 1 0-3-3M3 12h15a3 3 0 1 1-3 3M3 16h7"
    const val Thermometer = "M10 14V4a2 2 0 0 1 4 0v10a4 4 0 1 1-4 0z"
    const val Drop = "M12 3s6 6.5 6 11a6 6 0 0 1-12 0c0-4.5 6-11 6-11z"
    const val Flame = "M12 21a6 6 0 0 0 6-6c0-4-3-6-4-9-1 2-2 3-3 3 0-2-1-4-1-6-3 3-4 7-4 12a6 6 0 0 0 6 6z"
    const val Gauge = "M4 15a8 8 0 0 1 16 0M12 15l3-4M12 4v2M5.6 7.6l1.4 1.4M18.4 7.6L17 9"
}

/** One canvas glyph. Decorative unless the caller gives it a description. */
@Composable
fun Glyph(
    path: String,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    strokeWidth: Float = 1.75f,
    color: Color = LocalContentColor.current,
) {
    val parsed = remember(path) { PathParser().parsePathString(path).toPath() }
    Canvas(modifier.size(size)) {
        scale(this.size.minDimension / 24f, pivot = Offset.Zero) {
            drawPath(parsed, color, style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** The canvas's press: a 3% shrink that settles quickly, no ripple. */
@Composable
fun Modifier.pressable(enabled: Boolean = true, role: Role = Role.Button, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(160, easing = EaseOut), label = "press")
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = source, indication = null, enabled = enabled, role = role, onClick = onClick)
}

/** A card's fill, hairline and the one-pixel highlight along its top edge. */
@Composable
fun Modifier.cardSurface(shape: Shape = RoundedCornerShape(16.dp), radius: Dp = 16.dp): Modifier {
    val highlight = PawseTheme.semantics.highlight
    return this
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceContainer)
        .drawBehind {
            val inset = radius.toPx()
            drawLine(highlight, Offset(inset, 1.5f), Offset(size.width - inset, 1.5f), strokeWidth = 1.dp.toPx())
        }
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
}

@Composable
fun PawseCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier)
            .cardSurface()
            .padding(padding),
        content = content,
    )
}

/** The pill everything important ends on. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 52.dp,
    elevated: Boolean = false,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .alpha(if (enabled) 1f else 0.4f)
            .pressable(enabled = enabled, onClick = onClick)
            .then(
                if (elevated) {
                    Modifier.lift(RoundedCornerShape(height / 2))
                } else {
                    Modifier
                },
            )
            .background(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(height / 2)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = PawseType.Heading, color = MaterialTheme.colorScheme.background)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 52.dp,
    glyph: String? = null,
    radius: Dp = height / 2,
    textStyle: TextStyle = PawseType.Heading,
) {
    Row(
        modifier = modifier
            .height(height)
            .pressable(onClick = onClick)
            .cardSurface(RoundedCornerShape(radius), radius),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            Glyph(glyph, size = 16.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = textStyle, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** A text action with an optional leading glyph, aligned to the text edge. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: String? = null,
) {
    Row(
        modifier = modifier
            .offset(x = (-12).dp)
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .pressable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            Glyph(glyph, size = 18.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = PawseType.BodyStrong, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** A 44 dp icon-only button. The description is required: it is the button's name. */
@Composable
fun GlyphButton(
    glyph: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .pressable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Glyph(glyph, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "‹ Today": back to where this screen was opened from, named. */
@Composable
fun BackLink(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .offset(x = (-10).dp)
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .pressable(onClick = onClick)
            .padding(start = 6.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(Glyphs.ChevronLeft, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(2.dp))
        Text(label, style = PawseType.BodyStrong, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** A segmented control whose selection slides rather than jumps. */
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
    glyph: ((T) -> String)? = null,
) {
    val index = options.indexOf(selected)
    val thumb = MaterialTheme.colorScheme.surfaceContainerHigh
    val thumbLine = MaterialTheme.colorScheme.outline
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .cardSurface(RoundedCornerShape(12.dp), 12.dp)
            .padding(4.dp),
    ) {
        val segment = maxWidth / options.size
        val offset by animateDpAsState(segment * index.coerceAtLeast(0), tween(240, easing = EaseInOut), label = "thumb")
        if (index >= 0) {
            Box(
                Modifier
                    .offset(x = offset)
                    .width(segment)
                    .height(height)
                    .background(thumb, RoundedCornerShape(9.dp))
                    .border(1.dp, thumbLine, RoundedCornerShape(9.dp)),
            )
        }
        Row {
            options.forEach { option ->
                val isSelected = option == selected
                val color by animateColorAsState(
                    if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    tween(160),
                    label = "segment",
                )
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(height)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                        ) { onSelect(option) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (glyph != null) {
                        Glyph(glyph(option), size = 14.dp, strokeWidth = 2f, color = color)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(label(option), style = PawseType.BodyStrong, color = color)
                }
            }
        }
    }
}

/** Equal-width choices; the chosen one inverts. For meals, sexes, quick amounts. */
@Composable
fun <T> ChoiceRow(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val chosen = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .pressable { onSelect(option) }
                    .then(
                        if (chosen) {
                            Modifier.background(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(12.dp))
                        } else {
                            Modifier.cardSurface(RoundedCornerShape(12.dp), 12.dp)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = PawseType.BodyStrong,
                    color = if (chosen) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A filled input with its unit at the end. The border lights up while focused. */
@Composable
fun PawseField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    placeholder: String? = null,
    numeric: Boolean = false,
    height: Dp = 52.dp,
    textStyle: TextStyle = PawseType.MetricValue,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val onSurface = MaterialTheme.colorScheme.onSurface
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        interactionSource = source,
        textStyle = textStyle.copy(color = onSurface),
        cursorBrush = SolidColor(onSurface),
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .height(height)
                    .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                    .border(
                        1.dp,
                        if (focused) onSurface else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(placeholder, style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    inner()
                }
                if (suffix != null) {
                    Spacer(Modifier.width(12.dp))
                    Text(suffix, style = PawseType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    )
}

/**
 * A score on a ring: the arc is the value, the faint ticks are the band edges at a
 * third and two thirds, and an optional bold tick marks a target.
 */
@Composable
fun ScoreRing(
    fraction: Float?,
    color: Color,
    size: Dp,
    modifier: Modifier = Modifier,
    target: Float? = null,
    glow: Boolean = true,
    ticks: Boolean = true,
    stroke: Float = 7f,
    content: @Composable BoxScope.() -> Unit,
) {
    val track = PawseTheme.semantics.baselineOuter
    val tickColor = MaterialTheme.colorScheme.outline
    val targetColor = MaterialTheme.colorScheme.onSurface
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(fraction) { sweep.animateTo((fraction ?: 0f).coerceIn(0f, 1f), tween(760, easing = EaseOut)) }

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val unit = this.size.minDimension / 100f
            val radius = 42f * unit
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2, radius * 2)
            drawCircle(track, radius, style = Stroke(stroke * unit))
            if (sweep.value > 0f) {
                if (glow) {
                    drawArc(color.copy(alpha = 0.12f), -90f, 360f * sweep.value, false, topLeft, arcSize, style = Stroke((stroke + 8f) * unit, cap = StrokeCap.Round))
                    drawArc(color.copy(alpha = 0.16f), -90f, 360f * sweep.value, false, topLeft, arcSize, style = Stroke((stroke + 3f) * unit, cap = StrokeCap.Round))
                }
                drawArc(color, -90f, 360f * sweep.value, false, topLeft, arcSize, style = Stroke(stroke * unit, cap = StrokeCap.Round))
            }
            fun tick(at: Float, from: Float, to: Float, width: Float, tint: Color) {
                val angle = Math.toRadians(at * 360.0)
                val dx = sin(angle).toFloat()
                val dy = -cos(angle).toFloat()
                drawLine(
                    tint,
                    Offset(center.x + dx * from * unit, center.y + dy * from * unit),
                    Offset(center.x + dx * to * unit, center.y + dy * to * unit),
                    strokeWidth = width * unit,
                    cap = StrokeCap.Round,
                )
            }
            if (ticks) {
                tick(0.33f, 49.5f, 47f, 1.5f, tickColor)
                tick(0.66f, 49.5f, 47f, 1.5f, tickColor)
            }
            target?.let { tick(it.coerceIn(0f, 1f), 47f, 35f, 2.5f, targetColor) }
        }
        content()
    }
}

/** The swatch that says what the shaded band means. */
@Composable
fun UsualRangeKey(text: String = "Your usual range", modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 16.dp, height = 8.dp)
                .background(PawseTheme.semantics.baselineBand, RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.width(6.dp))
        Text(text, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A small coloured square and its name, for split bars. */
@Composable
fun KeySwatch(color: Color, text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(6.dp))
        Text(text, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Proportional segments with 2 dp gaps and rounded outer ends. */
@Composable
fun SplitBar(parts: List<Pair<Float, Color>>, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val shown = parts.filter { it.first > 0f }
    Row(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        shown.forEach { (weight, color) ->
            Box(Modifier.weight(weight).height(height).background(color))
        }
    }
}

/** The dark pill that confirms an action and offers to take it back. */
@Composable
fun UndoToast(text: String, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .lift(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(16.dp))
            .padding(start = 16.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = PawseType.Body, color = MaterialTheme.colorScheme.background, modifier = Modifier.weight(1f))
        Box(
            Modifier
                .height(40.dp)
                .clip(RoundedCornerShape(9.dp))
                .pressable(onClick = onUndo)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Undo", style = PawseType.Delta, color = MaterialTheme.colorScheme.background)
        }
    }
}

/**
 * The bottom call to action: content scrolls under a fade into the background, and
 * the pill floats above the navigation bar.
 */
@Composable
fun BoxScope.BottomAction(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    above: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val background = MaterialTheme.colorScheme.background
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to background.copy(alpha = 0f), 0.45f to background, 1f to background))
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 48.dp, bottom = 20.dp),
    ) {
        above?.invoke(this)
        PrimaryButton(text = text, onClick = onClick, enabled = enabled, height = 64.dp, elevated = true)
    }
}

/** A bottom sheet in the canvas's dress: surface fill, short handle, 16 dp corners. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PawseSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        scrimColor = Color.Black.copy(alpha = 0.5f),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 18.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            )
        },
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 28.dp)) { content() }
        }
    }
}

/** The canvas's lift: a soft shadow under floating chrome. */
fun Modifier.lift(shape: Shape): Modifier = shadow(12.dp, shape, clip = false)
