package app.pawse.feature.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pawse.core.data.scoring.ScoringSnapshot
import app.pawse.core.ui.component.BackLink
import app.pawse.core.ui.component.BaselineBandRow
import app.pawse.core.ui.component.BandDot
import app.pawse.core.ui.component.Chip
import app.pawse.core.ui.component.Segmented
import app.pawse.core.ui.theme.PawseType
import app.pawse.scoring.config.ScoringConfig
import app.pawse.scoring.model.ScoreType
import app.pawse.scoring.model.SessionLoad
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The arithmetic, in full, for Body, Sleep and Load.
 *
 * Everything here was computed once, by the engine. Nothing is recalculated in
 * the UI: a screen that recomputes is a second engine that will eventually
 * disagree with the first, about the number the user was shown.
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
    var shown by rememberSaveable { mutableStateOf(type) }
    val pad = Modifier.padding(horizontal = 24.dp)

    Scaffold(modifier = modifier, containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 48.dp,
            ),
        ) {
            item {
                Column(pad) {
                    BackLink("Today", onClick = onBack)
                    Spacer(Modifier.height(12.dp))
                    Segmented(
                        options = ScoreType.entries,
                        selected = shown,
                        onSelect = { shown = it },
                        label = ScoreCopy::name,
                        glyph = ::scoreGlyph,
                    )
                    Spacer(Modifier.height(20.dp))
                    Text("How ${ScoreCopy.name(shown)} was worked out", style = PawseType.Title)
                    Spacer(Modifier.height(16.dp))
                }
            }
            if (snapshot == null) {
                item { Note("Nothing to explain yet.", pad) }
                return@LazyColumn
            }
            item {
                Column(pad) {
                    when (shown) {
                        ScoreType.BODY -> BodyDetail(snapshot)
                        ScoreType.SLEEP -> SleepDetail(snapshot)
                        ScoreType.LOAD -> LoadDetail(snapshot)
                    }
                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(12.dp))
                    DetailLine("Engine", snapshot.scoringVersion)
                    DetailLine("Defaults", snapshot.configHash)
                    Spacer(Modifier.height(6.dp))
                    Note(
                        "Every number marked as a default is a reasoned starting point, not a validated value. " +
                            "${ScoreCopy.FOOTER}",
                    )
                }
            }
        }
    }
}

@Composable
private fun BodyDetail(snapshot: ScoringSnapshot) {
    val today = snapshot.today
    val body = today.body
    if (body == null) {
        Note(ScoreCopy.bodyStatus(today.bodyStatus, today.validNights, ScoringConfig.DEFAULT.body.minValidNights))
        return
    }
    BandDot(color = bodyColor(body.band, today.illness.level), label = ScoreCopy.bandWord(body.band))
    Spacer(Modifier.height(4.dp))
    Text("Body ${body.score}, likely ${body.low}–${body.high}", style = PawseType.Heading)
    Note("${ScoreCopy.bandMeaning(body.band)} Body ${body.score} is roughly “better than ${body.score}% of your normal days”.")
    Spacer(Modifier.height(4.dp))
    Chip(ScoreCopy.confidence(body.confidence))

    Section("Two views of one nervous system")
    body.hrv?.let { BaselineBandRow(state = it.toRowState(hrv = true)) }
    body.sleepingHr?.let { BaselineBandRow(state = it.toRowState(hrv = false)) }
    listOfNotNull(body.hrv?.let { "HRV" to it }, body.sleepingHr?.let { "Sleeping HR" to it }).forEach { (name, s) ->
        DetailLine("$name source", s.source.substringBefore('#') + if (s.source.endsWith("#resting")) " (watch's resting HR)" else "")
        DetailLine("$name normal", "${s.baselineNights} nights, the 60 ending a week ago")
        if (s.cycleAdjusted) DetailLine("$name cycle", "Your luteal offset was removed first")
    }
    Note(
        "Each night is a noisy reading of your true state. A Kalman filter trusts it only as much as its " +
            "quality allows, so a patchy night barely moves the estimate and a missed one widens the range.",
    )

    Section("Combined without double counting")
    Text("z = (w·z_h + (1−w)·z_r) / √(w² + (1−w)² + 2w(1−w)ρ)", style = PawseType.MetricValue)
    Spacer(Modifier.height(6.dp))
    DetailLine("HRV weight w", fmt(body.hrvWeight))
    DetailLine("Overlap ρ", fmt(body.rho))
    DetailLine("Combined z", fmt(body.z))
    DetailLine("Filter spread on z", fmt(body.zSd))
    Note(
        "HRV and sleeping heart rate read the same nervous system, so their overlap ρ — your own, pulled toward 0.5 " +
            "until there are enough nights — is divided out. Body = 100 × Φ(z); the range is the filter's own uncertainty.",
    )

    if (body.reasons.isNotEmpty()) {
        Section("Top reasons")
        Text(ScoreCopy.reasons(body.reasons), style = PawseType.Body)
    }

    today.weekly?.let { w ->
        Section("The week, not just the day")
        Text(ScoreCopy.weekly(w.pattern), style = PawseType.Body)
        DetailLine("7-day HRV", "${w.hrv7.roundToInt()} ms over ${w.nights} nights")
        DetailLine("Day-to-day variation", "${fmt(w.cv7)}%" + (w.usualCv7?.let { " (usually ${fmt(it)}%)" } ?: ""))
        Note("A falling weekly HRV with shrinking variation under heavy load is a warning even when no single day looks bad.")
    }

    Section("Overnight heart-rate pattern")
    DetailLine("Running sum", fmt(today.illness.cusum))
    DetailLine("Quiet note above", "3")
    DetailLine("Alert above", "5, with temperature or breathing up")
    Note(
        "Small excesses add up night after night, so three slightly high nights count and one random spike does not. " +
            "Logged drinks and travel or stress tags are checked first. It never names a disease.",
    )

    Section("What moves your Body")
    if (snapshot.insights.isEmpty()) {
        Note("Nothing clear yet. An insight appears after at least 8 days of exposure and only when its 90% range excludes zero.")
    } else {
        snapshot.insights.forEach {
            Text(ScoreCopy.insight(it), style = PawseType.Body, modifier = Modifier.padding(vertical = 4.dp))
        }
        Note(ScoreCopy.INSIGHT_NOTE)
    }
}

