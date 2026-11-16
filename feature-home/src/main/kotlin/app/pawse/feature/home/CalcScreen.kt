package app.pawse.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pawse.core.ui.component.GlyphButton
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.PawseCard
import app.pawse.core.ui.component.aura
import app.pawse.core.ui.theme.PawseTheme
import app.pawse.core.ui.theme.PawseType
import app.pawse.scoring.model.Confidence
import app.pawse.scoring.model.DayReport
import app.pawse.scoring.tr
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How Pawse calculates: every number with its equation, what goes in, this
 * morning worked through, and where the data comes from. Opened from Profile at
 * the top, or from a detail's ⓘ at that number's section.
 */
@Composable
fun CalcScreen(section: CalcSection?, onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val day = (state as? HomeUiState.Ready)?.snapshot?.today
    val sections = calcSections(day)
    val list = rememberLazyListState()
    // The lead is item 0, so a section's item is its index plus one.
    LaunchedEffect(section) {
        section?.let { s -> sections.indexOfFirst { it.key == s }.takeIf { it >= 0 }?.let { list.scrollToItem(it + 1) } }
    }
    val lifted by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 4 } }
    val hair = MaterialTheme.colorScheme.outlineVariant

    Scaffold(modifier = modifier, containerColor = MaterialTheme.colorScheme.background) { padding ->
        val top = padding.calculateTopPadding()
        Box(Modifier.fillMaxSize().aura()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = list,
                contentPadding = PaddingValues(top = top + 64.dp, bottom = padding.calculateBottomPadding() + 64.dp),
            ) {
                item {
                    Text(
                        tr(
                            "Every number Pawse shows is worked out on this phone. The examples use this morning.",
                            "Pawse가 보여주는 숫자는 모두 이 휴대폰에서 계산해요. 예시는 오늘 아침 값이에요.",
                        ),
                        style = PawseType.Label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
                sections.forEach { s -> item(key = s.key) { CalcCard(s, Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp)) } }
                item {
                    Text(
                        tr(
                            "Wellness information, not a diagnosis. Numbers marked as defaults are reasoned starting points, not validated values.",
                            "건강 정보이며 진단이 아니에요. 기본값으로 표시된 숫자는 합리적인 출발점이지 검증된 값이 아니에요.",
                        ),
                        style = PawseType.Micro,
                        color = PawseTheme.semantics.faint,
                        modifier = Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp),
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (lifted) MaterialTheme.colorScheme.background.copy(alpha = 0.92f) else Color.Transparent)
                    .drawBehind { if (lifted) drawLine(hair, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
                    .padding(start = 12.dp, top = top, end = 12.dp, bottom = 8.dp)
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphButton(Glyphs.ChevronLeft, tr("Back", "뒤로"), onClick = onBack)
                Text(tr("How Pawse calculates", "Pawse 계산 방법"), style = PawseType.Heading, modifier = Modifier.padding(start = 4.dp).semantics { heading() })
            }
        }
    }
}

private class CalcSectionCopy(
    val key: CalcSection,
    val title: String,
    val tag: String,
    val eq: String,
    val eq2: String?,
    val ins: List<Pair<String, String>>,
    /** Null when this morning has nothing to work through. */
    val example: String?,
    val note: String?,
    val source: String,
)

@Composable
private fun CalcCard(s: CalcSectionCopy, modifier: Modifier) {
    val on = MaterialTheme.colorScheme.onSurface
    val faint = PawseTheme.semantics.faint
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    PawseCard(modifier) {
        Row {
            Text(s.title, style = PawseType.BodyStrong, modifier = Modifier.weight(1f).semantics { heading() })
            Text(s.tag, style = PawseType.Micro, color = faint, textAlign = TextAlign.End, modifier = Modifier.padding(start = 8.dp))
        }
        val eqStyle = PawseType.Body.copy(fontSize = 17.sp, lineHeight = 24.sp, letterSpacing = (-0.1).sp)
        Text(s.eq, style = eqStyle, color = on, modifier = Modifier.padding(top = 8.dp))
        s.eq2?.let { Text(it, style = eqStyle, color = on, modifier = Modifier.padding(top = 4.dp)) }
        Text(tr("What goes in", "들어가는 값"), style = PawseType.Label, color = grey, modifier = Modifier.padding(top = 16.dp))
        s.ins.forEach { (k, v) ->
            Row(Modifier.padding(top = 8.dp)) {
                Text(k, style = PawseType.Micro, color = on, modifier = Modifier.widthIn(min = 52.dp).padding(end = 8.dp))
                Text(v, style = PawseType.Micro, color = faint, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            }
        }
        s.example?.let {
            Text(tr("With this morning", "오늘 아침 값으로"), style = PawseType.Label, color = grey, modifier = Modifier.padding(top = 16.dp))
            Text(it, style = PawseType.Body, modifier = Modifier.padding(top = 4.dp))
        }
        s.note?.let { Text(it, style = PawseType.Micro, color = faint, modifier = Modifier.padding(top = 8.dp)) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 12.dp))
        Text(s.source, style = PawseType.Micro, color = faint, modifier = Modifier.padding(top = 10.dp))
    }
}

private fun f2(x: Double): String = (if (x >= 0) "+" else "−") + String.format(Locale.US, "%.2f", abs(x))

private fun d1(x: Double): String = String.format(Locale.getDefault(), "%.1f", x)

private fun confidenceWord(c: Confidence): String = when (c) {
    Confidence.HIGH -> tr("High", "높음")
    Confidence.MEDIUM -> tr("Medium", "보통")
    Confidence.LOW -> tr("Low", "낮음")
}

@Composable
private fun calcSections(day: DayReport?): List<CalcSectionCopy> {
    val body = day?.body
    val w = body?.hrvWeight ?: 0.6
    val wP = (w * 100).roundToInt()
    val hrvSource = body?.hrv?.source?.takeIf { it.isNotBlank() }?.let { writer(it) }
    val sleep = day?.sleep
    val load = day?.load
    return listOf(
        CalcSectionCopy(
            CalcSection.BODY,
            tr("Body", "컨디션"),
            tr("0–100 · your normal reads 50", "0–100 · 평소는 50"),
            tr("Body = 100 × Φ(z)", "컨디션 = 100 × Φ(z)"),
            "z = (w·zh + (1 − w)·zr) ÷ √(w² + (1 − w)² + 2w(1 − w)ρ)",
            listOf(
                "zh" to tr("HRV in SDs from your normal, read on a log scale", "평소 대비 HRV 편차(SD), 로그 척도"),
                "zr" to tr("Sleeping heart rate in SDs from your normal, sign flipped so lower pushes Body up", "평소 대비 수면 중 심박수 편차(SD), 낮을수록 올라가도록 부호를 바꿔요"),
                "w" to tr("HRV's share: $wP% now, adapting slowly after 30 check-ins", "HRV 비중: 지금 $wP%, 기분 기록 30번 뒤 천천히 맞춰져요"),
                "ρ" to tr("How much the two signals overlap, so the same nervous system is not counted twice", "두 신호가 겹치는 정도. 같은 신경계를 두 번 세지 않게 해요"),
                "Φ" to tr("The normal curve: z = 0 reads 50", "정규분포 누적: z = 0이면 50"),
                tr("Typical", "평소 수준") to tr("100 × Φ(±0.5) = 31–69, within half an SD of your normal", "100 × Φ(±0.5) = 31–69, 평소에서 SD 절반 이내"),
            ),
            body?.let { b ->
                val hrv = b.hrv
                val hr = b.sleepingHr
                val parts = listOfNotNull(
                    hrv?.tonight?.let { tr("HRV ${it.roundToInt()} ms against your normal ${hrv.baseline.roundToInt()}: zh ${f2(hrv.z)}", "HRV ${it.roundToInt()} ms(평소 ${hrv.baseline.roundToInt()}): zh ${f2(hrv.z)}") },
                    hr?.tonight?.let { tr("sleeping heart rate ${it.roundToInt()} bpm against ${d1(hr.baseline)}: zr ${f2(hr.z)}", "수면 중 심박수 ${it.roundToInt()} bpm(평소 ${d1(hr.baseline)}): zr ${f2(hr.z)}") },
                )
                parts.joinToString(", ") + tr(". Together z = ${f2(b.z)}, and 100 × Φ(${f2(b.z)}) = ${b.score}.", ". 합치면 z = ${f2(b.z)}, 100 × Φ(${f2(b.z)}) = ${b.score}.")
            },
            tr(
                "zh and zr come from the night's signals against a slowly moving normal (a Kalman filter), so they sit a little off the plain (value − normal) ÷ SD.",
                "zh와 zr은 그 밤의 신호를 천천히 움직이는 평소(칼만 필터)와 비교해 읽어서, 단순한 (값 − 평소) ÷ SD와 조금 달라요.",
            ),
            tr(
                "From Health Connect: HRV (RMSSD) and heart rate written overnight${hrvSource?.let { " by $it" }.orEmpty()}. Your normal is the 60 nights ending a week before.",
                "Health Connect에서: 밤사이 기록된 HRV(RMSSD)와 심박수${hrvSource?.let { " ($it)" }.orEmpty()}. 내 평소는 일주일 전까지의 60일 밤이에요.",
            ),
        ),
        CalcSectionCopy(
            CalcSection.LIKELY,
            tr("Likely range and confidence", "범위와 신뢰도"),
            tr("behind the confidence chip", "신뢰도 칩 안"),
            tr("Likely = 100 × Φ(z − s) to 100 × Φ(z + s)", "범위 = 100 × Φ(z − s) ~ 100 × Φ(z + s)"),
            null,
            listOf(
                "s" to tr("The filter's own spread for that morning: wider after a short or patchy night", "그날 아침 필터 자체의 퍼짐. 짧거나 끊긴 밤 뒤에는 넓어져요"),
                tr("High", "높음") to tr("10 or more nights in your normal and both signals present", "평소에 밤 10일 이상, 두 신호 모두 있음"),
                tr("Low", "낮음") to tr("Fewer nights, or one signal missing", "밤이 더 적거나 신호 하나가 빠짐"),
            ),
            body?.let { b ->
                tr(
                    "z ${f2(b.z)} with s ${String.format(Locale.US, "%.2f", b.zSd)}: ${b.low} to ${b.high}. ${b.validNights} nights → ${confidenceWord(b.confidence)} confidence.",
                    "z ${f2(b.z)}, s ${String.format(Locale.US, "%.2f", b.zSd)}: ${b.low}부터 ${b.high}까지. 밤 ${b.validNights}일 → 신뢰도 ${confidenceWord(b.confidence)}.",
                )
            },
            null,
            tr("Worked out on this phone. The spread grows when the watch was off for part of the night.", "이 휴대폰에서 계산해요. 밤중에 워치를 벗은 시간이 있으면 퍼짐이 커져요."),
        ),
        CalcSectionCopy(
            CalcSection.SLEEP,
            tr("Sleep score and need", "수면 점수와 필요한 잠"),
            tr("optional, one level down", "선택 · 한 단계 아래"),
            tr("Score = 0.45·D + 0.35·R + 0.20·C", "점수 = 0.45·D + 0.35·R + 0.20·C"),
            null,
            listOf(
                "D" to tr("Duration: 100 × asleep ÷ need, capped at 100", "수면 시간: 100 × 잠 ÷ 필요량, 최대 100"),
                "R" to tr("Regularity: how alike your sleep and wake times are, day to day (SRI)", "규칙성: 날마다 잠들고 깨는 시간이 얼마나 비슷한지(SRI)"),
                "C" to tr("Continuity: 100 × asleep ÷ (asleep + awake in bed)", "연속성: 100 × 잠 ÷ (잠 + 누운 동안 깬 시간)"),
                tr("Need", "필요한 잠") to tr(
                    "Learned from nights off, 8 h until then; 4 min more a load point above your usual day, at most 30",
                    "쉬는 날 밤에서 배워요. 그 전에는 8시간. 평소보다 부하 1점마다 4분, 최대 30분 더해요",
                ),
                tr("Stages", "수면 단계") to tr("Shown, never scored", "보여주기만 하고 점수에는 넣지 않아요"),
            ),
            sleep?.let { s ->
                val p = s.parts
                val asleep = s.lastNightMinutes
                if (p == null || asleep == null || s.score == null) return@let null
                val d = (p.duration * 100).roundToInt()
                val r = p.regularity?.let { (it * 100).roundToInt() }
                val c = p.continuity?.let { (it * 100).roundToInt() }
                val need = ScoreCopy.formatMinutes(s.needHours * 60)
                val head = tr("Asleep ${ScoreCopy.formatMinutes(asleep)} of $need → D $d", "$need 중 ${ScoreCopy.formatMinutes(asleep)} 잠 → D $d")
                if (r != null && c != null) {
                    head + tr(". R $r, C $c. ", ". R $r, C $c. ") + "0.45 × $d + 0.35 × $r + 0.20 × $c = ${d1(0.45 * d + 0.35 * r + 0.20 * c)} → ${s.score}."
                } else {
                    head + tr(" → ${s.score}.", " → ${s.score}.")
                }
            },
            tr("Missed sleep never adds up into a debt you must repay: each night is read on its own.", "못 잔 잠을 갚아야 할 빚처럼 쌓지 않아요. 밤마다 따로 읽어요."),
            tr("From Health Connect: the sleep session and its stages.", "Health Connect에서: 수면 세션과 단계."),
        ),
        CalcSectionCopy(
            CalcSection.LOAD,
            tr("Load, fitness and fatigue", "부하, 체력, 피로"),
            tr("grey: training, not a grade", "회색: 훈련량, 성적 아님"),
            tr("Form = (fitness − fatigue) ÷ fitness", "폼 = (체력 − 피로) ÷ 체력"),
            tr("Range = r to r + 5, r = round(5 + Body ÷ 20)", "범위 = r ~ r + 5, r = round(5 + 컨디션 ÷ 20)"),
            listOf(
                tr("Load", "부하") to tr(
                    "Heart-rate minutes weighted by effort (Banister TRIMP), scaled so your typical active day is 10; steps stand in when there is no heart rate",
                    "강도로 가중한 심박 분(Banister TRIMP), 평소 활동한 날이 10이 되게 맞춰요. 심박수가 없으면 걸음 수로 대신해요",
                ),
                tr("Fitness", "체력") to tr("The 42-day average of daily load", "하루 부하의 42일 평균"),
                tr("Fatigue", "피로") to tr("The 7-day average of daily load", "하루 부하의 7일 평균"),
                tr("Form", "폼") to tr("fresh above +5% · productive to −30% · heavy below", "+5% 위 가뿐함 · −30%까지 효과적 · 그 아래 무거움"),
                tr("Rest", "휴식") to tr("With the illness alert, or Body strained two mornings running, the range is 0–5", "질병 알림이 있거나 이틀 연속 컨디션이 크게 낮으면 범위는 0–5"),
            ),
            load?.let { l ->
                val range = l.pointsRange
                val bd = body?.score
                val first = if (range != null && bd != null && !range.rest) {
                    tr(
                        "Body $bd → r = round(5 + ${d1(bd / 20.0)}) = ${range.low}, so ${range.low}–${range.high}; ${d1(l.points)} so far.",
                        "컨디션 $bd → r = round(5 + ${d1(bd / 20.0)}) = ${range.low}, 그래서 ${range.low}–${range.high}. 지금까지 ${d1(l.points)}.",
                    )
                } else {
                    tr("${d1(l.points)} so far.", "지금까지 ${d1(l.points)}.")
                }
                val fit = l.fitness
                val fat = l.fatigue
                val second = if (fit != null && fat != null && fit > 0) {
                    " " + tr(
                        "Fitness ${d1(fit)} and fatigue ${d1(fat)} → form = (${d1(fit)} − ${d1(fat)}) ÷ ${d1(fit)} = ${((fit - fat) / fit * 100).roundToInt()}%.",
                        "체력 ${d1(fit)}, 피로 ${d1(fat)} → 폼 = (${d1(fit)} − ${d1(fat)}) ÷ ${d1(fit)} = ${((fit - fat) / fit * 100).roundToInt()}%.",
                    )
                } else {
                    ""
                }
                first + second
            },
            null,
            tr("From Health Connect: exercise sessions with heart rate, and steps.", "Health Connect에서: 심박수가 있는 운동 기록과 걸음 수."),
        ),
        CalcSectionCopy(
            CalcSection.USUAL,
            tr("Your usual", "내 평소"),
            tr("the faint band", "옅은 띠"),
            tr("Usual = median ± 1.4826 × MAD", "평소 = 중앙값 ± 1.4826 × MAD"),
            null,
            listOf(
                tr("Days", "날") to tr("Up to 60 days with a reading, ending a week before the day shown", "표시한 날 일주일 전까지, 기록이 있는 최대 60일"),
                tr("Needs", "필요") to tr("At least 4 days; until then it says how many more", "최소 4일. 그 전에는 몇 번이 더 필요한지 알려요"),
                tr("Why", "이유") to tr("Median and MAD, so one sick week cannot widen it; ending a week back, so a hard week cannot quietly become your normal", "중앙값과 MAD라서 아픈 한 주가 넓히지 못하고, 일주일 전까지라서 힘든 한 주가 슬그머니 평소가 되지 않아요"),
            ),
            body?.hrv?.let { h -> tr("HRV: your normal is ${h.baseline.roundToInt()} ms over ${h.baselineNights} nights.", "HRV: 내 평소는 밤 ${h.baselineNights}일 기준 ${h.baseline.roundToInt()} ms예요.") },
            null,
            tr("Worked out from what Pawse has already read; nothing extra is fetched.", "Pawse가 이미 읽은 기록으로 계산해요. 따로 가져오는 건 없어요."),
        ),
        CalcSectionCopy(
            CalcSection.SAME,
            tr("“≈ about the same”", "“≈ 비슷함”"),
            tr("Side by side", "나란히 보기"),
            tr("≈ when |a − b| < ½ × SD of daily change", "|a − b| < ½ × 하루 변화의 SD이면 ≈"),
            null,
            listOf(
                tr("Daily change", "하루 변화") to tr("The SD of each day minus the day before, over the same days as your usual", "같은 기간 동안 하루와 전날의 차이의 SD"),
                tr("Needs", "필요") to tr("14 days; before that every gap shows its size", "14일. 그 전에는 모든 차이를 크기로 보여줘요"),
                tr("Never", "하지 않음") to tr("Better or worse, a colour, or a best night", "더 좋음·나쁨, 색, 최고의 밤"),
            ),
            null,
            null,
            tr("Used in Side by side and the compare readouts only.", "나란히 보기와 비교 읽기에만 써요."),
        ),
    )
}
