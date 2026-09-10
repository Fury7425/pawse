package app.pawse.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pawse.core.ui.component.BaselineBand
import app.pawse.core.ui.component.BandDot
import app.pawse.core.ui.component.Chip
import app.pawse.core.ui.theme.PawseTheme
import app.pawse.core.ui.theme.PawseType
import app.pawse.scoring.load.LoadRatioScorer
import app.pawse.scoring.model.Contribution
import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.Score
import app.pawse.scoring.model.ScoreType
import app.pawse.scoring.strain.SessionLoad
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The arithmetic, in full.
 *
 * Everything on this screen was computed once, by the engine, and carried here
 * inside `Contribution`. Nothing is recalculated in the UI — not a weight, not a
 * z-score, not a point total. That is a deliberate constraint: a screen that
 * recomputes is a second implementation of the engine that will eventually
 * disagree with the first, and the number it disagreed with is the one the user
 * was shown.
 *
 * It also means every figure here is traceable. Each term carries the provenance
 * of its coefficient — published, recovered, inferred, or our own default — and
 * the citation that backs it, verbatim from the engine. No invented weight is
 * ever presented as a vendor's formula, because the tag travels with the number.
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

    Scaffold(modifier = modifier) { padding ->
        val score = snapshot?.score(type)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 40.dp,
            ),
        ) {
            item {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onBack) { Text("Back") }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "How ${ScoreCopy.name(type)} was built",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(16.dp))
            }

            if (snapshot == null || score == null) {
                item {
                    Text(
                        text = snapshot?.unavailable?.get(type)
                            ?.let { ScoreCopy.unavailableSentence(type, it) }
                            ?: "There is no score to explain yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
                return@LazyColumn
            }

            item {
                Summary(type = type, score = score, snapshot = snapshot)
                Spacer(Modifier.height(20.dp))
            }

            item {
                Paragraph(ScoreCopy.arithmeticNote(type))
                Spacer(Modifier.height(20.dp))
            }

            if (type == ScoreType.LOAD_RATIO) {
                item {
                    // The critique is not decoration. It is the condition under
                    // which this number is honest, and it ships from the engine so
                    // that a UI refactor cannot drop it.
                    Callout(LoadRatioScorer.CRITIQUE)
                    Spacer(Modifier.height(20.dp))
                }
            }

            items(score.contributions) { contribution ->
                ContributionDetail(contribution)
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }

            if (type == ScoreType.SLEEP) {
                item {
                    Spacer(Modifier.height(20.dp))
                    SleepNeedBreakdown(snapshot)
                }
            }

            if (type == ScoreType.STRAIN) {
                item {
                    Spacer(Modifier.height(20.dp))
                    StrainDetail(snapshot)
                }
            }

            if (type == ScoreType.ENERGY_BANK) {
                item {
                    Spacer(Modifier.height(20.dp))
                    EnergyArithmetic(score)
                }
            }

            item {
                Spacer(Modifier.height(28.dp))
                Provenance(score)
            }
        }
    }
}

