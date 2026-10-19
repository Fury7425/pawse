package app.pawse.feature.home

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pawse.core.data.scoring.ScoringSnapshot
import app.pawse.core.data.sync.SyncFailure
import app.pawse.core.data.sync.SyncStatus
import app.pawse.core.data.sync.SyncStep
import app.pawse.core.ui.component.BandDot
import app.pawse.core.ui.component.GhostButton
import app.pawse.core.ui.component.Glyph
import app.pawse.core.ui.component.GlyphButton
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.PawseCard
import app.pawse.core.ui.component.PrimaryButton
import app.pawse.core.ui.component.cardSurface
import app.pawse.core.ui.component.pressable
import app.pawse.core.ui.theme.PawseTheme
import app.pawse.core.ui.theme.PawseType
import app.pawse.scoring.config.ScoringConfig
import app.pawse.scoring.model.BodyStatus
import app.pawse.scoring.model.DayTag
import app.pawse.scoring.model.EnergyForecast
import app.pawse.scoring.model.IllnessLevel
import app.pawse.scoring.model.ScoreType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Today: one sentence of guidance, then Body, Sleep and Load as three cards, then
 * the energy curve. The scores support the sentence; they are not the headline.
 *
 * Rules against score anxiety are layout rules here: the band word is large and
 * the number small (and hideable), no single night is painted red, and the only
 * red on the screen is an illness alert.
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
    val alert = today.illness.level == IllnessLevel.ALERT
    val glow = if (today.body != null || alert) bodyColor(today.body?.band, today.illness.level) else null
    val pad = Modifier.padding(horizontal = 24.dp)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 140.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(
                Modifier.drawBehind {
                    if (glow != null) {
                        val c = Offset(size.width * 0.5f, 120.dp.toPx())
                        drawCircle(
                            Brush.radialGradient(listOf(glow.copy(alpha = 0.10f), Color.Transparent), c, 240.dp.toPx()),
                            radius = 240.dp.toPx(),
                            center = c,
                        )
                    }
                },
            ) {
                Header(snapshot.date, sync, snapshot.lastSyncEpochMs, onSyncNow, onSettings, pad)
                Spacer(Modifier.height(24.dp))
                Text(ScoreCopy.guidance(today.guidance), style = PawseType.Title, modifier = pad)
                Spacer(Modifier.height(8.dp))
            }
        }

        if (alert) {
            item {
                PawseCard(modifier = pad) {
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
                    modifier = pad,
                )
            }
        }

        if (syncError != null) {
            item { Notice(text = syncError, actionLabel = "Dismiss", onAction = onDismissError, modifier = pad) }
        }

        item { BodyCard(snapshot, onClick = { onOpenExplain(ScoreType.BODY) }, modifier = pad) }
        item { SleepCard(snapshot, onClick = { onOpenExplain(ScoreType.SLEEP) }, modifier = pad) }
        item { LoadCard(snapshot, onClick = { onOpenExplain(ScoreType.LOAD) }, modifier = pad) }
        snapshot.energy?.let { energy -> item { EnergyCard(energy, modifier = pad) } }

        item { CheckInCard(snapshot, onCheckIn, onTag, modifier = pad) }

        if (today.sleep.suggestDoctor) {
            item { Notice(text = ScoreCopy.DOCTOR, modifier = pad) }
        }

        if (snapshot.needsProfile) {
            item {
                Notice(
                    text = "Workouts are counted by effort until Pawse knows your maximum heart rate. " +
                        "Your age, or a measured maximum, lets it read your heart rate instead.",
                    actionLabel = "Add it",
                    onAction = onSettings,
                    modifier = pad,
                )
            }
        }

        item {
            Column(pad.padding(top = 12.dp)) {
                GhostButton("You and your settings", onClick = onSettings)
                Spacer(Modifier.height(8.dp))
                Text(ScoreCopy.FOOTER, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

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

/** A card's label row: glyph, name, chevron. */
@Composable
private fun CardHead(glyph: String, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Glyph(glyph, size = 14.dp, strokeWidth = 2f, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(title, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Glyph(Glyphs.ChevronRight, size = 16.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Body: band word large, number and range small, a week, and the top two reasons. */
@Composable
private fun BodyCard(snapshot: ScoringSnapshot, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val today = snapshot.today
    val body = today.body
    PawseCard(modifier = modifier, onClick = onClick) {
        CardHead(Glyphs.Recovery, "Body")
        Spacer(Modifier.height(8.dp))
        if (body == null) {
            Text(
                if (today.bodyStatus == BodyStatus.NO_RECENT_NIGHT) "No reading" else "Settling",
                style = PawseType.Title,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                ScoreCopy.bodyStatus(today.bodyStatus, today.validNights, ScoringConfig.DEFAULT.body.minValidNights),
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@PawseCard
        }
        val dot = bodyColor(body.band, today.illness.level)
        Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.padding(bottom = 10.dp).size(10.dp).background(dot, RoundedCornerShape(5.dp)))
            Spacer(Modifier.width(10.dp))
            Text(ScoreCopy.bandWord(body.band), style = PawseType.Title, modifier = Modifier.weight(1f))
            if (snapshot.showNumbers) {
                Text("${body.score} (${body.low}–${body.high})", style = PawseType.MetricValue)
            }
        }
        Text(
            ScoreCopy.confidence(body.confidence),
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Sparkline(
            values = snapshot.week.map { it.body?.score?.toDouble() },
            band = 31.0..69.0,
            min = 0.0,
            max = 100.0,
            modifier = Modifier.fillMaxWidth().height(36.dp),
        )
        if (body.reasons.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(ScoreCopy.reasons(body.reasons), style = PawseType.Body)
        }
        today.weekly?.let {
            Spacer(Modifier.height(6.dp))
            Text("This week: ${ScoreCopy.weekly(it.pattern)}", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** Sleep in real units: hours behind, how regular, last night. Stages are one tap away. */
@Composable
private fun SleepCard(snapshot: ScoringSnapshot, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val sleep = snapshot.today.sleep
    PawseCard(modifier = modifier, onClick = onClick) {
        CardHead(Glyphs.Sleep, "Sleep")
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(ScoreCopy.debt(sleep.debtHours), style = PawseType.Title, modifier = Modifier.weight(1f))
            if (snapshot.showNumbers) sleep.score?.let { Text("Sleep $it", style = PawseType.MetricValue) }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Fact("Last night", sleep.lastNightMinutes?.let(ScoreCopy::formatMinutes) ?: "—", Modifier.weight(1f))
            Fact("Need", ScoreCopy.formatHours(sleep.needHours), Modifier.weight(1f))
            Fact("Regularity", ScoreCopy.regularity(sleep.sri), Modifier.weight(1.4f))
        }
    }
}

/** Load: form, today's range, and a week of form. */
@Composable
private fun LoadCard(snapshot: ScoringSnapshot, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val load = snapshot.today.load
    PawseCard(modifier = modifier, onClick = onClick) {
        CardHead(Glyphs.Load, "Load")
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                load.formBand?.let(ScoreCopy::form) ?: "Building your history",
                style = PawseType.Title,
                modifier = Modifier.weight(1f),
            )
            if (snapshot.showNumbers) load.form?.let { Text("Form ${(it * 100).roundToInt()}%", style = PawseType.MetricValue) }
        }
        Spacer(Modifier.height(10.dp))
        val low = load.rangeLow
        val high = load.rangeHigh
        Text(
            when {
                load.rest -> "Today: rest"
                low != null && high != null -> "Today: ${ScoreCopy.load(low)}–${ScoreCopy.load(high)} · so far ${ScoreCopy.load(load.todayLoad)}"
                else -> "Today so far: ${ScoreCopy.load(load.todayLoad)}"
            },
            style = PawseType.Body,
        )
        val forms = snapshot.week.map { it.load.form }
        if (forms.count { it != null } >= 2) {
            Spacer(Modifier.height(12.dp))
            Sparkline(
                values = forms.map { it?.times(100) },
                band = -30.0..-10.0,
                min = null,
                max = null,
                modifier = Modifier.fillMaxWidth().height(36.dp),
            )
        }
    }
}

/** Today's energy forecast: best focus window, afternoon dip, wind-down, caffeine at bedtime. */
@Composable
private fun EnergyCard(energy: EnergyForecast, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.onSurface
    val focus = PawseTheme.semantics.baselineBand
    val muted = MaterialTheme.colorScheme.outline
    val caffeine = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    PawseCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(Glyphs.Energy, size = 14.dp, strokeWidth = 2f, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text("Energy forecast", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        val points = energy.points
        val lo = points.minOf { it.alertness }
        val hi = points.maxOf { it.alertness }
        val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
        val maxCaf = points.maxOf { it.caffeineMg }.coerceAtLeast(1.0)
        val first = points.first().minute
        val last = points.last().minute
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(96.dp)
                .semantics {
                    contentDescription = "Energy forecast. Best focus ${ScoreCopy.minuteOfDay(energy.focusStart)} to " +
                        "${ScoreCopy.minuteOfDay(energy.focusEnd)}, dip around ${ScoreCopy.minuteOfDay(energy.dipMinute)}, " +
                        "wind down from ${ScoreCopy.minuteOfDay(energy.windDownMinute)}."
                },
        ) {
            fun x(m: Int) = (m - first).toFloat() / (last - first).coerceAtLeast(1) * size.width
            fun y(a: Double) = (size.height - 6.dp.toPx()) - ((a - lo) / span).toFloat() * (size.height - 12.dp.toPx())
            drawRoundRect(
                focus,
                Offset(x(energy.focusStart), 0f),
                Size(x(energy.focusEnd) - x(energy.focusStart), size.height),
                CornerRadius(6.dp.toPx()),
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
            drawPath(path, line, style = Stroke(1.75.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            val wd = x(energy.windDownMinute)
            drawLine(muted, Offset(wd, 0f), Offset(wd, size.height), 1.dp.toPx())
            val dip = points.minByOrNull { kotlin.math.abs(it.minute - energy.dipMinute) }
            if (dip != null) drawCircle(line, 3.5.dp.toPx(), Offset(x(dip.minute), y(dip.alertness)))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Fact("Best focus", "${ScoreCopy.minuteOfDay(energy.focusStart)}–${ScoreCopy.minuteOfDay(energy.focusEnd)}", Modifier.weight(1f))
            Fact("Dip", ScoreCopy.minuteOfDay(energy.dipMinute), Modifier.weight(0.7f))
            Fact("Wind down", ScoreCopy.minuteOfDay(energy.windDownMinute), Modifier.weight(0.9f))
        }
        if (energy.caffeineAtBedMg >= 1.0) {
            Spacer(Modifier.height(8.dp))
            Text(
                "About ${energy.caffeineAtBedMg.roundToInt()} mg of caffeine still in your system at bedtime.",
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (energy.clockLagMinutes != 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Your body clock is still catching up with the time zone, about an hour a day.",
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(ScoreCopy.FORECAST_NOTE, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun Fact(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = PawseType.BodyStrong)
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
