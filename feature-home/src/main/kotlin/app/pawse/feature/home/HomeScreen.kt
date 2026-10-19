package app.pawse.feature.home

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pawse.core.data.scoring.Warmup
import app.pawse.core.data.sync.SyncFailure
import app.pawse.core.data.sync.SyncStatus
import app.pawse.core.data.sync.SyncStep
import app.pawse.core.ui.component.BandDot
import app.pawse.core.ui.component.BaselineBandRow
import app.pawse.core.ui.component.Chip
import app.pawse.core.ui.component.FillBar
import app.pawse.core.ui.component.Glyph
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.IconAction
import app.pawse.core.ui.component.PawseCard
import app.pawse.core.ui.component.Pictogram
import app.pawse.core.ui.component.PrimaryButton
import app.pawse.core.ui.component.QuietButton
import app.pawse.core.ui.component.ScoreRing
import app.pawse.core.ui.component.SecondaryButton
import app.pawse.core.ui.component.SectionLabel
import app.pawse.core.ui.component.Sparkline
import app.pawse.core.ui.component.StatePictogram
import app.pawse.core.ui.component.UsualRangeKey
import app.pawse.core.ui.component.press
import app.pawse.core.ui.component.rememberReducedMotion
import app.pawse.core.ui.component.reveal
import app.pawse.core.ui.theme.PawseTheme
import app.pawse.core.ui.theme.PawseType
import app.pawse.scoring.config.StrainScale
import app.pawse.scoring.model.Band
import app.pawse.scoring.model.Contribution
import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.Score
import app.pawse.scoring.model.ScoreType
import app.pawse.scoring.model.ScoreUnavailable
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Today: three rings, one of them in focus, and the rows that produced it.
 *
 * Sleep, Recovery and Strain open the day left to right, last night to today so
 * far. The one tapped explains itself underneath — "Why Recovery is 71" and the
 * metrics drawn against their usual range — and nothing else competes with it.
 * The other two scores sit lower down as small cards with a week of context.
 *
 * Every state is a screen of its own: couldn't read, no sleep, access off, still
 * learning. Each says what happened, whether anything was lost, and what next.
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
    var editingProfile by rememberSaveable { mutableStateOf(false) }
    var showPast by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val openHealthConnect: () -> Unit = {
        runCatching { context.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when (val current = state) {
            is HomeUiState.Loading -> Loading()
            is HomeUiState.Failed -> CouldNotBuild(current.message, onRetry = viewModel::refresh)
            is HomeUiState.Ready -> if (current.accessOff && !showPast) {
                AccessOff(onTurnOn = openHealthConnect, onLookAtPast = { showPast = true })
            } else {
                Today(
                    snapshot = current.snapshot,
                    sync = current.sync,
                    accessOff = current.accessOff,
                    syncError = error,
                    onOpenExplain = onOpenExplain,
                    onSyncNow = viewModel::syncNow,
                    onEditProfile = { editingProfile = true },
                    onDismissError = viewModel::dismissError,
                    onOpenHealthConnect = openHealthConnect,
                )
            }
        }
    }

    if (editingProfile) {
        val snapshot = (state as? HomeUiState.Ready)?.snapshot
        ProfileSheet(
            initialProfile = snapshot?.profile,
            initialScale = snapshot?.strainScale,
            initialAppearance = appearance,
            onDismiss = { editingProfile = false },
            onSave = { age, sex, maxHr, scale, chosenAppearance ->
                viewModel.saveProfile(age, sex, maxHr)
                viewModel.setStrainScale(scale)
                viewModel.setAppearance(chosenAppearance)
                editingProfile = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Today(
    snapshot: HomeSnapshot,
    sync: SyncStatus,
    accessOff: Boolean,
    syncError: String?,
    onOpenExplain: (ScoreType) -> Unit,
    onSyncNow: () -> Unit,
    onEditProfile: () -> Unit,
    onDismissError: () -> Unit,
    onOpenHealthConnect: () -> Unit,
) {
    val reduced = rememberReducedMotion()
    val reveal = rememberFirstOpenToday() && !reduced
    val hour = remember { LocalTime.now().hour }
    val today = remember { LocalDate.now() }
    val recovery = snapshot.score(ScoreType.RECOVERY)
    val recoveryMissing = snapshot.unavailable[ScoreType.RECOVERY]
    val stale = syncError != null && snapshot.date.isBefore(today)
    val noSleep = !stale && recovery == null && recoveryMissing?.reason == ScoreUnavailable.Reason.NO_SLEEP_SESSION
    val learning = !stale && !noSleep && recovery == null && snapshot.warmup != null
    var focus by rememberSaveable { mutableStateOf(defaultFocus(snapshot, hour)) }
    var focusChanged by remember { mutableStateOf(false) }
    val warm = ScoreCopy.warmLine(
        hour = hour,
        recovery = recovery,
        recoveryMissing = recoveryMissing,
        warmingUp = snapshot.warmup != null,
        strain = snapshot.strainDisplay,
        strainTarget = snapshot.strainTargetDisplay,
    )
    val refreshState = rememberPullToRefreshState()
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize()) {
        // The one soft light on the screen, in Recovery's band colour. Chrome only.
        if (recovery != null && !stale) {
            val glow = bandColor(ScoreType.RECOVERY, recovery.band)
            Box(
                Modifier
                    .offset(x = (-85).dp, y = topInset + 52.dp)
                    .size(width = 560.dp, height = 420.dp)
                    .reveal(reveal)
                    .background(Brush.radialGradient(listOf(glow.copy(alpha = 0.10f), Color.Transparent))),
            )
        }

        PullToRefreshBox(
            isRefreshing = sync is SyncStatus.Running,
            onRefresh = onSyncNow,
            state = refreshState,
            modifier = Modifier.fillMaxSize(),
            indicator = {
                PullMark(
                    pull = refreshState.distanceFraction,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = topInset + 4.dp),
                )
            },
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 24.dp,
                    end = 24.dp,
                    top = topInset + 12.dp,
                    bottom = bottomInset + 140.dp,
                ),
            ) {
                item(key = "header") {
                    Header(
                        date = snapshot.date,
                        sync = sync,
                        lastSyncEpochMs = snapshot.lastSyncEpochMs,
                        onRefresh = onSyncNow,
                        onProfile = onEditProfile,
                    )
                }

                if (accessOff) {
                    item(key = "access") { AccessNotice(onTurnOn = onOpenHealthConnect) }
                }

                when {
                    stale -> item(key = "stale") {
                        FailedReadCard(
                            stale = true,
                            message = syncError.orEmpty(),
                            onRetry = onSyncNow,
                            onSecondary = onOpenHealthConnect,
                        )
                        StaleScore(snapshot = snapshot, today = today, onOpenExplain = onOpenExplain)
                    }

                    noSleep -> item(key = "no-sleep") {
                        NoSleep(
                            snapshot = snapshot,
                            warm = warm,
                            onAddNight = onOpenHealthConnect,
                            onOpenExplain = onOpenExplain,
                        )
                    }

                    learning -> item(key = "learning") {
                        Learning(
                            snapshot = snapshot,
                            warmup = snapshot.warmup!!,
                            warm = warm,
                            onOpenExplain = onOpenExplain,
                        )
                    }

                    else -> {
                        if (syncError != null) {
                            item(key = "error") {
                                FailedReadCard(
                                    stale = false,
                                    message = syncError,
                                    onRetry = onSyncNow,
                                    onSecondary = onDismissError,
                                )
                            }
                        }
                        item(key = "warm") {
                            Text(
                                text = warm,
                                style = PawseType.Headline,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .padding(top = 28.dp)
                                    .reveal(reveal),
                            )
                        }
                        item(key = "rings") {
                            Rings(
                                snapshot = snapshot,
                                focus = focus,
                                onFocus = {
                                    focusChanged = focusChanged || it != focus
                                    focus = it
                                },
                                reveal = reveal,
                            )
                        }
                        if (snapshot.strainTargetDisplay != null) {
                            item(key = "target") {
                                TargetCard(
                                    snapshot = snapshot,
                                    onClick = { onOpenExplain(ScoreType.STRAIN) },
                                    modifier = Modifier
                                        .padding(top = 16.dp)
                                        .reveal(reveal, 380),
                                )
                            }
                        }
                        item(key = "focus") {
                            Focus(
                                type = focus,
                                snapshot = snapshot,
                                animate = focusChanged && !reduced,
                                onOpenExplain = onOpenExplain,
                                modifier = Modifier.reveal(reveal, 460),
                            )
                        }
                        item(key = "also") {
                            AlsoToday(snapshot = snapshot, onOpenExplain = onOpenExplain)
                        }
                    }
                }

                if (snapshot.needsProfile) {
                    item(key = "profile") {
                        PawseCard(Modifier.padding(top = 24.dp)) {
                            Text(
                                text = "Strain needs a maximum heart rate, and the only way to get one is " +
                                    "your age or a measured figure. Health Connect has neither, and " +
                                    "guessing would mean inventing the number every workout is divided by.",
                                style = PawseType.Body,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            QuietButton("Add it", onEditProfile, Modifier.offset(x = (-12).dp))
                        }
                    }
                }

                item(key = "footer") {
                    Column(Modifier.padding(top = 24.dp)) {
                        QuietButton("You and your scales", onEditProfile, Modifier.offset(x = (-12).dp))
                        Text(
                            text = "Pawse reads what your watch already recorded, against your own " +
                                "history. It is not a medical device and nothing here is a diagnosis.",
                            style = PawseType.Label,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Recovery leads in the morning, Strain in the evening, whichever exists. */
private fun defaultFocus(snapshot: HomeSnapshot, hour: Int): ScoreType = when {
    hour >= 18 && snapshot.score(ScoreType.STRAIN) != null -> ScoreType.STRAIN
    snapshot.score(ScoreType.RECOVERY) != null -> ScoreType.RECOVERY
    snapshot.score(ScoreType.SLEEP) != null -> ScoreType.SLEEP
    else -> ScoreType.RECOVERY
}

/**
 * True for the first look at Today on a given day, and false on every later
 * visit that day. That first look is the moment people come back on their own,
 * so it alone gets the quiet reveal.
 */
@Composable
private fun rememberFirstOpenToday(): Boolean {
    val context = LocalContext.current
    return remember {
        val prefs = context.getSharedPreferences("pawse_reveal", Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        (prefs.getString("last", null) != today).also { first ->
            if (first) prefs.edit().putString("last", today).apply()
        }
    }
}

// --- Header ---------------------------------------------------------------------

/**
 * The date and what happened: when Health Connect was last read, or which of the
 * three steps is running now. The scores below never blank while it works.
 */
@Composable
private fun Header(
    date: LocalDate,
    sync: SyncStatus,
    lastSyncEpochMs: Long,
    onRefresh: () -> Unit,
    onProfile: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())),
                    style = PawseType.Title,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    statusGlyph(sync, lastSyncEpochMs)?.let {
                        Glyph(it, size = 14.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant, strokeWidth = 2f)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = statusLine(sync, lastSyncEpochMs),
                        style = PawseType.Label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconAction(Glyphs.REFRESH, "Refresh", onRefresh)
            IconAction(Glyphs.PERSON, "You and your scales", onProfile)
        }
        if (sync is SyncStatus.Running) {
            Spacer(Modifier.height(10.dp))
            SyncSteps(sync.step)
        }
    }
}

private fun statusGlyph(sync: SyncStatus, lastSyncEpochMs: Long): String? = when (sync) {
    is SyncStatus.Running -> null
    is SyncStatus.Failed -> Glyphs.ALERT
    else -> if (lastSyncEpochMs > 0) Glyphs.CHECK else Glyphs.CLOCK
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

/** Read, compare, score: done steps filled, the running one moving, the rest empty. */
@Composable
private fun SyncSteps(step: SyncStep) {
    val on = MaterialTheme.colorScheme.onSurface
    val track = PawseTheme.semantics.baselineOuter
    val still = rememberReducedMotion()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        SyncStep.entries.forEach { s ->
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (s.ordinal < step.ordinal) on else track),
            ) {
                if (s == step) {
                    val x = if (still) {
                        0f
                    } else {
                        val moving by rememberInfiniteTransition(label = "step").animateFloat(
                            initialValue = -0.4f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
                            label = "slide",
                        )
                        moving
                    }
                    Box(
                        Modifier
                            .offset(x = maxWidth * x)
                            .width(maxWidth * 0.4f)
                            .fillMaxHeight()
                            .background(on, RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
    }
}

/** The band and its dot, sliding along as the screen is pulled. */
@Composable
private fun PullMark(pull: Float, modifier: Modifier = Modifier) {
    if (pull <= 0f) return
    val line = MaterialTheme.colorScheme.outline
    val dot = MaterialTheme.colorScheme.onSurface
    Canvas(
        modifier
            .size(width = 56.dp, height = 16.dp)
            .graphicsLayer { alpha = pull.coerceIn(0f, 1f) },
    ) {
        drawRoundRect(
            color = line,
            topLeft = Offset(0f, 7.dp.toPx()),
            size = Size(size.width, 3.dp.toPx()),
            cornerRadius = CornerRadius(1.5.dp.toPx()),
        )
        val r = 5.dp.toPx()
        drawCircle(dot, radius = r, center = Offset(r + (size.width - 2 * r) * pull.coerceIn(0f, 1f), 8.5.dp.toPx()))
    }
}

// --- Rings -----------------------------------------------------------------------

private val RingOrder = listOf(ScoreType.SLEEP, ScoreType.RECOVERY, ScoreType.STRAIN)

@Composable
private fun Rings(
    snapshot: HomeSnapshot,
    focus: ScoreType,
    onFocus: (ScoreType) -> Unit,
    reveal: Boolean,
) {
    Column(Modifier.padding(top = 20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            RingOrder.forEachIndexed { index, type ->
                RingTile(
                    type = type,
                    snapshot = snapshot,
                    selected = focus == type,
                    onClick = { onFocus(type) },
                    reveal = reveal,
                    delayMillis = 120 + index * 80,
                    modifier = Modifier.weight(if (type == ScoreType.RECOVERY) 1.3f else 1f),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .reveal(reveal, 300),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf("Last night", "This morning", "Today so far").forEachIndexed { index, caption ->
                Text(
                    text = caption,
                    style = PawseType.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(if (index == 1) 1.3f else 1f),
                )
            }
        }
    }
}

@Composable
private fun RingTile(
    type: ScoreType,
    snapshot: HomeSnapshot,
    selected: Boolean,
    onClick: () -> Unit,
    reveal: Boolean,
    delayMillis: Int,
    modifier: Modifier = Modifier,
) {
    val score = snapshot.score(type)
    val big = type == ScoreType.RECOVERY
    val strain = type == ScoreType.STRAIN
    val ceiling = strainCeiling(snapshot.strainScale)
    val (value, unit) = score?.let { displayValue(type, it, snapshot) } ?: ("—" to null)
    val fraction = when {
        score == null -> null
        strain -> ((snapshot.strainDisplay ?: score.value.toDouble()) / ceiling).toFloat()
        else -> score.value / 100f
    }
    val word = score?.let { ScoreCopy.bandWord(type, it.band) } ?: "Not scored"
    val dot = score?.let { bandColor(type, it.band) } ?: PawseTheme.semantics.neutral
    val arc = if (strain) MaterialTheme.colorScheme.onSurfaceVariant else dot
    val shape = RoundedCornerShape(16.dp)
    val described = "${ScoreCopy.name(type)} $value${unit.orEmpty()}, $word"

    Column(
        modifier = modifier
            .reveal(reveal, delayMillis)
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent, shape)
            .border(1.dp, if (selected) MaterialTheme.colorScheme.outlineVariant else Color.Transparent, shape)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .clearAndSetSemantics { contentDescription = described }
            .padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ScoreRing(
            fraction = fraction,
            color = arc,
            size = if (big) 112.dp else 84.dp,
            glow = !strain && score != null,
            targetFraction = if (strain) snapshot.strainTargetDisplay?.let { (it / ceiling).toFloat() } else null,
            drawIn = reveal,
            delayMillis = delayMillis,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = value,
                    style = PawseType.Display.copy(
                        fontSize = if (big) 36.sp else 26.sp,
                        lineHeight = if (big) 40.sp else 29.sp,
                        letterSpacing = (-1).sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                unit?.let {
                    Text(
                        text = it,
                        style = PawseType.Label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 1.dp, top = 6.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(glyphOf(type), size = 14.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant, strokeWidth = 2f)
            Spacer(Modifier.width(6.dp))
            Text(ScoreCopy.name(type), style = PawseType.Action, color = MaterialTheme.colorScheme.onSurface)
        }
        BandDot(color = dot, label = word)
    }
}

// --- Target ---------------------------------------------------------------------

@Composable
private fun TargetCard(snapshot: HomeSnapshot, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val target = snapshot.strainTargetDisplay ?: return
    val scale = snapshot.strainScale
    val ceiling = strainCeiling(scale)
    val soFar = snapshot.strainDisplay ?: 0.0
    PawseCard(
        modifier = modifier,
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Today's target",
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Strain around ${ScoreCopy.strainNumber(target, scale)}",
                style = PawseType.Action,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            Glyph(Glyphs.CHEVRON, size = 16.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        FillBar(
            fraction = (soFar / ceiling).toFloat(),
            tickFraction = (target / ceiling).toFloat(),
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "${ScoreCopy.strainNumber(soFar, scale)} so far",
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = ScoreCopy.targetNote(target, snapshot.strainTypicalDisplay, scale),
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// --- Focus ----------------------------------------------------------------------

@Composable
private fun Focus(
    type: ScoreType,
    snapshot: HomeSnapshot,
    animate: Boolean,
    onOpenExplain: (ScoreType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(top = 32.dp)) {
        // A new focus fades in; the height snaps. The first one is simply there.
        androidx.compose.runtime.key(type) {
            Column(Modifier.reveal(animate)) {
                val score = snapshot.score(type)
                when {
                    score == null -> {
                        FocusHeading(ScoreCopy.name(type), null)
                        Text(
                            text = snapshot.unavailable[type]?.let { ScoreCopy.unavailableSentence(type, it) }
                                ?: "Nothing to score yet. Scores arrive after your first synced night.",
                            style = PawseType.Body,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    type == ScoreType.SLEEP -> SleepFocus(score, snapshot) { onOpenExplain(type) }
                    type == ScoreType.STRAIN -> StrainFocus(score, snapshot) { onOpenExplain(type) }
                    else -> RecoveryFocus(score, snapshot) { onOpenExplain(type) }
                }
            }
        }
        PawseCard(
            modifier = Modifier
                .padding(top = 16.dp)
                .heightIn(min = 52.dp),
            onClick = { onOpenExplain(type) },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "How ${ScoreCopy.name(type)} was built",
                    style = PawseType.Action,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Glyph(Glyphs.CHEVRON, size = 18.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FocusHeading(title: String, trailing: String?) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = title,
            style = PawseType.Title,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        trailing?.let {
            Text(it, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FocusSentence(text: String, flags: List<String> = emptyList()) {
    Text(
        text = text,
        style = PawseType.Body,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
    if (flags.isNotEmpty()) {
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            flags.forEach { Chip(text = it) }
        }
    }
}

@Composable
private fun RecoveryFocus(score: Score, snapshot: HomeSnapshot, onOpen: () -> Unit) {
    FocusHeading("Why Recovery is ${score.value}", ScoreCopy.inputsLine(score))
    FocusSentence(ScoreCopy.sentence(ScoreType.RECOVERY, score, snapshot), ScoreCopy.flags(score))

    val rows = score.contributions.sortedWith(
        compareByDescending<Contribution> { it.present }.thenByDescending { it.nominalWeight },
    )
    val hasBaselines = rows.any { it.baselineMean != null }
    Row(
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (hasBaselines) "Against your usual" else "What it was built from",
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (hasBaselines) UsualRangeKey()
    }
    rows.forEach { contribution ->
        BaselineBandRow(state = contribution.toRowState(), onClick = onOpen)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun SleepFocus(score: Score, snapshot: HomeSnapshot, onOpen: () -> Unit) {
    FocusHeading("Why Sleep is ${score.value}", ScoreCopy.inputsLine(score))
    FocusSentence(
        ScoreCopy.sentence(ScoreType.SLEEP, score, snapshot) + " These parts add up to ${score.value}.",
        ScoreCopy.flags(score),
    )
    snapshot.sleepNeedHours?.let { need -> NeedCard(need, snapshot) }
    SectionLabel("Against tonight's target", Modifier.padding(top = 16.dp, bottom = 4.dp))
    score.contributions.sortedByDescending { it.nominalWeight }.forEach { contribution ->
        SleepTermRow(contribution, snapshot.sleepNeedHours, onOpen)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Tonight's need, and the three things it is made of. */
@Composable
private fun NeedCard(need: Double, snapshot: HomeSnapshot) {
    val parts = listOfNotNull(
        snapshot.sleepNeedBaselineHours?.let { it to MaterialTheme.colorScheme.outline },
        snapshot.sleepNeedStrainAdderHours?.takeIf { it > 0 }?.let { it to MaterialTheme.colorScheme.onSurfaceVariant },
        snapshot.sleepNeedDebtAdderHours?.takeIf { it > 0 }?.let { it to MaterialTheme.colorScheme.onSurface },
    )
    val words = buildList {
        snapshot.sleepNeedBaselineHours?.let { add("Baseline ${ScoreCopy.formatHours(it)}") }
        snapshot.sleepNeedStrainAdderHours?.takeIf { it > 0 }?.let { add("+ ${minutes(it)} from yesterday's strain") }
        snapshot.sleepNeedDebtAdderHours?.takeIf { it > 0 }?.let { add("+ ${minutes(it)} recent debt") }
        snapshot.sleepNeedNapCreditHours?.takeIf { it > 0 }?.let { add("− ${minutes(it)} from naps") }
    }
    PawseCard(
        modifier = Modifier.padding(top = 16.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row {
            Text(
                "Tonight's need",
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                ScoreCopy.formatHours(need),
                style = PawseType.Label.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (parts.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                parts.forEach { (hours, color) ->
                    Box(
                        Modifier
                            .weight(hours.toFloat().coerceAtLeast(0.01f))
                            .fillMaxHeight()
                            .background(color),
                    )
                }
            }
        }
        if (words.isNotEmpty()) {
            Text(
                text = words.joinToString("   "),
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private fun minutes(hours: Double) = "${(hours * 60).roundToInt()} min"

@Composable
private fun SleepTermRow(contribution: Contribution, needHours: Double?, onOpen: () -> Unit) {
    val sub = ScoreCopy.subScore(contribution)
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = ScoreCopy.metricLabel(contribution.metric),
                style = PawseType.Body,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = contribution.raw?.let { ScoreCopy.formatRaw(contribution.metric, it) } ?: "—",
                style = PawseType.MetricValue,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (contribution.present) "+${contribution.points.roundToInt()}" else "",
                style = PawseType.Delta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .width(52.dp),
            )
        }
        FillBar(
            fraction = (sub ?: 0) / 100f,
            modifier = Modifier
                .padding(top = 8.dp, bottom = 6.dp)
                .alpha(if (contribution.present) 1f else 0.45f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = ScoreCopy.sleepNote(contribution, needHours),
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = (sub?.let { "$it / 100 · " } ?: "") + "weight ${(contribution.nominalWeight * 100).roundToInt()}%",
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StrainFocus(score: Score, snapshot: HomeSnapshot, onOpen: () -> Unit) {
    val scale = snapshot.strainScale
    val display = snapshot.strainDisplay ?: score.value.toDouble()
    FocusHeading(
        "Strain so far: ${ScoreCopy.strainNumber(display, scale)}",
        "On the ${if (scale == StrainScale.WHOOP_21) "21" else "100"} scale",
    )
    FocusSentence(
        ScoreCopy.sentence(ScoreType.STRAIN, score, snapshot) + " Strain is load, not a grade, so it stays grey.",
        ScoreCopy.flags(score),
    )
    SectionLabel("Where it came from", Modifier.padding(top = 16.dp, bottom = 4.dp))
    fun part(metric: Metric) = score.contributions.firstOrNull { it.metric == metric }
    StrainPart("Workouts", part(Metric.WORKOUT_LOAD), workoutsNote(snapshot), scale, onOpen)
    StrainPart("Rest of the day", part(Metric.PASSIVE_LOAD), "Passive load across your waking hours.", scale, onOpen)
    Text(
        text = "Sessions don't add up: load runs through a saturating curve, so a second hard hour adds less than the first.",
        style = PawseType.Label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 14.dp),
    )
}

private fun workoutsNote(snapshot: HomeSnapshot): String {
    if (snapshot.sessionLoads.isEmpty()) return "No workouts logged yet today."
    return snapshot.sessionLoads.joinToString(" ") { session ->
        val head = "${session.title ?: "Workout"}, ${session.durationMinutes.roundToInt()} min."
        if (session.scored) {
            val alone = scaledStrain(session.strainIfAlone, snapshot.strainScale)
            "$head On its own it would have scored ${ScoreCopy.strainNumber(alone, snapshot.strainScale)}."
        } else {
            "$head Not scored: ${session.skippedReason ?: "not enough data"}."
        }
    }
}

@Composable
private fun StrainPart(
    name: String,
    contribution: Contribution?,
    note: String,
    scale: StrainScale,
    onOpen: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(name, style = PawseType.Body, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text(
                text = contribution?.raw?.let { ScoreCopy.formatRaw(contribution.metric, it) } ?: "0 TRIMP",
                style = PawseType.MetricValue,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val points = contribution?.points?.let { scaledStrain(it, scale) } ?: 0.0
            Text(
                text = (if (points > 0.0) "+" else "") + ScoreCopy.strainNumber(points, scale),
                style = PawseType.Delta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .width(52.dp),
            )
        }
        Text(
            text = note,
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

// --- Also today -------------------------------------------------------------------

@Composable
private fun AlsoToday(snapshot: HomeSnapshot, onOpenExplain: (ScoreType) -> Unit) {
    Column(Modifier.padding(top = 32.dp)) {
        SectionLabel("Also today", Modifier.padding(bottom = 10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
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
}

@Composable
private fun AlsoCard(type: ScoreType, snapshot: HomeSnapshot, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val score = snapshot.score(type)
    val (value, suffix) = score?.let { displayValue(type, it, snapshot) } ?: ("—" to null)
    val trend = snapshot.trends[type]
    val divisor = if (type == ScoreType.LOAD_RATIO) 100.0 else 1.0
    PawseCard(modifier = modifier.heightIn(min = 156.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(glyphOf(type), size = 14.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant, strokeWidth = 2f)
            Spacer(Modifier.width(6.dp))
            Text(ScoreCopy.name(type), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = PawseType.Display.copy(fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = (-1).sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            suffix?.let {
                Text(
                    text = it,
                    style = PawseType.Body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
                )
            }
        }
        score?.let { BandDot(color = bandColor(type, it.band), label = ScoreCopy.bandWord(type, it.band)) }
        Spacer(Modifier.weight(1f))
        if (trend != null) {
            val mean = trend.usualMean
            val sd = trend.usualSd
            val low = if (mean != null && sd != null) (mean - sd) / divisor else null
            val high = if (mean != null && sd != null) (mean + sd) / divisor else null
            Sparkline(
                values = trend.values.map { it?.div(divisor) },
                usualLow = low,
                usualHigh = high,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Text(
            text = score?.let { ScoreCopy.sentence(type, it, snapshot) }
                ?: snapshot.unavailable[type]?.let { ScoreCopy.unavailableSentence(type, it) }
                ?: "Not available yet.",
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

// --- States -----------------------------------------------------------------------

/** Couldn't read: what happened, that nothing was lost, and a retry. */
@Composable
private fun FailedReadCard(
    stale: Boolean,
    message: String,
    onRetry: () -> Unit,
    onSecondary: () -> Unit,
) {
    PawseCard(Modifier.padding(top = 24.dp)) {
        StatePictogram(Pictogram.READ_FAILED)
        Text(
            text = if (stale) "Showing yesterday's scores" else "Nothing new this time",
            style = PawseType.Title,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = if (stale) {
                "Nothing new was scored this morning. Yesterday's numbers are below, marked as " +
                    "yesterday, so you never mistake them for today."
            } else {
                message
            },
            style = PawseType.Body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            modifier = Modifier.padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrimaryButton("Try again", onRetry, height = 44.dp)
            QuietButton(if (stale) "Open Health Connect" else "Dismiss", onSecondary)
        }
    }
}

/** The last score there is, dimmed and dated, so it is never read as today's. */
@Composable
private fun StaleScore(snapshot: HomeSnapshot, today: LocalDate, onOpenExplain: (ScoreType) -> Unit) {
    val type = snapshot.heroType
    val score = snapshot.score(type) ?: return
    val (value, unit) = displayValue(type, score, snapshot)
    val color = bandColor(type, score.band)
    val day = if (snapshot.date == today.minusDays(1)) "yesterday" else "last scored"
    Column(
        Modifier
            .padding(top = 28.dp)
            .fillMaxWidth()
            .clickable { onOpenExplain(type) },
    ) {
        Text(
            text = buildAnnotatedString {
                append("${ScoreCopy.name(type)} · ")
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)) { append(day) }
                append(", ${snapshot.date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))}")
            },
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier
                .padding(top = 4.dp)
                .alpha(0.6f),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(value, style = PawseType.Hero, color = MaterialTheme.colorScheme.onSurface)
            unit?.let {
                Text(
                    it,
                    style = PawseType.Title.copy(fontWeight = FontWeight.Normal),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp, bottom = 12.dp),
                )
            }
        }
        if (type != ScoreType.STRAIN) BandScale(score.value, score.band, color, ScoreCopy.bandWord(type, score.band))
        Text(
            text = ScoreCopy.sentence(type, score, snapshot),
            style = PawseType.Body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = ScoreCopy.coverageLine(score),
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Low · Moderate · High as three segments, the score's own marked and named. */
@Composable
private fun BandScale(value: Int, band: Band, color: Color, word: String) {
    val track = PawseTheme.semantics.baselineOuter
    val mixed = lerp(track, color, 0.3f)
    Column(Modifier.padding(top = 16.dp)) {
        BoxWithConstraints(Modifier.alpha(0.6f)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .height(6.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Band.entries.forEach { b ->
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(if (b == band) mixed else track, RoundedCornerShape(3.dp)),
                    )
                }
            }
            Box(
                Modifier
                    .offset(x = maxWidth * (value / 100f) - 2.dp)
                    .size(width = 4.dp, height = 14.dp)
                    .background(color, RoundedCornerShape(2.dp)),
            )
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            listOf(Band.LOW to "Low", Band.MODERATE to "Moderate", Band.HIGH to "High").forEach { (b, name) ->
                Box(Modifier.weight(1f)) {
                    if (b == band) {
                        BandDot(color = color, label = word)
                    } else {
                        Text(name, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** No sleep last night: say why, offer the fix, show what still works. */
@Composable
private fun NoSleep(
    snapshot: HomeSnapshot,
    warm: String,
    onAddNight: () -> Unit,
    onOpenExplain: (ScoreType) -> Unit,
) {
    Column {
        StatePictogram(Pictogram.NO_SLEEP, Modifier.padding(top = 28.dp))
        Text(warm, style = PawseType.Headline, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 16.dp))
        Text("Recovery", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 20.dp))
        Text("—", style = PawseType.Hero, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = (snapshot.unavailable[ScoreType.RECOVERY]?.let { ScoreCopy.unavailableSentence(ScoreType.RECOVERY, it) } ?: "") +
                " If you slept without your watch, add the night in Health Connect and it will be scored.",
            style = PawseType.Body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
        SecondaryButton(
            text = "Add last night in Health Connect",
            onClick = onAddNight,
            modifier = Modifier
                .padding(top = 16.dp)
                .fillMaxWidth(),
        )
        val still = listOf(ScoreType.STRAIN, ScoreType.LOAD_RATIO).filter { snapshot.score(it) != null }
        if (still.isNotEmpty()) {
            SectionLabel("Still here · these don't need last night", Modifier.padding(top = 28.dp))
            still.forEach { ScoreLine(it, snapshot, note = null, onClick = { onOpenExplain(it) }) }
        }
    }
}

/** Still learning: a count and a date, never just "not yet". */
@Composable
private fun Learning(
    snapshot: HomeSnapshot,
    warmup: Warmup,
    warm: String,
    onOpenExplain: (ScoreType) -> Unit,
) {
    val night = (warmup.nightsCollected + 1).coerceAtMost(warmup.nightsNeeded)
    val day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
    Column {
        StatePictogram(
            Pictogram.LEARNING,
            Modifier.padding(top = 28.dp),
            learnedFraction = night / warmup.nightsNeeded.toFloat(),
        )
        Text(warm, style = PawseType.Headline, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 16.dp))
        Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Recovery", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Chip("Night $night of ${warmup.nightsNeeded}")
        }
        Row(
            modifier = Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
                .clearAndSetSemantics { contentDescription = "$night of ${warmup.nightsNeeded} nights collected" },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            repeat(warmup.nightsNeeded) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(6.dp)
                        .background(
                            if (i < night) MaterialTheme.colorScheme.onSurface else PawseTheme.semantics.baselineOuter,
                            RoundedCornerShape(3.dp),
                        ),
                )
            }
        }
        Row(Modifier.padding(top = 8.dp)) {
            Text(
                "Started ${warmup.startedOn.format(day)}",
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Ready around ${warmup.readyAround.format(day)}",
                style = PawseType.Label.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = "Recovery compares each night with your own usual range. That takes about two weeks — " +
                "a number built on $night ${if (night == 1) "night" else "nights"} would be a guess.",
            style = PawseType.Body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
        val ready = listOf(ScoreType.SLEEP, ScoreType.STRAIN, ScoreType.ENERGY_BANK, ScoreType.LOAD_RATIO)
            .filter { snapshot.score(it) != null }
        if (ready.isNotEmpty()) {
            SectionLabel("Ready now", Modifier.padding(top = 28.dp))
            ready.forEach { type ->
                ScoreLine(
                    type = type,
                    snapshot = snapshot,
                    note = if (type == ScoreType.SLEEP) {
                        "Sleep is scored against a target, not your history, so it works from night one."
                    } else {
                        null
                    },
                    onClick = { onOpenExplain(type) },
                )
            }
        }
    }
}

/** A score as one line: name, value, its band in a dot and a word. */
@Composable
private fun ScoreLine(type: ScoreType, snapshot: HomeSnapshot, note: String?, onClick: () -> Unit) {
    val score = snapshot.score(type) ?: return
    val (value, suffix) = displayValue(type, score, snapshot)
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ScoreCopy.name(type), style = PawseType.Title, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text(value, style = PawseType.MetricValue, color = MaterialTheme.colorScheme.onSurface)
            suffix?.let {
                Text(it, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp))
            }
        }
        BandDot(
            color = bandColor(type, score.band),
            label = ScoreCopy.bandWord(type, score.band),
            modifier = Modifier.padding(top = 6.dp),
        )
        note?.let {
            Text(it, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** Access turned off: reassure first, then one clear way back. */
@Composable
private fun AccessOff(onTurnOn: () -> Unit, onLookAtPast: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 24.dp),
    ) {
        StatePictogram(Pictogram.LOCKED, Modifier.padding(top = 108.dp))
        Text(
            "Pawse can't read your data right now",
            style = PawseType.Headline,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            "Access to Health Connect was turned off, so nothing new can be scored. Everything " +
                "already scored is still on this phone, untouched.",
            style = PawseType.Body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        PawseCard(Modifier.padding(top = 20.dp), contentPadding = PaddingValues(0.dp)) {
            listOf("Heart rate variability", "Sleep", "Heart rate, breathing, temperature").forEachIndexed { i, name ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(name, style = PawseType.Body, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Text("Off", style = PawseType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(48.dp))
        PrimaryButton("Turn access back on", onTurnOn, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        SecondaryButton("Look at past scores", onLookAtPast, Modifier.fillMaxWidth())
        Spacer(Modifier.height(140.dp))
    }
}

/** Shown over past scores while access stays off. */
@Composable
private fun AccessNotice(onTurnOn: () -> Unit) {
    PawseCard(Modifier.padding(top = 20.dp)) {
        Text(
            "Access to Health Connect is off. These are the last scores Pawse had, and nothing new " +
                "arrives until it is back on.",
            style = PawseType.Body,
            color = MaterialTheme.colorScheme.onSurface,
        )
        QuietButton("Turn access back on", onTurnOn, Modifier.offset(x = (-12).dp))
    }
}

@Composable
private fun CouldNotBuild(message: String, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp),
    ) {
        StatePictogram(Pictogram.READ_FAILED, Modifier.padding(top = 108.dp))
        Text(
            "Could not build your scores",
            style = PawseType.Headline,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            message,
            style = PawseType.Body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        PrimaryButton("Try again", onRetry, Modifier.padding(top = 24.dp))
    }
}

@Composable
private fun Loading() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Working out your night",
            style = PawseType.Body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

// --- Shared formatting ---------------------------------------------------------------

private fun strainCeiling(scale: StrainScale): Double = if (scale == StrainScale.WHOOP_21) 21.0 else 100.0

/** Canonical 0-100 strain onto the scale the user reads. */
private fun scaledStrain(canonical: Double, scale: StrainScale): Double =
    if (scale == StrainScale.WHOOP_21) canonical * 21.0 / 100.0 else canonical

/** A score's number and the unit beside it, the same way everywhere on Today. */
private fun displayValue(type: ScoreType, score: Score, snapshot: HomeSnapshot): Pair<String, String?> = when (type) {
    ScoreType.RECOVERY -> score.value.toString() to "%"
    ScoreType.STRAIN -> ScoreCopy.strainNumber(snapshot.strainDisplay ?: score.value.toDouble(), snapshot.strainScale) to null
    ScoreType.LOAD_RATIO -> String.format(Locale.getDefault(), "%.2f", score.value / 100.0) to "×"
    else -> score.value.toString() to null
}

private fun glyphOf(type: ScoreType): String = when (type) {
    ScoreType.SLEEP -> Glyphs.SLEEP
    ScoreType.RECOVERY -> Glyphs.RECOVERY
    ScoreType.STRAIN -> Glyphs.STRAIN
    ScoreType.ENERGY_BANK -> Glyphs.ENERGY
    ScoreType.LOAD_RATIO -> Glyphs.LOAD
}