@Composable
private fun Summary(type: ScoreType, score: Score, snapshot: HomeSnapshot) {
    Column(Modifier.padding(horizontal = 24.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = when (type) {
                    ScoreType.STRAIN -> ScoreCopy.formatStrain(
                        snapshot.strainDisplay ?: score.value.toDouble(),
                        snapshot.strainScale,
                    )
                    ScoreType.LOAD_RATIO -> String.format(Locale.getDefault(), "%.2f×", score.value / 100.0)
                    else -> score.value.toString()
                },
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            BandDot(
                color = bandColor(type, score.band),
                label = ScoreCopy.bandWord(type, score.band),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = ScoreCopy.coverageLine(score),
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val flags = ScoreCopy.flags(score)
        if (flags.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                flags.forEach { Chip(text = it) }
            }
        }
    }
}

@Composable
private fun ContributionDetail(contribution: Contribution) {
    val semantics = PawseTheme.semantics
    val row = contribution.toRowState()
    val markerColor = when {
        !contribution.present -> semantics.neutral
        (contribution.z ?: 0.0) > ScoreCopy.NEUTRAL_Z -> semantics.better
        (contribution.z ?: 0.0) < -ScoreCopy.NEUTRAL_Z -> semantics.worse
        else -> semantics.neutral
    }

    Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = row.label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = row.valueText,
                style = PawseType.MetricValue,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        if (contribution.z != null) {
            Spacer(Modifier.height(10.dp))
            BaselineBand(
                z = contribution.z?.toFloat(),
                markerColor = markerColor,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(10.dp))
        DetailLine("Against your usual", row.deltaText)
        DetailLine("Baseline", row.baselineText)
        contribution.z?.let {
            DetailLine("z", String.format(Locale.getDefault(), "%+.2f", it))
        }
        DetailLine("Weight", ScoreCopy.weightText(contribution))
        DetailLine(
            label = if (contribution.anomalyFlag) "Flag" else "Effect on the score",
            value = if (!contribution.present) {
                "Not recorded, so its weight was shared out across the rest"
            } else {
                ScoreCopy.pointsText(contribution) ?: "—"
            },
        )

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(text = ScoreCopy.provenanceLabel(contribution.provenance))
        }
        if (contribution.citation.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = contribution.citation,
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(0.38f),
        )
        Text(
            text = value,
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SleepNeedBreakdown(snapshot: HomeSnapshot) {
    val total = snapshot.sleepNeedHours ?: return
    Column(Modifier.padding(horizontal = 24.dp)) {
        Text("Tonight's sleep need", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        snapshot.sleepNeedBaselineHours?.let {
            DetailLine("Your baseline", ScoreCopy.formatHours(it))
        }
        snapshot.sleepNeedStrainAdderHours?.let {
            DetailLine("Added by yesterday's strain", "+ ${ScoreCopy.formatHours(it)}")
        }
        snapshot.sleepNeedDebtAdderHours?.let {
            DetailLine("Added by recent debt", "+ ${ScoreCopy.formatHours(it)}")
        }
        snapshot.sleepNeedNapCreditHours?.let {
            if (it > 0.0) DetailLine("Credited by naps", "− ${ScoreCopy.formatHours(it)}")
        }
        DetailLine("Total", ScoreCopy.formatHours(total))
        Spacer(Modifier.height(10.dp))
        Text(
            text = "The strain term is Whoop's published logistic, " +
                "f(i) = 1.7 / (1 + e^((17 − i) / 3.5)) hours, from patent US 9,538,923. " +
                "Sleep need is never a static eight hours.",
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StrainDetail(snapshot: HomeSnapshot) {
    Column(Modifier.padding(horizontal = 24.dp)) {
        Text("Today's sessions", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))

        if (snapshot.sessionLoads.isEmpty()) {
            Text(
                text = "No workouts logged today. The strain shown is the passive term alone.",
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            snapshot.sessionLoads.forEach { SessionLine(it) }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Sessions do not add up. Load is squashed through a saturating curve, " +
                    "so a second session always adds less than it would have on its own — " +
                    "which is why each line shows what it alone would have scored.",
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        snapshot.strainTargetDisplay?.let { target ->
            Spacer(Modifier.height(16.dp))
            Text("Target strain", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            DetailLine("Target for today", ScoreCopy.formatStrain(target, snapshot.strainScale))
            snapshot.strainTypicalDisplay?.let {
                DetailLine("Your recent typical", ScoreCopy.formatStrain(it, snapshot.strainScale))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "About two weeks of your typical strain, moved by this morning's " +
                    "Recovery. Bevel describes that pairing and publishes neither half of it, " +
                    "so the window is theirs and the coupling strength is ours.",
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SessionLine(session: SessionLoad) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Row {
            Text(
                text = session.title ?: "Workout",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${session.durationMinutes.roundToInt()} min",
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        if (session.scored) {
            DetailLine("Load", "${session.load.roundToInt()} TRIMP")
            DetailLine("On its own", session.strainIfAlone.roundToInt().toString())
            session.reserveFraction?.let {
                DetailLine("Heart-rate reserve", "${(it * 100).roundToInt()}%")
            }
        } else {
            DetailLine("Not scored", session.skippedReason ?: "Not enough data.")
        }
    }
}

@Composable
private fun EnergyArithmetic(score: Score) {
    fun points(metric: Metric) = score.contributions.firstOrNull { it.metric == metric }?.points
    val carryover = points(Metric.CARRYOVER) ?: return
    val recharge = points(Metric.OVERNIGHT_RECHARGE) ?: 0.0
    val drain = points(Metric.STRAIN_DRAIN) ?: 0.0
    val nap = points(Metric.NAP_CREDIT) ?: 0.0

    Column(Modifier.padding(horizontal = 24.dp)) {
        Text("It adds up", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        Text(
            text = "${carryover.roundToInt()} + ${recharge.roundToInt()} − " +
                "${abs(drain).roundToInt()} + ${nap.roundToInt()} = ${score.value}",
            style = PawseType.MetricValue,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "These are realised changes rather than requested ones: the gauge resists " +
                "near the top and bottom of its range, so what a great night asked for and " +
                "what it actually put back are different numbers. The one shown is what landed.",
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Provenance(score: Score) {
    Column(Modifier.padding(horizontal = 24.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))
        DetailLine("Engine", score.scoringVersion)
        DetailLine("Weights", score.configHash)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Both travel with every stored score. Changing a weight writes a new row " +
                "rather than rewriting this one, so a number you were shown last month stays " +
                "the number you were shown last month.",
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Paragraph(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
}

@Composable
private fun Callout(text: String) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(16.dp),
        )
    }
}
