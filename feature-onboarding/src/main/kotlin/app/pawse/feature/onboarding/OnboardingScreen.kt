package app.pawse.feature.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pawse.core.data.health.CapabilityReport
import app.pawse.core.data.health.ScoreCapability
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

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
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
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        CircularProgressIndicator()
        Spacer(Modifier.height(20.dp))
        Text(
            text = when (step) {
                OnboardingStep.SYNCING -> "Reading your last 90 days"
                OnboardingStep.PROBING -> "Working out what we can measure"
                else -> "Checking Health Connect"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Message(title: String, body: String, action: Pair<String, () -> Unit>? = null) {
    Text(title, style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(12.dp))
    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (action != null) {
        Spacer(Modifier.height(24.dp))
        Button(onClick = action.second) { Text(action.first) }
    }
}

@Composable
private fun Explain(onContinue: () -> Unit) {
    Text("What this reads", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(16.dp))
    Text(
        "Pawse reads what your watch or ring already writes to Health Connect: " +
            "overnight heart rate variability, resting heart rate, sleep, breathing rate, " +
            "skin temperature, blood oxygen, workouts and steps. It writes back only the " +
            "food you log.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        "Everything stays on this phone. There is no account and no server. " +
            "Grant only what you want; anything you leave out is dropped from the maths " +
            "rather than guessed at.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(32.dp))
    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Choose what to share") }
}

@Composable
private fun Report(report: CapabilityReport, error: String?, onContinue: () -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        item {
            Text("What we can measure", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "Based on ${report.nightsWithSleep} nights found in the last ${report.probedDays} days.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
        }

        items(report.scores) { capability ->
            ScoreCapabilityRow(capability)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        if (report.contestedTypes.isNotEmpty()) {
            item {
                Spacer(Modifier.height(20.dp))
                Text(
                    "Two apps are writing the same records " +
                        "(${report.contestedTypes.joinToString()}). We will use one source per " +
                        "night rather than double-counting. You can change which one in settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (error != null) {
            item {
                Spacer(Modifier.height(16.dp))
                Text(error, style = MaterialTheme.typography.bodySmall, color = PawseTheme.semantics.worse)
            }
        }

        item {
            Spacer(Modifier.height(28.dp))
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Start") }
            Spacer(Modifier.height(12.dp))
            Text(
                "Scores need about two weeks of nights to calibrate against you. " +
                    "Until then they are marked as still settling.",
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ScoreCapabilityRow(capability: ScoreCapability) {
    val semantics = PawseTheme.semantics
    val dot: Color = when {
        !capability.computable -> semantics.worse
        capability.degraded -> semantics.bandModerate
        else -> semantics.better
    }
    // Never hue alone: the dot is always paired with this word.
    val stateLabel = when {
        !capability.computable -> "Not available"
        capability.degraded -> "Lower confidence"
        else -> "Available"
    }

    Column(Modifier.padding(vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = dot,
                shape = CircleShape,
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .semantics { contentDescription = stateLabel },
            ) {}
            Spacer(Modifier.size(10.dp))
            Text(capability.type.displayName(), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Text(
                text = "$stateLabel · ${(capability.expectedCoverage * 100).roundToInt()}%",
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            capability.explanation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun ScoreType.displayName(): String = when (this) {
    ScoreType.RECOVERY -> "Recovery"
    ScoreType.SLEEP -> "Sleep"
    ScoreType.STRAIN -> "Strain"
    ScoreType.ENERGY_BANK -> "Energy Bank"
    ScoreType.LOAD_RATIO -> "Training load"
}
