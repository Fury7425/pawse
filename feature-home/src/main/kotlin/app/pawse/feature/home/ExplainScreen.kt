package app.pawse.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pawse.core.data.scoring.ScoringSnapshot
import app.pawse.core.ui.component.BackLink
import app.pawse.core.ui.component.BaselineBand
import app.pawse.core.ui.component.Chip
import app.pawse.core.ui.component.Glyph
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.PawseCard
import app.pawse.core.ui.component.ScoreRing
import app.pawse.core.ui.component.Segmented
import app.pawse.core.ui.theme.PawseTheme
import app.pawse.core.ui.theme.PawseType
import app.pawse.scoring.config.ScoringConfig
import app.pawse.scoring.model.ScoreType
import app.pawse.scoring.model.SignalDetail
import app.pawse.scoring.tr
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The arithmetic, in full, for Body, Sleep and Load.
 *
 * Everything here was computed once, by the engine. Nothing is recalculated in
 * the UI: a screen that recomputes is a second engine that will eventually
 * disagree with the first, about the number the user was shown. (The dot strip
 * only places this week's nights on tonight's axis.)
 */
@Composable
fun ExplainScreen(
    type: ScoreType,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snapshot = (state as? HomeUiState.Ready)?.snapshot
    var shown by rememberSaveable { mutableStateOf(type) }
    var open by rememberSaveable(shown) { mutableIntStateOf(0) }
    var mathOpen by rememberSaveable(shown) { mutableStateOf(false) }
    val pad = Modifier.padding(horizontal = 24.dp)

    Scaffold(modifier = modifier, containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 48.dp,
            ),
        ) {
            item {
                Column(pad) {
                    BackLink(tr("Today", "오늘"), onClick = onBack)
                    Spacer(Modifier.height(12.dp))
                    Segmented(
                        options = ScoreType.entries,
                        selected = shown,
                        onSelect = { shown = it },
                        label = ScoreCopy::name,
                        glyph = ::scoreGlyph,
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(tr("How ${ScoreCopy.name(shown)} was worked out", "${ScoreCopy.name(shown)} 계산 방법"), style = PawseType.Title)
                    Spacer(Modifier.height(20.dp))
                }
            }

            val today = snapshot?.today
            if (snapshot == null || today == null || (shown == ScoreType.BODY && today.body == null)) {
                item {
                    Text(
                        text = today?.let {
                            ScoreCopy.bodyStatus(it.bodyStatus, it.validNights, ScoringConfig.DEFAULT.body.minValidNights)
                        } ?: tr("Nothing to explain yet.", "아직 설명할 내용이 없어요."),
                        style = PawseType.Body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = pad,
                    )
                }
                return@LazyColumn
            }

            item {
                Summary(shown, snapshot, pad)
                Spacer(Modifier.height(20.dp))
                Combiner(
                    combiner = combinerOf(shown, snapshot),
                    mathOpen = mathOpen,
                    onToggleMath = { mathOpen = !mathOpen },
                    modifier = pad,
                )
            }

            val terms = termsOf(shown, snapshot)
            if (terms.isNotEmpty()) {
                item {
                    Row(pad.padding(top = 28.dp, bottom = 2.dp)) {
                        Text(termsHeader(shown), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text(
                            effectHeader(shown),
                            style = PawseType.Label,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 30.dp),
                        )
                    }
                }
                itemsIndexed(terms, key = { i, t -> "$shown-$i-${t.label}" }) { i, term ->
                    Term(term, expanded = open == i, onToggle = { open = if (open == i) -1 else i }, modifier = pad)
                }
            }

            item {
                Column(pad.padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (shown) {
                        ScoreType.BODY -> BodyCards(snapshot)
                        ScoreType.SLEEP -> SleepCards(snapshot)
                        ScoreType.LOAD -> LoadCards(snapshot)
                    }
                }
            }

            item { Provenance(snapshot, pad.padding(top = 28.dp)) }
        }
    }
}

