package app.pawse.feature.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pawse.core.data.body.RecentWeighIn
import app.pawse.core.ui.component.BottomAction
import app.pawse.core.ui.component.EaseOut
import app.pawse.core.ui.component.GhostButton
import app.pawse.core.ui.component.GlyphButton
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.PawseCard
import app.pawse.core.ui.component.PawseHaptic
import app.pawse.core.ui.component.PawseMotion
import app.pawse.core.ui.component.Segmented
import app.pawse.core.ui.component.SelectChip
import app.pawse.core.ui.component.SplitRow
import app.pawse.core.ui.component.aura
import app.pawse.core.ui.component.pressable
import app.pawse.core.ui.component.rememberHaptic
import app.pawse.core.ui.theme.PawseTheme
import app.pawse.core.ui.theme.PawseType
import app.pawse.scoring.tr
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Weigh-in, two ways in. Weight: a scale reading set with the ruler. InBody: a
 * whole result sheet, typed in from the last one or read from a photo of it, where
 * tapping a number hands it the same ruler. What Pawse works out follows live, and
 * a number the sheet doesn't have is marked Don't know rather than guessed.
 */
@Composable
fun WeighInScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WeighInViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val low = PawseTheme.semantics.bandLow
    val grants = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        viewModel.onGrantResult()
    }

    // The sheet photo goes to one cache file, read on the phone and deleted straight after.
    val sheetFile = remember { File(context.cacheDir, "sheet/inbody.jpg") }
    val sheetUri = remember {
        sheetFile.parentFile?.mkdirs()
        FileProvider.getUriForFile(context, "${context.packageName}.sheet", sheetFile)
    }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (taken) viewModel.readSheet(sheetUri, sheetFile) else sheetFile.delete()
    }
    // Pawse holds the camera grant for Food's scanner, so Android asks for it before the camera app opens too.
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) photo.launch(sheetUri)
    }
    val photograph = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            photo.launch(sheetUri)
        } else {
            camera.launch(Manifest.permission.CAMERA)
        }
    }

    val haptic = rememberHaptic()
    LaunchedEffect(state.saved) {
        if (state.saved) {
            haptic(PawseHaptic.Confirm)
            onDone()
        }
    }
    // The sheet was read: confirm, and let the eyes stay on the numbers to check.
    LaunchedEffect(state.fromSheet) {
        if (state.fromSheet) haptic(PawseHaptic.Confirm)
    }
    LaunchedEffect(state.askFor) {
        state.askFor?.let {
            viewModel.onAskLaunched()
            grants.launch(it)
        }
    }

    Scaffold(modifier = modifier, containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(Modifier.fillMaxSize().aura()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    // Room under the last row for the InBody ruler panel and Save, so nothing sits behind them.
                    .padding(top = padding.calculateTopPadding() + 12.dp, bottom = if (state.inBody) 340.dp else 140.dp)
                    .padding(horizontal = 24.dp),
            ) {
                Row(Modifier.offset(x = (-12).dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlyphButton(Glyphs.ChevronLeft, tr("Back to Body", "신체로 돌아가기"), onClick = onBack)
                    Text(tr("Weigh-in", "체중 기록"), style = PawseType.Heading, modifier = Modifier.semantics { heading() })
                }
                Segmented(
                    options = listOf(false, true),
                    selected = state.inBody,
                    onSelect = viewModel::setInBody,
                    label = { if (it) "InBody" else tr("Weight", "체중") },
                    glyph = { if (it) Glyphs.Sheet else Glyphs.Body },
                    height = 48.dp,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    if (state.inBody) {
                        tr("A full InBody sheet: weight, muscle, fat and visceral fat", "인바디 결과지 전체: 체중, 근육, 체지방, 내장지방")
                    } else {
                        tr("A scale reading, set with the ruler", "체중계 측정값을 눈금자로 입력")
                    },
                    style = PawseType.Micro,
                    color = PawseTheme.semantics.faint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )

                if (state.inBody) {
                    InBodyForm(state, viewModel, onPhoto = photograph)
                } else {
                    ScaleReading(state, viewModel)
                }
            }

            BottomAction(
                text = state.saveLabel,
                onClick = viewModel::save,
                enabled = !state.busy && !state.reading,
                height = 56.dp,
                above = {
                    state.problem?.let { problem ->
                        Text(problem, style = PawseType.Label, color = low)
                        if (problem == WeighInViewModel.NOT_ALLOWED) {
                            GhostButton(
                                tr("Open Health Connect", "헬스 커넥트 열기"),
                                onClick = { runCatching { context.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) } },
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    // The InBody ruler sits here, under the rows it sets, so it never scrolls away.
                    if (state.inBody && !state.reading) {
                        val row = state.picked
                        Text(
                            "${row.label} · ${row.format(state.value(row))}",
                            style = PawseType.Micro,
                            color = PawseTheme.semantics.faint,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // A new row re-centres the ruler on its value at once, with no slide.
                        key(row) {
                            ValueRuler(
                                value = state.value(row),
                                onValue = { viewModel.setRow(row, it) },
                                min = row.min,
                                max = state.max(row),
                                step = row.step,
                                major = if (row.step < 1.0) 10 else 5,
                                spacing = if (row.step < 1.0) 10.dp else 20.dp,
                                name = row.label,
                                valueText = row.format(state.value(row)),
                                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                            )
                        }
                    }
                },
            )
        }
    }
}

/** Weight: today's reading at 64, the change since last time, the ruler, and what it is read against. */
@Composable
private fun ScaleReading(state: WeighInUiState, viewModel: WeighInViewModel) {
    val onv = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(top = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(whenText(System.currentTimeMillis()), style = PawseType.Label, color = onv)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(WeighInUiState.oneDecimal(state.kg), style = PawseType.Hero)
            Text("kg", style = PawseType.Label, color = onv, modifier = Modifier.padding(start = 6.dp, bottom = 12.dp))
        }
        Text(delta(state.kg, state.lastKg), style = PawseType.Micro, color = PawseTheme.semantics.faint)
    }
    ValueRuler(
        value = state.kg,
        onValue = viewModel::setKg,
        min = WeighInUiState.MIN_KG,
        max = WeighInUiState.MAX_KG,
        step = 0.1,
        major = 10,
        // A kilogram is 100 dp: ten ticks of 10.
        spacing = 10.dp,
        name = tr("Weight", "체중"),
        valueText = WeighInUiState.oneDecimal(state.kg) + " kg",
        modifier = Modifier.padding(top = 32.dp),
    )
    Text(
        tr("Drag or fling. It settles on the nearest 0.1 kg.", "끌거나 튕기세요. 가장 가까운 0.1kg에 멈춰요."),
        style = PawseType.Micro,
        color = PawseTheme.semantics.faint,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    )
    if (state.recent.isNotEmpty()) Recent(state, viewModel)
}

private fun delta(kg: Double, last: Double?): String {
    last ?: return tr("Your first weigh-in here", "여기서 첫 체중 기록")
    val d = (kg * 10).roundToInt() / 10.0 - last
    return if (abs(d) < 0.05) {
        tr("Same as last time", "지난번과 같아요")
    } else {
        val signed = (if (d > 0) "+" else "−") + WeighInUiState.oneDecimal(abs(d))
        tr("$signed kg since last time", "지난번보다 ${signed}kg")
    }
}

/** What a weigh-in is read against: the trend and the last few readings. */
@Composable
private fun Recent(state: WeighInUiState, viewModel: WeighInViewModel) {
    PawseCard(modifier = Modifier.padding(top = 24.dp)) {
        SplitRow {
            Caption(tr("Recent weigh-ins", "최근 체중 기록"), Modifier.weight(1f))
            state.trendKg?.let { Text(tr("trend ${WeighInUiState.oneDecimal(it)} kg", "추세 ${WeighInUiState.oneDecimal(it)}kg"), style = PawseType.Micro, color = PawseTheme.semantics.faint) }
        }
        state.recent.forEach { entry ->
            RecentRow(
                entry = entry,
                removed = entry.atEpochMs in state.removed,
                onRemove = { viewModel.remove(entry) },
                onUndo = { viewModel.undoRemove(entry.atEpochMs) },
            )
        }
    }
}

/** InBody: the photo, the four numbers with Don't know, and what Pawse works out from them. */
@Composable
private fun InBodyForm(state: WeighInUiState, viewModel: WeighInViewModel, onPhoto: () -> Unit) {
    if (!state.reading) {
        GhostButton(tr("Photograph the result sheet", "결과지 찍기"), onClick = onPhoto, glyph = Glyphs.Camera, modifier = Modifier.padding(top = 16.dp))
        Caption(state.readNote ?: tr("Read on this phone. The photo is not kept.", "휴대폰에서 바로 읽어요. 사진은 남기지 않아요."))
    } else {
        PawseCard(modifier = Modifier.padding(top = 24.dp).semantics { contentDescription = tr("Reading the result sheet", "결과지 읽는 중") }) {
            Text(tr("Reading the result sheet…", "결과지 읽는 중…"), style = PawseType.BodyStrong)
            LinearProgressIndicator(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                trackColor = PawseTheme.semantics.baselineBand,
                modifier = Modifier.padding(top = 12.dp).fillMaxWidth().height(2.dp),
            )
        }
        return
    }

    val since = state.lastDate?.format(DateTimeFormatter.ofPattern(tr("d MMM", "M월 d일"), Locale.getDefault()))
    Text(
        tr("The weight counts as today’s weigh-in. ", "체중은 오늘의 체중 기록이 돼요. ") +
            when {
                state.fromSheet -> tr(
                    "Read from the sheet. Check each number against it: tap one, then drag the ruler to correct it.",
                    "결과지에서 읽었어요. 하나씩 결과지와 비교하고, 누른 다음 눈금자를 끌어 고쳐 주세요.",
                )
                since != null -> tr(
                    "Starts from your last result, $since. Tap a number, then drag or fling the ruler.",
                    "지난 결과($since)에서 시작해요. 숫자를 누르고 눈금자를 끌거나 튕기세요.",
                )
                else -> tr("Tap a number, then drag or fling the ruler.", "숫자를 누르고 눈금자를 끌거나 튕기세요.")
            } +
            tr(" Not on your sheet? Mark it Don’t know: it is left out, never guessed.", " 결과지에 없나요? 모름으로 두면 빼고 저장해요. 추측하지 않아요."),
        style = PawseType.Micro,
        color = PawseTheme.semantics.faint,
        modifier = Modifier.padding(top = 16.dp),
    )

    PawseCard(modifier = Modifier.padding(top = 8.dp), padding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
        InBodyRow.entries.forEach { row -> InBodyLine(row, state, onPick = { viewModel.pick(row) }, onUnknown = { viewModel.toggleUnknown(row) }) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 8.dp))
        val needs = tr("needs body fat mass", "체지방량이 필요해요")
        Derived(tr("Body fat", "체지방률"), if (state.fatMass != null) tr("fat mass ÷ weight", "체지방량 ÷ 체중") else needs, state.fatPercent?.let { WeighInUiState.oneDecimal(it) + "%" })
        Derived(tr("Lean mass", "제지방량"), if (state.fatMass != null) tr("weight − fat mass", "체중 − 체지방량") else needs, state.leanKg?.let { WeighInUiState.oneDecimal(it) + " kg" })
        Derived(
            tr("Basal metabolic rate", "기초대사량"),
            if (state.fatMass != null) tr("370 + 21.6 × lean mass (Katch–McArdle)", "370 + 21.6 × 제지방량 (Katch–McArdle)") else needs,
            state.bmr?.let { "%,d kcal".format(it.roundToInt()) },
        )
    }
    Caption(
        tr(
            "Weight, body fat, lean mass and basal metabolic rate go to Health Connect. " +
                "Skeletal muscle and visceral fat stay in Pawse: Health Connect has no record for them.",
            "체중, 체지방, 제지방량, 기초대사량은 헬스 커넥트에 저장해요. " +
                "골격근량과 내장지방은 헬스 커넥트에 항목이 없어서 Pawse에만 남아요.",
        ),
        Modifier.padding(top = 12.dp),
    )
}

