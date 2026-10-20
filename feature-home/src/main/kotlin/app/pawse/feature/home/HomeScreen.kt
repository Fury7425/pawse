package app.pawse.feature.home

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pawse.core.data.scoring.ScoringSnapshot
import app.pawse.core.data.sync.SyncFailure
import app.pawse.core.data.sync.SyncStatus
import app.pawse.core.data.sync.SyncStep
import app.pawse.core.ui.component.BandDot
import app.pawse.core.ui.component.BaselineBandRow
import app.pawse.core.ui.component.Chip
import app.pawse.core.ui.component.GhostButton
import app.pawse.core.ui.component.Glyph
import app.pawse.core.ui.component.GlyphButton
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.PawseCard
import app.pawse.core.ui.component.PrimaryButton
import app.pawse.core.ui.component.ScoreRing
import app.pawse.core.ui.component.SplitBar
import app.pawse.core.ui.component.UsualRangeKey
import app.pawse.core.ui.component.cardSurface
import app.pawse.core.ui.component.pressable
import app.pawse.core.ui.theme.PawseTheme
import app.pawse.core.ui.theme.PawseType
import app.pawse.scoring.config.ScoringConfig
import app.pawse.scoring.model.BodyStatus
import app.pawse.scoring.model.DayTag
import app.pawse.scoring.model.EnergyForecast
import app.pawse.scoring.model.FormBand
import app.pawse.scoring.model.IllnessLevel
import app.pawse.scoring.model.LoadReading
import app.pawse.scoring.model.ScoreType
import app.pawse.scoring.model.SleepReading
import app.pawse.scoring.tr
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Today: one line of guidance, then Sleep, Body and Load as three rings. Tapping a
 * ring swaps the section below to what produced it; Energy and Form sit under that.
 *
 * Rules against score anxiety are layout rules here: every ring carries its word,
 * Body's number can be hidden, no single night is painted red, and the only red on
 * the screen is an illness alert.
 */
@Composable
fun HomeScreen(
    onOpenExplain: (ScoreType) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
    var editingProfile by remember { mutableStateOf(false) }

    Scaffold(modifier = modifier, containerColor = MaterialTheme.colorScheme.background) { padding ->
        when (val current = state) {
            is HomeUiState.Loading -> Loading(Modifier.padding(padding))

            is HomeUiState.Failed -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(tr("Could not work out today", "오늘 값을 계산하지 못했어요"), style = PawseType.Title)
                Spacer(Modifier.height(8.dp))
                Text(current.message, style = PawseType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                PrimaryButton(tr("Try again", "다시 시도"), onClick = viewModel::refresh)
            }

            is HomeUiState.Ready -> TodayContent(
                snapshot = current.snapshot,
                sync = current.sync,
                accessOff = current.accessOff,
                syncError = error,
                contentPadding = padding,
                onOpenExplain = onOpenExplain,
                onSyncNow = viewModel::syncNow,
                onSettings = { editingProfile = true },
                onDismissError = viewModel::dismissError,
                onCheckIn = viewModel::checkIn,
                onTag = viewModel::toggleTag,
            )
        }
    }

    if (editingProfile) {
        val snapshot = (state as? HomeUiState.Ready)?.snapshot
        ProfileDialog(
            initialProfile = snapshot?.profile,
            initialShowNumbers = snapshot?.showNumbers ?: true,
            initialAppearance = appearance,
            calibration = snapshot?.calibration,
            onResetModel = viewModel::resetModel,
            onDismiss = { editingProfile = false },
            onSave = { age, sex, maxHr, showNumbers, chosenAppearance ->
                viewModel.saveProfile(age, sex, maxHr)
                viewModel.setShowNumbers(showNumbers)
                viewModel.setAppearance(chosenAppearance)
                editingProfile = false
            },
        )
    }
}