/** The ring, its word, and the few facts that frame it. */
@Composable
private fun Summary(type: ScoreType, snapshot: ScoringSnapshot, modifier: Modifier = Modifier) {
    val today = snapshot.today
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val readAt = snapshot.lastSyncEpochMs.takeIf { it > 0 }?.let { tr("Read at ${ScoreCopy.clock(it)}", "${ScoreCopy.clock(it)}에 읽음") }

    var fraction: Float? = null
    var color = grey
    var glow = false
    var value = "—"
    var unit = ""
    var target: Float? = null
    val dot: Color
    val word: String
    val info: List<String>
    when (type) {
        ScoreType.BODY -> {
            val body = today.body ?: return
            fraction = body.score / 100f
            color = bodyColor(body.band, today.illness.level)
            glow = true
            value = body.score.toString()
            dot = color
            word = ScoreCopy.bandWord(body.band)
            info = listOfNotNull(tr("Likely", "범위") + " ${body.low}–${body.high}", ScoreCopy.confidence(body.confidence), readAt)
        }
        ScoreType.SLEEP -> {
            val sleep = today.sleep
            sleep.lastNightMinutes?.let {
                fraction = (it / (sleep.needHours * 60.0)).toFloat()
                value = String.format(Locale.getDefault(), "%.1f", it / 60.0)
                unit = "h"
            }
            dot = PawseTheme.semantics.neutral
            word = ScoreCopy.debt(sleep.debtHours)
            info = listOfNotNull(
                tr("Need", "필요 수면") + " ${ScoreCopy.formatHours(sleep.needHours)}",
                ScoreCopy.regularity(sleep.sri),
                sleep.score?.let { tr("Sleep score $it", "수면 점수 $it") },
            )
        }
        ScoreType.LOAD -> {
            val load = today.load
            val top = load.rangeHigh?.takeIf { it > 0.0 && !load.rest }
            fraction = top?.let { (load.todayLoad / it).toFloat() }
            target = top?.let { high -> load.rangeLow?.let { (it / high).toFloat() } }
            value = ScoreCopy.load(load.todayLoad)
            dot = PawseTheme.semantics.neutral
            word = load.formBand?.let(ScoreCopy::form) ?: tr("Building your history", "기록 쌓는 중")
            val low = load.rangeLow
            val high = load.rangeHigh
            info = listOfNotNull(
                when {
                    load.rest -> tr("Rest today", "오늘은 휴식")
                    low != null && high != null -> tr(
                        "Range ${ScoreCopy.load(low)}–${ScoreCopy.load(high)} today",
                        "오늘 범위 ${ScoreCopy.load(low)}–${ScoreCopy.load(high)}",
                    )
                    else -> tr("No range yet", "아직 범위 없음")
                },
                load.typicalDayLoad?.let { tr("Typical day", "평소 하루") + " ${ScoreCopy.load(it)}" },
                readAt,
            )
        }
    }

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        ScoreRing(fraction = fraction, color = color, size = 112.dp, glow = glow, target = target) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value, style = PawseType.Numeral)
                if (unit.isNotEmpty()) {
                    Text(unit, style = PawseType.Label, color = grey, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        Spacer(Modifier.width(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(8.dp)) { drawCircle(dot) }
                Spacer(Modifier.width(8.dp))
                Text(word, style = PawseType.BodyStrong)
            }
            info.forEach { Text(it, style = PawseType.Label, color = grey) }
        }
    }
}

/** The formula that turns the parts into the reading, and what it gave. */
private class CombinerText(
    val formula: AnnotatedString,
    val left: String,
    val result: String,
    val note: String,
    val math: String,
)

