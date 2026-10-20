package app.pawse.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import app.pawse.core.data.scoring.DailyInputAssembler
import app.pawse.core.data.scoring.ScoringSnapshot
import app.pawse.core.ui.component.EaseOut
import app.pawse.core.ui.component.Glyph
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.KeySwatch
import app.pawse.core.ui.component.RevealEnter
import app.pawse.core.ui.component.RevealExit
import app.pawse.core.ui.component.Segmented
import app.pawse.core.ui.component.SplitBar
import app.pawse.core.ui.component.UsualRangeKey
import app.pawse.core.ui.component.cardSurface
import app.pawse.core.ui.theme.PawseTheme
import app.pawse.core.ui.theme.PawseType
import app.pawse.scoring.config.ScoringConfig
import app.pawse.scoring.model.BodyScore
import app.pawse.scoring.model.DayReport
import app.pawse.scoring.model.LoadReading
import app.pawse.scoring.model.ScoreType
import app.pawse.scoring.model.SessionLoad
import app.pawse.scoring.model.SignalDetail
import app.pawse.scoring.tr
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
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

    Column(modifier) {
        DayFigure(type, picked.day, today)

        Row(Modifier.padding(top = 32.dp).fillMaxWidth()) {
            Text(tr("Over time", "기간별 추이"), style = PawseType.Heading, modifier = Modifier.weight(1f).alignByBaseline())
            Text(tr("Touch a day to read it", "날짜를 눌러 읽어요"), style = PawseType.Label, color = grey, modifier = Modifier.alignByBaseline())
        }
        Segmented(
            options = DAY_RANGES,
            selected = span,
            onSelect = onRange,
            label = { tr("$it days", "${it}일") },
            height = 36.dp,
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

        Row(Modifier.padding(top = 28.dp, bottom = 2.dp)) {
            Text(
                if (type == ScoreType.BODY) tr("Its signals, same days", "같은 날들의 신호") else tr("Load, fitness and form, same days", "같은 날들의 부하·체력·폼"),
                style = PawseType.Label,
                color = grey,
                modifier = Modifier.weight(1f),
            )
            Text(shortDate(picked.date), style = PawseType.Label, color = grey, modifier = Modifier.padding(end = 30.dp))
        }
        val lines = if (type == ScoreType.BODY) bodyLines(snapshot, rows, selI) else loadLines(rows, selI)
        lines.forEachIndexed { i, line ->
            SignalRow(line, selI, expanded = open == i, onToggle = { onOpen(if (open == i) -1 else i) })
        }
    }
}

// --- The picked day ------------------------------------------------------------

