package app.pawse.feature.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pawse.core.data.health.CapabilityReport
import app.pawse.core.ui.component.BandDot
import app.pawse.core.ui.component.BottomAction
import app.pawse.core.ui.component.Glyph
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.PawseCard
import app.pawse.core.ui.component.PawseMotion
import app.pawse.core.ui.component.Pictogram
import app.pawse.core.ui.component.PrimaryButton
import app.pawse.core.ui.component.ScoreRing
import app.pawse.core.ui.component.StatePictogram
import app.pawse.core.ui.component.rememberReducedMotion
import app.pawse.core.ui.component.reveal
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

    LaunchedEffect(state.step) {
        if (state.step == OnboardingStep.DONE) onFinished()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
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

            OnboardingStep.EXPLAIN -> Welcome(
                onContinue = {
                    viewModel.onRequestStarted()
                    launcher.launch(viewModel.permissionsToRequest)
                },
            )

            OnboardingStep.REPORT -> state.report?.let {
                Measure(report = it, error = state.error, onContinue = viewModel::finish)
            } ?: Message(
                title = "Could not read your data",
                body = state.error ?: "Something went wrong reading Health Connect.",
                action = "Try again" to viewModel::retryProbe,
            )

            OnboardingStep.DONE -> Unit
        }
    }
}

/** Three short bars: where in the three beats this screen is. */
@Composable
private fun Steps(current: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = "Step $current of 3" },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(3) { i ->
            Box(
                Modifier
                    .weight(1f)
                    .height(3.dp)
                    .background(
                        if (i < current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHigh,
                        RoundedCornerShape(2.dp),
                    ),
            )
        }
    }
}

/** The screen's frame: inset, scrolling, the gutter, room for the bottom button. */
@Composable
private fun Page(content: @Composable () -> Unit) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, top = top + 12.dp, bottom = bottom + 180.dp),
    ) { content() }
}

private val TitleStyle = PawseType.Headline.copy(fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = (-0.8).sp)

private data class Read(val name: String, val time: String, val glyph: String)

private val Reads = listOf(
    Read("Heart rate variability", "Overnight", Glyphs.RECOVERY),
    Read("Resting heart rate", "Overnight", Glyphs.HEART),
    Read("Sleep", "Stages, if recorded", Glyphs.SLEEP),
    Read("Breathing rate", "Overnight", Glyphs.BREATH),
    Read("Skin temperature", "Overnight", Glyphs.TEMPERATURE),
    Read("Blood oxygen", "Overnight", Glyphs.OXYGEN),
    Read("Workouts and steps", "All day", Glyphs.STRAIN),
    Read("Calories burned", "All day", Glyphs.FLAME),
    Read("Weight and body composition", "When you weigh in", Glyphs.BODY),
    Read("Basal metabolic rate", "If your scale writes it", Glyphs.GAUGE),
)

