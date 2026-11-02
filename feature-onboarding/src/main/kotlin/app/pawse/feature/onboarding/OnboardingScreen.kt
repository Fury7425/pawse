package app.pawse.feature.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pawse.core.data.health.CapabilityReport
import app.pawse.core.data.health.ScoreCapability
import app.pawse.core.ui.component.BottomAction
import app.pawse.core.ui.component.EaseInOut
import app.pawse.core.ui.component.EaseOut
import app.pawse.core.ui.component.GhostButton
import app.pawse.core.ui.component.Glyph
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.PawseCard
import app.pawse.core.ui.component.PrimaryButton
import app.pawse.core.ui.component.ScoreRing
import app.pawse.core.ui.theme.PawseTheme
import app.pawse.core.ui.theme.PawseType
import app.pawse.scoring.model.ScoreType
import app.pawse.scoring.tr
import kotlin.math.roundToInt

/**
 * Onboarding is three beats: say what we read, ask, then report honestly what we
 * found. The third beat is the one that matters and the one no competitor does.
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val launcher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { granted -> viewModel.onPermissionResult(granted) }

    if (state.step == OnboardingStep.DONE) {
        onFinished()
        return
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            when (state.step) {
                OnboardingStep.CHECKING,
                OnboardingStep.REQUESTING,
                OnboardingStep.SYNCING,
                OnboardingStep.PROBING,
                -> Busy(state.step)

                OnboardingStep.UNAVAILABLE -> Message(
                    title = tr("Health Connect is not available", "헬스 커넥트를 사용할 수 없어요"),
                    body = tr(
                        "This phone has no Health Connect, so there is no watch or ring " +
                            "data to interpret. Nothing here would be accurate without it.",
                        "이 휴대폰에는 헬스 커넥트가 없어서 해석할 시계나 링 데이터가 없어요. " +
                            "헬스 커넥트 없이는 여기 나오는 어떤 값도 정확할 수 없어요.",
                    ),
                )

                OnboardingStep.NEEDS_UPDATE -> Message(
                    title = tr("Health Connect needs updating", "헬스 커넥트 업데이트가 필요해요"),
                    body = tr("Update Health Connect in the Play Store, then come back.", "Play 스토어에서 헬스 커넥트를 업데이트한 뒤 돌아오세요."),
                    action = tr("Check again", "다시 확인") to viewModel::refreshAvailability,
                )

                OnboardingStep.EXPLAIN -> Explain(
                    onContinue = {
                        viewModel.onRequestStarted()
                        launcher.launch(viewModel.permissionsToRequest)
                    },
                    onLookAround = viewModel::finish,
                )

                OnboardingStep.REPORT -> state.report?.let {
                    Report(report = it, error = state.error, onContinue = viewModel::finish)
                } ?: Message(
                    title = tr("Could not read your data", "데이터를 읽지 못했어요"),
                    body = state.error ?: tr("Something went wrong reading Health Connect.", "헬스 커넥트를 읽는 중 문제가 생겼어요."),
                    action = tr("Try again", "다시 시도") to viewModel::retryProbe,
                )

                OnboardingStep.DONE -> Unit
            }
        }
    }
}

@Composable
private fun Busy(step: OnboardingStep) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurface, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(20.dp))
        Text(
            text = when (step) {
                OnboardingStep.SYNCING -> tr("Reading your last 120 days", "최근 120일 기록을 읽는 중")
                OnboardingStep.PROBING -> tr("Working out what we can measure", "측정할 수 있는 항목을 확인하는 중")
                else -> tr("Checking Health Connect", "헬스 커넥트 확인 중")
            },
            style = PawseType.Body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Message(title: String, body: String, action: Pair<String, () -> Unit>? = null) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = PawseType.Title)
        Spacer(Modifier.height(8.dp))
        Text(body, style = PawseType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.height(24.dp))
            PrimaryButton(action.first, onClick = action.second)
        }
    }
}

/** Three hairlines across the top: which beat of three this is. */
@Composable
private fun Steps(done: Int) {
    Row(
        Modifier.semantics(mergeDescendants = true) { contentDescription = tr("Step $done of 3", "3단계 중 ${done}단계") },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(3) { i ->
            Box(
                Modifier
                    .weight(1f)
                    .height(3.dp)
                    .background(
                        if (i < done) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHigh,
                        RoundedCornerShape(2.dp),
                    ),
            )
        }
    }
}


/**
 * First run: value before commitment. A sample morning first, dimmed and labelled
 * as not yours, then each kind of data with the reason it is asked for, and a way
 * to look around before granting anything.
 */
