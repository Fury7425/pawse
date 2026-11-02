package app.pawse.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import app.pawse.core.model.Answer
import app.pawse.core.model.Summary
import app.pawse.core.model.Turn
import app.pawse.core.ui.component.Chip
import app.pawse.core.ui.component.EaseOut
import app.pawse.core.ui.component.Glyph
import app.pawse.core.ui.component.GlyphButton
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.LocalReduceMotion
import app.pawse.core.ui.component.PawseCard
import app.pawse.core.ui.component.PawseField
import app.pawse.core.ui.component.PawseMotion
import app.pawse.core.ui.component.PawseSheet
import app.pawse.core.ui.component.PrimaryButton
import app.pawse.core.ui.component.cardSurface
import app.pawse.core.ui.component.lift
import app.pawse.core.ui.component.pressable
import app.pawse.core.ui.theme.PawseType
import app.pawse.scoring.tr

/**
 * This morning's summary. No typing animation: it is there whole, or not at all.
 * The tag says who wrote it, the model on this phone or Pawse's own line.
 */
@Composable
internal fun SummaryCard(summary: Summary, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null) {
    PawseCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("This morning", "오늘 아침"),
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Chip(if (summary.written) tr("Written on this phone", "이 휴대폰에서 작성") else tr("Pawse’s own line", "Pawse 기본 문장"))
            if (onClose != null) GlyphButton(Glyphs.Close, tr("Close", "닫기"), onClick = onClose)
        }
        Spacer(Modifier.height(8.dp))
        Text(summary.text, style = PawseType.Body)
    }
}

/**
 * The first time Pawse opens after the night, the summary rises from behind the
 * dock, once. Closed, it waits at the bottom of Scores until tomorrow.
 */
@Composable
internal fun RisingSummary(summary: Summary?, rises: Boolean, onShown: () -> Unit, modifier: Modifier = Modifier) {
    var shown by remember(summary?.date) { mutableStateOf(false) }
    LaunchedEffect(summary?.date, rises) {
        if (summary != null && rises) {
            shown = true
            onShown()
        }
    }
    val reduce = LocalReduceMotion.current
    AnimatedVisibility(
        visible = shown && summary != null,
        modifier = modifier,
        enter = if (reduce) {
            EnterTransition.None
        } else {
            slideInVertically(tween(PawseMotion.SHEET, easing = EaseOut)) { it / 2 } + fadeIn(tween(PawseMotion.SHEET))
        },
        exit = fadeOut(tween(120)),
    ) {
        summary?.let {
            SummaryCard(
                summary = it,
                onClose = { shown = false },
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 96.dp)
                    .lift(RoundedCornerShape(16.dp)),
            )
        }
    }
}

/** One row under the summary card: Ask Pawse. */
@Composable
internal fun AskRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .pressable(onClick = onClick)
            .cardSurface()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tr("Ask Pawse", "Pawse에게 묻기"), style = PawseType.BodyStrong, modifier = Modifier.weight(1f))
        Glyph(Glyphs.ChevronRight, size = 18.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Ask Pawse. The whole disclaimer shows whenever the conversation is empty, and a
 * one-line reminder sits under the field. Answers say how far they can be trusted.
 */
@Composable
internal fun AskSheet(turns: List<Turn>, onAsk: (String) -> Unit, onDismiss: () -> Unit) {
    var question by rememberSaveable { mutableStateOf("") }
    val writing = turns.any { it.answer == null }
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    PawseSheet(onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Ask Pawse", "Pawse에게 묻기"), style = PawseType.Title, modifier = Modifier.weight(1f))
                GlyphButton(Glyphs.Close, tr("Close", "닫기"), onClick = onDismiss)
            }
            if (turns.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(DISCLAIMER, style = PawseType.Body, color = grey)
            }
            turns.forEach { turn ->
                Spacer(Modifier.height(16.dp))
                Text(turn.question, style = PawseType.BodyStrong)
                Spacer(Modifier.height(4.dp))
                val answer = turn.answer
                if (answer == null) {
                    Text(tr("Writing on this phone…", "이 휴대폰에서 쓰는 중…"), style = PawseType.Label, color = grey)
                } else {
                    Text(answer.text, style = PawseType.Body)
                    kindLabel(answer.kind)?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = PawseType.Label, color = grey)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            PawseField(
                value = question,
                onValueChange = { question = it.take(MAX_QUESTION) },
                placeholder = tr("Ask about this morning", "오늘 아침에 대해 물어보세요"),
                textStyle = PawseType.Body,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                tr("Small model on this phone · can be wrong · not medical advice", "휴대폰 속 작은 모델 · 틀릴 수 있음 · 의학적 조언 아님"),
                style = PawseType.Label,
                color = grey,
            )
            Spacer(Modifier.height(12.dp))
            PrimaryButton(
                text = tr("Ask", "묻기"),
                enabled = question.isNotBlank() && !writing,
                onClick = {
                    onAsk(question)
                    question = ""
                },
            )
        }
    }
}

private fun kindLabel(kind: Answer.Kind): String? = when (kind) {
    Answer.Kind.CHECKED -> tr("Checked against your numbers", "내 수치와 대조함")
    Answer.Kind.GENERAL -> tr("General tip · can be wrong", "일반적인 팁 · 틀릴 수 있음")
    Answer.Kind.SYMPTOM -> tr("Pawse’s fixed answer", "Pawse의 정해진 답")
    Answer.Kind.UNCHECKED -> null
}

private const val MAX_QUESTION = 300

private val DISCLAIMER: String
    get() = tr(
        "Ask Pawse is a small language model that runs only on this phone. It words the numbers Pawse already " +
            "worked out and never works out its own: every number it writes is checked against this morning’s " +
            "figures, and a sentence that fails is left out. It can still be wrong, and it is not medical advice. " +
            "Questions about symptoms get a fixed answer that points to a doctor. Your questions stay on this " +
            "phone and clear each morning.",
        "Pawse에게 묻기는 이 휴대폰에서만 동작하는 작은 언어 모델이에요. Pawse가 이미 계산한 숫자를 말로 옮길 뿐 " +
            "스스로 계산하지 않아요. 모델이 쓰는 숫자는 모두 오늘 아침 수치와 대조하고, 맞지 않는 문장은 빼요. " +
            "그래도 틀릴 수 있고, 의학적 조언이 아니에요. 증상에 관한 질문에는 의사 상담을 권하는 정해진 답을 " +
            "드려요. 질문은 이 휴대폰에만 남고 매일 아침 지워져요.",
    )
