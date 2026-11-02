package app.pawse.feature.home

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pawse.core.model.Answer
import app.pawse.core.model.Engine
import app.pawse.core.model.ModelState
import app.pawse.core.model.PawseModel
import app.pawse.core.model.Summary
import app.pawse.core.model.Turn
import app.pawse.core.ui.component.Chip
import app.pawse.core.ui.component.EaseOut
import app.pawse.core.ui.component.Glyph
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.LocalReduceMotion
import app.pawse.core.ui.component.PawseSheet
import app.pawse.core.ui.component.PrimaryButton
import app.pawse.core.ui.component.SecondaryButton
import app.pawse.core.ui.component.cardSurface
import app.pawse.core.ui.component.pressable
import app.pawse.core.ui.theme.PawseTheme
import app.pawse.core.ui.theme.PawseType
import app.pawse.scoring.tr
import java.time.LocalDate
import java.util.Locale
import kotlin.math.ceil

// --- Scores ------------------------------------------------------------------------

/**
 * The model's sheets on Scores: this morning's summary, which rises once the first
 * time Pawse opens after the night, and Ask Pawse. One call from the screen.
 */
@Composable
internal fun ModelSheets(viewModel: AssistantViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val summary = ui.summary
    var rising by remember(summary?.date) { mutableStateOf(false) }
    LaunchedEffect(summary?.date, ui.rises) {
        if (summary != null && ui.rises) {
            rising = true
            viewModel.summaryRose()
        }
    }
    if (rising && summary != null) MorningSheet(summary, onDone = { rising = false })
    if (ui.asking) AskSheet(ui.turns, onAsk = viewModel::ask, onDismiss = viewModel::closeAsk)
}

/** The summary sheet, risen once a morning. No typing animation: the text is there whole. */
@Composable
private fun MorningSheet(summary: Summary, onDone: () -> Unit) {
    val faint = PawseTheme.semantics.faint
    PawseSheet(onDismiss = onDone) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val at = ScoreCopy.clock(summary.writtenAtEpochMs)
            Text(tr("$at · after last night", "$at · 지난밤 이후"), style = PawseType.Micro, color = faint, modifier = Modifier.weight(1f))
            Chip(WRITTEN)
        }
        Spacer(Modifier.height(12.dp))
        Text(tr("Good morning", "좋은 아침이에요"), style = PawseType.Heading)
        Spacer(Modifier.height(8.dp))
        Text(summary.text, style = Reading)
        Spacer(Modifier.height(12.dp))
        Text(CHECKED, style = PawseType.Micro, color = faint)
        Spacer(Modifier.height(24.dp))
        PrimaryButton(tr("See today", "오늘 보기"), onClick = onDone)
        Spacer(Modifier.height(12.dp))
        Text(
            tr("It waits at the bottom of Scores until tomorrow.", "내일까지 점수 화면 맨 아래에 있어요."),
            style = PawseType.Micro,
            color = faint,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The card at the bottom of Scores, today only, ending in the one row that opens Ask Pawse. */
@Composable
internal fun MorningSummaryCard(modifier: Modifier = Modifier, viewModel: AssistantViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    if (!ui.ready) return
    val summary = ui.summary
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val text = summary?.text ?: if (ui.firstDay) {
        tr(
            "Your first morning summary comes tomorrow, after the night is read. Ask Pawse works now.",
            "첫 아침 요약은 내일, 밤 기록을 읽은 뒤에 나와요. Pawse에게 묻기는 지금 쓸 수 있어요.",
        )
    } else {
        null
    }
    Column(
        modifier
            .fillMaxWidth()
            .fadeUp(rise = 8.dp, blur = 2.dp, millis = 280)
            .cardSurface(),
    ) {
        if (text != null) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("This morning", "오늘 아침"), style = PawseType.Label, color = grey, modifier = Modifier.weight(1f))
                    summary?.let { Chip(if (it.written) WRITTEN else tr("Pawse’s own line", "Pawse 기본 문장")) }
                }
                Spacer(Modifier.height(8.dp))
                Text(text, style = PawseType.Body)
                if (summary?.written == true) {
                    Spacer(Modifier.height(8.dp))
                    Text(CHECKED, style = PawseType.Micro, color = PawseTheme.semantics.faint)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .pressable(onClick = viewModel::openAsk)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(tr("Ask Pawse about today", "오늘에 대해 Pawse에게 묻기"), style = PawseType.BodyStrong, modifier = Modifier.weight(1f))
            Glyph(Glyphs.ChevronRight, size = 18.dp, color = grey)
        }
    }
}

