package app.pawse.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import app.pawse.core.ui.component.BandDot
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pawse.core.data.scoring.DailyInputAssembler
import app.pawse.core.data.scoring.ScoringSnapshot
import app.pawse.core.ui.component.EaseOut
import app.pawse.core.ui.component.Glyph
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.KeySwatch
import app.pawse.core.ui.component.PawseCard
import app.pawse.core.ui.component.RevealEnter
import app.pawse.core.ui.component.RevealExit
import app.pawse.core.ui.component.Segmented
import app.pawse.core.ui.component.SplitBar
import app.pawse.core.ui.component.UsualRangeKey
import app.pawse.core.ui.component.cardSurface
import app.pawse.core.ui.theme.PawseTheme
import app.pawse.core.ui.theme.PawseType
import app.pawse.scoring.config.ScoringConfig
import app.pawse.scoring.load.LoadPoints
import app.pawse.scoring.model.BodyScore
import app.pawse.scoring.model.DayReport
import app.pawse.scoring.model.LoadReading
import app.pawse.scoring.model.ScoreType
import app.pawse.scoring.model.SessionLoad
import app.pawse.scoring.model.SignalDetail
import androidx.compose.ui.platform.LocalContext
import app.pawse.scoring.tr
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/** The Over time chart's ranges, in days. */
private val DAY_RANGES = listOf(7, 30, 90)

/**
 * Body or Load for the day the date strip or the chart picked: the day's figure
 * and its parts, the days around it, and each signal over the same days.
 *
 * Every figure is the engine's, or the night's as stored. The usual bands behind
 * the night signals are display statistics over stored nights (LastNight's
 * [usual]), never fed back into a score.
 */
@Composable
internal fun DayTab(
    snapshot: ScoringSnapshot,
    type: ScoreType,
    back: Int,
    onPick: (Int) -> Unit,
    range: Int,
    onRange: (Int) -> Unit,
    open: Int,
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    // A day picked further back than the range (from a Sleep trend) widens it to fit.
    val span = if (back < range) range else DAY_RANGES.firstOrNull { it > back } ?: DAY_RANGES.last()
    val rows = snapshot.rows(span)
    val selI = (span - 1 - back).coerceAtLeast(0)
    val picked = rows[selI]
    val today = picked.date == LocalDate.now()
    val pick: (Int) -> Unit = { i -> onPick(span - 1 - i) }

    val pm = LocalContext.current.packageManager
    val lines = if (type == ScoreType.BODY) bodyLines(snapshot, rows, selI) { appName(pm, it) } else loadLines(rows, selI) { appName(pm, it) }
    @Composable
    fun signalRows(title: String, aside: String) {
        Row(Modifier.padding(top = 28.dp, bottom = 2.dp)) {
            Text(title, style = PawseType.Label, color = grey, modifier = Modifier.weight(1f))
            Text(aside, style = PawseType.Micro, color = PawseTheme.semantics.faint)
        }
        lines.forEachIndexed { i, line ->
            if (type == ScoreType.BODY) {
                NormalRow(line, selI, expanded = open == i, onToggle = { onOpen(if (open == i) -1 else i) })
            } else {
                SignalRow(line, selI, expanded = open == i, onToggle = { onOpen(if (open == i) -1 else i) })
            }
        }
    }

    Column(modifier) {
        DayFigure(type, picked.day, today)
        // Body: Against your normal sits under What moved it, before Over time, as on the canvas.
        if (type == ScoreType.BODY) signalRows(tr("Against your normal", "내 평소와 비교"), tr("track ±3σ · band ±1σ", "트랙 ±3σ · 음영 ±1σ"))

        Row(Modifier.padding(top = 32.dp).fillMaxWidth()) {
            Text(tr("Over time", "기간별 추이"), style = PawseType.Heading, modifier = Modifier.weight(1f).alignByBaseline())
            Text(tr("Touch a day to read it", "날짜를 눌러 읽어요"), style = PawseType.Label, color = grey, modifier = Modifier.alignByBaseline())
        }
        Segmented(
            options = DAY_RANGES,
            selected = span,
            onSelect = onRange,
            label = { tr("$it days", "${it}일") },
            modifier = Modifier.padding(top = 12.dp),
        )
        if (type == ScoreType.BODY) {
            TrendChart(NightFigure.BODY, rows, selI, usual = null, wakeUsual = null, need = 0.0, onPick = pick)
        } else {
            LoadChart(rows, selI, pick)
        }
        Row(Modifier.padding(top = 8.dp, end = 36.dp).fillMaxWidth()) {
            Text(shortDate(rows.first().date), style = PawseType.Label, color = grey, modifier = Modifier.weight(1f))
            Text(if (snapshot.date == LocalDate.now()) tr("Today", "오늘") else shortDate(snapshot.date), style = PawseType.Label, color = grey)
        }
        Legend(type)

        if (type != ScoreType.BODY) signalRows(tr("Load, fitness and form, same days", "같은 날들의 부하·체력·폼"), shortDate(picked.date))
    }
}

// --- The picked day ------------------------------------------------------------

