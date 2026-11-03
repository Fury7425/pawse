package app.pawse.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
    fun show(t: ScoreType) {
        scope.launch { pager.animateScrollToPage(PAGES.indexOf(t)) }
    }
    // One window day drives every page; it stays put as the pages swipe.
    var back by rememberSaveable { mutableIntStateOf(initialBack) }
    // Body and Load's Over time range, shared by both pages.
    var range by rememberSaveable { mutableIntStateOf(30) }
    // The ⓘ opens how the open page's number is worked out, as a sheet.
    var mathFor by remember { mutableStateOf<ScoreType?>(null) }
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

    Scaffold(modifier = modifier, containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .aura()
                .padding(top = padding.calculateTopPadding() + 12.dp),
        ) {
            // Pinned: back, the switch and the day rail stay while the pages swipe under them.
            Row(Modifier.padding(horizontal = 12.dp).heightIn(min = 56.dp).lookIn(look, 0), verticalAlignment = Alignment.CenterVertically) {
                GlyphButton(Glyphs.ChevronLeft, tr("Back to Scores", "점수로 돌아가기"), onClick = onBack)
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Segmented(
                        options = PAGES,
                        selected = shown,
                        onSelect = ::show,
                        label = ScoreCopy::name,
                        height = 32.dp,
                        modifier = Modifier.width(240.dp),
                    )
                }
                GlyphButton(Glyphs.Info, tr("How Pawse calculates ${ScoreCopy.name(shown)}", "${ScoreCopy.name(shown)} 계산 방법"), onClick = { mathFor = shown })
            }
            if (snapshot != null) {
                DateStrip(snapshot, shown, back, onPick = { back = it }, modifier = Modifier.padding(start = 24.dp, top = 8.dp, end = 24.dp).lookIn(look, 1))
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f), beyondViewportPageCount = if (warm) 1 else 0) { page ->
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
                    bottom = padding.calculateBottomPadding(),
                    pin = pin,
                    onCompare = { daySheet = true },
                    onUnpin = { pin = null },
                    onCalendar = { daySheet = false },
                )
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

    mathFor?.let { type ->
        val day = snapshot?.history?.lastOrNull { it.date == date }
        PawseSheet(onDismiss = { mathFor = null }) {
            Text(tr("How Pawse calculates ${ScoreCopy.name(type)}", "${ScoreCopy.name(type)} 계산 방법"), style = PawseType.Heading)
            if (day != null && type != ScoreType.SLEEP) {
                Combiner(combiner = combinerOf(type, day), mathOpen = true, onToggleMath = {}, modifier = Modifier.padding(top = 16.dp))
            } else {
                Text(
                    tr(
                        "Sleep is hours asleep against your need. Need comes from your nights off and rises a little after a hard day. Stages are shown, never scored.",
                        "수면은 필요한 잠에 대한 실제 수면 시간이에요. 필요한 잠은 쉬는 날 밤에서 배우고, 힘든 날 뒤에는 조금 늘어요. 수면 단계는 보여주기만 하고 점수에 넣지 않아요.",
                    ),
                    style = PawseType.Body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
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
    bottom: Dp,
    pin: Int?,
    onCompare: () -> Unit,
    onUnpin: () -> Unit,
    onCalendar: () -> Unit,
) {
    var open by rememberSaveable { mutableIntStateOf(0) }
    var mathOpen by rememberSaveable { mutableStateOf(false) }
    val pad = Modifier.padding(horizontal = 24.dp)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = bottom + 48.dp)) {
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
        if (day != null) {
            item {
                Combiner(
                    combiner = combinerOf(type, day),
                    mathOpen = mathOpen,
                    onToggleMath = { mathOpen = !mathOpen },
                    modifier = pad.padding(top = 28.dp).lookIn(look, 4),
                )
            }
        }
        // Body's reasons, week, overnight pattern and insights sit under the combiner, as on the board.
        if (type == ScoreType.BODY && day?.body != null) {
            item {
                Column(pad.padding(top = 28.dp).lookIn(look, 4), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    BodyCards(day, snapshot.insights)
                }
            }
        }

        item { Provenance(snapshot, pad.padding(top = 28.dp).lookIn(look, 4)) }
    }
}

/**
 * The day rail: seven chips ending at the newest day, each with the open page's
 * mark and no number, so it never reads as a scoreboard. Body is its band dot,
 * Sleep a bar against need, Load a bar against the ring's 16 points.
 */
@Composable
private fun DateStrip(snapshot: ScoringSnapshot, type: ScoreType, back: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val byDate = snapshot.history.associateBy { it.date }
    val s = PawseTheme.semantics
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val raised = MaterialTheme.colorScheme.surfaceContainerHigh
    val ring = ScoringConfig.DEFAULT.points.ringPoints
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (d in 6 downTo 0) {
            val date = snapshot.date.minusDays(d.toLong())
            val day = if (d == 0) snapshot.today else byDate[date]
            val picked = d == back
            val dot = day?.body?.let { bodyColor(it.band, day.illness.level) }
            val bar = when (type) {
                ScoreType.SLEEP -> day?.sleep?.let { sl -> sl.lastNightMinutes?.let { it / (sl.needHours * 60.0) } }
                ScoreType.LOAD -> day?.load?.points?.let { it / ring }
                ScoreType.BODY -> null
            }?.toFloat()?.coerceIn(0.08f, 1f)
            Column(
                Modifier
                    .weight(1f)
                    .height(64.dp)
                    .pressable(enabled = day != null, role = Role.Tab) { onPick(d) }
                    .background(if (picked) raised else Color.Transparent, RoundedCornerShape(12.dp))
                    .semantics(mergeDescendants = true) { selected = picked }
                    .padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()), style = PawseType.Micro, color = s.faint)
                Text(date.dayOfMonth.toString(), style = PawseType.Heading)
                Canvas(Modifier.padding(top = 2.dp).size(12.dp)) {
                    when {
                        day == null -> Unit
                        type == ScoreType.BODY && dot != null -> drawCircle(dot, 3.dp.toPx())
                        type == ScoreType.BODY -> drawCircle(grey, 2.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                        bar != null -> {
                            val h = bar * size.height
                            drawRoundRect(grey, Offset(4.5.dp.toPx(), size.height - h), Size(3.dp.toPx(), h), CornerRadius(1.5.dp.toPx()))
                        }
                    }
                }
            }
        }
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
    modifier: Modifier = Modifier,
) {
    val s = PawseTheme.semantics
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val now = LocalDate.now()
    val title = when (date) {
        now -> tr("Today", "오늘")
        now.minusDays(1) -> tr("Yesterday", "어제")
        else -> date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())
    }
    Column(modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().lookIn(look, 1), verticalAlignment = Alignment.CenterVertically) {
            GlyphButton(Glyphs.ChevronLeft, tr("Day before", "전날"), onClick = { if (back < oldest) onPick(back + 1) })
            // The title opens the calendar: every route to a day stays visible.
            Text(
                title,
                style = PawseType.Heading,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .pressable(onClick = onCalendar)
                    .semantics { contentDescription = "$title, " + tr("choose a date", "날짜 선택") }
                    .padding(top = 12.dp),
            )
            GlyphButton(Glyphs.ChevronRight, tr("Day after", "다음 날"), onClick = { if (back > 0) onPick(back - 1) })
        }
        Row(Modifier.lookIn(look, 1), verticalAlignment = Alignment.CenterVertically) {
            Text(shortDate(date), style = PawseType.Micro, color = s.faint)
            if (back > 0) {
                Text(
                    tr("Back to today", "오늘로"),
                    style = PawseType.Micro.copy(fontWeight = PawseType.BodyStrong.fontWeight),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 8.dp).heightIn(min = 32.dp).pressable { onPick(0) }.padding(horizontal = 6.dp, vertical = 8.dp),
                )
            }
        }
        CompareChip(pinDate, onOpen = onCompare, onUnpin = onUnpin)

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
            modifier = Modifier.padding(top = 4.dp).flightTarget(type),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(face.value, style = PawseType.RingXL)
                if (face.unit.isNotEmpty()) Text(face.unit, style = PawseType.Heading.copy(fontWeight = PawseType.Body.fontWeight), color = grey, modifier = Modifier.padding(start = 2.dp, bottom = 8.dp))
            }
        }
        BandDot(color = face.dot, label = face.word, hollow = face.hollow, modifier = Modifier.padding(top = 12.dp).lookIn(look, 2))
        Text(face.sentence, style = PawseType.Body, color = grey, modifier = Modifier.fillMaxWidth().padding(top = 16.dp).lookIn(look, 2))
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

