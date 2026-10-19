package app.pawse.feature.home

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pawse.core.data.scoring.ScoreTrend
import app.pawse.core.data.sync.SyncFailure
import app.pawse.core.data.sync.SyncStatus
import app.pawse.core.data.sync.SyncStep
import app.pawse.core.ui.component.BandDot
import app.pawse.core.ui.component.BaselineBandRow
import app.pawse.core.ui.component.Chip
import app.pawse.core.ui.component.GhostButton
import app.pawse.core.ui.component.Glyph
import app.pawse.core.ui.component.GlyphButton
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.PawseCard
import app.pawse.core.ui.component.PrimaryButton
import app.pawse.core.ui.component.ScoreRing
import app.pawse.core.ui.component.SplitBar
import app.pawse.core.ui.component.UsualRangeKey
import app.pawse.core.ui.component.cardSurface
import app.pawse.core.ui.component.pressable
import app.pawse.core.ui.theme.PawseTheme
import app.pawse.core.ui.theme.PawseType
import app.pawse.scoring.config.StrainScale
import app.pawse.scoring.model.Contribution
import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.Score
import app.pawse.scoring.model.ScoreType
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Today: three rings, one target, and the reason behind whichever ring is picked.
 *
 * Sleep, Recovery and Strain sit side by side because they are read together each
 * morning; tapping one swaps the section below to the inputs that produced it, each
 * drawn against the range it usually occupies. Energy Bank and Training load are
 * two cards under that, each with a week of history over its usual band.
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

    Scaffold(modifier = modifier, containerColor = MaterialTheme.colorScheme.background) { padding ->
        when (val current = state) {
            is HomeUiState.Loading -> Loading(Modifier.padding(padding))

            is HomeUiState.Failed -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Could not build your scores", style = PawseType.Title)
                Spacer(Modifier.height(8.dp))
                Text(current.message, style = PawseType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                PrimaryButton("Try again", onClick = viewModel::refresh)
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
        CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurface, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(16.dp))
        Text("Working out your night", style = PawseType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val RINGS = listOf(ScoreType.SLEEP, ScoreType.RECOVERY, ScoreType.STRAIN)

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
    val context = LocalContext.current
    val hour = LocalTime.now().hour
    var focus by rememberSaveable {
        mutableStateOf(
            when {
                hour >= 18 && snapshot.score(ScoreType.STRAIN) != null -> ScoreType.STRAIN
                snapshot.score(ScoreType.RECOVERY) != null -> ScoreType.RECOVERY
                snapshot.score(ScoreType.SLEEP) != null -> ScoreType.SLEEP
                else -> ScoreType.RECOVERY
            },
        )
    }
    val glow = snapshot.score(ScoreType.RECOVERY)?.let { bandColor(ScoreType.RECOVERY, it.band) }
    val pad = Modifier.padding(horizontal = 24.dp)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 140.dp,
        ),
    ) {
        item {
            Column(
                // A faint wash of the morning's band colour behind the rings, drawn
                // past this item's edges and scrolled away with it.
                Modifier.drawBehind {
                    if (glow != null) {
                        val c = Offset(size.width * 0.5f, 250.dp.toPx())
                        drawCircle(
                            Brush.radialGradient(listOf(glow.copy(alpha = 0.10f), Color.Transparent), c, 260.dp.toPx()),
                            radius = 260.dp.toPx(),
                            center = c,
                        )
                    }
                },
            ) {
                Header(
                    date = snapshot.date,
                    sync = sync,
                    lastSyncEpochMs = snapshot.lastSyncEpochMs,
                    onSyncNow = onSyncNow,
                    onEditProfile = onEditProfile,
                    modifier = pad,
                )
                Spacer(Modifier.height(28.dp))
                Text(
                    text = ScoreCopy.warmLine(
                        hour = hour,
                        recovery = snapshot.score(ScoreType.RECOVERY),
                        recoveryMissing = snapshot.unavailable[ScoreType.RECOVERY],
                        warmingUp = snapshot.warmup != null,
                        strain = snapshot.strainDisplay,
                        strainTarget = snapshot.strainTargetDisplay,
                    ),
                    style = PawseType.Title,
                    modifier = pad,
                )

                Spacer(Modifier.height(20.dp))
                Rings(snapshot = snapshot, focus = focus, onFocus = { focus = it })
            }
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
                    modifier = pad.padding(top = 16.dp),
                )
            }
        }

        snapshot.warmup?.takeIf { snapshot.score(ScoreType.RECOVERY) == null }?.let { warmup ->
            item {
                Notice(
                    text = "Recovery · night ${warmup.nightsCollected + 1} of ${warmup.nightsNeeded}. " +
                        "Ready around ${warmup.readyAround.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))}. " +
                        "Recovery compares each night with your own usual range, and a number built " +
                        "on a few nights would be a guess.",
                    modifier = pad.padding(top = 16.dp),
                )
            }
        }

        if (syncError != null) {
            item {
                Notice(text = syncError, actionLabel = "Dismiss", onAction = onDismissError, modifier = pad.padding(top = 16.dp))
            }
        }

        snapshot.strainTargetDisplay?.let { target ->
            item {
                TargetCard(
                    target = target,
                    soFar = snapshot.strainDisplay ?: 0.0,
                    typical = snapshot.strainTypicalDisplay,
                    scale = snapshot.strainScale,
                    onClick = { onOpenExplain(ScoreType.STRAIN) },
                    modifier = pad.padding(top = 16.dp),
                )
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
        focusSection(focus, snapshot, onOpenExplain, pad)

        item {
            Spacer(Modifier.height(32.dp))
            Text("Also today", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = pad)
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = pad.height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                listOf(ScoreType.ENERGY_BANK, ScoreType.LOAD_RATIO).forEach { type ->
                    AlsoCard(
                        type = type,
                        snapshot = snapshot,
                        onClick = { onOpenExplain(type) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
        }

        if (snapshot.needsProfile) {
            item {
                Notice(
                    text = "Strain needs a maximum heart rate, and the only way to get one is " +
                        "your age or a measured figure. Health Connect has neither, and " +
                        "guessing would mean inventing the number every workout is divided by.",
                    actionLabel = "Add it",
                    onAction = onEditProfile,
                    modifier = pad.padding(top = 20.dp),
                )
            }
        }

        item {
            Column(pad.padding(top = 24.dp)) {
                GhostButton("You and your scales", onClick = onEditProfile)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Pawse reads what your watch already recorded, against your own " +
                        "history. It is not a medical device and nothing here is a diagnosis.",
                    style = PawseType.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
    onEditProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())),
                    style = PawseType.Heading,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Glyph(
                        if (sync is SyncStatus.Failed) Glyphs.Info else Glyphs.Check,
                        size = 14.dp,
                        strokeWidth = 2f,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = statusLine(sync, lastSyncEpochMs),
                        style = PawseType.Label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (sync is SyncStatus.Running) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                GlyphButton(Glyphs.Refresh, "Refresh", onClick = onSyncNow)
            }
            GlyphButton(Glyphs.Person, "You and your scales", onClick = onEditProfile)
        }
        if (sync is SyncStatus.Running) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SyncStep.entries.forEach { step ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(3.dp)
                            .background(
                                when {
                                    step.ordinal < sync.step.ordinal -> MaterialTheme.colorScheme.onSurface
                                    step == sync.step -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    else -> PawseTheme.semantics.baselineOuter
                                },
                                RoundedCornerShape(2.dp),
                            ),
                    )
                }
            }
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

/** Sleep, Recovery and Strain as rings, Recovery a size up; tapping one picks the section below. */
@Composable
private fun Rings(snapshot: HomeSnapshot, focus: ScoreType, onFocus: (ScoreType) -> Unit) {
    val weights = listOf(1f, 1.3f, 1f)
    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        RINGS.forEachIndexed { i, type ->
            RingTile(
                type = type,
                snapshot = snapshot,
                selected = focus == type,
                onClick = { onFocus(type) },
                modifier = Modifier.weight(weights[i]),
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("Last night", "This morning", "Today so far").forEachIndexed { i, caption ->
            Text(
                caption,
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(weights[i]),
            )
        }
    }
}

@Composable
private fun RingTile(
    type: ScoreType,
    snapshot: HomeSnapshot,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val score = snapshot.score(type)
    val big = type == ScoreType.RECOVERY
    val strain = type == ScoreType.STRAIN
    val scaleMax = if (snapshot.strainScale == StrainScale.WHOOP_21) 21.0 else 100.0
    val value = when {
        score == null -> "—"
        strain -> strainNumber(snapshot.strainDisplay ?: score.value.toDouble(), snapshot.strainScale)
        else -> score.value.toString()
    }
    val unit = if (type == ScoreType.RECOVERY && score != null) "%" else ""
    val word = when {
        score != null -> ScoreCopy.bandWord(type, score.band)
        snapshot.warmup != null -> "Settling"
        else -> "Not scored"
    }
    val dot = score?.let { bandColor(type, it.band) } ?: PawseTheme.semantics.neutral
    val arc = if (strain) MaterialTheme.colorScheme.onSurfaceVariant else dot
    val description = "${ScoreCopy.name(type)} $value$unit, $word" + if (selected) ", shown below" else ""

    Column(
        modifier = modifier
            .pressable(onClick = onClick)
            .then(if (selected) Modifier.cardSurface() else Modifier)
            .padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ScoreRing(
            fraction = score?.let { it.value / 100f },
            color = arc,
            size = if (big) 112.dp else 84.dp,
            glow = !strain,
            target = if (strain) snapshot.strainTargetDisplay?.let { (it / scaleMax).toFloat() } else null,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value, style = if (big) PawseType.Numeral else PawseType.RingSmall)
                if (unit.isNotEmpty()) {
                    Text(
                        unit,
                        style = PawseType.Label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(scoreGlyph(type), size = 14.dp, strokeWidth = 2f, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(ScoreCopy.name(type), style = PawseType.BodyStrong)
        }
        BandDot(color = dot, label = word)
    }
}

/** Today's strain against the target this morning's Recovery set. */
@Composable
private fun TargetCard(
    target: Double,
    soFar: Double,
    typical: Double?,
    scale: StrainScale,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scaleMax = if (scale == StrainScale.WHOOP_21) 21.0 else 100.0
    val track = PawseTheme.semantics.baselineOuter
    val fill = MaterialTheme.colorScheme.onSurfaceVariant
    val marker = MaterialTheme.colorScheme.onSurface
    PawseCard(modifier = modifier, onClick = onClick, padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Today’s target", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("Strain around ${strainNumber(target, scale)}", style = PawseType.BodyStrong)
            Spacer(Modifier.width(8.dp))
            Glyph(Glyphs.ChevronRight, size = 16.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(14.dp),
        ) {
            val bar = 6.dp.toPx()
            val top = (size.height - bar) / 2f
            val r = CornerRadius(bar / 2f)
            drawRoundRect(track, Offset(0f, top), Size(size.width, bar), r)
            val done = (soFar / scaleMax).coerceIn(0.0, 1.0).toFloat() * size.width
            if (done > 0f) drawRoundRect(fill, Offset(0f, top), Size(done, bar), r)
            val x = (target / scaleMax).coerceIn(0.0, 1.0).toFloat() * size.width
            drawRoundRect(marker, Offset(x - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height), CornerRadius(1.dp.toPx()))
        }
        Spacer(Modifier.height(4.dp))
        Row {
            Text("${strainNumber(soFar, scale)} so far", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            typical?.let {
                Text("Your recent usual is ${strainNumber(it, scale)}", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The section under the rings: why the picked score came out as it did. */
private fun LazyListScope.focusSection(
    type: ScoreType,
    snapshot: HomeSnapshot,
    onOpenExplain: (ScoreType) -> Unit,
    pad: Modifier,
) {
    val score = snapshot.score(type)
    item(key = "focus-head-$type") {
        Column(pad) {
            if (score == null) {
                Text("No ${ScoreCopy.name(type)} yet", style = PawseType.Heading)
                Spacer(Modifier.height(4.dp))
                Text(
                    snapshot.unavailable[type]?.let { ScoreCopy.unavailableSentence(type, it) }
                        ?: "Nothing to score yet. Scores arrive after your first synced night.",
                    style = PawseType.Body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    when (type) {
                        ScoreType.STRAIN -> "Strain so far: ${strainNumber(snapshot.strainDisplay ?: score.value.toDouble(), snapshot.strainScale)}"
                        else -> "Why ${ScoreCopy.name(type)} is ${score.value}"
                    },
                    style = PawseType.Heading,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (type == ScoreType.STRAIN) {
                        if (snapshot.strainScale == StrainScale.WHOOP_21) "On the 21 scale" else "On the 100 scale"
                    } else {
                        inputsLine(score)
                    },
                    style = PawseType.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                ScoreCopy.sentence(type, score, snapshot) +
                    if (type == ScoreType.STRAIN) " Strain is load, not a grade, so it stays grey." else "",
                style = PawseType.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val flags = ScoreCopy.flags(score)
            if (flags.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { flags.forEach { Chip(it) } }
            }
            if (type == ScoreType.SLEEP) SleepNeedCard(snapshot)
            Spacer(Modifier.height(16.dp))
            val hasBaselines = score.contributions.any { it.baselineMean != null }
            Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        type == ScoreType.STRAIN -> "Where it came from"
                        type == ScoreType.SLEEP -> "Against tonight’s target"
                        hasBaselines -> "Against your usual"
                        else -> "What it was built from"
                    },
                    style = PawseType.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (hasBaselines && type != ScoreType.SLEEP) UsualRangeKey()
            }
        }
    }

    if (score != null) {
        val rows = score.contributions.sortedWith(
            compareByDescending<Contribution> { it.present }.thenByDescending { it.nominalWeight },
        )
        items(rows, key = { "focus-$type-${it.metric}" }) { contribution ->
            Column(pad) {
                when {
                    type == ScoreType.STRAIN -> StrainPart(contribution, snapshot, onClick = { onOpenExplain(type) })
                    type == ScoreType.SLEEP && contribution.present && contribution.weight > 0.0 ->
                        PartRow(contribution, onClick = { onOpenExplain(type) })
                    else -> BaselineBandRow(state = contribution.toRowState(), onClick = { onOpenExplain(type) })
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        item(key = "focus-foot-$type") {
            Column(pad) {
                if (type == ScoreType.STRAIN) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Sessions don’t add up: load runs through a saturating curve, so a second hard hour adds less than the first.",
                        style = PawseType.Label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))
                ExplainLink("How ${ScoreCopy.name(type)} was built", onClick = { onOpenExplain(type) })
            }
        }
    }
}

/** Sleep's need, built up from its parts, above the sleep rows. */
@Composable
private fun SleepNeedCard(snapshot: HomeSnapshot) {
    val total = snapshot.sleepNeedHours ?: return
    val base = snapshot.sleepNeedBaselineHours ?: 0.0
    val strain = snapshot.sleepNeedStrainAdderHours ?: 0.0
    val debt = snapshot.sleepNeedDebtAdderHours ?: 0.0
    Spacer(Modifier.height(16.dp))
    PawseCard(padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row {
            Text("Tonight’s need", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(ScoreCopy.formatHours(total), style = PawseType.Label.copy(fontWeight = PawseType.BodyStrong.fontWeight))
        }
        Spacer(Modifier.height(10.dp))
        SplitBar(
            listOf(
                base.toFloat() to MaterialTheme.colorScheme.outline,
                strain.toFloat() to MaterialTheme.colorScheme.onSurfaceVariant,
                debt.toFloat() to MaterialTheme.colorScheme.onSurface,
            ),
            height = 8.dp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            listOfNotNull(
                "Baseline ${ScoreCopy.formatHours(base)}",
                strain.takeIf { it > 0.0 }?.let { "+ ${minutes(it)} from yesterday’s strain" },
                debt.takeIf { it > 0.0 }?.let { "+ ${minutes(it)} recent debt" },
            ).joinToString("   "),
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A sleep part, scored against a target rather than a history: its points, and the
 * part score they came from. Sleep is a weighted sum, so part = points / weight.
 */
@Composable
private fun PartRow(contribution: Contribution, onClick: () -> Unit) {
    val part = (contribution.points / contribution.weight).coerceIn(0.0, 100.0).roundToInt()
    val track = PawseTheme.semantics.baselineOuter
    val fill = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(ScoreCopy.metricLabel(contribution.metric), style = PawseType.Body, modifier = Modifier.weight(1f))
            Text(contribution.raw?.let { ScoreCopy.formatRaw(contribution.metric, it) } ?: "—", style = PawseType.MetricValue)
            Text(
                ScoreCopy.pointsText(contribution)?.removeSuffix(" pts") ?: "",
                style = PawseType.Delta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(min = 52.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(6.dp),
        ) {
            val r = CornerRadius(size.height / 2f)
            drawRoundRect(track, cornerRadius = r)
            drawRoundRect(fill, size = Size(size.width * part / 100f, size.height), cornerRadius = r)
        }
        Spacer(Modifier.height(6.dp))
        Row {
            Text("Part score $part / 100", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("Weight ${(contribution.weight * 100).roundToInt()}%", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A strain part: its load, its points, and what the workouts were. */
@Composable
private fun StrainPart(contribution: Contribution, snapshot: HomeSnapshot, onClick: () -> Unit) {
    val note = when (contribution.metric) {
        Metric.WORKOUT_LOAD -> snapshot.sessionLoads.takeIf { it.isNotEmpty() }?.joinToString(". ") { s ->
            "${s.title ?: "Workout"}, ${s.durationMinutes.roundToInt()} min" +
                if (s.scored) ". On its own it would have scored ${s.strainIfAlone.roundToInt()}" else ""
        }?.plus(".") ?: "No workouts logged yet today."
        Metric.PASSIVE_LOAD -> "Load from waking hours outside workouts."
        else -> null
    }
    Column(
        Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(ScoreCopy.metricLabel(contribution.metric), style = PawseType.Body, modifier = Modifier.weight(1f))
            Text(contribution.raw?.let { ScoreCopy.formatRaw(contribution.metric, it) } ?: "—", style = PawseType.MetricValue)
            Text(
                ScoreCopy.pointsText(contribution)?.removeSuffix(" pts") ?: "",
                style = PawseType.Delta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(min = 52.dp),
            )
        }
        note?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ExplainLink(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .pressable(onClick = onClick)
            .cardSurface()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = PawseType.BodyStrong, modifier = Modifier.weight(1f))
        Glyph(Glyphs.ChevronRight, size = 18.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Energy Bank or Training load: the number, a week of it over its usual band, one line. */
@Composable
private fun AlsoCard(type: ScoreType, snapshot: HomeSnapshot, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val score = snapshot.score(type)
    val load = type == ScoreType.LOAD_RATIO
    PawseCard(modifier = modifier.heightIn(min = 156.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(scoreGlyph(type), size = 14.dp, strokeWidth = 2f, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(ScoreCopy.name(type), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                when {
                    score == null -> "—"
                    load -> String.format(Locale.getDefault(), "%.2f", score.value / 100.0)
                    else -> score.value.toString()
                },
                style = PawseType.NumeralSmall,
            )
            if (load && score != null) {
                Text(
                    "×",
                    style = PawseType.Body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
                )
            }
        }
        if (score != null) {
            BandDot(color = bandColor(type, score.band), label = ScoreCopy.bandWord(type, score.band))
        }
        Spacer(Modifier.weight(1f))
        snapshot.trends[type]?.let { trend ->
            Spacer(Modifier.height(12.dp))
            Sparkline(trend, Modifier.fillMaxWidth().height(36.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            score?.let { ScoreCopy.sentence(type, it, snapshot) }
                ?: snapshot.unavailable[type]?.let { ScoreCopy.unavailableSentence(type, it) }
                ?: "Not available yet.",
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Seven days as a line over the shaded usual range; today is the dot. */
@Composable
private fun Sparkline(trend: ScoreTrend, modifier: Modifier = Modifier) {
    val band = PawseTheme.semantics.baselineBand
    val line = MaterialTheme.colorScheme.onSurfaceVariant
    val dot = MaterialTheme.colorScheme.onSurface
    val values = trend.values
    val mean = trend.usualMean
    val sd = trend.usualSd
    val known = values.filterNotNull().map { it.toDouble() }
    if (known.isEmpty()) return
    val lo = (known + listOfNotNull(mean?.minus(sd ?: 0.0))).min()
    val hi = (known + listOfNotNull(mean?.plus(sd ?: 0.0))).max()
    val span = (hi - lo).takeIf { it > 0.0 } ?: 1.0
    Canvas(modifier) {
        val pad = 4.dp.toPx()
        fun y(v: Double) = ((size.height - pad) - (v - lo) / span * (size.height - 2 * pad)).toFloat()
        fun x(i: Int) = i * size.width / (values.size - 1).coerceAtLeast(1)
        if (mean != null && sd != null) {
            val top = y(mean + sd)
            drawRoundRect(band, Offset(0f, top), Size(size.width, y(mean - sd) - top), CornerRadius(3.dp.toPx()))
        }
        val path = Path()
        var started = false
        values.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            if (!started) path.moveTo(x(i), y(v.toDouble())) else path.lineTo(x(i), y(v.toDouble()))
            started = true
        }
        drawPath(path, line, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val last = values.indexOfLast { it != null }
        drawCircle(dot, 3.5.dp.toPx(), Offset(x(last), y(values[last]!!.toDouble())))
    }
}

@Composable
private fun Notice(
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    PawseCard(modifier = modifier, padding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = if (actionLabel != null) 2.dp else 14.dp)) {
        Row {
            Glyph(Glyphs.Info, size = 18.dp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 1.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(text, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurface)
                if (actionLabel != null) GhostButton(actionLabel, onClick = onAction)
            }
        }
    }
}

internal fun scoreGlyph(type: ScoreType): String = when (type) {
    ScoreType.SLEEP -> Glyphs.Sleep
    ScoreType.RECOVERY -> Glyphs.Recovery
    ScoreType.STRAIN -> Glyphs.Strain
    ScoreType.ENERGY_BANK -> Glyphs.Energy
    ScoreType.LOAD_RATIO -> Glyphs.Load
}

/** Strain as a bare number on the user's scale: "41", or "8.6" out of 21. */
internal fun strainNumber(value: Double, scale: StrainScale): String =
    if (scale == StrainScale.WHOOP_21) String.format(Locale.getDefault(), "%.1f", value) else value.roundToInt().toString()

internal fun inputsLine(score: Score): String {
    val present = score.contributions.count { it.present && !it.anomalyFlag }
    val total = score.contributions.count { !it.anomalyFlag }
    return "$present of $total inputs"
}

private fun minutes(hours: Double): String = "${(hours * 60).roundToInt()} min"