private fun combinerOf(type: ScoreType, snapshot: ScoringSnapshot): CombinerText {
    val config = ScoringConfig.DEFAULT
    val today = snapshot.today
    val sup = SpanStyle(fontSize = 11.sp, baselineShift = BaselineShift.Superscript)
    return when (type) {
        ScoreType.BODY -> {
            val body = today.body
            val here = body?.let { "w = ${fmt(it.hrvWeight)}, ρ = ${fmt(it.rho)}, z = ${fmt(it.z)}" }
            val spread = body?.let { " (${fmt(it.zSd)})" } ?: ""
            CombinerText(
                formula = AnnotatedString("100 × Φ(z)"),
                left = tr("z, both signals combined", "z, 두 신호를 합친 값"),
                result = body?.score?.toString() ?: "—",
                note = tr("Your normal reads 50", "내 평소는 50"),
                math = tr(
                    "Each night is a noisy reading of your true state. A Kalman filter trusts it only as much as " +
                        "its quality allows, so a patchy night barely moves the estimate and a missed one widens the range. " +
                        "HRV and sleeping heart rate read the same nervous system, so " +
                        "z = (w·z_h + (1−w)·z_r) / √(w² + (1−w)² + 2w(1−w)ρ) divides their overlap ρ out" +
                        (here?.let { " — here $it" } ?: "") +
                        ". The range is ±${fmt(config.body.rangeZ)} of the filter's own spread$spread.",
                    "매일 밤의 기록은 실제 상태를 잡음과 함께 읽은 값이에요. 칼만 필터는 기록의 품질만큼만 그 값을 믿어서, " +
                        "끊긴 밤은 추정치를 거의 움직이지 않고 빠진 밤은 범위를 넓혀요. HRV와 수면 중 심박수는 같은 " +
                        "자율신경계를 읽기 때문에 z = (w·z_h + (1−w)·z_r) / √(w² + (1−w)² + 2w(1−w)ρ)로 둘의 겹침 ρ를 덜어내요" +
                        (here?.let { " — 여기서는 $it" } ?: "") +
                        ". 범위는 필터 자체 퍼짐${spread}의 ±${fmt(config.body.rangeZ)}배예요.",
                ),
            )
        }
        ScoreType.SLEEP -> {
            val sleep = today.sleep
            val s = config.sleep
            CombinerText(
                formula = buildAnnotatedString {
                    append(tr("Debt = Σ ", "부채 = Σ ") + "${s.debtDecay}")
                    withStyle(sup) { append("n") }
                    append(tr(" × (need − slept)", " × (필요 − 수면)"))
                },
                left = tr("${s.debtNights} nights", "${s.debtNights}밤"),
                result = sleep.debtHours?.let(ScoreCopy::formatHours) ?: "—",
                note = tr("A lie-in repays at most ${s.maxRepayHours.roundToInt()} h", "늦잠은 최대 ${s.maxRepayHours.roundToInt()}시간만 갚아요"),
                math = tr(
                    "Debt weights recent nights more (${s.debtDecay} per night back, " +
                        "${s.debtNights} nights), and one long lie-in repays at most an hour. " +
                        "Regularity is the Sleep Regularity Index: the chance you are in the same state 24 hours apart, " +
                        "−100 to 100 over ${s.sriDays} days, naps included. In UK Biobank it predicted mortality " +
                        "better than duration. The optional Sleep score is ${pct(s.scoreDurationWeight)} duration, " +
                        "${pct(s.scoreRegularityWeight)} regularity and ${pct(s.scoreContinuityWeight)} " +
                        "continuity; a part that was not measured has its weight shared out. Stages get no weight.",
                    "부채는 최근 밤일수록 크게 반영해요(하루 거슬러 갈 때마다 ${s.debtDecay}배, ${s.debtNights}밤). " +
                        "한 번 길게 늦잠을 자도 최대 한 시간만 갚아요. 규칙성은 수면 규칙성 지수(SRI)로, 24시간 간격으로 " +
                        "같은 상태일 확률을 ${s.sriDays}일 동안 −100~100으로 나타내며 낮잠도 포함해요. UK Biobank에서는 " +
                        "수면 시간보다 사망률을 더 잘 예측했어요. 선택 항목인 수면 점수는 수면 시간 ${pct(s.scoreDurationWeight)}, " +
                        "규칙성 ${pct(s.scoreRegularityWeight)}, 연속성 ${pct(s.scoreContinuityWeight)}로 이루어지고, " +
                        "측정되지 않은 항목의 비중은 나머지에 나눠요. 수면 단계는 반영하지 않아요.",
                ),
            )
        }
        ScoreType.LOAD -> {
            val load = today.load
            val l = config.load
            val fitnessDays = l.fitnessDays.roundToInt()
            val fatigueDays = l.fatigueDays.roundToInt()
            CombinerText(
                formula = AnnotatedString(tr("Form = (fitness − fatigue) ÷ fitness", "폼 = (체력 − 피로) ÷ 체력")),
                left = tr("$fitnessDays days vs $fatigueDays", "${fitnessDays}일 대 ${fatigueDays}일"),
                result = load.form?.let { "${(it * 100).roundToInt()}%" } ?: "—",
                note = tr("Below ${pct(l.heavyBelow)} reads heavy", "${pct(l.heavyBelow)} 미만이면 무거움"),
                math = tr(
                    "Each session is Banister's TRIMP: minutes weighted by how far into your heart-rate reserve you " +
                        "worked, counted only above ${pct(l.minReserveFraction)} of it. Without a usable heart rate it " +
                        "falls back to effort × minutes. Fitness averages $fitnessDays days of load " +
                        "and fatigue $fatigueDays. Above ${pct(l.freshAbove)} form reads " +
                        "fresh, ${pct(l.freshAbove)} to ${pct(l.heavyBelow)} productive, below that heavy. " +
                        "Starting defaults, never an injury-risk label.",
                    "각 운동은 Banister의 TRIMP로 계산해요. 심박 예비량을 얼마나 깊이 썼는지로 가중한 운동 시간(분)이며, " +
                        "예비량의 ${pct(l.minReserveFraction)}를 넘을 때만 집계해요. 쓸 만한 심박수가 없으면 운동 강도 × " +
                        "시간(분)으로 대신해요. 체력은 ${fitnessDays}일, 피로는 ${fatigueDays}일 동안의 부하 평균이에요. " +
                        "폼이 ${pct(l.freshAbove)}보다 높으면 개운함, ${pct(l.freshAbove)}~${pct(l.heavyBelow)}면 효과적, " +
                        "그보다 낮으면 무거움이에요. 시작용 기본값일 뿐, 부상 위험을 판정하지 않아요.",
                ),
            )
        }
    }
}