/** Beat one: what this reads, what it writes, and that it all stays here. */
@Composable
private fun Welcome(onContinue: () -> Unit) {
    val play = !rememberReducedMotion()
    val glow = PawseTheme.semantics.bandHigh
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .offset(x = (-100).dp, y = 20.dp)
                .size(width = 420.dp, height = 320.dp)
                .background(Brush.radialGradient(listOf(glow.copy(alpha = 0.09f), Color.Transparent))),
        )
        Page {
            Steps(1)
            Mark(play, Modifier.padding(top = 40.dp))
            Text(
                "Pawse",
                style = PawseType.Title,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(top = 14.dp)
                    .reveal(play, 700),
            )
            Text(
                "What this reads",
                style = TitleStyle,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(top = 28.dp)
                    .reveal(play, 40),
            )
            Text(
                "Pawse reads what your watch or ring already writes to Health Connect, and reads it against your own history.",
                style = PawseType.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .reveal(play, 80),
            )
            PawseCard(
                Modifier
                    .padding(top = 20.dp)
                    .reveal(play, 120),
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(
                    "Reads",
                    style = PawseType.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                )
                Reads.forEachIndexed { i, read ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Glyph(read.glyph, size = 16.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(read.name, style = PawseType.Body, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                        Text(read.time, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            PawseCard(
                Modifier
                    .padding(top = 8.dp)
                    .reveal(play, 160),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text("Writes", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "Only the food you log",
                            style = PawseType.Body,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Text("Optional", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(
                Modifier
                    .padding(top = 20.dp)
                    .reveal(play, 200),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Glyph(Glyphs.SHIELD, size = 20.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) {
                            append("Everything stays on this phone.")
                        }
                        append(
                            " No account, no server. Grant only what you want; anything you leave out is " +
                                "dropped from the maths rather than guessed at.",
                        )
                    },
                    style = PawseType.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        BottomAction("Choose what to share", onContinue)
    }
}

/**
 * The app's mark: the baseline band, drawn in from the left, and tonight's dot
 * dropping onto its place above it.
 */
@Composable
private fun Mark(play: Boolean, modifier: Modifier = Modifier) {
    val band = remember { Animatable(if (play) 0f else 1f) }
    val dot = remember { Animatable(if (play) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (play) {
            band.animateTo(1f, tween(520, easing = PawseMotion.EaseInOut))
        }
    }
    LaunchedEffect(Unit) {
        if (play) dot.animateTo(1f, tween(420, delayMillis = 440, easing = PawseMotion.EaseOut))
    }
    val high = PawseTheme.semantics.bandHigh
    Box(
        modifier
            .size(width = 132.dp, height = 64.dp)
            .clearAndSetSemantics { contentDescription = "Pawse" },
    ) {
        Box(
            Modifier
                .offset(y = 44.dp)
                .fillMaxWidth()
                .height(6.dp)
                .graphicsLayer {
                    scaleX = band.value
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .background(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(3.dp)),
        )
        Box(
            Modifier
                .offset(x = 76.dp, y = 14.dp)
                .graphicsLayer {
                    alpha = dot.value
                    translationY = (1f - dot.value) * -28.dp.toPx()
                }
                .size(18.dp)
                .shadow(10.dp, CircleShape, ambientColor = high, spotColor = high)
                .background(high, CircleShape),
        )
    }
}

/** Beat two happens in Health Connect's own sheet; this is what shows around it. */
@Composable
private fun Busy(step: OnboardingStep) {
    Page {
        Steps(2)
        Text(
            text = when (step) {
                OnboardingStep.REQUESTING -> "Choose what to share"
                OnboardingStep.SYNCING -> "Reading your last 90 days"
                OnboardingStep.PROBING -> "Working out what we can measure"
                else -> "Checking Health Connect"
            },
            style = TitleStyle,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 32.dp),
        )
        Text(
            text = when (step) {
                OnboardingStep.REQUESTING ->
                    "Health Connect is asking which records Pawse may read. Anything you leave out is dropped from the maths."
                OnboardingStep.SYNCING -> "Nothing leaves this phone while it reads."
                OnboardingStep.PROBING -> "Checking which scores your devices can support, and how well."
                else -> "One moment."
            },
            style = PawseType.Body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        CircularProgressIndicator(
            modifier = Modifier
                .padding(top = 28.dp)
                .size(20.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Message(title: String, body: String, action: Pair<String, () -> Unit>? = null) {
    Page {
        StatePictogram(Pictogram.READ_FAILED, Modifier.padding(top = 96.dp))
        Text(title, style = PawseType.Headline, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 20.dp))
        Text(body, style = PawseType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        if (action != null) PrimaryButton(action.first, action.second, Modifier.padding(top = 24.dp))
    }
}

/** Beat three: an honest report of what we can compute, and at what confidence. */
@Composable
private fun Measure(report: CapabilityReport, error: String?, onContinue: () -> Unit) {
    val play = !rememberReducedMotion()
    val semantics = PawseTheme.semantics
    Box(Modifier.fillMaxSize()) {
        Page {
            Steps(3)
            Text("What we can measure", style = TitleStyle, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 32.dp))
            Text(
                "Based on ${report.nightsWithSleep} nights found in the last ${report.probedDays} days.",
                style = PawseType.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Column(Modifier.padding(top = 12.dp)) {
                report.scores.forEachIndexed { i, capability ->
                    // Never hue alone: the dot is always paired with this word.
                    val (state, color) = when {
                        !capability.computable -> "Not available" to semantics.bandLow
                        capability.degraded -> "Lower confidence" to semantics.bandModerate
                        else -> "Available" to semantics.bandHigh
                    }
                    Capability(
                        name = capability.type.displayName(),
                        glyph = capability.type.glyph(),
                        coverage = capability.expectedCoverage,
                        state = state,
                        color = color,
                        why = capability.explanation,
                        modifier = Modifier.reveal(play, i * 50),
                    )
                }
                Capability(
                    name = "Body",
                    glyph = Glyphs.BODY,
                    coverage = if (report.weighIns > 0) 1f else 0f,
                    state = if (report.weighIns > 0) "Available" else "No scale yet",
                    color = if (report.weighIns > 0) semantics.bandHigh else semantics.neutral,
                    why = if (report.weighIns > 0) {
                        "Your scale writes weight" +
                            report.bodyParts.joinToString(prefix = if (report.bodyParts.isEmpty()) "" else ", ") +
                            ": ${report.weighIns} weigh-ins in ${report.probedDays} days."
                    } else {
                        "Nothing writes weight to Health Connect yet. The Body tab fills in on its own when a scale does."
                    },
                    modifier = Modifier.reveal(play, report.scores.size * 50),
                )
            }

            if (report.contestedTypes.isNotEmpty()) {
                PawseCard(Modifier.padding(top = 20.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Glyph(Glyphs.INFO, size = 18.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "Two apps are writing the same records (${report.contestedTypes.joinToString()}). " +
                                "We will use one source per night rather than double-counting. You can change which " +
                                "one in settings.",
                            style = PawseType.Label,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            if (error != null) {
                Text(
                    error,
                    style = PawseType.Label,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
        BottomAction(
            text = "Start",
            onClick = onContinue,
            note = "Scores need about two weeks of nights to calibrate against you. Until then they say “still settling”.",
        )
    }
}

/** One score: how much of its input was found, as a ring, and what that means in words. */
@Composable
private fun Capability(
    name: String,
    glyph: String,
    coverage: Float,
    state: String,
    color: Color,
    why: String,
    modifier: Modifier = Modifier,
) {
    val percent = (coverage * 100).roundToInt()
    Column(modifier) {
        Row(Modifier.padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ScoreRing(
                fraction = coverage,
                color = color,
                size = 52.dp,
                glow = false,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = "$name: $state, $percent% of its inputs found"
                },
            ) {
                Text("$percent%", style = PawseType.Label.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface)
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Glyph(glyph, size = 14.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant, strokeWidth = 2f)
                    Spacer(Modifier.width(6.dp))
                    Text(name, style = PawseType.Title, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    BandDot(color = color, label = state)
                }
                Text(why, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
    ScoreType.RECOVERY -> Glyphs.RECOVERY
    ScoreType.SLEEP -> Glyphs.SLEEP
    ScoreType.STRAIN -> Glyphs.STRAIN
    ScoreType.ENERGY_BANK -> Glyphs.ENERGY
    ScoreType.LOAD_RATIO -> Glyphs.LOAD
}