/** One InBody number: tap it to set it with the ruler; Don't know leaves it out. */
@Composable
private fun InBodyLine(row: InBodyRow, state: WeighInUiState, onPick: () -> Unit, onUnknown: () -> Unit) {
    val picked = row == state.picked && row !in state.unknown
    val unknown = row in state.unknown
    val fill by animateColorAsState(
        if (picked) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
        tween(PawseMotion.PRESS_UP, easing = EaseOut),
        label = "row",
    )
    val shown = if (unknown) "—" else row.format(state.value(row))
    val note = when {
        unknown && state.fromSheet -> tr("couldn’t read it · tap to set it", "읽지 못했어요 · 눌러서 입력")
        unknown -> tr("left out · tap to set it", "빼고 저장 · 눌러서 입력")
        state.fromSheet -> tr("read from the sheet", "결과지에서 읽음")
        else -> tr("last result ", "지난 결과 ") + (state.last[row]?.let(row::format) ?: tr("not known", "모름"))
    }
    // The picked row is raised with a hairline: it is the one the ruler below is setting.
    val edge by animateColorAsState(
        if (picked) MaterialTheme.colorScheme.outline else Color.Transparent,
        tween(PawseMotion.PRESS_UP, easing = EaseOut),
        label = "rowEdge",
    )
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(fill).border(1.dp, edge, RoundedCornerShape(12.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 60.dp)
                .pressable(onClick = onPick)
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    selected = picked
                    stateDescription = if (picked) tr("set with the ruler below", "아래 눈금자로 조정") else tr("tap to set with the ruler", "눌러서 눈금자로 조정")
                }
                .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(row.label, style = PawseType.Body)
                Text(note, style = PawseType.Micro, color = PawseTheme.semantics.faint)
            }
            Text(shown, style = PawseType.MetricValue, color = if (unknown) PawseTheme.semantics.faint else MaterialTheme.colorScheme.onSurface)
        }
        if (row != InBodyRow.WEIGHT) {
            SelectChip(tr("Don’t know", "모름"), selected = unknown, onClick = onUnknown, modifier = Modifier.padding(end = 6.dp))
        }
    }
}

