package app.pawse.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import app.pawse.core.ui.component.Glyph
import app.pawse.core.ui.component.pressable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pawse.core.data.activity.HeartHour
import app.pawse.core.data.activity.MovingSnapshot
import app.pawse.core.data.activity.ThroughDay
import app.pawse.core.ui.component.BandDot
import app.pawse.core.ui.component.GlyphButton
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.PawseCard
import app.pawse.core.ui.component.Segmented
import app.pawse.core.ui.component.aura
import app.pawse.core.ui.component.rememberSwap
import app.pawse.core.ui.component.swapIn
import app.pawse.core.ui.theme.PawseFontFamily
import app.pawse.core.ui.theme.PawseTheme
import app.pawse.core.ui.theme.PawseType
import app.pawse.scoring.activity.MovingReading
import app.pawse.scoring.tr
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle as DayStyle
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Moving today's detail: steps, energy and heart rate today in one push, opened on
 * the figure that was tapped. Health Connect's own totals by the clock, against the
 * same hours on your recent days. Shown, never scored; grey only.
 */
@Composable
fun ActivityScreen(
    initial: ActivityFigure,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** The ⓘ: How Pawse calculates, at Your usual. */
    onOpenCalc: () -> Unit = {},
    /** A Where it counts row: the score it feeds. */
    onOpenScore: (app.pawse.scoring.model.ScoreType) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val moving by viewModel.moving.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snapshot = (state as? HomeUiState.Ready)?.snapshot
    var figure by rememberSaveable { mutableStateOf(initial) }

    Scaffold(modifier = modifier, containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().aura().padding(top = padding.calculateTopPadding() + 12.dp)) {
            Row(Modifier.padding(horizontal = 12.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphButton(Glyphs.ChevronLeft, tr("Back to Scores", "점수로 돌아가기"), onClick = onBack)
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Segmented(
                        options = ActivityFigure.entries,
                        selected = figure,
                        onSelect = { figure = it },
                        label = ::figureName,
                        modifier = Modifier.width(264.dp),
                    )
                }
                GlyphButton(Glyphs.Info, tr("How your usual is worked out", "내 평소 계산 방법"), onClick = onOpenCalc)
            }
            val m = moving
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = padding.calculateBottomPadding() + 48.dp),
            ) {
                // A swap between figures fades the page in place; nothing slides.
                Column(Modifier.swapIn(rememberSwap(figure))) {
                    when {
                        m == null -> Text(
                            tr("Nothing written to Health Connect today yet.", "오늘은 아직 헬스 커넥트에 기록된 게 없어요."),
                            style = PawseType.Body,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        figure == ActivityFigure.HEART -> HeartPage(m, snapshot?.nights?.get(snapshot.date)?.signals?.sleepingHr?.value)
                        else -> {
                            val energy = figure == ActivityFigure.ENERGY
                            AmountPage(
                                reading = if (energy) m.energy else m.steps,
                                day = if (energy) m.energyDay else m.stepsDay,
                                asOf = m.asOfEpochMs,
                                energy = energy,
                            )
                        }
                    }
                    if (m != null) {
                        // Where it counts: heart rate feeds Body overnight; steps and energy are shown only.
                        val body = snapshot?.today?.body
                        val sleeping = body?.sleepingHr
                        if (figure == ActivityFigure.HEART && body != null && sleeping?.tonight != null) {
                            WhereItCounts(
                                title = tr("Sleeping heart rate", "수면 중 심박수"),
                                meta = tr("${sleeping.tonight!!.roundToInt()} bpm against your usual ${sleeping.baseline.roundToInt()}", "${sleeping.tonight!!.roundToInt()} bpm, 평소 ${sleeping.baseline.roundToInt()}"),
                                value = tr("Body ${body.score}", "컨디션 ${body.score}"),
                                onClick = { onOpenScore(app.pawse.scoring.model.ScoreType.BODY) },
                            )
                        }
                        Sources(
                            writtenBy = if (figure == ActivityFigure.STEPS) m.stepsWriter?.let { writer(it) } else null,
                            readAt = m.asOfEpochMs,
                            note = shownNote(figure),
                        )
                    }
                }
            }
        }
    }
}

