package app.pawse.feature.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pawse.core.data.health.CapabilityProbe
import app.pawse.core.data.scoring.CurvePoint
import app.pawse.core.data.scoring.DailyInputAssembler
import app.pawse.core.data.scoring.NightDetail
import app.pawse.core.data.scoring.NightRecord
import app.pawse.core.data.scoring.NightStage
import app.pawse.core.data.scoring.NightSummary
import app.pawse.core.data.scoring.ScoringSnapshot
import app.pawse.core.data.scoring.StageSpan
import app.pawse.core.ui.component.BackLink
import app.pawse.core.ui.component.Glyph
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.KeySwatch
import app.pawse.core.ui.component.PawseCard
import app.pawse.core.ui.component.Segmented
import app.pawse.core.ui.component.UsualRangeKey
import app.pawse.core.ui.component.cardSurface
import app.pawse.core.ui.component.pressable
import app.pawse.core.ui.component.rememberSwap
import app.pawse.core.ui.component.swapIn
import app.pawse.core.ui.theme.PawseTheme
import app.pawse.core.ui.theme.PawseType
import app.pawse.scoring.config.ScoringConfig
import app.pawse.scoring.model.DayReport
import app.pawse.scoring.model.IllnessLevel
import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.SleepScoreParts
import app.pawse.scoring.tr
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A figure on the Sleep tab, and the trend it opens in place. */
internal enum class NightFigure { ASLEEP, TIMING, SCORE, BODY, HR, HRV, SPO2, BR, TEMP }

private val RANGES = listOf(7, 30, 90)

/** Your usual: the 60 nights ending a week ago, the window Body's baseline uses. */
private const val USUAL_END = 67
private const val USUAL_NIGHTS = 60

private val Big56 = PawseType.Hero.copy(fontSize = 56.sp, lineHeight = 64.sp, letterSpacing = (-2).sp)
private val Big44 = PawseType.Hero.copy(fontSize = 44.sp, lineHeight = 52.sp, letterSpacing = (-1.5).sp)
private val Figure15 = PawseType.BodyStrong.copy(fontSize = 15.sp, lineHeight = 20.sp)

/**
 * The Sleep tab: the night the date strip picked, in full, and the trend behind
 * any of its figures, opened in place. Every figure is the stored one; only the
 * stage chart and the curves come from [detail], read when the night is opened.
 */
@Composable
internal fun SleepTab(
    snapshot: ScoringSnapshot,
    back: Int,
    detail: NightDetail?,
    onOpenNight: (Int) -> Unit,
    onBody: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A new night starts clean: no trend open, no stage picked, no cursor.
    var figure by rememberSaveable(back) { mutableStateOf<NightFigure?>(null) }
    var range by rememberSaveable(back) { mutableIntStateOf(30) }
    var picked by rememberSaveable(back) { mutableIntStateOf(back) }
    var stage by rememberSaveable(back) { mutableStateOf<NightStage?>(null) }
    var cursor by remember(back) { mutableStateOf<Float?>(null) }
    val swap = rememberSwap(figure?.let { "$it$range" })
    val night = snapshot.row(back)

    Column(modifier.padding(top = 16.dp)) {
        Column(Modifier.swapIn(swap)) {
            val open = figure
            if (open == null) {
                NightPage(
                    snapshot = snapshot,
                    row = night,
                    latest = back == 0,
                    detail = detail,
                    stage = stage,
                    onStage = { stage = it },
                    cursor = cursor,
                    onCursor = { cursor = it },
                    onOpen = { figure = it; picked = back },
                )
            } else {
                Trend(
                    snapshot = snapshot,
                    figure = open,
                    range = range,
                    onRange = { range = it },
                    sel = picked.takeIf { it < range } ?: back.takeIf { it < range } ?: 0,
                    onSel = { picked = it },
                    page = night,
                    pageBack = back,
                    onClose = { figure = null },
                    onOpenNight = onOpenNight,
                    onBody = onBody,
                )
            }
        }
    }
}

// --- The night ---------------------------------------------------------------

