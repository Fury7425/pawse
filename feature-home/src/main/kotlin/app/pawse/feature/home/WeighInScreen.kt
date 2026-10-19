package app.pawse.feature.home

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pawse.core.data.body.RecentWeighIn
import app.pawse.core.ui.component.BackLink
import app.pawse.core.ui.component.BottomAction
import app.pawse.core.ui.component.EaseOut
import app.pawse.core.ui.component.GhostButton
import app.pawse.core.ui.component.Glyph
import app.pawse.core.ui.component.GlyphButton
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.PawseCard
import app.pawse.core.ui.component.PawseField
import app.pawse.core.ui.component.Segmented
import app.pawse.core.ui.component.cardSurface
import app.pawse.core.ui.component.pressable
import app.pawse.core.ui.theme.PawseTheme
import app.pawse.core.ui.theme.PawseType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Add a weigh-in: for a scale that doesn't talk to Health Connect, or a day without one.
 *
 * Weight is the only figure asked for. Composition is there for someone reading it
 * off a scale's display, and every blank stays blank: a typed weight never grows a
 * body fat figure it wasn't given.
 */
@Composable
fun WeighInScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WeighInViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val onv = MaterialTheme.colorScheme.onSurfaceVariant
    val low = PawseTheme.semantics.bandLow
    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        viewModel.onGrantResult()
    }

    LaunchedEffect(state.saved) {
        if (state.saved) onDone()
    }
    LaunchedEffect(state.askFor) {
        state.askFor?.let {
            viewModel.onAskLaunched()
            launcher.launch(it)
        }
    }

    Scaffold(modifier = modifier, containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = padding.calculateTopPadding() + 12.dp, bottom = 140.dp)
                    .padding(horizontal = 24.dp),
            ) {
                BackLink("Body", onClick = onBack)
                Spacer(Modifier.height(12.dp))
                Text("Add a weigh-in", style = PawseType.Title)
                Spacer(Modifier.height(4.dp))
                Text(
                    "For a scale that doesn’t talk to Health Connect, or a day without one.",
                    style = PawseType.Body,
                    color = onv,
                )

                Spacer(Modifier.height(28.dp))
                Caption("Weight")
                Spacer(Modifier.height(8.dp))
                PawseField(
                    value = state.weightText,
                    onValueChange = viewModel::setWeight,
                    suffix = "kg",
                    numeric = true,
                    height = 72.dp,
                    textStyle = PawseType.Numeral,
                )
                Spacer(Modifier.height(8.dp))
                WeightRuler(kg = state.weightKg, onKg = viewModel::setWeightKg)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (state.weightBad) "Between 20 and 300 kg" else weightHint(state.recent.firstOrNull()),
                    style = PawseType.Label,
                    color = if (state.weightBad) low else onv,
                )

                Spacer(Modifier.height(24.dp))
                Caption("When")
                Spacer(Modifier.height(8.dp))
                val now = remember { ScoreCopy.clock(System.currentTimeMillis()) }
                Segmented(
                    options = listOf(false, true),
                    selected = state.earlier,
                    onSelect = viewModel::setEarlier,
                    label = { if (it) "Earlier" else "Now · $now" },
                )
                AnimatedVisibility(state.earlier, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val at = state.earlierAt
                            PickerBox(
                                label = "Day",
                                value = at.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())),
                                onClick = {
                                    DatePickerDialog(
                                        context,
                                        { _, y, m, d -> viewModel.setEarlierAt(LocalDate.of(y, m + 1, d).atTime(at.toLocalTime())) },
                                        at.year,
                                        at.monthValue - 1,
                                        at.dayOfMonth,
                                    ).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
                                },
                                modifier = Modifier.weight(1f),
                            )
                            PickerBox(
                                label = "Time",
                                value = ScoreCopy.clock(at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()),
                                onClick = {
                                    TimePickerDialog(
                                        context,
                                        { _, h, min -> viewModel.setEarlierAt(at.toLocalDate().atTime(h, min)) },
                                        at.hour,
                                        at.minute,
                                        DateFormat.is24HourFormat(context),
                                    ).show()
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Not later than now. A weigh-in in the future can’t be true yet.",
                            style = PawseType.Label,
                            color = if (state.timeBad) low else onv,
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
                CompositionHeader(open = state.compositionOpen, onToggle = viewModel::toggleComposition)
                AnimatedVisibility(state.compositionOpen, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        PawseCard(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                            BodyFigure.entries.forEach { figure ->
                                FigureRow(
                                    figure = figure,
                                    value = state.figures[figure].orEmpty(),
                                    error = state.figureError(figure),
                                    onValueChange = { viewModel.setFigure(figure, it) },
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                            val (derived, warn) = derivedLine(state)
                            Text(
                                derived,
                                style = PawseType.Label,
                                color = if (warn) PawseTheme.semantics.bandModerate else onv,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Caption(
                            "Leave any blank. Pawse saves only what you type and never fills a gap with a guess. " +
                                "No muscle field: Health Connect has no record for it.",
                        )
                    }
                }

                if (state.recent.isNotEmpty()) {
                    Spacer(Modifier.height(32.dp))
                    Caption("Recent weigh-ins")
                    Spacer(Modifier.height(4.dp))
                    state.recent.forEach { entry ->
                        RecentRow(
                            entry = entry,
                            removed = entry.atEpochMs in state.removed,
                            onRemove = { viewModel.remove(entry) },
                            onUndo = { viewModel.undoRemove(entry.atEpochMs) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    Spacer(Modifier.height(12.dp))
                    Caption("Only what you typed here can be removed here. A scale’s readings belong to the scale’s app.")
                }

                Spacer(Modifier.height(24.dp))
                Caption("Saved to Health Connect, marked as typed in by hand, so other apps see it and can tell it apart from a scale.")
            }

            BottomAction(
                text = state.saveLabel,
                onClick = viewModel::save,
                enabled = state.canSave,
                above = {
                    state.problem?.let { problem ->
                        Text(problem, style = PawseType.Label, color = low)
                        if (problem == WeighInViewModel.NOT_ALLOWED) {
                            GhostButton(
                                "Open Health Connect",
                                onClick = {
                                    runCatching { context.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }
                                },
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                },
            )
        }
    }
}

@Composable
private fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(text, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

private fun weightHint(last: RecentWeighIn?): String {
    last ?: return "Scroll the ruler or type it."
    val date = Instant.ofEpochMilli(last.atEpochMs).atZone(ZoneId.systemDefault()).toLocalDate()
    val day = when (date) {
        LocalDate.now() -> "today"
        LocalDate.now().minusDays(1) -> "yesterday"
        else -> "on " + date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
    }
    return "Last: ${WeighInUiState.oneDecimal(last.weightKg)} kg $day, ${source(last).replaceFirstChar { it.lowercase() }}."
}

private fun source(entry: RecentWeighIn): String = when {
    entry.typedIn -> "Typed in"
    entry.sourceLabel != null -> "From ${entry.sourceLabel}"
    else -> "From another app"
}

private fun whenText(epochMs: Long): String {
    val date = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
    val day = when (date) {
        LocalDate.now() -> "Today"
        LocalDate.now().minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
    }
    return "$day, ${ScoreCopy.clock(epochMs)}"
}

/** What a body fat figure means in kilograms, or where it disagrees with lean mass. */
private fun derivedLine(s: WeighInUiState): Pair<String, Boolean> {
    val w = s.weightKg
    val fat = s.figure(BodyFigure.FAT)?.takeIf { s.figureError(BodyFigure.FAT) == null }
    if (w == null || fat == null) return "Type a body fat figure and Pawse shows what it means in kilograms." to false
    val fatKg = w * fat / 100.0
    val rest = w - fatKg
    val lean = s.figure(BodyFigure.LEAN)?.takeIf { s.figureError(BodyFigure.LEAN) == null }
    if (lean != null && abs(rest - lean) > 1.0) {
        return "Body fat and lean mass disagree by ${WeighInUiState.oneDecimal(abs(rest - lean))} kg. " +
            "A scale reports both from one reading, so check the display." to true
    }
    fun one(v: Double) = WeighInUiState.oneDecimal(v)
    return "At ${one(w)} kg, ${one(fat)}% is ${one(fatKg)} kg of fat and ${one(rest)} kg of everything else." to false
}

@Composable
private fun PickerBox(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .height(56.dp)
            .pressable(onClick = onClick)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = PawseType.Body)
    }
}

@Composable
private fun CompositionHeader(open: Boolean, onToggle: () -> Unit) {
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(200, easing = EaseOut), label = "chevron")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressable(onClick = onToggle)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Body composition", style = PawseType.Heading)
            Text(
                "Optional. From a scale’s display or a DEXA report.",
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Glyph(Glyphs.ChevronDown, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.rotate(turn))
    }
}

@Composable
private fun FigureRow(figure: BodyFigure, value: String, error: String?, onValueChange: (String) -> Unit) {
    Column(Modifier.padding(vertical = 10.dp)) {
        Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(figure.label, style = PawseType.Body, modifier = Modifier.weight(1f))
            PawseField(
                value = value,
                onValueChange = onValueChange,
                suffix = figure.unit,
                placeholder = "—",
                numeric = true,
                height = 44.dp,
                modifier = Modifier.width(132.dp),
            )
        }
        if (error != null) {
            Text(
                error,
                style = PawseType.Label,
                color = PawseTheme.semantics.bandLow,
                modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun RecentRow(entry: RecentWeighIn, removed: Boolean, onRemove: () -> Unit, onUndo: () -> Unit) {
    val onv = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (removed) {
            Text("Removed, here and in Health Connect", style = PawseType.Body, color = onv, modifier = Modifier.weight(1f))
            Box(
                Modifier
                    .offset(x = 10.dp)
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .pressable(onClick = onUndo)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Undo", style = PawseType.BodyStrong)
            }
        } else {
            val whenText = whenText(entry.atEpochMs)
            val kg = "${WeighInUiState.oneDecimal(entry.weightKg)} kg"
            Column(Modifier.weight(1f)) {
                Text(whenText, style = PawseType.Body)
                Text(source(entry), style = PawseType.Label, color = onv)
            }
            Text(kg, style = PawseType.MetricValue)
            if (entry.typedIn) {
                GlyphButton(Glyphs.Trash, "Remove $kg from $whenText", onClick = onRemove, modifier = Modifier.offset(x = 10.dp))
            }
        }
    }
}

// --- The ruler -----------------------------------------------------------------

private const val RULER_TICKS = 2801 // one per 0.1 kg, 20 to 300
private const val RULER_REST_KG = 70.0
private val TICK_SPACING = 8.dp

private fun tickOf(kg: Double): Int =
    ((kg - WeighInUiState.MIN_KG) * 10).roundToInt().coerceIn(0, RULER_TICKS - 1)

private fun kgOf(tick: Int): Double = WeighInUiState.MIN_KG + tick / 10.0

/** The tick under the centre line. Offsets are measured from the start padding, so the middle is too. */
private fun LazyListState.centered(): LazyListItemInfo? {
    val info = layoutInfo
    val middle = (info.viewportStartOffset + info.viewportEndOffset) / 2
    return info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - middle) }
}

/**
 * A ruler dragged sideways under a fixed centre line: a tick per 0.1 kg, a number
 * per kilogram. It and the field above are two views of one value; the field is
 * the accessible one, so the ruler is hidden from TalkBack rather than repeating it.
 */
@Composable
private fun WeightRuler(kg: Double?, onKg: (Double) -> Unit, modifier: Modifier = Modifier) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = tickOf(kg ?: RULER_REST_KG))
    val latestKg by rememberUpdatedState(kg)
    val latestOnKg by rememberUpdatedState(onKg)
    val haptics = LocalHapticFeedback.current
    val measurer = rememberTextMeasurer()
    val minor = MaterialTheme.colorScheme.outline
    val half = MaterialTheme.colorScheme.onSurfaceVariant
    val major = MaterialTheme.colorScheme.onSurface
    val labelStyle = PawseType.Label.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)

    // A drag or fling moves the number, one tenth per tick. Only while scrolling,
    // so opening the screen never types a weight the user didn't choose.
    LaunchedEffect(state) {
        snapshotFlow { state.centered()?.index }.collect { tick ->
            if (tick != null && state.isScrollInProgress && tick != latestKg?.let(::tickOf)) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                latestOnKg(kgOf(tick))
            }
        }
    }

    // Typing moves the ruler. Skipped mid-drag, so the two never fight.
    LaunchedEffect(kg) {
        val target = kg?.let(::tickOf) ?: return@LaunchedEffect
        if (!state.isScrollInProgress && state.centered()?.index != target) state.scrollToItem(target)
    }

    // A fling comes to rest on a tick, never between two.
    LaunchedEffect(state.isScrollInProgress) {
        if (state.isScrollInProgress) return@LaunchedEffect
        val info = state.layoutInfo
        val nearest = state.centered() ?: return@LaunchedEffect
        val delta = nearest.offset + nearest.size / 2 - (info.viewportStartOffset + info.viewportEndOffset) / 2
        if (delta != 0) state.animateScrollBy(delta.toFloat())
    }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            .cardSurface(RoundedCornerShape(12.dp), 12.dp)
            .clearAndSetSemantics {},
    ) {
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = (maxWidth - TICK_SPACING) / 2),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.horizontalGradient(0f to Color.Transparent, 0.18f to Color.Black, 0.82f to Color.Black, 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            items(RULER_TICKS) { tick ->
                Canvas(Modifier.width(TICK_SPACING).fillMaxHeight()) {
                    val x = size.width / 2
                    val kilo = tick % 10 == 0
                    val (length, color) = when {
                        kilo -> 22.dp to major
                        tick % 5 == 0 -> 16.dp to half
                        else -> 10.dp to minor
                    }
                    drawLine(color, Offset(x, 0f), Offset(x, length.toPx()), strokeWidth = (if (kilo) 2.dp else 1.dp).toPx())
                    if (kilo) {
                        val label = measurer.measure((WeighInUiState.MIN_KG.toInt() + tick / 10).toString(), labelStyle)
                        drawText(label, topLeft = Offset(x - label.size.width / 2f, 32.dp.toPx()))
                    }
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .width(2.dp)
                .height(30.dp)
                .background(major, RoundedCornerShape(bottomStart = 1.dp, bottomEnd = 1.dp)),
        )
    }
}