@Composable
private fun Derived(name: String, how: String, value: String?) {
    SplitRow(Modifier.padding(top = 8.dp, start = 10.dp, end = 10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(name, style = PawseType.Body)
            Text(how, style = PawseType.Micro, color = PawseTheme.semantics.faint)
        }
        Text(value ?: "—", style = PawseType.MetricValue, color = if (value == null) PawseTheme.semantics.faint else MaterialTheme.colorScheme.onSurface)
    }
}

/** The InBody sheet photo's one file, shared with the camera app for the length of one shot. */
class SheetFileProvider : FileProvider()

@Composable
private fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(text, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

private fun source(entry: RecentWeighIn): String = when {
    entry.typedIn -> tr("Typed in", "직접 입력")
    entry.sourceLabel != null -> tr("From ${entry.sourceLabel}", "${entry.sourceLabel}에서")
    else -> tr("From another app", "다른 앱에서")
}

private fun whenText(epochMs: Long): String {
    val date = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
    val day = when (date) {
        LocalDate.now() -> tr("Today", "오늘")
        LocalDate.now().minusDays(1) -> tr("Yesterday", "어제")
        else -> date.format(DateTimeFormatter.ofPattern(tr("EEE d MMM", "M월 d일 (E)"), Locale.getDefault()))
    }
    return "$day, ${ScoreCopy.clock(epochMs)}"
}

@Composable
private fun RecentRow(entry: RecentWeighIn, removed: Boolean, onRemove: () -> Unit, onUndo: () -> Unit) {
    val onv = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (removed) {
            Text(tr("Removed, here and in Health Connect", "여기와 헬스 커넥트에서 삭제됨"), style = PawseType.Body, color = onv, modifier = Modifier.weight(1f))
            Box(
                Modifier
                    .offset(x = 10.dp)
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .pressable(onClick = onUndo)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(tr("Undo", "되돌리기"), style = PawseType.BodyStrong)
            }
        } else {
            val whenText = whenText(entry.atEpochMs)
            val kg = "${WeighInUiState.oneDecimal(entry.weightKg)} kg"
            Column(Modifier.weight(1f)) {
                Text(whenText, style = PawseType.Body)
                Text(source(entry), style = PawseType.Micro, color = PawseTheme.semantics.faint)
            }
            Text(kg, style = PawseType.MetricValue)
            if (entry.typedIn) {
                GlyphButton(Glyphs.Trash, tr("Remove $kg from $whenText", "$whenText $kg 삭제"), onClick = onRemove, modifier = Modifier.offset(x = 10.dp))
            }
        }
    }
}