@Composable
private fun NightPage(
    snapshot: ScoringSnapshot,
    row: NightRow,
    latest: Boolean,
    detail: NightDetail?,
    stage: NightStage?,
    onStage: (NightStage?) -> Unit,
    cursor: Float?,
    onCursor: (Float?) -> Unit,
    onOpen: (NightFigure) -> Unit,
) {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    Text(nightTitle(row.date, latest), style = PawseType.Title)
    Text(nightRange(row.date), style = PawseType.Label, color = grey)

    val rec = row.rec
    val night = rec?.sleep
    val start = night?.startEpochMs
    val end = night?.endEpochMs
    val bed = night?.bedtimeMinutes
    if (rec == null || night == null || start == null || end == null || bed == null || end <= start) {
        Note(
            tr("No night recorded: nothing came in from your watch that night.", "기록된 밤이 없어요. 그날 밤 시계에서 들어온 기록이 없어요."),
            Modifier.padding(top = 12.dp),
        )
        return
    }
    val sleep = row.day?.sleep
    val need = (sleep?.needHours ?: ScoringConfig.DEFAULT.sleep.defaultNeedHours) * 60.0
    val axis = NightAxis(start, end, bed)

    AsleepCard(night.asleepMinutes, need, sleep?.debtHours, onClick = { onOpen(NightFigure.ASLEEP) })
    Row(Modifier.padding(top = 8.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MiniCard(
            label = tr("In bed", "누운 시각"),
            value = ScoreCopy.minuteOfDay(bed.roundToInt()),
            sub = tr("to ${axis.clockAt(end)}", "~ ${axis.clockAt(end)}"),
            modifier = Modifier.weight(1f),
            onClick = { onOpen(NightFigure.TIMING) },
        )
        MiniCard(
            label = tr("Fell asleep", "잠들기까지"),
            value = night.latencyMinutes?.let(::dur) ?: "—",
            sub = tr("after lights out", "불 끈 뒤"),
            modifier = Modifier.weight(1f),
        )
        MiniCard(
            label = tr("Awake", "깬 시간"),
            value = (sleep?.wasoMinutes ?: night.wasoMinutes)?.let(::dur) ?: "—",
            sub = wakeUps(night.awakenings),
            modifier = Modifier.weight(1f),
        )
    }

    // Through the night: the stage chart and every curve on one time axis, one cursor.
    Row(Modifier.padding(top = 32.dp).fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(tr("Through the night", "밤사이"), style = PawseType.Heading, modifier = Modifier.weight(1f))
        if (cursor != null) {
            val t = axis.timeAt(cursor)
            val at = detail?.spans?.firstOrNull { t >= it.startEpochMs && t < it.endEpochMs }?.stage
            val clock = axis.clockAt(t)
            Row(
                Modifier
                    .height(36.dp)
                    .pressable { onCursor(null) }
                    .semantics { contentDescription = tr("Clear the reading at $clock", "$clock 읽기 지우기") }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(listOfNotNull(clock, at?.let { stageName(it, sleep = true) }).joinToString(" · "), style = PawseType.BodyStrong)
                Spacer(Modifier.width(6.dp))
                Glyph(Glyphs.Close, size = 16.dp, color = grey)
            }
        } else {
            Text(tr("Touch a chart to read a moment", "차트를 눌러 그 순간을 읽어요"), style = PawseType.Label, color = grey)
        }
    }
    Hypnogram(
        spans = detail?.spans.orEmpty(),
        axis = axis,
        staged = night.hasStages,
        stage = stage,
        cursor = cursor,
        onCursor = onCursor,
        description = if (night.hasStages) {
            tr(
                "Stages through the night: deep ${hm(night.deepMinutes ?: 0.0)}, light ${hm(night.lightMinutes ?: 0.0)}, " +
                    "REM ${hm(night.remMinutes ?: 0.0)}, awake ${dur(night.inBedMinutes - night.asleepMinutes)}.",
                "밤사이 수면 단계: 깊은 잠 ${hm(night.deepMinutes ?: 0.0)}, 얕은 잠 ${hm(night.lightMinutes ?: 0.0)}, " +
                    "렘수면 ${hm(night.remMinutes ?: 0.0)}, 깸 ${dur(night.inBedMinutes - night.asleepMinutes)}.",
            )
        } else {
            tr("Asleep and awake through the night, without stages.", "단계 없이 잠듦과 깸만 있는 밤이에요.")
        },
    )
    Ticks(axis)

    if (night.hasStages) {
        Row(Modifier.padding(top = 16.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(NightStage.AWAKE, NightStage.REM, NightStage.LIGHT, NightStage.DEEP).forEach { s ->
                StageChip(s, night, picked = stage == s, modifier = Modifier.weight(1f)) { onStage(if (stage == s) null else s) }
            }
        }
        val stageSwap = rememberSwap(stage)
        if (stage != null) {
            Text(stageLine(snapshot, night, stage), style = PawseType.Body, modifier = Modifier.padding(top = 10.dp).swapIn(stageSwap))
        }
        Note(
            tr(
                "Stages are shown, never scored. Wrist staging is the least reliable thing a watch measures.",
                "수면 단계는 보여주기만 하고 점수에는 넣지 않아요. 손목으로 재는 수면 단계는 시계가 측정하는 것 중 가장 부정확해요.",
            ),
            Modifier.padding(top = 10.dp),
        )
    } else {
        Note(
            tr(
                "Your sleep app wrote this night as asleep and awake only, without stages. Time asleep, timing and " +
                    "continuity still count; the score never used stages.",
                "수면 앱이 이 밤을 단계 없이 잠듦과 깸으로만 기록했어요. 수면 시간, 시각, 연속성은 그대로 반영되고, " +
                    "점수는 원래 단계를 쓰지 않아요.",
            ),
            Modifier.padding(top = 12.dp),
        )
    }

    Column(Modifier.padding(top = 20.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        vitalsOf(rec, detail, axis).forEach { VitalRow(it, axis, cursor, onCursor, onOpen) }
    }

    FedInto(row, rec, latest, onOpen)

    Column(Modifier.padding(top = 28.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))
        DetailLine(tr("Written by", "기록한 앱"), writer(rec.source))
        DetailLine(
            tr("Read", "읽은 때"),
            snapshot.lastSyncEpochMs.takeIf { latest && it > 0 }
                ?.let { tr("From Health Connect at ${ScoreCopy.clock(it)}", "${ScoreCopy.clock(it)}에 헬스 커넥트에서") }
                ?: tr("From Health Connect", "헬스 커넥트에서"),
        )
        DetailLine(
            tr("Night quality", "밤 기록 품질"),
            rec.hrCoverage?.takeIf { it > 0.0 }
                ?.let { tr("Heart rate covered ${pct(it)} of the night", "심박수가 밤의 ${pct(it)}를 덮었어요") }
                ?: pct(ScoringConfig.DEFAULT.signal.unknownCoverage).let {
                    tr("No heart-rate series, so this night counts at $it trust", "심박수 기록이 없어서 이 밤은 $it 만큼만 믿어요")
                },
        )
        Note(
            tr(
                "The heart-rate and skin-temperature curves are read from Health Connect when you open a night; " +
                    "Pawse keeps only the night's figures for them.",
                "심박수와 피부 온도 곡선은 밤을 열 때 헬스 커넥트에서 읽어요. Pawse는 그 밤의 수치만 보관해요.",
            ),
            Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun AsleepCard(asleep: Double, need: Double, debtHours: Double?, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val grey = scheme.onSurfaceVariant
    val total = asleep.roundToInt()
    val short = need - asleep
    PawseCard(modifier = Modifier.padding(top = 20.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Time asleep", "수면 시간"), style = PawseType.Label, color = grey, modifier = Modifier.weight(1f))
            Text(tr("Trend", "추세"), style = PawseType.Label, color = grey)
            Glyph(Glyphs.ChevronRight, size = 16.dp, color = grey)
        }
        Row(Modifier.padding(top = 2.dp)) {
            Text("${total / 60}", style = Big56, modifier = Modifier.alignByBaseline())
            Text(tr("h", "시간"), style = PawseType.Heading, color = grey, modifier = Modifier.alignByBaseline().padding(start = 4.dp))
            Text(
                tr(String.format(Locale.getDefault(), "%02d", total % 60), "${total % 60}"),
                style = Big56,
                modifier = Modifier.alignByBaseline().padding(start = 10.dp),
            )
            // Korean names the minutes too: 7시간 24분.
            val minuteUnit = tr("", "분")
            if (minuteUnit.isNotEmpty()) {
                Text(minuteUnit, style = PawseType.Heading, color = grey, modifier = Modifier.alignByBaseline().padding(start = 4.dp))
            }
        }
        Text(needLine(asleep, need) + debtLine(debtHours), style = PawseType.Body, color = grey)
        Row(Modifier.padding(top = 12.dp).fillMaxWidth().height(8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(
                Modifier
                    .weight(min(asleep, need).coerceAtLeast(1.0).toFloat())
                    .fillMaxHeight()
                    .background(scheme.outline, if (short > 0) RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp) else RoundedCornerShape(4.dp)),
            )
            if (short > 0) {
                Box(
                    Modifier
                        .weight(short.toFloat())
                        .fillMaxHeight()
                        .background(scheme.onSurface, RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)),
                )
            }
        }
        Row(Modifier.padding(top = 8.dp)) {
            Text(tr("Slept ${hm(asleep)}", "${hm(asleep)} 잠"), style = PawseType.Label, color = grey, modifier = Modifier.weight(1f))
            Text(tr("Need ${hm(need)}", "필요 ${hm(need)}"), style = PawseType.Label, color = grey)
        }
    }
}

@Composable
private fun MiniCard(label: String, value: String, sub: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier
            .fillMaxHeight()
            .then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier)
            .cardSurface(RoundedCornerShape(14.dp), 14.dp)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = PawseType.Label, color = grey, maxLines = 1, modifier = Modifier.weight(1f))
            if (onClick != null) Glyph(Glyphs.ChevronRight, size = 14.dp, color = grey)
        }
        Text(value, style = Figure15, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        Text(sub, style = PawseType.Label, color = grey)
    }
}

/** The stage chart: one lane per stage, linked where the night moves between them. */
@Composable
private fun Hypnogram(
    spans: List<StageSpan>,
    axis: NightAxis,
    staged: Boolean,
    stage: NightStage?,
    cursor: Float?,
    onCursor: (Float?) -> Unit,
    description: String,
) {
    val lanes = if (staged) listOf(NightStage.AWAKE, NightStage.REM, NightStage.LIGHT, NightStage.DEEP) else listOf(NightStage.AWAKE, NightStage.ASLEEP)
    val height = (lanes.size * LANE_STEP - (LANE_STEP - LANE_HEIGHT)).dp
    val fills = stageColors()
    val dims = NightStage.entries.associateWith { s ->
        animateFloatAsState(if (stage == null || stage == s) 1f else 0.22f, tween(200), label = "stage").value
    }
    val link = MaterialTheme.colorScheme.outline
    val mark = MaterialTheme.colorScheme.onSurface
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.padding(top = 8.dp)) {
        Box(Modifier.width(44.dp).height(height)) {
            lanes.forEachIndexed { i, s ->
                Text(stageName(s), style = PawseType.Label, color = grey, maxLines = 1, modifier = Modifier.pinned(0.dp, (i * LANE_STEP + LANE_HEIGHT / 2).dp, centreX = false))
            }
        }
        Canvas(
            Modifier
                .weight(1f)
                .height(height)
                .scrub(onCursor)
                .semantics { contentDescription = description },
        ) {
            val laneH = LANE_HEIGHT.dp.toPx()
            val step = LANE_STEP.dp.toPx()
            fun x(t: Long) = axis.frac(t).coerceIn(0f, 1f) * size.width
            fun top(s: NightStage) = lanes.indexOf(s) * step
            spans.zipWithNext().forEach { (a, b) ->
                if (a.stage in lanes && b.stage in lanes) {
                    drawLine(link, Offset(x(b.startEpochMs), top(a.stage) + laneH / 2), Offset(x(b.startEpochMs), top(b.stage) + laneH / 2), 1.dp.toPx())
                }
            }
            spans.forEach { s ->
                if (s.stage !in lanes) return@forEach
                val left = x(s.startEpochMs)
                drawRoundRect(
                    fills.getValue(s.stage),
                    Offset(left, top(s.stage)),
                    Size(max(1.dp.toPx(), x(s.endEpochMs) - left), laneH),
                    CornerRadius(4.dp.toPx()),
                    alpha = dims.getValue(s.stage),
                )
            }
            cursor?.let { c ->
                drawRoundRect(mark, Offset(c * size.width - 1.dp.toPx(), -4.dp.toPx()), Size(2.dp.toPx(), size.height + 8.dp.toPx()), CornerRadius(1.dp.toPx()))
            }
        }
    }
}

/** Every two hours on the clock, under the stage chart. */
@Composable
private fun Ticks(axis: NightAxis) {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    BoxWithConstraints(Modifier.padding(start = 44.dp, top = 8.dp).fillMaxWidth().height(18.dp)) {
        val width = maxWidth
        var m = ceil(axis.bedMinute / 120.0) * 120.0
        while (m < axis.bedMinute + axis.minutes) {
            val x = width * ((m - axis.bedMinute) / axis.minutes).toFloat()
            if (x > 18.dp && x < width - 18.dp) {
                Text(ScoreCopy.minuteOfDay(m.roundToInt()), style = PawseType.Label, color = grey, modifier = Modifier.pinned(x, 9.dp))
            }
            m += 120.0
        }
    }
}