@Composable
private fun BodyCards(today: DayReport, insights: List<Insight>) {
    val body = today.body ?: return
    if (body.reasons.isNotEmpty()) {
        PawseCard {
            SectionTitle(tr("Top reasons", "주요 이유"))
            Text(ScoreCopy.reasons(body.reasons), style = PawseType.Body)
            Spacer(Modifier.height(8.dp))
            Note(
                ScoreCopy.bandMeaning(body.band) + tr(
                    " Body ${body.score} is roughly “better than ${body.score}% of your normal days”.",
                    " 컨디션 ${body.score}점은 대략 ‘평소 날의 ${body.score}%보다 좋음’이라는 뜻이에요.",
                ),
            )
        }
    }
    today.weekly?.let { w ->
        PawseCard {
            SectionTitle(tr("The week, not just the day", "하루가 아닌 한 주"))
            Text(ScoreCopy.weekly(w.pattern), style = PawseType.Body)
            Spacer(Modifier.height(6.dp))
            DetailLine(tr("7-day HRV", "7일 HRV"), tr("${w.hrv7.roundToInt()} ms over ${w.nights} nights", "${w.nights}밤 평균 ${w.hrv7.roundToInt()} ms"))
            DetailLine(
                tr("Day-to-day variation", "날마다의 변동"),
                "${fmt(w.cv7)}%" + (w.usualCv7?.let { tr(" (usually ${fmt(it)}%)", " (평소 ${fmt(it)}%)") } ?: ""),
            )
            Spacer(Modifier.height(8.dp))
            Note(
                tr(
                    "A falling weekly HRV with shrinking variation under heavy load is a warning even when no single day looks bad.",
                    "높은 부하 속에 주간 HRV가 떨어지고 변동도 줄어든다면, 하루하루는 괜찮아 보여도 경고 신호예요.",
                ),
            )
        }
    }
    PawseCard {
        SectionTitle(tr("Overnight heart-rate pattern", "야간 심박수 패턴"))
        DetailLine(tr("Running sum", "누적 합"), fmt(today.illness.cusum))
        DetailLine(tr("Quiet note above", "조용한 알림 기준"), "3")
        DetailLine(tr("Alert above", "경고 기준"), tr("5, with temperature or breathing up", "5, 체온이나 호흡수도 올랐을 때"))
        Spacer(Modifier.height(8.dp))
        Note(
            tr(
                "Small excesses add up night after night, so three slightly high nights count and one random spike does not. " +
                    "Logged drinks and travel or stress tags are checked first. It never names a disease.",
                "작은 초과분이 밤마다 쌓이는 방식이라, 조금 높은 밤 세 번은 반영되고 우연한 한 번의 급등은 반영되지 " +
                    "않아요. 기록한 음주와 여행·스트레스 태그를 먼저 확인해요. 병명은 절대 말하지 않아요.",
            ),
        )
    }
    PawseCard {
        SectionTitle(tr("What moves your Body", "컨디션을 움직이는 것"))
        if (insights.isEmpty()) {
            Note(
                tr(
                    "Nothing clear yet. An insight appears after at least 8 days of exposure and only when its 90% range excludes zero.",
                    "아직 뚜렷한 건 없어요. 최소 8일 이상 겪은 일이고 90% 범위가 0을 포함하지 않을 때만 인사이트가 나타나요.",
                ),
            )
        } else {
            insights.forEach {
                Text(ScoreCopy.insight(it), style = PawseType.Body, modifier = Modifier.padding(vertical = 4.dp))
            }
            Spacer(Modifier.height(4.dp))
            Note(ScoreCopy.INSIGHT_NOTE)
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
