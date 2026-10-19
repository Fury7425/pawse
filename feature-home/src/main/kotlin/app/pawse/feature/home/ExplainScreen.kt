package app.pawse.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pawse.core.data.scoring.ScoringSnapshot
import app.pawse.core.ui.component.BackLink
import app.pawse.core.ui.component.BaselineBand
import app.pawse.core.ui.component.Chip
import app.pawse.core.ui.component.Glyph
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.PawseCard
import app.pawse.core.ui.component.ScoreRing
import app.pawse.core.ui.component.Segmented
import app.pawse.core.ui.theme.PawseTheme
import app.pawse.core.ui.theme.PawseType
import app.pawse.scoring.config.ScoringConfig
import app.pawse.scoring.model.ScoreType
import app.pawse.scoring.model.SignalDetail
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The arithmetic, in full, for Body, Sleep and Load.
 *
 * Everything here was computed once, by the engine. Nothing is recalculated in
 * the UI: a screen that recomputes is a second engine that will eventually
 * disagree with the first, about the number the user was shown. (The dot strip
 * only places this week's nights on tonight's axis.)
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
    var open by rememberSaveable(shown) { mutableIntStateOf(0) }
    var mathOpen by rememberSaveable(shown) { mutableStateOf(false) }
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
                    Spacer(Modifier.height(20.dp))
                }
            }

            val today = snapshot?.today
            if (snapshot == null || today == null || (shown == ScoreType.BODY && today.body == null)) {
                item {
                    Text(
                        text = today?.let {
                            ScoreCopy.bodyStatus(it.bodyStatus, it.validNights, ScoringConfig.DEFAULT.body.minValidNights)
                        } ?: "Nothing to explain yet.",
                        style = PawseType.Body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = pad,
                    )
                }
                return@LazyColumn
            }

            item {
                Summary(shown, snapshot, pad)
                Spacer(Modifier.height(20.dp))
                Combiner(
                    combiner = combinerOf(shown, snapshot),
                    mathOpen = mathOpen,
                    onToggleMath = { mathOpen = !mathOpen },
                    modifier = pad,
                )
            }

            val terms = termsOf(shown, snapshot)
            if (terms.isNotEmpty()) {
                item {
                    Row(pad.padding(top = 28.dp, bottom = 2.dp)) {
                        Text(termsHeader(shown), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text(
                            effectHeader(shown),
                            style = PawseType.Label,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 30.dp),
                        )
                    }
                }
                itemsIndexed(terms, key = { i, t -> "$shown-$i-${t.label}" }) { i, term ->
                    Term(term, expanded = open == i, onToggle = { open = if (open == i) -1 else i }, modifier = pad)
                }
            }

            item {
                Column(pad.padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (shown) {
                        ScoreType.BODY -> BodyCards(snapshot)
                        ScoreType.SLEEP -> SleepCards(snapshot)
                        ScoreType.LOAD -> LoadCards(snapshot)
                    }
                }
            }

            item { Provenance(snapshot, pad.padding(top = 28.dp)) }
        }
    }
}

