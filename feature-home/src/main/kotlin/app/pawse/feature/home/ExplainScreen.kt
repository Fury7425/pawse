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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import app.pawse.scoring.config.StrainScale
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
 * was shown. (The dot strip only places earlier nights on tonight's axis.)
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
    var shown by rememberSaveable { mutableStateOf(type) }
    var open by rememberSaveable(shown) { mutableIntStateOf(0) }
    var mathOpen by rememberSaveable(shown) { mutableStateOf(false) }
    val pad = Modifier.padding(horizontal = 24.dp)
    val rings = listOf(ScoreType.SLEEP, ScoreType.RECOVERY, ScoreType.STRAIN)

    Scaffold(modifier = modifier, containerColor = MaterialTheme.colorScheme.background) { padding ->
        val score = snapshot?.score(shown)
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
                    if (shown in rings) {
                        Spacer(Modifier.height(12.dp))
                        Segmented(
                            options = rings,
                            selected = shown,
                            onSelect = { shown = it },
                            label = ScoreCopy::name,
                            glyph = ::scoreGlyph,
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Text("How ${ScoreCopy.name(shown)} was built", style = PawseType.Title)
                    Spacer(Modifier.height(20.dp))
                }
            }

            if (snapshot == null || score == null) {
                item {
                    Text(
                        text = snapshot?.unavailable?.get(shown)
                            ?.let { ScoreCopy.unavailableSentence(shown, it) }
                            ?: "There is no score to explain yet.",
                        style = PawseType.Body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = pad,
                    )
                }
                return@LazyColumn
            }

            item {
                Summary(type = shown, score = score, snapshot = snapshot, modifier = pad)
                Spacer(Modifier.height(20.dp))
                Combiner(
                    type = shown,
                    score = score,
                    snapshot = snapshot,
                    mathOpen = mathOpen,
                    onToggleMath = { mathOpen = !mathOpen },
                    modifier = pad,
                )
            }

            if (shown == ScoreType.LOAD_RATIO) {
                item {
                    // The critique is not decoration. It is the condition under
                    // which this number is honest, and it ships from the engine so
                    // that a UI refactor cannot drop it.
                    PawseCard(modifier = pad.padding(top = 12.dp)) {
                        Text(LoadRatioScorer.CRITIQUE, style = PawseType.Label)
                    }
                }
            }

            item {
                Row(pad.padding(top = 28.dp, bottom = 2.dp)) {
                    Text(termsHeader(shown), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(
                        if (shown == ScoreType.RECOVERY) "Effect" else "Points",
                        style = PawseType.Label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 30.dp),
                    )
                }
            }

            itemsIndexed(score.contributions, key = { _, c -> "${shown}-${c.metric}" }) { i, contribution ->
                Term(
                    type = shown,
                    contribution = contribution,
                    earlier = snapshot.nightValues[contribution.metric],
                    expanded = open == i,
                    onToggle = { open = if (open == i) -1 else i },
                    modifier = pad,
                )
            }

            when (shown) {
                ScoreType.SLEEP -> item { SleepNeedBreakdown(snapshot, pad.padding(top = 28.dp)) }
                ScoreType.STRAIN -> item { StrainDetail(snapshot, pad.padding(top = 28.dp)) }
                ScoreType.ENERGY_BANK -> item { EnergyArithmetic(score, pad.padding(top = 28.dp)) }
                else -> Unit
            }

            item { Provenance(score, pad.padding(top = 28.dp)) }
        }
    }
}