@Composable
private fun Combiner(
    combiner: CombinerText,
    mathOpen: Boolean,
    onToggleMath: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val turn by animateFloatAsState(if (mathOpen) 180f else 0f, tween(200), label = "chevron")

    PawseCard(modifier = modifier, padding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
        Text(tr("Combiner", "합산 방식"), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text(combiner.formula, style = PawseType.MetricValue)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(combiner.left, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Glyph(Glyphs.Arrow, size = 14.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(combiner.result, style = PawseType.Label)
            Text(
                combiner.note,
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clickable(onClick = onToggleMath),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(tr("How the maths works", "계산 원리"), style = PawseType.BodyStrong, modifier = Modifier.weight(1f))
            Glyph(Glyphs.ChevronDown, size = 18.dp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.rotate(turn))
        }
        AnimatedVisibility(mathOpen, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Text(
                combiner.math,
                style = PawseType.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
    }
}

private fun termsHeader(type: ScoreType): String = when (type) {
    ScoreType.BODY -> tr("Signals · tap one to see your week", "신호 · 눌러서 이번 주 보기")
    ScoreType.SLEEP -> tr("Parts · against your need", "항목 · 필요 수면과 비교")
    ScoreType.LOAD -> tr("Today’s sessions", "오늘의 운동")
}

private fun effectHeader(type: ScoreType): String = when (type) {
    ScoreType.BODY -> tr("vs normal", "평소 대비")
    ScoreType.SLEEP -> tr("Part", "항목")
    ScoreType.LOAD -> tr("Load", "부하")
}

/**
 * One line of the breakdown. [z] draws the usual-range band and toned marker,
 * [part] a plain 0..1 bar; [nights] are this week's earlier nights on z's axis.
 */
private class TermLine(
    val label: String,
    val value: String,
    val effect: String,
    val z: Double? = null,
    val part: Double? = null,
    val nights: List<Double> = emptyList(),
    val lines: List<Pair<String, String>> = emptyList(),
    val chip: String? = null,
    val citation: String? = null,
)

private fun termsOf(type: ScoreType, snapshot: ScoringSnapshot): List<TermLine> {
    val today = snapshot.today
    return when (type) {
        ScoreType.BODY -> {
            val body = today.body ?: return emptyList()
            val earlier = snapshot.week.dropLast(1)
            listOfNotNull(
                body.hrv?.let { signalTerm(it, hrv = true, body.hrvWeight, earlier.mapNotNull { d -> d.body?.hrv?.z }) },
                body.sleepingHr?.let { signalTerm(it, hrv = false, 1.0 - body.hrvWeight, earlier.mapNotNull { d -> d.body?.sleepingHr?.z }) },
            )
        }
        ScoreType.SLEEP -> {
            val sleep = today.sleep
            val parts = sleep.parts ?: return emptyList()
            val config = ScoringConfig.DEFAULT.sleep
            fun partLines(part: Double?, weight: Double) = listOf(
                tr("Part score", "항목 점수") to (
                    part?.let { "${(it * 100).roundToInt()} / 100" }
                        ?: tr("Not measured, so its weight was shared out", "측정되지 않아 비중을 나눴어요")
                    ),
                tr("Weight", "비중") to pct(weight),
            )
            listOf(
                TermLine(
                    label = tr("Duration vs need", "필요 대비 수면 시간"),
                    value = sleep.lastNightMinutes?.let(ScoreCopy::formatMinutes) ?: "—",
                    effect = partText(parts.duration),
                    part = parts.duration,
                    lines = partLines(parts.duration, config.scoreDurationWeight) +
                        (tr("Need", "필요 수면") to ScoreCopy.formatHours(sleep.needHours)),
                ),
                TermLine(
                    label = tr("Regularity", "규칙성"),
                    value = sleep.sri?.roundToInt()?.toString() ?: "—",
                    effect = partText(parts.regularity),
                    part = parts.regularity,
                    lines = partLines(parts.regularity, config.scoreRegularityWeight) +
                        (tr("Index", "지수") to ScoreCopy.regularity(sleep.sri)) +
                        (tr("Window", "기간") to tr("${config.sriDays} days, naps included", "${config.sriDays}일, 낮잠 포함")),
                ),
                TermLine(
                    label = tr("Continuity", "연속성"),
                    value = sleep.efficiencyPercent?.let { "${it.roundToInt()}%" } ?: "—",
                    effect = partText(parts.continuity),
                    part = parts.continuity,
                    lines = partLines(parts.continuity, config.scoreContinuityWeight) + listOfNotNull(
                        sleep.wasoMinutes?.let { tr("Awake after falling asleep", "잠든 뒤 깬 시간") to minutesText(it) },
                        tr("Targets", "목표") to tr(
                            "${config.efficiencyTargetPercent.roundToInt()}% asleep, under ${config.wasoLimitMinutes.roundToInt()} min awake",
                            "수면 효율 ${config.efficiencyTargetPercent.roundToInt()}%, 깬 시간 ${config.wasoLimitMinutes.roundToInt()}분 미만",
                        ),
                    ),
                ),
            )
        }
        ScoreType.LOAD -> today.load.sessions.map { s ->
            TermLine(
                label = s.title ?: tr("Workout", "운동"),
                value = minutesText(s.durationMinutes),
                effect = ScoreCopy.load(s.load),
                lines = listOfNotNull(
                    tr("Load", "부하") to ScoreCopy.load(s.load),
                    tr("Counted by", "계산 방식") to ScoreCopy.loadMethod(s.method),
                    s.reserveFraction?.let { tr("Heart-rate reserve", "심박 예비량") to "${(it * 100).roundToInt()}%" },
                    s.skippedReason?.let { tr("Not counted", "집계 안 됨") to it },
                ),
                chip = ScoreCopy.provenanceLabel(s.provenance),
                citation = s.citation.takeIf { it.isNotBlank() },
            )
        }
    }
}

private fun signalTerm(s: SignalDetail, hrv: Boolean, weight: Double, earlier: List<Double>): TermLine {
    val row = s.toRowState(hrv)
    val config = ScoringConfig.DEFAULT.body
    return TermLine(
        label = row.label,
        value = row.valueText,
        effect = String.format(Locale.getDefault(), "%+.1f", s.z),
        z = s.z,
        nights = earlier,
        lines = listOfNotNull(
            tr("Against your normal", "내 평소와 비교") to row.deltaText,
            tr("Your normal", "내 평소") to row.baselineText,
            tr("Filtered", "필터 적용값") to "${s.filtered.roundToInt()} ${if (hrv) "ms" else "bpm"}",
            "z" to String.format(Locale.getDefault(), "%+.2f", s.z),
            tr("Normal from", "평소 기준") to tr(
                "${s.baselineNights} nights, the ${config.baselineDays} ending ${config.baselineLagDays} days ago",
                "${config.baselineLagDays}일 전까지 ${config.baselineDays}일 중 ${s.baselineNights}밤",
            ),
            tr("Source", "출처") to s.source.substringBefore('#') +
                if (s.source.endsWith("#resting")) tr(" (watch's resting HR)", " (시계의 안정 시 심박수)") else "",
            if (s.cycleAdjusted) tr("Cycle", "생리 주기") to tr("Your luteal offset was removed first", "황체기 차이를 먼저 뺐어요") else null,
        ),
        chip = tr("Share of Body", "컨디션 내 비중") + " ${pct(weight)}",
    )
}

/** One term: a summary line that opens to your week, the figures and the source. */
@Composable
private fun Term(term: TermLine, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val semantics = PawseTheme.semantics
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val tone = when {
        term.z == null -> grey
        abs(term.z) < ScoreCopy.NEUTRAL_Z -> semantics.neutral
        term.z > 0 -> semantics.better
        else -> semantics.worse
    }
    val turn by animateFloatAsState(if (expanded) 180f else 0f, tween(200), label = "chevron")

    Column(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(term.label, style = PawseType.Body, modifier = Modifier.weight(1f))
                Text(term.value, style = PawseType.MetricValue)
                Text(
                    term.effect,
                    style = PawseType.Delta,
                    color = tone,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(min = 48.dp),
                )
                Spacer(Modifier.width(12.dp))
                Glyph(Glyphs.ChevronDown, size = 18.dp, color = grey, modifier = Modifier.rotate(turn))
            }
            when {
                term.z != null -> {
                    Spacer(Modifier.height(10.dp))
                    BaselineBand(z = term.z.toFloat(), markerColor = tone, height = 8.dp, modifier = Modifier.fillMaxWidth())
                }
                term.part != null -> {
                    val track = semantics.baselineOuter
                    val part = term.part.toFloat().coerceIn(0f, 1f)
                    Spacer(Modifier.height(10.dp))
                    Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                        val r = CornerRadius(size.height / 2f)
                        drawRoundRect(track, cornerRadius = r)
                        drawRoundRect(grey, size = Size(size.width * part, size.height), cornerRadius = r)
                    }
                }
            }
        }

        AnimatedVisibility(expanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(Modifier.padding(bottom = 18.dp)) {
                if (term.z != null && term.nights.isNotEmpty()) {
                    val better = term.nights.count { it < term.z }
                    val rank = tr("Better than $better of ${term.nights.size} nights", "${term.nights.size}밤 중 ${better}밤보다 좋음")
                    NightStrip(
                        tonight = term.z,
                        nights = term.nights,
                        tone = tone,
                        description = tr(
                            "This week's earlier nights as dots, last night marked. $rank.",
                            "이번 주 앞선 밤들은 점으로, 지난밤은 막대로 표시했어요. $rank.",
                        ),
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.padding(bottom = 12.dp)) {
                        Text(tr("Each dot is one of your nights", "점 하나가 하룻밤이에요"), style = PawseType.Label, color = grey, modifier = Modifier.weight(1f))
                        Text(rank, style = PawseType.Label.copy(fontWeight = PawseType.BodyStrong.fontWeight))
                    }
                }
                term.lines.forEach { (k, v) -> DetailLine(k, v) }
                term.chip?.let {
                    Spacer(Modifier.height(12.dp))
                    Chip(it)
                }
                term.citation?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = PawseType.Label, color = grey)
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * Last night among this week's: each night a dot on the same ±3 SD axis as the band
 * above, stacked where they pile up, and last night as a full-height bar.
 */
@Composable
private fun NightStrip(tonight: Double, nights: List<Double>, tone: Color, description: String) {
    val band = PawseTheme.semantics.baselineOuter
    val line = MaterialTheme.colorScheme.outline
    val dot = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .semantics { contentDescription = description },
    ) {
        fun x(z: Double) = ((z.coerceIn(-3.0, 3.0) + 3.0) / 6.0).toFloat() * size.width
        val third = size.width / 3f
        drawRoundRect(band, Offset(third, 0f), Size(third, size.height), CornerRadius(4.dp.toPx()))
        drawLine(line, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), 1.dp.toPx())
        drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        val radius = 3.dp.toPx()
        val step = 7.dp.toPx()
        val piles = HashMap<Int, Int>()
        nights.forEach { z ->
            val bin = (((z.coerceIn(-3.0, 3.0) + 3.0) / 6.0 * 100.0) / 2.4).roundToInt()
            val k = piles.getOrDefault(bin, 0)
            piles[bin] = k + 1
            drawCircle(dot, radius, Offset(x(z), size.height - radius - k * step))
        }
        val tx = x(tonight)
        drawRoundRect(tone, Offset(tx - 2.dp.toPx(), 0f), Size(4.dp.toPx(), size.height), CornerRadius(2.dp.toPx()))
    }
}

@Composable
private fun BodyCards(snapshot: ScoringSnapshot) {
    val today = snapshot.today
    val body = today.body ?: return
    if (body.reasons.isNotEmpty()) {
        PawseCard {
            SectionTitle(tr("Top reasons", "주요 이유"))
            Text(ScoreCopy.reasons(body.reasons), style = PawseType.Body)
            Spacer(Modifier.height(8.dp))
            Note(
                ScoreCopy.bandMeaning(body.band) + tr(
                    " Body ${body.score} is roughly “better than ${body.score}% of your normal days”.",
                    " 컨디션 ${body.score}점은 대략 ‘평소 날의 ${body.score}%보다 좋음’이라는 뜻이에요.",
                ),
            )
        }
    }
    today.weekly?.let { w ->
        PawseCard {
            SectionTitle(tr("The week, not just the day", "하루가 아닌 한 주"))
            Text(ScoreCopy.weekly(w.pattern), style = PawseType.Body)
            Spacer(Modifier.height(6.dp))
            DetailLine(tr("7-day HRV", "7일 HRV"), tr("${w.hrv7.roundToInt()} ms over ${w.nights} nights", "${w.nights}밤 평균 ${w.hrv7.roundToInt()} ms"))
            DetailLine(
                tr("Day-to-day variation", "날마다의 변동"),
                "${fmt(w.cv7)}%" + (w.usualCv7?.let { tr(" (usually ${fmt(it)}%)", " (평소 ${fmt(it)}%)") } ?: ""),
            )
            Spacer(Modifier.height(8.dp))
            Note(
                tr(
                    "A falling weekly HRV with shrinking variation under heavy load is a warning even when no single day looks bad.",
                    "높은 부하 속에 주간 HRV가 떨어지고 변동도 줄어든다면, 하루하루는 괜찮아 보여도 경고 신호예요.",
                ),
            )
        }
    }
    PawseCard {
        SectionTitle(tr("Overnight heart-rate pattern", "야간 심박수 패턴"))
        DetailLine(tr("Running sum", "누적 합"), fmt(today.illness.cusum))
        DetailLine(tr("Quiet note above", "조용한 알림 기준"), "3")
        DetailLine(tr("Alert above", "경고 기준"), tr("5, with temperature or breathing up", "5, 체온이나 호흡수도 올랐을 때"))
        Spacer(Modifier.height(8.dp))
        Note(
            tr(
                "Small excesses add up night after night, so three slightly high nights count and one random spike does not. " +
                    "Logged drinks and travel or stress tags are checked first. It never names a disease.",
                "작은 초과분이 밤마다 쌓이는 방식이라, 조금 높은 밤 세 번은 반영되고 우연한 한 번의 급등은 반영되지 " +
                    "않아요. 기록한 음주와 여행·스트레스 태그를 먼저 확인해요. 병명은 절대 말하지 않아요.",
            ),
        )
    }
    PawseCard {
        SectionTitle(tr("What moves your Body", "컨디션을 움직이는 것"))
        if (snapshot.insights.isEmpty()) {
            Note(
                tr(
                    "Nothing clear yet. An insight appears after at least 8 days of exposure and only when its 90% range excludes zero.",
                    "아직 뚜렷한 건 없어요. 최소 8일 이상 겪은 일이고 90% 범위가 0을 포함하지 않을 때만 인사이트가 나타나요.",
                ),
            )
        } else {
            snapshot.insights.forEach {
                Text(ScoreCopy.insight(it), style = PawseType.Body, modifier = Modifier.padding(vertical = 4.dp))
            }
            Spacer(Modifier.height(4.dp))
            Note(ScoreCopy.INSIGHT_NOTE)
        }
    }
}

@Composable
private fun SleepCards(snapshot: ScoringSnapshot) {
    val sleep = snapshot.today.sleep
    val config = ScoringConfig.DEFAULT.sleep
    PawseCard {
        SectionTitle(tr("Where your need came from", "필요 수면을 정한 방법"))
        DetailLine(tr("Need", "필요 수면"), ScoreCopy.formatHours(sleep.needHours))
        DetailLine(
            tr("Learned from", "기준"),
            if (sleep.needLearned) {
                tr(
                    "Median of ${sleep.freeNights} weekend nights, kept within ${config.minNeedHours.roundToInt()}–${config.maxNeedHours.roundToInt()} h",
                    "주말 밤 ${sleep.freeNights}번의 중앙값, ${config.minNeedHours.roundToInt()}–${config.maxNeedHours.roundToInt()}시간 범위 안",
                )
            } else {
                tr(
                    "Default ${config.defaultNeedHours.roundToInt()} h until there are ${config.minFreeNights} weekend nights",
                    "주말 밤이 ${config.minFreeNights}번 쌓일 때까지 기본값 ${config.defaultNeedHours.roundToInt()}시간",
                )
            },
        )
        sleep.lastNightMinutes?.let { DetailLine(tr("Last night", "지난밤"), ScoreCopy.formatMinutes(it)) }
        DetailLine(tr("Debt", "수면 부채"), ScoreCopy.debt(sleep.debtHours))
    }
    PawseCard {
        SectionTitle(tr("Last night in detail", "지난밤 자세히"))
        sleep.efficiencyPercent?.let { DetailLine(tr("Efficiency", "수면 효율"), "${it.roundToInt()}%") }
        sleep.wasoMinutes?.let { DetailLine(tr("Awake after falling asleep", "잠든 뒤 깬 시간"), minutesText(it)) }
        sleep.remMinutes?.let { DetailLine(tr("REM", "렘수면"), ScoreCopy.formatMinutes(it)) }
        sleep.deepMinutes?.let { DetailLine(tr("Deep", "깊은 잠"), ScoreCopy.formatMinutes(it)) }
        Spacer(Modifier.height(8.dp))
        Note(
            if (sleep.remMinutes == null) {
                tr("Your watch did not record stages for this night.", "이날 밤은 시계가 수면 단계를 기록하지 않았어요.")
            } else {
                tr(
                    "Stages are shown, never scored. Wrist staging is the least reliable thing a watch measures.",
                    "수면 단계는 보여주기만 하고 점수에는 넣지 않아요. 손목으로 재는 수면 단계는 시계가 측정하는 것 중 가장 부정확해요.",
                )
            },
        )
    }
}

@Composable
private fun LoadCards(snapshot: ScoringSnapshot) {
    val load = snapshot.today.load
    val low = load.rangeLow
    val high = load.rangeHigh
    PawseCard {
        SectionTitle(tr("Today’s range", "오늘의 범위"))
        load.typicalDayLoad?.let { DetailLine(tr("Your typical training day", "평소 운동하는 날"), ScoreCopy.load(it)) }
        DetailLine(
            tr("Suggested range", "권장 범위"),
            when {
                load.rest -> tr("Rest: your overnight heart rate has been high", "휴식: 야간 심박수가 높았어요")
                low != null && high != null -> "${ScoreCopy.load(low)}–${ScoreCopy.load(high)}"
                else -> tr("After your first few workouts", "운동을 몇 번 한 뒤에")
            },
        )
        DetailLine(tr("So far", "지금까지"), ScoreCopy.load(load.todayLoad))
        Spacer(Modifier.height(8.dp))
        Note(
            tr(
                "Your typical day scaled by this morning's Body: Body 50 is about usual, 80 about 30% more, 15 about two thirds.",
                "평소 하루를 오늘 아침 컨디션에 맞춰 조정해요. 컨디션 50이면 평소만큼, 80이면 30%쯤 더, 15면 3분의 2쯤이에요.",
            ),
        )
    }
    PawseCard {
        SectionTitle(tr("Fitness and fatigue", "체력과 피로"))
        load.fitness?.let { DetailLine(tr("Fitness", "체력"), ScoreCopy.load(it)) }
        load.fatigue?.let { DetailLine(tr("Fatigue", "피로"), ScoreCopy.load(it)) }
        load.form?.let { DetailLine(tr("Form", "폼"), "${(it * 100).roundToInt()}%") }
        if (load.fitness == null) Note(tr("Needs a few weeks of training to read.", "읽으려면 몇 주간의 운동 기록이 필요해요."))
        if (load.sessions.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Note(tr("No workouts logged today.", "오늘 기록된 운동이 없어요."))
        }
    }
}

@Composable
private fun Provenance(snapshot: ScoringSnapshot, modifier: Modifier = Modifier) {
    Column(modifier) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))
        DetailLine(tr("Engine", "엔진"), snapshot.scoringVersion)
        DetailLine(tr("Defaults", "기본값"), snapshot.configHash)
        Spacer(Modifier.height(8.dp))
        Note(
            tr(
                "Every number marked as a default is a reasoned starting point, not a validated value.",
                "기본값으로 표시된 숫자는 모두 근거 있는 출발점일 뿐, 검증된 값이 아니에요.",
            ) + " " + ScoreCopy.FOOTER,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = PawseType.Heading)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(label, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(0.38f))
        Text(value, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun minutesText(minutes: Double): String = "${minutes.roundToInt()}${tr(" min", "분")}"

private fun partText(part: Double?): String = part?.let { (it * 100).roundToInt().toString() } ?: "—"

private fun pct(fraction: Double): String = "${(fraction * 100).roundToInt()}%"

private fun fmt(v: Double): String = String.format(Locale.getDefault(), "%.2f", v)
