package app.pawse.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pawse.core.data.body.BodyReading
import app.pawse.core.data.body.BodySnapshot
import app.pawse.core.data.body.InBodyExtra
import app.pawse.core.ui.component.Chip
import app.pawse.core.ui.component.GhostButton
import app.pawse.core.ui.component.Glyph
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.PawseCard
import app.pawse.core.ui.component.PawseField
import app.pawse.core.ui.component.PawseSheet
import app.pawse.core.ui.component.PrimaryButton
import app.pawse.core.ui.component.RangeTrack
import app.pawse.core.ui.component.Segmented
import app.pawse.core.ui.component.SplitRow
import app.pawse.core.ui.component.StatusBarScrim
import app.pawse.core.ui.component.aura
import app.pawse.core.ui.component.pressable
import app.pawse.core.ui.theme.PawseFontFamily
import app.pawse.core.ui.theme.PawseTheme
import app.pawse.core.ui.theme.PawseType
import app.pawse.scoring.body.BodyUsual
import app.pawse.scoring.body.GoalEstimate
import app.pawse.scoring.body.TrendPoint
import app.pawse.scoring.body.WeighInPoint
import app.pawse.scoring.body.Weight
import app.pawse.scoring.config.ScoringConfig
import app.pawse.scoring.fitness.CardioFitness
import app.pawse.scoring.model.Metric
import app.pawse.scoring.tr
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Body: the trend weight as the headline, with the number, the sentence, the
 * chart and the goal drawn from one array so they cannot disagree. Then
 * composition, each figure against your usual, and fitness age.
 *
 * Every change stays grey. Up or down is not good or bad on its own, so the screen
 * says which way and by how much, and leaves the verdict to the person.
 */
@Composable
fun BodyScreen(
    onAddWeighIn: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BodyViewModel = hiltViewModel(),
) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val range by viewModel.range.collectAsStateWithLifecycle()
    val inBody by viewModel.inBody.collectAsStateWithLifecycle()
    var editingGoal by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.reload() }

    Scaffold(modifier = modifier, containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().aura(),
            contentPadding = PaddingValues(
                start = 24.dp,
                end = 24.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 140.dp,
            ),
        ) {
            item {
                Row(Modifier.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Body", "신체"), style = PawseType.Title, modifier = Modifier.weight(1f))
                    Row(
                        Modifier
                            .height(48.dp)
                            .pressable(onClick = onAddWeighIn)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(24.dp))
                            .clearAndSetSemantics {
                                contentDescription = tr("Weigh-in", "체중 기록")
                                role = Role.Button
                            }
                            .padding(start = 14.dp, end = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Glyph(Glyphs.Plus, size = 18.dp, strokeWidth = 2f, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.width(6.dp))
                        Text(tr("Weigh-in", "체중 기록"), style = PawseType.BodyStrong)
                    }
                }
                Text(
                    snapshot?.let { statusLine(it) } ?: tr("Reading your scale", "체중계 기록을 읽는 중"),
                    style = PawseType.Micro,
                    color = PawseTheme.semantics.faint,
                )
            }

            val s = snapshot ?: return@LazyColumn
            if (!s.hasAnyData) {
                item {
                    PawseCard(modifier = Modifier.padding(top = 24.dp)) {
                        Text(tr("No scale readings yet", "아직 체중계 기록이 없어요"), style = PawseType.Heading)
                        Text(
                            tr(
                                "When a scale writes weight to Health Connect, it shows here on its own. No scale that does? Add a weigh-in.",
                                "체중계가 헬스 커넥트에 체중을 기록하면 여기에 저절로 나타나요. 그런 체중계가 없다면 직접 기록하세요.",
                            ),
                            style = PawseType.Label,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }

            val points = weighIns(s)
            val trend = Weight.trend(points, ScoringConfig.DEFAULT.weight)
            if (trend.isNotEmpty()) {
                item { TrendSection(s, trend, points, range, viewModel::setRange, onGoal = { editingGoal = true }) }
            }
            item { Composition(s, range.days, inBody) }
            item { AgainstUsual(s) }
            item { FitnessAgeCard(s) }
        }
        StatusBarScrim()
        }
    }

    if (editingGoal) {
        GoalSheet(
            current = snapshot?.goalKg,
            onDismiss = { editingGoal = false },
            onSave = {
                editingGoal = false
                viewModel.setGoal(it)
            },
        )
    }
}

