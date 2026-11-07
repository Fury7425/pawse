package app.pawse.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import app.pawse.core.ui.component.EaseInOut
import app.pawse.core.ui.component.PawseHaptic
import app.pawse.core.ui.component.REDUCED_FADE
import app.pawse.core.ui.component.rememberHaptic
import app.pawse.core.ui.component.rememberSwap
import app.pawse.core.ui.component.swapIn
import kotlinx.coroutines.flow.drop
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pawse.core.data.scoring.NightDetail
import app.pawse.core.data.scoring.ScoringSnapshot
import app.pawse.core.ui.component.BandDot
import app.pawse.core.ui.component.EaseOut
import app.pawse.core.ui.component.Glyph
import app.pawse.core.ui.component.LocalReduceMotion
import app.pawse.core.ui.component.GlyphButton
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.PawseCard
import app.pawse.core.ui.component.PawseSheet
import app.pawse.core.ui.component.ScoreRing
import app.pawse.core.ui.component.RevealEnter
import app.pawse.core.ui.component.RevealExit
import app.pawse.core.ui.component.Segmented
import app.pawse.core.ui.component.aura
import app.pawse.core.ui.component.pressable
import app.pawse.core.ui.theme.PawseTheme
import app.pawse.core.ui.theme.PawseType
import app.pawse.scoring.config.ScoringConfig
import app.pawse.scoring.load.LoadPoints
import app.pawse.scoring.model.BodyBand
import app.pawse.scoring.model.DayReport
import app.pawse.scoring.model.IllnessLevel
import app.pawse.scoring.model.Insight
import app.pawse.scoring.model.ScoreType
import app.pawse.scoring.tr
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The arithmetic, in full, for Body and Load, and the night in full for Sleep,
 * for any day on the strip.
 *
 * Everything here was computed once, by the engine. Nothing is recalculated in
 * the UI: a screen that recomputes is a second engine that will eventually
 * disagree with the first, about the number the user was shown. (The usual bands
 * behind the night signals are display statistics over stored nights.)
 */
/** The pages in the rings' order, so the detail opens where the tapped ring sits. */
private val PAGES = listOf(ScoreType.SLEEP, ScoreType.BODY, ScoreType.LOAD)

