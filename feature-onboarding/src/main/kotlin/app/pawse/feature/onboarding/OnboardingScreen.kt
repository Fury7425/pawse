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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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

/** The mark: a usual range drawn left to right, and tonight dropping onto it. */
@Composable
private fun Mark() {
    val band = remember { Animatable(0f) }
    val drop = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        band.animateTo(1f, tween(520, easing = EaseInOut))
        drop.animateTo(1f, tween(420, easing = EaseOut))
    }
    val line = MaterialTheme.colorScheme.onSurface
    val dot = PawseTheme.semantics.bandHigh
    Canvas(
        Modifier
            .size(width = 132.dp, height = 64.dp)
            .semantics { contentDescription = "Pawse" },
    ) {
        val h = 6.dp.toPx()
        drawRoundRect(line, Offset(0f, 44.dp.toPx()), Size(size.width * band.value, h), CornerRadius(h / 2))
        if (drop.value > 0f) {
            val r = 9.dp.toPx()
            val c = Offset(76.dp.toPx() + r, 14.dp.toPx() + r - (1f - drop.value) * 28.dp.toPx())
            drawCircle(dot.copy(alpha = 0.25f * drop.value), r * 2f, c)
            drawCircle(dot.copy(alpha = drop.value), r, c)
        }
    }
}

/** A getter, not a list built once, so a language change while the app runs is picked up. */
private val READS: List<Triple<String, String, String>>
    get() {
        val overnight = tr("Overnight", "밤사이")
        val allDay = tr("All day", "하루 종일")
        return listOf(
            Triple(tr("Heart rate variability", "심박 변이도"), overnight, Glyphs.Recovery),
            Triple(tr("Resting heart rate", "안정 시 심박수"), overnight, Glyphs.Heart),
            Triple(tr("Sleep", "수면"), tr("Stages, if recorded", "기록됐다면 수면 단계까지"), Glyphs.Sleep),
            Triple(tr("Breathing rate", "호흡수"), overnight, Glyphs.Breath),
            Triple(tr("Skin temperature", "피부 온도"), overnight, Glyphs.Thermometer),
            Triple(tr("Blood oxygen", "혈중 산소"), overnight, Glyphs.Drop),
            Triple(tr("Workouts and steps", "운동과 걸음 수"), allDay, Glyphs.Strain),
            Triple(tr("Calories burned", "소모 칼로리"), allDay, Glyphs.Flame),
            Triple(tr("Weight and body composition", "체중과 체성분"), tr("When you weigh in", "체중을 잴 때"), Glyphs.Body),
            Triple(tr("Basal metabolic rate", "기초대사량"), tr("If your scale writes it", "체중계가 기록한다면"), Glyphs.Gauge),
            Triple(tr("Periods", "생리"), tr("If you log them", "기록한다면"), Glyphs.Clock),
            Triple(tr("Past history", "과거 기록"), tr("So baselines start full", "기준을 처음부터 채우려고"), Glyphs.Synced),
        )
    }

@Composable
private fun Explain(onContinue: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 140.dp),
        ) {
            Steps(done = 1)
            Spacer(Modifier.height(40.dp))
            Mark()
            Spacer(Modifier.height(14.dp))
            Text("Pawse", style = PawseType.Heading)

            Spacer(Modifier.height(28.dp))
            Text(tr("What this reads", "읽는 데이터"), style = PawseType.Display)
            Spacer(Modifier.height(8.dp))
            Text(
                tr(
                    "Pawse reads what your watch or ring already writes to Health Connect, and reads it against your own history.",
                    "Pawse는 시계나 링이 이미 헬스 커넥트에 기록한 데이터를 읽고, 내 과거 기록과 비교해 해석해요.",
                ),
                style = PawseType.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))
            PawseCard(padding = PaddingValues(0.dp)) {
                Text(
                    tr("Reads", "읽기"),
                    style = PawseType.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                )
                READS.forEachIndexed { i, (name, whenRead, glyph) ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph(glyph, size = 16.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                        Text(name, style = PawseType.Body, modifier = Modifier.weight(1f))
                        Text(whenRead, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            PawseCard(padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Writes", "쓰기"), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(2.dp))
                        Text(tr("Only food and weigh-ins you type in", "직접 입력한 음식과 체중만"), style = PawseType.Body)
                    }
                    Text(tr("Optional", "선택"), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(20.dp))
            Row {
                Glyph(Glyphs.Shield, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text(
                    tr(
                        "Everything stays on this phone. No account, no server. Grant only what you want; " +
                            "anything you leave out is dropped from the maths rather than guessed at.",
                        "모든 데이터는 이 휴대폰에만 남아요. 계정도 서버도 없어요. 원하는 것만 허용하세요. " +
                            "허용하지 않은 항목은 추측하지 않고 계산에서 빼요.",
                    ),
                    style = PawseType.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        BottomAction(text = tr("Choose what to share", "공유할 항목 고르기"), onClick = onContinue)
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
            Text(tr("What we can measure", "측정할 수 있는 것"), style = PawseType.Display)
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
        ScoreRing(fraction = coverage, color = dot, size = 52.dp, glow = false, ticks = false, stroke = 8f) {
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