private fun figureName(f: ActivityFigure): String = when (f) {
    ActivityFigure.STEPS -> tr("Steps", "걸음 수")
    ActivityFigure.ENERGY -> tr("Energy", "에너지")
    ActivityFigure.HEART -> tr("Heart rate", "심박수")
}

private fun count(v: Double): String = String.format(Locale.getDefault(), "%,d", v.roundToInt())

private fun hh(hour: Int): String = "%02d:00".format(hour % 24)

/** Steps or active energy: today by the clock, its usual band, the hours, and the week. */
@Composable
private fun AmountPage(reading: MovingReading, day: ThroughDay?, asOf: Long, energy: Boolean) {
    val unit = if (energy) "kcal" else tr("steps", "걸음")
    val per = if (energy) " kcal" else ""
    val now = ScoreCopy.clock(asOf)
    Text(tr("Today · midnight to $now", "오늘 · 자정부터 ${now}까지"), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(verticalAlignment = Alignment.Bottom) {
        Text(count(reading.today), style = PawseType.Hero, modifier = Modifier.semantics { heading() })
        Text(unit, style = PawseType.Heading.copy(fontWeight = PawseType.Body.fontWeight), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, bottom = 10.dp))
    }
    BandDot(color = PawseTheme.semantics.neutral, label = movingWord(reading.word))
    val low = reading.low
    val high = reading.high
    Text(
        if (low != null && high != null) {
            tr("Usually ${count(low)}–${count(high)}$per by now · from ${reading.days} days", "이 시간 평소 ${count(low)}–${count(high)}$per · ${reading.days}일 기준")
        } else {
            tr("Your usual needs a week of days. ${reading.days} so far.", "평소를 알려면 일주일이 필요해요. 지금까지 ${reading.days}일.")
        },
        style = PawseType.Micro,
        color = PawseTheme.semantics.faint,
        modifier = Modifier.padding(top = 4.dp),
    )
    day ?: return

    var picked by remember { mutableStateOf<Int?>(null) }
    SectionHead(tr("Through the day", "하루 동안"), if (day.usualHigh.isNotEmpty()) tr("shaded is your usual", "음영은 내 평소") else "")
    val nowHour = day.perHour.lastIndex
    val read = picked?.coerceAtMost(nowHour)?.let { h ->
        val by = day.today[h + 1]
        val usual = if (day.usualHigh.isNotEmpty()) {
            tr(" · usually ${count(day.usualLow[h + 1])}–${count(day.usualHigh[h + 1])}", " · 평소 ${count(day.usualLow[h + 1])}–${count(day.usualHigh[h + 1])}")
        } else {
            ""
        }
        // The hour still running reads "by now", not by its end.
        val until = if (h == nowHour) now else hh(h + 1)
        tr("By $until · ${count(by)}$per$usual · ${count(day.perHour[h])} in that hour", "${until}까지 ${count(by)}$per$usual · 그 시간 ${count(day.perHour[h])}")
    }
    ReadLine(read ?: tr("Drag across the chart to read any hour", "차트를 끌어 시간대별로 읽어요"), picked != null)
    val at = Instant.ofEpochMilli(asOf).atZone(ZoneId.systemDefault())
    ThroughChart(day, at.hour + at.minute / 60f, picked, onPick = { picked = it })

    val most = day.perHour.withIndex().maxByOrNull { it.value }
    val yesterday = day.week.getOrNull(day.week.size - 2)
    val stats = buildList {
        most?.takeIf { it.value > 0.0 }?.let { add(Triple(tr("Most in one hour", "가장 많은 한 시간"), count(it.value) + per, hh(it.index))) }
        if (day.usualHigh.isNotEmpty()) {
            add(Triple(tr("Usual whole day", "평소 하루 전체"), "${count(day.usualLow[24])}–${count(day.usualHigh[24])}$per", tr("from ${reading.days} days", "${reading.days}일 기준")))
        }
        yesterday?.let { (d, v) ->
            add(Triple(tr("Yesterday", "어제"), count(v) + per, d.dayOfWeek.getDisplayName(DayStyle.SHORT, Locale.getDefault())))
        }
        val full = day.week.dropLast(1).map { it.second }.filter { it > 0.0 }
        if (full.isNotEmpty()) add(Triple(tr("Last 6 days, average", "지난 6일 평균"), count(full.average()) + per, tr("days with data", "기록 있는 날")))
    }
    Stats(stats)

    SectionHead(tr("Last 7 days", "지난 7일"), if (day.usualHigh.isNotEmpty()) tr("shaded is a usual whole day", "음영은 평소 하루 전체") else "")
    WeekBars(day)
}

