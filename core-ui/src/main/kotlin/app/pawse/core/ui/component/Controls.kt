package app.pawse.core.ui.component

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pawse.core.ui.theme.PawseTheme
import app.pawse.core.ui.theme.PawseType

/** The two curves every movement in the app uses. Nothing starts slow. */
object PawseMotion {
    val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
    val EaseInOut = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)
}

/**
 * True when the phone's animator scale is off, which is what Android's "Remove
 * animations" accessibility switch sets. Movement goes; the screen still works.
 *
 * Compose already scales its own tweens by this setting, so the check is for the
 * places where motion carries meaning of its own: the first-open reveal is skipped
 * outright and rings appear filled rather than drawing in.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * Every pressable thing: acts on release, scales to 0.97 while held. Real button
 * semantics, so it reads as one to a screen reader.
 */
@Composable
fun Modifier.press(
    onClick: () -> Unit,
    enabled: Boolean = true,
    role: Role = Role.Button,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val still = rememberReducedMotion()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !still) 0.97f else 1f,
        animationSpec = tween(160, easing = PawseMotion.EaseOut),
        label = "press",
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = source,
            indication = LocalIndication.current,
            enabled = enabled,
            role = role,
            onClick = onClick,
        )
}

/**
 * The first open of the day only: rise 8 dp and fade in. Plays once per
 * composition and never again on recomposition.
 */
@Composable
fun Modifier.reveal(play: Boolean, delayMillis: Int = 0): Modifier {
    if (!play) return this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(360, delayMillis, PawseMotion.EaseOut))
    }
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 8.dp.toPx()
    }
}

/**
 * Lets a row reach past the screen gutter on both sides, so its press and swipe
 * surfaces run edge to edge while its content keeps the 24 dp line.
 */
fun Modifier.bleed(horizontal: Dp): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val extra = horizontal.roundToPx() * 2
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra),
    )
    layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
}

/** A flat card: container surface, one hairline, radius 16. Cards are rare. */
@Composable
fun PawseCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (onClick != null) Modifier.press(onClick) else Modifier)
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(contentPadding),
        content = content,
    )
}

/** Filled pill: the one thing to do next. Says the result, not "OK". */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 52.dp,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .height(height)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .press(onClick, enabled)
            .background(MaterialTheme.colorScheme.onSurface, CircleShape)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = if (height >= 52.dp) PawseType.Title else PawseType.Action,
            color = MaterialTheme.colorScheme.background,
            textAlign = TextAlign.Center,
        )
    }
}

