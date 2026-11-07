package app.pawse.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pawse.core.data.scoring.ScoringSnapshot
import app.pawse.core.ui.component.Glyph
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.Mark
import app.pawse.core.ui.component.PawseCard
import app.pawse.core.ui.component.PawseSheet
import app.pawse.core.ui.component.pressable
import app.pawse.core.ui.theme.PawseTheme
import app.pawse.core.ui.theme.PawseType
import app.pawse.scoring.math.Stats
import app.pawse.scoring.model.DayReport
import app.pawse.scoring.model.ScoreType
import app.pawse.scoring.tr
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Compare: two references, never more. Your usual is always on; one pinned day is
 * optional. Differences stay grey: no better or worse, no colour on a change.
 */

/** The Compare chip: "Compare" until a day is pinned, then "vs Fri 23" with its own ×. */
@Composable
internal fun CompareChip(pinned: LocalDate?, onOpen: () -> Unit, onUnpin: () -> Unit, modifier: Modifier = Modifier, height: Dp = 36.dp) {
    val s = PawseTheme.semantics
    val raised = MaterialTheme.colorScheme.surfaceContainerHigh
    Row(modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.height(height).background(if (pinned != null) s.selection else raised, RoundedCornerShape(12.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .heightIn(min = 48.dp)
                    .pressable(onClick = onOpen)
                    .clearAndSetSemantics {
                        contentDescription = pinned?.let { tr("Comparing with ${shortDate(it)}. Change it.", "${shortDate(it)}와 비교 중. 바꾸기.") } ?: tr("Compare with another day", "다른 날과 비교")
                        role = Role.Button
                    }
                    .padding(start = 14.dp, end = if (pinned != null) 4.dp else 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    pinned?.let { tr("vs ${shortDate(it)}", "비교: ${shortDate(it)}") } ?: tr("Compare", "비교"),
                    style = PawseType.Label,
                    color = if (pinned != null) s.onSelection else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (pinned != null) {
                Box(
                    Modifier
                        .size(48.dp)
                        .pressable(onClick = onUnpin)
                        .clearAndSetSemantics {
                            contentDescription = tr("Stop comparing", "비교 끝내기")
                            role = Role.Button
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Glyph(Glyphs.Close, size = 14.dp, color = s.onSelection)
                }
            }
        }
    }
}

/**
 * Where a pinned day comes from: the day before, the same day last week, or any
 * day from the calendar. The calendar also moves the window day when [pinning] is false.
 */
@Composable
internal fun DaySheet(
    snapshot: ScoringSnapshot,
    type: ScoreType,
    back: Int,
    pinning: Boolean,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
    /** The pinned day, as days before the newest, for Swap and Stop comparing. */
    pin: Int? = null,
    onSwap: () -> Unit = {},
    onUnpin: () -> Unit = {},
) {
    val oldest = (snapshot.history.size - 1).coerceAtLeast(0)
    val newest = snapshot.date
    // Compare is a short list; Another day… turns the same sheet into the calendar.
    var calendar by remember { mutableStateOf(!pinning) }
    PawseSheet(onDismiss = onDismiss) {
        if (!calendar) {
            Text(
                tr("Compare ${shortDate(newest.minusDays(back.toLong()))} with", "비교할 날 · ${shortDate(newest.minusDays(back.toLong()))} 기준"),
                style = PawseType.Heading,
            )
            Column(Modifier.padding(top = 8.dp)) {
                fun dayRow(target: Int, en: String, ko: String): Pair<String, Int?> {
                    val d = shortDate(newest.minusDays(target.toLong()))
                    return if (target <= oldest) tr("$en · $d", "$ko · $d") to target else tr("$en · before Pawse", "$ko · Pawse 사용 전") to null
                }
                buildList {
                    if (pin != null && pin != back) {
                        add(tr("Swap: look at ${shortDate(newest.minusDays(pin.toLong()))}, compare with ${shortDate(newest.minusDays(back.toLong()))}", "바꾸기: ${shortDate(newest.minusDays(pin.toLong()))} 보기 · ${shortDate(newest.minusDays(back.toLong()))} 비교") to { onSwap() })
                    }
                    listOf(dayRow(back + 1, "The day before", "전날"), dayRow(back + 7, "Same day last week", "지난주 같은 요일")).forEach { (label, target) ->
                        add(label to target?.let { t -> { onPick(t) } })
                    }
                    add(tr("Another day…", "다른 날…") to { calendar = true })
                    if (pin != null) add(tr("Stop comparing", "비교 그만하기") to { onUnpin(); onDismiss() })
                }.forEach { (label, go) ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .alpha(if (go != null) 1f else 0.38f)
                            .pressable(enabled = go != null) { go?.invoke() },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(label, style = PawseType.BodyStrong)
                    }
                }
            }
        } else {
            Text(if (pinning) tr("Pick a day to compare", "비교할 날 선택") else tr("Choose a day", "날짜 선택"), style = PawseType.Heading)
            Calendar(snapshot, type, selected = if (pinning) null else back, pin = pin, onPick = onPick)
        }
    }
}

/**
 * Months of days, oldest first, each with the open page's mark; the picked day sits
 * on a card, today is underlined, a pinned day is dotted, and days before Pawse or
 * after today are dimmed. Opens scrolled to the picked day's month.
 */
@Composable
private fun Calendar(snapshot: ScoringSnapshot, type: ScoreType, selected: Int?, pin: Int?, onPick: (Int) -> Unit) {
    val byDate = snapshot.history.associateBy { it.date } + (snapshot.date to snapshot.today)
    val first = snapshot.history.firstOrNull()?.date ?: snapshot.date
    val s = PawseTheme.semantics
    val scheme = MaterialTheme.colorScheme
    val months = generateSequence(first.withDayOfMonth(1)) { it.plusMonths(1) }.takeWhile { !it.isAfter(snapshot.date) }.toList().takeLast(4)
    val current = (selected ?: 0).let { snapshot.date.minusDays(it.toLong()).withDayOfMonth(1) }
    val scroll = rememberScrollState()
    var currentTop by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(currentTop) { currentTop?.let { scroll.scrollTo(it) } }
    val weekdays = listOf("S" to "일", "M" to "월", "T" to "화", "W" to "수", "T" to "목", "F" to "금", "S" to "토")
    Column(Modifier.heightIn(max = 432.dp).verticalScroll(scroll)) {
        months.forEach { m ->
            Column(
                Modifier
                    .padding(top = 12.dp)
                    .onGloballyPositioned { if (m == current && currentTop == null) currentTop = it.positionInParent().y.toInt() },
            ) {
                Text(m.format(DateTimeFormatter.ofPattern(tr("MMMM yyyy", "yyyy년 M월"), Locale.getDefault())), style = PawseType.BodyStrong)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    weekdays.forEach { (en, ko) ->
                        Text(tr(en, ko), style = PawseType.Micro.copy(fontSize = 11.sp, letterSpacing = 0.sp), color = s.faint, textAlign = TextAlign.Center, modifier = Modifier.width(48.dp))
                    }
                }
                val lead = m.dayOfWeek.value % 7
                val cells: List<LocalDate?> = List(lead) { null } + (1..m.lengthOfMonth()).map { m.withDayOfMonth(it) }
                cells.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        (0 until 7).forEach { i ->
                            val d = week.getOrNull(i)
                            if (d == null) {
                                Spacer(Modifier.size(48.dp))
                                return@forEach
                            }
                            val back = java.time.temporal.ChronoUnit.DAYS.between(d, snapshot.date).toInt()
                            val report = byDate[d]
                            val ok = back >= 0 && !d.isBefore(first)
                            val on = back == selected
                            Column(
                                Modifier
                                    .size(48.dp)
                                    .alpha(if (ok) 1f else 0.38f)
                                    .then(if (on) Modifier.background(scheme.surfaceContainer, RoundedCornerShape(14.dp)).border(1.dp, scheme.outlineVariant, RoundedCornerShape(14.dp)) else Modifier)
                                    .then(
                                        if (back == pin) {
                                            Modifier.drawBehind {
                                                val inset = 2.dp.toPx()
                                                drawRoundRect(
                                                    scheme.onSurfaceVariant,
                                                    topLeft = Offset(inset, inset),
                                                    size = Size(size.width - 2 * inset, size.height - 2 * inset),
                                                    cornerRadius = CornerRadius(12.dp.toPx()),
                                                    style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.01f, 3.dp.toPx()))),
                                                )
                                            }
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .pressable(enabled = ok) { onPick(back) }
                                    .clearAndSetSemantics {
                                        contentDescription = shortDate(d) + when {
                                            back < 0 -> tr(", not yet", ", 아직 오지 않은 날")
                                            !ok -> tr(", before Pawse, no data", ", Pawse 사용 전이라 기록 없음")
                                            report == null -> tr(", no reading", ", 기록 없음")
                                            else -> ""
                                        }
                                    },
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    "${d.dayOfMonth}",
                                    style = PawseType.Body.copy(fontSize = 15.sp, lineHeight = 20.sp, textDecoration = if (back == 0) TextDecoration.Underline else null),
                                    color = if (on) scheme.onSurface else scheme.onSurfaceVariant,
                                )
                                if (ok && report != null) DayMark(report, type, weekPeak(byDate, d), Modifier.padding(top = 2.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One figure on both days. */
internal class SideRow(val label: String, val a: Double?, val b: Double?, val format: (Double) -> String, val unit: String, val history: List<Double>)

/** The figures each page compares, read from the engine's reports and the stored nights. */
internal fun sideRows(type: ScoreType, snapshot: ScoringSnapshot, a: DayReport?, b: DayReport?): List<SideRow> {
    val past = snapshot.history.takeLast(60)
    fun hist(get: (DayReport) -> Double?) = past.mapNotNull(get)
    fun nightHrv(d: DayReport?) = d?.let { snapshot.nights[it.date]?.signals?.lnHrv?.value?.let(::exp) }
    fun nightHr(d: DayReport?) = d?.let { snapshot.nights[it.date]?.signals?.sleepingHr?.value }
    val oneDp: (Double) -> String = { String.format(Locale.getDefault(), "%.1f", it) }
    val whole: (Double) -> String = { "${it.roundToInt()}" }
    val hm: (Double) -> String = { ScoreCopy.formatMinutes(it) }
    return when (type) {
        ScoreType.SLEEP -> listOf(
            SideRow(tr("Asleep", "수면"), a?.sleep?.lastNightMinutes, b?.sleep?.lastNightMinutes, hm, "", hist { it.sleep.lastNightMinutes }),
            SideRow(tr("Deep", "깊은 잠"), a?.sleep?.deepMinutes, b?.sleep?.deepMinutes, hm, "", hist { it.sleep.deepMinutes }),
            SideRow("REM", a?.sleep?.remMinutes, b?.sleep?.remMinutes, hm, "", hist { it.sleep.remMinutes }),
            SideRow(tr("Awake", "깬 시간"), a?.sleep?.wasoMinutes, b?.sleep?.wasoMinutes, hm, "", hist { it.sleep.wasoMinutes }),
        )
        ScoreType.BODY -> listOf(
            SideRow(tr("Body", "컨디션"), a?.body?.score?.toDouble(), b?.body?.score?.toDouble(), whole, "", hist { it.body?.score?.toDouble() }),
            SideRow("HRV", nightHrv(a), nightHrv(b), whole, "ms", past.mapNotNull { nightHrv(it) }),
            SideRow(tr("Sleeping HR", "수면 중 심박수"), nightHr(a), nightHr(b), whole, "bpm", past.mapNotNull { nightHr(it) }),
        )
        ScoreType.LOAD -> listOf(
            SideRow(tr("Points", "점수"), a?.load?.points, b?.load?.points, whole, "", hist { it.load.points }),
            SideRow(tr("Form", "폼"), a?.load?.form?.times(100), b?.load?.form?.times(100), whole, "%", hist { it.load.form?.times(100) }),
            SideRow(tr("Fatigue", "피로"), a?.load?.fatigue, b?.load?.fatigue, oneDp, "", hist { it.load.fatigue }),
        )
    }
}

/**
 * Side by side: both days, with an arrow and words for each change, or "≈ about
 * the same" when it sits inside your usual day-to-day change. Then the biggest gap.
 */
@Composable
internal fun SideBySide(rows: List<SideRow>, dayA: LocalDate, dayB: LocalDate, modifier: Modifier = Modifier) {
    val s = PawseTheme.semantics
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    var biggest: Pair<String, Double>? = null
    PawseCard(modifier = modifier) {
        Row(Modifier.fillMaxWidth()) {
            Text(tr("Side by side", "나란히 보기"), style = PawseType.BodyStrong, modifier = Modifier.weight(1f))
            Text("${shortDate(dayA)} · ${shortDate(dayB)}", style = PawseType.Micro, color = s.faint)
        }
        rows.forEach { r ->
            val a = r.a
            val b = r.b
            val diffs = r.history.zipWithNext { x, y -> y - x }
            val step = Stats.sd(diffs)
            val change = if (a != null && b != null) a - b else null
            val same = change != null && step != null && abs(change) <= 0.5 * step
            if (change != null && step != null && step > 0 && !same) {
                val size = abs(change) / step
                if (biggest == null || size > biggest!!.second) biggest = r.label to size
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.label, style = PawseType.Label, color = grey, modifier = Modifier.weight(1.2f))
                Text(a?.let { r.format(it) + if (r.unit.isNotEmpty()) " ${r.unit}" else "" } ?: "—", style = PawseType.BodyStrong, modifier = Modifier.weight(1f))
                Text(b?.let { r.format(it) } ?: "—", style = PawseType.Body, color = grey, modifier = Modifier.weight(0.8f))
                Text(
                    when {
                        change == null -> "—"
                        same || abs(change) < 1e-9 -> tr("≈ same", "≈ 비슷")
                        change > 0 -> "↑ " + r.format(abs(change))
                        else -> "↓ " + r.format(abs(change))
                    },
                    style = PawseType.Micro,
                    color = s.faint,
                    modifier = Modifier.weight(0.9f),
                )
            }
        }
        Text(
            biggest?.let { tr("The biggest gap is ${it.first.lowercase()}.", "가장 큰 차이는 ${it.first}예요.") } ?: tr("About the same on every figure.", "모든 수치가 거의 같아요."),
            style = PawseType.Label,
            color = grey,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            tr("≈ means inside your usual day-to-day change. Changes stay grey.", "≈는 평소 하루 사이 변화 안이라는 뜻이에요. 변화는 회색으로 둬요."),
            style = PawseType.Micro,
            color = s.faint,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
