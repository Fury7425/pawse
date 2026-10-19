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
import androidx.compose.material3.LinearProgressIndicator
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
import android.content.Intent
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pawse.core.data.sync.SyncFailure
import app.pawse.core.data.sync.SyncStatus
import app.pawse.core.data.sync.SyncStep
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
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
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
                sync = current.sync,
                accessOff = current.accessOff,
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
            initialAppearance = appearance,
            onDismiss = { editingProfile = false },
            onSave = { age, sex, maxHr, scale, chosenAppearance ->
                viewModel.saveProfile(age, sex, maxHr)
                scale?.let(viewModel::setStrainScale)
                viewModel.setAppearance(chosenAppearance)
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
    sync: SyncStatus,
    accessOff: Boolean,
    syncError: String?,
    contentPadding: PaddingValues,
    onOpenExplain: (ScoreType) -> Unit,
    onSyncNow: () -> Unit,
    onEditProfile: () -> Unit,
    onDismissError: () -> Unit,
) {
    val hero = snapshot.heroType
    val heroScore = snapshot.score(hero)
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 120.dp,
        ),
    ) {
        item {
            Header(
                date = snapshot.date,
                sync = sync,
                lastSyncEpochMs = snapshot.lastSyncEpochMs,
                onSyncNow = onSyncNow,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = ScoreCopy.warmLine(
                    hour = java.time.LocalTime.now().hour,
                    recovery = snapshot.score(ScoreType.RECOVERY),
                    recoveryMissing = snapshot.unavailable[ScoreType.RECOVERY],
                    warmingUp = snapshot.warmup != null,
                    strain = snapshot.strainDisplay,
                    strainTarget = snapshot.strainTargetDisplay,
                ),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(20.dp))
        }

        if (accessOff) {
            item {
                Notice(
                    text = "Pawse can't read your data right now. Access to Health Connect was " +
                        "turned off, so nothing new can be scored. Everything already scored is " +
                        "still on this phone, untouched.",
                    actionLabel = "Turn access back on",
                    onAction = {
                        runCatching {
                            context.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
                        }
                    },
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(20.dp))
            }
        }

        snapshot.warmup?.takeIf { heroScore == null || hero != ScoreType.RECOVERY }?.let { warmup ->
            item {
                Notice(
                    text = "Recovery · night ${warmup.nightsCollected + 1} of ${warmup.nightsNeeded}. " +
                        "Ready around ${warmup.readyAround.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))}. " +
                        "Recovery compares each night with your own usual range, and a number built " +
                        "on a few nights would be a guess.",
                    actionLabel = null,
                    onAction = {},
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(20.dp))
            }
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

/**
 * The date and what happened: when Health Connect was last read, or which of the
 * three steps is running now. The scores below never blank while it works.
 */
@Composable
private fun Header(
    date: LocalDate,
    sync: SyncStatus,
    lastSyncEpochMs: Long,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = statusLine(sync, lastSyncEpochMs),
                    style = PawseType.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (sync is SyncStatus.Running) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = onSyncNow) { Text("Refresh") }
            }
        }
        if (sync is SyncStatus.Running) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { (sync.step.ordinal + 1) / SyncStep.entries.size.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun statusLine(sync: SyncStatus, lastSyncEpochMs: Long): String = when (sync) {
    is SyncStatus.Running -> "Reading Health Connect · step ${sync.step.ordinal + 1} of ${SyncStep.entries.size}, " +
        when (sync.step) {
            SyncStep.READ -> "reading"
            SyncStep.COMPARE -> "comparing"
            SyncStep.SCORE -> "scoring"
        }
    is SyncStatus.Failed -> when (sync.reason) {
        SyncFailure.READ_FAILED -> "Health Connect didn't answer at ${ScoreCopy.clock(sync.atEpochMs)}"
        SyncFailure.ACCESS_OFF -> "Access to Health Connect is off"
        SyncFailure.UNAVAILABLE -> "Health Connect isn't available"
    }
    else -> if (lastSyncEpochMs > 0) {
        "Read from Health Connect at ${ScoreCopy.clock(lastSyncEpochMs)}"
    } else {
        "Not read from Health Connect yet"
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
    actionLabel: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (actionLabel != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onAction, contentPadding = PaddingValues(0.dp)) { Text(actionLabel) }
            }
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