@Composable
private fun StageChip(stage: NightStage, night: NightSummary, picked: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val minutes = stageMinutes(night, stage) ?: 0.0
    Column(
        modifier
            .fillMaxHeight()
            .pressable(onClick = onClick)
            .background(if (picked) scheme.surfaceContainerHigh else scheme.surfaceContainer, shape)
            .border(1.dp, if (picked) scheme.outline else scheme.outlineVariant, shape)
            .semantics { selected = picked }
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        KeySwatch(stageColors().getValue(stage), stageName(stage))
        Text(dur(minutes), style = Figure15, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
        Text(
            if (stage == NightStage.AWAKE) tr("in bed", "누운 동안") else "${(100 * minutes / night.asleepMinutes).roundToInt()}%",
            style = PawseType.Label,
            color = scheme.onSurfaceVariant,
        )
    }
}

private fun stageLine(snapshot: ScoringSnapshot, night: NightSummary, stage: NightStage): String {
    val minutes = stageMinutes(night, stage) ?: 0.0
    val text: (Double) -> String = if (stage == NightStage.AWAKE) ::dur else ::hm
    val usual = snapshot.usual { r -> r.rec?.sleep?.let { stageMinutes(it, stage) } }
    val usually = usual?.let { tr(" Usually ${text(it.lo)}–${text(it.hi)}.", " 평소에는 ${text(it.lo)}~${text(it.hi)}예요.") }.orEmpty()
    val line = if (stage == NightStage.AWAKE) {
        val latency = night.latencyMinutes?.let(::dur) ?: "—"
        val waso = night.wasoMinutes?.let(::dur) ?: "—"
        tr(
            "Awake ${dur(minutes)} in bed: $latency falling asleep and $waso in the night.",
            "누운 동안 ${dur(minutes)} 깨어 있었어요. 잠들기까지 $latency, 밤사이 ${waso}예요.",
        )
    } else {
        val share = (100 * minutes / night.asleepMinutes).roundToInt()
        tr("${stageName(stage)} ${hm(minutes)}, $share% of your time asleep.", "${stageName(stage)} ${hm(minutes)}, 수면 시간의 ${share}%예요.")
    }
    return line + usually
}

// --- The curves --------------------------------------------------------------

private enum class Trace { LINE, STICKS, DOTS }

/** One overnight measure: its stored figure, its curve, and how the curve is drawn. */
private class Vital(
    val figure: NightFigure,
    val name: String,
    val value: Double?,
    val curve: List<CurvePoint>,
    val sub: String,
    val off: String,
    val height: Dp,
    val trace: Trace,
    val lo: Double,
    val hi: Double,
    val top: String,
    val bottom: String,
    val ref: Double? = null,
    val window: Pair<Long, Long>? = null,
)

private fun vitalsOf(rec: NightRecord, detail: NightDetail?, axis: NightAxis): List<Vital> {
    val s = rec.signals
    val notWritten = tr("No device wrote it to Health Connect.", "헬스 커넥트에 기록한 기기가 없어요.")
    val median = tr("Median of the night", "밤사이 중앙값")

    val hr = detail?.heartRate.orEmpty()
    val half = ScoringConfig.DEFAULT.signal.hrWindowMinutes * 30_000L
    val window = s.hrNadirFraction?.let { f -> axis.timeAt(f.toFloat()).let { it - half to it + half } }
    val hrLo = floor(hr.minOfOrNull { it.value } ?: 0.0) - 1
    val hrHi = ceil(hr.maxOfOrNull { it.value } ?: 0.0) + 1

    val hrv = detail?.hrv.orEmpty()
    val hrvHi = (hrv.maxOfOrNull { it.value } ?: 0.0) * 1.05

    val spo2 = detail?.spo2.orEmpty()
    val spo2Min = spo2.minOfOrNull { it.value }
    val spo2Lo = min(90.0, floor(spo2Min ?: 90.0))

    val br = detail?.breathing.orEmpty()
    val brLo = floor((br.minOfOrNull { it.value } ?: 0.0) - 0.3)
    val brHi = ceil((br.maxOfOrNull { it.value } ?: 0.0) + 0.3)

    val temp = detail?.skinTemp.orEmpty()
    val tempM = max(0.5, ceil((temp.maxOfOrNull { abs(it.value) } ?: 0.0) * 10) / 10 + 0.1)
    val tempLabel = String.format(Locale.getDefault(), "%.1f", tempM)

    return listOf(
        Vital(
            figure = NightFigure.HR,
            name = tr("Heart rate", "심박수"),
            value = s.sleepingHr?.value,
            curve = hr,
            sub = window?.let { (a, b) -> tr("Lowest 30 minutes, ${axis.clockAt(a)}–${axis.clockAt(b)}", "가장 낮은 30분, ${axis.clockAt(a)}–${axis.clockAt(b)}") }
                ?: if (s.sleepingHr?.source?.endsWith(DailyInputAssembler.RESTING) == true) {
                    tr("Your watch's resting heart rate", "시계의 안정 시 심박수")
                } else {
                    tr("Sleeping heart rate", "수면 중 심박수")
                },
            off = tr(
                "No heart rate came in, so there is no sleeping heart rate and no Body reading from this night.",
                "심박수가 들어오지 않아서 이 밤에는 수면 중 심박수도, 컨디션 값도 없어요.",
            ),
            height = 64.dp,
            trace = Trace.LINE,
            lo = hrLo,
            hi = hrHi,
            top = "${hrHi.roundToInt()}",
            bottom = "${hrLo.roundToInt()}",
            window = window,
        ),
        Vital(
            figure = NightFigure.HRV,
            name = tr("Heart rate variability", "심박 변이도"),
            value = rec.hrvMs,
            curve = hrv,
            sub = if (hrv.isEmpty()) median else tr("Median through the night, dashed", "밤사이 중앙값, 점선"),
            off = notWritten,
            height = 56.dp,
            trace = Trace.STICKS,
            lo = 0.0,
            hi = hrvHi,
            top = "${hrvHi.roundToInt()}",
            bottom = "0",
            ref = rec.hrvMs,
        ),
        Vital(
            figure = NightFigure.SPO2,
            name = tr("Blood oxygen", "혈중 산소"),
            value = s.spo2,
            curve = spo2,
            sub = spo2Min?.let { tr("Median, lowest ${it.roundToInt()}%", "중앙값, 최저 ${it.roundToInt()}%") } ?: median,
            off = notWritten,
            height = 48.dp,
            trace = Trace.LINE,
            lo = spo2Lo,
            hi = 100.0,
            top = "100",
            bottom = "${spo2Lo.roundToInt()}",
        ),
        Vital(
            figure = NightFigure.BR,
            name = tr("Breathing rate", "호흡수"),
            value = s.breathingRate,
            curve = br,
            sub = if (br.isEmpty()) median else tr("Median of ${br.size} readings", "측정값 ${br.size}개의 중앙값"),
            off = notWritten,
            height = 44.dp,
            trace = Trace.DOTS,
            lo = brLo,
            hi = brHi,
            top = "${brHi.roundToInt()}",
            bottom = "${brLo.roundToInt()}",
        ),
        Vital(
            figure = NightFigure.TEMP,
            name = tr("Skin temperature", "피부 온도"),
            value = s.skinTempDelta,
            curve = temp,
            sub = tr("Against the device's own baseline", "기기 자체 기준 대비"),
            off = notWritten,
            height = 40.dp,
            trace = Trace.LINE,
            lo = -tempM,
            hi = tempM,
            top = "+$tempLabel",
            bottom = "−$tempLabel",
            ref = 0.0,
        ),
    )
}

@Composable
private fun VitalRow(v: Vital, axis: NightAxis, cursor: Float?, onCursor: (Float?) -> Unit, onOpen: (NightFigure) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val grey = scheme.onSurfaceVariant
    val semantics = PawseTheme.semantics
    val on = v.value != null || v.curve.isNotEmpty()
    val t = cursor?.let(axis::timeAt)
    val atCursor = t?.let { v.curve.minByOrNull { p -> abs(p.epochMs - t) } }
    val shown = (atCursor?.value ?: v.value)?.let { v.figure.text(it) } ?: "—"
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(enabled = on) { onOpen(v.figure) }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(v.name, style = PawseType.Body)
                Text(
                    when {
                        !on -> v.off
                        atCursor != null && t != null -> tr("At ${axis.clockAt(t)}", "${axis.clockAt(t)} 시점")
                        else -> v.sub
                    },
                    style = PawseType.Label,
                    color = grey,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(shown, style = PawseType.MetricValue, color = if (on) scheme.onSurface else grey)
            Spacer(Modifier.width(12.dp))
            Glyph(Glyphs.ChevronRight, size = 16.dp, color = grey, modifier = Modifier.alpha(if (on) 1f else 0f))
        }
        if (v.curve.isNotEmpty() && v.hi > v.lo) {
            Row(Modifier.padding(top = 2.dp, bottom = 16.dp)) {
                Box(Modifier.width(44.dp).height(v.height)) {
                    Text(v.top, style = PawseType.Label, color = grey, modifier = Modifier.pinned(0.dp, 5.dp, centreX = false))
                    Text(v.bottom, style = PawseType.Label, color = grey, modifier = Modifier.pinned(0.dp, v.height - 5.dp, centreX = false))
                }
                Canvas(
                    Modifier
                        .weight(1f)
                        .height(v.height)
                        .scrub(onCursor)
                        .semantics { contentDescription = "${v.name}, ${v.sub}: $shown" },
                ) {
                    val pad = 3.dp.toPx()
                    fun x(at: Long) = axis.frac(at).coerceIn(0f, 1f) * size.width
                    fun y(value: Double) = ((size.height - pad) - (value - v.lo) / (v.hi - v.lo) * (size.height - 2 * pad)).toFloat()
                    drawRoundRect(semantics.baselineOuter, cornerRadius = CornerRadius(4.dp.toPx()))
                    v.window?.let { (a, b) ->
                        drawRoundRect(semantics.baselineBand, Offset(x(a), 0f), Size(x(b) - x(a), size.height), CornerRadius(4.dp.toPx()))
                    }
                    v.ref?.let {
                        drawLine(grey, Offset(0f, y(it)), Offset(size.width, y(it)), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())))
                    }
                    when (v.trace) {
                        Trace.STICKS -> v.curve.forEach { p ->
                            drawLine(grey.copy(alpha = 0.75f), Offset(x(p.epochMs), size.height - pad), Offset(x(p.epochMs), y(p.value)), 2.dp.toPx(), cap = StrokeCap.Round)
                        }
                        Trace.DOTS -> v.curve.forEach { p -> drawCircle(grey, 2.dp.toPx(), Offset(x(p.epochMs), y(p.value))) }
                        Trace.LINE -> {
                            val path = Path()
                            v.curve.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.epochMs), y(p.value)) else path.lineTo(x(p.epochMs), y(p.value)) }
                            drawPath(path, grey, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                    if (cursor != null && atCursor != null) {
                        val cx = cursor * size.width
                        drawLine(scheme.onSurface.copy(alpha = 0.5f), Offset(cx, -4.dp.toPx()), Offset(cx, size.height + 4.dp.toPx()), 1.dp.toPx())
                        drawCircle(scheme.background, 6.dp.toPx(), Offset(cx, y(atCursor.value)))
                        drawCircle(scheme.onSurface, 4.dp.toPx(), Offset(cx, y(atCursor.value)))
                    }
                }
            }
        }
        HorizontalDivider(color = scheme.outlineVariant)
    }
}