/** Heart rate since waking, hour by hour: low to high as a stick, the average as a dot. */
@Composable
private fun HeartPage(m: MovingSnapshot, sleepingBpm: Double?) {
    val zone = ZoneId.systemDefault()
    val wakeHour = Instant.ofEpochMilli(m.wakeEpochMs).atZone(zone).hour
    // Average and highest count from the waking hour, as the Scores card does; the
    // lowest awake starts the hour after it, since the waking hour is mostly sleep.
    val awake = m.heart.filter { it.hour >= wakeHour }.ifEmpty { m.heart }
    val upright = awake.filter { !m.wakeKnown || it.hour > wakeHour }.ifEmpty { awake }
    Text(
        if (m.wakeKnown) tr("Today · since you woke at ${ScoreCopy.clock(m.wakeEpochMs)}", "오늘 · ${ScoreCopy.clock(m.wakeEpochMs)} 기상 이후") else tr("Today · since midnight", "오늘 · 자정부터"),
        style = PawseType.Label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (awake.isEmpty()) {
        Text(
            tr("No heart rate since you woke. Your watch hasn’t written any today.", "기상 이후 심박수가 없어요. 오늘 워치가 기록하지 않았어요."),
            style = PawseType.Body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
        return
    }
    val mean = awake.map { it.mean.toDouble() }.average().roundToInt()
    val high = awake.maxBy { it.high }
    val low = upright.minBy { it.low }
    Row(verticalAlignment = Alignment.Bottom) {
        Text("$mean", style = PawseType.Hero, modifier = Modifier.semantics { heading() })
        Text(tr("bpm average", "bpm 평균"), style = PawseType.Heading.copy(fontWeight = PawseType.Body.fontWeight), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, bottom = 10.dp))
    }
    Text(
        tr("Now ${m.heart.last().mean} · lowest ${low.low} · highest ${high.high}", "지금 ${m.heart.last().mean} · 최저 ${low.low} · 최고 ${high.high}"),
        style = PawseType.Micro,
        color = PawseTheme.semantics.faint,
        modifier = Modifier.padding(top = 4.dp),
    )

    var picked by remember { mutableStateOf<Int?>(null) }
    SectionHead(tr("Hour by hour", "시간별"), tr("low to high · dot is the average", "최저–최고 · 점은 평균"))
    // A finger past the last hour reads the last hour there is.
    val read = picked?.let { h -> m.heart.lastOrNull { it.hour <= h } }?.let { b ->
        val asleep = b.hour < wakeHour && m.wakeKnown
        tr(
            "${hh(b.hour)}–${hh(b.hour + 1)}${if (asleep) " · asleep" else ""} · ${b.low}–${b.high} bpm · average ${b.mean}",
            "${hh(b.hour)}–${hh(b.hour + 1)}${if (asleep) " · 수면 중" else ""} · ${b.low}–${b.high} bpm · 평균 ${b.mean}",
        )
    }
    ReadLine(read ?: tr("Drag across the chart to read any hour", "차트를 끌어 시간대별로 읽어요"), picked != null)
    HeartHours(m.heart, if (m.wakeKnown) wakeHour else 0, picked?.let { h -> m.heart.lastOrNull { it.hour <= h }?.hour }, onPick = { picked = it })

    Stats(
        listOfNotNull(
            sleepingBpm?.let { Triple(tr("Sleeping", "수면 중"), "${it.roundToInt()} bpm", tr("last night · feeds Body", "지난밤 · 컨디션에 반영")) },
            Triple(tr("Highest", "최고"), "${high.high} bpm", hh(high.hour)),
            Triple(tr("Lowest awake", "깨어 있을 때 최저"), "${low.low} bpm", hh(low.hour)),
            Triple(tr("Now", "지금"), "${m.heart.last().mean} bpm", hh(m.heart.last().hour)),
        ),
    )
}

/** One row under Where it counts: what feeds a score, its value there, and a way to it. */
@Composable
private fun WhereItCounts(title: String, meta: String, value: String, onClick: () -> Unit) {
    Text(tr("Where it counts", "반영되는 곳"), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 32.dp))
    androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 8.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .pressable(onClick = onClick)
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = PawseType.Body)
            Text(meta, style = PawseType.Micro, color = PawseTheme.semantics.faint)
        }
        Text(value, style = PawseType.MetricValue, modifier = Modifier.padding(horizontal = 8.dp))
        Glyph(Glyphs.ChevronRight, size = 16.dp, color = PawseTheme.semantics.faint)
    }
}