/** The ring, its word, and the few facts that frame it. */
@Composable
private fun Summary(type: ScoreType, snapshot: ScoringSnapshot, modifier: Modifier = Modifier) {
    val today = snapshot.today
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val readAt = snapshot.lastSyncEpochMs.takeIf { it > 0 }?.let { "Read at ${ScoreCopy.clock(it)}" }

    var fraction: Float? = null
    var color = grey
    var glow = false
    var value = "—"
    var unit = ""
    var target: Float? = null
    val dot: Color
    val word: String
    val info: List<String>
    when (type) {
        ScoreType.BODY -> {
            val body = today.body ?: return
            fraction = body.score / 100f
            color = bodyColor(body.band, today.illness.level)
            glow = true
            value = body.score.toString()
            dot = color
            word = ScoreCopy.bandWord(body.band)
            info = listOfNotNull("Likely ${body.low}–${body.high}", ScoreCopy.confidence(body.confidence), readAt)
        }
        ScoreType.SLEEP -> {
            val sleep = today.sleep
            sleep.lastNightMinutes?.let {
                fraction = (it / (sleep.needHours * 60.0)).toFloat()
                value = String.format(Locale.getDefault(), "%.1f", it / 60.0)
                unit = "h"
            }
            dot = PawseTheme.semantics.neutral
            word = ScoreCopy.debt(sleep.debtHours)
            info = listOfNotNull(
                "Need ${ScoreCopy.formatHours(sleep.needHours)}",
                ScoreCopy.regularity(sleep.sri),
                sleep.score?.let { "Sleep score $it" },
            )
        }
        ScoreType.LOAD -> {
            val load = today.load
            val top = load.rangeHigh?.takeIf { it > 0.0 && !load.rest }
            fraction = top?.let { (load.todayLoad / it).toFloat() }
            target = top?.let { high -> load.rangeLow?.let { (it / high).toFloat() } }
            value = ScoreCopy.load(load.todayLoad)
            dot = PawseTheme.semantics.neutral
            word = load.formBand?.let(ScoreCopy::form) ?: "Building your history"
            val low = load.rangeLow
            val high = load.rangeHigh
            info = listOfNotNull(
                when {
                    load.rest -> "Rest today"
                    low != null && high != null -> "Range ${ScoreCopy.load(low)}–${ScoreCopy.load(high)} today"
                    else -> "No range yet"
                },
                load.typicalDayLoad?.let { "Typical day ${ScoreCopy.load(it)}" },
                readAt,
            )
        }
    }

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        ScoreRing(fraction = fraction, color = color, size = 112.dp, glow = glow, target = target) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value, style = PawseType.Numeral)
                if (unit.isNotEmpty()) {
                    Text(unit, style = PawseType.Label, color = grey, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        Spacer(Modifier.width(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(8.dp)) { drawCircle(dot) }
                Spacer(Modifier.width(8.dp))
                Text(word, style = PawseType.BodyStrong)
            }
            info.forEach { Text(it, style = PawseType.Label, color = grey) }
        }
    }
}

/** The formula that turns the parts into the reading, and what it gave. */
private class CombinerText(
    val formula: AnnotatedString,
    val left: String,
    val result: String,
    val note: String,
    val math: String,
)

private fun combinerOf(type: ScoreType, snapshot: ScoringSnapshot): CombinerText {
    val config = ScoringConfig.DEFAULT
    val today = snapshot.today
    val sup = SpanStyle(fontSize = 11.sp, baselineShift = BaselineShift.Superscript)
    return when (type) {
        ScoreType.BODY -> {
            val body = today.body
            CombinerText(
                formula = AnnotatedString("100 × Φ(z)"),
                left = "z, both signals combined",
                result = body?.score?.toString() ?: "—",
                note = "Your normal reads 50",
                math = "Each night is a noisy reading of your true state. A Kalman filter trusts it only as much as " +
                    "its quality allows, so a patchy night barely moves the estimate and a missed one widens the range. " +
                    "HRV and sleeping heart rate read the same nervous system, so " +
                    "z = (w·z_h + (1−w)·z_r) / √(w² + (1−w)² + 2w(1−w)ρ) divides their overlap ρ out" +
                    (body?.let { " — here w = ${fmt(it.hrvWeight)}, ρ = ${fmt(it.rho)}, z = ${fmt(it.z)}" } ?: "") +
                    ". The range is ±${fmt(config.body.rangeZ)} of the filter's own spread" +
                    (body?.let { " (${fmt(it.zSd)})" } ?: "") + ".",
            )
        }
        ScoreType.SLEEP -> {
            val sleep = today.sleep
            CombinerText(
                formula = buildAnnotatedString {
                    append("Debt = Σ ${config.sleep.debtDecay}")
                    withStyle(sup) { append("n") }
                    append(" × (need − slept)")
                },
                left = "${config.sleep.debtNights} nights",
                result = sleep.debtHours?.let(ScoreCopy::formatHours) ?: "—",
                note = "A lie-in repays at most ${config.sleep.maxRepayHours.roundToInt()} h",
                math = "Debt weights recent nights more (${config.sleep.debtDecay} per night back, " +
                    "${config.sleep.debtNights} nights), and one long lie-in repays at most an hour. " +
                    "Regularity is the Sleep Regularity Index: the chance you are in the same state 24 hours apart, " +
                    "−100 to 100 over ${config.sleep.sriDays} days, naps included. In UK Biobank it predicted mortality " +
                    "better than duration. The optional Sleep score is ${pct(config.sleep.scoreDurationWeight)} duration, " +
                    "${pct(config.sleep.scoreRegularityWeight)} regularity and ${pct(config.sleep.scoreContinuityWeight)} " +
                    "continuity; a part that was not measured has its weight shared out. Stages get no weight.",
            )
        }
        ScoreType.LOAD -> {
            val load = today.load
            CombinerText(
                formula = AnnotatedString("Form = (fitness − fatigue) ÷ fitness"),
                left = "${config.load.fitnessDays.roundToInt()} days vs ${config.load.fatigueDays.roundToInt()}",
                result = load.form?.let { "${(it * 100).roundToInt()}%" } ?: "—",
                note = "Below ${pct(config.load.heavyBelow)} reads heavy",
                math = "Each session is Banister's TRIMP: minutes weighted by how far into your heart-rate reserve you " +
                    "worked, counted only above ${pct(config.load.minReserveFraction)} of it. Without a usable heart rate it " +
                    "falls back to effort × minutes. Fitness averages ${config.load.fitnessDays.roundToInt()} days of load " +
                    "and fatigue ${config.load.fatigueDays.roundToInt()}. Above ${pct(config.load.freshAbove)} form reads " +
                    "fresh, ${pct(config.load.freshAbove)} to ${pct(config.load.heavyBelow)} productive, below that heavy. " +
                    "Starting defaults, never an injury-risk label.",
            )
        }
    }
}