// --- What the night fed into ---------------------------------------------------

@Composable
private fun FedInto(row: NightRow, rec: NightRecord, latest: Boolean, onOpen: (NightFigure) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val grey = scheme.onSurfaceVariant
    val sleep = row.day?.sleep
    val config = ScoringConfig.DEFAULT.sleep
    Text(tr("What this night fed into", "이 밤이 반영된 곳"), style = PawseType.Label, color = grey, modifier = Modifier.padding(top = 32.dp, bottom = 10.dp))
    PawseCard(onClick = { onOpen(NightFigure.SCORE) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Sleep score", "수면 점수"), style = PawseType.BodyStrong, modifier = Modifier.weight(1f))
            Text(sleep?.score?.toString() ?: "—", style = PawseType.NumeralSmall)
            Spacer(Modifier.width(8.dp))
            Glyph(Glyphs.ChevronRight, size = 16.dp, color = grey)
        }
        val parts = sleep?.parts
        listOf(
            Triple(tr("Duration vs need", "필요 대비 수면 시간") + " · " + hm(rec.sleep.asleepMinutes), parts?.duration, config.scoreDurationWeight),
            Triple(tr("Regularity", "규칙성") + " · " + (sleep?.sri?.roundToInt()?.toString() ?: "—"), parts?.regularity, config.scoreRegularityWeight),
            Triple(
                tr("Continuity", "연속성") + " · " + (rec.sleep.efficiencyPercent?.let { tr("${it.roundToInt()}% asleep", "효율 ${it.roundToInt()}%") } ?: "—"),
                parts?.continuity,
                config.scoreContinuityWeight,
            ),
        ).forEach { (name, part, weight) ->
            Row(Modifier.padding(top = 12.dp)) {
                Text(name, style = PawseType.Label, color = grey, modifier = Modifier.weight(1f))
                Text(part?.let { "${(it * 100).roundToInt()}" } ?: "—", style = PawseType.Label)
                Text(" · ${pct(weight)}", style = PawseType.Label, color = grey)
            }
            Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp).background(PawseTheme.semantics.baselineOuter, RoundedCornerShape(3.dp))) {
                Box(Modifier.fillMaxWidth((part ?: 0.0).toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(grey, RoundedCornerShape(3.dp)))
            }
        }
        Note(tr("Optional, for people who want one number. Stages get no weight.", "숫자 하나를 원하는 분을 위한 선택 항목이에요. 수면 단계는 반영하지 않아요."), Modifier.padding(top = 12.dp))
    }

    val day = row.day
    val body = day?.body
    PawseCard(modifier = Modifier.padding(top = 10.dp), onClick = body?.let { { onOpen(NightFigure.BODY) } }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (latest) tr("Body this morning", "오늘 아침 컨디션") else tr("Body that morning", "그날 아침 컨디션"),
                style = PawseType.BodyStrong,
                modifier = Modifier.weight(1f),
            )
            val dot = bodyColor(body?.band, day?.illness?.level ?: IllnessLevel.NONE)
            Canvas(Modifier.size(8.dp)) { drawCircle(dot) }
            Spacer(Modifier.width(6.dp))
            Text(body?.band?.let(ScoreCopy::bandWord) ?: tr("No reading", "값 없음"), style = PawseType.BodyStrong)
            Spacer(Modifier.width(8.dp))
            Glyph(Glyphs.ChevronRight, size = 16.dp, color = grey, modifier = Modifier.alpha(if (body != null) 1f else 0f))
        }
        val signals = listOfNotNull(
            body?.hrv?.tonight?.let { tr("HRV ${it.roundToInt()} ms", "HRV ${it.roundToInt()} ms") },
            body?.sleepingHr?.tonight?.let { tr("sleeping heart rate ${it.roundToInt()} bpm", "수면 중 심박수 ${it.roundToInt()} bpm") },
        )
        Text(
            when {
                body != null -> tr(
                    "Body ${body.score}, likely ${body.low}–${body.high}. From this night’s ${signals.joinToString(" and ")}, against your normal.",
                    "컨디션 ${body.score}, 범위 ${body.low}–${body.high}. 이 밤의 ${signals.joinToString(", ")}을 내 평소와 비교했어요.",
                )
                day != null && ScoreCopy.bodyStatus(day.bodyStatus, day.validNights, ScoringConfig.DEFAULT.body.minValidNights).isNotEmpty() ->
                    ScoreCopy.bodyStatus(day.bodyStatus, day.validNights, ScoringConfig.DEFAULT.body.minValidNights)
                else -> tr("No Body reading from this night.", "이 밤에는 컨디션 값이 없어요.")
            },
            style = PawseType.Label,
            color = grey,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

// --- The trend behind a figure ---------------------------------------------------

@Composable
private fun Trend(
    snapshot: ScoringSnapshot,
    figure: NightFigure,
    range: Int,
    onRange: (Int) -> Unit,
    sel: Int,
    onSel: (Int) -> Unit,
    page: NightRow,
    pageBack: Int,
    onClose: () -> Unit,
    onOpenNight: (Int) -> Unit,
    onBody: () -> Unit,
) {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val rows = snapshot.rows(range)
    val selI = range - 1 - sel
    val selRow = rows[selI]
    val usual = snapshot.usual(figure::of)
    val need = (page.day?.sleep?.needHours ?: snapshot.today.sleep.needHours) * 60.0
    val reading = readingOf(figure, selRow, usual, need)

    BackLink(nightTitle(page.date, pageBack == 0), onClick = onClose)
    Text(figure.title(), style = PawseType.Title, modifier = Modifier.padding(top = 12.dp))
    Text(figure.lead(), style = PawseType.Body, color = grey, modifier = Modifier.padding(top = 4.dp))
    Segmented(
        options = RANGES,
        selected = range,
        onSelect = onRange,
        label = { tr("$it nights", "${it}밤") },
        height = 36.dp,
        modifier = Modifier.padding(top = 20.dp),
    )

    Text(
        (if (sel == 0) tr("Last night · ", "지난밤 · ") else "") + nightRange(selRow.date),
        style = PawseType.Label,
        color = grey,
        modifier = Modifier.padding(top = 20.dp),
    )
    Row(Modifier.padding(top = 2.dp)) {
        Text(reading.value, style = Big44, modifier = Modifier.alignByBaseline())
        if (reading.unit.isNotEmpty()) {
            Text(reading.unit, style = PawseType.Heading, color = grey, modifier = Modifier.alignByBaseline().padding(start = 6.dp))
        }
        val day = selRow.day
        val body = day?.body
        if (figure == NightFigure.BODY && day != null && body != null) {
            val dot = bodyColor(body.band, day.illness.level)
            Row(Modifier.alignByBaseline().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(8.dp)) { drawCircle(dot) }
                Spacer(Modifier.width(6.dp))
                Text(ScoreCopy.bandWord(body.band), style = PawseType.BodyStrong)
            }
        }
    }
    Text(reading.note, style = PawseType.Body, color = grey)

    TrendChart(figure, rows, selI, usual, snapshot.wakeUsual().takeIf { figure == NightFigure.TIMING }, need, onPick = { onSel(range - 1 - it) })
    Row(Modifier.padding(top = 8.dp, end = 36.dp).fillMaxWidth()) {
        Text(nightLabel(rows.first().date), style = PawseType.Label, color = grey, modifier = Modifier.weight(1f))
        Text(tr("Last night", "지난밤"), style = PawseType.Label, color = grey)
    }
    TrendLegend(figure, rows, need)

    Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        statsOf(snapshot, figure, rows, selRow, usual).chunked(2).forEach { pair ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { stat ->
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .cardSurface(RoundedCornerShape(14.dp), 14.dp)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Text(stat.key, style = PawseType.Label, color = grey)
                        Text(stat.value, style = PawseType.MetricValue, modifier = Modifier.padding(top = 2.dp))
                        Text(stat.note, style = PawseType.Label, color = grey)
                    }
                }
            }
        }
    }

    if (figure == NightFigure.SCORE) Parts(rows, selI, need)

    if (sel != pageBack && selRow.rec != null) {
        LinkCard(
            if (sel == 0) tr("Open last night", "지난밤 열기") else tr("Open the night of ${nightLabel(selRow.date).removeSuffix(" night")}", "${nightLabel(selRow.date)} 열기"),
            Modifier.padding(top = 20.dp),
        ) { onOpenNight(sel) }
    }
    if (figure == NightFigure.BODY) {
        LinkCard(tr("How Body was worked out", "컨디션 계산 방법"), Modifier.padding(top = 12.dp), onBody)
    }

    Text(tr("About this number", "이 숫자에 대해"), style = PawseType.Label, color = grey, modifier = Modifier.padding(top = 28.dp))
    Text(figure.about(), style = PawseType.Body, color = grey, modifier = Modifier.padding(top = 6.dp))
}