@Composable
private fun Loading(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurface, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(16.dp))
        Text(tr("Working out your night", "지난밤을 분석하는 중"), style = PawseType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val RINGS = listOf(ScoreType.SLEEP, ScoreType.BODY, ScoreType.LOAD)
private val RING_WEIGHTS = listOf(1f, 1.3f, 1f)

@Composable
private fun TodayContent(
    snapshot: ScoringSnapshot,
    sync: SyncStatus,
    accessOff: Boolean,
    syncError: String?,
    contentPadding: PaddingValues,
    onOpenExplain: (ScoreType) -> Unit,
    onSyncNow: () -> Unit,
    onSettings: () -> Unit,
    onDismissError: () -> Unit,
    onCheckIn: (Int) -> Unit,
    onTag: (DayTag) -> Unit,
) {
    val context = LocalContext.current
    val today = snapshot.today
    val hour = LocalTime.now().hour
    var focus by rememberSaveable {
        mutableStateOf(
            when {
                hour >= 18 && today.load.todayLoad > 0.0 -> ScoreType.LOAD
                today.body != null -> ScoreType.BODY
                today.sleep.lastNightMinutes != null -> ScoreType.SLEEP
                else -> ScoreType.BODY
            },
        )
    }
    val alert = today.illness.level == IllnessLevel.ALERT
    val glow = if (today.body != null || alert) bodyColor(today.body?.band, today.illness.level) else null
    val load = today.load
    val pad = Modifier.padding(horizontal = 24.dp)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 140.dp,
        ),
    ) {
        item {
            Column(
                // A faint wash of Body's colour behind the rings, drawn past this
                // item's edges and scrolled away with it.
                Modifier.drawBehind {
                    if (glow != null) {
                        val c = Offset(size.width * 0.5f, 250.dp.toPx())
                        drawCircle(
                            Brush.radialGradient(listOf(glow.copy(alpha = 0.10f), Color.Transparent), c, 260.dp.toPx()),
                            radius = 260.dp.toPx(),
                            center = c,
                        )
                    }
                },
            ) {
                Header(snapshot.date, sync, snapshot.lastSyncEpochMs, onSyncNow, onSettings, pad)
                Spacer(Modifier.height(28.dp))
                Text(ScoreCopy.guidance(today.guidance), style = PawseType.Title, modifier = pad)
                Spacer(Modifier.height(20.dp))
                Rings(snapshot = snapshot, focus = focus, onFocus = { focus = it })
            }
        }

        if (alert) {
            item {
                PawseCard(modifier = pad.padding(top = 16.dp)) {
                    BandDot(color = PawseTheme.semantics.bandLow, label = tr("Overnight heart rate", "야간 심박수"))
                    Spacer(Modifier.height(6.dp))
                    Text(ScoreCopy.ILLNESS_ALERT, style = PawseType.Body)
                    ScoreCopy.likelyCauses(today.illness.likelyCauses)?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (accessOff) {
            item {
                Notice(
                    text = tr(
                        "Pawse can't read your data right now. Access to Health Connect was turned off, " +
                            "so nothing new can come in. Everything already read is still on this phone.",
                        "지금은 Pawse가 데이터를 읽을 수 없어요. 헬스 커넥트 접근이 꺼져 있어서 새 데이터가 들어오지 " +
                            "않아요. 이미 읽은 데이터는 모두 이 휴대폰에 그대로 있어요.",
                    ),
                    actionLabel = tr("Turn access back on", "접근 다시 켜기"),
                    onAction = {
                        runCatching { context.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }
                    },
                    modifier = pad.padding(top = 16.dp),
                )
            }
        }

        if (today.body == null && today.bodyStatus == BodyStatus.WARMING_UP && focus != ScoreType.BODY) {
            item {
                Notice(
                    text = ScoreCopy.bodyStatus(today.bodyStatus, today.validNights, ScoringConfig.DEFAULT.body.minValidNights),
                    modifier = pad.padding(top = 16.dp),
                )
            }
        }

        if (syncError != null) {
            item {
                Notice(text = syncError, actionLabel = tr("Dismiss", "닫기"), onAction = onDismissError, modifier = pad.padding(top = 16.dp))
            }
        }

        if (load.rest || (load.rangeLow != null && load.rangeHigh != null)) {
            item { RangeCard(load, onClick = { onOpenExplain(ScoreType.LOAD) }, modifier = pad.padding(top = 16.dp)) }
        }

        item { Spacer(Modifier.height(32.dp)) }
        focusSection(focus, snapshot, onOpenExplain, pad)

        item {
            Spacer(Modifier.height(32.dp))
            Text(tr("Also today", "오늘의 다른 정보"), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = pad)
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = pad.height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                EnergyCard(snapshot.energy, Modifier.weight(1f).fillMaxHeight())
                FormCard(snapshot, onClick = { onOpenExplain(ScoreType.LOAD) }, modifier = Modifier.weight(1f).fillMaxHeight())
            }
            if (snapshot.energy != null) {
                Spacer(Modifier.height(10.dp))
                Text(ScoreCopy.FORECAST_NOTE, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = pad)
            }
        }

        item { CheckInCard(snapshot, onCheckIn, onTag, modifier = pad.padding(top = 20.dp)) }

        if (today.sleep.suggestDoctor) {
            item { Notice(text = ScoreCopy.DOCTOR, modifier = pad.padding(top = 20.dp)) }
        }

        if (snapshot.needsProfile) {
            item {
                Notice(
                    text = tr(
                        "Workouts are counted by effort until Pawse knows your maximum heart rate. " +
                            "Your age, or a measured maximum, lets it read your heart rate instead.",
                        "Pawse가 최대 심박수를 알기 전까지는 운동을 체감 강도로 계산해요. 나이나 측정한 최대 " +
                            "심박수를 알려 주면 심박수로 계산할 수 있어요.",
                    ),
                    actionLabel = tr("Add it", "입력하기"),
                    onAction = onSettings,
                    modifier = pad.padding(top = 20.dp),
                )
            }
        }

        item {
            Column(pad.padding(top = 24.dp)) {
                GhostButton(tr("You and your settings", "내 정보와 설정"), onClick = onSettings)
                Spacer(Modifier.height(8.dp))
                Text(ScoreCopy.FOOTER, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * The date and what happened: when Health Connect was last read, or which of the
 * three steps is running now. The rings below never blank while it works.
 */
@Composable
private fun Header(
    date: LocalDate,
    sync: SyncStatus,
    lastSyncEpochMs: Long,
    onSyncNow: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = date.format(DateTimeFormatter.ofPattern(tr("EEEE d MMMM", "M월 d일 EEEE"), Locale.getDefault())),
                    style = PawseType.Heading,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Glyph(
                        if (sync is SyncStatus.Failed) Glyphs.Info else Glyphs.Check,
                        size = 14.dp,
                        strokeWidth = 2f,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(statusLine(sync, lastSyncEpochMs), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (sync is SyncStatus.Running) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                GlyphButton(Glyphs.Refresh, tr("Refresh", "새로고침"), onClick = onSyncNow)
            }
            GlyphButton(Glyphs.Person, tr("You and your settings", "내 정보와 설정"), onClick = onSettings)
        }
        if (sync is SyncStatus.Running) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SyncStep.entries.forEach { step ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(3.dp)
                            .background(
                                when {
                                    step.ordinal < sync.step.ordinal -> MaterialTheme.colorScheme.onSurface
                                    step == sync.step -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    else -> PawseTheme.semantics.baselineOuter
                                },
                                RoundedCornerShape(2.dp),
                            ),
                    )
                }
            }
        }
    }
}

private fun statusLine(sync: SyncStatus, lastSyncEpochMs: Long): String = when (sync) {
    is SyncStatus.Running -> {
        val step = sync.step.ordinal + 1
        val total = SyncStep.entries.size
        val what = when (sync.step) {
            SyncStep.READ -> tr("reading", "읽는 중")
            SyncStep.COMPARE -> tr("comparing", "비교하는 중")
            SyncStep.SCORE -> tr("working it out", "계산하는 중")
        }
        tr("Reading Health Connect · step $step of $total, $what", "헬스 커넥트 읽는 중 · ${total}단계 중 ${step}단계, $what")
    }
    is SyncStatus.Failed -> when (sync.reason) {
        SyncFailure.READ_FAILED -> tr(
            "Health Connect didn't answer at ${ScoreCopy.clock(sync.atEpochMs)}",
            "${ScoreCopy.clock(sync.atEpochMs)}에 헬스 커넥트가 응답하지 않았어요",
        )
        SyncFailure.ACCESS_OFF -> tr("Access to Health Connect is off", "헬스 커넥트 접근이 꺼져 있어요")
        SyncFailure.UNAVAILABLE -> tr("Health Connect isn't available", "헬스 커넥트를 사용할 수 없어요")
    }
    else -> if (lastSyncEpochMs > 0) {
        tr("Read from Health Connect at ${ScoreCopy.clock(lastSyncEpochMs)}", "${ScoreCopy.clock(lastSyncEpochMs)}에 헬스 커넥트에서 읽음")
    } else {
        tr("Not read from Health Connect yet", "아직 헬스 커넥트에서 읽지 않았어요")
    }
}

/** What one ring shows. A null [value] draws the score's glyph instead: Body with numbers hidden. */
private class RingFace(
    val fraction: Float?,
    val arc: Color,
    val glow: Boolean,
    val value: String?,
    val unit: String,
    val dot: Color,
    val word: String,
    val target: Float? = null,
)

/**
 * Body is its score on its band colour. Sleep is last night against your need and
 * Load is today against the top of its range; both are real units, so both stay grey.
 */
@Composable
private fun ringFace(type: ScoreType, snapshot: ScoringSnapshot): RingFace {
    val today = snapshot.today
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val neutral = PawseTheme.semantics.neutral
    return when (type) {
        ScoreType.BODY -> {
            val body = today.body
            val color = bodyColor(body?.band, today.illness.level)
            RingFace(
                fraction = body?.let { it.score / 100f },
                arc = color,
                glow = body != null,
                value = when {
                    body == null -> "—"
                    snapshot.showNumbers -> body.score.toString()
                    else -> null
                },
                unit = "",
                dot = color,
                word = body?.let { ScoreCopy.bandWord(it.band) }
                    ?: if (today.bodyStatus == BodyStatus.NO_RECENT_NIGHT) tr("No reading", "값 없음") else tr("Settling", "기준 잡는 중"),
            )
        }

        ScoreType.SLEEP -> {
            val sleep = today.sleep
            val slept = sleep.lastNightMinutes
            RingFace(
                fraction = slept?.let { (it / (sleep.needHours * 60.0)).toFloat() },
                arc = grey,
                glow = false,
                value = slept?.let { String.format(Locale.getDefault(), "%.1f", it / 60.0) } ?: "—",
                unit = if (slept != null) "h" else "",
                dot = neutral,
                word = sleep.debtHours?.let(ScoreCopy::debt) ?: tr("Settling", "기준 잡는 중"),
            )
        }

        ScoreType.LOAD -> {
            val load = today.load
            // The full ring is the top of today's range; the tick is where the range starts.
            val top = load.rangeHigh?.takeIf { it > 0.0 && !load.rest }
            RingFace(
                fraction = top?.let { (load.todayLoad / it).toFloat() },
                arc = grey,
                glow = false,
                value = ScoreCopy.load(load.todayLoad),
                unit = "",
                dot = neutral,
                word = formWord(load),
                target = top?.let { high -> load.rangeLow?.let { (it / high).toFloat() } },
            )
        }
    }
}

private fun formWord(load: LoadReading): String = when {
    load.rest -> tr("Rest today", "오늘은 휴식")
    else -> when (load.formBand) {
        FormBand.FRESH -> tr("Fresh", "개운함")
        FormBand.PRODUCTIVE -> tr("Productive", "효과적")
        FormBand.HEAVY -> tr("Heavy", "무거움")
        null -> tr("Building", "쌓는 중")
    }
}

/** Sleep, Body and Load as rings, Body a size up; tapping one picks the section below. */
@Composable
private fun Rings(snapshot: ScoringSnapshot, focus: ScoreType, onFocus: (ScoreType) -> Unit) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        RINGS.forEachIndexed { i, type ->
            RingTile(
                type = type,
                face = ringFace(type, snapshot),
                selected = focus == type,
                onClick = { onFocus(type) },
                modifier = Modifier.weight(RING_WEIGHTS[i]),
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(tr("Last night", "지난밤"), tr("This morning", "오늘 아침"), tr("Today so far", "오늘 지금까지")).forEachIndexed { i, caption ->
            Text(
                caption,
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(RING_WEIGHTS[i]),
            )
        }
    }
}

@Composable
private fun RingTile(
    type: ScoreType,
    face: RingFace,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val big = type == ScoreType.BODY
    val shown = face.value?.let { " $it${face.unit}" } ?: ""
    val description = "${ScoreCopy.name(type)}$shown, ${face.word}" + if (selected) tr(", shown below", ", 아래에 표시됨") else ""

    Column(
        modifier = modifier
            .pressable(onClick = onClick)
            .then(if (selected) Modifier.cardSurface() else Modifier)
            .padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ScoreRing(
            fraction = face.fraction,
            color = face.arc,
            size = if (big) 112.dp else 84.dp,
            glow = face.glow,
            target = face.target,
        ) {
            if (face.value == null) {
                Glyph(scoreGlyph(type), size = 28.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(face.value, style = if (big) PawseType.Numeral else PawseType.RingSmall)
                    if (face.unit.isNotEmpty()) {
                        Text(
                            face.unit,
                            style = PawseType.Label,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(scoreGlyph(type), size = 14.dp, strokeWidth = 2f, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(ScoreCopy.name(type), style = PawseType.BodyStrong)
        }
        BandDot(color = face.dot, label = face.word)
    }
}

/** Today's load against the range this morning's Body set. */
@Composable
private fun RangeCard(load: LoadReading, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val low = load.rangeLow
    val high = load.rangeHigh
    val inRange = !load.rest && low != null && high != null
    val track = PawseTheme.semantics.baselineOuter
    val band = PawseTheme.semantics.baselineBand
    val fill = MaterialTheme.colorScheme.onSurfaceVariant
    val marker = MaterialTheme.colorScheme.onSurface
    PawseCard(modifier = modifier, onClick = onClick, padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Today’s range", "오늘의 범위"), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(
                if (inRange) tr("Load", "부하") + " ${ScoreCopy.load(low!!)}–${ScoreCopy.load(high!!)}" else tr("Rest", "휴식"),
                style = PawseType.BodyStrong,
            )
            Spacer(Modifier.width(8.dp))
            Glyph(Glyphs.ChevronRight, size = 16.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(14.dp),
        ) {
            val bar = 6.dp.toPx()
            val top = (size.height - bar) / 2f
            val r = CornerRadius(bar / 2f)
            val scale = maxOf((high ?: 0.0) * 1.25, load.todayLoad, 1.0)
            fun x(v: Double) = (v / scale).coerceIn(0.0, 1.0).toFloat() * size.width
            drawRoundRect(track, Offset(0f, top), Size(size.width, bar), r)
            if (inRange) drawRoundRect(band, Offset(x(low!!), top), Size(x(high!!) - x(low), bar), r)
            val done = x(load.todayLoad)
            if (done > 0f) drawRoundRect(fill, Offset(0f, top), Size(done, bar), r)
            if (inRange) {
                drawRoundRect(marker, Offset(x(low!!) - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height), CornerRadius(1.dp.toPx()))
            }
        }
        Spacer(Modifier.height(4.dp))
        Row {
            Text(
                tr("${ScoreCopy.load(load.todayLoad)} so far", "지금까지 ${ScoreCopy.load(load.todayLoad)}"),
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                when {
                    load.rest -> tr("Your overnight heart rate has been high", "야간 심박수가 높았어요")
                    else -> load.typicalDayLoad?.let { tr("Your typical day is ${ScoreCopy.load(it)}", "평소 하루는 ${ScoreCopy.load(it)}") } ?: ""
                },
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The section under the rings: why the picked one came out as it did. */
private fun LazyListScope.focusSection(
    type: ScoreType,
    snapshot: ScoringSnapshot,
    onOpenExplain: (ScoreType) -> Unit,
    pad: Modifier,
) {
    val open = { onOpenExplain(type) }
    when (type) {
        ScoreType.BODY -> bodySection(snapshot, open, pad)
        ScoreType.SLEEP -> sleepSection(snapshot, open, pad)
        ScoreType.LOAD -> loadSection(snapshot, open, pad)
    }
    item(key = "focus-foot-$type") {
        Column(pad) {
            Spacer(Modifier.height(16.dp))
            ExplainLink(tr("How ${ScoreCopy.name(type)} was worked out", "${ScoreCopy.name(type)} 계산 방법"), onClick = open)
        }
    }
}

@Composable
private fun FocusHead(title: String, aside: String?, sentence: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(title, style = PawseType.Heading, modifier = Modifier.weight(1f))
        aside?.let { Text(it, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    Spacer(Modifier.height(4.dp))
    Text(sentence, style = PawseType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Body: the two signals against your normal, then the week. */
private fun LazyListScope.bodySection(snapshot: ScoringSnapshot, onOpen: () -> Unit, pad: Modifier) {
    val today = snapshot.today
    val body = today.body
    item(key = "focus-head-BODY") {
        Column(pad) {
            if (body == null) {
                FocusHead(
                    tr("No Body reading yet", "아직 컨디션 값이 없어요"),
                    null,
                    ScoreCopy.bodyStatus(today.bodyStatus, today.validNights, ScoringConfig.DEFAULT.body.minValidNights),
                )
                return@Column
            }
            val measured = listOfNotNull(body.hrv?.tonight, body.sleepingHr?.tonight).size
            FocusHead(
                title = tr(
                    "Why Body is ${ScoreCopy.bandWord(body.band).lowercase()}",
                    "컨디션이 ‘${ScoreCopy.bandWord(body.band)}’인 이유",
                ),
                aside = tr("$measured of 2 signals", "신호 2개 중 ${measured}개"),
                sentence = (
                    if (snapshot.showNumbers) {
                        tr("Body ${body.score}, likely ${body.low}–${body.high}. ", "컨디션 ${body.score}, 범위 ${body.low}–${body.high}. ")
                    } else {
                        ""
                    }
                    ) +
                    (body.reasons.takeIf { it.isNotEmpty() }?.let { ScoreCopy.reasons(it) + "." } ?: ScoreCopy.bandMeaning(body.band)),
            )
            Spacer(Modifier.height(10.dp))
            Row { Chip(ScoreCopy.confidence(body.confidence)) }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr("Against your normal", "내 평소와 비교"),
                    style = PawseType.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                UsualRangeKey(tr("Your normal range", "내 평소 범위"))
            }
        }
    }
    if (body == null) return

    val rows = listOfNotNull(body.hrv?.toRowState(hrv = true), body.sleepingHr?.toRowState(hrv = false))
    items(rows, key = { "focus-BODY-${it.label}" }) { row ->
        Column(pad) {
            BaselineBandRow(state = row, onClick = onOpen)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
    item(key = "focus-week-BODY") {
        Column(pad) {
            Spacer(Modifier.height(16.dp))
            Text(tr("This week", "이번 주"), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Sparkline(
                values = snapshot.week.map { it.body?.score?.toDouble() },
                band = 31.0..69.0,
                min = 0.0,
                max = 100.0,
                modifier = Modifier.fillMaxWidth().height(36.dp),
            )
            today.weekly?.let {
                Spacer(Modifier.height(8.dp))
                Text(ScoreCopy.weekly(it.pattern), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (today.illness.level == IllnessLevel.WATCH) {
                Spacer(Modifier.height(6.dp))
                Text(
                    ScoreCopy.ILLNESS_WATCH + (ScoreCopy.likelyCauses(today.illness.likelyCauses)?.let { " $it" } ?: ""),
                    style = PawseType.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            today.cycle?.takeIf { it.luteal }?.let { cycle ->
                Spacer(Modifier.height(6.dp))
                Text(
                    if (cycle.adjusted) {
                        tr("Adjusted for your cycle, using your own past cycles.", "지난 생리 주기를 바탕으로 주기 영향을 보정했어요.")
                    } else {
                        tr("Part of this may be your cycle.", "일부는 생리 주기 영향일 수 있어요.")
                    },
                    style = PawseType.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Sleep: last night against your need, then the optional score's three parts. */
private fun LazyListScope.sleepSection(snapshot: ScoringSnapshot, onOpen: () -> Unit, pad: Modifier) {
    val sleep = snapshot.today.sleep
    val config = ScoringConfig.DEFAULT.sleep
    item(key = "focus-head-SLEEP") {
        Column(pad) {
            FocusHead(
                title = ScoreCopy.debt(sleep.debtHours),
                aside = if (snapshot.showNumbers) sleep.score?.let { tr("Sleep $it", "수면 $it") } else null,
                sentence = (
                    sleep.lastNightMinutes?.let {
                        tr(
                            "You slept ${ScoreCopy.formatMinutes(it)} against a need of ${ScoreCopy.formatHours(sleep.needHours)}.",
                            "필요 수면 ${ScoreCopy.formatHours(sleep.needHours)} 중 ${ScoreCopy.formatMinutes(it)} 잤어요.",
                        )
                    } ?: tr("No night recorded yet.", "아직 기록된 밤이 없어요.")
                    ) + if (sleep.needLearned) {
                    ""
                } else {
                    tr(
                        " Need stays at ${ScoreCopy.formatHours(config.defaultNeedHours)} until there are ${config.minFreeNights} weekend nights to learn yours from.",
                        " 주말 밤이 ${config.minFreeNights}번 쌓여 내 필요 수면을 알 때까지는 ${ScoreCopy.formatHours(config.defaultNeedHours)}으로 둬요.",
                    )
                },
            )
            SleepNeedCard(sleep)
            Spacer(Modifier.height(16.dp))
            Text(
                tr("Against your need", "필요 수면과 비교"),
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
    val parts = sleep.parts
    if (parts == null) {
        item(key = "focus-SLEEP-none") {
            Text(tr("The parts appear once there is a night to read.", "읽을 밤 기록이 생기면 항목이 나타나요."), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = pad)
        }
        return
    }
    val rows = listOf(
        SleepPart(tr("Duration vs need", "필요 대비 수면 시간"), sleep.lastNightMinutes?.let(ScoreCopy::formatMinutes) ?: "—", parts.duration, config.scoreDurationWeight),
        SleepPart(tr("Regularity", "규칙성"), sleep.sri?.roundToInt()?.toString() ?: "—", parts.regularity, config.scoreRegularityWeight),
        SleepPart(tr("Continuity", "연속성"), sleep.efficiencyPercent?.let { "${it.roundToInt()}%" } ?: "—", parts.continuity, config.scoreContinuityWeight),
    )
    items(rows, key = { "focus-SLEEP-${it.label}" }) { part ->
        Column(pad) {
            PartRow(part, onClick = onOpen)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** Last night against the need, as one bar: slept, then what was missing. */
@Composable
private fun SleepNeedCard(sleep: SleepReading) {
    val need = sleep.needHours * 60.0
    val slept = sleep.lastNightMinutes ?: return
    val short = (need - slept).coerceAtLeast(0.0)
    Spacer(Modifier.height(16.dp))
    PawseCard(padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row {
            Text(tr("Your need", "필요 수면"), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(ScoreCopy.formatHours(sleep.needHours), style = PawseType.Label.copy(fontWeight = PawseType.BodyStrong.fontWeight))
        }
        Spacer(Modifier.height(10.dp))
        SplitBar(
            listOf(
                slept.coerceAtMost(need).toFloat() to MaterialTheme.colorScheme.outline,
                short.toFloat() to MaterialTheme.colorScheme.onSurface,
            ),
            height = 8.dp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            listOfNotNull(
                tr("Slept ${ScoreCopy.formatMinutes(slept)}", "수면 ${ScoreCopy.formatMinutes(slept)}"),
                short.takeIf { it >= 1.0 }?.let { tr("${minutes(it)} short", "${minutes(it)} 부족") },
            ).joinToString("   "),
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private class SleepPart(val label: String, val value: String, val part: Double?, val weight: Double)

/**
 * A sleep part, scored against your need rather than your history: its value and its
 * part score. A part that was not measured has its weight shared out by the engine.
 */
@Composable
private fun PartRow(part: SleepPart, onClick: () -> Unit) {
    val score = part.part?.let { (it * 100).roundToInt() }
    val track = PawseTheme.semantics.baselineOuter
    val fill = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(part.label, style = PawseType.Body, modifier = Modifier.weight(1f))
            Text(part.value, style = PawseType.MetricValue)
        }
        Spacer(Modifier.height(8.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(6.dp),
        ) {
            val r = CornerRadius(size.height / 2f)
            drawRoundRect(track, cornerRadius = r)
            if (score != null) drawRoundRect(fill, size = Size(size.width * score / 100f, size.height), cornerRadius = r)
        }
        Spacer(Modifier.height(6.dp))
        Row {
            Text(
                score?.let { tr("Part score $it / 100", "항목 점수 $it / 100") }
                    ?: tr("Not measured, so its weight went to the others", "측정되지 않아 비중을 다른 항목에 나눴어요"),
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(tr("Weight", "비중") + " ${(part.weight * 100).roundToInt()}%", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Load: today's sessions, what each counted for, and how. */
private fun LazyListScope.loadSection(snapshot: ScoringSnapshot, onOpen: () -> Unit, pad: Modifier) {
    val load = snapshot.today.load
    item(key = "focus-head-LOAD") {
        Column(pad) {
            FocusHead(
                title = tr("Load so far: ", "지금까지 부하: ") + ScoreCopy.load(load.todayLoad),
                aside = if (snapshot.showNumbers) {
                    load.form?.let { tr("Form", "폼") + " ${(it * 100).roundToInt()}%" }
                } else {
                    load.formBand?.let(ScoreCopy::form)
                },
                sentence = rangeSentence(load) + tr(
                    " Load is training, not a grade, so it stays grey.",
                    " 부하는 성적이 아니라 훈련량이라서 회색으로 표시해요.",
                ),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                tr("Where it came from", "어디서 나온 값인지"),
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
    if (load.sessions.isEmpty()) {
        item(key = "focus-LOAD-none") {
            Column(pad) {
                SessionRow(tr("Workouts", "운동"), "0", tr("No workouts logged yet today.", "오늘은 아직 기록된 운동이 없어요."), onOpen)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    } else {
        items(load.sessions, key = { "focus-LOAD-${it.id}" }) { s ->
            Column(pad) {
                SessionRow(
                    label = s.title ?: tr("Workout", "운동"),
                    value = ScoreCopy.load(s.load),
                    note = "${s.durationMinutes.roundToInt()}${tr(" min", "분")} · ${ScoreCopy.loadMethod(s.method)}" + (s.skippedReason?.let { ". $it" } ?: ""),
                    onClick = onOpen,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
    item(key = "focus-note-LOAD") {
        val config = ScoringConfig.DEFAULT.load
        Text(
            tr(
                "Fitness is your load over ${config.fitnessDays.roundToInt()} days and fatigue over ${config.fatigueDays.roundToInt()}; " +
                    "form is the gap between them.",
                "체력은 ${config.fitnessDays.roundToInt()}일, 피로는 ${config.fatigueDays.roundToInt()}일 동안의 부하이고, " +
                    "폼은 그 둘의 차이예요.",
            ),
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = pad.padding(top = 14.dp),
        )
    }
}

private fun rangeSentence(load: LoadReading): String {
    val low = load.rangeLow
    val high = load.rangeHigh
    return when {
        load.rest -> tr("Your overnight heart rate has been high, so today is for rest.", "야간 심박수가 높았으니 오늘은 쉬는 날이에요.")
        low != null && high != null -> tr(
            "Today’s range is ${ScoreCopy.load(low)}–${ScoreCopy.load(high)}: your typical training day, moved by this morning’s Body.",
            "오늘의 범위: ${ScoreCopy.load(low)}–${ScoreCopy.load(high)}. 평소 운동량을 오늘 아침 컨디션에 맞춰 조정했어요.",
        )
        else -> tr("A range appears after your first few workouts.", "운동을 몇 번 하고 나면 범위가 나타나요.")
    }
}

@Composable
private fun SessionRow(label: String, value: String, note: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, style = PawseType.Body, modifier = Modifier.weight(1f))
            Text(value, style = PawseType.MetricValue)
        }
        Spacer(Modifier.height(4.dp))
        Text(note, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ExplainLink(text: String, onClick: () -> Unit) {
    Row(
        Modifier
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

/** An "Also today" card: a figure, its word, a small chart, one line. */
@Composable
private fun AlsoCard(
    glyph: String,
    name: String,
    value: String?,
    suffix: String,
    word: String,
    sentence: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    chart: @Composable () -> Unit,
) {
    PawseCard(modifier = modifier.heightIn(min = 156.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(glyph, size = 14.dp, strokeWidth = 2f, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(name, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        if (value != null) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = PawseType.NumeralSmall)
                if (suffix.isNotEmpty()) {
                    Text(
                        suffix,
                        style = PawseType.Body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
                    )
                }
            }
        }
        BandDot(color = PawseTheme.semantics.neutral, label = word)
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(12.dp))
        chart()
        Spacer(Modifier.height(8.dp))
        Text(sentence, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Today's energy forecast: when focus is best, and the curve it came from. */
@Composable
private fun EnergyCard(energy: EnergyForecast?, modifier: Modifier = Modifier) {
    AlsoCard(
        glyph = Glyphs.Energy,
        name = tr("Energy", "에너지"),
        value = energy?.let { ScoreCopy.minuteOfDay(it.focusStart) } ?: "—",
        suffix = "",
        word = energy?.let { tr("Best focus to ${ScoreCopy.minuteOfDay(it.focusEnd)}", "${ScoreCopy.minuteOfDay(it.focusEnd)}까지 집중하기 좋음") }
            ?: tr("Not forecast yet", "아직 예측 전"),
        sentence = energy?.let { e ->
            listOfNotNull(
                tr(
                    "Dip around ${ScoreCopy.minuteOfDay(e.dipMinute)}, wind down from ${ScoreCopy.minuteOfDay(e.windDownMinute)}.",
                    "${ScoreCopy.minuteOfDay(e.dipMinute)}쯤 처지고, ${ScoreCopy.minuteOfDay(e.windDownMinute)}부터 잘 준비를 하세요.",
                ),
                e.caffeineAtBedMg.takeIf { it >= 1.0 }?.let {
                    tr("About ${it.roundToInt()} mg of caffeine left at bedtime.", "잠자리에 들 때 카페인이 약 ${it.roundToInt()} mg 남아 있어요.")
                },
                if (e.clockLagMinutes != 0) {
                    tr("Your body clock is still catching up with the time zone.", "생체 시계가 아직 시간대에 적응하는 중이에요.")
                } else {
                    null
                },
            ).joinToString(" ")
        } ?: tr("Appears once Pawse knows when you sleep.", "Pawse가 수면 시간을 알게 되면 나타나요."),
        onClick = null,
        modifier = modifier,
    ) {
        energy?.let { EnergyCurve(it, Modifier.fillMaxWidth().height(36.dp)) }
    }
}

/** Form: fitness against fatigue, and a week of it over the productive band. */
@Composable
private fun FormCard(snapshot: ScoringSnapshot, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val load = snapshot.today.load
    val config = ScoringConfig.DEFAULT.load
    val fitness = load.fitness
    val fatigue = load.fatigue
    val forms = snapshot.week.map { it.load.form?.times(100) }
    AlsoCard(
        glyph = Glyphs.Load,
        name = tr("Form", "폼"),
        value = if (snapshot.showNumbers) load.form?.let { (it * 100).roundToInt().toString() } ?: "—" else null,
        suffix = if (snapshot.showNumbers && load.form != null) "%" else "",
        word = load.formBand?.let(ScoreCopy::form) ?: tr("Building your history", "기록 쌓는 중"),
        sentence = if (fitness != null && fatigue != null) {
            tr("Fitness ${ScoreCopy.load(fitness)}, fatigue ${ScoreCopy.load(fatigue)}.", "체력 ${ScoreCopy.load(fitness)}, 피로 ${ScoreCopy.load(fatigue)}.")
        } else {
            tr("Needs a few weeks of training to read.", "읽으려면 몇 주간의 운동 기록이 필요해요.")
        },
        onClick = onClick,
        modifier = modifier,
    ) {
        if (forms.count { it != null } >= 2) {
            Sparkline(
                values = forms,
                band = config.heavyBelow * 100..config.freshAbove * 100,
                min = null,
                max = null,
                modifier = Modifier.fillMaxWidth().height(36.dp),
            )
        }
    }
}

/** The forecast curve: focus window shaded, caffeine underneath, wind-down line, dip dot. */
@Composable
private fun EnergyCurve(energy: EnergyForecast, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.onSurfaceVariant
    val dot = MaterialTheme.colorScheme.onSurface
    val focus = PawseTheme.semantics.baselineBand
    val muted = MaterialTheme.colorScheme.outline
    val caffeine = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val points = energy.points
    if (points.isEmpty()) return
    val lo = points.minOf { it.alertness }
    val hi = points.maxOf { it.alertness }
    val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
    val maxCaf = points.maxOf { it.caffeineMg }.coerceAtLeast(1.0)
    val first = points.first().minute
    val last = points.last().minute
    Canvas(
        modifier.semantics {
            val focusStart = ScoreCopy.minuteOfDay(energy.focusStart)
            val focusEnd = ScoreCopy.minuteOfDay(energy.focusEnd)
            val dip = ScoreCopy.minuteOfDay(energy.dipMinute)
            val windDown = ScoreCopy.minuteOfDay(energy.windDownMinute)
            contentDescription = tr(
                "Energy forecast. Best focus $focusStart to $focusEnd, dip around $dip, wind down from $windDown.",
                "에너지 예측. ${focusStart}부터 ${focusEnd}까지 집중하기 좋고, ${dip}쯤 처지며, ${windDown}부터 잘 준비를 하세요.",
            )
        },
    ) {
        val pad = 4.dp.toPx()
        fun x(m: Int) = (m - first).toFloat() / (last - first).coerceAtLeast(1) * size.width
        fun y(a: Double) = (size.height - pad) - ((a - lo) / span).toFloat() * (size.height - 2 * pad)
        drawRoundRect(
            focus,
            Offset(x(energy.focusStart), 0f),
            Size(x(energy.focusEnd) - x(energy.focusStart), size.height),
            CornerRadius(3.dp.toPx()),
        )
        if (points.any { it.caffeineMg > 1.0 }) {
            val caf = Path()
            caf.moveTo(x(first), size.height)
            points.forEach { caf.lineTo(x(it.minute), size.height - (it.caffeineMg / maxCaf).toFloat() * size.height * 0.4f) }
            caf.lineTo(x(last), size.height)
            caf.close()
            drawPath(caf, caffeine)
        }
        val path = Path()
        points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.minute), y(p.alertness)) else path.lineTo(x(p.minute), y(p.alertness)) }
        drawPath(path, line, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val wd = x(energy.windDownMinute)
        drawLine(muted, Offset(wd, 0f), Offset(wd, size.height), 1.dp.toPx())
        val dip = points.minByOrNull { kotlin.math.abs(it.minute - energy.dipMinute) }
        if (dip != null) drawCircle(dot, 3.5.dp.toPx(), Offset(x(dip.minute), y(dip.alertness)))
    }
}

/** One tap: how do you feel? Never feeds the score, only the calibration. Plus one-tap tags. */
@Composable
private fun CheckInCard(snapshot: ScoringSnapshot, onCheckIn: (Int) -> Unit, onTag: (DayTag) -> Unit, modifier: Modifier = Modifier) {
    PawseCard(modifier = modifier) {
        Text(tr("How do you feel?", "오늘 기분은 어때요?"), style = PawseType.Heading)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..5).forEach { n ->
                Pill(n.toString(), selected = snapshot.checkIn == n, onClick = { onCheckIn(n) }, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(tr("1 rough · 5 great. It never changes today’s reading.", "1 힘듦 · 5 아주 좋음. 오늘 값은 바뀌지 않아요."), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val feeling = snapshot.checkIn
        val band = snapshot.today.body?.band
        if (feeling != null && band != null && ScoreCopy.disagrees(feeling, band)) {
            Spacer(Modifier.height(6.dp))
            Text(ScoreCopy.DISAGREE, style = PawseType.Label)
        }
        Spacer(Modifier.height(16.dp))
        Text(tr("Anything going on?", "특별한 일이 있나요?"), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DayTag.entries.forEach { tag ->
                Pill(ScoreCopy.tag(tag), selected = tag in snapshot.tags, onClick = { onTag(tag) })
            }
        }
    }
}

@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(40.dp)
            .pressable(onClick = onClick)
            .then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(12.dp))
                } else {
                    Modifier.cardSurface(RoundedCornerShape(12.dp), 12.dp)
                },
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = PawseType.BodyStrong,
            color = if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/** A week as a line over a shaded band; today is the dot. */
@Composable
internal fun Sparkline(
    values: List<Double?>,
    band: ClosedFloatingPointRange<Double>?,
    min: Double?,
    max: Double?,
    modifier: Modifier = Modifier,
) {
    val shade = PawseTheme.semantics.baselineBand
    val line = MaterialTheme.colorScheme.onSurfaceVariant
    val dot = MaterialTheme.colorScheme.onSurface
    val known = values.filterNotNull()
    if (known.isEmpty()) return
    val lo = min ?: (known + listOfNotNull(band?.start)).min()
    val hi = max ?: (known + listOfNotNull(band?.endInclusive)).max()
    val span = (hi - lo).takeIf { it > 0.0 } ?: 1.0
    Canvas(modifier) {
        val pad = 4.dp.toPx()
        fun y(v: Double) = ((size.height - pad) - (v - lo) / span * (size.height - 2 * pad)).toFloat()
        fun x(i: Int) = i * size.width / (values.size - 1).coerceAtLeast(1)
        band?.let {
            val top = y(it.endInclusive)
            drawRoundRect(shade, Offset(0f, top), Size(size.width, y(it.start) - top), CornerRadius(3.dp.toPx()))
        }
        val path = Path()
        var started = false
        values.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            if (!started) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v))
            started = true
        }
        drawPath(path, line, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val last = values.indexOfLast { it != null }
        drawCircle(dot, 3.5.dp.toPx(), Offset(x(last), y(values[last]!!)))
    }
}

@Composable
private fun Notice(
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    PawseCard(modifier = modifier, padding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = if (actionLabel != null) 2.dp else 14.dp)) {
        Row {
            Glyph(Glyphs.Info, size = 18.dp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 1.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(text, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurface)
                if (actionLabel != null) GhostButton(actionLabel, onClick = onAction)
            }
        }
    }
}

internal fun scoreGlyph(type: ScoreType): String = when (type) {
    ScoreType.BODY -> Glyphs.Recovery
    ScoreType.SLEEP -> Glyphs.Sleep
    ScoreType.LOAD -> Glyphs.Load
}

private fun minutes(m: Double): String = if (m >= 60.0) ScoreCopy.formatMinutes(m) else "${m.roundToInt()}${tr(" min", "분")}"