@Composable
private fun Combiner(
    combiner: CombinerText,
    mathOpen: Boolean,
    onToggleMath: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val turn by animateFloatAsState(if (mathOpen) 180f else 0f, tween(200), label = "chevron")

    PawseCard(modifier = modifier, padding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
        Text("Combiner", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text(combiner.formula, style = PawseType.MetricValue)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(combiner.left, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Glyph(Glyphs.Arrow, size = 14.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(combiner.result, style = PawseType.Label)
            Text(
                combiner.note,
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clickable(onClick = onToggleMath),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("How the maths works", style = PawseType.BodyStrong, modifier = Modifier.weight(1f))
            Glyph(Glyphs.ChevronDown, size = 18.dp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.rotate(turn))
        }
        AnimatedVisibility(mathOpen, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Text(
                combiner.math,
                style = PawseType.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
    }
}

private fun termsHeader(type: ScoreType): String = when (type) {
    ScoreType.BODY -> "Signals · tap one to see your week"
    ScoreType.SLEEP -> "Parts · against your need"
    ScoreType.LOAD -> "Today’s sessions"
}

private fun effectHeader(type: ScoreType): String = when (type) {
    ScoreType.BODY -> "vs normal"
    ScoreType.SLEEP -> "Part"
    ScoreType.LOAD -> "Load"
}

/**
 * One line of the breakdown. [z] draws the usual-range band and toned marker,
 * [part] a plain 0..1 bar; [nights] are this week's earlier nights on z's axis.
 */
private class TermLine(
    val label: String,
    val value: String,
    val effect: String,
    val z: Double? = null,
    val part: Double? = null,
    val nights: List<Double> = emptyList(),
    val lines: List<Pair<String, String>> = emptyList(),
    val chip: String? = null,
    val citation: String? = null,
)

private fun termsOf(type: ScoreType, snapshot: ScoringSnapshot): List<TermLine> {
    val today = snapshot.today
    return when (type) {
        ScoreType.BODY -> {
            val body = today.body ?: return emptyList()
            val earlier = snapshot.week.dropLast(1)
            listOfNotNull(
                body.hrv?.let { signalTerm(it, hrv = true, body.hrvWeight, earlier.mapNotNull { d -> d.body?.hrv?.z }) },
                body.sleepingHr?.let { signalTerm(it, hrv = false, 1.0 - body.hrvWeight, earlier.mapNotNull { d -> d.body?.sleepingHr?.z }) },
            )
        }
        ScoreType.SLEEP -> {
            val sleep = today.sleep
            val parts = sleep.parts ?: return emptyList()
            val config = ScoringConfig.DEFAULT.sleep
            fun partLines(part: Double?, weight: Double) = listOf(
                "Part score" to (part?.let { "${(it * 100).roundToInt()} / 100" } ?: "Not measured, so its weight was shared out"),
                "Weight" to pct(weight),
            )
            listOf(
                TermLine(
                    label = "Duration vs need",
                    value = sleep.lastNightMinutes?.let(ScoreCopy::formatMinutes) ?: "—",
                    effect = partText(parts.duration),
                    part = parts.duration,
                    lines = partLines(parts.duration, config.scoreDurationWeight) +
                        ("Need" to ScoreCopy.formatHours(sleep.needHours)),
                ),
                TermLine(
                    label = "Regularity",
                    value = sleep.sri?.roundToInt()?.toString() ?: "—",
                    effect = partText(parts.regularity),
                    part = parts.regularity,
                    lines = partLines(parts.regularity, config.scoreRegularityWeight) +
                        ("Index" to ScoreCopy.regularity(sleep.sri)) +
                        ("Window" to "${config.sriDays} days, naps included"),
                ),
                TermLine(
                    label = "Continuity",
                    value = sleep.efficiencyPercent?.let { "${it.roundToInt()}%" } ?: "—",
                    effect = partText(parts.continuity),
                    part = parts.continuity,
                    lines = partLines(parts.continuity, config.scoreContinuityWeight) + listOfNotNull(
                        sleep.wasoMinutes?.let { "Awake after falling asleep" to "${it.roundToInt()} min" },
                        "Targets" to "${config.efficiencyTargetPercent.roundToInt()}% asleep, under ${config.wasoLimitMinutes.roundToInt()} min awake",
                    ),
                ),
            )
        }
        ScoreType.LOAD -> today.load.sessions.map { s ->
            TermLine(
                label = s.title ?: "Workout",
                value = "${s.durationMinutes.roundToInt()} min",
                effect = ScoreCopy.load(s.load),
                lines = listOfNotNull(
                    "Load" to ScoreCopy.load(s.load),
                    "Counted by" to ScoreCopy.loadMethod(s.method),
                    s.reserveFraction?.let { "Heart-rate reserve" to "${(it * 100).roundToInt()}%" },
                    s.skippedReason?.let { "Not counted" to it },
                ),
                chip = ScoreCopy.provenanceLabel(s.provenance),
                citation = s.citation.takeIf { it.isNotBlank() },
            )
        }
    }
}

private fun signalTerm(s: SignalDetail, hrv: Boolean, weight: Double, earlier: List<Double>): TermLine {
    val row = s.toRowState(hrv)
    val config = ScoringConfig.DEFAULT.body
    return TermLine(
        label = row.label,
        value = row.valueText,
        effect = String.format(Locale.getDefault(), "%+.1f", s.z),
        z = s.z,
        nights = earlier,
        lines = listOfNotNull(
            "Against your normal" to row.deltaText,
            "Your normal" to row.baselineText,
            "Filtered" to "${s.filtered.roundToInt()} ${if (hrv) "ms" else "bpm"}",
            "z" to String.format(Locale.getDefault(), "%+.2f", s.z),
            "Normal from" to "${s.baselineNights} nights, the ${config.baselineDays} ending ${config.baselineLagDays} days ago",
            "Source" to s.source.substringBefore('#') + if (s.source.endsWith("#resting")) " (watch's resting HR)" else "",
            if (s.cycleAdjusted) "Cycle" to "Your luteal offset was removed first" else null,
        ),
        chip = "Share of Body ${pct(weight)}",
    )
}

/** One term: a summary line that opens to your week, the figures and the source. */
@Composable
private fun Term(term: TermLine, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val semantics = PawseTheme.semantics
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val tone = when {
        term.z == null -> grey
        abs(term.z) < ScoreCopy.NEUTRAL_Z -> semantics.neutral
        term.z > 0 -> semantics.better
        else -> semantics.worse
    }
    val turn by animateFloatAsState(if (expanded) 180f else 0f, tween(200), label = "chevron")

    Column(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(term.label, style = PawseType.Body, modifier = Modifier.weight(1f))
                Text(term.value, style = PawseType.MetricValue)
                Text(
                    term.effect,
                    style = PawseType.Delta,
                    color = tone,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(min = 48.dp),
                )
                Spacer(Modifier.width(12.dp))
                Glyph(Glyphs.ChevronDown, size = 18.dp, color = grey, modifier = Modifier.rotate(turn))
            }
            when {
                term.z != null -> {
                    Spacer(Modifier.height(10.dp))
                    BaselineBand(z = term.z.toFloat(), markerColor = tone, height = 8.dp, modifier = Modifier.fillMaxWidth())
                }
                term.part != null -> {
                    val track = semantics.baselineOuter
                    val part = term.part.toFloat().coerceIn(0f, 1f)
                    Spacer(Modifier.height(10.dp))
                    Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                        val r = CornerRadius(size.height / 2f)
                        drawRoundRect(track, cornerRadius = r)
                        drawRoundRect(grey, size = Size(size.width * part, size.height), cornerRadius = r)
                    }
                }
            }
        }

        AnimatedVisibility(expanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(Modifier.padding(bottom = 18.dp)) {
                if (term.z != null && term.nights.isNotEmpty()) {
                    val better = term.nights.count { it < term.z }
                    val rank = "Better than $better of ${term.nights.size} nights"
                    NightStrip(
                        tonight = term.z,
                        nights = term.nights,
                        tone = tone,
                        description = "This week's earlier nights as dots, last night marked. $rank.",
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.padding(bottom = 12.dp)) {
                        Text("Each dot is one of your nights", style = PawseType.Label, color = grey, modifier = Modifier.weight(1f))
                        Text(rank, style = PawseType.Label.copy(fontWeight = PawseType.BodyStrong.fontWeight))
                    }
                }
                term.lines.forEach { (k, v) -> DetailLine(k, v) }
                term.chip?.let {
                    Spacer(Modifier.height(12.dp))
                    Chip(it)
                }
                term.citation?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = PawseType.Label, color = grey)
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * Last night among this week's: each night a dot on the same ±3 SD axis as the band
 * above, stacked where they pile up, and last night as a full-height bar.
 */
@Composable
private fun NightStrip(tonight: Double, nights: List<Double>, tone: Color, description: String) {
    val band = PawseTheme.semantics.baselineOuter
    val line = MaterialTheme.colorScheme.outline
    val dot = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .semantics { contentDescription = description },
    ) {
        fun x(z: Double) = ((z.coerceIn(-3.0, 3.0) + 3.0) / 6.0).toFloat() * size.width
        val third = size.width / 3f
        drawRoundRect(band, Offset(third, 0f), Size(third, size.height), CornerRadius(4.dp.toPx()))
        drawLine(line, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), 1.dp.toPx())
        drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        val radius = 3.dp.toPx()
        val step = 7.dp.toPx()
        val piles = HashMap<Int, Int>()
        nights.forEach { z ->
            val bin = (((z.coerceIn(-3.0, 3.0) + 3.0) / 6.0 * 100.0) / 2.4).roundToInt()
            val k = piles.getOrDefault(bin, 0)
            piles[bin] = k + 1
            drawCircle(dot, radius, Offset(x(z), size.height - radius - k * step))
        }
        val tx = x(tonight)
        drawRoundRect(tone, Offset(tx - 2.dp.toPx(), 0f), Size(4.dp.toPx(), size.height), CornerRadius(2.dp.toPx()))
    }
}

@Composable
private fun BodyCards(snapshot: ScoringSnapshot) {
    val today = snapshot.today
    val body = today.body ?: return
    if (body.reasons.isNotEmpty()) {
        PawseCard {
            SectionTitle("Top reasons")
            Text(ScoreCopy.reasons(body.reasons), style = PawseType.Body)
            Spacer(Modifier.height(8.dp))
            Note("${ScoreCopy.bandMeaning(body.band)} Body ${body.score} is roughly “better than ${body.score}% of your normal days”.")
        }
    }
    today.weekly?.let { w ->
        PawseCard {
            SectionTitle("The week, not just the day")
            Text(ScoreCopy.weekly(w.pattern), style = PawseType.Body)
            Spacer(Modifier.height(6.dp))
            DetailLine("7-day HRV", "${w.hrv7.roundToInt()} ms over ${w.nights} nights")
            DetailLine("Day-to-day variation", "${fmt(w.cv7)}%" + (w.usualCv7?.let { " (usually ${fmt(it)}%)" } ?: ""))
            Spacer(Modifier.height(8.dp))
            Note("A falling weekly HRV with shrinking variation under heavy load is a warning even when no single day looks bad.")
        }
    }
    PawseCard {
        SectionTitle("Overnight heart-rate pattern")
        DetailLine("Running sum", fmt(today.illness.cusum))
        DetailLine("Quiet note above", "3")
        DetailLine("Alert above", "5, with temperature or breathing up")
        Spacer(Modifier.height(8.dp))
        Note(
            "Small excesses add up night after night, so three slightly high nights count and one random spike does not. " +
                "Logged drinks and travel or stress tags are checked first. It never names a disease.",
        )
    }
    PawseCard {
        SectionTitle("What moves your Body")
        if (snapshot.insights.isEmpty()) {
            Note("Nothing clear yet. An insight appears after at least 8 days of exposure and only when its 90% range excludes zero.")
        } else {
            snapshot.insights.forEach {
                Text(ScoreCopy.insight(it), style = PawseType.Body, modifier = Modifier.padding(vertical = 4.dp))
            }
            Spacer(Modifier.height(4.dp))
            Note(ScoreCopy.INSIGHT_NOTE)
        }
    }
}

@Composable
private fun SleepCards(snapshot: ScoringSnapshot) {
    val sleep = snapshot.today.sleep
    val config = ScoringConfig.DEFAULT.sleep
    PawseCard {
        SectionTitle("Where your need came from")
        DetailLine("Need", ScoreCopy.formatHours(sleep.needHours))
        DetailLine(
            "Learned from",
            if (sleep.needLearned) {
                "Median of ${sleep.freeNights} weekend nights, kept within ${config.minNeedHours.roundToInt()}–${config.maxNeedHours.roundToInt()} h"
            } else {
                "Default ${config.defaultNeedHours.roundToInt()} h until there are ${config.minFreeNights} weekend nights"
            },
        )
        sleep.lastNightMinutes?.let { DetailLine("Last night", ScoreCopy.formatMinutes(it)) }
        DetailLine("Debt", ScoreCopy.debt(sleep.debtHours))
    }
    PawseCard {
        SectionTitle("Last night in detail")
        sleep.efficiencyPercent?.let { DetailLine("Efficiency", "${it.roundToInt()}%") }
        sleep.wasoMinutes?.let { DetailLine("Awake after falling asleep", "${it.roundToInt()} min") }
        sleep.remMinutes?.let { DetailLine("REM", ScoreCopy.formatMinutes(it)) }
        sleep.deepMinutes?.let { DetailLine("Deep", ScoreCopy.formatMinutes(it)) }
        Spacer(Modifier.height(8.dp))
        Note(
            if (sleep.remMinutes == null) {
                "Your watch did not record stages for this night."
            } else {
                "Stages are shown, never scored. Wrist staging is the least reliable thing a watch measures."
            },
        )
    }
}

@Composable
private fun LoadCards(snapshot: ScoringSnapshot) {
    val load = snapshot.today.load
    val low = load.rangeLow
    val high = load.rangeHigh
    PawseCard {
        SectionTitle("Today’s range")
        load.typicalDayLoad?.let { DetailLine("Your typical training day", ScoreCopy.load(it)) }
        DetailLine(
            "Suggested range",
            when {
                load.rest -> "Rest: your overnight heart rate has been high"
                low != null && high != null -> "${ScoreCopy.load(low)}–${ScoreCopy.load(high)}"
                else -> "After your first few workouts"
            },
        )
        DetailLine("So far", ScoreCopy.load(load.todayLoad))
        Spacer(Modifier.height(8.dp))
        Note("Your typical day scaled by this morning's Body: Body 50 is about usual, 80 about 30% more, 15 about two thirds.")
    }
    PawseCard {
        SectionTitle("Fitness and fatigue")
        load.fitness?.let { DetailLine("Fitness", ScoreCopy.load(it)) }
        load.fatigue?.let { DetailLine("Fatigue", ScoreCopy.load(it)) }
        load.form?.let { DetailLine("Form", "${(it * 100).roundToInt()}%") }
        if (load.fitness == null) Note("Needs a few weeks of training to read.")
        if (load.sessions.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Note("No workouts logged today.")
        }
    }
}

@Composable
private fun Provenance(snapshot: ScoringSnapshot, modifier: Modifier = Modifier) {
    Column(modifier) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))
        DetailLine("Engine", snapshot.scoringVersion)
        DetailLine("Defaults", snapshot.configHash)
        Spacer(Modifier.height(8.dp))
        Note("Every number marked as a default is a reasoned starting point, not a validated value. ${ScoreCopy.FOOTER}")
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = PawseType.Heading)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(label, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(0.38f))
        Text(value, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun partText(part: Double?): String = part?.let { (it * 100).roundToInt().toString() } ?: "—"

private fun pct(fraction: Double): String = "${(fraction * 100).roundToInt()}%"

private fun fmt(v: Double): String = String.format(Locale.getDefault(), "%.2f", v)
