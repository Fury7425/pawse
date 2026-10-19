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
                    title = "Health Connect is not available",
                    body = "This phone has no Health Connect, so there is no watch or ring " +
                        "data to interpret. Nothing here would be accurate without it.",
                )

                OnboardingStep.NEEDS_UPDATE -> Message(
                    title = "Health Connect needs updating",
                    body = "Update Health Connect in the Play Store, then come back.",
                    action = "Check again" to viewModel::refreshAvailability,
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
                    title = "Could not read your data",
                    body = state.error ?: "Something went wrong reading Health Connect.",
                    action = "Try again" to viewModel::retryProbe,
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
                OnboardingStep.SYNCING -> "Reading your last 90 days"
                OnboardingStep.PROBING -> "Working out what we can measure"
                else -> "Checking Health Connect"
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
        Modifier.semantics(mergeDescendants = true) { contentDescription = "Step $done of 3" },
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

private val READS = listOf(
    Triple("Heart rate variability", "Overnight", Glyphs.Recovery),
    Triple("Resting heart rate", "Overnight", Glyphs.Heart),
    Triple("Sleep", "Stages, if recorded", Glyphs.Sleep),
    Triple("Breathing rate", "Overnight", Glyphs.Breath),
    Triple("Skin temperature", "Overnight", Glyphs.Thermometer),
    Triple("Blood oxygen", "Overnight", Glyphs.Drop),
    Triple("Workouts and steps", "All day", Glyphs.Strain),
    Triple("Calories burned", "All day", Glyphs.Flame),
    Triple("Weight and body composition", "When you weigh in", Glyphs.Body),
    Triple("Basal metabolic rate", "If your scale writes it", Glyphs.Gauge),
)

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
            Text("What this reads", style = PawseType.Display)
            Spacer(Modifier.height(8.dp))
            Text(
                "Pawse reads what your watch or ring already writes to Health Connect, and reads it against your own history.",
                style = PawseType.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))
            PawseCard(padding = PaddingValues(0.dp)) {
                Text(
                    "Reads",
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
                        Text("Writes", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(2.dp))
                        Text("Only food and weigh-ins you type in", style = PawseType.Body)
                    }
                    Text("Optional", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(20.dp))
            Row {
                Glyph(Glyphs.Shield, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text(
                    "Everything stays on this phone. No account, no server. Grant only what you want; " +
                        "anything you leave out is dropped from the maths rather than guessed at.",
                    style = PawseType.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        BottomAction(text = "Choose what to share", onClick = onContinue)
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
            Text("What we can measure", style = PawseType.Display)
            Spacer(Modifier.height(8.dp))
            Text(
                "Based on ${report.nightsWithSleep} nights found in the last ${report.probedDays} days.",
                style = PawseType.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            report.scores.forEach { capability ->
                ScoreCapabilityRow(capability)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            CapabilityRow(
                name = "Body",
                glyph = Glyphs.Body,
                coverage = if (report.weighIns > 0) 1f else 0f,
                dot = if (report.weighIns > 0) PawseTheme.semantics.better else PawseTheme.semantics.neutral,
                state = if (report.weighIns > 0) "Available" else "No scale yet",
                why = if (report.weighIns > 0) {
                    "Your scale writes weight" +
                        report.bodyParts.joinToString(prefix = if (report.bodyParts.isEmpty()) "" else ", ") +
                        ": ${report.weighIns} weigh-ins in ${report.probedDays} days."
                } else {
                    "Nothing writes weight to Health Connect yet. The Body tab fills in on its own when a scale does."
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (report.contestedTypes.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                PawseCard(padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                    Row {
                        Glyph(Glyphs.Info, size = 18.dp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 1.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Two apps are writing the same records (${report.contestedTypes.joinToString()}). " +
                                "We will use one source per night rather than double-counting. You can change which one in settings.",
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
        BottomAction(text = "Start", onClick = onContinue) {
            Text(
                "Scores need about two weeks of nights to calibrate against you. Until then they say “still settling”.",
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
            !capability.computable -> "Not available"
            capability.degraded -> "Lower confidence"
            else -> "Available"
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
            .semantics(mergeDescendants = true) { contentDescription = "$name: $state, $percent% of its inputs found. $why" },
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
    ScoreType.RECOVERY -> "Recovery"
    ScoreType.SLEEP -> "Sleep"
    ScoreType.STRAIN -> "Strain"
    ScoreType.ENERGY_BANK -> "Energy Bank"
    ScoreType.LOAD_RATIO -> "Training load"
}

private fun ScoreType.glyph(): String = when (this) {
    ScoreType.RECOVERY -> Glyphs.Recovery
    ScoreType.SLEEP -> Glyphs.Sleep
    ScoreType.STRAIN -> Glyphs.Strain
    ScoreType.ENERGY_BANK -> Glyphs.Energy
    ScoreType.LOAD_RATIO -> Glyphs.Load
}
