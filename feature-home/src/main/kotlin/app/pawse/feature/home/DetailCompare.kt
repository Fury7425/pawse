package app.pawse.feature.home

import androidx.compose.foundation.background
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
internal fun CompareChip(pinned: LocalDate?, onOpen: () -> Unit, onUnpin: () -> Unit, modifier: Modifier = Modifier) {
    val s = PawseTheme.semantics
    val raised = MaterialTheme.colorScheme.surfaceContainerHigh
    Row(modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.height(36.dp).background(if (pinned != null) s.selection else raised, RoundedCornerShape(12.dp)),
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
) {
    val oldest = (snapshot.history.size - 1).coerceAtLeast(0)
    PawseSheet(onDismiss = onDismiss) {
        Text(if (pinning) tr("Compare with", "비교할 날") else tr("Choose a day", "날짜 선택"), style = PawseType.Heading)
        if (pinning) {
            listOf(
                tr("The day before", "전날") to back + 1,
                tr("Same day last week", "지난주 같은 요일") to back + 7,
            ).forEach { (label, target) ->
                val ok = target <= oldest
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .pressable(enabled = ok) { onPick(target) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, style = PawseType.BodyStrong, color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(shortDate(snapshot.date.minusDays(target.toLong())), style = PawseType.Micro, color = PawseTheme.semantics.faint)
                }
            }
            Text(tr("Or another day", "또는 다른 날"), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
        }
        Calendar(snapshot, type, selected = if (pinning) null else back, onPick = onPick)
    }
}

/** Months of days with the open page's mark under each; days before Pawse cannot be picked. */
@Composable
private fun Calendar(snapshot: ScoringSnapshot, type: ScoreType, selected: Int?, onPick: (Int) -> Unit) {
    val byDate = snapshot.history.associateBy { it.date }
    val first = snapshot.history.firstOrNull()?.date ?: snapshot.date
    val s = PawseTheme.semantics
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    var month = snapshot.date.withDayOfMonth(1)
    val months = buildList {
        while (!month.isBefore(first.withDayOfMonth(1)) && size < 4) {
            add(month)
            month = month.minusMonths(1)
        }
    }
    months.forEach { m ->
        Text(
            m.format(DateTimeFormatter.ofPattern(tr("MMMM yyyy", "yyyy년 M월"), Locale.getDefault())),
            style = PawseType.BodyStrong,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )
        val lead = m.dayOfWeek.value % 7
        val days = (1..m.lengthOfMonth()).map { m.withDayOfMonth(it) }
        val cells: List<LocalDate?> = List(lead) { null } + days
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                (0 until 7).forEach { i ->
                    val d = week.getOrNull(i)
                    val report = d?.let { if (it == snapshot.date) snapshot.today else byDate[it] }
                    val back = d?.let { java.time.temporal.ChronoUnit.DAYS.between(it, snapshot.date).toInt() }
                    val ok = report != null && back != null && back >= 0
                    Column(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                            .background(if (back != null && back == selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent, RoundedCornerShape(10.dp))
                            .pressable(enabled = ok) { back?.let(onPick) }
                            .clearAndSetSemantics {
                                contentDescription = d?.let { shortDate(it) + if (ok) "" else tr(", no reading", ", 기록 없음") }.orEmpty()
                            }
                            .padding(top = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (d != null) {
                            Text("${d.dayOfMonth}", style = PawseType.Label, color = if (ok) MaterialTheme.colorScheme.onSurface else s.faint)
                            if (ok) {
                                val body = report?.body
                                Mark(
                                    color = when (type) {
                                        ScoreType.BODY -> body?.let { bodyColor(it.band, report.illness.level) }
                                        else -> grey
                                    },
                                    hollow = type == ScoreType.BODY && body == null,
                                    diameter = 6.dp,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
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
