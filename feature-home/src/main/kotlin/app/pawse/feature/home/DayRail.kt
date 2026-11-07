package app.pawse.feature.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pawse.core.data.scoring.ScoringSnapshot
import app.pawse.core.ui.component.EaseInOut
import app.pawse.core.ui.component.EaseOut
import app.pawse.core.ui.component.Glyph
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.LocalReduceMotion
import app.pawse.core.ui.component.PawseHaptic
import app.pawse.core.ui.component.pressable
import app.pawse.core.ui.component.rememberHaptic
import app.pawse.core.ui.theme.PawseTheme
import app.pawse.core.ui.theme.PawseType
import app.pawse.scoring.model.ScoreType
import app.pawse.scoring.tr
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/**
 * The detail's day rail, pinned under the header and shared by the three pages.
 * A week at a time, Sunday to Saturday; drag sideways for other weeks. Chips show
 * the open page's mark and no number. As the page scrolls 0 → 80 dp ([collapse]
 * 0 → 1) it shrinks 64 → 40 dp: the chips fade out and a compact ‹ date › bar with
 * Today and Compare fades in, so the day stays in view.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DayRail(
    snapshot: ScoringSnapshot,
    type: ScoreType,
    back: Int,
    pin: Int?,
    collapse: () -> Float,
    lifted: Boolean,
    onPick: (Int) -> Unit,
    onPin: (Int) -> Unit,
    onCompare: () -> Unit,
    onUnpin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = PawseTheme.semantics
    val bg = MaterialTheme.colorScheme.background
    val hair = MaterialTheme.colorScheme.outlineVariant
    val haptic = rememberHaptic()
    val reduce = LocalReduceMotion.current
    val newest = snapshot.date
    val oldest = (snapshot.history.size - 1).coerceAtLeast(0)
    val first = newest.minusDays(oldest.toLong())
    val firstWeek = sunday(first)
    val weeks = (ChronoUnit.DAYS.between(firstWeek, sunday(newest)) / 7 + 1).toInt()
    fun weekOf(d: LocalDate) = (ChronoUnit.DAYS.between(firstWeek, sunday(d)) / 7).toInt().coerceIn(0, weeks - 1)
    val date = newest.minusDays(back.toLong())

    val pager = rememberPagerState(initialPage = weekOf(date)) { weeks }
    // A day step into another week slides the rail there; a far jump lands without the slide.
    LaunchedEffect(back) {
        val target = weekOf(date)
        if (target == pager.currentPage || pager.isScrollInProgress) return@LaunchedEffect
        if (reduce || abs(target - pager.currentPage) > 1) pager.scrollToPage(target)
        else pager.animateScrollToPage(target, animationSpec = tween(260, easing = EaseInOut))
    }
    // Only a week the finger paged to ticks; the label shows while the finger drags.
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(pager) {
        pager.interactionSource.interactions.collect {
            when (it) {
                is DragInteraction.Start -> dragging = true
                is DragInteraction.Stop, is DragInteraction.Cancel -> dragging = false
            }
        }
    }
    var fingerPaged by remember { mutableStateOf(false) }
    LaunchedEffect(dragging) { if (dragging) fingerPaged = true }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect {
            if (fingerPaged) haptic(PawseHaptic.Tick)
            fingerPaged = false
        }
    }
    val labelOn by remember { derivedStateOf { dragging || pager.isScrollInProgress && fingerPaged } }
    val labelAlpha by animateFloatAsState(if (labelOn) 1f else 0f, tween(if (labelOn) 120 else 150, easing = EaseOut), label = "weekLabel")
    val chipsLive by remember { derivedStateOf { collapse() < 0.3f } }
    val barLive by remember { derivedStateOf { collapse() > 0.7f } }

    // Each week's peak load, so a Load bar reads against its own week (17.5 at least).
    val byDate = remember(snapshot) { snapshot.history.associateBy { it.date } + (newest to snapshot.today) }

    Box(
        modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val h = (64f - 24f * collapse()).dp.roundToPx()
                val p = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                layout(p.width, h) { p.place(0, 0) }
            }
            .background(bg.copy(alpha = 0.92f))
            .drawBehind { if (lifted) drawLine(hair, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) },
    ) {
        HorizontalPager(
            state = pager,
            contentPadding = PaddingValues(horizontal = 24.dp),
            userScrollEnabled = chipsLive,
            flingBehavior = PagerDefaults.flingBehavior(pager, snapAnimationSpec = tween(260, easing = EaseInOut), snapPositionalThreshold = 0.33f),
            modifier = Modifier
                .padding(top = 4.dp)
                .height(56.dp)
                .graphicsLayer { alpha = (1f - collapse() / 0.6f).coerceIn(0f, 1f) }
                .semantics { contentDescription = tr("Days, a week at a time. Drag sideways for other weeks.", "날짜, 한 주씩. 옆으로 끌어 다른 주를 봐요.") },
        ) { w ->
            val start = firstWeek.plusDays(7L * w)
            val lmax = weekPeak(byDate, start)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (i in 0..6) {
                    val d = start.plusDays(i.toLong())
                    val dBack = ChronoUnit.DAYS.between(d, newest).toInt()
                    RailChip(
                        date = d,
                        weekday = WEEKDAYS[i],
                        day = byDate[d],
                        type = type,
                        loadMax = lmax,
                        on = dBack == back,
                        today = dBack == 0,
                        pinned = pin == dBack,
                        // Before Pawse's first day, and after today: shown, never picked.
                        enabled = chipsLive && dBack in 0..oldest,
                        onTap = { onPick(dBack) },
                        onLong = {
                            haptic(PawseHaptic.LongPress)
                            onPin(dBack)
                        },
                    )
                }
            }
        }

        // The compact bar: ‹ date ›, then Today and Compare.
        Row(
            Modifier
                .padding(start = 8.dp, end = 16.dp)
                .fillMaxWidth()
                .height(40.dp)
                .graphicsLayer { alpha = ((collapse() - 0.4f) / 0.6f).coerceIn(0f, 1f) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            StepArrow(Glyphs.ChevronLeft, tr("Previous day", "이전 날"), enabled = barLive && back < oldest) {
                haptic(PawseHaptic.Step)
                onPick(back + 1)
            }
            Text(shortDate(date), style = PawseType.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            StepArrow(Glyphs.ChevronRight, tr("Next day", "다음 날"), enabled = barLive && back > 0) {
                haptic(PawseHaptic.Step)
                onPick(back - 1)
            }
            Spacer(Modifier.weight(1f))
            if (back > 0) {
                Box(
                    Modifier
                        .height(28.dp)
                        .pressable(enabled = barLive) { onPick(0) }
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(14.dp))
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(tr("Today", "오늘"), style = PawseType.Micro.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp))
                }
            }
            if (barLive || collapse() > 0.4f) {
                CompareChip(pin?.let { newest.minusDays(it.toLong()) }, onOpen = onCompare, onUnpin = onUnpin, height = 32.dp)
            }
        }

        // While a finger pages weeks, which week it is shows under the rail.
        if (labelAlpha > 0f) {
            val shownWeek = firstWeek.plusDays(7L * pager.currentPage)
            Text(
                if (pager.currentPage == 0) {
                    tr("Pawse started ${shortDate(first)}", "${shortDate(first)}부터 기록했어요")
                } else {
                    tr("Week of ${shownWeek.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))}", "${shownWeek.monthValue}월 ${shownWeek.dayOfMonth}일 주")
                },
                style = PawseType.Micro.copy(letterSpacing = 0.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, (64f - 24f * collapse() + 6f).dp.roundToPx()) }
                    .graphicsLayer { alpha = labelAlpha }
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }
    }
}

private val WEEKDAYS: List<Pair<String, String>> = listOf("S" to "일", "M" to "월", "T" to "화", "W" to "수", "T" to "목", "F" to "금", "S" to "토")

private fun sunday(d: LocalDate): LocalDate = d.minusDays((d.dayOfWeek.value % 7).toLong())

/**
 * One day: weekday, date and the open page's mark. Body is its band dot (a hollow
 * ring with no reading), Sleep a bar to 10 hours, Load a bar against the week's peak.
 * The picked day sits on a card; today is underlined; a pinned day is dotted.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RailChip(
    date: LocalDate,
    weekday: Pair<String, String>,
    day: app.pawse.scoring.model.DayReport?,
    type: ScoreType,
    loadMax: Double,
    on: Boolean,
    today: Boolean,
    pinned: Boolean,
    enabled: Boolean,
    onTap: () -> Unit,
    onLong: () -> Unit,
) {
    val s = PawseTheme.semantics
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val card = MaterialTheme.colorScheme.surfaceContainer
    val hair = MaterialTheme.colorScheme.outlineVariant
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(if (pressed) 90 else 160, easing = EaseOut), label = "chipPress")
    val chip by animateFloatAsState(if (on) 1f else 0f, tween(220, easing = EaseInOut), label = "chipOn")
    val shown = enabled || day != null
    Column(
        Modifier
            .size(48.dp, 56.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (shown) 1f else 0.38f
            }
            .drawBehind {
                val r = CornerRadius(14.dp.toPx())
                if (chip > 0f) {
                    drawRoundRect(card.copy(alpha = card.alpha * chip), cornerRadius = r)
                    drawRoundRect(hair.copy(alpha = hair.alpha * chip), cornerRadius = r, style = Stroke(1.dp.toPx()))
                }
                if (pinned) {
                    val inset = 2.dp.toPx()
                    drawRoundRect(
                        grey,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - 2 * inset, size.height - 2 * inset),
                        cornerRadius = CornerRadius(12.dp.toPx()),
                        style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.01f, 3.dp.toPx()))),
                    )
                }
            }
            .combinedClickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Tab,
                onClick = onTap,
                onLongClick = onLong,
            )
            .semantics(mergeDescendants = true) {
                selected = on
                contentDescription = shortDate(date) + (if (today) tr(", today", ", 오늘") else "") + (if (pinned) tr(", compared day", ", 비교하는 날") else "")
            }
            .padding(top = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(tr(weekday.first, weekday.second), style = PawseType.Micro.copy(fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.sp), color = s.faint)
        Text(
            date.dayOfMonth.toString(),
            style = PawseType.Body.copy(fontSize = 15.sp, lineHeight = 20.sp, textDecoration = if (today) TextDecoration.Underline else null),
            color = if (on) MaterialTheme.colorScheme.onSurface else grey,
        )
        if (day != null) DayMark(day, type, loadMax, Modifier.padding(top = 2.dp))
    }
}

/**
 * A day's mark for the open page, shared by the rail and the calendar: Body's band
 * dot (a hollow ring with no reading), Sleep a bar to 10 hours, Load a bar against
 * [loadMax], the week's peak.
 */