@Composable
fun ExplainScreen(
    type: ScoreType,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** The day the Scores strip had picked, as days before the newest. */
    initialBack: Int = 0,
    onOpenCalc: (CalcSection) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val night by viewModel.night.collectAsStateWithLifecycle()
    val snapshot = (state as? HomeUiState.Ready)?.snapshot
    val pager = rememberPagerState(initialPage = PAGES.indexOf(type).coerceAtLeast(0)) { PAGES.size }
    // The look-in runs only after a ring was tapped, and never with reduced motion.
    val reduce = LocalReduceMotion.current
    val look = remember { LookIn(flying = !reduce && RingFlight.origin != null && RingFlight.type == type) }
    // Neighbouring pages are composed once the look-in is over, so its first frames stay light.
    var warm by remember { mutableStateOf(!look.flying) }
    LaunchedEffect(Unit) {
        look.run()
        warm = true
    }
    val scope = rememberCoroutineScope()
    val shown = PAGES[pager.currentPage]
    // Tapping a segment moves the page on the segmented curve.
    fun show(t: ScoreType) {
        scope.launch {
            pager.animateScrollToPage(PAGES.indexOf(t), animationSpec = if (reduce) snap() else tween(240, easing = EaseInOut))
        }
    }
    // A page landing is a discrete step, so it ticks.
    val haptic = rememberHaptic()
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.drop(1).collect { haptic(PawseHaptic.Tick) }
    }
    // One window day drives every page; it stays put as the pages swipe.
    var back by rememberSaveable { mutableIntStateOf(initialBack) }
    // Body and Load's Over time range, shared by both pages.
    var range by rememberSaveable { mutableIntStateOf(30) }
    // One pinned day to compare against, as days before the newest; your usual is always on.
    var pin by rememberSaveable { mutableStateOf<Int?>(null) }
    // The day sheet: true picks a day to pin, false moves the window day (the calendar).
    var daySheet by remember { mutableStateOf<Boolean?>(null) }
    val date = snapshot?.date?.minusDays(back.toLong())

    // The night is read whichever page is open, so swiping to Sleep finds it ready.
    val computedAt = snapshot?.computedAtEpochMs
    LaunchedEffect(date, computedAt) {
        if (date != null && computedAt != null) viewModel.openNight(date, computedAt)
    }

    // Each page keeps its own scroll; the open one collapses the rail and lifts the header.
    val lists = PAGES.map { rememberLazyListState() }
    val density = LocalDensity.current
    val collapseRange = with(density) { 80.dp.toPx() }
    val lift = with(density) { 4.dp.toPx() }
    fun scrolled(): Float = lists[pager.currentPage].let { if (it.firstVisibleItemIndex > 0) Float.MAX_VALUE else it.firstVisibleItemScrollOffset.toFloat() }
    val collapse = { (scrolled() / collapseRange).coerceIn(0f, 1f) }
    val lifted by remember { derivedStateOf { scrolled() > lift } }

    Scaffold(modifier = modifier, containerColor = MaterialTheme.colorScheme.background) { padding ->
        val top = padding.calculateTopPadding()
        Box(Modifier.fillMaxSize().aura()) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = if (warm) 1 else 0) { page ->
                ScorePage(
                    look = look,
                    type = PAGES[page],
                    snapshot = snapshot,
                    date = date,
                    back = back,
                    onPick = { back = it },
                    range = range,
                    // A narrower range drops a day picked outside it, as the canvas does.
                    onRange = { range = it; if (back >= it) back = 0 },
                    night = night?.takeIf { it.date == date },
                    onBody = { show(ScoreType.BODY) },
                    // Pages start under the 56 dp header and the 64 dp rail, and scroll under both.
                    top = top + 128.dp,
                    bottom = padding.calculateBottomPadding(),
                    list = lists[page],
                    pin = pin,
                    onCompare = { daySheet = true },
                    onUnpin = { pin = null },
                    onCalendar = { daySheet = false },
                    onBasis = { onOpenCalc(CalcSection.LIKELY) },
                )
            }
            // Pinned: back, the switch and the day rail stay while the pages swipe and scroll under them.
            Column(Modifier.fillMaxWidth()) {
                val fade = MaterialTheme.colorScheme.background.copy(alpha = 0.92f)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (lifted) fade else Color.Transparent)
                        .padding(start = 12.dp, top = top, end = 12.dp, bottom = 8.dp)
                        .height(48.dp)
                        .lookIn(look, 0),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlyphButton(Glyphs.ChevronLeft, tr("Back to Scores", "점수로 돌아가기"), onClick = onBack)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Segmented(
                            options = PAGES,
                            selected = shown,
                            onSelect = ::show,
                            label = ScoreCopy::name,
                            modifier = Modifier.width(240.dp),
                            position = { pager.currentPage + pager.currentPageOffsetFraction },
                        )
                    }
                    GlyphButton(Glyphs.Info, tr("How Pawse calculates ${ScoreCopy.name(shown)}", "${ScoreCopy.name(shown)} 계산 방법"), onClick = {
                        // The ⓘ opens How Pawse calculates at the open page's number.
                        onOpenCalc(
                            when (shown) {
                                ScoreType.BODY -> CalcSection.BODY
                                ScoreType.SLEEP -> CalcSection.SLEEP
                                ScoreType.LOAD -> CalcSection.LOAD
                            },
                        )
                    })
                }
                if (snapshot != null) {
                    DayRail(
                        snapshot = snapshot,
                        type = shown,
                        back = back,
                        pin = pin,
                        collapse = collapse,
                        lifted = lifted,
                        onPick = { back = it },
                        onPin = { pin = if (pin == it) null else it },
                        onCompare = { daySheet = true },
                        onUnpin = { pin = null },
                        modifier = Modifier.lookIn(look, 1),
                    )
                }
            }
        }
    }

    val sheet = daySheet
    if (sheet != null && snapshot != null) {
        DaySheet(
            snapshot = snapshot,
            type = shown,
            back = back,
            pinning = sheet,
            onPick = { d ->
                if (sheet) pin = d else back = d
                daySheet = null
            },
            onDismiss = { daySheet = null },
        )
    }
}