/** The scale reading appears once, here; the headline is the trend. */
private fun statusLine(s: BodySnapshot): String {
    val weight = s.weight ?: return tr("No weigh-ins yet", "아직 체중 기록이 없어요")
    val kg = oneDecimal(weight.value)
    val days = s.daysSinceWeighIn ?: 0L
    return when (days) {
        0L -> tr("Weighed today ${ScoreCopy.clock(weight.atEpochMs)} · $kg kg on the scale", "오늘 ${ScoreCopy.clock(weight.atEpochMs)} 측정 · 체중계 $kg kg")
        1L -> tr("Weighed yesterday · $kg kg on the scale", "어제 측정 · 체중계 $kg kg")
        else -> tr("Last weighed $days days ago · $kg kg on the scale", "${days}일 전 측정 · 체중계 $kg kg")
    }
}

internal fun weighIns(s: BodySnapshot): List<WeighInPoint> {
    val zone = ZoneId.systemDefault()
    return s.series[Metric.BODY_WEIGHT].orEmpty().map {
        val t = java.time.Instant.ofEpochMilli(it.atEpochMs).atZone(zone)
        WeighInPoint(it.date.toEpochDay(), it.value, t.hour * 60.0 + t.minute)
    }
}

@Composable
private fun TrendSection(
    s: BodySnapshot,
    trend: List<TrendPoint>,
    points: List<WeighInPoint>,
    range: BodyRange,
    onRange: (BodyRange) -> Unit,
    onGoal: () -> Unit,
) {
    val config = ScoringConfig.DEFAULT.weight
    val today = s.today.toEpochDay()
    val latest = trend.last()
    val before = trend.lastOrNull { it.day <= today - 28 }
    Column(Modifier.padding(top = 24.dp)) {
        Text(tr("Trend weight", "추세 체중"), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(oneDecimal(latest.trend), style = PawseType.Hero)
            Text("kg", style = PawseType.Heading.copy(fontWeight = PawseType.Body.fontWeight), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, bottom = 10.dp))
        }
        Text(
            when {
                before == null -> tr("Pawse needs a few weeks of weigh-ins for a trend.", "추세를 보려면 몇 주의 기록이 필요해요.")
                Weight.aboutSame(latest.trend - before.trend, points) -> tr("About the same as 4 weeks ago", "4주 전과 거의 같아요")
                latest.trend > before.trend -> tr("Up ${oneDecimal(latest.trend - before.trend)} kg in 4 weeks", "4주 동안 ${oneDecimal(latest.trend - before.trend)} kg 늘었어요")
                else -> tr("Down ${oneDecimal(before.trend - latest.trend)} kg in 4 weeks", "4주 동안 ${oneDecimal(before.trend - latest.trend)} kg 줄었어요")
            },
            style = PawseType.BodyStrong,
        )
        Segmented(
            options = BodyRange.entries,
            selected = range,
            onSelect = onRange,
            label = { it.label },
            modifier = Modifier.padding(top = 16.dp),
        )
        WeightChart(trend, s.today, range, s.goalKg, Modifier.padding(top = 16.dp))
        Text(
            tr("Dots are scale readings, the line is the trend. It lags a little behind the scale.", "점은 체중계 측정값, 선은 추세예요. 추세는 측정값보다 조금 늦게 따라가요."),
            style = PawseType.Micro,
            color = PawseTheme.semantics.faint,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (latest.likelyWater) {
            Text(tr("The last reading looks like water and counts at half weight.", "마지막 측정값은 수분 변화로 보여 절반만 반영했어요."), style = PawseType.Micro, color = PawseTheme.semantics.faint)
        }
        val goal = s.goalKg
        Row(
            Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .pressable(onClick = onGoal)
                .semantics(mergeDescendants = true) { role = Role.Button },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (goal == null) tr("Set a goal weight", "목표 체중 정하기") else goalLine(goal, Weight.goal(trend, goal, today, config)),
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Glyph(Glyphs.ChevronRight, size = 16.dp, color = PawseTheme.semantics.faint)
        }
    }
}