/** One bar or dot a night, oldest on the left; touching it picks the night above. */
@Composable
internal fun TrendChart(
    figure: NightFigure,
    rows: List<NightRow>,
    selI: Int,
    usual: Usual?,
    wakeUsual: Usual?,
    need: Double,
    onPick: (Int) -> Unit,
) {
    val s = PawseTheme.semantics
    val scheme = MaterialTheme.colorScheme
    val grey = scheme.onSurfaceVariant
    val n = rows.size
    val vals = rows.map(figure::of)
    val selDot = if (figure == NightFigure.BODY) bodyColor(rows[selI].day?.body?.band) else scheme.onSurface
    val fills = stageColors()

    // Where a value sits, as a share of the plot's height from the top.
    val frac: (Double) -> Float
    val bands = mutableListOf<Pair<Double, Double>>()
    val labels: List<Pair<String, Double>>
    when (figure) {
        NightFigure.ASLEEP -> {
            frac = { (1 - it / 600.0).toFloat() }
            labels = listOf(540.0, 360.0, 180.0).map { tr("${(it / 60).toInt()} h", "${(it / 60).toInt()}시간") to it }
        }
        NightFigure.SCORE -> {
            frac = { (1 - it / 100.0).toFloat() }
            labels = listOf("100" to 100.0, "50" to 50.0)
        }
        NightFigure.TIMING -> {
            frac = { ((it - 1260.0) / 840.0).toFloat() }
            listOfNotNull(usual, wakeUsual).forEach { bands += it.lo to it.hi }
            labels = listOf(1320, 1560, 1800, 2040).map { ScoreCopy.minuteOfDay(it) to it.toDouble() }
        }
        else -> {
            val band = if (figure == NightFigure.BODY) 31.0 to 69.0 else usual?.let { it.lo to it.hi }
            val known = vals.filterNotNull() + listOfNotNull(band?.first, band?.second)
            var lo = if (figure == NightFigure.BODY) 0.0 else known.minOrNull() ?: 0.0
            var hi = if (figure == NightFigure.BODY) 100.0 else known.maxOrNull() ?: 1.0
            if (figure != NightFigure.BODY) {
                val padding = ((hi - lo) * 0.15).takeIf { it > 0.0 } ?: 1.0
                lo -= padding
                hi += padding
            }
            frac = { ((CHART_HEIGHT - 6 - (it - lo) / (hi - lo) * (CHART_HEIGHT - 12)) / CHART_HEIGHT).toFloat() }
            band?.let { bands += it }
            labels = band?.let { (a, b) -> listOf(b, a).map { v -> bare(figure.text(v)) to v } }.orEmpty()
        }
    }

    BoxWithConstraints(Modifier.padding(top = 20.dp).fillMaxWidth().height(CHART_HEIGHT.dp)) {
        val plot = maxWidth - 36.dp
        Canvas(
            Modifier
                .width(plot)
                .fillMaxHeight()
                .scrub { f -> if (f != null) onPick((f * n).toInt().coerceIn(0, n - 1)) }
                .semantics {
                    contentDescription = tr(
                        "${figure.title()}, last $n nights. Touch the chart to pick a night.",
                        "${figure.title()}, 최근 ${n}밤. 차트를 눌러 밤을 골라요.",
                    )
                },
        ) {
            val h = size.height
            val slot = size.width / n
            val bar = max(1.5.dp.toPx(), slot * 0.62f)
            val r2 = CornerRadius(2.dp.toPx())
            fun y(v: Double) = frac(v) * h
            fun cx(i: Int) = i * slot + slot / 2

            drawRoundRect(s.baselineOuter, cornerRadius = CornerRadius(6.dp.toPx()))
            bands.forEach { (a, b) ->
                drawRoundRect(s.baselineBand, Offset(0f, min(y(a), y(b))), Size(size.width, max(2.dp.toPx(), abs(y(a) - y(b)))), CornerRadius(4.dp.toPx()))
            }
            val column = Offset(selI * slot, -6.dp.toPx())
            val columnSize = Size(slot, h + 12.dp.toPx())
            drawRoundRect(scheme.surfaceContainerHigh, column, columnSize, CornerRadius(4.dp.toPx()))
            drawRoundRect(scheme.outline, column, columnSize, CornerRadius(4.dp.toPx()), style = Stroke(1.dp.toPx()))

            when (figure) {
                NightFigure.ASLEEP -> rows.forEachIndexed { i, r ->
                    val night = r.rec?.sleep ?: return@forEachIndexed
                    val stack = if (night.hasStages) {
                        listOf(night.deepMinutes to fills.getValue(NightStage.DEEP), night.lightMinutes to fills.getValue(NightStage.LIGHT), night.remMinutes to fills.getValue(NightStage.REM))
                    } else {
                        listOf(night.asleepMinutes to fills.getValue(NightStage.ASLEEP))
                    }
                    var sum = 0.0
                    stack.forEach { (m, c) ->
                        val minutes = m ?: 0.0
                        if (minutes > 0) drawRect(c, Offset(cx(i) - bar / 2, y(sum + minutes)), Size(bar, y(sum) - y(sum + minutes)))
                        sum += minutes
                    }
                }
                NightFigure.SCORE -> vals.forEachIndexed { i, v ->
                    if (v != null) drawRoundRect(if (i == selI) scheme.onSurface else grey, Offset(cx(i) - bar / 2, y(v)), Size(bar, h - y(v)), r2)
                }
                NightFigure.TIMING -> rows.forEachIndexed { i, r ->
                    val bed = vals[i] ?: return@forEachIndexed
                    val inBed = r.rec?.sleep?.inBedMinutes ?: return@forEachIndexed
                    drawRoundRect(if (i == selI) scheme.onSurface else grey, Offset(cx(i) - bar / 2, y(bed)), Size(bar, y(bed + inBed) - y(bed)), r2)
                }
                else -> {
                    val path = Path()
                    var pen = false
                    vals.forEachIndexed { i, v ->
                        if (v == null) {
                            pen = false
                        } else {
                            if (pen) path.lineTo(cx(i), y(v)) else path.moveTo(cx(i), y(v))
                            pen = true
                        }
                    }
                    drawPath(path, grey, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    if (n <= 30) vals.forEachIndexed { i, v -> if (v != null) drawCircle(grey, 2.5.dp.toPx(), Offset(cx(i), y(v))) }
                    vals[selI]?.let { v ->
                        drawCircle(scheme.background, 8.dp.toPx(), Offset(cx(selI), y(v)))
                        drawCircle(selDot, 5.dp.toPx(), Offset(cx(selI), y(v)))
                    }
                }
            }
            if (figure == NightFigure.ASLEEP) {
                drawLine(scheme.onSurface, Offset(0f, y(need)), Offset(size.width, y(need)), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
            }
        }
        labels.forEach { (text, v) ->
            Text(text, style = PawseType.Label, color = grey, maxLines = 1, modifier = Modifier.pinned(plot + 6.dp, CHART_HEIGHT.dp * frac(v), centreX = false))
        }
    }
}

@Composable
private fun TrendLegend(figure: NightFigure, rows: List<NightRow>, need: Double) {
    val fills = stageColors()
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val on = MaterialTheme.colorScheme.onSurface
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        when (figure) {
            NightFigure.ASLEEP -> {
                if (rows.any { it.rec?.sleep?.hasStages == true }) {
                    listOf(NightStage.DEEP, NightStage.LIGHT, NightStage.REM).forEach { KeySwatch(fills.getValue(it), stageName(it)) }
                } else {
                    KeySwatch(fills.getValue(NightStage.ASLEEP), stageName(NightStage.ASLEEP))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(width = 16.dp, height = 2.dp)) {
                        drawLine(on, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Need ${hm(need)}", "필요 ${hm(need)}"), style = PawseType.Label, color = grey)
                }
            }
            NightFigure.TIMING -> {
                KeySwatch(grey, tr("In bed", "누운 시간"))
                UsualRangeKey(tr("Your usual bedtime and wake", "평소 취침·기상"))
            }
            NightFigure.SCORE -> Unit
            NightFigure.BODY -> UsualRangeKey(tr("Typical, 31–69", "평소 수준, 31–69"))
            else -> UsualRangeKey()
        }
    }
}

/** A part of the Sleep score: its name, its weight, what it measures, and how to read it. */
private class Part(val name: String, val weight: Double, val note: String, val of: (SleepScoreParts) -> Double?)

@Composable
private fun Parts(rows: List<NightRow>, selI: Int, need: Double) {
    val scheme = MaterialTheme.colorScheme
    val grey = scheme.onSurfaceVariant
    val config = ScoringConfig.DEFAULT.sleep
    val parts = listOf(
        Part(
            tr("Duration vs need", "필요 대비 수면 시간"),
            config.scoreDurationWeight,
            tr("Time asleep against your ${hm(need)} need, capped at 100.", "필요 수면 ${hm(need)} 대비 수면 시간이며, 최대 100이에요."),
        ) { it.duration },
        Part(
            tr("Regularity", "규칙성"),
            config.scoreRegularityWeight,
            tr("Sleep Regularity Index over ${config.sriDays} days: 50 scores 0, 90 scores 100.", "${config.sriDays}일 동안의 수면 규칙성 지수예요. 50이면 0점, 90이면 100점이에요."),
        ) { it.regularity },
        Part(
            tr("Continuity", "연속성"),
            config.scoreContinuityWeight,
            tr(
                "Half efficiency against ${config.efficiencyTargetPercent.roundToInt()}%, half time awake against ${config.wasoLimitMinutes.roundToInt()} minutes.",
                "절반은 수면 효율 ${config.efficiencyTargetPercent.roundToInt()}% 대비, 절반은 깬 시간 ${config.wasoLimitMinutes.roundToInt()}분 대비예요.",
            ),
        ) { it.continuity },
    )
    Text(tr("The three parts, same nights", "같은 밤들의 세 항목"), style = PawseType.Label, color = grey, modifier = Modifier.padding(top = 28.dp))
    parts.forEach { part ->
        val values = rows.map { r -> r.day?.sleep?.parts?.let(part.of) }
        val now = values[selI]
        Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(part.name, style = PawseType.Body, modifier = Modifier.weight(1f))
                Text(now?.let { "${(it * 100).roundToInt()}" } ?: "—", style = PawseType.MetricValue)
                Text(pct(part.weight), style = PawseType.Label, color = grey, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 44.dp))
            }
            Canvas(Modifier.padding(top = 8.dp).fillMaxWidth().height(32.dp)) {
                val slot = size.width / values.size
                fun y(p: Double) = ((29 - p * 26) / 32 * size.height).toFloat()
                val path = Path()
                var pen = false
                values.forEachIndexed { i, p ->
                    if (p == null) {
                        pen = false
                    } else {
                        if (pen) path.lineTo(i * slot + slot / 2, y(p)) else path.moveTo(i * slot + slot / 2, y(p))
                        pen = true
                    }
                }
                drawPath(path, grey, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                now?.let { drawCircle(scheme.onSurface, 3.5.dp.toPx(), Offset(selI * slot + slot / 2, y(it))) }
            }
            Text(part.note, style = PawseType.Label, color = grey, modifier = Modifier.padding(top = 6.dp))
        }
        HorizontalDivider(color = scheme.outlineVariant)
    }
}