/** Outlined pill on the container surface: the second way forward. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 52.dp,
) {
    Box(
        modifier = modifier
            .height(height)
            .clip(CircleShape)
            .press(onClick)
            .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = if (height >= 52.dp) PawseType.Title else PawseType.Action,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}

/** No surface at all, 44 dp tall: "You and your scales", "Add something". */
@Composable
fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: String? = null,
) {
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .press(onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        glyph?.let { Glyph(it, size = 18.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(text, style = PawseType.Action, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** A 44 dp icon button. The label is what a screen reader says. */
@Composable
fun IconAction(
    glyph: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .press(onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Glyph(glyph, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Two or three options in a track, with a raised thumb that slides to the one
 * picked. Selection is a surface, never a hue.
 */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
) {
    val shape = RoundedCornerShape(12.dp)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(4.dp),
    ) {
        val width = maxWidth / options.size
        val x by animateDpAsState(
            targetValue = width * selected,
            animationSpec = tween(240, easing = PawseMotion.EaseInOut),
            label = "thumb",
        )
        Box(
            Modifier
                .offset(x = x)
                .width(width)
                .height(height)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(9.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(9.dp)),
        )
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, label ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(height)
                        .clip(RoundedCornerShape(9.dp))
                        .selectable(selected = index == selected, role = Role.RadioButton) { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = PawseType.Action,
                        color = if (index == selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/**
 * Every option shown at once, as equal 44 dp buttons: meals, sex, appearance. The
 * picked one is filled.
 */
@Composable
fun Choices(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { index, label ->
            val on = index == selected
            val shape = RoundedCornerShape(12.dp)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(shape)
                    .selectable(selected = on, role = Role.RadioButton) { onSelect(index) }
                    .background(
                        if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainer,
                        shape,
                    )
                    .then(if (on) Modifier else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = PawseType.Action,
                    color = if (on) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

/** What just happened, and the way back. Announced politely. */
@Composable
fun UndoToast(
    text: String,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .background(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(16.dp))
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = PawseType.Body,
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .height(44.dp)
                .clip(RoundedCornerShape(9.dp))
                .press(onUndo)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Undo",
                style = PawseType.Action.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.background,
            )
        }
    }
}

/** The small drawings at the top of each empty or failure state. */
enum class Pictogram { READ_FAILED, NO_SLEEP, LOCKED, LEARNING, OFFLINE, EMPTY_DAY }

/**
 * A 72 × 40 tile with the baseline band drawn inside it, broken in a way that
 * says what happened. The app's mark, used as its own state illustration.
 */
@Composable
fun StatePictogram(
    kind: Pictogram,
    modifier: Modifier = Modifier,
    learnedFraction: Float = 0f,
) {
    val container = MaterialTheme.colorScheme.surfaceContainer
    val line = MaterialTheme.colorScheme.outline
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    val on = MaterialTheme.colorScheme.onSurface
    val bg = MaterialTheme.colorScheme.background
    val tile = if (kind == Pictogram.READ_FAILED || kind == Pictogram.EMPTY_DAY) bg else container
    Box(
        modifier = modifier
            .size(width = 72.dp, height = 40.dp)
            .background(tile, RoundedCornerShape(12.dp))
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(width = 72.dp, height = 40.dp)) {
            val left = 14.dp.toPx()
            val right = size.width - 14.dp.toPx()
            val h = 4.dp.toPx()
            val radius = CornerRadius(h / 2f)
            fun bar(from: Float, to: Float, y: Float, color: Color, alpha: Float = 1f) = drawRoundRect(
                color = color,
                topLeft = Offset(from, y),
                size = Size(to - from, h),
                cornerRadius = radius,
                alpha = alpha,
            )
            fun dashed(from: Float, to: Float, y: Float, color: Color) {
                var x = from
                while (x < to) {
                    drawRect(color, Offset(x, y), Size(minOf(4.dp.toPx(), to - x), h))
                    x += 7.dp.toPx()
                }
            }
            val mid = 18.dp.toPx()
            when (kind) {
                Pictogram.READ_FAILED -> {
                    bar(left, right, mid, line)
                    drawRoundRect(
                        color = bg,
                        topLeft = Offset(40.dp.toPx(), 10.dp.toPx()),
                        size = Size(6.dp.toPx(), 20.dp.toPx()),
                        cornerRadius = CornerRadius(3.dp.toPx()),
                    )
                    drawRoundRect(
                        color = variant,
                        topLeft = Offset(40.dp.toPx(), 10.dp.toPx()),
                        size = Size(6.dp.toPx(), 20.dp.toPx()),
                        cornerRadius = CornerRadius(3.dp.toPx()),
                        style = Stroke(1.5.dp.toPx()),
                    )
                }
                Pictogram.NO_SLEEP -> {
                    bar(left, right, 24.dp.toPx(), line)
                    drawRoundRect(
                        color = variant,
                        topLeft = Offset(16.dp.toPx(), 16.dp.toPx()),
                        size = Size(18.dp.toPx(), 5.dp.toPx()),
                        cornerRadius = CornerRadius(2.5.dp.toPx()),
                    )
                }
                Pictogram.LOCKED -> bar(left, right, mid, line, alpha = 0.5f)
                Pictogram.LEARNING -> {
                    dashed(left, right, mid, line)
                    dashed(left, left + (right - left) * learnedFraction.coerceIn(0f, 1f), mid, on)
                }
                Pictogram.OFFLINE -> {
                    bar(left, left + 18.dp.toPx(), mid, line)
                    bar(right - 18.dp.toPx(), right, mid, line)
                }
                Pictogram.EMPTY_DAY -> dashed(left, right, mid, line)
            }
        }
        if (kind == Pictogram.LOCKED) {
            Box(
                Modifier
                    .size(18.dp)
                    .background(container, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Glyph(Glyphs.LOCK, size = 16.dp, tint = variant, strokeWidth = 2f)
            }
        }
    }
}

/** A heading in the quiet label style, with an optional figure on the right. */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing?.let {
            Text(it, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "Your usual range": the swatch that explains the shaded band beside it. */
@Composable
fun UsualRangeKey(text: String = "Your usual range", modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Spacer(
            Modifier
                .size(width = 16.dp, height = 8.dp)
                .background(PawseTheme.semantics.baselineBand, RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.width(6.dp))
        Text(text, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A filled bar on the outer track: strain so far, a sub-score, a meal's share. */
@Composable
fun FillBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    tickFraction: Float? = null,
) {
    val track = PawseTheme.semantics.baselineOuter
    val tick = MaterialTheme.colorScheme.onSurface
    Canvas(
        modifier
            .fillMaxWidth()
            .height(if (tickFraction != null) height + 8.dp else height),
    ) {
        val barH = height.toPx()
        val top = (size.height - barH) / 2f
        val r = CornerRadius(barH / 2f)
        drawRoundRect(track, Offset(0f, top), Size(size.width, barH), r)
        val w = size.width * fraction.coerceIn(0f, 1f)
        if (w > 0f) drawRoundRect(color, Offset(0f, top), Size(w, barH), r)
        tickFraction?.let {
            val x = size.width * it.coerceIn(0f, 1f)
            drawRoundRect(
                color = tick,
                topLeft = Offset(x - 1.dp.toPx(), 0f),
                size = Size(2.dp.toPx(), size.height),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
        }
    }
}