/** The day's figure, its word, and the columns that make it up. */
@Composable
private fun DayFigure(type: ScoreType, day: DayReport?, today: Boolean) {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val body = day?.body
    val load = day?.load
    if (type == ScoreType.BODY) {
        // The design's What moved it: each signal's pull in SD, then the sum and the shares.
        if (body != null) WhatMoved(body) else {
            day?.let { ScoreCopy.bodyStatus(it.bodyStatus, it.validNights, ScoringConfig.DEFAULT.body.minValidNights) }?.takeIf { it.isNotEmpty() }?.let {
                // States board, first week: a "Why no score" card instead of an empty breakdown.
                PawseCard(Modifier.padding(top = 32.dp)) {
                    Text(tr("Why no score", "점수가 없는 이유"), style = PawseType.BodyStrong)
                    Text(it, style = PawseType.Label, color = grey, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        return
    }
    if (load == null) {
        Text(tr("No reading for this day.", "이날은 기록이 없어요."), style = PawseType.Label, color = grey, modifier = Modifier.padding(top = 16.dp))
        return
    }
    // The number, its word and where it sits in the range are the page's hero; this keeps what makes it up.
    LoadColumns(load, today)
    Sessions(load, today)
}

/**
 * TRIMP on the ring's scale (your typical active day is 10), so the Load page speaks
 * one unit: the card, the chart and the rows all agree with the ring.
 */
private fun LoadReading.pts(trimp: Double): Double =
    if (pointsReference > 0.0) LoadPoints.points(trimp, pointsReference, ScoringConfig.DEFAULT.points) else trimp

/** Sessions: each workout, its load, and how it was counted. */
@Composable
private fun Sessions(load: LoadReading, today: Boolean) {
    val faint = PawseTheme.semantics.faint
    val pm = LocalContext.current.packageManager
    Column(Modifier.padding(top = 32.dp)) {
        Text(tr("Sessions", "활동"), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (load.sessions.isEmpty()) {
            Text(
                if (today) tr("No workouts logged yet today.", "오늘은 아직 기록된 운동이 없어요.") else tr("A rest day.", "쉬는 날이에요."),
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        load.sessions.forEach { s ->
            Column(Modifier.padding(vertical = 8.dp)) {
                Row {
                    Text(s.title ?: tr("Workout", "운동"), style = PawseType.BodyStrong, modifier = Modifier.weight(1f))
                    Text(s.skippedReason ?: tr("${ScoreCopy.load(load.pts(s.load))} load", "${ScoreCopy.load(load.pts(s.load))} 부하"), style = PawseType.MetricValue)
                }
                Text(
                    listOfNotNull(
                        minutesText(s.durationMinutes),
                        ScoreCopy.provenanceLabel(s.provenance).takeIf { s.skippedReason == null },
                        s.source.takeIf { it.isNotBlank() }?.let { appName(pm, it) },
                    ).joinToString(" · "),
                    style = PawseType.Micro,
                    color = faint,
                )
            }
        }
        Text(
            tr("Counted by heart rate (Banister TRIMP), or steps when there is no heart rate.", "심박수(Banister TRIMP)로 계산하고, 심박수가 없으면 걸음 수를 써요."),
            style = PawseType.Micro,
            color = faint,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * What moved it: HRV and sleeping heart rate as bars from your normal (the zero
 * line), in SD, signed so each points the way it pushed Body; then the combined z
 * and the score it gave, and the two signals' shares.
 */
@Composable
private fun WhatMoved(body: BodyScore) {
    val scheme = MaterialTheme.colorScheme
    val s = PawseTheme.semantics
    val faint = s.faint
    val w = body.hrvWeight
    Column(Modifier.padding(top = 32.dp)) {
        Row {
            Text(tr("What moved it", "움직인 것"), style = PawseType.Label, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(tr("SD from your normal", "평소에서 떨어진 정도(SD)"), style = PawseType.Micro, color = faint)
        }
        body.hrv?.let { h ->
            MovedRow(
                tr("HRV ${h.tonight?.roundToInt() ?: "—"} ms", "HRV ${h.tonight?.roundToInt() ?: "—"} ms"),
                h.z,
                tr("Higher is better", "높을수록 좋아요"),
            )
        }
        body.sleepingHr?.let { r ->
            MovedRow(
                tr("Sleeping HR ${r.tonight?.roundToInt() ?: "—"} bpm", "수면 중 심박수 ${r.tonight?.roundToInt() ?: "—"} bpm"),
                r.z,
                tr("Lower is better, so the bar points the way it pushes Body", "낮을수록 좋아서 막대는 컨디션을 미는 방향을 가리켜요"),
            )
        }
        HorizontalDivider(color = scheme.outlineVariant, modifier = Modifier.padding(top = 12.dp))
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Combined z ${signed(body.z, 2)} →", "합친 z ${signed(body.z, 2)} →"), style = PawseType.Label, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(body.score.toString(), style = PawseType.MetricValue)
        }
        Text(tr("Your normal reads 50 · Typical is 31–69", "내 평소는 50 · 평소 수준은 31–69"), style = PawseType.Micro, color = faint, modifier = Modifier.padding(top = 4.dp))
        SplitBar(listOf(w.toFloat() to scheme.onSurfaceVariant, (1 - w).toFloat() to s.mark), Modifier.padding(top = 12.dp))
        Row(Modifier.padding(top = 8.dp)) {
            BandDot(color = scheme.onSurfaceVariant, label = tr("HRV, ${pct(w)} of Body", "HRV, 컨디션의 ${pct(w)}"), modifier = Modifier.weight(1f))
            BandDot(color = s.mark, label = tr("Sleeping HR, ${pct(1 - w)}", "수면 중 심박수, ${pct(1 - w)}"))
        }
    }
}

/** One signal's pull: a bar from the centre line, a third of the track per SD, at most 3 SD. */
@Composable
private fun MovedRow(name: String, z: Double, note: String) {
    val s = PawseTheme.semantics
    val track = s.baselineOuter
    val stroke = MaterialTheme.colorScheme.outline
    val color = when {
        z > 0.5 -> s.bandHigh
        z < -0.5 -> s.bandModerate
        else -> s.neutral
    }
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(400, easing = EaseOut)) }
    Column(Modifier.padding(top = 8.dp).padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = PawseType.BodyStrong, modifier = Modifier.weight(1f))
            Text("${signed(z, 2)} SD", style = PawseType.MetricValue)
        }
        Canvas(Modifier.padding(top = 4.dp).fillMaxWidth().height(14.dp).semantics { contentDescription = "$name, ${signed(z, 2)} SD" }) {
            val y = 4.dp.toPx()
            val h = 6.dp.toPx()
            val mid = size.width / 2f
            drawRoundRect(track, Offset(0f, y), Size(size.width, h), CornerRadius(3.dp.toPx()))
            val len = (minOf(3.0, abs(z)) / 3.0 * mid).toFloat() * grow.value
            drawRoundRect(color, Offset(if (z >= 0) mid else mid - len, y), Size(len, h), CornerRadius(3.dp.toPx()))
            drawRoundRect(stroke, Offset(mid - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height), CornerRadius(1.dp.toPx()))
        }
        Text(note, style = PawseType.Micro, color = s.faint, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Load, fitness and fatigue, and where the day's load sits in its range. */
@Composable
private fun LoadColumns(load: LoadReading, today: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val s = PawseTheme.semantics
    val grey = scheme.onSurfaceVariant
    // The ring's own range, in points: the card never shows a second scale.
    val range = load.pointsRange
    val low = range?.low?.toDouble()
    val high = range?.high?.toDouble()
    val points = load.points
    val faint = s.faint
    // The design's Load card: three light figures, the range bar, its ends, and why it stays grey.
    Column(Modifier.padding(top = 32.dp).fillMaxWidth().cardSurface().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                ScoreCopy.load(points) to tr("Load", "부하"),
                (load.fitness?.let { ScoreCopy.load(load.pts(it)) } ?: "—") to tr("Fitness", "체력"),
                (load.fatigue?.let { ScoreCopy.load(load.pts(it)) } ?: "—") to tr("Fatigue", "피로"),
            ).forEach { (v, k) ->
                Column(Modifier.weight(1f)) {
                    Text(v, style = PawseType.Numeral.copy(fontSize = 28.sp, lineHeight = 34.sp), maxLines = 1)
                    Text(k, style = PawseType.Label, color = grey)
                }
            }
        }
        val ranged = low != null && high != null
        val top = maxOf((high ?: 0.0) * 1.25, points, 1.0)
        val whose = if (today) tr("today’s", "오늘") else tr("that day’s", "그날")
        val label = if (ranged) {
            tr(
                "Load ${ScoreCopy.load(points)} against $whose range of ${ScoreCopy.load(low!!)} to ${ScoreCopy.load(high!!)}.",
                "부하 ${ScoreCopy.load(points)}, $whose 범위는 ${ScoreCopy.load(low!!)}~${ScoreCopy.load(high!!)}예요.",
            )
        } else {
            tr("Load ${ScoreCopy.load(points)}.", "부하 ${ScoreCopy.load(points)}.")
        }
        Canvas(Modifier.padding(top = 16.dp).fillMaxWidth().height(14.dp).semantics { contentDescription = label }) {
            fun x(v: Double) = (v / top).toFloat().coerceIn(0f, 1f) * size.width
            val r = CornerRadius(7.dp.toPx())
            drawRoundRect(s.baselineOuter, cornerRadius = r)
            if (ranged) drawRoundRect(s.baselineBand, Offset(x(low!!), 0f), Size(x(high!!) - x(low), size.height), r)
            drawRoundRect(grey.copy(alpha = 0.9f), Offset.Zero, Size(x(points), size.height), r)
            if (ranged) drawRoundRect(scheme.onSurface, Offset(x(low!!) - 1.dp.toPx(), -3.dp.toPx()), Size(2.dp.toPx(), size.height + 6.dp.toPx()), CornerRadius(1.dp.toPx()))
        }
        Row(Modifier.padding(top = 8.dp)) {
            Text(
                when {
                    ranged -> tr("Range", "범위") + " ${ScoreCopy.load(low!!)}–${ScoreCopy.load(high!!)}"
                    else -> tr("No range yet", "아직 범위 없음")
                },
                style = PawseType.Micro,
                color = faint,
                modifier = Modifier.weight(1f),
            )
            load.typicalDayLoad?.let { Text(tr("Typical day", "보통의 하루") + " ${ScoreCopy.load(load.pts(it))}", style = PawseType.Micro, color = faint) }
        }
        Text(tr("Load is training, not a grade, so it stays grey.", "부하는 훈련량이지 성적이 아니라서 회색으로 둬요."), style = PawseType.Micro, color = faint, modifier = Modifier.padding(top = 8.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Keys(modifier: Modifier = Modifier.padding(top = 8.dp), content: @Composable () -> Unit) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

// --- Over time -----------------------------------------------------------------------

/** Daily load as bars, fitness solid and fatigue dashed over them; touching it picks the day. */
@Composable
private fun LoadChart(rows: List<NightRow>, selI: Int, onPick: (Int) -> Unit) {
    val s = PawseTheme.semantics
    val scheme = MaterialTheme.colorScheme
    val grey = scheme.onSurfaceVariant
    val n = rows.size
    val loads = rows.map { it.day?.load }
    // In points like the ring. As on the canvas: the top is the largest of load, fitness
    // and fatigue plus 15%, rounded up to a 5; the labels are the top and its half.
    val peak = loads.maxOf { l -> if (l == null) 0.0 else maxOf(l.points, l.fitness?.let(l::pts) ?: 0.0, l.fatigue?.let(l::pts) ?: 0.0) }
    val top = ceil(max(1.0, peak) * 1.15 / 5.0) * 5.0
    val frac: (Double) -> Float = { (1 - it / top).toFloat() }
    val description = tr(
        "Load over the last $n days, ${shortDate(rows[selI].date)} picked. Touch the chart to pick another day.",
        "최근 ${n}일 부하, ${shortDate(rows[selI].date)} 선택됨. 차트를 눌러 다른 날을 골라요.",
    )

    BoxWithConstraints(Modifier.padding(top = 20.dp).fillMaxWidth().height(CHART_HEIGHT.dp)) {
        val plot = maxWidth - 36.dp
        Canvas(
            Modifier
                .width(plot)
                .fillMaxHeight()
                .scrub(n) { f -> if (f != null) onPick((f * n).toInt().coerceIn(0, n - 1)) }
                .semantics { contentDescription = description },
        ) {
            val h = size.height
            val slot = size.width / n
            val bar = max(1.5.dp.toPx(), slot * 0.62f)
            val x: (Int) -> Float = { i -> i * slot + slot / 2 }
            val y: (Double) -> Float = { v -> frac(v) * h }

            drawRoundRect(s.baselineOuter, cornerRadius = CornerRadius(6.dp.toPx()))
            val column = Offset(selI * slot, -6.dp.toPx())
            val columnSize = Size(slot, h + 12.dp.toPx())
            drawRoundRect(scheme.surfaceContainerHigh, column, columnSize, CornerRadius(4.dp.toPx()))
            drawRoundRect(scheme.outline, column, columnSize, CornerRadius(4.dp.toPx()), style = Stroke(1.dp.toPx()))
            loads.forEachIndexed { i, l ->
                val v = l?.points ?: 0.0
                if (v > 0) {
                    drawRoundRect(
                        if (i == selI) scheme.onSurface else scheme.outline,
                        Offset(x(i) - bar / 2, y(v)),
                        Size(bar, max(1f, h - y(v))),
                        CornerRadius(2.dp.toPx()),
                    )
                }
            }
            drawPath(gapPath(loads.map { it?.let { l -> l.fitness?.let(l::pts) } }, x, y), scheme.onSurface, style = Stroke(1.75.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(
                gapPath(loads.map { it?.let { l -> l.fatigue?.let(l::pts) } }, x, y),
                grey,
                style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
            )
        }
        listOf(top, (top / 2).roundToInt().toDouble()).forEach { v ->
            Text("${v.roundToInt()}", style = PawseType.Label, color = grey, maxLines = 1, modifier = Modifier.pinned(plot + 6.dp, CHART_HEIGHT.dp * frac(v), centreX = false))
        }
    }
}

@Composable
private fun Legend(type: ScoreType) {
    val scheme = MaterialTheme.colorScheme
    val load = ScoringConfig.DEFAULT.load
    Keys(Modifier.padding(top = 10.dp)) {
        if (type == ScoreType.BODY) {
            UsualRangeKey(tr("Typical, 31–69", "평소 수준, 31–69"))
            LineKey(scheme.onSurfaceVariant, dashed = false, tr("Body each morning", "아침마다 컨디션"))
        } else {
            KeySwatch(scheme.outline, tr("Daily load", "하루 부하"))
            LineKey(scheme.onSurface, dashed = false, tr("Fitness, ${load.fitnessDays.roundToInt()} days", "체력, ${load.fitnessDays.roundToInt()}일"))
            LineKey(scheme.onSurfaceVariant, dashed = true, tr("Fatigue, ${load.fatigueDays.roundToInt()} days", "피로, ${load.fatigueDays.roundToInt()}일"))
        }
    }
}

@Composable
private fun LineKey(color: Color, dashed: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 16.dp, height = 2.dp)) {
            drawLine(
                color,
                Offset(0f, size.height / 2),
                Offset(size.width, size.height / 2),
                size.height,
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(text, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// --- Each signal over the same days ----------------------------------------------------

/** A signal as a row: the picked day's value, its line over the range, and its figures behind a tap. */
private class DayLine(
    val label: String,
    val values: List<Double?>,
    val shown: String?,
    val lines: List<Pair<String, String>>,
    val note: String,
    val aside: String = "",
    val band: Pair<Double, Double>? = null,
    val fromZero: Boolean = false,
    /** Body's rows: the picked day in SDs from your usual, signed so higher pushes Body up. */
    val z: Double? = null,
    /** Body's rows: whether the signal feeds Body or is only watched. */
    val scored: Boolean = true,
    /** Body's rows: "Usually 52 ± 6 ms · 43 nights". */
    val usualText: String? = null,
    /** Body's rows: the day had a reading but the usual needs more nights. */
    val learning: Boolean = false,
)

private fun bodyLines(snapshot: ScoringSnapshot, rows: List<NightRow>, selI: Int, name: (String) -> String): List<DayLine> {
    val row = rows[selI]
    val body = row.day?.body
    val w = body?.hrvWeight ?: ScoringConfig.DEFAULT.body.hrvWeight
    val context = ScoringConfig.DEFAULT.context
    val window = ScoringConfig.DEFAULT.signal.hrWindowMinutes
    val notRecorded = listOf(tr("That day", "그날") to tr("No night recorded", "기록된 밤 없음"))

    fun line(figure: NightFigure, label: String, aside: String, note: String, scored: Boolean = false, lines: (Double, Usual?) -> List<Pair<String, String>>): DayLine {
        val usual = snapshot.usual { figure.of(it) }
        val v = figure.of(row)
        // Sleeping heart rate is better lower, so its key points the way it pushes Body.
        val z = if (v != null && usual != null && usual.sd > 0) ((v - usual.mid) / usual.sd).let { if (figure == NightFigure.HR) -it else it } else null
        return DayLine(
            label = label,
            values = rows.map { figure.of(it) },
            shown = v?.let { figure.text(it) },
            lines = v?.let { lines(it, usual) } ?: notRecorded,
            note = note,
            aside = aside,
            band = usual?.let { it.lo to it.hi },
            z = z,
            scored = scored,
            usualText = usual?.let { usualSpread(figure, it) },
            learning = v != null && usual == null,
        )
    }

    fun usualLine(figure: NightFigure, u: Usual?) = u?.let { tr("Your usual", "내 평소") to span(figure, it) }

    /** What the engine compared the night with, when Body was read that morning. */
    fun engine(s: SignalDetail?, figure: NightFigure, u: Usual?, against: (SignalDetail) -> String, normal: (SignalDetail) -> String, extra: Pair<String, String>?) =
        if (s == null) {
            listOfNotNull(usualLine(figure, u))
        } else {
            listOfNotNull(
                tr("Against your normal", "내 평소와 비교") to against(s),
                tr("Your normal", "내 평소") to normal(s),
                "z" to signed(s.z, 2),
                extra,
                if (s.cycleAdjusted) tr("Cycle", "생리 주기") to tr("Your luteal offset was removed first", "황체기 차이를 먼저 뺐어요") else null,
            )
        }

    return listOf(
        line(
            NightFigure.HRV,
            tr("Heart rate variability", "심박 변이도"),
            pct(w),
            tr(
                "Higher is better. Body reads it on a log scale, so a 10 ms drop matters more from 40 than from 80. Your normal is the 60 nights ending a week ago.",
                "높을수록 좋아요. 컨디션은 이를 로그 척도로 읽어서, 같은 10 ms 하락도 80보다 40에서 더 크게 반영돼요. 내 평소는 일주일 전까지 60밤이에요.",
            ),
            scored = true,
        ) { v, u ->
            engine(
                body?.hrv,
                NightFigure.HRV,
                u,
                // One usual per page, as on the canvas: the row's "Usually 66 ± 12 ms" is the
                // figure these lines use too. The engine's own normal stands in until there is one.
                against = { signed((v / (u?.mid ?: it.baseline) - 1) * 100, 0) + "%" },
                normal = {
                    if (u != null) {
                        val spread = (u.sd / u.mid * 100).roundToInt()
                        tr("${u.mid.roundToInt()} ms ± $spread% · ${u.nights} nights", "${u.mid.roundToInt()} ms ± $spread% · ${u.nights}일 밤")
                    } else {
                        val spread = ((exp(it.spread) - 1.0) * 100).roundToInt()
                        tr("${it.baseline.roundToInt()} ms ± $spread% over ${it.baselineNights} nights", "${it.baselineNights}밤 기준 ${it.baseline.roundToInt()} ms ± $spread%")
                    }
                },
                // The app's name, never its package.
                extra = body?.hrv?.let { tr("Source", "출처") to name(it.source.substringBefore('#')) },
            )
        },
        line(
            NightFigure.HR,
            tr("Sleeping heart rate", "수면 중 심박수"),
            pct(1 - w),
            tr(
                "Lower is better. Several high nights in a row are what the overnight pattern detector watches for; it names likely causes from your log, never a disease.",
                "낮을수록 좋아요. 여러 밤 연속으로 높으면 야간 패턴 감지기가 이를 지켜봐요. 기록에서 그럴 만한 이유를 찾아줄 뿐, 병명은 말하지 않아요.",
            ),
            scored = true,
        ) { v, u ->
            engine(
                body?.sleepingHr,
                NightFigure.HR,
                u,
                against = { signed(v - (u?.mid ?: it.baseline), 1) + " bpm" },
                normal = {
                    if (u != null) {
                        val b = "${u.mid.roundToInt()} ± " + String.format(Locale.getDefault(), "%.1f", u.sd)
                        tr("$b bpm · ${u.nights} nights", "$b bpm · ${u.nights}일 밤")
                    } else {
                        val b = String.format(Locale.getDefault(), "%.1f ± %.1f", it.baseline, it.spread)
                        tr("$b bpm over ${it.baselineNights} nights", "${it.baselineNights}밤 기준 $b bpm")
                    }
                },
                extra = body?.sleepingHr?.let {
                    tr("Definition", "정의") to if (it.source.endsWith(DailyInputAssembler.RESTING)) {
                        tr("Your watch's resting heart rate", "시계의 안정 시 심박수")
                    } else {
                        tr("Lowest $window-minute rolling mean of the night", "밤 동안 가장 낮은 ${window}분 이동 평균")
                    }
                },
            )
        },
        line(
            NightFigure.BR,
            tr("Breathing rate", "호흡수"),
            tr("Watched", "관찰"),
            tr(
                "A rise of ${fmt1(context.breathingRise)} breath a minute alongside a high heart rate is one of the overnight pattern detector’s signs.",
                "심박수가 높으면서 호흡수가 분당 ${fmt1(context.breathingRise)}회 오르는 것은 야간 패턴 감지기가 살피는 신호 중 하나예요.",
            ),
        ) { _, u -> listOfNotNull(usualLine(NightFigure.BR, u), tr("Role", "역할") to tr("Not part of Body", "컨디션에 들어가지 않음")) },
        line(
            NightFigure.TEMP,
            tr("Skin temperature", "피부 온도"),
            tr("Watched", "관찰"),
            tr(
                "The device’s own deviation. A rise of ${fmt1(context.tempRiseC)} °C held over several nights is one of the detector’s signs; your cycle moves it too.",
                "기기 자체 기준 대비 차이예요. ${fmt1(context.tempRiseC)} °C 상승이 며칠 밤 이어지면 감지기가 살피는 신호 중 하나이며, 생리 주기도 영향을 줘요.",
            ),
        ) { _, u -> listOfNotNull(usualLine(NightFigure.TEMP, u), tr("Role", "역할") to tr("Not part of Body", "컨디션에 들어가지 않음")) },
        line(
            NightFigure.SPO2,
            tr("Blood oxygen", "혈중 산소"),
            tr("Watched", "관찰"),
            tr("Watched only. Short dips are common and are not scored.", "지켜보기만 해요. 짧게 떨어지는 건 흔하고 점수에 넣지 않아요."),
        ) { _, u -> listOfNotNull(usualLine(NightFigure.SPO2, u), tr("Role", "역할") to tr("Not part of Body", "컨디션에 들어가지 않음")) },
    )
}

private fun loadLines(rows: List<NightRow>, selI: Int, name: (String) -> String): List<DayLine> {
    val loads = rows.map { it.day?.load }
    val l = loads[selI]
    val config = ScoringConfig.DEFAULT.load
    val fitnessDays = config.fitnessDays.roundToInt()
    val fatigueDays = config.fatigueDays.roundToInt()
    val none = listOf(tr("That day", "그날") to tr("No reading", "값 없음"))

    fun line(label: String, get: (LoadReading) -> Double?, show: (Double) -> String, note: String, band: Pair<Double, Double>? = null, fromZero: Boolean = false, lines: (LoadReading, Double) -> List<Pair<String, String>>): DayLine {
        val v = l?.let(get)
        return DayLine(
            label = label,
            values = loads.map { it?.let(get) },
            shown = v?.let(show),
            lines = if (l != null && v != null) lines(l, v) else none,
            note = note,
            band = band,
            fromZero = fromZero,
        )
    }

    return listOf(
        line(
            tr("Daily load", "하루 부하"),
            get = { it.points },
            show = ScoreCopy::load,
            note = tr(
                "Minutes weighted by how far into your heart-rate reserve you worked, counted only above ${pct(config.minReserveFraction)} of it. " +
                    "Without a usable heart rate it falls back to effort × minutes.",
                "심박 예비량을 얼마나 깊이 썼는지로 가중한 운동 시간(분)이며, 예비량의 ${pct(config.minReserveFraction)}를 넘을 때만 집계해요. " +
                    "쓸 만한 심박수가 없으면 운동 강도 × 시간(분)으로 대신해요.",
            ),
            fromZero = true,
        ) { load, _ ->
            val range = load.pointsRange
            listOf(
                tr("Counted by", "계산 방식") to load.sessions.mapNotNull { it.method }.distinct().joinToString(", ") { ScoreCopy.loadMethod(it) }.ifEmpty { "—" },
                tr("Sessions", "운동") to load.sessions.joinToString("; ") { sessionText(it, name, load::pts) }.ifEmpty { tr("None", "없음") },
                tr("Sources", "출처") to load.sessions.map { it.citation }.filter { it.isNotBlank() }.distinct().joinToString(" ").ifEmpty { "—" },
                tr("Range that day", "그날 범위") to when {
                    range == null -> tr("No range yet", "아직 범위 없음")
                    range.rest -> tr("${range.low}–${range.high} · rest or easy", "${range.low}–${range.high} · 쉬거나 가볍게")
                    else -> "${range.low}–${range.high}"
                },
            )
        },
        line(
            tr("Fitness", "체력"),
            get = { l -> l.fitness?.let(l::pts) },
            show = ScoreCopy::load,
            note = tr("The long average: what your recent training has built.", "긴 평균: 최근 훈련으로 쌓은 것이에요."),
        ) { _, _ -> listOf(tr("Window", "기간") to tr("$fitnessDays days of load", "${fitnessDays}일 부하")) },
        line(
            tr("Fatigue", "피로"),
            get = { l -> l.fatigue?.let(l::pts) },
            show = ScoreCopy::load,
            note = tr("The short average: what the last week has cost.", "짧은 평균: 지난 일주일이 남긴 부담이에요."),
        ) { _, _ -> listOf(tr("Window", "기간") to tr("$fatigueDays days of load", "${fatigueDays}일 부하")) },
        line(
            tr("Form", "폼"),
            get = { it.form?.times(100) },
            show = { "${it.roundToInt()}%" },
            note = tr(
                "The gap between fitness and fatigue. Starting defaults, never an injury-risk label.",
                "체력과 피로의 차이예요. 시작용 기본값일 뿐, 부상 위험을 판정하지 않아요.",
            ),
            band = config.heavyBelow * 100 to config.freshAbove * 100,
        ) { load, v ->
            listOf(
                tr("Form", "폼") to "${v.roundToInt()}%" + (load.formBand?.let { ", " + ScoreCopy.form(it).lowercase() } ?: ""),
                tr("Bands", "구간") to tr(
                    "Fresh above ${pct(config.freshAbove)}, productive to ${pct(config.heavyBelow)}, heavy below",
                    "${pct(config.freshAbove)} 초과는 개운함, ${pct(config.heavyBelow)}까지 효과적, 그 아래는 무거움",
                ),
            )
        },
    )
}

/** "Run, 42 min · 35 · Published · Samsung Health", or why it wasn't counted. */
private fun sessionText(s: SessionLoad, name: (String) -> String, pts: (Double) -> Double): String =
    listOfNotNull(
        "${s.title ?: tr("Workout", "운동")}, ${minutesText(s.durationMinutes)}",
        s.skippedReason ?: ScoreCopy.load(pts(s.load)),
        // Every number carries where its coefficient came from (Provenance.kt).
        ScoreCopy.provenanceLabel(s.provenance).takeIf { s.skippedReason == null },
        s.source.takeIf { it.isNotBlank() }?.let(name),
    ).joinToString(" · ")

/**
 * A Body row as the canvas draws it: name and value, a band line (track ±3σ, your
 * normal ±1σ, a coloured key where the day sits), the word and your usual under
 * it; its line over the range and its figures open on a tap.
 */
@Composable
private fun NormalRow(line: DayLine, selI: Int, expanded: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val s = PawseTheme.semantics
    val faint = s.faint
    val turn by animateFloatAsState(if (expanded) 180f else 0f, tween(220, easing = EaseOut), label = "chevron")
    val z = line.z
    val key = when {
        !line.scored || z == null || abs(z) <= 1 -> s.neutral
        z > 0 -> s.bandHigh
        else -> s.bandModerate
    }
    val word = when {
        line.shown == null -> tr("No reading", "기록 없음")
        z == null -> tr("Your usual needs more nights", "평소 범위를 알려면 밤이 더 필요해요")
        abs(z) <= 1 -> tr("Within your normal", "평소 범위 안")
        else -> tr("${fmt1(abs(z))}σ ${if (z > 0) "above" else "below"} your normal", "평소보다 ${fmt1(abs(z))}σ ${if (z > 0) "높음" else "낮음"}")
    } + if (line.scored) "" else tr(" · watched", " · 지켜보기만")
    Column {
        HorizontalDivider(color = scheme.outlineVariant)
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClick = onToggle)
                .padding(top = 12.dp, bottom = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(line.label, style = PawseType.BodyStrong, modifier = Modifier.weight(1f))
                Text(line.shown ?: "—", style = PawseType.MetricValue)
                Glyph(Glyphs.ChevronDown, size = 16.dp, color = faint, modifier = Modifier.padding(start = 4.dp).rotate(turn))
            }
            Canvas(Modifier.padding(vertical = 8.dp).fillMaxWidth().height(12.dp)) {
                val r = CornerRadius(6.dp.toPx())
                drawRoundRect(s.baselineOuter, cornerRadius = r)
                drawRoundRect(s.baselineBand, Offset(size.width / 3f, 0f), Size(size.width / 3f, size.height), r)
                drawRect(scheme.outline, Offset(size.width / 2f, 0f), Size(1.dp.toPx(), size.height))
                val at = (((z ?: 0.0).coerceIn(-3.0, 3.0) + 3) / 6).toFloat() * size.width
                drawRoundRect(key, Offset(at - 2.dp.toPx(), 0f), Size(4.dp.toPx(), size.height), CornerRadius(2.dp.toPx()))
            }
            Row {
                Text(word, style = PawseType.Micro, color = faint, modifier = Modifier.weight(1f))
                line.usualText?.let { Text(it, style = PawseType.Micro, color = faint, textAlign = TextAlign.End, modifier = Modifier.padding(start = 8.dp)) }
            }
        }
        AnimatedVisibility(expanded, enter = RevealEnter, exit = RevealExit) {
            Column(Modifier.padding(bottom = 14.dp)) {
                Sparkline(line, selI, Modifier.fillMaxWidth().height(36.dp))
                line.lines.forEach { (k, v) ->
                    Row(Modifier.padding(top = 4.dp)) {
                        Text(k, style = PawseType.Micro, color = faint, modifier = Modifier.weight(1f))
                        Text(v, style = PawseType.Micro, color = scheme.onSurface, textAlign = TextAlign.End)
                    }
                }
                Text(line.note, style = PawseType.Micro, color = faint, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** A row's days as a line, its usual band behind, the picked day marked. */
@Composable
private fun Sparkline(line: DayLine, selI: Int, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val grey = scheme.onSurfaceVariant
    val bandColor = scheme.onSurface.copy(alpha = 0.1f)
    val ext = line.values.filterNotNull() + listOfNotNull(line.band?.first, line.band?.second)
    val least = if (line.fromZero) 0.0 else ext.minOrNull() ?: 0.0
    val most = ext.maxOrNull() ?: 1.0
    val room = ((most - least) * 0.12).takeIf { it > 0.0 } ?: 1.0
    val lo = if (line.fromZero) least else least - room
    val hi = most + room
    Canvas(modifier) {
        val slot = size.width / line.values.size
        val x: (Int) -> Float = { i -> i * slot + slot / 2 }
        val y: (Double) -> Float = { v -> (size.height - (v - lo) / (hi - lo) * size.height).toFloat() }
        line.band?.let { (a, b) -> drawRect(bandColor, Offset(0f, y(b)), Size(size.width, max(2.dp.toPx(), y(a) - y(b)))) }
        drawPath(gapPath(line.values, x, y), grey.copy(alpha = 0.7f), style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        line.values.forEachIndexed { i, v -> v?.let { drawCircle(grey, 1.5.dp.toPx(), Offset(x(i), y(it))) } }
        drawLine(scheme.onSurface, Offset(x(selI), 0f), Offset(x(selI), size.height), 1.dp.toPx())
        line.values[selI]?.let { drawCircle(scheme.onSurface, 3.dp.toPx(), Offset(x(selI), y(it))) }
    }
}


@Composable
private fun SignalRow(line: DayLine, selI: Int, expanded: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val grey = scheme.onSurfaceVariant
    val bandColor = PawseTheme.semantics.baselineBand
    val turn by animateFloatAsState(if (expanded) 180f else 0f, tween(220, easing = EaseOut), label = "chevron")

    // The line's own scale: its days and its band, with a little room either side.
    val ext = line.values.filterNotNull() + listOfNotNull(line.band?.first, line.band?.second)
    val least = if (line.fromZero) 0.0 else ext.minOrNull() ?: 0.0
    val most = ext.maxOrNull() ?: 1.0
    val room = ((most - least) * 0.12).takeIf { it > 0.0 } ?: 1.0
    val lo = if (line.fromZero) least else least - room
    val hi = most + room

    Column {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(top = 14.dp, bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(line.label, style = PawseType.Body, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Text(line.shown ?: "—", style = PawseType.MetricValue)
                Text(
                    line.aside,
                    style = PawseType.Label,
                    color = grey,
                    textAlign = TextAlign.End,
                    modifier = Modifier.padding(start = 12.dp).widthIn(min = 40.dp),
                )
                Spacer(Modifier.width(12.dp))
                Glyph(Glyphs.ChevronDown, size = 18.dp, color = grey, modifier = Modifier.rotate(turn))
            }
            Canvas(Modifier.padding(top = 10.dp, end = 36.dp).fillMaxWidth().height(32.dp)) {
                val slot = size.width / line.values.size
                val x: (Int) -> Float = { i -> i * slot + slot / 2 }
                val y: (Double) -> Float = { v -> ((29 - (v - lo) / (hi - lo) * 26) / 32 * size.height).toFloat() }
                line.band?.let { (a, b) ->
                    drawRoundRect(bandColor, Offset(0f, y(b)), Size(size.width, max(2.dp.toPx(), y(a) - y(b))), CornerRadius(3.dp.toPx()))
                }
                drawLine(scheme.outline, Offset(x(selI), -2.dp.toPx()), Offset(x(selI), size.height + 2.dp.toPx()), 1.dp.toPx())
                drawPath(gapPath(line.values, x, y), grey, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                line.values[selI]?.let { drawCircle(scheme.onSurface, 3.5.dp.toPx(), Offset(x(selI), y(it))) }
            }
        }
        AnimatedVisibility(expanded, enter = RevealEnter, exit = RevealExit) {
            Column(Modifier.padding(bottom = 18.dp)) {
                line.lines.forEach { (k, v) -> DetailLine(k, v) }
                Note(line.note, Modifier.padding(top = 8.dp))
            }
        }
        HorizontalDivider(color = scheme.outlineVariant)
    }
}

// --- Words and numbers -------------------------------------------------------------------

/** A line through the known values, lifted over the missing ones. */
private fun gapPath(values: List<Double?>, x: (Int) -> Float, y: (Double) -> Float): Path {
    val path = Path()
    var pen = false
    values.forEachIndexed { i, v ->
        if (v == null) {
            pen = false
        } else {
            if (pen) path.lineTo(x(i), y(v)) else path.moveTo(x(i), y(v))
            pen = true
        }
    }
    return path
}

/** "+0.42", "−3": a true minus, so signs line up with the canvas. */
internal fun signed(v: Double, digits: Int): String =
    (if (v < 0) "−" else "+") + String.format(Locale.getDefault(), "%.${digits}f", abs(v))

/** "Mon 19 Oct". */
internal fun shortDate(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern(tr("EEE d MMM", "M월 d일 (E)"), Locale.getDefault()))

private fun fmt1(v: Double): String = String.format(Locale.getDefault(), "%.1f", v)