@Composable
private fun LinkCard(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .pressable(onClick = onClick)
            .cardSurface()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = PawseType.BodyStrong, modifier = Modifier.weight(1f))
        Glyph(Glyphs.ChevronRight, size = 18.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// --- Reading the stored nights -------------------------------------------------

/** One night by wake date: the day the engine scored and the night as stored. Either may be missing. */
internal class NightRow(val date: LocalDate, val day: DayReport?, val rec: NightRecord?)

private fun ScoringSnapshot.row(back: Int): NightRow =
    date.minusDays(back.toLong()).let { d -> NightRow(d, history.lastOrNull { it.date == d }, nights[d]) }

/** The [count] nights ending on the newest, oldest first. */
internal fun ScoringSnapshot.rows(count: Int): List<NightRow> {
    val byDate = history.associateBy { it.date }
    return (count - 1 downTo 0).map { j -> date.minusDays(j.toLong()).let { NightRow(it, byDate[it], nights[it]) } }
}

/** A figure's stored value on one night. Bedtimes after midnight run past 1440 so a night sorts as one. */
internal fun NightFigure.of(r: NightRow): Double? = when (this) {
    NightFigure.ASLEEP -> r.rec?.sleep?.asleepMinutes
    NightFigure.TIMING -> r.rec?.sleep?.bedtimeMinutes?.let { if (it < 720.0) it + 1440.0 else it }
    NightFigure.SCORE -> r.day?.sleep?.score?.toDouble()
    NightFigure.BODY -> r.day?.body?.score?.toDouble()
    NightFigure.HR -> r.rec?.signals?.sleepingHr?.value
    NightFigure.HRV -> r.rec?.hrvMs
    NightFigure.SPO2 -> r.rec?.signals?.spo2
    NightFigure.BR -> r.rec?.signals?.breathingRate
    NightFigure.TEMP -> r.rec?.signals?.skinTempDelta
}

/**
 * Your usual for a figure, mean ± one SD over the 60 nights ending a week ago.
 * Display statistics over stored figures, not a score: nothing here feeds a number
 * the engine printed.
 */
internal class Usual(val mean: Double, val sd: Double, val nights: Int) {
    val lo: Double get() = mean - sd
    val hi: Double get() = mean + sd
}

internal fun ScoringSnapshot.usual(get: (NightRow) -> Double?): Usual? {
    val xs = rows(USUAL_END).take(USUAL_NIGHTS).mapNotNull(get)
    if (xs.size < 2) return null
    val m = xs.average()
    return Usual(m, sqrt(xs.sumOf { (it - m) * (it - m) } / xs.size), xs.size)
}

private fun ScoringSnapshot.wakeUsual(): Usual? =
    usual { r -> NightFigure.TIMING.of(r)?.let { bed -> r.rec?.sleep?.inBedMinutes?.let { bed + it } } }

/** Bedtimes averaged as times on a clock, so 23:30 and 00:30 average to midnight. */
private fun clockMean(minutes: List<Double>): Double? {
    if (minutes.isEmpty()) return null
    val angles = minutes.map { it / 1440.0 * 2 * PI }
    val mean = atan2(angles.sumOf { sin(it) }, angles.sumOf { cos(it) }) / (2 * PI) * 1440.0
    return (mean + 1440.0) % 1440.0
}

private class Reading(val value: String, val unit: String, val note: String)

private fun readingOf(figure: NightFigure, row: NightRow, usual: Usual?, need: Double): Reading {
    val night = row.rec?.sleep
    val sleep = row.day?.sleep
    val sriDays = ScoringConfig.DEFAULT.sleep.sriDays
    val reading = when (figure) {
        NightFigure.ASLEEP -> night?.let {
            val stages = if (it.hasStages) {
                tr(" Deep ${hm(it.deepMinutes ?: 0.0)}, REM ${hm(it.remMinutes ?: 0.0)}.", " 깊은 잠 ${hm(it.deepMinutes ?: 0.0)}, 렘수면 ${hm(it.remMinutes ?: 0.0)}.")
            } else {
                ""
            }
            Reading(hm(it.asleepMinutes), "", needLine(it.asleepMinutes, need) + stages)
        }
        NightFigure.TIMING -> night?.let { n -> n.bedtimeMinutes?.let { bed -> n.inBedMinutes to bed } }?.let { (inBed, bed) ->
            Reading(
                "${ScoreCopy.minuteOfDay(bed.roundToInt())}–${ScoreCopy.minuteOfDay((bed + inBed).roundToInt())}",
                "",
                sleep?.sri?.let {
                    tr(
                        "Regularity ${it.roundToInt()}, ${regularityWord(it).lowercase()}, over the $sriDays days before.",
                        "그전 ${sriDays}일의 규칙성은 ${it.roundToInt()}, ${regularityWord(it)}이에요.",
                    )
                } ?: tr("Regularity needs a week of nights.", "규칙성은 일주일치 밤이 필요해요."),
            )
        }
        NightFigure.SCORE -> sleep?.let { sl -> sl.score?.let { it to sl.parts } }?.let { (score, parts) ->
            Reading(
                "$score",
                tr("of 100", "/ 100"),
                listOf(
                    tr("Duration", "수면 시간") to parts?.duration,
                    tr("Regularity", "규칙성") to parts?.regularity,
                    tr("Continuity", "연속성") to parts?.continuity,
                ).joinToString(" · ") { (k, v) -> "$k ${v?.let { (it * 100).roundToInt() } ?: "—"}" },
            )
        }
        NightFigure.BODY -> row.day?.body?.let { Reading("${it.score}", "", tr("Likely ${it.low}–${it.high}.", "범위 ${it.low}–${it.high}.")) }
        else -> figure.of(row)?.let { v ->
            val text = figure.text(v)
            val note = usual?.let { u ->
                val d = v - u.mean
                val size = if (figure == NightFigure.TEMP) String.format(Locale.getDefault(), "%.2f °C", abs(d)) else figure.text(abs(d))
                when {
                    abs(d) < 0.4 * u.sd -> tr("About your usual.", "평소와 비슷해요.")
                    d > 0 -> tr("$size above your usual.", "평소보다 $size 높아요.")
                    else -> tr("$size below your usual.", "평소보다 $size 낮아요.")
                } + tr(" Usually ${span(figure, u)}.", " 평소 범위는 ${span(figure, u)}예요.")
            } ?: tr("Your usual needs more nights.", "평소 범위를 잡으려면 밤이 더 필요해요.")
            Reading(text.substringBefore(' '), text.substringAfter(' ', ""), note)
        }
    }
    return reading ?: Reading("—", "", tr("Not recorded. Nothing came in from your watch that night.", "기록 없음. 그날 밤 시계에서 들어온 기록이 없어요."))
}

private class Stat(val key: String, val value: String, val note: String)

private fun statsOf(snapshot: ScoringSnapshot, figure: NightFigure, rows: List<NightRow>, selRow: NightRow, usual: Usual?): List<Stat> {
    val vals = rows.map(figure::of)
    val known = vals.indices.filter { vals[it] != null }
    val iHi = known.maxByOrNull { vals[it]!! }
    val iLo = known.minByOrNull { vals[it]!! }
    fun extreme(i: Int?, key: String) = Stat(key, i?.let { figure.text(vals[it]!!) } ?: "—", i?.let { nightLabel(rows[it].date) }.orEmpty())

    if (figure == NightFigure.TIMING) {
        val window = snapshot.rows(USUAL_END).take(USUAL_NIGHTS)
        val beds = window.mapNotNull(NightFigure.TIMING::of)
        val wakes = window.mapNotNull { r -> NightFigure.TIMING.of(r)?.let { bed -> r.rec?.sleep?.inBedMinutes?.let { bed + it } } }
        val wakeUsual = snapshot.wakeUsual()
        val sri = selRow.day?.sleep?.sri
        fun spread(u: Usual?) = u?.let { tr("± ${it.sd.roundToInt()} min", "± ${it.sd.roundToInt()}분") }.orEmpty()
        return listOf(
            Stat(tr("Usual bedtime", "평소 취침"), clockMean(beds)?.let { ScoreCopy.minuteOfDay(it.roundToInt()) } ?: "—", spread(usual)),
            Stat(tr("Usual wake", "평소 기상"), clockMean(wakes)?.let { ScoreCopy.minuteOfDay(it.roundToInt()) } ?: "—", spread(wakeUsual)),
            extreme(iHi, tr("Latest bedtime", "가장 늦은 취침")),
            Stat(tr("Regularity", "규칙성"), sri?.roundToInt()?.toString() ?: "—", sri?.let(::regularityWord).orEmpty()),
        )
    }
    val recorded = known.map { vals[it]!! }
    return listOf(
        Stat(
            tr("Average", "평균"),
            recorded.takeIf { it.isNotEmpty() }?.average()?.let { figure.text(it) } ?: "—",
            tr("${recorded.size} of ${rows.size} nights recorded", "${rows.size}밤 중 ${recorded.size}밤 기록"),
        ),
        if (figure == NightFigure.BODY) {
            Stat(tr("Your usual", "내 평소"), tr("31–69 Typical", "31–69 평소 수준"), tr("Inside your own normal", "내 평소 범위 안"))
        } else {
            Stat(
                tr("Your usual", "내 평소"),
                usual?.let { span(figure, it) } ?: "—",
                usual?.let { tr("From ${it.nights} nights", "${it.nights}밤 기준") } ?: tr("Needs more nights", "밤이 더 필요해요"),
            )
        },
        extreme(iHi, if (figure == NightFigure.ASLEEP) tr("Longest", "가장 길게") else tr("Highest", "가장 높음")),
        extreme(iLo, if (figure == NightFigure.ASLEEP) tr("Shortest", "가장 짧게") else tr("Lowest", "가장 낮음")),
    )
}

// --- Words and numbers -----------------------------------------------------------

internal fun NightFigure.text(v: Double): String = when (this) {
    NightFigure.ASLEEP -> hm(v)
    NightFigure.TIMING -> ScoreCopy.minuteOfDay(v.roundToInt())
    NightFigure.SCORE, NightFigure.BODY -> "${v.roundToInt()}"
    NightFigure.HR -> ScoreCopy.formatRaw(Metric.SLEEPING_HEART_RATE, v)
    NightFigure.HRV -> ScoreCopy.formatRaw(Metric.HRV_RMSSD, v)
    NightFigure.SPO2 -> ScoreCopy.formatRaw(Metric.SPO2, v)
    NightFigure.BR -> ScoreCopy.formatRaw(Metric.RESPIRATORY_RATE, v)
    NightFigure.TEMP -> ScoreCopy.formatRaw(Metric.SKIN_TEMPERATURE, v)
}

/** A figure without its unit, for axis labels and ranges. */
private fun bare(text: String): String = text.substringBefore(' ').removeSuffix("%")

internal fun span(figure: NightFigure, u: Usual): String =
    if (figure == NightFigure.ASLEEP) {
        "${hm(u.lo)}–${hm(u.hi)}"
    } else {
        bare(figure.text(u.lo)) + (if (u.lo < 0) tr(" to ", " ~ ") else "–") + figure.text(u.hi)
    }

private fun NightFigure.title(): String = when (this) {
    NightFigure.ASLEEP -> tr("Time asleep", "수면 시간")
    NightFigure.TIMING -> tr("Bedtime and wake", "취침과 기상")
    NightFigure.SCORE -> tr("Sleep score", "수면 점수")
    NightFigure.BODY -> tr("Body", "컨디션")
    NightFigure.HR -> tr("Sleeping heart rate", "수면 중 심박수")
    NightFigure.HRV -> tr("Heart rate variability", "심박 변이도")
    NightFigure.SPO2 -> tr("Blood oxygen", "혈중 산소")
    NightFigure.BR -> tr("Breathing rate", "호흡수")
    NightFigure.TEMP -> tr("Skin temperature", "피부 온도")
}

private fun NightFigure.lead(): String = when (this) {
    NightFigure.ASLEEP -> tr("One bar a night with its stages stacked, your need dashed.", "하룻밤에 막대 하나, 수면 단계를 쌓아 보여주고 필요 수면은 점선이에요.")
    NightFigure.TIMING -> tr("One bar a night, from lights out to waking.", "하룻밤에 막대 하나, 불 끈 시각부터 깬 시각까지예요.")
    NightFigure.SCORE -> tr("One bar a night, built from the three parts below.", "하룻밤에 막대 하나, 아래 세 항목으로 이루어져요.")
    NightFigure.BODY -> tr("One dot a morning; the shaded band is Typical.", "아침마다 점 하나, 음영은 ‘평소 수준’ 범위예요.")
    else -> tr(
        "One dot a night against your usual range, the 60 nights ending a week ago.",
        "하룻밤에 점 하나, 일주일 전까지 60밤으로 잡은 평소 범위와 비교해요.",
    )
}

private fun NightFigure.about(): String {
    val sleep = ScoringConfig.DEFAULT.sleep
    val minRecords = ScoringConfig.DEFAULT.signal.minRecordsOvernight
    return when (this) {
        NightFigure.ASLEEP -> tr(
            "Every minute your watch scored as asleep. Your need is the median of your weekend nights, kept within " +
                "${sleep.minNeedHours.roundToInt()} to ${sleep.maxNeedHours.roundToInt()} hours, and debt weights recent nights more. " +
                "Stages are shown, never scored: wrist staging is the least reliable thing a watch measures.",
            "시계가 잠든 것으로 기록한 모든 시간이에요. 필요 수면은 주말 밤들의 중앙값으로 정하고 " +
                "${sleep.minNeedHours.roundToInt()}~${sleep.maxNeedHours.roundToInt()}시간 안에 두며, 수면 부채는 최근 밤일수록 크게 반영해요. " +
                "수면 단계는 보여주기만 하고 점수에는 넣지 않아요. 손목으로 재는 수면 단계는 시계가 측정하는 것 중 가장 부정확해요.",
        )
        NightFigure.TIMING -> tr(
            "Regularity is the Sleep Regularity Index: the chance you are in the same state, asleep or awake, at two moments " +
                "24 hours apart, from −100 to 100 over ${sleep.sriDays} days, naps included. In UK Biobank it predicted mortality " +
                "better than duration. Bedtimes are averaged as times on a clock, so 23:30 and 00:30 average to midnight.",
            "규칙성은 수면 규칙성 지수(SRI)예요. 24시간 간격의 두 순간에 같은 상태(잠듦 또는 깸)일 확률을 ${sleep.sriDays}일 동안 " +
                "−100~100으로 나타내며 낮잠도 포함해요. UK Biobank에서는 수면 시간보다 사망률을 더 잘 예측했어요. " +
                "취침 시각은 시계 위의 시각으로 평균을 내서, 23:30과 00:30의 평균은 자정이에요.",
        )
        NightFigure.SCORE -> tr(
            "Optional, for people who want one number: ${pct(sleep.scoreDurationWeight)} duration against your need, " +
                "${pct(sleep.scoreRegularityWeight)} regularity and ${pct(sleep.scoreContinuityWeight)} continuity. " +
                "A part that wasn’t measured has its weight shared out. Stages get no weight.",
            "숫자 하나를 원하는 분을 위한 선택 항목이에요. 필요 수면 대비 수면 시간 ${pct(sleep.scoreDurationWeight)}, " +
                "규칙성 ${pct(sleep.scoreRegularityWeight)}, 연속성 ${pct(sleep.scoreContinuityWeight)}로 이루어져요. " +
                "측정되지 않은 항목의 비중은 나머지에 나누고, 수면 단계는 반영하지 않아요.",
        )
        NightFigure.BODY -> tr(
            "Body is your HRV and sleeping heart rate against your own normal, through a filter that trusts each night only " +
                "as much as its quality allows. 50 is your normal. It is shown as a word first, and the number can be hidden in settings.",
            "컨디션은 HRV와 수면 중 심박수를 내 평소와 비교한 값이에요. 각 밤을 기록 품질만큼만 믿는 필터를 거쳐요. " +
                "50이 내 평소예요. 먼저 단어로 보여주며, 숫자는 설정에서 숨길 수 있어요.",
        )
        NightFigure.HR -> tr(
            "The lowest 30-minute stretch of the night, worked out by Pawse from the raw heart-rate series. It is one of " +
                "Body’s two signals, and several high nights in a row are what the overnight pattern detector watches for.",
            "밤 동안 가장 낮았던 30분 구간의 평균으로, Pawse가 원본 심박수 기록에서 직접 계산해요. 컨디션의 두 신호 중 " +
                "하나이고, 여러 밤 연속으로 높으면 야간 패턴 감지기가 이를 지켜봐요.",
        )
        NightFigure.HRV -> tr(
            "The median of every RMSSD reading in the night. Body reads it on a log scale, so a 10 ms drop matters more from 40 than from 80.",
            "밤 동안의 모든 RMSSD 측정값의 중앙값이에요. 컨디션은 이를 로그 척도로 읽어서, 같은 10 ms 하락도 80보다 40에서 더 크게 반영돼요.",
        )
        NightFigure.SPO2 -> tr(
            "Shown, never scored. It needs $minRecords readings in the night to count, and a short dip is usually the strap shifting rather than you.",
            "보여주기만 하고 점수에는 넣지 않아요. 밤 동안 측정값이 ${minRecords}개 이상 있어야 반영되며, 잠깐 떨어지는 건 대개 몸이 아니라 시곗줄이 움직인 탓이에요.",
        )
        NightFigure.BR -> tr(
            "The median of the night’s readings; it needs $minRecords. A rise alongside a high heart rate is one of the signs " +
                "the overnight pattern detector looks for.",
            "밤 동안 측정값의 중앙값이며, ${minRecords}개 이상 필요해요. 심박수가 높으면서 호흡수도 오르는 것은 야간 패턴 " +
                "감지기가 살피는 신호 중 하나예요.",
        )
        NightFigure.TEMP -> tr(
            "The device reports skin temperature against its own baseline; this is the night’s average. A rise held over " +
                "several nights is one of the detector’s signs. Your cycle moves it too.",
            "기기는 자체 기준 대비 피부 온도를 기록해요. 이 값은 그 밤의 평균이에요. 며칠 밤 이어지는 상승은 감지기가 " +
                "살피는 신호 중 하나이며, 생리 주기도 영향을 줘요.",
        )
    }
}

private fun stageName(stage: NightStage, sleep: Boolean = false): String = when (stage) {
    NightStage.AWAKE -> tr("Awake", "깸")
    NightStage.REM -> tr(if (sleep) "REM sleep" else "REM", "렘수면")
    NightStage.LIGHT -> tr(if (sleep) "Light sleep" else "Light", "얕은 잠")
    NightStage.DEEP -> tr(if (sleep) "Deep sleep" else "Deep", "깊은 잠")
    NightStage.ASLEEP -> tr("Asleep", "잠듦")
}

@Composable
private fun stageColors(): Map<NightStage, Color> {
    val s = PawseTheme.semantics
    return mapOf(
        NightStage.AWAKE to s.stageAwake,
        NightStage.REM to s.stageRem,
        NightStage.LIGHT to s.stageLight,
        NightStage.DEEP to s.stageDeep,
        NightStage.ASLEEP to s.stageAsleep,
    )
}

private fun stageMinutes(night: NightSummary, stage: NightStage): Double? = when (stage) {
    NightStage.AWAKE -> night.inBedMinutes - night.asleepMinutes
    NightStage.REM -> night.remMinutes
    NightStage.LIGHT -> night.lightMinutes
    NightStage.DEEP -> night.deepMinutes
    NightStage.ASLEEP -> night.asleepMinutes
}

private fun nightTitle(wake: LocalDate, latest: Boolean): String {
    if (latest) return tr("Last night", "지난밤")
    val day = wake.minusDays(1).dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())
    return tr("$day night", "$day 밤")
}