/** The day's figure, its word, and the columns that make it up. */
@Composable
private fun DayFigure(type: ScoreType, day: DayReport?, today: Boolean) {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val body = day?.body
    val load = day?.load
    val neutral = PawseTheme.semantics.neutral

    var value = "—"
    var unit = ""
    var dot = neutral
    var word = tr("No reading", "값 없음")
    var note = tr("No reading for this day.", "이날은 기록이 없어요.")
    if (type == ScoreType.BODY && day != null) {
        if (body != null) {
            value = body.score.toString()
            dot = bodyColor(body.band, day.illness.level)
            word = ScoreCopy.bandWord(body.band)
            val measured = listOfNotNull(body.hrv?.tonight, body.sleepingHr?.tonight).size
            note = listOf(
                tr("Likely", "범위") + " ${body.low}–${body.high}",
                ScoreCopy.confidence(body.confidence),
                tr("$measured of 2 signals", "신호 2개 중 ${measured}개"),
            ).joinToString(" · ")
        } else {
            ScoreCopy.bodyStatus(day.bodyStatus, day.validNights, ScoringConfig.DEFAULT.body.minValidNights).takeIf { it.isNotEmpty() }?.let { note = it }
        }
    }
    if (type == ScoreType.LOAD && load != null) {
        value = ScoreCopy.load(load.todayLoad)
        unit = tr("load", "부하")
        word = load.formBand?.let(ScoreCopy::form) ?: tr("Building your history", "기록 쌓는 중")
        note = loadNote(load, today)
    }

    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, style = PawseType.Hero)
        if (unit.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(
                unit,
                style = PawseType.Heading.copy(fontWeight = PawseType.Body.fontWeight),
                color = grey,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        Row(
            Modifier.weight(1f).padding(start = 8.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(Modifier.size(8.dp)) { drawCircle(dot) }
            Spacer(Modifier.width(6.dp))
            Text(word, style = PawseType.BodyStrong, textAlign = TextAlign.End)
        }
    }
    Text(note, style = PawseType.Label, color = grey, modifier = Modifier.padding(top = 8.dp))

    if (type == ScoreType.BODY && body != null) BodyColumns(body)
    if (type == ScoreType.LOAD && load != null) LoadColumns(load, today)
}

private fun loadNote(load: LoadReading, today: Boolean): String {
    val low = load.rangeLow
    val high = load.rangeHigh
    val whose = if (today) tr("today’s", "오늘") else tr("that day’s", "그날")
    val where = when {
        load.rest -> tr("Rest: your overnight heart rate has been high. ", "휴식: 야간 심박수가 높았어요. ")
        load.todayLoad <= 0.0 -> if (today) tr("No workouts logged yet today. ", "오늘은 아직 기록된 운동이 없어요. ") else tr("A rest day. ", "쉰 날이에요. ")
        low == null || high == null -> ""
        load.todayLoad < low -> tr("Under $whose range. ", "$whose 범위보다 적어요. ")
        load.todayLoad > high -> tr("Over $whose range. ", "$whose 범위를 넘었어요. ")
        else -> tr("Inside $whose range. ", "$whose 범위 안이에요. ")
    }
    return where + tr("Load is training, not a grade, so it stays grey.", "부하는 평가가 아닌 훈련량이라 회색으로 둬요.")
}

/** HRV and sleeping heart rate side by side, and their shares of Body. */
@Composable
private fun BodyColumns(body: BodyScore) {
    val scheme = MaterialTheme.colorScheme
    val w = body.hrvWeight
    fun z(s: SignalDetail?) = s?.let { " · ${signed(it.z, 2)} SD" }.orEmpty()
    Columns(
        listOf(
            (body.hrv?.tonight?.let { "${it.roundToInt()} ms" } ?: "—") to "HRV" + z(body.hrv),
            (body.sleepingHr?.tonight?.let { "${it.roundToInt()} bpm" } ?: "—") to tr("Sleeping HR", "수면 중 심박수") + z(body.sleepingHr),
        ),
    ) {
        val label = tr(
            "HRV counts for ${pct(w)} of Body and sleeping heart rate for ${pct(1 - w)}.",
            "컨디션에서 HRV는 ${pct(w)}, 수면 중 심박수는 ${pct(1 - w)}를 차지해요.",
        )
        SplitBar(
            listOf(w.toFloat() to scheme.onSurface, (1 - w).toFloat() to scheme.onSurfaceVariant),
            Modifier.semantics { contentDescription = label },
        )
        Keys {
            KeySwatch(scheme.onSurface, tr("HRV, ${pct(w)} of Body", "HRV, 컨디션의 ${pct(w)}"))
            KeySwatch(scheme.onSurfaceVariant, tr("Sleeping HR, ${pct(1 - w)}", "수면 중 심박수, ${pct(1 - w)}"))
        }
    }
}

/** Load, fitness and fatigue, and where the day's load sits in its range. */
@Composable
private fun LoadColumns(load: LoadReading, today: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val s = PawseTheme.semantics
    val grey = scheme.onSurfaceVariant
    val low = load.rangeLow
    val high = load.rangeHigh
    Columns(
        listOf(
            ScoreCopy.load(load.todayLoad) to tr("Load", "부하"),
            (load.fitness?.let(ScoreCopy::load) ?: "—") to tr("Fitness", "체력"),
            (load.fatigue?.let(ScoreCopy::load) ?: "—") to tr("Fatigue", "피로"),
        ),
    ) {
        if (low != null && high != null && !load.rest) {
            val top = maxOf(high * 1.25, load.todayLoad, 1.0)
            val whose = if (today) tr("today’s", "오늘") else tr("that day’s", "그날")
            val label = tr(
                "Load ${ScoreCopy.load(load.todayLoad)} against $whose range of ${ScoreCopy.load(low)} to ${ScoreCopy.load(high)}.",
                "부하 ${ScoreCopy.load(load.todayLoad)}, $whose 범위는 ${ScoreCopy.load(low)}~${ScoreCopy.load(high)}예요.",
            )
            Canvas(Modifier.fillMaxWidth().height(14.dp).semantics { contentDescription = label }) {
                fun x(v: Double) = (v / top).toFloat().coerceIn(0f, 1f) * size.width
                val y = 4.dp.toPx()
                val h = 6.dp.toPx()
                val r = CornerRadius(3.dp.toPx())
                drawRoundRect(s.baselineOuter, Offset(0f, y), Size(size.width, h), r)
                drawRoundRect(s.baselineBand, Offset(x(low), y), Size(x(high) - x(low), h), r)
                drawRoundRect(grey, Offset(0f, y), Size(x(load.todayLoad), h), r)
                drawRoundRect(scheme.onSurface, Offset(x(low) - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height), CornerRadius(1.dp.toPx()))
            }
        }
        Row(Modifier.padding(top = 6.dp)) {
            Text(
                when {
                    load.rest -> tr("Rest, no range", "휴식, 범위 없음")
                    low != null && high != null -> tr("Range", "범위") + " ${ScoreCopy.load(low)}–${ScoreCopy.load(high)}"
                    else -> tr("No range yet", "아직 범위 없음")
                },
                style = PawseType.Label,
                color = grey,
                modifier = Modifier.weight(1f),
            )
            load.typicalDayLoad?.let { Text(tr("Typical day", "평소 하루") + " ${ScoreCopy.load(it)}", style = PawseType.Label, color = grey) }
        }
    }
}

/** A card of figures side by side, hairlines between them, and what they add up to underneath. */
@Composable
private fun Columns(cols: List<Pair<String, String>>, foot: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.padding(top = 16.dp).fillMaxWidth().cardSurface().padding(vertical = 14.dp)) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            cols.forEachIndexed { i, (value, key) ->
                if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(scheme.outlineVariant))
                Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    Text(value, style = PawseType.MetricValue, maxLines = 1)
                    Text(key, style = PawseType.Label, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        Column(Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp), content = foot)
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
    val top = max(150.0, loads.maxOf { it?.todayLoad ?: 0.0 })
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
                .scrub { f -> if (f != null) onPick((f * n).toInt().coerceIn(0, n - 1)) }
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
                val v = l?.todayLoad ?: 0.0
                if (v > 0) {
                    drawRoundRect(
                        if (i == selI) scheme.onSurface else scheme.outline,
                        Offset(x(i) - bar / 2, y(v)),
                        Size(bar, max(1f, h - y(v))),
                        CornerRadius(2.dp.toPx()),
                    )
                }
            }
            drawPath(gapPath(loads.map { it?.fitness }, x, y), scheme.onSurface, style = Stroke(1.75.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(
                gapPath(loads.map { it?.fatigue }, x, y),
                grey,
                style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
            )
        }
        listOf(100.0, 50.0).forEach { v ->
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
)

private fun bodyLines(snapshot: ScoringSnapshot, rows: List<NightRow>, selI: Int): List<DayLine> {
    val row = rows[selI]
    val body = row.day?.body
    val w = body?.hrvWeight ?: ScoringConfig.DEFAULT.body.hrvWeight
    val context = ScoringConfig.DEFAULT.context
    val window = ScoringConfig.DEFAULT.signal.hrWindowMinutes
    val notRecorded = listOf(tr("That day", "그날") to tr("No night recorded", "기록된 밤 없음"))

    fun line(figure: NightFigure, label: String, aside: String, note: String, lines: (Double, Usual?) -> List<Pair<String, String>>): DayLine {
        val usual = snapshot.usual { figure.of(it) }
        val v = figure.of(row)
        return DayLine(
            label = label,
            values = rows.map { figure.of(it) },
            shown = v?.let { figure.text(it) },
            lines = v?.let { lines(it, usual) } ?: notRecorded,
            note = note,
            aside = aside,
            band = usual?.let { it.lo to it.hi },
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
        ) { v, u ->
            engine(
                body?.hrv,
                NightFigure.HRV,
                u,
                against = { signed((v / it.baseline - 1) * 100, 0) + "%" },
                normal = {
                    val spread = ((exp(it.spread) - 1.0) * 100).roundToInt()
                    tr("${it.baseline.roundToInt()} ms ± $spread% over ${it.baselineNights} nights", "${it.baselineNights}밤 기준 ${it.baseline.roundToInt()} ms ± $spread%")
                },
                extra = body?.hrv?.let { tr("Source", "출처") to it.source.substringBefore('#') },
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
        ) { v, u ->
            engine(
                body?.sleepingHr,
                NightFigure.HR,
                u,
                against = { signed(v - it.baseline, 1) + " bpm" },
                normal = {
                    val b = String.format(Locale.getDefault(), "%.1f ± %.1f", it.baseline, it.spread)
                    tr("$b bpm over ${it.baselineNights} nights", "${it.baselineNights}밤 기준 $b bpm")
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
    )
}

private fun loadLines(rows: List<NightRow>, selI: Int): List<DayLine> {
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
            get = { it.todayLoad },
            show = ScoreCopy::load,
            note = tr(
                "Minutes weighted by how far into your heart-rate reserve you worked, counted only above ${pct(config.minReserveFraction)} of it. " +
                    "Without a usable heart rate it falls back to effort × minutes.",
                "심박 예비량을 얼마나 깊이 썼는지로 가중한 운동 시간(분)이며, 예비량의 ${pct(config.minReserveFraction)}를 넘을 때만 집계해요. " +
                    "쓸 만한 심박수가 없으면 운동 강도 × 시간(분)으로 대신해요.",
            ),
            fromZero = true,
        ) { load, _ ->
            val low = load.rangeLow
            val high = load.rangeHigh
            listOf(
                tr("Counted by", "계산 방식") to load.sessions.mapNotNull { it.method }.distinct().joinToString(", ") { ScoreCopy.loadMethod(it) }.ifEmpty { "—" },
                tr("Sessions", "운동") to load.sessions.joinToString("; ", transform = ::sessionText).ifEmpty { tr("None", "없음") },
                tr("Range that day", "그날 범위") to when {
                    load.rest -> tr("Rest: overnight heart rate was high", "휴식: 야간 심박수가 높았어요")
                    low != null && high != null -> "${ScoreCopy.load(low)}–${ScoreCopy.load(high)}"
                    else -> tr("After your first few workouts", "운동을 몇 번 한 뒤에")
                },
            )
        },
        line(
            tr("Fitness", "체력"),
            get = { it.fitness },
            show = ScoreCopy::load,
            note = tr("The long average: what your recent training has built.", "긴 평균: 최근 훈련으로 쌓은 것이에요."),
        ) { _, _ -> listOf(tr("Window", "기간") to tr("$fitnessDays days of load", "${fitnessDays}일 부하")) },
        line(
            tr("Fatigue", "피로"),
            get = { it.fatigue },
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

/** "Run, 42 min · 35", or why it wasn't counted. */
private fun sessionText(s: SessionLoad): String =
    "${s.title ?: tr("Workout", "운동")}, ${minutesText(s.durationMinutes)} · ${s.skippedReason ?: ScoreCopy.load(s.load)}"

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