// --- Ask Pawse ---------------------------------------------------------------------

/**
 * Ask Pawse. The disclaimer opens every conversation and a reminder sits under the
 * field. Answers say how far they can be trusted.
 */
@Composable
private fun AskSheet(turns: List<Turn>, onAsk: (String) -> Unit, onDismiss: () -> Unit) {
    var question by rememberSaveable { mutableStateOf("") }
    val writing = turns.any { it.answer == null }
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val faint = PawseTheme.semantics.faint
    val scroll = rememberScrollState()
    LaunchedEffect(turns) { scroll.animateScrollTo(scroll.maxValue) }
    fun send(text: String) {
        if (text.isBlank() || writing) return
        onAsk(text)
        question = ""
    }

    PawseSheet(onDismiss = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Ask Pawse", "Pawse에게 묻기"), style = PawseType.Heading, modifier = Modifier.weight(1f))
            Chip(tr("Small model on this phone", "휴대폰 속 작은 모델"))
        }
        Spacer(Modifier.height(12.dp))
        BoxWithConstraints {
            val bubble = maxWidth * 0.82f
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(scroll),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(PawseTheme.semantics.baselineOuter, RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(tr("A small model, running on this phone", "이 휴대폰에서 동작하는 작은 모델"), style = PawseType.BodyStrong)
                    Spacer(Modifier.height(4.dp))
                    Text(DISCLAIMER, style = PawseType.Label, color = grey)
                }
                if (turns.isEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(tr("Try", "이렇게 물어보세요"), style = PawseType.Label, color = grey)
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SUGGESTIONS.forEach { s -> Suggestion(s, onClick = { send(s) }) }
                    }
                }
                turns.forEach { turn ->
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text(
                            turn.question,
                            style = PawseType.Body,
                            modifier = Modifier
                                .widthIn(max = bubble)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh, QuestionShape)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    val answer = turn.answer
                    if (answer == null) {
                        Text(tr("Writing on this phone…", "이 휴대폰에서 쓰는 중…"), style = PawseType.Micro, color = faint)
                    } else {
                        Column(Modifier.fadeUp(rise = 4.dp, blur = 2.dp, millis = 200)) {
                            Text(answer.text, style = PawseType.Body)
                            kindLabel(answer.kind)?.let {
                                Spacer(Modifier.height(4.dp))
                                Text(it, style = PawseType.Micro, color = faint)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.imePadding(), verticalAlignment = Alignment.CenterVertically) {
            AskField(question, onValueChange = { question = it.take(MAX_QUESTION) }, onSend = { send(question) }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            SendButton(enabled = question.isNotBlank() && !writing, onClick = { send(question) })
        }
        Spacer(Modifier.height(10.dp))
        Text(
            tr("Small model · can be wrong · not medical advice", "작은 모델 · 틀릴 수 있음 · 의학적 조언 아님"),
            style = PawseType.Micro,
            color = faint,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The 48 dp pill input: track fill, a hairline, the placeholder in faint. */
@Composable
private fun AskField(value: String, onValueChange: (String) -> Unit, onSend: () -> Unit, modifier: Modifier = Modifier) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val track = PawseTheme.semantics.baselineOuter
    val line = MaterialTheme.colorScheme.outlineVariant
    val faint = PawseTheme.semantics.faint
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = PawseType.Body.copy(color = onSurface),
        cursorBrush = SolidColor(onSurface),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onSend() }),
        modifier = modifier,
        decorationBox = { inner ->
            Box(
                Modifier
                    .height(48.dp)
                    .background(track, CircleShape)
                    .border(1.dp, line, CircleShape)
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(
                        tr("Ask about your sleep, Body or a small habit", "수면, 컨디션, 작은 습관에 대해 물어보세요"),
                        style = PawseType.Body,
                        color = faint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                inner()
            }
        },
    )
}

/** The round send button in the selection fill. */
@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    val s = PawseTheme.semantics
    Box(
        Modifier
            .size(48.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(s.selection)
            .pressable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = tr("Send", "보내기") },
        contentAlignment = Alignment.Center,
    ) {
        Glyph(UP_ARROW, size = 20.dp, strokeWidth = 2f, color = s.onSelection)
    }
}

/** A suggested question: tap to ask it. */
@Composable
private fun Suggestion(text: String, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).pressable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = PawseType.Label,
            maxLines = 1,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

private fun kindLabel(kind: Answer.Kind): String? = when (kind) {
    Answer.Kind.CHECKED -> tr("Checked against today’s numbers", "오늘 수치와 대조함")
    Answer.Kind.GENERAL -> tr("General tip · can be wrong", "일반적인 팁 · 틀릴 수 있음")
    Answer.Kind.SYMPTOM -> tr("Pawse’s fixed answer", "Pawse의 정해진 답")
    Answer.Kind.UNCHECKED -> null
}

// --- Settings ----------------------------------------------------------------------

/** The model's switch in the settings sheet, and the download sheet it opens. One call from the sheet. */
@Composable
internal fun ModelSettingsSection(viewModel: AssistantViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val state = settings.state
    var sheet by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val grey = colors.onSurfaceVariant

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(MODEL_TITLE, style = PawseType.Heading, modifier = Modifier.weight(1f))
        Switch(
            checked = state != ModelState.Off,
            onCheckedChange = { on ->
                viewModel.setModel(on)
                sheet = on
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.background,
                checkedTrackColor = colors.onSurface,
                uncheckedThumbColor = colors.onSurfaceVariant,
                uncheckedTrackColor = colors.surfaceContainerHigh,
                uncheckedBorderColor = colors.outline,
            ),
        )
    }
    Spacer(Modifier.height(6.dp))
    Text(
        tr(
            "Writes your morning summary and answers simple questions, only on this phone. It can be wrong and is not medical advice.",
            "아침 요약을 쓰고 간단한 질문에 답해요. 이 휴대폰에서만 동작해요. 틀릴 수 있고 의학적 조언이 아니에요.",
        ),
        style = PawseType.Label,
        color = grey,
    )
    when (state) {
        ModelState.Off -> if (settings.unsupported) {
            Spacer(Modifier.height(6.dp))
            Text(
                tr(
                    "This phone can’t run it: it needs a processor from about 2018 on and 3 GB of memory, or Gemini Nano.",
                    "이 휴대폰에서는 쓸 수 없어요. 2018년 무렵 이후의 프로세서와 3GB 메모리, 또는 Gemini Nano가 필요해요.",
                ),
                style = PawseType.Label,
                color = grey,
            )
        }
        ModelState.NotDownloaded, ModelState.Failed -> TextAction(tr("Download the model", "모델 다운로드"), onClick = { sheet = true })
        is ModelState.Downloading -> Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .pressable { sheet = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(tr("Getting the model ready · ", "모델 준비 중 · ") + PawseModel.downloaded(state.bytes), style = PawseType.Label, modifier = Modifier.weight(1f))
            Glyph(Glyphs.ChevronRight, size = 18.dp, color = grey)
        }
        is ModelState.Ready -> {
            Spacer(Modifier.height(6.dp))
            Text(
                if (state.engine == Engine.NANO) {
                    tr("Using Gemini Nano, already on this phone. Nothing was downloaded.", "이 휴대폰에 이미 있는 Gemini Nano를 써요. 다운로드한 건 없어요.")
                } else {
                    tr("LFM3-1.7B by Liquid AI, under the LFM Open License v1.0. Kept only on this phone.", "Liquid AI의 LFM3-1.7B, LFM Open License v1.0. 이 휴대폰에만 있어요.")
                },
                style = PawseType.Label,
                color = grey,
            )
            if (state.since == LocalDate.now()) {
                Text(tr("Ask Pawse works now. The first summary comes tomorrow morning.", "이제 Pawse에게 물어볼 수 있어요. 첫 요약은 내일 아침에 나와요."), style = PawseType.Label, color = grey)
            }
        }
    }
    if (settings.dropped > 0 && state != ModelState.Off) {
        Spacer(Modifier.height(6.dp))
        Text(
            tr("Sentences the number check has left out so far: ${settings.dropped}", "지금까지 숫자 확인에서 뺀 문장: ${settings.dropped}개"),
            style = PawseType.Micro,
            color = PawseTheme.semantics.faint,
        )
    }

    if (sheet && (state == ModelState.NotDownloaded || state == ModelState.Failed || state is ModelState.Downloading)) {
        val cancel = {
            viewModel.setModel(false)
            sheet = false
        }
        PawseSheet(onDismiss = { sheet = false }) {
            if (state is ModelState.Downloading) {
                DownloadProgress(state, onMobileData = { viewModel.download(true) }, onKeep = { sheet = false }, onCancel = cancel)
            } else {
                BeforeDownload(failed = state == ModelState.Failed, room = settings.room, onDownload = { viewModel.download(false) }, onNotNow = cancel)
            }
        }
    }
}

/** The download, asked for with its size before anything moves. */
@Composable
private fun ColumnScope.BeforeDownload(failed: Boolean, room: Boolean, onDownload: () -> Unit, onNotNow: () -> Unit) {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val gb = String.format(Locale.getDefault(), "%.2f", PawseModel.BYTES / 1e9)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(MODEL_TITLE, style = PawseType.Heading, modifier = Modifier.weight(1f))
        Chip(tr("About $gb GB", "약 ${gb}GB"))
    }
    Spacer(Modifier.height(8.dp))
    Text(
        tr(
            "A small model that writes your morning summary and answers simple questions. It runs on this phone: " +
                "nothing you type or measure leaves it. It downloads once, on Wi-Fi, and needs about 1.3 GB free while it unpacks.",
            "아침 요약을 쓰고 간단한 질문에 답하는 작은 모델이에요. 이 휴대폰에서 동작해서 입력하거나 측정한 어떤 것도 " +
                "밖으로 나가지 않아요. Wi-Fi로 한 번만 내려받고, 푸는 동안 약 1.3GB의 여유 공간이 필요해요.",
        ),
        style = PawseType.Label,
        color = grey,
    )
    Spacer(Modifier.height(8.dp))
    Text(tr("It can be wrong and is not medical advice.", "틀릴 수 있고 의학적 조언이 아니에요."), style = PawseType.Label, color = grey)
    if (failed) {
        Spacer(Modifier.height(8.dp))
        Text(tr("The last download didn’t finish. Nothing was kept.", "지난 다운로드가 끝나지 않았어요. 남은 건 없어요."), style = PawseType.Label, color = grey)
    }
    Spacer(Modifier.height(24.dp))
    PrimaryButton(tr("Download on Wi-Fi", "Wi-Fi로 다운로드"), enabled = room, onClick = onDownload)
    if (!room) {
        Spacer(Modifier.height(8.dp))
        Text(
            tr("Free up about 1.3 GB first.", "먼저 약 1.3GB를 비워 주세요."),
            style = PawseType.Micro,
            color = PawseTheme.semantics.faint,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    TextAction(tr("Not now", "나중에"), onClick = onNotNow)
}

/** The download as it runs: megabytes, percent, a real bar and the time left. Never a spinner. */
@Composable
private fun ColumnScope.DownloadProgress(
    state: ModelState.Downloading,
    onMobileData: () -> Unit,
    onKeep: () -> Unit,
    onCancel: () -> Unit,
) {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val done = (state.bytes.toDouble() / PawseModel.BYTES).coerceIn(0.0, 1.0).toFloat()
    Text(tr("Getting the model ready", "모델 준비 중"), style = PawseType.Heading)
    Spacer(Modifier.height(16.dp))
    Row {
        Text(PawseModel.downloaded(state.bytes), style = PawseType.Label, modifier = Modifier.weight(1f))
        Text("${(done * 100).toInt()}%", style = PawseType.Label)
    }
    Spacer(Modifier.height(8.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(PawseTheme.semantics.baselineOuter)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(done, 0f..1f) },
    ) {
        Box(
            Modifier
                .fillMaxWidth(done)
                .fillMaxHeight()
                .background(grey, RoundedCornerShape(3.dp)),
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(timeLeft(state), style = PawseType.Label, color = grey)
    if (state.waiting && state.wifiOnly) TextAction(tr("Use mobile data instead", "모바일 데이터로 받기"), onClick = onMobileData)
    Spacer(Modifier.height(24.dp))
    SecondaryButton(tr("Keep using Pawse", "Pawse 계속 쓰기"), onClick = onKeep, modifier = Modifier.fillMaxWidth())
    TextAction(tr("Cancel download", "다운로드 취소"), onClick = onCancel)
}

/** From the rate since this sheet opened; nothing until there is enough to go on. */
@Composable
private fun timeLeft(state: ModelState.Downloading): String {
    val start = remember(state.waiting) { System.currentTimeMillis() to state.bytes }
    if (state.waiting) {
        return if (state.wifiOnly) tr("Waiting for Wi-Fi", "Wi-Fi를 기다리는 중") else tr("Waiting for a connection", "연결을 기다리는 중")
    }
    val got = state.bytes - start.second
    val ms = System.currentTimeMillis() - start.first
    if (got < MIN_SAMPLE_BYTES || ms < MIN_SAMPLE_MS) return tr("Working out the time left", "남은 시간 계산 중")
    val minutes = ceil((PawseModel.BYTES - state.bytes).toDouble() * ms / got / 60_000).toInt()
    return if (minutes <= 1) tr("Less than a minute left", "1분도 안 남았어요") else tr("About $minutes min left", "약 ${minutes}분 남았어요")
}

/** A full-width text button. */
@Composable
private fun TextAction(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = PawseType.BodyStrong, color = MaterialTheme.colorScheme.onSurface)
    }
}

// --- Shared ------------------------------------------------------------------------

/** Fades up [rise] out of a [blur] on first showing; a plain fade under reduce motion. */
@Composable
private fun Modifier.fadeUp(rise: Dp, blur: Dp, millis: Int): Modifier {
    val reduce = LocalReduceMotion.current
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(millis, easing = EaseOut)) }
    return graphicsLayer {
        val hidden = 1f - shown.value
        alpha = shown.value
        if (!reduce) {
            translationY = hidden * rise.toPx()
            // Blur needs Android 12; older phones get the fade and the rise.
            renderEffect = if (hidden > 0f && Build.VERSION.SDK_INT >= 31) {
                BlurEffect(hidden * blur.toPx(), hidden * blur.toPx(), TileMode.Decal)
            } else {
                null
            }
        }
    }
}

