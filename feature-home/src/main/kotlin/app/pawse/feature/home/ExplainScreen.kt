package app.pawse.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pawse.core.data.scoring.ScoringSnapshot
import app.pawse.core.ui.component.BackLink
import app.pawse.core.ui.component.EaseOut
import app.pawse.core.ui.component.Glyph
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.PawseCard
import app.pawse.core.ui.component.RevealEnter
import app.pawse.core.ui.component.RevealExit
import app.pawse.core.ui.component.Segmented
import app.pawse.core.ui.component.aura
import app.pawse.core.ui.component.cardSurface
import app.pawse.core.ui.component.pressable
import app.pawse.core.ui.component.rememberSwap
import app.pawse.core.ui.component.swapIn
import app.pawse.core.ui.theme.PawseType
import app.pawse.scoring.config.ScoringConfig
import app.pawse.scoring.model.DayReport
import app.pawse.scoring.model.Insight
import app.pawse.scoring.model.ScoreType
import app.pawse.scoring.tr
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The arithmetic, in full, for Body and Load, and the night in full for Sleep,
 * for any day on the strip.
 *
 * Everything here was computed once, by the engine. Nothing is recalculated in
 * the UI: a screen that recomputes is a second engine that will eventually
 * disagree with the first, about the number the user was shown. (The usual bands
 * behind the night signals are display statistics over stored nights.)
 */
@Composable
fun ExplainScreen(
    type: ScoreType,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val night by viewModel.night.collectAsStateWithLifecycle()
    val snapshot = (state as? HomeUiState.Ready)?.snapshot
    var shown by rememberSaveable { mutableStateOf(type) }
    // The picked day, as days before the newest. It stays put across tabs.
    var back by rememberSaveable { mutableIntStateOf(0) }
    // Body and Load's Over time range, shared by both tabs.
    var range by rememberSaveable { mutableIntStateOf(30) }
    var open by rememberSaveable(shown) { mutableIntStateOf(0) }
    var mathOpen by rememberSaveable(shown) { mutableStateOf(false) }
    // The back link, the score switch and the date strip stay put; only what the tab shows swaps in.
    val swap = rememberSwap(shown)
    val pad = Modifier.padding(horizontal = 24.dp)
    val date = snapshot?.date?.minusDays(back.toLong())

    val computedAt = snapshot?.computedAtEpochMs
    LaunchedEffect(shown, date, computedAt) {
        if (shown == ScoreType.SLEEP && date != null && computedAt != null) viewModel.openNight(date, computedAt)
    }

    Scaffold(modifier = modifier, containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().aura(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 48.dp,
            ),
        ) {
            item {
                Column {
                    Column(pad) {
                        BackLink(tr("Scores", "점수"), onClick = onBack)
                        Spacer(Modifier.height(12.dp))
                        Segmented(
                            options = ScoreType.entries,
                            selected = shown,
                            onSelect = { shown = it },
                            label = ScoreCopy::name,
                            glyph = ::scoreGlyph,
                        )
                    }
                    if (snapshot != null) {
                        DateStrip(snapshot, shown, back, onPick = { back = it }, modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp))
                    }
                }
            }

            if (snapshot == null || date == null) {
                item {
                    Text(
                        tr("Nothing to explain yet.", "아직 설명할 내용이 없어요."),
                        style = PawseType.Body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = pad.padding(top = 20.dp),
                    )
                }
                return@LazyColumn
            }

            if (shown == ScoreType.SLEEP) {
                item {
                    SleepTab(
                        snapshot = snapshot,
                        back = back,
                        detail = night?.takeIf { it.date == date },
                        onOpenNight = { back = it },
                        onBody = { shown = ScoreType.BODY },
                        modifier = pad.swapIn(swap),
                    )
                }
                item { Provenance(snapshot, pad.padding(top = 28.dp)) }
                return@LazyColumn
            }

            val day = snapshot.history.lastOrNull { it.date == date }
            item { DayHead(date, pad.swapIn(swap).padding(top = 16.dp, bottom = 8.dp)) }
            item {
                DayTab(
                    snapshot = snapshot,
                    type = shown,
                    back = back,
                    onPick = { back = it },
                    range = range,
                    // A narrower range drops a day picked outside it, as the canvas does.
                    onRange = { range = it; if (back >= it) back = 0 },
                    open = open,
                    onOpen = { open = it },
                    modifier = pad.swapIn(swap),
                )
            }
            if (day != null) {
                item {
                    Combiner(
                        combiner = combinerOf(shown, day),
                        mathOpen = mathOpen,
                        onToggleMath = { mathOpen = !mathOpen },
                        modifier = pad.swapIn(swap).padding(top = 28.dp),
                    )
                }
            }
            // Body's reasons, week, overnight pattern and insights sit under the combiner, as on the board.
            if (shown == ScoreType.BODY && day?.body != null) {
                item {
                    Column(pad.swapIn(swap).padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        BodyCards(day, snapshot.insights)
                    }
                }
            }

            item { Provenance(snapshot, pad.padding(top = 28.dp)) }
        }
    }
}

/**
 * The week up to the newest day, one tap per day. It reads the open tab's figure
 * and stays put while the tabs swap beneath it.
 */