/** Where it comes from: who wrote it, when Pawse read it, and that Pawse keeps none of it. */
@Composable
private fun Sources(writtenBy: String?, readAt: Long, note: String) {
    val faint = PawseTheme.semantics.faint
    Text(tr("Where it comes from", "데이터 출처"), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 32.dp))
    listOfNotNull(
        writtenBy?.let { tr("Written by", "기록한 앱") to it },
        tr("Read", "읽은 때") to tr("From Health Connect at ${ScoreCopy.clock(readAt)}", "${ScoreCopy.clock(readAt)}에 헬스 커넥트에서"),
        tr("Kept", "보관") to tr("Nothing. Pawse reads it again each time Scores opens.", "없음. 점수 화면을 열 때마다 다시 읽어요."),
    ).forEach { (k, v) ->
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.padding(top = 10.dp)) {
            Text(k, style = PawseType.Micro, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.widthIn(min = 84.dp).padding(end = 8.dp))
            Text(v, style = PawseType.Micro, color = faint, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
    }
    Text(note, style = PawseType.Micro, color = faint, modifier = Modifier.padding(top = 16.dp))
}

private fun shownNote(figure: ActivityFigure): String = when (figure) {
    ActivityFigure.STEPS -> tr("Shown, never scored. Steps stand in for Load only when a walk has no heart rate.", "보여주기만 하고 점수에는 넣지 않아요. 심박수가 없는 걷기만 걸음 수로 부하에 반영해요.")
    ActivityFigure.ENERGY -> tr("Shown, never scored. Food compares what you ate with active plus resting energy.", "보여주기만 하고 점수에는 넣지 않아요. 음식 화면에서 먹은 양을 활동과 휴식 에너지의 합과 비교해요.")
    ActivityFigure.HEART -> tr("Shown, never scored. Sleeping heart rate feeds Body; workouts count under Load.", "보여주기만 하고 점수에는 넣지 않아요. 수면 중 심박수는 컨디션에, 운동은 부하에 반영돼요.")
}

@Composable
private fun SectionHead(title: String, aside: String) {
    Row(Modifier.fillMaxWidth().padding(top = 32.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = PawseType.Heading, modifier = Modifier.weight(1f))
        Text(aside, style = PawseType.Micro, color = PawseTheme.semantics.faint)
    }
}

