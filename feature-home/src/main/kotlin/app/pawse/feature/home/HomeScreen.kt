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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pawse.core.ui.component.BaselineBandRow
import app.pawse.core.ui.component.Chip
import app.pawse.core.ui.component.ScoreHero
import app.pawse.core.ui.component.ScoreRow
import app.pawse.core.ui.theme.PawseType
import app.pawse.scoring.model.Contribution
import app.pawse.scoring.model.Score
import app.pawse.scoring.model.ScoreType
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Home: one number, one sentence, and the rows that produced it.
 *
 * The explicit failure case for this screen is the twelve-tile dashboard, so the
 * hierarchy is enforced rather than encouraged. There is exactly one hero numeral.
 * Under it sits the plain-language sentence and the coverage line — never the
 * number alone. Under that, the metrics that produced it, each drawn against the
 * range it usually occupies. The other four scores are rows, not tiles, and every
 * row leads to the arithmetic behind it.
 *
 * Missing inputs are listed rather than hidden. A user whose watch stopped writing
 * respiratory rate three weeks ago should be able to see that here, not deduce it
 * from a score that quietly got less accurate.
 */
@Composable
fun HomeScreen(
    onOpenExplain: (ScoreType) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var editingProfile by remember { mutableStateOf(false) }

    Scaffold(modifier = modifier) { padding ->
        when (val current = state) {
            is HomeUiState.Loading -> Loading(Modifier.padding(padding))

            is HomeUiState.Failed -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
            ) {
                Text("Could not build your scores", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Text(
                    current.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = viewModel::refresh) { Text("Try again") }
            }

            is HomeUiState.Ready -> HomeContent(
                snapshot = current.snapshot,
                syncing = current.syncing,
                syncError = error,
                contentPadding = padding,
                onOpenExplain = onOpenExplain,
                onSyncNow = viewModel::syncNow,
                onEditProfile = { editingProfile = true },
                onDismissError = viewModel::dismissError,
            )
        }
    }

    if (editingProfile) {
        val snapshot = (state as? HomeUiState.Ready)?.snapshot
        ProfileDialog(
            initialProfile = snapshot?.profile,
            initialScale = snapshot?.strainScale,
            onDismiss = { editingProfile = false },
            onSave = { age, sex, maxHr, scale ->
                viewModel.saveProfile(age, sex, maxHr)
                scale?.let(viewModel::setStrainScale)
                editingProfile = false
            },
        )
    }
}