@Composable
private fun DateStrip(snapshot: ScoringSnapshot, type: ScoreType, back: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val byDate = snapshot.history.associateBy { it.date }
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (d in 6 downTo 0) {
            val date = snapshot.date.minusDays(d.toLong())
            val day = byDate[date]
            val value = when (type) {
                ScoreType.BODY -> day?.body?.score?.toString()
                ScoreType.SLEEP -> day?.sleep?.lastNightMinutes?.let { String.format(Locale.getDefault(), "%.1f", it / 60.0) }
                ScoreType.LOAD -> day?.load?.todayLoad?.let(ScoreCopy::load)
            } ?: "—"
            val picked = d == back
            Column(
                Modifier
                    .weight(1f)
                    .height(72.dp)
                    .pressable(role = Role.Tab) { onPick(d) }
                    .then(if (picked) Modifier.cardSurface(RoundedCornerShape(12.dp), 12.dp) else Modifier)
                    .semantics { selected = picked }
                    .padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()), style = PawseType.Label, color = grey)
                Text(date.dayOfMonth.toString(), style = PawseType.Heading.copy(lineHeight = 22.sp))
                Text(value, style = PawseType.Label, color = grey, maxLines = 1)
            }
        }
    }
}

/** "Today · Mon 19 Oct" over Body and Load, so the picked day is never in doubt. */
@Composable
private fun DayHead(date: LocalDate, modifier: Modifier = Modifier) {
    val now = LocalDate.now()
    val title = when (date) {
        now -> tr("Today", "오늘")
        now.minusDays(1) -> tr("Yesterday", "어제")
        else -> date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(title, style = PawseType.Heading, modifier = Modifier.weight(1f))
        Text(shortDate(date), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

private fun combinerOf(type: ScoreType, day: DayReport): CombinerText {
    val config = ScoringConfig.DEFAULT
    // Sleep's maths lives in its trends' "About this number" (LastNight.kt).
    return when (type) {
        ScoreType.BODY -> {
            val body = day.body
            val here = body?.let { "w = ${fmt(it.hrvWeight)}, ρ = ${fmt(it.rho)}, z = ${fmt(it.z)}" }
            val spread = body?.let { " (${fmt(it.zSd)})" } ?: ""
            CombinerText(
                formula = AnnotatedString("100 × Φ(z)"),
                left = body?.let { "z = ${signed(it.z, 2)}" } ?: "z",
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
        ScoreType.SLEEP, ScoreType.LOAD -> {
            val load = day.load
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
                        "The day's range is your typical training day scaled by that morning's Body: Body 50 is about " +
                        "usual, 80 about 30% more, 15 about two thirds. Starting defaults, never an injury-risk label.",
                    "각 운동은 Banister의 TRIMP로 계산해요. 심박 예비량을 얼마나 깊이 썼는지로 가중한 운동 시간(분)이며, " +
                        "예비량의 ${pct(l.minReserveFraction)}를 넘을 때만 집계해요. 쓸 만한 심박수가 없으면 운동 강도 × " +
                        "시간(분)으로 대신해요. 체력은 ${fitnessDays}일, 피로는 ${fatigueDays}일 동안의 부하 평균이에요. " +
                        "폼이 ${pct(l.freshAbove)}보다 높으면 개운함, ${pct(l.freshAbove)}~${pct(l.heavyBelow)}면 효과적, " +
                        "그보다 낮으면 무거움이에요. 그날의 범위는 평소 하루를 그날 아침 컨디션에 맞춰 조정해요. 컨디션 50이면 " +
                        "평소만큼, 80이면 30%쯤 더, 15면 3분의 2쯤이에요. 시작용 기본값일 뿐, 부상 위험을 판정하지 않아요.",
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
    val turn by animateFloatAsState(if (mathOpen) 180f else 0f, tween(220, easing = EaseOut), label = "chevron")

    PawseCard(modifier = modifier, padding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
        Text(tr("How it was worked out", "계산 방법"), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        AnimatedVisibility(mathOpen, enter = RevealEnter, exit = RevealExit) {
            Text(
                combiner.math,
                style = PawseType.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun BodyCards(today: DayReport, insights: List<Insight>) {
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
        if (insights.isEmpty()) {
            Note(
                tr(
                    "Nothing clear yet. An insight appears after at least 8 days of exposure and only when its 90% range excludes zero.",
                    "아직 뚜렷한 건 없어요. 최소 8일 이상 겪은 일이고 90% 범위가 0을 포함하지 않을 때만 인사이트가 나타나요.",
                ),
            )
        } else {
            insights.forEach {
                Text(ScoreCopy.insight(it), style = PawseType.Body, modifier = Modifier.padding(vertical = 4.dp))
            }
            Spacer(Modifier.height(4.dp))
            Note(ScoreCopy.INSIGHT_NOTE)
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
internal fun DetailLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(label, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(0.38f))
        Text(value, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

internal fun minutesText(minutes: Double): String = "${minutes.roundToInt()}${tr(" min", "분")}"

internal fun pct(fraction: Double): String = "${(fraction * 100).roundToInt()}%"

private fun fmt(v: Double): String = String.format(Locale.getDefault(), "%.2f", v)