@Composable
internal fun DayMark(day: app.pawse.scoring.model.DayReport, type: ScoreType, loadMax: Double, modifier: Modifier = Modifier) {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val dot = day.body?.let { bodyColor(it.band, day.illness.level) }
    val bar = when (type) {
        ScoreType.SLEEP -> day.sleep.lastNightMinutes?.let { it / 600.0 }
        ScoreType.LOAD -> maxOf(1.5 / 12, day.load.points / loadMax)
        ScoreType.BODY -> null
    }?.coerceIn(0.0, 1.0)?.toFloat()
    Canvas(modifier.size(12.dp)) {
        when {
            type == ScoreType.BODY && dot != null -> drawCircle(dot, 3.dp.toPx())
            bar != null -> {
                val h = bar * size.height
                drawRoundRect(grey, Offset(4.5.dp.toPx(), size.height - h), Size(3.dp.toPx(), h), CornerRadius(1.5.dp.toPx()))
            }
            else -> drawCircle(grey, 2.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
        }
    }
}

/** Each Sunday-to-Saturday week's peak load, at least 17.5, so a Load bar reads against its own week. */
internal fun weekPeak(byDate: Map<LocalDate, app.pawse.scoring.model.DayReport>, d: LocalDate): Double {
    val start = sundayOf(d)
    return maxOf(17.5, (0..6).maxOf { byDate[start.plusDays(it.toLong())]?.load?.points ?: 0.0 })
}

internal fun sundayOf(d: LocalDate): LocalDate = d.minusDays((d.dayOfWeek.value % 7).toLong())

@Composable
private fun StepArrow(glyph: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
            .pressable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Glyph(glyph, size = 18.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