// --- The ruler -----------------------------------------------------------------

/** The tick under the centre line. Offsets are measured from the start padding, so the middle is too. */
private fun LazyListState.centered(): LazyListItemInfo? {
    val info = layoutInfo
    val middle = (info.viewportStartOffset + info.viewportEndOffset) / 2
    return info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - middle) }
}

/**
 * A ruler dragged sideways under a fixed centre line: a tick per [step], taller
 * every half major, labelled every [major] steps. Weight and every InBody number
 * use it. For TalkBack it is a slider that moves one step at a time.
 */
@Composable
private fun ValueRuler(
    value: Double,
    onValue: (Double) -> Unit,
    min: Double,
    max: Double,
    step: Double,
    major: Int,
    spacing: Dp,
    name: String,
    valueText: String,
    modifier: Modifier = Modifier,
) {
    val count = ((max - min) / step).roundToInt() + 1
    fun tickOf(v: Double): Int = ((v - min) / step).roundToInt().coerceIn(0, count - 1)
    fun valueOf(tick: Int): Double = ((min + tick * step) / step).roundToInt() * step
    val state = rememberLazyListState(initialFirstVisibleItemIndex = tickOf(value))
    val latest by rememberUpdatedState(value)
    val latestOnValue by rememberUpdatedState(onValue)
    val haptic = rememberHaptic()
    val measurer = rememberTextMeasurer()
    // The canvas's ruler: grey ticks on the page, no box; the labelled ones a step lighter.
    val minor = PawseTheme.semantics.mark
    val labelledTick = MaterialTheme.colorScheme.onSurfaceVariant
    val strong = MaterialTheme.colorScheme.onSurface
    val labelStyle = PawseType.Label.copy(color = PawseTheme.semantics.faint)

    // A drag or fling moves the number a step per tick, harder on each labelled one.
    // Only while scrolling, so opening the screen never sets a value the user didn't choose.
    LaunchedEffect(state) {
        snapshotFlow { state.centered()?.index }.collect { tick ->
            if (tick != null && state.isScrollInProgress && tick != tickOf(latest)) {
                val units = (valueOf(tick) / step).roundToInt()
                // 0.1 kg under the thumb; whole kilograms a firmer tick.
                haptic(if (units % major == 0) PawseHaptic.FineTick else PawseHaptic.Ruler)
                latestOnValue(valueOf(tick))
            }
        }
    }

    // A value set elsewhere (TalkBack, a photo) moves the ruler. Skipped mid-drag, so the two never fight.
    LaunchedEffect(value) {
        val target = tickOf(value)
        if (!state.isScrollInProgress && state.centered()?.index != target) state.scrollToItem(target)
    }

    // A fling comes to rest on a tick, never between two.
    LaunchedEffect(state.isScrollInProgress) {
        if (state.isScrollInProgress) return@LaunchedEffect
        val info = state.layoutInfo
        val nearest = state.centered() ?: return@LaunchedEffect
        val delta = nearest.offset + nearest.size / 2 - (info.viewportStartOffset + info.viewportEndOffset) / 2
        if (delta != 0) state.animateScrollBy(delta.toFloat())
    }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(92.dp)
            .clearAndSetSemantics {
                contentDescription = name
                stateDescription = valueText
                progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), min.toFloat()..max.toFloat(), steps = (count - 2).coerceAtLeast(0))
                setProgress { target ->
                    latestOnValue(valueOf(tickOf(target.toDouble())))
                    true
                }
            },
    ) {
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = (maxWidth - spacing) / 2),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.horizontalGradient(0f to Color.Transparent, 0.18f to Color.Black, 0.82f to Color.Black, 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            items(count) { tick ->
                Canvas(Modifier.width(spacing).fillMaxHeight()) {
                    val x = size.width / 2
                    val units = (valueOf(tick) / step).roundToInt()
                    val labelled = units % major == 0
                    val length = when {
                        labelled -> 34.dp
                        major % 2 == 0 && units % (major / 2) == 0 -> 24.dp
                        else -> 14.dp
                    }
                    drawLine(if (labelled) labelledTick else minor, Offset(x, 0f), Offset(x, length.toPx()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                    if (labelled) {
                        val label = measurer.measure(valueOf(tick).roundToInt().toString(), labelStyle)
                        drawText(label, topLeft = Offset(x - label.size.width / 2f, 40.dp.toPx()))
                    }
                }
            }
        }
        // The fixed centre line the ticks pass under.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .width(3.dp)
                .height(44.dp)
                .background(strong, RoundedCornerShape(1.5.dp)),
        )
    }
}