@Composable
private fun SleepDetail(snapshot: ScoringSnapshot) {
    val sleep = snapshot.today.sleep
    Text(ScoreCopy.debt(sleep.debtHours), style = PawseType.Heading)
    Spacer(Modifier.height(8.dp))
    DetailLine("Need", ScoreCopy.formatHours(sleep.needHours))
    DetailLine(
        "Where need came from",
        if (sleep.needLearned) "Median of ${sleep.freeNights} weekend nights, kept within 7–9 h" else "Default 8 h until there are 10 weekend nights",
    )
    sleep.lastNightMinutes?.let { DetailLine("Last night", ScoreCopy.formatMinutes(it)) }
    DetailLine("Regularity", ScoreCopy.regularity(sleep.sri))
    Note(
        "Debt weights recent nights more (0.85 per night back, 14 nights), and one long lie-in repays at most an hour. " +
            "Regularity is the Sleep Regularity Index: the chance you are in the same state 24 hours apart, " +
            "−100 to 100 over 14 days, naps included. In UK Biobank it predicted mortality better than duration.",
    )

    sleep.score?.let { score ->
        Section("Optional Sleep score: $score")
        sleep.parts?.let { p ->
            DetailLine("Duration vs need (45%)", "${(p.duration * 100).roundToInt()} / 100")
            DetailLine("Regularity (35%)", p.regularity?.let { "${(it * 100).roundToInt()} / 100" } ?: "Not yet")
            DetailLine("Continuity (20%)", p.continuity?.let { "${(it * 100).roundToInt()} / 100" } ?: "Not recorded")
        }
        Note("Stages get no weight. Wrist staging is the least reliable thing a watch measures.")
    }

    Section("Last night in detail")
    sleep.efficiencyPercent?.let { DetailLine("Efficiency", "${it.roundToInt()}%") }
    sleep.wasoMinutes?.let { DetailLine("Awake after falling asleep", "${it.roundToInt()} min") }
    sleep.remMinutes?.let { DetailLine("REM", ScoreCopy.formatMinutes(it)) }
    sleep.deepMinutes?.let { DetailLine("Deep", ScoreCopy.formatMinutes(it)) }
    if (sleep.remMinutes == null) Note("Your watch did not record stages for this night.")
}

@Composable
private fun LoadDetail(snapshot: ScoringSnapshot) {
    val load = snapshot.today.load
    Text(load.formBand?.let(ScoreCopy::form) ?: "Building your history", style = PawseType.Heading)
    Spacer(Modifier.height(8.dp))
    load.fitness?.let { DetailLine("Fitness (42-day)", ScoreCopy.load(it)) }
    load.fatigue?.let { DetailLine("Fatigue (7-day)", ScoreCopy.load(it)) }
    load.form?.let { DetailLine("Form", "${(it * 100).roundToInt()}%") }
    Note(
        "Form is the gap between fitness and fatigue as a share of fitness. Above −10% reads fresh, −10% to −30% " +
            "productive, below −30% heavy. Starting defaults, never an injury-risk label.",
    )

    Section("Today")
    load.typicalDayLoad?.let { DetailLine("Your typical training day", ScoreCopy.load(it)) }
    val low = load.rangeLow
    val high = load.rangeHigh
    DetailLine(
        "Suggested range",
        when {
            load.rest -> "Rest: your overnight heart rate has been high"
            low != null && high != null -> "${ScoreCopy.load(low)}–${ScoreCopy.load(high)}"
            else -> "After your first few workouts"
        },
    )
    DetailLine("So far", ScoreCopy.load(load.todayLoad))
    Note("Your typical day scaled by this morning's Body: Body 50 is about usual, 80 about 30% more, 15 about two thirds.")

    Section("Sessions")
    if (load.sessions.isEmpty()) Note("No workouts logged today.")
    load.sessions.forEach { SessionLine(it) }
}

@Composable
private fun SessionLine(session: SessionLoad) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Row {
            Text(session.title ?: "Workout", style = PawseType.Body, modifier = Modifier.weight(1f))
            Text("${session.durationMinutes.roundToInt()} min", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DetailLine("Load", ScoreCopy.load(session.load))
        DetailLine("Counted by", ScoreCopy.loadMethod(session.method))
        session.reserveFraction?.let { DetailLine("Heart-rate reserve", "${(it * 100).roundToInt()}%") }
        session.skippedReason?.let { Note(it) }
        if (session.citation.isNotBlank()) Note(session.citation)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(24.dp))
    Text(title, style = PawseType.Heading)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(label, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(0.42f))
        Text(value, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.padding(top = 4.dp))
}

private fun fmt(v: Double): String = String.format(Locale.getDefault(), "%.2f", v)