@Composable
private fun Explain(onContinue: () -> Unit, onLookAround: () -> Unit) {
    val s = PawseTheme.semantics
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 16.dp, bottom = 40.dp),
    ) {
        Text(tr("Sample · not your data", "샘플 · 내 데이터 아님"), style = PawseType.Micro, color = s.faint, modifier = Modifier.padding(horizontal = 24.dp))
        Row(
            Modifier
                .padding(start = 24.dp, end = 24.dp, top = 16.dp)
                .alpha(0.45f)
                .clearAndSetSemantics {},
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            SampleRing(tr("Sleep", "수면"), "7.4", 7.4f / 7.83f, grey, 84.dp, Modifier.weight(1f))
            SampleRing(tr("Body", "컨디션"), "78", 0.78f, s.bandHigh, 112.dp, Modifier.weight(1.4f))
            SampleRing(tr("Load", "부하"), "12", 12f / 16f, grey, 84.dp, Modifier.weight(1f), range = 9f / 16f..14f / 16f)
        }
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text(
                tr("Your sleep, your body and your training, on this phone", "수면, 컨디션, 운동을 이 휴대폰 안에서"),
                style = PawseType.Title,
                modifier = Modifier.padding(top = 32.dp),
            )
            Text(
                tr(
                    "Pawse reads from Health Connect and works everything out here. Nothing leaves the phone.",
                    "Pawse는 헬스 커넥트에서 읽고 모든 계산을 휴대폰 안에서 해요. 어떤 데이터도 밖으로 나가지 않아요.",
                ),
                style = PawseType.Label,
                color = grey,
                modifier = Modifier.padding(top = 8.dp),
            )
            Column(Modifier.padding(top = 16.dp)) {
                ASKS.forEach { (glyph, title, why) ->
                    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph(glyph, size = 24.dp, color = grey)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(title, style = PawseType.BodyStrong)
                            Text(why, style = PawseType.Label, color = grey)
                        }
                    }
                }
            }
            PrimaryButton(tr("Choose what to share", "공유할 항목 선택"), onClick = onContinue, modifier = Modifier.padding(top = 24.dp))
            GhostButton(tr("Not now, look around first", "나중에, 먼저 둘러볼게요"), onClick = onLookAround, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** Each kind of data with the ring it serves, in the order the rings sit. */
private val ASKS: List<Triple<String, String, String>>
    get() = listOf(
        Triple(Glyphs.Sleep, tr("Sleep", "수면"), tr("For the Sleep ring and your bedtime", "수면 링과 취침 시각에 쓰여요")),
        Triple(Glyphs.Heart, tr("Heart rate and HRV", "심박수와 심박변이도"), tr("For Body, compared with your normal", "컨디션 점수, 평소와 비교")),
        Triple(Glyphs.Strain, tr("Exercise and steps", "운동과 걸음 수"), tr("For Load", "부하 점수에 쓰여요")),
        Triple(Glyphs.Food, tr("Weight and nutrition", "체중과 영양"), tr("Optional. Food can also write back.", "선택 사항. 음식 기록을 다시 저장할 수도 있어요.")),
    )

@Composable
private fun SampleRing(
    name: String,
    value: String,
    fraction: Float,
    color: Color,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float>? = null,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        ScoreRing(fraction = fraction, color = color, size = size, glow = false, range = range, stroke = 7.2f) {
            Text(value, style = if (size > 100.dp) PawseType.Numeral else PawseType.RingSmall)
        }
        Text(name, style = PawseType.BodyStrong, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun Report(report: CapabilityReport, error: String?, onContinue: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 220.dp),
        ) {
            Steps(done = 3)
            Spacer(Modifier.height(32.dp))
            Text(tr("What we can measure", "측정할 수 있는 것"), style = PawseType.Title)
            Spacer(Modifier.height(8.dp))
            Text(
                tr(
                    "Based on ${report.nightsWithSleep} nights found in the last ${report.probedDays} days.",
                    "최근 ${report.probedDays}일 동안 찾은 밤 ${report.nightsWithSleep}번을 바탕으로 했어요.",
                ),
                style = PawseType.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            report.scores.forEach { capability ->
                ScoreCapabilityRow(capability)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            CapabilityRow(
                name = tr("Body", "신체"),
                glyph = Glyphs.Body,
                coverage = if (report.weighIns > 0) 1f else 0f,
                dot = if (report.weighIns > 0) PawseTheme.semantics.better else PawseTheme.semantics.neutral,
                state = if (report.weighIns > 0) tr("Available", "사용 가능") else tr("No scale yet", "아직 체중계 없음"),
                why = if (report.weighIns > 0) {
                    tr(
                        "Your scale writes weight" +
                            report.bodyParts.joinToString(prefix = if (report.bodyParts.isEmpty()) "" else ", ") +
                            ": ${report.weighIns} weigh-ins in ${report.probedDays} days.",
                        "체중계가 " + (listOf("체중") + report.bodyParts).joinToString(", ") +
                            "을 기록해요: ${report.probedDays}일 동안 ${report.weighIns}번 측정.",
                    )
                } else {
                    tr(
                        "Nothing writes weight to Health Connect yet. The Body tab fills in on its own when a scale does.",
                        "아직 헬스 커넥트에 체중을 기록하는 앱이 없어요. 체중계가 기록하면 신체 탭이 저절로 채워져요.",
                    )
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            report.samsungChecklist?.let { items ->
                Spacer(Modifier.height(20.dp))
                SamsungChecklist(items)
            }

            if (report.contestedTypes.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                PawseCard(padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                    Row {
                        Glyph(Glyphs.Info, size = 18.dp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 1.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            tr(
                                "Two apps are writing the same records (${report.contestedTypes.joinToString()}). " +
                                    "We will use one source per night rather than double-counting. You can change which one in settings.",
                                "두 앱이 같은 기록을 쓰고 있어요(${report.contestedTypes.joinToString()}). 중복으로 세지 않도록 " +
                                    "밤마다 한 출처만 써요. 어느 쪽을 쓸지는 설정에서 바꿀 수 있어요.",
                            ),
                            style = PawseType.Label,
                        )
                    }
                }
            }

            if (error != null) {
                Spacer(Modifier.height(16.dp))
                Text(error, style = PawseType.Label, color = PawseTheme.semantics.worse)
            }
        }
        BottomAction(text = tr("Start", "시작하기"), onClick = onContinue) {
            Text(
                tr(
                    "Body needs seven nights before it reads anything, and settles over a few weeks. Until then it says “settling”.",
                    "컨디션은 7일 밤이 쌓여야 값을 읽고, 몇 주에 걸쳐 안정돼요. 그때까지는 ‘기준 잡는 중’이라고 표시해요.",
                ),
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun ScoreCapabilityRow(capability: ScoreCapability) {
    val semantics = PawseTheme.semantics
    CapabilityRow(
        name = capability.type.displayName(),
        glyph = capability.type.glyph(),
        coverage = capability.expectedCoverage,
        dot = when {
            !capability.computable -> semantics.worse
            capability.degraded -> semantics.bandModerate
            else -> semantics.better
        },
        // Never hue alone: the dot is always paired with this word.
        state = when {
            !capability.computable -> tr("Not available", "사용 불가")
            capability.degraded -> tr("Lower confidence", "신뢰도 낮음")
            else -> tr("Available", "사용 가능")
        },
        why = capability.explanation,
    )
}

/** A score's coverage as a small ring, its name, its state, and why. */
@Composable
private fun CapabilityRow(name: String, glyph: String, coverage: Float, dot: Color, state: String, why: String) {
    val percent = (coverage * 100).roundToInt()
    Row(
        Modifier
            .padding(vertical = 14.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = tr("$name: $state, $percent% of its inputs found. $why", "$name: $state, 입력값의 $percent% 확인. $why")
            },
    ) {
        ScoreRing(fraction = coverage, color = dot, size = 52.dp, glow = false, stroke = 8f) {
            Text("$percent%", style = PawseType.Label.copy(fontWeight = PawseType.BodyStrong.fontWeight))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Glyph(glyph, size = 14.dp, strokeWidth = 2f, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(name, style = PawseType.Heading, modifier = Modifier.weight(1f))
                Box(Modifier.size(8.dp).background(dot, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(state, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Text(why, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun ScoreType.displayName(): String = when (this) {
    ScoreType.BODY -> tr("Body", "컨디션")
    ScoreType.SLEEP -> tr("Sleep", "수면")
    ScoreType.LOAD -> tr("Load", "부하")
}

private fun ScoreType.glyph(): String = when (this) {
    ScoreType.BODY -> Glyphs.Recovery
    ScoreType.SLEEP -> Glyphs.Sleep
    ScoreType.LOAD -> Glyphs.Load
}

/**
 * Samsung Health shares HRV, skin temperature, breathing rate and resting HR with
 * Health Connect only when each is allowed in Samsung Health's own settings, and
 * Body is noticeably better with all four.
 */
@Composable
private fun SamsungChecklist(items: List<Pair<String, Boolean>>) {
    PawseCard(padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Text(tr("Samsung Health checklist", "삼성 헬스 체크리스트"), style = PawseType.Heading)
        Spacer(Modifier.height(4.dp))
        Text(
            tr(
                "In Samsung Health, open Settings › Health Connect and allow each of these to be shared. " +
                    "Watch data reaches the phone when the watch reconnects or Samsung Health is opened.",
                "삼성 헬스에서 설정 › 헬스 커넥트를 열고 아래 항목을 하나씩 공유하도록 허용하세요. " +
                    "시계 데이터는 시계가 다시 연결되거나 삼성 헬스를 열 때 휴대폰으로 넘어와요.",
            ),
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        items.forEach { (name, found) ->
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(if (found) Glyphs.Check else Glyphs.Close, size = 16.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Text(name, style = PawseType.Body, modifier = Modifier.weight(1f))
                Text(if (found) tr("Shared", "공유됨") else tr("Not seen yet", "아직 안 보임"), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