private val Reading = PawseType.Body.copy(fontSize = 16.sp, lineHeight = 24.sp)
private val QuestionShape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomEnd = 4.dp, bottomStart = 14.dp)
private const val UP_ARROW = "M12 19V5M6 11l6-6 6 6"
private const val MAX_QUESTION = 300
private const val MIN_SAMPLE_BYTES = 5_000_000L
private const val MIN_SAMPLE_MS = 3_000L

private val MODEL_TITLE: String get() = tr("Model on this phone", "휴대폰 속 모델")
private val WRITTEN: String get() = tr("Written on this phone", "이 휴대폰에서 작성")
private val CHECKED: String get() = tr("Every number matches this morning’s readings.", "모든 숫자가 오늘 아침 측정값과 맞아요.")

private val SUGGESTIONS: List<String>
    get() = listOf(
        tr("Why is my Body where it is?", "컨디션이 왜 이런가요?"),
        tr("How did I sleep against my need?", "필요 수면과 비교해 어떻게 잤나요?"),
        tr("A small tip for winding down", "잠들기 전 작은 팁"),
    )

private val DISCLAIMER: String
    get() = tr(
        "It can be wrong. Use it for quick questions about your numbers and small everyday tips, not for medical " +
            "advice. For symptoms, medicines or anything that worries you, ask a doctor. Nothing you type leaves the phone.",
        "틀릴 수 있어요. 내 수치에 관한 간단한 질문이나 일상 속 작은 팁에 쓰고, 의학적 조언으로 쓰지 마세요. " +
            "증상이나 약, 걱정되는 일은 의사에게 물어보세요. 입력한 내용은 휴대폰 밖으로 나가지 않아요.",
    )