/** One page of the detail: the night in full for Sleep, the arithmetic for Body and Load. */
@Composable
private fun ScorePage(
    look: LookIn,
    type: ScoreType,
    snapshot: ScoringSnapshot?,
    date: LocalDate?,
    back: Int,
    onPick: (Int) -> Unit,
    range: Int,
    onRange: (Int) -> Unit,
    night: NightDetail?,
    onBody: () -> Unit,
    top: Dp,
    bottom: Dp,
    list: LazyListState,
    pin: Int?,
    onCompare: () -> Unit,
    onUnpin: () -> Unit,
    onCalendar: () -> Unit,
    onBasis: () -> Unit,
) {
    var open by rememberSaveable { mutableIntStateOf(0) }
    var mathOpen by rememberSaveable { mutableStateOf(false) }
    val pad = Modifier.padding(horizontal = 24.dp)
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = top, bottom = bottom + 48.dp)) {
        if (snapshot == null || date == null) {
            item {
                Text(
                    tr("Nothing to explain yet.", "아직 설명할 내용이 없어요."),
                    style = PawseType.Body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = pad.padding(top = 20.dp),
                )
            }
            return@LazyColumn
        }

        val day = if (back == 0) snapshot.today else snapshot.history.lastOrNull { it.date == date }
        val pinDate = pin?.let { snapshot.date.minusDays(it.toLong()) }
        val pinned = pinDate?.let { d -> if (pin == 0) snapshot.today else snapshot.history.lastOrNull { it.date == d } }
        item {
            DetailHero(
                look = look,
                type = type,
                day = day,
                date = date,
                back = back,
                oldest = (snapshot.history.size - 1).coerceAtLeast(0),
                showNumbers = snapshot.showNumbers,
                onPick = onPick,
                pinned = pinned,
                pinDate = pinDate,
                onCompare = onCompare,
                onUnpin = onUnpin,
                onCalendar = onCalendar,
                onBasis = onBasis,
                modifier = pad,
            )
        }
        if (pinDate != null) {
            item { SideBySide(sideRows(type, snapshot, day, pinned), date, pinDate, pad.padding(top = 24.dp).lookIn(look, 3)) }
        }

        if (type == ScoreType.SLEEP) {
            item { SleepTab(snapshot = snapshot, back = back, detail = night, onOpenNight = onPick, onBody = onBody, modifier = pad.lookIn(look, 3)) }
            item { Provenance(snapshot, pad.padding(top = 28.dp).lookIn(look, 4)) }
            return@LazyColumn
        }

        item {
            DayTab(
                snapshot = snapshot,
                type = type,
                back = back,
                onPick = onPick,
                range = range,
                onRange = onRange,
                open = open,
                onOpen = { open = it },
                modifier = pad.lookIn(look, 3),
            )
        }
        // Body's why, week, overnight pattern and effects come before how it was worked out, as on the canvas.
        if (type == ScoreType.BODY && day?.body != null) {
            item {
                Column(pad.padding(top = 32.dp).lookIn(look, 4)) {
                    BodyCards(day, snapshot.insights)
                }
            }
        }
        if (day != null) {
            item {
                Combiner(
                    combiner = combinerOf(type, day),
                    mathOpen = mathOpen,
                    onToggleMath = { mathOpen = !mathOpen },
                    modifier = pad.padding(top = 32.dp).lookIn(look, 4),
                )
            }
        }

        item { Provenance(snapshot, pad.padding(top = 28.dp).lookIn(look, 4)) }
    }
}

/**
 * The top of every page: the day with ‹ › to step, its date and Back to today,
 * then the score's ring at 168 with its word and one sentence. The same ring as
 * on Scores, a size up, so the tap lands where it pointed.
 */
