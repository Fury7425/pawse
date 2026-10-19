package app.pawse.feature.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pawse.core.ui.component.BaselineBand
import app.pawse.core.ui.component.Chip
import app.pawse.core.ui.component.FillBar
import app.pawse.core.ui.component.Glyph
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.PawseCard
import app.pawse.core.ui.component.QuietButton
import app.pawse.core.ui.component.ScoreRing
import app.pawse.core.ui.component.Segmented
import app.pawse.core.ui.component.rememberReducedMotion
import app.pawse.core.ui.component.reveal
import app.pawse.core.ui.theme.PawseTheme
import app.pawse.core.ui.theme.PawseType
import app.pawse.scoring.config.ScoringConfig
import app.pawse.scoring.config.StrainScale
import app.pawse.scoring.load.LoadRatioScorer
import app.pawse.scoring.model.Contribution
import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.Score
import app.pawse.scoring.model.ScoreType
import java.util.Locale
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
 * The one exception is drawing: the earlier nights a term opens to are placed on
 * the same ±3σ track the term's own marker uses, from the baseline the engine
 * stored with it. Their rank is the same count [ScoreCopy.rankText] reports.
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
    var current by rememberSaveable { mutableStateOf(type) }
    var changed by remember { mutableStateOf(false) }
    var open by rememberSaveable(current) { mutableIntStateOf(0) }
    var mathOpen by rememberSaveable(current) { mutableStateOf(false) }
    val still = rememberReducedMotion()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val score = snapshot?.score(current)
    val terms = score?.let { orderedTerms(current, it) }.orEmpty()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = top + 12.dp, bottom = bottom + 48.dp),
    ) {
        item(key = "top") {
            QuietButton("Today", onBack, Modifier.offset(x = (-12).dp), glyph = Glyphs.BACK)
            if (current in Tabs) {
                Segmented(
                    options = Tabs.map { ScoreCopy.name(it) },
                    glyphs = Tabs.map { glyphFor(it) },
                    selected = Tabs.indexOf(current),
                    onSelect = {
                        changed = true
                        current = Tabs[it]
                    },
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }

        item(key = "head-$current") {
            Column(Modifier.reveal(changed && !still)) {
                Text(
                    text = "How ${ScoreCopy.name(current)} was built",
                    style = PawseType.Headline,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 20.dp),
                )
                if (snapshot == null || score == null) {
                    Text(
                        text = snapshot?.unavailable?.get(current)
                            ?.let { ScoreCopy.unavailableSentence(current, it) }
                            ?: "There is no score to explain yet.",
                        style = PawseType.Body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                } else {
                    Summary(current, score, snapshot)
                    Combiner(current, score, snapshot, mathOpen) { mathOpen = !mathOpen }
                    Row(Modifier.padding(top = 28.dp, bottom = 2.dp)) {
                        Text(
                            termsHeader(current),
                            style = PawseType.Label,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            effectLabel(current),
                            style = PawseType.Label,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 30.dp),
                        )
                    }
                }
            }
        }

        if (snapshot != null && score != null) {
            itemsIndexed(terms, key = { _, it -> "$current-${it.metric.name}" }) { index, contribution ->
                Term(
                    type = current,
                    contribution = contribution,
                    snapshot = snapshot,
                    isOpen = open == index,
                    animate = !still,
                    onToggle = { open = if (open == index) -1 else index },
                )
            }

            if (current == ScoreType.LOAD_RATIO) {
                item(key = "critique") {
                    // The critique is not decoration. It is the condition under
                    // which this number is honest, and it ships from the engine so
                    // that a UI refactor cannot drop it.
                    PawseCard(Modifier.padding(top = 20.dp)) {
                        Text(LoadRatioScorer.CRITIQUE, style = PawseType.Body, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }

            item(key = "provenance") { Provenance(score) }
        }
    }
}

private val Tabs = listOf(ScoreType.SLEEP, ScoreType.RECOVERY, ScoreType.STRAIN)

/** The ring, its band in a dot and a word, and the three facts the number rests on. */
@Composable
private fun Summary(type: ScoreType, score: Score, snapshot: HomeSnapshot) {
    val strain = type == ScoreType.STRAIN
    val scale = snapshot.strainScale
    val ceiling = if (scale == StrainScale.WHOOP_21) 21.0 else 100.0
    val color = if (strain) MaterialTheme.colorScheme.onSurfaceVariant else bandColor(type, score.band)
    val (value, unit) = when (type) {
        ScoreType.RECOVERY -> score.value.toString() to "%"
        ScoreType.STRAIN -> ScoreCopy.strainNumber(snapshot.strainDisplay ?: score.value.toDouble(), scale) to ""
        ScoreType.LOAD_RATIO -> String.format(Locale.getDefault(), "%.2f", score.value / 100.0) to "×"
        else -> score.value.toString() to ""
    }
    val fraction = when (type) {
        ScoreType.STRAIN -> ((snapshot.strainDisplay ?: score.value.toDouble()) / ceiling).toFloat()
        ScoreType.LOAD_RATIO -> (score.value / 200f).coerceIn(0f, 1f)
        else -> score.value / 100f
    }
    val coverage = ScoreCopy.coverageLine(score).split(" · ")
    val read = if (snapshot.lastSyncEpochMs > 0) "Read at ${ScoreCopy.clock(snapshot.lastSyncEpochMs)}" else null
    val info = when (type) {
        ScoreType.SLEEP -> coverage + listOfNotNull(snapshot.sleepNeedHours?.let { "Need tonight: ${ScoreCopy.formatHours(it)}" })
        ScoreType.STRAIN -> listOfNotNull(
            snapshot.strainTargetDisplay?.let { "Target around ${ScoreCopy.strainNumber(it, scale)} today" },
            "On the ${if (scale == StrainScale.WHOOP_21) "21" else "100"} scale",
            read,
        )
        else -> coverage + listOfNotNull(read)
    } + ScoreCopy.flags(score)

    Row(
        modifier = Modifier.padding(top = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScoreRing(
            fraction = fraction,
            color = color,
            size = 112.dp,
            glow = !strain,
            targetFraction = if (strain) snapshot.strainTargetDisplay?.let { (it / ceiling).toFloat() } else null,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    value,
                    style = PawseType.Display.copy(fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-1).sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (unit.isNotEmpty()) {
                    Text(
                        unit,
                        style = PawseType.Label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 1.dp, top = 8.dp),
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(
                    Modifier
                        .size(8.dp)
                        .background(bandColor(type, score.band), CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Text(ScoreCopy.bandWord(type, score.band), style = PawseType.Action, color = MaterialTheme.colorScheme.onSurface)
            }
            info.forEach { Text(it, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** The combiner: the formula the terms go through, what it gave, and why it is shaped that way. */
@Composable
private fun Combiner(
    type: ScoreType,
    score: Score,
    snapshot: HomeSnapshot,
    mathOpen: Boolean,
    onToggleMath: () -> Unit,
) {
    val config = ScoringConfig.DEFAULT
    val scale = snapshot.strainScale
    fun raw(metric: Metric) = score.contributions.firstOrNull { it.metric == metric }?.raw
    fun pts(metric: Metric) = score.contributions.firstOrNull { it.metric == metric }?.points?.roundToInt()
    val present = score.contributions.count { it.present && !it.anomalyFlag }
    val k = String.format(Locale.getDefault(), "%.1f", config.recovery.k)
    val loadK = config.strain.saturationK.roundToInt()

    val formula = buildAnnotatedString {
        val sup = SpanStyle(fontSize = 11.sp, baselineShift = BaselineShift.Superscript)
        when (type) {
            ScoreType.RECOVERY -> {
                append("100 / (1 + e")
                withStyle(sup) { append("−$k × Z") }
                append(")")
            }
            ScoreType.STRAIN -> {
                append("${if (scale == StrainScale.WHOOP_21) "21" else "100"} × (1 − e")
                withStyle(sup) { append("−load / $loadK") }
                append(")")
            }
            ScoreType.SLEEP -> append("Σ weight × part score")
            ScoreType.ENERGY_BANK -> append("Yesterday + night − day + naps")
            ScoreType.LOAD_RATIO -> append("This week ÷ last four weeks")
        }
    }
    val (left, note) = when (type) {
        ScoreType.RECOVERY -> "$present ${if (present == 1) "term" else "terms"} against your usual" to "A usual night reads 50"
        ScoreType.SLEEP -> "$present ${if (present == 1) "part" else "parts"}" to "They add up exactly"
        ScoreType.STRAIN -> {
            val load = (raw(Metric.WORKOUT_LOAD) ?: 0.0) + (raw(Metric.PASSIVE_LOAD) ?: 0.0)
            "Load ${load.roundToInt()} TRIMP" to "A hard threshold hour reads about 30"
        }
        ScoreType.ENERGY_BANK ->
            listOfNotNull(pts(Metric.CARRYOVER), pts(Metric.OVERNIGHT_RECHARGE), pts(Metric.STRAIN_DRAIN), pts(Metric.NAP_CREDIT))
                .joinToString(" ") { if (it < 0) "− ${-it}" else "+ $it" }
                .removePrefix("+ ") to "Exact"
        ScoreType.LOAD_RATIO ->
            "${raw(Metric.ACUTE_LOAD)?.roundToInt() ?: "—"} ÷ ${raw(Metric.CHRONIC_LOAD)?.roundToInt() ?: "—"} TRIMP" to ""
    }
    val value = when (type) {
        ScoreType.STRAIN -> ScoreCopy.strainNumber(snapshot.strainDisplay ?: score.value.toDouble(), scale)
        ScoreType.LOAD_RATIO -> String.format(Locale.getDefault(), "%.2f", score.value / 100.0)
        else -> score.value.toString()
    }
    val chevron by animateFloatAsState(if (mathOpen) 180f else 0f, tween(200), label = "math")

    PawseCard(Modifier.padding(top = 20.dp), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp)) {
        Text("Combiner", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formula, style = PawseType.MetricValue, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 6.dp))
        Row(
            modifier = Modifier.padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(left, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Glyph(Glyphs.ARROW, size = 14.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            Text(note, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable(onClick = onToggleMath)
                .semantics { stateDescription = if (mathOpen) "Expanded" else "Collapsed" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("How the maths works", style = PawseType.Action, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Glyph(Glyphs.CHEVRON_DOWN, size = 18.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.rotate(chevron))
        }
        if (mathOpen) {
            Text(
                ScoreCopy.arithmeticNote(type),
                style = PawseType.Body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .reveal(true),
            )
        }
    }
}

private fun termsHeader(type: ScoreType): String = when (type) {
    ScoreType.RECOVERY -> "Terms · tap one to see your nights"
    ScoreType.SLEEP -> "Parts · against tonight's target"
    ScoreType.STRAIN -> "Where it came from"
    ScoreType.ENERGY_BANK -> "Steps · they add up exactly"
    ScoreType.LOAD_RATIO -> "The two averages"
}

private fun effectLabel(type: ScoreType): String = when (type) {
    ScoreType.RECOVERY -> "Effect"
    ScoreType.LOAD_RATIO -> ""
    else -> "Points"
}

/** Present terms first, heaviest first; Recovery keeps its flags at the end. */
private fun orderedTerms(type: ScoreType, score: Score): List<Contribution> =
    if (type == ScoreType.STRAIN || type == ScoreType.ENERGY_BANK || type == ScoreType.LOAD_RATIO) {
        score.contributions
    } else {
        score.contributions.sortedWith(
            compareByDescending<Contribution> { it.present }.thenBy { it.anomalyFlag }.thenByDescending { it.nominalWeight },
        )
    }

/** One term: its value and effect, its place against your usual, and on tap the working. */
@Composable
private fun Term(
    type: ScoreType,
    contribution: Contribution,
    snapshot: HomeSnapshot,
    isOpen: Boolean,
    animate: Boolean,
    onToggle: () -> Unit,
) {
    val semantics = PawseTheme.semantics
    val z = contribution.z
    val tone = when {
        type != ScoreType.RECOVERY || !contribution.present || z == null -> semantics.neutral
        z > ScoreCopy.NEUTRAL_Z -> semantics.better
        z < -ScoreCopy.NEUTRAL_Z -> semantics.worse
        else -> semantics.neutral
    }
    val pointsTone = if (type == ScoreType.RECOVERY) tone else MaterialTheme.colorScheme.onSurfaceVariant
    val chevron by animateFloatAsState(if (isOpen) 180f else 0f, tween(200), label = "term")
    val earlier = snapshot.nightValues[contribution.metric]

    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .semantics { stateDescription = if (isOpen) "Expanded" else "Collapsed" }
                .padding(vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ScoreCopy.metricLabel(contribution.metric),
                    style = PawseType.Body,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    contribution.raw?.let { ScoreCopy.formatRaw(contribution.metric, it) } ?: "—",
                    style = PawseType.MetricValue,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 12.dp),
                )
                Text(
                    pointsFor(type, contribution, snapshot.strainScale),
                    style = PawseType.Delta,
                    color = pointsTone,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .widthIn(min = 48.dp),
                )
                Glyph(
                    Glyphs.CHEVRON_DOWN,
                    size = 18.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .rotate(chevron),
                )
            }
            when {
                type == ScoreType.SLEEP -> FillBar(
                    fraction = (ScoreCopy.subScore(contribution) ?: 0) / 100f,
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .alpha(if (contribution.present) 1f else 0.45f),
                )
                z != null || contribution.baselineMean != null -> BaselineBand(
                    z = z?.toFloat(),
                    markerColor = tone,
                    height = 8.dp,
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .fillMaxWidth()
                        .alpha(if (contribution.present) 1f else 0.45f),
                )
            }
        }

        if (isOpen) {
            Column(
                Modifier
                    .padding(bottom = 18.dp)
                    .reveal(animate),
            ) {
                if (z != null && !earlier.isNullOrEmpty() && contribution.baselineMean != null) {
                    NightsStrip(contribution, earlier, tone)
                    Row(Modifier.padding(top = 6.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Each dot is one of your nights",
                            style = PawseType.Label,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        ScoreCopy.rankText(contribution, earlier)?.let {
                            Text(it, style = PawseType.Label.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                termLines(type, contribution, snapshot).forEach { (k, v) -> DetailLine(k, v) }
                Chip(ScoreCopy.provenanceLabel(contribution.provenance), Modifier.padding(top = 12.dp))
                if (contribution.citation.isNotBlank()) {
                    Text(
                        contribution.citation,
                        style = PawseType.Label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private fun pointsFor(type: ScoreType, c: Contribution, scale: StrainScale): String {
    if (!c.present) return ""
    return when (type) {
        ScoreType.RECOVERY -> ScoreCopy.pointsText(c).orEmpty()
        ScoreType.LOAD_RATIO -> ""
        ScoreType.STRAIN -> {
            val p = if (scale == StrainScale.WHOOP_21) c.points * 21.0 / 100.0 else c.points
            (if (p > 0) "+" else "") + ScoreCopy.strainNumber(p, scale)
        }
        else -> c.points.roundToInt().let { if (it > 0) "+$it" else if (it < 0) "−${-it}" else "0" }
    }
}

/** The lines a term opens to. Every value is one the engine stored. */
private fun termLines(type: ScoreType, c: Contribution, snapshot: HomeSnapshot): List<Pair<String, String>> {
    val nominal = "${(c.nominalWeight * 100).roundToInt()}%"
    return when (type) {
        ScoreType.SLEEP -> buildList {
            ScoreCopy.subScore(c)?.let { add("Part score" to "$it / 100") }
            add("Against target" to ScoreCopy.sleepNote(c, snapshot.sleepNeedHours))
            add("Weight" to nominal)
            add("Points" to if (c.present) "+${c.points.roundToInt()} of ${(c.nominalWeight * 100).roundToInt()}" else "Not recorded, so its weight was shared out across the rest")
            if (c.metric == Metric.TIME_ASLEEP) {
                snapshot.sleepNeedBaselineHours?.let { add("Your baseline need" to ScoreCopy.formatHours(it)) }
                snapshot.sleepNeedStrainAdderHours?.takeIf { it > 0 }?.let { add("Yesterday's strain" to "+ ${ScoreCopy.formatHours(it)}") }
                snapshot.sleepNeedDebtAdderHours?.takeIf { it > 0 }?.let { add("Recent debt" to "+ ${ScoreCopy.formatHours(it)}") }
                snapshot.sleepNeedNapCreditHours?.takeIf { it > 0 }?.let { add("Naps" to "− ${ScoreCopy.formatHours(it)}") }
            }
        }

        ScoreType.STRAIN -> buildList {
            if (c.metric == Metric.WORKOUT_LOAD) {
                if (snapshot.sessionLoads.isEmpty()) add("Sessions" to "None logged yet today")
                snapshot.sessionLoads.forEach { s ->
                    val name = "${s.title ?: "Workout"}, ${s.durationMinutes.roundToInt()} min"
                    add(
                        name to if (s.scored) {
                            val alone = if (snapshot.strainScale == StrainScale.WHOOP_21) s.strainIfAlone * 21.0 / 100.0 else s.strainIfAlone
                            "${s.load.roundToInt()} TRIMP · on its own ${ScoreCopy.strainNumber(alone, snapshot.strainScale)}" +
                                (s.reserveFraction?.let { " · ${(it * 100).roundToInt()}% of reserve" } ?: "")
                        } else {
                            "Not scored: ${s.skippedReason ?: "not enough data"}"
                        },
                    )
                }
            }
            add("Load" to (c.raw?.let { ScoreCopy.formatRaw(c.metric, it) } ?: "0 TRIMP"))
            add("Effect on the score" to pointsFor(type, c, snapshot.strainScale).ifEmpty { "0" })
        }

        ScoreType.ENERGY_BANK, ScoreType.LOAD_RATIO -> buildList {
            add("Value" to (c.raw?.let { ScoreCopy.formatRaw(c.metric, it) } ?: "—"))
            if (type == ScoreType.ENERGY_BANK) add("Effect on the gauge" to pointsFor(type, c, snapshot.strainScale).ifEmpty { "—" })
        }

        ScoreType.RECOVERY -> buildList {
            add("Against your usual" to ScoreCopy.deltaText(c))
            add("Baseline" to ScoreCopy.baselineText(c))
            c.z?.let { add("z" to String.format(Locale.getDefault(), "%+.2f", it)) }
            add("Weight" to ScoreCopy.weightText(c))
            add(
                (if (c.anomalyFlag) "Flag" else "Effect on the score") to
                    if (!c.present) "Not recorded, so its weight was shared out across the rest" else ScoreCopy.pointsText(c) ?: "—",
            )
            if (!c.present) {
                add("What to do" to "Check that your watch app is allowed to write ${ScoreCopy.metricLabel(c.metric).lowercase(Locale.getDefault())} to Health Connect")
            }
        }
    }
}

/**
 * Your earlier nights as dots on the term's own track, stacked where they crowd,
 * with tonight as the tall marker.
 */
@Composable
private fun NightsStrip(contribution: Contribution, earlier: List<Double>, tone: Color) {
    val mean = contribution.baselineMean ?: return
    val sd = contribution.baselineSd?.takeIf { it > 0 } ?: return
    val direction = if (contribution.metric.direction == 0) 1 else contribution.metric.direction
    val outer = PawseTheme.semantics.baselineOuter
    val line = MaterialTheme.colorScheme.outline
    val dot = MaterialTheme.colorScheme.onSurfaceVariant
    val tonight = ((contribution.z ?: 0.0).coerceIn(-3.0, 3.0) + 3.0) / 6.0
    val positions = earlier.map { (((it - mean) / sd * direction).coerceIn(-3.0, 3.0) + 3.0) / 6.0 }
    val description = "Your last ${earlier.size} nights as dots, tonight marked. " +
        (ScoreCopy.rankText(contribution, earlier) ?: "")

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .semantics { contentDescription = description },
    ) {
        val w = size.width
        val h = size.height
        drawRoundRect(outer, Offset(w / 3f, 0f), Size(w / 3f, h), CornerRadius(4.dp.toPx()))
        drawLine(line, Offset(w / 2f, 0f), Offset(w / 2f, h), strokeWidth = 1.dp.toPx())
        drawLine(line, Offset(0f, h), Offset(w, h), strokeWidth = 1.dp.toPx())
        val r = 3.dp.toPx()
        val stacks = mutableMapOf<Int, Int>()
        positions.forEach { p ->
            val bin = (p * 100 / 2.4).roundToInt()
            val k = stacks[bin] ?: 0
            stacks[bin] = k + 1
            val cy = h - r - k * 7.dp.toPx()
            if (cy >= r) drawCircle(dot, radius = r, center = Offset((p * w).toFloat(), cy), alpha = 0.55f)
        }
        val x = (tonight * w).toFloat().coerceIn(2.dp.toPx(), w - 2.dp.toPx())
        drawRoundRect(tone, Offset(x - 2.dp.toPx(), 0f), Size(4.dp.toPx(), h), CornerRadius(2.dp.toPx()))
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
private fun Provenance(score: Score) {
    Column(Modifier.padding(top = 28.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(16.dp))
        DetailLine("Engine", score.scoringVersion)
        DetailLine("Weights", score.configHash)
        Text(
            text = "Both travel with every stored score. Changing a weight writes a new row " +
                "rather than rewriting this one, so a number you were shown last month stays " +
                "the number you were shown last month.",
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private fun glyphFor(type: ScoreType): String = when (type) {
    ScoreType.SLEEP -> Glyphs.SLEEP
    ScoreType.RECOVERY -> Glyphs.RECOVERY
    ScoreType.STRAIN -> Glyphs.STRAIN
    ScoreType.ENERGY_BANK -> Glyphs.ENERGY
    ScoreType.LOAD_RATIO -> Glyphs.LOAD
}