@Composable
private fun Summary(type: ScoreType, score: Score, snapshot: HomeSnapshot, modifier: Modifier = Modifier) {
    val strain = type == ScoreType.STRAIN
    val scaleMax = if (snapshot.strainScale == StrainScale.WHOOP_21) 21.0 else 100.0
    val dot = bandColor(type, score.band)
    val value = when (type) {
        ScoreType.STRAIN -> strainNumber(snapshot.strainDisplay ?: score.value.toDouble(), snapshot.strainScale)
        ScoreType.LOAD_RATIO -> String.format(Locale.getDefault(), "%.2f", score.value / 100.0)
        else -> score.value.toString()
    }
    val unit = when (type) {
        ScoreType.RECOVERY -> "%"
        ScoreType.LOAD_RATIO -> "×"
        else -> ""
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        ScoreRing(
            fraction = if (type == ScoreType.LOAD_RATIO) (score.value / 200f) else score.value / 100f,
            color = if (strain) MaterialTheme.colorScheme.onSurfaceVariant else dot,
            size = 112.dp,
            glow = !strain,
            target = if (strain) snapshot.strainTargetDisplay?.let { (it / scaleMax).toFloat() } else null,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value, style = PawseType.Numeral)
                if (unit.isNotEmpty()) {
                    Text(unit, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        Spacer(Modifier.width(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(8.dp)) { drawCircle(dot) }
                Spacer(Modifier.width(8.dp))
                Text(ScoreCopy.bandWord(type, score.band), style = PawseType.BodyStrong)
            }
            Info(inputsLine(score))
            Info("${(score.dataCoverage * 100).roundToInt()}% of the weight")
            when {
                strain && snapshot.strainTargetDisplay != null ->
                    Info("Target around ${strainNumber(snapshot.strainTargetDisplay, snapshot.strainScale)} today")
                type == ScoreType.SLEEP && snapshot.sleepNeedHours != null ->
                    Info("Need tonight: ${ScoreCopy.formatHours(snapshot.sleepNeedHours)}")
                snapshot.lastSyncEpochMs > 0 -> Info("Read at ${ScoreCopy.clock(snapshot.lastSyncEpochMs)}")
            }
            ScoreCopy.flags(score).forEach { Info(it) }
        }
    }
}

@Composable
private fun Info(text: String) {
    Text(text, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The formula that turns terms into the score, and what it gave. */
@Composable
private fun Combiner(
    type: ScoreType,
    score: Score,
    snapshot: HomeSnapshot,
    mathOpen: Boolean,
    onToggleMath: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sup = SpanStyle(fontSize = 11.sp, baselineShift = BaselineShift.Superscript)
    val formula = buildAnnotatedString {
        when (type) {
            ScoreType.RECOVERY -> {
                append("100 / (1 + e")
                withStyle(sup) { append("−1.1 × Z") }
                append(")")
            }
            ScoreType.STRAIN -> {
                append("100 × (1 − e")
                withStyle(sup) { append("−load / 260") }
                append(")")
            }
            ScoreType.SLEEP -> append("Σ weight × part score")
            ScoreType.ENERGY_BANK -> append("Leftover + recharge − spent + naps")
            ScoreType.LOAD_RATIO -> append("This week ÷ last four weeks")
        }
    }
    val (left, note) = when (type) {
        ScoreType.RECOVERY -> "Z, the weighted deviations" to "A usual night reads 50"
        ScoreType.SLEEP -> "${score.contributions.count { it.present }} parts" to "They add up exactly"
        ScoreType.STRAIN -> {
            val load = score.contributions.mapNotNull { it.raw }.sum()
            "Load ${load.roundToInt()} TRIMP" to "Saturates, never adds"
        }
        ScoreType.ENERGY_BANK -> "Realised changes" to "Resists near both ends"
        ScoreType.LOAD_RATIO -> "Two averages" to "Only the middle is good"
    }
    val result = when (type) {
        ScoreType.STRAIN -> strainNumber(snapshot.strainDisplay ?: score.value.toDouble(), snapshot.strainScale)
        ScoreType.LOAD_RATIO -> String.format(Locale.getDefault(), "%.2f×", score.value / 100.0)
        else -> score.value.toString()
    }
    val turn by animateFloatAsState(if (mathOpen) 180f else 0f, tween(200), label = "chevron")

    PawseCard(modifier = modifier, padding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
        Text("Combiner", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text(formula, style = PawseType.MetricValue)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(left, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Glyph(Glyphs.Arrow, size = 14.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(result, style = PawseType.Label)
            Text(note, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
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
                ScoreCopy.arithmeticNote(type),
                style = PawseType.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
    }
}

private fun termsHeader(type: ScoreType): String = when (type) {
    ScoreType.RECOVERY -> "Terms · tap one to see your nights"
    ScoreType.SLEEP -> "Parts · against tonight’s target"
    ScoreType.STRAIN -> "Where it came from"
    ScoreType.ENERGY_BANK -> "Steps through the day"
    ScoreType.LOAD_RATIO -> "The two averages"
}

/** One term: a summary line that opens to your nights, the figures and the source. */
@Composable
private fun Term(
    type: ScoreType,
    contribution: Contribution,
    earlier: List<Double>?,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantics = PawseTheme.semantics
    val row = contribution.toRowState()
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val tone = when {
        type != ScoreType.RECOVERY -> grey
        !contribution.present -> semantics.neutral
        (contribution.z ?: 0.0) > ScoreCopy.NEUTRAL_Z -> semantics.better
        (contribution.z ?: 0.0) < -ScoreCopy.NEUTRAL_Z -> semantics.worse
        else -> semantics.neutral
    }
    val sleepPart = type == ScoreType.SLEEP && contribution.weight > 0.0 && contribution.present
    val turn by animateFloatAsState(if (expanded) 180f else 0f, tween(200), label = "chevron")

    Column(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.label, style = PawseType.Body, modifier = Modifier.weight(1f))
                Text(row.valueText, style = PawseType.MetricValue)
                Text(
                    row.pointsText?.let { if (type == ScoreType.RECOVERY) it else it.removeSuffix(" pts") } ?: "",
                    style = PawseType.Delta,
                    color = tone,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(min = 48.dp),
                )
                Spacer(Modifier.width(12.dp))
                Glyph(Glyphs.ChevronDown, size = 18.dp, color = grey, modifier = Modifier.rotate(turn))
            }
            when {
                contribution.z != null || (type == ScoreType.RECOVERY && contribution.baselineMean != null) -> {
                    Spacer(Modifier.height(10.dp))
                    BaselineBand(z = contribution.z?.toFloat(), markerColor = tone, height = 8.dp, modifier = Modifier.fillMaxWidth())
                }
                sleepPart -> {
                    val part = (contribution.points / contribution.weight).coerceIn(0.0, 100.0).toFloat() / 100f
                    val track = semantics.baselineOuter
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
                val rank = ScoreCopy.rankText(contribution, earlier)
                val z = contribution.z
                val mean = contribution.baselineMean
                val sd = contribution.baselineSd
                if (z != null && mean != null && sd != null && sd > 0.0 && !earlier.isNullOrEmpty() && !contribution.metric.deviationOnly) {
                    NightStrip(
                        tonight = z,
                        nights = earlier.map { ((it - mean) / sd * contribution.metric.direction).coerceIn(-3.0, 3.0) },
                        tone = tone,
                        description = "Your last ${earlier.size} nights as dots, tonight marked." + (rank?.let { " $it." } ?: ""),
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.padding(bottom = 12.dp)) {
                        Text("Each dot is one of your nights", style = PawseType.Label, color = grey, modifier = Modifier.weight(1f))
                        rank?.let { Text(it, style = PawseType.Label.copy(fontWeight = PawseType.BodyStrong.fontWeight)) }
                    }
                }
                if (type == ScoreType.RECOVERY || contribution.baselineMean != null) {
                    DetailLine("Against your usual", row.deltaText)
                    DetailLine("Baseline", row.baselineText)
                }
                contribution.z?.let { DetailLine("z", String.format(Locale.getDefault(), "%+.2f", it)) }
                if (sleepPart) {
                    DetailLine("Part score", "${(contribution.points / contribution.weight).coerceIn(0.0, 100.0).roundToInt()} / 100")
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
                Spacer(Modifier.height(12.dp))
                Chip(text = ScoreCopy.provenanceLabel(contribution.provenance))
                if (contribution.citation.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(contribution.citation, style = PawseType.Label, color = grey)
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * Tonight among your earlier nights: each night a dot on the same ±3 SD axis as the
 * band above, stacked where they pile up, and tonight as a full-height bar.
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
        fun x(z: Double) = ((z + 3.0) / 6.0).toFloat() * size.width
        val third = size.width / 3f
        drawRoundRect(band, Offset(third, 0f), Size(third, size.height), CornerRadius(4.dp.toPx()))
        drawLine(line, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), 1.dp.toPx())
        drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        val radius = 3.dp.toPx()
        val step = 7.dp.toPx()
        val piles = HashMap<Int, Int>()
        nights.forEach { z ->
            val bin = (((z + 3.0) / 6.0 * 100.0) / 2.4).roundToInt()
            val k = piles.getOrDefault(bin, 0)
            piles[bin] = k + 1
            drawCircle(dot, radius, Offset(x(z), size.height - radius - k * step))
        }
        val tx = x(tonight.coerceIn(-3.0, 3.0))
        drawRoundRect(tone, Offset(tx - 2.dp.toPx(), 0f), Size(4.dp.toPx(), size.height), CornerRadius(2.dp.toPx()))
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
        Text(text = value, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = PawseType.Heading)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun Note(text: String) {
    Text(text, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SleepNeedBreakdown(snapshot: HomeSnapshot, modifier: Modifier = Modifier) {
    val total = snapshot.sleepNeedHours ?: return
    PawseCard(modifier) {
        SectionTitle("Tonight’s sleep need")
        snapshot.sleepNeedBaselineHours?.let { DetailLine("Your baseline", ScoreCopy.formatHours(it)) }
        snapshot.sleepNeedStrainAdderHours?.let { DetailLine("Added by yesterday’s strain", "+ ${ScoreCopy.formatHours(it)}") }
        snapshot.sleepNeedDebtAdderHours?.let { DetailLine("Added by recent debt", "+ ${ScoreCopy.formatHours(it)}") }
        snapshot.sleepNeedNapCreditHours?.let {
            if (it > 0.0) DetailLine("Credited by naps", "− ${ScoreCopy.formatHours(it)}")
        }
        DetailLine("Total", ScoreCopy.formatHours(total))
        Spacer(Modifier.height(10.dp))
        Note(
            "The strain term is Whoop's published logistic, " +
                "f(i) = 1.7 / (1 + e^((17 − i) / 3.5)) hours, from patent US 9,538,923. " +
                "Sleep need is never a static eight hours.",
        )
    }
}

@Composable
private fun StrainDetail(snapshot: HomeSnapshot, modifier: Modifier = Modifier) {
    Column(modifier) {
        SectionTitle("Today’s sessions")
        if (snapshot.sessionLoads.isEmpty()) {
            Note("No workouts logged today. The strain shown is the passive term alone.")
        } else {
            snapshot.sessionLoads.forEach { SessionLine(it) }
            Spacer(Modifier.height(10.dp))
            Note(
                "Sessions do not add up. Load is squashed through a saturating curve, " +
                    "so a second session always adds less than it would have on its own — " +
                    "which is why each line shows what it alone would have scored.",
            )
        }

        snapshot.strainTargetDisplay?.let { target ->
            Spacer(Modifier.height(20.dp))
            PawseCard {
                SectionTitle("Target strain")
                DetailLine("Target for today", ScoreCopy.formatStrain(target, snapshot.strainScale))
                snapshot.strainTypicalDisplay?.let {
                    DetailLine("Your recent typical", ScoreCopy.formatStrain(it, snapshot.strainScale))
                }
                Spacer(Modifier.height(8.dp))
                Note(
                    "About two weeks of your typical strain, moved by this morning's " +
                        "Recovery. Bevel describes that pairing and publishes neither half of it, " +
                        "so the window is theirs and the coupling strength is ours.",
                )
            }
        }
    }
}

@Composable
private fun SessionLine(session: SessionLoad) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Row {
            Text(session.title ?: "Workout", style = PawseType.Body, modifier = Modifier.weight(1f))
            Text("${session.durationMinutes.roundToInt()} min", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        if (session.scored) {
            DetailLine("Load", "${session.load.roundToInt()} TRIMP")
            DetailLine("On its own", session.strainIfAlone.roundToInt().toString())
            session.reserveFraction?.let { DetailLine("Heart-rate reserve", "${(it * 100).roundToInt()}%") }
        } else {
            DetailLine("Not scored", session.skippedReason ?: "Not enough data.")
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun EnergyArithmetic(score: Score, modifier: Modifier = Modifier) {
    fun points(metric: Metric) = score.contributions.firstOrNull { it.metric == metric }?.points
    val carryover = points(Metric.CARRYOVER) ?: return
    val recharge = points(Metric.OVERNIGHT_RECHARGE) ?: 0.0
    val drain = points(Metric.STRAIN_DRAIN) ?: 0.0
    val nap = points(Metric.NAP_CREDIT) ?: 0.0

    PawseCard(modifier) {
        SectionTitle("It adds up")
        Text(
            text = "${carryover.roundToInt()} + ${recharge.roundToInt()} − " +
                "${abs(drain).roundToInt()} + ${nap.roundToInt()} = ${score.value}",
            style = PawseType.MetricValue,
        )
        Spacer(Modifier.height(8.dp))
        Note(
            "These are realised changes rather than requested ones: the gauge resists " +
                "near the top and bottom of its range, so what a great night asked for and " +
                "what it actually put back are different numbers. The one shown is what landed.",
        )
    }
}

@Composable
private fun Provenance(score: Score, modifier: Modifier = Modifier) {
    Column(modifier) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))
        DetailLine("Engine", score.scoringVersion)
        DetailLine("Weights", score.configHash)
        Spacer(Modifier.height(8.dp))
        Note(
            "Both travel with every stored score. Changing a weight writes a new row " +
                "rather than rewriting this one, so a number you were shown last month stays " +
                "the number you were shown last month.",
        )
    }
}