/** "Sun 18 → Mon 19 October". */
private fun nightRange(wake: LocalDate): String {
    val bed = wake.minusDays(1)
    val from = bed.format(pattern(tr(if (bed.month == wake.month) "EEE d" else "EEE d MMM", "M월 d일 (E)")))
    return "$from → ${wake.format(pattern(tr("EEE d MMMM", "M월 d일 (E)")))}"
}

/** "Sun 18 Oct night", named by its bedtime. */
private fun nightLabel(wake: LocalDate): String =
    wake.minusDays(1).format(pattern(tr("EEE d MMM", "M월 d일 (E)"))).let { tr("$it night", "$it 밤") }

private fun pattern(p: String): DateTimeFormatter = DateTimeFormatter.ofPattern(p, Locale.getDefault())

private fun needLine(asleep: Double, need: Double): String {
    val d = need - asleep
    return when {
        abs(d) <= 2 -> tr("Right on your ${hm(need)} need.", "필요 수면 ${hm(need)}에 딱 맞았어요.")
        d > 0 -> tr("${dur(d)} short of your ${hm(need)} need.", "필요 수면 ${hm(need)}보다 ${dur(d)} 부족했어요.")
        else -> tr("${dur(-d)} over your ${hm(need)} need.", "필요 수면 ${hm(need)}보다 ${dur(-d)} 더 잤어요.")
    }
}