private fun goalLine(goal: Double, e: GoalEstimate): String {
    val g = tr("Goal ${oneDecimal(goal)} kg", "목표 ${oneDecimal(goal)} kg")
    return g + " · " + when (e) {
        is GoalEstimate.Months -> when {
            e.high == null -> tr("at least ${e.low} months at this pace", "이 속도면 최소 ${e.low}개월")
            e.low == e.high -> tr("about ${e.months} months", "약 ${e.months}개월")
            else -> tr("about ${e.low}–${e.high} months", "약 ${e.low}–${e.high}개월")
        }
        GoalEstimate.TooEarly -> tr("an estimate comes after 3 weeks and 8 weigh-ins", "3주와 8번의 기록 뒤에 예상이 나와요")
        GoalEstimate.NoClearTrend -> tr("No clear trend toward your goal yet.", "아직 목표 쪽으로 뚜렷한 추세가 없어요.")
        GoalEstimate.TooFast -> tr(
            "This is faster than common guidance; a doctor or dietitian can help set a pace.",
            "일반적인 권장보다 빠른 속도예요. 의사나 영양사와 속도를 정해 보세요.",
        )
    }
}

/** Raw weigh-ins as pale dots, the trend as the line, the goal dashed. One array draws all three. */
@Composable
private fun WeightChart(trend: List<TrendPoint>, today: LocalDate, range: BodyRange, goal: Double?, modifier: Modifier = Modifier) {
    val first = today.minusDays(range.days).toEpochDay()
    val inRange = trend.filter { it.day >= first }
    // The line enters at the left edge where the trend stood on the range's first day,
    // so a range holding one weigh-in, or none, still draws its trend.
    val edge = trend.lastOrNull { it.day < first }?.let { l ->
        val next = inRange.firstOrNull()
        val v = if (next == null) l.trend else l.trend + (next.trend - l.trend) * (first - l.day) / (next.day - l.day).toDouble()
        TrendPoint(first, v, v, false)
    }
    val shown = listOfNotNull(edge) + inRange + listOfNotNull(edge?.takeIf { inRange.isEmpty() }?.copy(day = today.toEpochDay()))
    if (shown.isEmpty()) return
    val s = PawseTheme.semantics
    val on = MaterialTheme.colorScheme.onSurface
    val bg = MaterialTheme.colorScheme.background
    val hair = MaterialTheme.colorScheme.outlineVariant
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val label = TextStyle(fontFamily = PawseFontFamily, fontSize = 12.sp, color = s.faint)
    val values = inRange.map { it.kg } + shown.map { it.trend } + listOfNotNull(goal?.takeIf { g -> abs(g - shown.last().trend) < 6.0 })
    val lo = values.min() - 0.4
    val hi = values.max() + 0.4
    val start = LocalDate.ofEpochDay(first).format(DateTimeFormatter.ofPattern(tr("d MMM", "M월 d일"), Locale.getDefault()))
    Canvas(
        modifier
            .fillMaxWidth()
            .height(168.dp)
            .semantics {
                contentDescription = tr(
                    "Trend weight over ${range.spoken}, ending at ${oneDecimal(shown.last().trend)} kg.",
                    "최근 ${range.spoken} 추세 체중, 마지막 ${oneDecimal(shown.last().trend)} kg.",
                )
            },
    ) {
        val chartH = 140.dp.toPx()
        fun x(day: Long) = (day - first).toFloat() / range.days * size.width
        fun y(v: Double) = (chartH - 4.dp.toPx() - (v - lo) / (hi - lo) * (chartH - 8.dp.toPx())).toFloat()
        // Three hairlines with their kg, then the goal, the dots and the line.
        (0..2).forEach { i ->
            val v = lo + (hi - lo) * (0.2 + 0.3 * i)
            val yy = y(v)
            drawLine(hair, Offset(0f, yy), Offset(size.width, yy), 1.dp.toPx())
            drawText(measurer.measure(oneDecimal(v), label), topLeft = Offset(0f, yy - 16.dp.toPx()))
        }
        goal?.takeIf { it in lo..hi }?.let {
            drawLine(grey.copy(alpha = 0.7f), Offset(0f, y(it)), Offset(size.width, y(it)), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 5.dp.toPx())))
        }
        inRange.forEach { drawCircle(s.mark, 2.5.dp.toPx(), Offset(x(it.day), y(it.kg))) }
        if (shown.size > 1) {
            val path = Path()
            shown.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.day), y(p.trend)) else path.lineTo(x(p.day), y(p.trend)) }
            drawPath(path, on, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        val end = Offset(x(shown.last().day), y(shown.last().trend))
        drawCircle(bg, 7.dp.toPx(), end)
        drawCircle(on, 5.dp.toPx(), end)
        drawText(measurer.measure(start, label), topLeft = Offset(0f, chartH + 8.dp.toPx()))
        val t = measurer.measure(tr("today", "오늘"), label)
        drawText(t, topLeft = Offset(size.width - t.size.width, chartH + 8.dp.toPx()))
    }
}

