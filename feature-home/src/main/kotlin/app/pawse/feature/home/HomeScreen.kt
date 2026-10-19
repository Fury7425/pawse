package app.pawse.feature.home

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import app.pawse.core.data.scoring.ScoringSnapshot
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
import app.pawse.scoring.config.ScoringConfig
import app.pawse.scoring.model.BodyStatus
import app.pawse.scoring.model.DayTag
import app.pawse.scoring.model.EnergyForecast
import app.pawse.scoring.model.FormBand
import app.pawse.scoring.model.IllnessLevel
import app.pawse.scoring.model.LoadReading
import app.pawse.scoring.model.ScoreType
import app.pawse.scoring.model.SleepReading
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Today: one line of guidance, then Sleep, Body and Load as three rings. Tapping a
 * ring swaps the section below to what produced it; Energy and Form sit under that.
 *
 * Rules against score anxiety are layout rules here: every ring carries its word,
 * Body's number can be hidden, no single night is painted red, and the only red on
 * the screen is an illness alert.
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
                Text("Could not work out today", style = PawseType.Title)
                Spacer(Modifier.height(8.dp))
                Text(current.message, style = PawseType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                PrimaryButton("Try again", onClick = viewModel::refresh)
            }

            is HomeUiState.Ready -> TodayContent(
                snapshot = current.snapshot,
                sync = current.sync,
                accessOff = current.accessOff,
                syncError = error,
                contentPadding = padding,
                onOpenExplain = onOpenExplain,
                onSyncNow = viewModel::syncNow,
                onSettings = { editingProfile = true },
                onDismissError = viewModel::dismissError,
                onCheckIn = viewModel::checkIn,
                onTag = viewModel::toggleTag,
            )
        }
    }

    if (editingProfile) {
        val snapshot = (state as? HomeUiState.Ready)?.snapshot
        ProfileDialog(
            initialProfile = snapshot?.profile,
            initialShowNumbers = snapshot?.showNumbers ?: true,
            initialAppearance = appearance,
            calibration = snapshot?.calibration,
            onResetModel = viewModel::resetModel,
            onDismiss = { editingProfile = false },
            onSave = { age, sex, maxHr, showNumbers, chosenAppearance ->
                viewModel.saveProfile(age, sex, maxHr)
                viewModel.setShowNumbers(showNumbers)
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

private val RINGS = listOf(ScoreType.SLEEP, ScoreType.BODY, ScoreType.LOAD)
private val RING_WEIGHTS = listOf(1f, 1.3f, 1f)

@Composable
private fun TodayContent(
    snapshot: ScoringSnapshot,
    sync: SyncStatus,
    accessOff: Boolean,
    syncError: String?,
    contentPadding: PaddingValues,
    onOpenExplain: (ScoreType) -> Unit,
    onSyncNow: () -> Unit,
    onSettings: () -> Unit,
    onDismissError: () -> Unit,
    onCheckIn: (Int) -> Unit,
    onTag: (DayTag) -> Unit,
) {
    val context = LocalContext.current
    val today = snapshot.today
    val hour = LocalTime.now().hour
    var focus by rememberSaveable {
        mutableStateOf(
            when {
                hour >= 18 && today.load.todayLoad > 0.0 -> ScoreType.LOAD
                today.body != null -> ScoreType.BODY
                today.sleep.lastNightMinutes != null -> ScoreType.SLEEP
                else -> ScoreType.BODY
            },
        )
    }
    val alert = today.illness.level == IllnessLevel.ALERT
    val glow = if (today.body != null || alert) bodyColor(today.body?.band, today.illness.level) else null
    val load = today.load
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
                // A faint wash of Body's colour behind the rings, drawn past this
                // item's edges and scrolled away with it.
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
                Header(snapshot.date, sync, snapshot.lastSyncEpochMs, onSyncNow, onSettings, pad)
                Spacer(Modifier.height(28.dp))
                Text(ScoreCopy.guidance(today.guidance), style = PawseType.Title, modifier = pad)
                Spacer(Modifier.height(20.dp))
                Rings(snapshot = snapshot, focus = focus, onFocus = { focus = it })
            }
        }

        if (alert) {
            item {
                PawseCard(modifier = pad.padding(top = 16.dp)) {
                    BandDot(color = PawseTheme.semantics.bandLow, label = "Overnight heart rate")
                    Spacer(Modifier.height(6.dp))
                    Text(ScoreCopy.ILLNESS_ALERT, style = PawseType.Body)
                    ScoreCopy.likelyCauses(today.illness.likelyCauses)?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (accessOff) {
            item {
                Notice(
                    text = "Pawse can't read your data right now. Access to Health Connect was turned off, " +
                        "so nothing new can come in. Everything already read is still on this phone.",
                    actionLabel = "Turn access back on",
                    onAction = {
                        runCatching { context.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }
                    },
                    modifier = pad.padding(top = 16.dp),
                )
            }
        }

        if (today.body == null && today.bodyStatus == BodyStatus.WARMING_UP && focus != ScoreType.BODY) {
            item {
                Notice(
                    text = ScoreCopy.bodyStatus(today.bodyStatus, today.validNights, ScoringConfig.DEFAULT.body.minValidNights),
                    modifier = pad.padding(top = 16.dp),
                )
            }
        }

        if (syncError != null) {
            item {
                Notice(text = syncError, actionLabel = "Dismiss", onAction = onDismissError, modifier = pad.padding(top = 16.dp))
            }
        }

        if (load.rest || (load.rangeLow != null && load.rangeHigh != null)) {
            item { RangeCard(load, onClick = { onOpenExplain(ScoreType.LOAD) }, modifier = pad.padding(top = 16.dp)) }
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
                EnergyCard(snapshot.energy, Modifier.weight(1f).fillMaxHeight())
                FormCard(snapshot, onClick = { onOpenExplain(ScoreType.LOAD) }, modifier = Modifier.weight(1f).fillMaxHeight())
            }
            if (snapshot.energy != null) {
                Spacer(Modifier.height(10.dp))
                Text(ScoreCopy.FORECAST_NOTE, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = pad)
            }
        }

        item { CheckInCard(snapshot, onCheckIn, onTag, modifier = pad.padding(top = 20.dp)) }

        if (today.sleep.suggestDoctor) {
            item { Notice(text = ScoreCopy.DOCTOR, modifier = pad.padding(top = 20.dp)) }
        }

        if (snapshot.needsProfile) {
            item {
                Notice(
                    text = "Workouts are counted by effort until Pawse knows your maximum heart rate. " +
                        "Your age, or a measured maximum, lets it read your heart rate instead.",
                    actionLabel = "Add it",
                    onAction = onSettings,
                    modifier = pad.padding(top = 20.dp),
                )
            }
        }

        item {
            Column(pad.padding(top = 24.dp)) {
                GhostButton("You and your settings", onClick = onSettings)
                Spacer(Modifier.height(8.dp))
                Text(ScoreCopy.FOOTER, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * The date and what happened: when Health Connect was last read, or which of the
 * three steps is running now. The rings below never blank while it works.
 */
@Composable
private fun Header(
    date: LocalDate,
    sync: SyncStatus,
    lastSyncEpochMs: Long,
    onSyncNow: () -> Unit,
    onSettings: () -> Unit,
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
                    Text(statusLine(sync, lastSyncEpochMs), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            GlyphButton(Glyphs.Person, "You and your settings", onClick = onSettings)
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
            SyncStep.SCORE -> "working it out"
        }
    is SyncStatus.Failed -> when (sync.reason) {
        SyncFailure.READ_FAILED -> "Health Connect didn't answer at ${ScoreCopy.clock(sync.atEpochMs)}"
        SyncFailure.ACCESS_OFF -> "Access to Health Connect is off"
        SyncFailure.UNAVAILABLE -> "Health Connect isn't available"
    }
    else -> if (lastSyncEpochMs > 0) "Read from Health Connect at ${ScoreCopy.clock(lastSyncEpochMs)}" else "Not read from Health Connect yet"
}

/** What one ring shows. A null [value] draws the score's glyph instead: Body with numbers hidden. */
private class RingFace(
    val fraction: Float?,
    val arc: Color,
    val glow: Boolean,
    val value: String?,
    val unit: String,
    val dot: Color,
    val word: String,
    val target: Float? = null,
)

/**
 * Body is its score on its band colour. Sleep is last night against your need and
 * Load is today against the top of its range; both are real units, so both stay grey.
 */
@Composable
private fun ringFace(type: ScoreType, snapshot: ScoringSnapshot): RingFace {
    val today = snapshot.today
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val neutral = PawseTheme.semantics.neutral
    return when (type) {
        ScoreType.BODY -> {
            val body = today.body
            val color = bodyColor(body?.band, today.illness.level)
            RingFace(
                fraction = body?.let { it.score / 100f },
                arc = color,
                glow = body != null,
                value = when {
                    body == null -> "—"
                    snapshot.showNumbers -> body.score.toString()
                    else -> null
                },
                unit = "",
                dot = color,
                word = body?.let { ScoreCopy.bandWord(it.band) }
                    ?: if (today.bodyStatus == BodyStatus.NO_RECENT_NIGHT) "No reading" else "Settling",
            )
        }

        ScoreType.SLEEP -> {
            val sleep = today.sleep
            val slept = sleep.lastNightMinutes
            RingFace(
                fraction = slept?.let { (it / (sleep.needHours * 60.0)).toFloat() },
                arc = grey,
                glow = false,
                value = slept?.let { String.format(Locale.getDefault(), "%.1f", it / 60.0) } ?: "—",
                unit = if (slept != null) "h" else "",
                dot = neutral,
                word = sleep.debtHours?.let(ScoreCopy::debt) ?: "Settling",
            )
        }

        ScoreType.LOAD -> {
            val load = today.load
            // The full ring is the top of today's range; the tick is where the range starts.
            val top = load.rangeHigh?.takeIf { it > 0.0 && !load.rest }
            RingFace(
                fraction = top?.let { (load.todayLoad / it).toFloat() },
                arc = grey,
                glow = false,
                value = ScoreCopy.load(load.todayLoad),
                unit = "",
                dot = neutral,
                word = formWord(load),
                target = top?.let { high -> load.rangeLow?.let { (it / high).toFloat() } },
            )
        }
    }
}

private fun formWord(load: LoadReading): String = when {
    load.rest -> "Rest today"
    else -> when (load.formBand) {
        FormBand.FRESH -> "Fresh"
        FormBand.PRODUCTIVE -> "Productive"
        FormBand.HEAVY -> "Heavy"
        null -> "Building"
    }
}

/** Sleep, Body and Load as rings, Body a size up; tapping one picks the section below. */
@Composable
private fun Rings(snapshot: ScoringSnapshot, focus: ScoreType, onFocus: (ScoreType) -> Unit) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        RINGS.forEachIndexed { i, type ->
            RingTile(
                type = type,
                face = ringFace(type, snapshot),
                selected = focus == type,
                onClick = { onFocus(type) },
                modifier = Modifier.weight(RING_WEIGHTS[i]),
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
                modifier = Modifier.weight(RING_WEIGHTS[i]),
            )
        }
    }
}

@Composable
private fun RingTile(
    type: ScoreType,
    face: RingFace,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val big = type == ScoreType.BODY
    val shown = face.value?.let { " $it${face.unit}" } ?: ""
    val description = "${ScoreCopy.name(type)}$shown, ${face.word}" + if (selected) ", shown below" else ""

    Column(
        modifier = modifier
            .pressable(onClick = onClick)
            .then(if (selected) Modifier.cardSurface() else Modifier)
            .padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ScoreRing(
            fraction = face.fraction,
            color = face.arc,
            size = if (big) 112.dp else 84.dp,
            glow = face.glow,
            target = face.target,
        ) {
            if (face.value == null) {
                Glyph(scoreGlyph(type), size = 28.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(face.value, style = if (big) PawseType.Numeral else PawseType.RingSmall)
                    if (face.unit.isNotEmpty()) {
                        Text(
                            face.unit,
                            style = PawseType.Label,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(scoreGlyph(type), size = 14.dp, strokeWidth = 2f, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(ScoreCopy.name(type), style = PawseType.BodyStrong)
        }
        BandDot(color = face.dot, label = face.word)
    }
}

/** Today's load against the range this morning's Body set. */
@Composable
private fun RangeCard(load: LoadReading, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val low = load.rangeLow
    val high = load.rangeHigh
    val inRange = !load.rest && low != null && high != null
    val track = PawseTheme.semantics.baselineOuter
    val band = PawseTheme.semantics.baselineBand
    val fill = MaterialTheme.colorScheme.onSurfaceVariant
    val marker = MaterialTheme.colorScheme.onSurface
    PawseCard(modifier = modifier, onClick = onClick, padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Today’s range", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(
                if (inRange) "Load ${ScoreCopy.load(low!!)}–${ScoreCopy.load(high!!)}" else "Rest",
                style = PawseType.BodyStrong,
            )
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
            val scale = maxOf((high ?: 0.0) * 1.25, load.todayLoad, 1.0)
            fun x(v: Double) = (v / scale).coerceIn(0.0, 1.0).toFloat() * size.width
            drawRoundRect(track, Offset(0f, top), Size(size.width, bar), r)
            if (inRange) drawRoundRect(band, Offset(x(low!!), top), Size(x(high!!) - x(low), bar), r)
            val done = x(load.todayLoad)
            if (done > 0f) drawRoundRect(fill, Offset(0f, top), Size(done, bar), r)
            if (inRange) {
                drawRoundRect(marker, Offset(x(low!!) - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height), CornerRadius(1.dp.toPx()))
            }
        }
        Spacer(Modifier.height(4.dp))
        Row {
            Text(
                "${ScoreCopy.load(load.todayLoad)} so far",
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                when {
                    load.rest -> "Your overnight heart rate has been high"
                    else -> load.typicalDayLoad?.let { "Your typical day is ${ScoreCopy.load(it)}" } ?: ""
                },
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The section under the rings: why the picked one came out as it did. */
private fun LazyListScope.focusSection(
    type: ScoreType,
    snapshot: ScoringSnapshot,
    onOpenExplain: (ScoreType) -> Unit,
    pad: Modifier,
) {
    val open = { onOpenExplain(type) }
    when (type) {
        ScoreType.BODY -> bodySection(snapshot, open, pad)
        ScoreType.SLEEP -> sleepSection(snapshot, open, pad)
        ScoreType.LOAD -> loadSection(snapshot, open, pad)
    }
    item(key = "focus-foot-$type") {
        Column(pad) {
            Spacer(Modifier.height(16.dp))
            ExplainLink("How ${ScoreCopy.name(type)} was worked out", onClick = open)
        }
    }
}

@Composable
private fun FocusHead(title: String, aside: String?, sentence: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(title, style = PawseType.Heading, modifier = Modifier.weight(1f))
        aside?.let { Text(it, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    Spacer(Modifier.height(4.dp))
    Text(sentence, style = PawseType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Body: the two signals against your normal, then the week. */
private fun LazyListScope.bodySection(snapshot: ScoringSnapshot, onOpen: () -> Unit, pad: Modifier) {
    val today = snapshot.today
    val body = today.body
    item(key = "focus-head-BODY") {
        Column(pad) {
            if (body == null) {
                FocusHead(
                    "No Body reading yet",
                    null,
                    ScoreCopy.bodyStatus(today.bodyStatus, today.validNights, ScoringConfig.DEFAULT.body.minValidNights),
                )
                return@Column
            }
            val measured = listOfNotNull(body.hrv?.tonight, body.sleepingHr?.tonight).size
            FocusHead(
                title = "Why Body is ${ScoreCopy.bandWord(body.band).lowercase()}",
                aside = "$measured of 2 signals",
                sentence = (if (snapshot.showNumbers) "Body ${body.score}, likely ${body.low}–${body.high}. " else "") +
                    (body.reasons.takeIf { it.isNotEmpty() }?.let { ScoreCopy.reasons(it) + "." } ?: ScoreCopy.bandMeaning(body.band)),
            )
            Spacer(Modifier.height(10.dp))
            Row { Chip(ScoreCopy.confidence(body.confidence)) }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Against your normal",
                    style = PawseType.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                UsualRangeKey("Your normal range")
            }
        }
    }
    if (body == null) return

    val rows = listOfNotNull(body.hrv?.toRowState(hrv = true), body.sleepingHr?.toRowState(hrv = false))
    items(rows, key = { "focus-BODY-${it.label}" }) { row ->
        Column(pad) {
            BaselineBandRow(state = row, onClick = onOpen)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
    item(key = "focus-week-BODY") {
        Column(pad) {
            Spacer(Modifier.height(16.dp))
            Text("This week", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Sparkline(
                values = snapshot.week.map { it.body?.score?.toDouble() },
                band = 31.0..69.0,
                min = 0.0,
                max = 100.0,
                modifier = Modifier.fillMaxWidth().height(36.dp),
            )
            today.weekly?.let {
                Spacer(Modifier.height(8.dp))
                Text(ScoreCopy.weekly(it.pattern), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (today.illness.level == IllnessLevel.WATCH) {
                Spacer(Modifier.height(6.dp))
                Text(
                    ScoreCopy.ILLNESS_WATCH + (ScoreCopy.likelyCauses(today.illness.likelyCauses)?.let { " $it" } ?: ""),
                    style = PawseType.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            today.cycle?.takeIf { it.luteal }?.let { cycle ->
                Spacer(Modifier.height(6.dp))
                Text(
                    if (cycle.adjusted) "Adjusted for your cycle, using your own past cycles." else "Part of this may be your cycle.",
                    style = PawseType.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Sleep: last night against your need, then the optional score's three parts. */
private fun LazyListScope.sleepSection(snapshot: ScoringSnapshot, onOpen: () -> Unit, pad: Modifier) {
    val sleep = snapshot.today.sleep
    val config = ScoringConfig.DEFAULT.sleep
    item(key = "focus-head-SLEEP") {
        Column(pad) {
            FocusHead(
                title = ScoreCopy.debt(sleep.debtHours),
                aside = if (snapshot.showNumbers) sleep.score?.let { "Sleep $it" } else null,
                sentence = (
                    sleep.lastNightMinutes?.let {
                        "You slept ${ScoreCopy.formatMinutes(it)} against a need of ${ScoreCopy.formatHours(sleep.needHours)}."
                    } ?: "No night recorded yet."
                    ) + if (sleep.needLearned) "" else " Need stays at ${ScoreCopy.formatHours(config.defaultNeedHours)} until there are ${config.minFreeNights} weekend nights to learn yours from.",
            )
            SleepNeedCard(sleep)
            Spacer(Modifier.height(16.dp))
            Text(
                "Against your need",
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
    val parts = sleep.parts
    if (parts == null) {
        item(key = "focus-SLEEP-none") {
            Text("The parts appear once there is a night to read.", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = pad)
        }
        return
    }
    val rows = listOf(
        SleepPart("Duration vs need", sleep.lastNightMinutes?.let(ScoreCopy::formatMinutes) ?: "—", parts.duration, config.scoreDurationWeight),
        SleepPart("Regularity", sleep.sri?.roundToInt()?.toString() ?: "—", parts.regularity, config.scoreRegularityWeight),
        SleepPart("Continuity", sleep.efficiencyPercent?.let { "${it.roundToInt()}%" } ?: "—", parts.continuity, config.scoreContinuityWeight),
    )
    items(rows, key = { "focus-SLEEP-${it.label}" }) { part ->
        Column(pad) {
            PartRow(part, onClick = onOpen)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** Last night against the need, as one bar: slept, then what was missing. */
@Composable
private fun SleepNeedCard(sleep: SleepReading) {
    val need = sleep.needHours * 60.0
    val slept = sleep.lastNightMinutes ?: return
    val short = (need - slept).coerceAtLeast(0.0)
    Spacer(Modifier.height(16.dp))
    PawseCard(padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row {
            Text("Your need", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(ScoreCopy.formatHours(sleep.needHours), style = PawseType.Label.copy(fontWeight = PawseType.BodyStrong.fontWeight))
        }
        Spacer(Modifier.height(10.dp))
        SplitBar(
            listOf(
                slept.coerceAtMost(need).toFloat() to MaterialTheme.colorScheme.outline,
                short.toFloat() to MaterialTheme.colorScheme.onSurface,
            ),
            height = 8.dp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            listOfNotNull(
                "Slept ${ScoreCopy.formatMinutes(slept)}",
                short.takeIf { it >= 1.0 }?.let { "${minutes(it)} short" },
            ).joinToString("   "),
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private class SleepPart(val label: String, val value: String, val part: Double?, val weight: Double)

/**
 * A sleep part, scored against your need rather than your history: its value and its
 * part score. A part that was not measured has its weight shared out by the engine.
 */
@Composable
private fun PartRow(part: SleepPart, onClick: () -> Unit) {
    val score = part.part?.let { (it * 100).roundToInt() }
    val track = PawseTheme.semantics.baselineOuter
    val fill = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(part.label, style = PawseType.Body, modifier = Modifier.weight(1f))
            Text(part.value, style = PawseType.MetricValue)
        }
        Spacer(Modifier.height(8.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(6.dp),
        ) {
            val r = CornerRadius(size.height / 2f)
            drawRoundRect(track, cornerRadius = r)
            if (score != null) drawRoundRect(fill, size = Size(size.width * score / 100f, size.height), cornerRadius = r)
        }
        Spacer(Modifier.height(6.dp))
        Row {
            Text(
                score?.let { "Part score $it / 100" } ?: "Not measured, so its weight went to the others",
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text("Weight ${(part.weight * 100).roundToInt()}%", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Load: today's sessions, what each counted for, and how. */
private fun LazyListScope.loadSection(snapshot: ScoringSnapshot, onOpen: () -> Unit, pad: Modifier) {
    val load = snapshot.today.load
    item(key = "focus-head-LOAD") {
        Column(pad) {
            FocusHead(
                title = "Load so far: ${ScoreCopy.load(load.todayLoad)}",
                aside = if (snapshot.showNumbers) load.form?.let { "Form ${(it * 100).roundToInt()}%" } else load.formBand?.let(ScoreCopy::form),
                sentence = rangeSentence(load) + " Load is training, not a grade, so it stays grey.",
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Where it came from",
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
    if (load.sessions.isEmpty()) {
        item(key = "focus-LOAD-none") {
            Column(pad) {
                SessionRow("Workouts", "0", "No workouts logged yet today.", onOpen)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    } else {
        items(load.sessions, key = { "focus-LOAD-${it.id}" }) { s ->
            Column(pad) {
                SessionRow(
                    label = s.title ?: "Workout",
                    value = ScoreCopy.load(s.load),
                    note = "${s.durationMinutes.roundToInt()} min · ${ScoreCopy.loadMethod(s.method)}" + (s.skippedReason?.let { ". $it" } ?: ""),
                    onClick = onOpen,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
    item(key = "focus-note-LOAD") {
        val config = ScoringConfig.DEFAULT.load
        Text(
            "Fitness is your load over ${config.fitnessDays.roundToInt()} days and fatigue over ${config.fatigueDays.roundToInt()}; " +
                "form is the gap between them.",
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = pad.padding(top = 14.dp),
        )
    }
}

private fun rangeSentence(load: LoadReading): String {
    val low = load.rangeLow
    val high = load.rangeHigh
    return when {
        load.rest -> "Your overnight heart rate has been high, so today is for rest."
        low != null && high != null ->
            "Today’s range is ${ScoreCopy.load(low)}–${ScoreCopy.load(high)}: your typical training day, moved by this morning’s Body."
        else -> "A range appears after your first few workouts."
    }
}

@Composable
private fun SessionRow(label: String, value: String, note: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, style = PawseType.Body, modifier = Modifier.weight(1f))
            Text(value, style = PawseType.MetricValue)
        }
        Spacer(Modifier.height(4.dp))
        Text(note, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** An "Also today" card: a figure, its word, a small chart, one line. */
@Composable
private fun AlsoCard(
    glyph: String,
    name: String,
    value: String?,
    suffix: String,
    word: String,
    sentence: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    chart: @Composable () -> Unit,
) {
    PawseCard(modifier = modifier.heightIn(min = 156.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(glyph, size = 14.dp, strokeWidth = 2f, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(name, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        if (value != null) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = PawseType.NumeralSmall)
                if (suffix.isNotEmpty()) {
                    Text(
                        suffix,
                        style = PawseType.Body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
                    )
                }
            }
        }
        BandDot(color = PawseTheme.semantics.neutral, label = word)
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(12.dp))
        chart()
        Spacer(Modifier.height(8.dp))
        Text(sentence, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Today's energy forecast: when focus is best, and the curve it came from. */
@Composable
private fun EnergyCard(energy: EnergyForecast?, modifier: Modifier = Modifier) {
    AlsoCard(
        glyph = Glyphs.Energy,
        name = "Energy",
        value = energy?.let { ScoreCopy.minuteOfDay(it.focusStart) } ?: "—",
        suffix = "",
        word = energy?.let { "Best focus to ${ScoreCopy.minuteOfDay(it.focusEnd)}" } ?: "Not forecast yet",
        sentence = energy?.let { e ->
            listOfNotNull(
                "Dip around ${ScoreCopy.minuteOfDay(e.dipMinute)}, wind down from ${ScoreCopy.minuteOfDay(e.windDownMinute)}.",
                e.caffeineAtBedMg.takeIf { it >= 1.0 }?.let { "About ${it.roundToInt()} mg of caffeine left at bedtime." },
                if (e.clockLagMinutes != 0) "Your body clock is still catching up with the time zone." else null,
            ).joinToString(" ")
        } ?: "Appears once Pawse knows when you sleep.",
        onClick = null,
        modifier = modifier,
    ) {
        energy?.let { EnergyCurve(it, Modifier.fillMaxWidth().height(36.dp)) }
    }
}

/** Form: fitness against fatigue, and a week of it over the productive band. */
@Composable
private fun FormCard(snapshot: ScoringSnapshot, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val load = snapshot.today.load
    val config = ScoringConfig.DEFAULT.load
    val fitness = load.fitness
    val fatigue = load.fatigue
    val forms = snapshot.week.map { it.load.form?.times(100) }
    AlsoCard(
        glyph = Glyphs.Load,
        name = "Form",
        value = if (snapshot.showNumbers) load.form?.let { (it * 100).roundToInt().toString() } ?: "—" else null,
        suffix = if (snapshot.showNumbers && load.form != null) "%" else "",
        word = load.formBand?.let(ScoreCopy::form) ?: "Building your history",
        sentence = if (fitness != null && fatigue != null) {
            "Fitness ${ScoreCopy.load(fitness)}, fatigue ${ScoreCopy.load(fatigue)}."
        } else {
            "Needs a few weeks of training to read."
        },
        onClick = onClick,
        modifier = modifier,
    ) {
        if (forms.count { it != null } >= 2) {
            Sparkline(
                values = forms,
                band = config.heavyBelow * 100..config.freshAbove * 100,
                min = null,
                max = null,
                modifier = Modifier.fillMaxWidth().height(36.dp),
            )
        }
    }
}

/** The forecast curve: focus window shaded, caffeine underneath, wind-down line, dip dot. */
@Composable
private fun EnergyCurve(energy: EnergyForecast, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.onSurfaceVariant
    val dot = MaterialTheme.colorScheme.onSurface
    val focus = PawseTheme.semantics.baselineBand
    val muted = MaterialTheme.colorScheme.outline
    val caffeine = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val points = energy.points
    if (points.isEmpty()) return
    val lo = points.minOf { it.alertness }
    val hi = points.maxOf { it.alertness }
    val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
    val maxCaf = points.maxOf { it.caffeineMg }.coerceAtLeast(1.0)
    val first = points.first().minute
    val last = points.last().minute
    Canvas(
        modifier.semantics {
            contentDescription = "Energy forecast. Best focus ${ScoreCopy.minuteOfDay(energy.focusStart)} to " +
                "${ScoreCopy.minuteOfDay(energy.focusEnd)}, dip around ${ScoreCopy.minuteOfDay(energy.dipMinute)}, " +
                "wind down from ${ScoreCopy.minuteOfDay(energy.windDownMinute)}."
        },
    ) {
        val pad = 4.dp.toPx()
        fun x(m: Int) = (m - first).toFloat() / (last - first).coerceAtLeast(1) * size.width
        fun y(a: Double) = (size.height - pad) - ((a - lo) / span).toFloat() * (size.height - 2 * pad)
        drawRoundRect(
            focus,
            Offset(x(energy.focusStart), 0f),
            Size(x(energy.focusEnd) - x(energy.focusStart), size.height),
            CornerRadius(3.dp.toPx()),
        )
        if (points.any { it.caffeineMg > 1.0 }) {
            val caf = Path()
            caf.moveTo(x(first), size.height)
            points.forEach { caf.lineTo(x(it.minute), size.height - (it.caffeineMg / maxCaf).toFloat() * size.height * 0.4f) }
            caf.lineTo(x(last), size.height)
            caf.close()
            drawPath(caf, caffeine)
        }
        val path = Path()
        points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.minute), y(p.alertness)) else path.lineTo(x(p.minute), y(p.alertness)) }
        drawPath(path, line, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val wd = x(energy.windDownMinute)
        drawLine(muted, Offset(wd, 0f), Offset(wd, size.height), 1.dp.toPx())
        val dip = points.minByOrNull { kotlin.math.abs(it.minute - energy.dipMinute) }
        if (dip != null) drawCircle(dot, 3.5.dp.toPx(), Offset(x(dip.minute), y(dip.alertness)))
    }
}

/** One tap: how do you feel? Never feeds the score, only the calibration. Plus one-tap tags. */
@Composable
private fun CheckInCard(snapshot: ScoringSnapshot, onCheckIn: (Int) -> Unit, onTag: (DayTag) -> Unit, modifier: Modifier = Modifier) {
    PawseCard(modifier = modifier) {
        Text("How do you feel?", style = PawseType.Heading)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..5).forEach { n ->
                Pill(n.toString(), selected = snapshot.checkIn == n, onClick = { onCheckIn(n) }, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("1 rough · 5 great. It never changes today’s reading.", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val feeling = snapshot.checkIn
        val band = snapshot.today.body?.band
        if (feeling != null && band != null && ScoreCopy.disagrees(feeling, band)) {
            Spacer(Modifier.height(6.dp))
            Text(ScoreCopy.DISAGREE, style = PawseType.Label)
        }
        Spacer(Modifier.height(16.dp))
        Text("Anything going on?", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DayTag.entries.forEach { tag ->
                Pill(ScoreCopy.tag(tag), selected = tag in snapshot.tags, onClick = { onTag(tag) })
            }
        }
    }
}

@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(40.dp)
            .pressable(onClick = onClick)
            .then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(12.dp))
                } else {
                    Modifier.cardSurface(RoundedCornerShape(12.dp), 12.dp)
                },
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = PawseType.BodyStrong,
            color = if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/** A week as a line over a shaded band; today is the dot. */
@Composable
internal fun Sparkline(
    values: List<Double?>,
    band: ClosedFloatingPointRange<Double>?,
    min: Double?,
    max: Double?,
    modifier: Modifier = Modifier,
) {
    val shade = PawseTheme.semantics.baselineBand
    val line = MaterialTheme.colorScheme.onSurfaceVariant
    val dot = MaterialTheme.colorScheme.onSurface
    val known = values.filterNotNull()
    if (known.isEmpty()) return
    val lo = min ?: (known + listOfNotNull(band?.start)).min()
    val hi = max ?: (known + listOfNotNull(band?.endInclusive)).max()
    val span = (hi - lo).takeIf { it > 0.0 } ?: 1.0
    Canvas(modifier) {
        val pad = 4.dp.toPx()
        fun y(v: Double) = ((size.height - pad) - (v - lo) / span * (size.height - 2 * pad)).toFloat()
        fun x(i: Int) = i * size.width / (values.size - 1).coerceAtLeast(1)
        band?.let {
            val top = y(it.endInclusive)
            drawRoundRect(shade, Offset(0f, top), Size(size.width, y(it.start) - top), CornerRadius(3.dp.toPx()))
        }
        val path = Path()
        var started = false
        values.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            if (!started) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v))
            started = true
        }
        drawPath(path, line, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val last = values.indexOfLast { it != null }
        drawCircle(dot, 3.5.dp.toPx(), Offset(x(last), y(values[last]!!)))
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
    ScoreType.BODY -> Glyphs.Recovery
    ScoreType.SLEEP -> Glyphs.Sleep
    ScoreType.LOAD -> Glyphs.Load
}

private fun minutes(m: Double): String = if (m >= 60.0) ScoreCopy.formatMinutes(m) else "${m.roundToInt()} min"