private fun debtLine(hours: Double?): String {
    val nights = ScoringConfig.DEFAULT.sleep.debtNights
    return when {
        hours == null -> ""
        hours < 0.25 -> tr(" No sleep debt over the last $nights nights.", " 최근 ${nights}밤 동안 수면 부채가 없어요.")
        else -> tr(" You’re ${hm(hours * 60)} behind over the last $nights nights.", " 최근 ${nights}밤 동안 ${hm(hours * 60)} 부족해요.")
    }
}

private fun wakeUps(count: Int?): String = when (count) {
    null -> ""
    0 -> tr("no wake-ups", "깬 적 없음")
    1 -> tr("once", "1번")
    else -> tr("$count times", "${count}번")
}

private fun regularityWord(sri: Double): String = ScoreCopy.regularity(sri).substringBefore(" (")

private fun hm(minutes: Double): String = ScoreCopy.formatMinutes(minutes)

private fun dur(minutes: Double): String = if (minutes.roundToInt() >= 60) hm(minutes) else minutesText(max(0.0, minutes))

// ponytail: one friendly name; other writers show their package until one needs its own.
internal fun writer(pkg: String): String = when (pkg) {
    CapabilityProbe.SAMSUNG_HEALTH -> "Samsung Health"
    "" -> "—"
    else -> pkg
}

// --- Layout helpers ----------------------------------------------------------------

private const val LANE_HEIGHT = 20
private const val LANE_STEP = 26
internal const val CHART_HEIGHT = 160.0

/** One night's time axis, lights-out to final wake, in epoch ms and on the local clock. */
private class NightAxis(val start: Long, val end: Long, val bedMinute: Double) {
    val minutes: Double get() = (end - start) / 60_000.0
    fun frac(t: Long): Float = ((t - start).toDouble() / (end - start)).toFloat()
    fun timeAt(f: Float): Long = start + (f * (end - start)).toLong()
    fun clockAt(t: Long): String = ScoreCopy.minuteOfDay((bedMinute + (t - start) / 60_000.0).roundToInt())
}

/**
 * Touch or drag across a chart to read it: [onAt] gets the share of the width
 * under the finger. Horizontal only, so the page still scrolls.
 */
@Composable
internal fun Modifier.scrub(onAt: (Float?) -> Unit): Modifier {
    val latest by rememberUpdatedState(onAt)
    return pointerInput(Unit) {
        fun at(x: Float) = latest((x / size.width).coerceIn(0f, 0.999f))
        coroutineScope {
            launch { detectTapGestures { at(it.x) } }
            launch {
                detectHorizontalDragGestures(onDragStart = { at(it.x) }) { change, _ ->
                    change.consume()
                    at(change.position.x)
                }
            }
        }
    }
}

/** Places the element with its centre (or left edge) on [x] and its middle on [y]. */
internal fun Modifier.pinned(x: Dp, y: Dp, centreX: Boolean = true): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    layout(p.width, p.height) {
        p.place(x.roundToPx() - if (centreX) p.width / 2 else 0, y.roundToPx() - p.height / 2)
    }
}