/** Fat and lean share the chart's weeks, as % or kg, each with its slow trend. */
@Composable
private fun Composition(s: BodySnapshot, days: Long, inBody: List<InBodyExtra>) {
    val fat = s.series[Metric.BODY_FAT].orEmpty()
    val lean = s.series[Metric.LEAN_MASS].orEmpty()
    val first = s.today.minusDays(days)
    // Kept in Pawse only, from InBody sheets: Health Connect has no record for either.
    val extras = inBody.filter { !it.date.isBefore(first) }
    if (fat.isEmpty() && lean.isEmpty() && extras.isEmpty()) return
    var inKg by rememberSaveable { mutableStateOf(false) }
    val weights = s.series[Metric.BODY_WEIGHT].orEmpty().associateBy { it.date }
    // Fat is written as a percent and lean as kilograms; the other unit needs that day's weight.
    fun fatShown(r: BodyReading): Double? = if (inKg) weights[r.date]?.let { r.value / 100.0 * it.value } else r.value
    fun leanShown(r: BodyReading): Double? = if (inKg) r.value else weights[r.date]?.let { r.value / it.value * 100.0 }
    val unit = if (inKg) " kg" else "%"
    PawseCard(modifier = Modifier.padding(top = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Composition", "체성분"), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Segmented(listOf(false, true), inKg, onSelect = { inKg = it }, label = { if (it) "kg" else "%" }, height = 36.dp, modifier = Modifier.width(112.dp))
        }
        CompositionRow(tr("Body fat", "체지방"), fat.filter { !it.date.isBefore(first) }.mapNotNull { r -> fatShown(r)?.let { r.date to it } }, unit)
        CompositionRow(tr("Lean mass", "제지방량"), lean.filter { !it.date.isBefore(first) }.mapNotNull { r -> leanShown(r)?.let { r.date to it } }, unit)
        CompositionRow(tr("Skeletal muscle", "골격근량"), extras.mapNotNull { e -> e.skeletalMuscleKg?.let { e.date to it } }, " kg")
        CompositionRow(tr("Visceral fat level", "내장지방 레벨"), extras.mapNotNull { e -> e.visceralFat?.let { e.date to it.toDouble() } }, "")
        val source = (fat.lastOrNull() ?: lean.lastOrNull())?.sourceApp?.let { writer(it) }.orEmpty()
        Text(
            listOf(tr("Same ${days / 7} weeks as the weight chart", "체중 차트와 같은 ${days / 7}주"), source).filter { it.isNotEmpty() }.joinToString(" · "),
            style = PawseType.Micro,
            color = PawseTheme.semantics.faint,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun CompositionRow(name: String, values: List<Pair<LocalDate, Double>>, unit: String) {
    if (values.isEmpty()) return
    val config = ScoringConfig.DEFAULT.weight
    val points = values.map { WeighInPoint(it.first.toEpochDay(), it.second) }
    val slow = Weight.slowTrend(points, config)
    val change = slow.last().trend - slow.first().trend
    val since = values.first().first.format(DateTimeFormatter.ofPattern(tr("d MMM", "M월 d일"), Locale.getDefault()))
    SplitRow(Modifier.padding(top = 16.dp)) {
        Text(name, style = PawseType.Body, modifier = Modifier.weight(1f))
        // A visceral fat level is a whole number with no unit.
        Text(if (unit.isEmpty()) values.last().second.roundToInt().toString() else oneDecimal(values.last().second) + unit, style = PawseType.MetricValue)
        Text(
            if (slow.size < 2 || Weight.aboutSame(change, points)) tr("about the same", "거의 같음") else "${if (change > 0) "+" else "−"}${oneDecimal(abs(change))} " + tr("since $since", "$since 이후"),
            style = PawseType.Micro,
            color = PawseTheme.semantics.faint,
        )
    }
    if (slow.size > 1) {
        val grey = MaterialTheme.colorScheme.onSurfaceVariant
        Canvas(Modifier.padding(top = 4.dp).fillMaxWidth().height(28.dp)) {
            val lo = slow.minOf { it.trend }
            val span = (slow.maxOf { it.trend } - lo).takeIf { it > 1e-9 } ?: 1.0
            val d0 = slow.first().day
            val dn = (slow.last().day - d0).coerceAtLeast(1)
            val path = Path()
            slow.forEachIndexed { i, p ->
                val x = (p.day - d0).toFloat() / dn * size.width
                val y = (size.height - 4.dp.toPx() - (p.trend - lo) / span * (size.height - 8.dp.toPx())).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, grey, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

private val USUAL_METRICS = listOf(Metric.BODY_FAT, Metric.LEAN_MASS, Metric.BODY_WATER, Metric.BONE_MASS, Metric.BASAL_METABOLIC_RATE)

/**
 * Each figure against your usual: median ± 1.4826 × MAD over the last 60 days from
 * the device that wrote the latest reading, at least 4 readings. Grey throughout.
 */
@Composable
private fun AgainstUsual(s: BodySnapshot) {
    val config = ScoringConfig.DEFAULT.weight
    val today = s.today.toEpochDay()
    val rows = USUAL_METRICS.mapNotNull { metric ->
        val readings = s.series[metric].orEmpty()
        val latest = readings.lastOrNull() ?: return@mapNotNull null
        // InBody and a smart scale never share a usual: one device's readings only.
        val own = readings.filter { it.sourceApp == latest.sourceApp }.map { WeighInPoint(it.date.toEpochDay(), it.value) }
        val usual = Weight.usual(own.dropLast(1), today, config) ?: return@mapNotNull null
        Triple(metric, latest, usual)
    }
    if (rows.isEmpty()) return
    Column(Modifier.padding(top = 32.dp)) {
        Text(
            tr("Against your usual · ${rows.maxOf { it.third.readings }} readings", "평소와 비교 · ${rows.maxOf { it.third.readings }}회 측정"),
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        rows.forEach { (metric, latest, usual) -> UsualRow(metric, latest, usual) }
        Text(
            tr("Body changes stay grey. Up or down isn’t good or bad on its own.", "신체 변화는 회색으로 보여줘요. 오르내림 자체는 좋고 나쁨이 아니에요."),
            style = PawseType.Micro,
            color = PawseTheme.semantics.faint,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun UsualRow(metric: Metric, latest: BodyReading, usual: BodyUsual) {
    val spread = usual.spread.takeIf { it > 1e-9 } ?: 1e-9
    val z = ((latest.value - usual.median) / spread).coerceIn(-3.0, 3.0)
    Column(Modifier.padding(vertical = 8.dp)) {
        SplitRow {
            Text(ScoreCopy.metricLabel(metric), style = PawseType.BodyStrong, modifier = Modifier.weight(1f))
            Text(ScoreCopy.formatRaw(metric, latest.value), style = PawseType.MetricValue)
        }
        RangeTrack(
            band = (1f / 3f)..(2f / 3f),
            marker = ((z + 3.0) / 6.0).toFloat(),
            markerColor = PawseTheme.semantics.neutral,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
        Text(
            tr("Usually", "평소") + " ${ScoreCopy.formatRaw(metric, usual.median)} ± ${ScoreCopy.formatRaw(metric, usual.spread).substringBefore(' ').removeSuffix("%")}",
            style = PawseType.Micro,
            color = PawseTheme.semantics.faint,
        )
    }
}

/**
 * Fitness age, marked temporary: the age at which a typical person of your sex has
 * your VO₂max, from FRIEND's reference curve, as a range. Body only, never Scores.
 */
@Composable
private fun FitnessAgeCard(s: BodySnapshot) {
    val config = ScoringConfig.DEFAULT.fitness
    val age = CardioFitness.age(s.vo2, s.sex, config)
    PawseCard(modifier = Modifier.padding(top = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Fitness age", "체력 나이"), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Chip(tr("Temporary", "임시"))
        }
        if (age == null) {
            Text(
                tr(
                    "Needs two VO₂max readings from your watch, at least 14 days apart.",
                    "워치의 VO₂max 기록이 14일 이상 간격으로 두 번 필요해요.",
                ),
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${age.age}", style = PawseType.Numeral)
                Text(tr("years", "세"), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            }
            val date = LocalDate.ofEpochDay(age.vo2max.lastDay).format(DateTimeFormatter.ofPattern(tr("d MMM", "M월 d일"), Locale.getDefault()))
            Text(
                listOfNotNull(
                    s.ageYears?.let { tr("You’re $it", "실제 나이 $it") },
                    tr("likely ${age.low}–${age.high}", "범위 ${age.low}–${age.high}"),
                    tr("from VO₂max ${age.vo2max.value.roundToInt()}, ${writer(age.vo2max.source)}, $date", "VO₂max ${age.vo2max.value.roundToInt()} 기준, ${writer(age.vo2max.source)}, $date"),
                ).joinToString(" · "),
                style = PawseType.Micro,
                color = PawseTheme.semantics.faint,
            )
        }
        Text(
            tr(
                "A placeholder while its own model is built. Only VO₂max feeds it, and it never reaches Scores.",
                "자체 모델을 만드는 동안 쓰는 임시 값이에요. VO₂max만 반영하고 점수 화면에는 나오지 않아요.",
            ),
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** Sets or clears the goal weight. Optional; nothing on Body depends on it. */
@Composable
private fun GoalSheet(current: Double?, onDismiss: () -> Unit, onSave: (Double?) -> Unit) {
    var text by remember { mutableStateOf(current?.let(::oneDecimal).orEmpty()) }
    val kg = text.replace(',', '.').toDoubleOrNull()?.takeIf { it in 30.0..250.0 }
    PawseSheet(onDismiss = onDismiss) {
        Text(tr("Goal weight", "목표 체중"), style = PawseType.Heading)
        Text(
            tr("Shown as a dashed line and an estimate in whole months. Pawse never turns it into a streak.", "점선과 개월 단위 예상으로만 보여요. 연속 기록으로 바꾸지 않아요."),
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        PawseField(value = text, onValueChange = { text = it }, suffix = "kg", numeric = true, placeholder = "70.0")
        PrimaryButton(tr("Save goal", "목표 저장"), onClick = { onSave(kg) }, enabled = kg != null, modifier = Modifier.padding(top = 24.dp))
        if (current != null) GhostButton(tr("Remove goal", "목표 지우기"), onClick = { onSave(null) }, modifier = Modifier.padding(top = 8.dp))
    }
}

private fun oneDecimal(value: Double) = String.format(Locale.getDefault(), "%.1f", value)