/** What the finger is on, read out politely; grey until something is picked. */
@Composable
private fun ReadLine(text: String, picked: Boolean) {
    Text(
        text,
        style = PawseType.Label,
        color = if (picked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.heightIn(min = 18.dp).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Four small figures, two by two. */
@Composable
private fun Stats(items: List<Triple<String, String, String>>) {
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (k, v, n) ->
                    PawseCard(modifier = Modifier.weight(1f), padding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                        Text(k, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(v, style = PawseType.Heading.copy(fontSize = 15.sp, lineHeight = 22.sp))
                        Text(n, style = PawseType.Micro, color = PawseTheme.semantics.faint)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** A round top for a chart's y axis: 1, 2 or 5 times a power of ten. */
private fun niceTop(max: Double): Double {
    if (max <= 0.0) return 1.0
    val p = 10.0.pow(kotlin.math.floor(log10(max)))
    return listOf(1.0, 2.0, 5.0, 10.0).first { it * p >= max } * p
}

private fun axisLabel(v: Double): String = if (v >= 1000) "${(v / 1000).let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it) }}k" else count(v)

/** Today's running total by the clock over your usual band, the rest of a usual day fainter. */
@Composable
private fun ThroughChart(day: ThroughDay, nowFrac: Float, picked: Int?, onPick: (Int?) -> Unit) {
    val s = PawseTheme.semantics
    val on = MaterialTheme.colorScheme.onSurface
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val bg = MaterialTheme.colorScheme.background
    val measurer = rememberTextMeasurer()
    val axis = TextStyle(fontFamily = PawseFontFamily, fontSize = 11.sp, color = s.faint)
    val nowHour = day.perHour.lastIndex + 1
    // Hour marks along the line; the last one is now, not the end of this hour.
    fun mark(i: Int): Float = if (i == day.today.lastIndex) nowFrac else i.toFloat()
    val top = niceTop(maxOf(day.today.last(), day.usualHigh.maxOrNull() ?: 0.0) * 1.05)
    Canvas(
        Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .height(148.dp)
            .scrub(24) { f -> onPick(f?.let { (it * 24).toInt() }) }
            .semantics { contentDescription = tr("Running total by hour against your usual.", "내 평소와 비교한 시간별 누적.") },
    ) {
        val w = size.width * 310f / 342f
        val chartTop = 4.dp.toPx()
        val chartH = 120.dp.toPx()
        fun x(h: Float) = h / 24f * w
        fun y(v: Double) = chartTop + chartH - (v / top).toFloat() * chartH
        listOf(0f, 0.5f, 1f).forEach { f ->
            drawLine(s.glassLine, Offset(0f, chartTop + chartH * f), Offset(w, chartTop + chartH * f), 1.dp.toPx())
        }
        if (day.usualHigh.isNotEmpty()) {
            fun band(from: Int, to: Int) = Path().apply {
                moveTo(x(from.toFloat()), y(day.usualHigh[from]))
                for (h in from + 1..to) lineTo(x(h.toFloat()), y(day.usualHigh[h]))
                for (h in to downTo from) lineTo(x(h.toFloat()), y(day.usualLow[h]))
                close()
            }
            // Past now the band is the rest of a usual day, fainter.
            drawPath(band(0, nowHour.coerceAtMost(24)), on.copy(alpha = 0.12f))
            if (nowHour < 24) drawPath(band(nowHour, 24), on.copy(alpha = 0.05f))
        }
        val line = Path().apply {
            day.today.forEachIndexed { i, v -> if (i == 0) moveTo(x(0f), y(v)) else lineTo(x(mark(i)), y(v)) }
        }
        drawPath(line, on, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(grey, Offset(x(nowFrac), chartTop), Offset(x(nowFrac), chartTop + chartH), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
        val end = Offset(x(nowFrac), y(day.today.last()))
        drawCircle(bg, 6.5.dp.toPx(), end)
        drawCircle(on, 4.5.dp.toPx(), end)
        picked?.coerceAtMost(nowHour - 1)?.let { h ->
            val px = x(mark(h + 1))
            drawLine(on, Offset(px, chartTop), Offset(px, chartTop + chartH), 1.dp.toPx())
            drawCircle(bg, 7.dp.toPx(), Offset(px, y(day.today[h + 1])))
            drawCircle(on, 5.dp.toPx(), Offset(px, y(day.today[h + 1])))
        }
        listOf(top to 0f, top / 2 to 0.5f, 0.0 to 1f).forEach { (v, f) ->
            val t = measurer.measure(axisLabel(v), axis)
            drawText(t, topLeft = Offset(size.width - t.size.width, chartTop + chartH * f - t.size.height / 2f))
        }
        listOf(0, 6, 12, 18, 24).forEach { h ->
            val t = measurer.measure("%02d".format(h), axis)
            val left = when (h) {
                0 -> 0f
                24 -> w - t.size.width
                else -> x(h.toFloat()) - t.size.width / 2f
            }
            drawText(t, topLeft = Offset(left, chartTop + chartH + 6.dp.toPx()))
        }
    }
}

/** Heart rate by hour: sleep shaded before waking, a stick per hour and its average as a dot. */
@Composable
private fun HeartHours(hours: List<HeartHour>, wakeHour: Int, picked: Int?, onPick: (Int?) -> Unit) {
    val s = PawseTheme.semantics
    val on = MaterialTheme.colorScheme.onSurface
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val axis = TextStyle(fontFamily = PawseFontFamily, fontSize = 11.sp, color = s.faint)
    val asleep = tr("Asleep", "수면")
    Canvas(
        Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .height(148.dp)
            .scrub(24) { f -> onPick(f?.let { (it * 24).toInt() }) }
            .semantics { contentDescription = tr("Heart rate by hour, lowest to highest.", "시간별 심박수, 최저에서 최고까지.") },
    ) {
        val w = size.width * 310f / 342f
        val chartTop = 4.dp.toPx()
        val chartH = 120.dp.toPx()
        fun xc(h: Int) = (h + 0.5f) / 24f * w
        fun y(b: Long) = chartTop + chartH - ((b - 40f) / 160f).coerceIn(0f, 1f) * chartH
        if (wakeHour > 0) {
            drawRoundRect(s.baselineOuter, Offset(0f, chartTop), Size(wakeHour / 24f * w, chartH), CornerRadius(6.dp.toPx()))
            val t = measurer.measure(asleep, axis.copy(color = grey))
            if (t.size.width < wakeHour / 24f * w) drawText(t, topLeft = Offset(wakeHour / 48f * w - t.size.width / 2f, chartTop + 8.dp.toPx()))
        }
        listOf(0f, 0.5f, 1f).forEach { f -> drawLine(s.glassLine, Offset(0f, chartTop + chartH * f), Offset(w, chartTop + chartH * f), 1.dp.toPx()) }
        hours.forEach { b ->
            val sleeping = b.hour < wakeHour
            val chosen = b.hour == picked
            val stick = when {
                chosen -> on
                sleeping -> s.mark
                else -> grey
            }
            drawLine(stick, Offset(xc(b.hour), y(b.high)), Offset(xc(b.hour), y(b.low)), 6.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(if (sleeping) grey else on, 2.5.dp.toPx(), Offset(xc(b.hour), y(b.mean)))
        }
        listOf("200" to 0f, "120" to 0.5f, "40" to 1f).forEach { (v, f) ->
            val t = measurer.measure(v, axis)
            drawText(t, topLeft = Offset(size.width - t.size.width, chartTop + chartH * f - t.size.height / 2f))
        }
        listOf(0, 6, 12, 18, 24).forEach { h ->
            val t = measurer.measure("%02d".format(h), axis)
            val left = when (h) {
                0 -> 0f
                24 -> w - t.size.width
                else -> h / 24f * w - t.size.width / 2f
            }
            drawText(t, topLeft = Offset(left, chartTop + chartH + 6.dp.toPx()))
        }
    }
}

/** Seven days of totals, today's so far in full ink, over a usual whole day's band. */
@Composable
private fun WeekBars(day: ThroughDay) {
    val on = MaterialTheme.colorScheme.onSurface
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val s = PawseTheme.semantics
    val top = niceTop(maxOf(day.week.maxOf { it.second }, day.usualHigh.lastOrNull() ?: 0.0) * 1.05)
    Canvas(Modifier.padding(top = 8.dp).fillMaxWidth().height(100.dp)) {
        val base = 96.dp.toPx()
        val tall = 92.dp.toPx()
        val cw = size.width / 7f
        if (day.usualHigh.isNotEmpty()) {
            val hi = base - (day.usualHigh[24] / top).toFloat() * tall
            val lo = base - (day.usualLow[24] / top).toFloat() * tall
            drawRoundRect(on.copy(alpha = 0.08f), Offset(0f, hi), Size(size.width, lo - hi), CornerRadius(4.dp.toPx()))
        }
        drawLine(s.glassLine, Offset(0f, base), Offset(size.width, base), 1.dp.toPx())
        day.week.forEachIndexed { i, (_, v) ->
            // Never under 2 dp, so a near-empty morning still shows.
            val h = ((v / top).toFloat() * tall).coerceAtLeast(2.dp.toPx())
            drawRoundRect(
                if (i == day.week.lastIndex) on else grey,
                Offset(cw * (i + 0.5f) - 11.dp.toPx(), base - h),
                Size(22.dp.toPx(), h),
                CornerRadius(4.dp.toPx().coerceAtMost(h / 2f)),
            )
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        day.week.forEachIndexed { i, (d, v) ->
            val today = i == day.week.lastIndex
            Column(
                Modifier.weight(1f).semantics(mergeDescendants = true) {},
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(count(v), style = PawseType.Micro.copy(fontWeight = PawseType.BodyStrong.fontWeight), color = if (today) on else grey, textAlign = TextAlign.Center)
                Text(
                    if (today) tr("Today", "오늘") else d.dayOfWeek.getDisplayName(DayStyle.SHORT, Locale.getDefault()),
                    style = PawseType.Micro,
                    color = s.faint,
                )
            }
        }
    }
}