@Composable
private fun DetailHero(
    look: LookIn,
    type: ScoreType,
    day: DayReport?,
    date: LocalDate,
    back: Int,
    oldest: Int,
    showNumbers: Boolean,
    onPick: (Int) -> Unit,
    pinned: DayReport?,
    pinDate: LocalDate?,
    onCompare: () -> Unit,
    onUnpin: () -> Unit,
    onCalendar: () -> Unit,
    onBasis: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = PawseTheme.semantics
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val haptic = rememberHaptic()
    val reduce = LocalReduceMotion.current
    val step = with(LocalDensity.current) { 12.dp.roundToPx() }
    Column(modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // ‹ day › on the left and Compare on the right, the date under it. The head
        // reaches 12 dp past the page padding so the arrows' glyphs sit on the text edge.
        Row(Modifier.fillMaxWidth().bleed(12.dp).heightIn(min = 48.dp).lookIn(look, 1), verticalAlignment = Alignment.CenterVertically) {
            GlyphButton(Glyphs.ChevronLeft, tr("Day before", "전날"), onClick = {
                if (back < oldest) {
                    haptic(PawseHaptic.Step)
                    onPick(back + 1)
                }
            })
            // The title opens the calendar: every route to a day stays visible. A step
            // slides it 12 dp in from the side it travelled, the old one out fast.
            AnimatedContent(
                targetState = date,
                modifier = Modifier.widthIn(max = 176.dp),
                transitionSpec = {
                    // An earlier day comes in from the left, a later one from the right.
                    val from = if (targetState < initialState) -step else step
                    if (reduce) {
                        fadeIn(tween(REDUCED_FADE)) togetherWith fadeOut(tween(90))
                    } else {
                        (slideInHorizontally(tween(180, easing = EaseOut)) { from } + fadeIn(tween(180, easing = EaseOut))) togetherWith
                            fadeOut(tween(90, easing = EaseOut))
                    }
                },
                label = "dayHead",
            ) { shownDate ->
                val shownTitle = dayTitle(shownDate)
                Text(
                    shownTitle,
                    style = PawseType.Heading.copy(fontSize = 17.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .pressable(onClick = onCalendar)
                        .semantics { contentDescription = "$shownTitle, " + tr("choose a date", "날짜 선택") }
                        .wrapContentHeight()
                        .padding(horizontal = 2.dp),
                )
            }
            GlyphButton(Glyphs.ChevronRight, tr("Day after", "다음 날"), onClick = {
                if (back > 0) {
                    haptic(PawseHaptic.Step)
                    onPick(back - 1)
                }
            })
            Spacer(Modifier.weight(1f))
            CompareChip(pinDate, onOpen = onCompare, onUnpin = onUnpin, modifier = Modifier.padding(end = 12.dp))
        }
        Row(
            Modifier.fillMaxWidth().bleed(12.dp).heightIn(min = 32.dp).padding(start = 48.dp, end = 12.dp).lookIn(look, 1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(shortDate(date), style = PawseType.Label, color = grey, modifier = Modifier.weight(1f))
            if (back > 0) {
                Text(
                    tr("Back to today", "오늘로"),
                    style = PawseType.Label.copy(fontWeight = PawseType.BodyStrong.fontWeight),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.heightIn(min = 32.dp).pressable { onPick(0) }.padding(horizontal = 4.dp, vertical = 7.dp),
                )
            }
        }

        val face = heroFace(type, day, showNumbers)
        ScoreRing(
            fraction = face.fraction,
            color = face.arc,
            size = 168.dp,
            glow = face.glow && s.aura.alpha > 0f,
            target = face.target,
            range = face.range,
            stroke = 6f,
            ghost = pinDate?.let { heroFace(type, pinned, showNumbers).fraction },
            // A day step sweeps old → new in 220 ms, no refill.
            changeMillis = 220,
            modifier = Modifier.padding(top = 4.dp).flightTarget(type),
        ) {
            // Another day keeps everything in place: the arc sweeps, the numeral swaps.
            Row(Modifier.swapIn(rememberSwap(face.value)), verticalAlignment = Alignment.Bottom) {
                Text(face.value, style = PawseType.RingXL)
                if (face.unit.isNotEmpty()) Text(face.unit, style = PawseType.Heading.copy(fontWeight = PawseType.Body.fontWeight), color = grey, modifier = Modifier.padding(start = 2.dp, bottom = 8.dp))
            }
        }
        BandDot(color = face.dot, label = face.word, hollow = face.hollow, modifier = Modifier.padding(top = 12.dp).lookIn(look, 2))
        // Load: the form word under the range word.
        if (type == ScoreType.LOAD) {
            day?.load?.formBand?.let { Text(ScoreCopy.form(it), style = PawseType.BodyStrong, modifier = Modifier.padding(top = 8.dp).lookIn(look, 2)) }
        }
        // Body: how sure the reading is, a chip that opens how the range and confidence are worked out.
        if (type == ScoreType.BODY && day != null) {
            val body = day.body
            val signals = listOfNotNull(body?.hrv?.tonight, body?.sleepingHr?.tonight).size
            Row(
                Modifier
                    .padding(top = 12.dp)
                    .lookIn(look, 2)
                    .heightIn(min = 48.dp)
                    .pressable(onClick = onBasis),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .height(36.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Glyph(Glyphs.Shield, size = 16.dp, color = grey)
                    Text(
                        when {
                            body == null -> tr("Learning your normal", "평소 상태를 배우는 중")
                            body.confidence == app.pawse.scoring.model.Confidence.HIGH -> tr("High confidence · $signals of 2 signals", "신뢰도 높음 · 신호 2개 중 ${signals}개")
                            signals < 2 -> tr("Low confidence · $signals of 2 signals", "신뢰도 낮음 · 신호 2개 중 ${signals}개")
                            else -> tr("Low confidence · few nights so far", "신뢰도 낮음 · 아직 밤이 적어요")
                        },
                        style = PawseType.Label,
                        color = grey,
                    )
                    Glyph(Glyphs.ChevronRight, size = 16.dp, color = grey)
                }
            }
        }
        Text(face.sentence, style = PawseType.Body, color = grey, modifier = Modifier.fillMaxWidth().padding(top = 16.dp).lookIn(look, 2))
    }
}

/** Reaches [x] past the parent's padding on both sides, like a CSS margin of -x. */
private fun Modifier.bleed(x: Dp): Modifier = layout { measurable, constraints ->
    val px = x.roundToPx()
    if (!constraints.hasBoundedWidth) return@layout measurable.measure(constraints).let { p -> layout(p.width, p.height) { p.place(0, 0) } }
    val p = measurable.measure(constraints.copy(minWidth = constraints.minWidth + 2 * px, maxWidth = constraints.maxWidth + 2 * px))
    layout(p.width - 2 * px, p.height) { p.place(-px, 0) }
}

private fun dayTitle(date: LocalDate): String {
    val now = LocalDate.now()
    return when (date) {
        now -> tr("Today", "오늘")
        now.minusDays(1) -> tr("Yesterday", "어제")
        else -> date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())
    }
}

private class HeroFace(
    val fraction: Float?,
    val arc: Color,
    val glow: Boolean,
    val value: String,
    val unit: String,
    val dot: Color,
    val hollow: Boolean,
    val word: String,
    val sentence: String,
    val target: Float? = null,
    val range: ClosedFloatingPointRange<Float>? = null,
)

@Composable
private fun heroFace(type: ScoreType, day: DayReport?, showNumbers: Boolean): HeroFace {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val neutral = PawseTheme.semantics.neutral
    if (day == null) return HeroFace(null, grey, false, "—", "", neutral, true, tr("No reading", "기록 없음"), tr("No reading for this day.", "이날은 기록이 없어요."))
    return when (type) {
        ScoreType.SLEEP -> {
            val sleep = day.sleep
            val slept = sleep.lastNightMinutes
            val need = sleep.needHours * 60.0
            val gap = slept?.let { need - it }
            HeroFace(
                fraction = slept?.let { (it / need).toFloat() },
                arc = grey,
                glow = false,
                value = slept?.let { String.format(Locale.getDefault(), "%.1f", it / 60.0) } ?: "—",
                unit = if (slept != null) tr("h", "시간") else "",
                dot = neutral,
                hollow = slept == null,
                word = when {
                    gap == null -> tr("No night", "기록 없음")
                    abs(gap) <= 30.0 -> tr("Close to your need", "필요량에 가까움")
                    gap > 0 -> tr("Short of your need", "필요량보다 짧음")
                    else -> tr("Longer than your need", "필요량보다 김")
                },
                sentence = when {
                    slept == null -> tr("No night came in from your watch.", "워치에서 들어온 밤 기록이 없어요.")
                    abs(gap!!) < 1.0 -> tr("You slept ${ScoreCopy.formatMinutes(slept)}, what you needed.", "${ScoreCopy.formatMinutes(slept)} 잤어요. 필요한 만큼이에요.")
                    gap > 0 -> tr("You slept ${ScoreCopy.formatMinutes(slept)}, ${ScoreCopy.formatMinutes(gap)} short of your ${ScoreCopy.formatMinutes(need)} need.", "${ScoreCopy.formatMinutes(slept)} 잤어요. 필요한 ${ScoreCopy.formatMinutes(need)}보다 ${ScoreCopy.formatMinutes(gap)} 부족해요.")
                    else -> tr("You slept ${ScoreCopy.formatMinutes(slept)}, ${ScoreCopy.formatMinutes(-gap)} over your ${ScoreCopy.formatMinutes(need)} need.", "${ScoreCopy.formatMinutes(slept)} 잤어요. 필요한 ${ScoreCopy.formatMinutes(need)}보다 ${ScoreCopy.formatMinutes(-gap)} 더 잤어요.")
                },
                target = if (slept != null) 1f else null,
            )
        }

        ScoreType.BODY -> {
            val body = day.body
            val color = bodyColor(body?.band, day.illness.level)
            HeroFace(
                fraction = body?.let { it.score / 100f },
                arc = color,
                glow = body != null,
                value = body?.let { if (showNumbers) it.score.toString() else "·" } ?: "—",
                unit = "",
                dot = color,
                hollow = body == null,
                word = body?.let { ScoreCopy.bandWord(it.band) } ?: tr("Learning", "배우는 중"),
                sentence = when {
                    day.illness.level == IllnessLevel.ALERT -> tr("Clearly off your normal. Take it easy and watch for symptoms.", "평소와 확연히 달라요. 무리하지 말고 증상이 있는지 살펴보세요.")
                    body == null -> ScoreCopy.bodyStatus(day.bodyStatus, day.validNights, ScoringConfig.DEFAULT.body.minValidNights)
                    body.band == BodyBand.ABOVE_NORMAL -> tr("Your body was a little ahead of its normal.", "몸이 평소보다 조금 앞서 있었어요.")
                    body.band == BodyBand.TYPICAL -> tr("Inside your normal range.", "평소 범위 안이에요.")
                    else -> tr("Something was costing you.", "무언가 몸에 부담을 주고 있었어요.")
                },
            )
        }

        ScoreType.LOAD -> {
            val load = day.load
            val ring = ScoringConfig.DEFAULT.points.ringPoints
            val range = load.pointsRange
            val shown = load.points.roundToInt()
            HeroFace(
                fraction = LoadPoints.ringFraction(load.points, ScoringConfig.DEFAULT.points),
                arc = grey,
                glow = false,
                value = shown.toString(),
                unit = "",
                dot = neutral,
                hollow = false,
                word = range?.let { tr("$shown of ${it.low}–${it.high}", "${it.low}–${it.high} 중 $shown") } ?: "$shown",
                sentence = when {
                    range == null -> tr("Load is training, not a grade, so it stays grey.", "부하는 평가가 아닌 훈련량이라 회색으로 둬요.")
                    range.rest -> tr("Rest or easy today: your body has been strained or your overnight heart rate high.", "오늘은 쉬거나 가볍게: 몸에 부담이 쌓였거나 야간 심박수가 높았어요.")
                    shown < range.low -> tr("Under the range Body set for the day.", "컨디션이 정한 범위보다 적어요.")
                    shown > range.high -> tr("Over the range Body set for the day.", "컨디션이 정한 범위를 넘었어요.")
                    else -> tr("Inside the range Body set for the day.", "컨디션이 정한 범위 안이에요.")
                } + " " + tr("Your typical active day is 10.", "평소 활동한 날이 10이에요."),
                range = range?.let { it.low.toFloat() / ring..it.high.toFloat() / ring },
            )
        }
    }
}

/** The formula that turns the parts into the reading, and what it gave. */
private class CombinerText(
    val formula: AnnotatedString,
    val left: String,
    val result: String,
    val note: String,
    val math: String,
)

private fun combinerOf(type: ScoreType, day: DayReport): CombinerText {
    val config = ScoringConfig.DEFAULT
    // Sleep's maths lives in its trends' "About this number" (LastNight.kt).
    return when (type) {
        ScoreType.BODY -> {
            val body = day.body
            val here = body?.let { "w = ${fmt(it.hrvWeight)}, ρ = ${fmt(it.rho)}, z = ${fmt(it.z)}" }
            val spread = body?.let { " (${fmt(it.zSd)})" } ?: ""
            CombinerText(
                formula = AnnotatedString("100 × Φ(z)"),
                left = body?.let { "z = ${signed(it.z, 2)}" } ?: "z",
                result = body?.score?.toString() ?: "—",
                note = tr("Your normal reads 50", "내 평소는 50"),
                math = tr(
                    "Each night is a noisy reading of your true state. A Kalman filter trusts it only as much as " +
                        "its quality allows, so a patchy night barely moves the estimate and a missed one widens the range. " +
                        "HRV and sleeping heart rate read the same nervous system, so " +
                        "z = (w·z_h + (1−w)·z_r) / √(w² + (1−w)² + 2w(1−w)ρ) divides their overlap ρ out" +
                        (here?.let { " — here $it" } ?: "") +
                        ". The range is ±${fmt(config.body.rangeZ)} of the filter's own spread$spread.",
                    "매일 밤의 기록은 실제 상태를 잡음과 함께 읽은 값이에요. 칼만 필터는 기록의 품질만큼만 그 값을 믿어서, " +
                        "끊긴 밤은 추정치를 거의 움직이지 않고 빠진 밤은 범위를 넓혀요. HRV와 수면 중 심박수는 같은 " +
                        "자율신경계를 읽기 때문에 z = (w·z_h + (1−w)·z_r) / √(w² + (1−w)² + 2w(1−w)ρ)로 둘의 겹침 ρ를 덜어내요" +
                        (here?.let { " — 여기서는 $it" } ?: "") +
                        ". 범위는 필터 자체 퍼짐${spread}의 ±${fmt(config.body.rangeZ)}배예요.",
                ),
            )
        }
        ScoreType.SLEEP, ScoreType.LOAD -> {
            val load = day.load
            val l = config.load
            val fitnessDays = l.fitnessDays.roundToInt()
            val fatigueDays = l.fatigueDays.roundToInt()
            CombinerText(
                formula = AnnotatedString(tr("Form = (fitness − fatigue) ÷ fitness", "폼 = (체력 − 피로) ÷ 체력")),
                left = tr("$fitnessDays days vs $fatigueDays", "${fitnessDays}일 대 ${fatigueDays}일"),
                result = load.form?.let { "${(it * 100).roundToInt()}%" } ?: "—",
                note = tr("Below ${pct(l.heavyBelow)} reads heavy", "${pct(l.heavyBelow)} 미만이면 무거움"),
                math = tr(
                    "Each session is Banister's TRIMP: minutes weighted by how far into your heart-rate reserve you " +
                        "worked, counted only above ${pct(l.minReserveFraction)} of it. Without a usable heart rate it " +
                        "falls back to effort × minutes. Fitness averages $fitnessDays days of load " +
                        "and fatigue $fatigueDays. Above ${pct(l.freshAbove)} form reads " +
                        "fresh, ${pct(l.freshAbove)} to ${pct(l.heavyBelow)} productive, below that heavy. " +
                        "The day's range is your typical training day scaled by that morning's Body: Body 50 is about " +
                        "usual, 80 about 30% more, 15 about two thirds. Starting defaults, never an injury-risk label.",
                    "각 운동은 Banister의 TRIMP로 계산해요. 심박 예비량을 얼마나 깊이 썼는지로 가중한 운동 시간(분)이며, " +
                        "예비량의 ${pct(l.minReserveFraction)}를 넘을 때만 집계해요. 쓸 만한 심박수가 없으면 운동 강도 × " +
                        "시간(분)으로 대신해요. 체력은 ${fitnessDays}일, 피로는 ${fatigueDays}일 동안의 부하 평균이에요. " +
                        "폼이 ${pct(l.freshAbove)}보다 높으면 개운함, ${pct(l.freshAbove)}~${pct(l.heavyBelow)}면 효과적, " +
                        "그보다 낮으면 무거움이에요. 그날의 범위는 평소 하루를 그날 아침 컨디션에 맞춰 조정해요. 컨디션 50이면 " +
                        "평소만큼, 80이면 30%쯤 더, 15면 3분의 2쯤이에요. 시작용 기본값일 뿐, 부상 위험을 판정하지 않아요.",
                ),
            )
        }
    }
}

@Composable
private fun Combiner(
    combiner: CombinerText,
    mathOpen: Boolean,
    onToggleMath: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val turn by animateFloatAsState(if (mathOpen) 180f else 0f, tween(220, easing = EaseOut), label = "chevron")

    PawseCard(modifier = modifier, padding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
        Text(tr("How it was worked out", "계산 방법"), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text(combiner.formula, style = PawseType.MetricValue)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(combiner.left, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Glyph(Glyphs.Arrow, size = 14.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(combiner.result, style = PawseType.Label)
            Text(
                combiner.note,
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clickable(onClick = onToggleMath),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(tr("How the maths works", "계산 원리"), style = PawseType.BodyStrong, modifier = Modifier.weight(1f))
            Glyph(Glyphs.ChevronDown, size = 18.dp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.rotate(turn))
        }
        AnimatedVisibility(mathOpen, enter = RevealEnter, exit = RevealExit) {
            Text(
                combiner.math,
                style = PawseType.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
    }
}

/** A card's small head: the label on the left, an optional word or figure on the right. */
@Composable
private fun CardHead(label: String, aside: String? = null, asideColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        aside?.let { Text(it, style = PawseType.BodyStrong, color = asideColor) }
    }
}

@Composable
private fun MicroLine(k: String, v: String) {
    Row(Modifier.padding(top = 8.dp)) {
        Text(k, style = PawseType.Micro, color = PawseTheme.semantics.faint, modifier = Modifier.weight(1f))
        Text(v, style = PawseType.Micro, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End)
    }
}

/** Body's cards under Over time, in the design's order: why, the week, the overnight pattern, what moves it. */
@Composable
private fun BodyCards(today: DayReport, insights: List<Insight>) {
    val body = today.body ?: return
    val faint = PawseTheme.semantics.faint
    // Why NN: each signal against your normal, in its own unit.
    val why = listOfNotNull(
        body.hrv?.let { h ->
            h.tonight?.let { t ->
                val p = ((t - h.baseline) / h.baseline * 100).roundToInt()
                if (abs(p) < 3) tr("HRV about your normal", "HRV는 평소와 비슷") else tr("HRV ${abs(p)}% ${if (p > 0) "above" else "below"} your normal", "HRV가 평소보다 ${abs(p)}% ${if (p > 0) "높음" else "낮음"}")
            }
        },
        body.sleepingHr?.let { r ->
            r.tonight?.let { t ->
                val d = t - r.baseline
                if (abs(d) < 1) tr("sleeping heart rate about your normal", "수면 중 심박수는 평소와 비슷") else tr("sleeping heart rate ${f1(abs(d))} bpm ${if (d > 0) "above" else "below"} your normal", "수면 중 심박수가 평소보다 ${f1(abs(d))} bpm ${if (d > 0) "높음" else "낮음"}")
            }
        },
    ).joinToString("; ").replaceFirstChar { it.uppercase() }
    PawseCard {
        CardHead(tr("Why ${body.score}", "왜 ${body.score}인가요"))
        Text(if (why.isEmpty()) tr("Your usual needs a few more nights before this score can be explained.", "이 점수를 설명하려면 평소 범위를 알 밤이 몇 개 더 필요해요.") else "$why.", style = PawseType.Body, modifier = Modifier.padding(top = 8.dp))
        if (body.reasons.isNotEmpty()) Text(ScoreCopy.reasons(body.reasons), style = PawseType.Micro, color = faint, modifier = Modifier.padding(top = 8.dp))
    }
    today.weekly?.let { w ->
        PawseCard(Modifier.padding(top = 16.dp)) {
            CardHead(tr("The week, not just the day", "하루가 아니라 한 주"), ScoreCopy.weekly(w.pattern))
            MicroLine(tr("7-day HRV", "7일 HRV"), tr("${w.hrv7.roundToInt()} ms · ${w.nights} nights", "${w.hrv7.roundToInt()} ms · ${w.nights}일 밤"))
            MicroLine(tr("Day-to-day variation", "날마다 변동"), "${f1(w.cv7)}%" + (w.usualCv7?.let { tr(" (usually ${f1(it)}%)", " (평소 ${f1(it)}%)") } ?: ""))
        }
    }
    PawseCard(Modifier.padding(top = 16.dp)) {
        CardHead(
            tr("Overnight heart-rate pattern", "밤사이 심박 패턴"),
            tr("Running sum ${fmt(today.illness.cusum)}", "누적 합 ${fmt(today.illness.cusum)}"),
            if (today.illness.level == IllnessLevel.ALERT) PawseTheme.semantics.bandLow else MaterialTheme.colorScheme.onSurface,
        )
        Row(Modifier.padding(top = 8.dp)) {
            Text(tr("Quiet note above 3", "3 넘으면 조용한 알림"), style = PawseType.Micro, color = faint, modifier = Modifier.weight(1f))
            Text(tr("Alert above 5, with temperature or breathing up", "5 넘고 체온이나 호흡이 오르면 경보"), style = PawseType.Micro, color = faint, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
        Text(
            tr(
                "Small nightly excesses over your normal add up; logged drinks, travel and stress are checked first. It never names a disease.",
                "평소보다 조금씩 높은 밤이 쌓여요. 기록된 술, 여행, 스트레스를 먼저 확인하고, 질병 이름은 붙이지 않아요.",
            ),
            style = PawseType.Micro,
            color = faint,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    Text(tr("What moves your Body", "컨디션을 움직이는 것"), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 32.dp))
    PawseCard(Modifier.padding(top = 12.dp)) {
        if (insights.isEmpty()) {
            Text(
                tr(
                    "Nothing clear yet. An effect shows after at least 8 days of exposure, and only when its 90% range excludes zero.",
                    "아직 뚜렷한 건 없어요. 최소 8일 이상 겪고, 90% 범위가 0을 포함하지 않을 때만 보여줘요.",
                ),
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            insights.forEach { Text(ScoreCopy.insight(it), style = PawseType.Body, modifier = Modifier.padding(top = 8.dp)) }
            Text(ScoreCopy.INSIGHT_NOTE, style = PawseType.Micro, color = faint, modifier = Modifier.padding(top = 16.dp))
        }
    }
}

@Composable
private fun Provenance(snapshot: ScoringSnapshot, modifier: Modifier = Modifier) {
    Column(modifier) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))
        DetailLine(tr("Engine", "엔진"), snapshot.scoringVersion)
        DetailLine(tr("Defaults", "기본값"), snapshot.configHash)
        Spacer(Modifier.height(8.dp))
        Note(
            tr(
                "Every number marked as a default is a reasoned starting point, not a validated value.",
                "기본값으로 표시된 숫자는 모두 근거 있는 출발점일 뿐, 검증된 값이 아니에요.",
            ) + " " + ScoreCopy.FOOTER,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = PawseType.Heading)
    Spacer(Modifier.height(10.dp))
}

@Composable
internal fun DetailLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(label, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(0.38f))
        Text(value, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

internal fun minutesText(minutes: Double): String = "${minutes.roundToInt()}${tr(" min", "분")}"

internal fun pct(fraction: Double): String = "${(fraction * 100).roundToInt()}%"

private fun fmt(v: Double): String = String.format(Locale.getDefault(), "%.2f", v)

private fun f1(v: Double): String = String.format(Locale.getDefault(), "%.1f", v)