@Composable
private fun Loading(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            "Working out your night",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HomeContent(
    snapshot: HomeSnapshot,
    syncing: Boolean,
    syncError: String?,
    contentPadding: PaddingValues,
    onOpenExplain: (ScoreType) -> Unit,
    onSyncNow: () -> Unit,
    onEditProfile: () -> Unit,
    onDismissError: () -> Unit,
) {
    val hero = snapshot.heroType
    val heroScore = snapshot.score(hero)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 40.dp,
        ),
    ) {
        item {
            Header(
                date = snapshot.date,
                syncing = syncing,
                onSyncNow = onSyncNow,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(20.dp))
        }

        item {
            Hero(
                type = hero,
                score = heroScore,
                snapshot = snapshot,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(24.dp))
        }

        if (syncError != null) {
            item {
                Notice(
                    text = syncError,
                    actionLabel = "Dismiss",
                    onAction = onDismissError,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(20.dp))
            }
        }

        if (heroScore != null) {
            val rows = heroScore.contributions.sortedWith(
                compareByDescending<Contribution> { it.present }
                    .thenByDescending { it.nominalWeight },
            )
            val hasBaselines = rows.any { it.baselineMean != null }

            item {
                SectionLabel(
                    text = if (hasBaselines) "Against your usual" else "What it was built from",
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }

            if (!hasBaselines) {
                item {
                    Text(
                        text = ScoreCopy.arithmeticNote(hero),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            items(rows) { contribution ->
                BaselineBandRow(
                    state = contribution.toRowState(),
                    onClick = { onOpenExplain(hero) },
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }

            item {
                TextButton(
                    onClick = { onOpenExplain(hero) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text("How this number was built")
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        item {
            SectionLabel(text = "Everything else", modifier = Modifier.padding(horizontal = 24.dp))
        }

        items(ScoreType.entries.filter { it != hero }) { type ->
            OtherScoreRow(
                type = type,
                snapshot = snapshot,
                onOpenExplain = onOpenExplain,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }

        if (snapshot.needsProfile) {
            item {
                Spacer(Modifier.height(20.dp))
                Notice(
                    text = "Strain needs a maximum heart rate, and the only way to get one is " +
                        "your age or a measured figure. Health Connect has neither, and " +
                        "guessing would mean inventing the number every workout is divided by.",
                    actionLabel = "Add it",
                    onAction = onEditProfile,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }

        item {
            Spacer(Modifier.height(28.dp))
            Footer(
                snapshot = snapshot,
                onEditProfile = onEditProfile,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

@Composable
private fun Header(
    date: LocalDate,
    syncing: Boolean,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        if (syncing) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            TextButton(onClick = onSyncNow) { Text("Refresh") }
        }
    }
}

@Composable
private fun Hero(
    type: ScoreType,
    score: Score?,
    snapshot: HomeSnapshot,
    modifier: Modifier = Modifier,
) {
    if (score == null) {
        val reason = snapshot.unavailable[type]
        ScoreHero(
            label = ScoreCopy.name(type),
            value = "—",
            suffix = null,
            supporting = reason?.let { ScoreCopy.unavailableSentence(type, it) }
                ?: "Nothing to score yet. Scores arrive after your first synced night.",
            coverage = if (snapshot.nightsInWindow > 0) {
                "${snapshot.nightsInWindow} nights of data so far"
            } else {
                null
            },
            modifier = modifier,
        )
        return
    }

    ScoreHero(
        label = ScoreCopy.name(type),
        value = score.value.toString(),
        suffix = if (type == ScoreType.RECOVERY) "%" else null,
        bandWord = ScoreCopy.bandWord(type, score.band),
        bandColor = bandColor(type, score.band),
        supporting = ScoreCopy.sentence(type, score, snapshot),
        coverage = ScoreCopy.coverageLine(score),
        flags = ScoreCopy.flags(score),
        modifier = modifier,
    )
}

@Composable
private fun OtherScoreRow(
    type: ScoreType,
    snapshot: HomeSnapshot,
    onOpenExplain: (ScoreType) -> Unit,
    modifier: Modifier = Modifier,
) {
    val score = snapshot.score(type)
    if (score == null) {
        val reason = snapshot.unavailable[type]
        ScoreRow(
            label = ScoreCopy.name(type),
            value = "—",
            supporting = reason?.let { ScoreCopy.unavailableSentence(type, it) }
                ?: "Not available yet.",
            modifier = modifier,
        )
        return
    }

    // Strain is stored on the canonical 0-100 scale and displayed on whichever the
    // user picked. Load ratio is stored as the ratio times a hundred, because the
    // score table is keyed on integers.
    val value = when (type) {
        ScoreType.STRAIN -> ScoreCopy.formatStrain(
            snapshot.strainDisplay ?: score.value.toDouble(),
            snapshot.strainScale,
        )
        ScoreType.LOAD_RATIO -> String.format(Locale.getDefault(), "%.2f", score.value / 100.0)
        else -> score.value.toString()
    }

    ScoreRow(
        label = ScoreCopy.name(type),
        value = value,
        // Strain's scale label is already part of its formatted value, so it takes
        // no suffix of its own.
        suffix = if (type == ScoreType.LOAD_RATIO) "×" else null,
        bandWord = ScoreCopy.bandWord(type, score.band),
        bandColor = bandColor(type, score.band),
        supporting = ScoreCopy.sentence(type, score, snapshot),
        onClick = { onOpenExplain(type) },
        modifier = modifier,
    )
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = text,
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun Notice(
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onAction, contentPadding = PaddingValues(0.dp)) { Text(actionLabel) }
        }
    }
}

@Composable
private fun Footer(
    snapshot: HomeSnapshot,
    onEditProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Chip(text = syncedAgo(snapshot.lastSyncEpochMs))
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onEditProfile) { Text("You and your scales") }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Pawse interprets what your watch already recorded, against your own " +
                "history. It is not a medical device and nothing here is a diagnosis.",
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun syncedAgo(lastSyncEpochMs: Long): String {
    if (lastSyncEpochMs <= 0L) return "Not synced yet"
    val minutes = Duration.ofMillis(System.currentTimeMillis() - lastSyncEpochMs).toMinutes()
    return when {
        minutes < 1 -> "Synced just now"
        minutes < 60 -> "Synced $minutes min ago"
        minutes < 60 * 24 -> "Synced ${(minutes / 60.0).roundToInt()} h ago"
        else -> "Synced ${minutes / (60 * 24)} days ago"
    }
}
