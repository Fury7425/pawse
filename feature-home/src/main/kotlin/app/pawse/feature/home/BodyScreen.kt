package app.pawse.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pawse.core.data.body.BodyComposition
import app.pawse.core.data.body.BodyRow
import app.pawse.core.data.body.BodySnapshot
import app.pawse.core.data.body.EnergyToday
import app.pawse.core.ui.component.BaselineBandRow
import app.pawse.core.ui.component.BaselineRowState
import app.pawse.core.ui.component.Chip
import app.pawse.core.ui.component.DeviationDirection
import app.pawse.core.ui.component.GhostButton
import app.pawse.core.ui.component.Glyph
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.KeySwatch
import app.pawse.core.ui.component.PawseCard
import app.pawse.core.ui.component.Segmented
import app.pawse.core.ui.component.SplitBar
import app.pawse.core.ui.component.UsualRangeKey
import app.pawse.core.ui.component.cardSurface
import app.pawse.core.ui.component.pressable
import app.pawse.core.ui.theme.PawseTheme
import app.pawse.core.ui.theme.PawseType
import app.pawse.scoring.body.BmrResult
import app.pawse.scoring.body.BmrSource
import app.pawse.scoring.model.Metric
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Body: what the scale wrote, against your own usual range, and today's energy.
 *
 * Every change stays grey. Up or down is not good or bad on its own, so the screen
 * says which way and by how much, and leaves the verdict to the person.
 */
@Composable
fun BodyScreen(
    onAddWeighIn: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BodyViewModel = hiltViewModel(),
) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val range by viewModel.range.collectAsStateWithLifecycle()
    val ownPackage = LocalContext.current.packageName
    val typedIn = snapshot?.weight?.sourceApp == ownPackage

    LaunchedEffect(Unit) { viewModel.reload() }

    Scaffold(modifier = modifier, containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 24.dp,
                end = 24.dp,
                top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + 140.dp,
            ),
        ) {
            item {
                Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Body", style = PawseType.Title, modifier = Modifier.weight(1f))
                    Row(
                        Modifier
                            .height(44.dp)
                            .pressable(onClick = onAddWeighIn)
                            .cardSurface(RoundedCornerShape(22.dp), 22.dp)
                            .padding(start = 10.dp, end = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Glyph(Glyphs.Plus, size = 18.dp, strokeWidth = 2f, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.width(6.dp))
                        Text("Weigh-in", style = PawseType.BodyStrong)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Glyph(
                        if (snapshot?.weighedToday == true) Glyphs.Check else Glyphs.Clock,
                        size = 14.dp,
                        strokeWidth = 2f,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = snapshot?.let { statusLine(it, typedIn) } ?: "Reading your scale",
                        style = PawseType.Label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(24.dp))
            }

            val s = snapshot ?: return@LazyColumn
            if (!s.hasAnyData) {
                item {
                    PawseCard {
                        Text("No scale readings yet", style = PawseType.Heading)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "When a scale writes weight to Health Connect, it shows here on its own. " +
                                "No scale that does? Type a weigh-in in.",
                            style = PawseType.Label,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        GhostButton("Add a weigh-in", onClick = onAddWeighIn, glyph = Glyphs.Plus)
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }

            s.weight?.let {
                item {
                    WeightSection(s, range, viewModel::setRange, typedIn)
                    Spacer(Modifier.height(28.dp))
                }
            }

            s.composition?.let { composition ->
                item {
                    CompositionCard(composition, typedIn)
                    Spacer(Modifier.height(28.dp))
                }
            }

            if (s.rows.isNotEmpty()) {
                item {
                    Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Against your usual · ${s.weighInsLast30Days} weigh-ins",
                            style = PawseType.Label,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        UsualRangeKey("Usual range")
                    }
                }
                s.rows.forEach { row ->
                    item {
                        BaselineBandRow(state = row.toRowState())
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                item {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Body changes stay grey. Up or down isn't good or bad on its own, so Pawse says " +
                            "which way and by how much, and leaves the verdict to you.",
                        style = PawseType.Label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(28.dp))
                }
            }

            item {
                EnergyCard(s.energy)
                Spacer(Modifier.height(16.dp))
            }

            s.bmr?.let { bmr ->
                item {
                    BmrCard(bmr, s.composition?.leanKg)
                    Spacer(Modifier.height(24.dp))
                }
            }

            item {
                Text(
                    "Scales estimate composition from a small electric current, so water and a meal " +
                        "can move it by a percent or two. Weigh at the same time each day for the " +
                        "steadiest numbers. Not a medical device.",
                    style = PawseType.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun statusLine(s: BodySnapshot, typedIn: Boolean): String {
    val weight = s.weight ?: return "No weigh-ins yet"
    val days = s.daysSinceWeighIn ?: 0L
    return if (days == 0L) {
        "Weighed today at ${ScoreCopy.clock(weight.atEpochMs)}, " + if (typedIn) "typed in" else "from your scale"
    } else {
        val date = weight.date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
        "Last weighed $date, ${if (days == 1L) "yesterday" else "$days days ago"}"
    }
}

@Composable
private fun WeightSection(s: BodySnapshot, range: BodyRange, onRange: (BodyRange) -> Unit, typedIn: Boolean) {
    val weight = s.weight ?: return
    Text("Weight", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.alpha(if (s.weighedToday) 1f else 0.6f)) {
        Text(oneDecimal(weight.value), style = PawseType.Hero)
        Spacer(Modifier.width(6.dp))
        Text(
            "kg",
            style = PawseType.Heading.copy(fontWeight = PawseType.Body.fontWeight),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(weightLine(s), style = PawseType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(10.dp))
    val days = s.daysSinceWeighIn ?: 0L
    Chip(
        when {
            days == 0L -> if (typedIn) "Typed in" else "Measured"
            days == 1L -> "A day old"
            else -> "$days days old"
        },
    )
    Spacer(Modifier.height(20.dp))

    Segmented(
        options = BodyRange.entries,
        selected = range,
        onSelect = onRange,
        label = { it.label },
        height = 36.dp,
    )
    Spacer(Modifier.height(16.dp))
    WeightChart(s, range)
}

private fun weightLine(s: BodySnapshot): String {
    val weight = s.weight ?: return ""
    if (!s.weighedToday) return "Step on the scale and it updates here on its own, or add a weigh-in."
    val parts = buildList {
        s.weightUsual?.let { add(kgDelta(weight.value - it.mean, "your 30-day usual")) }
        s.weightYearAgo?.let { add(kgDelta(weight.value - it, "a year ago")) }
    }
    return if (parts.isEmpty()) "Pawse needs a few more weigh-ins to know your usual." else parts.joinToString(", and ") + "."
}

private fun kgDelta(delta: Double, against: String): String = when {
    abs(delta) < 0.05 -> "About the same as $against"
    delta > 0 -> "${oneDecimal(delta)} kg over $against"
    else -> "${oneDecimal(-delta)} kg under $against"
}.replaceFirstChar { it.uppercase() }

@Composable
private fun WeightChart(s: BodySnapshot, range: BodyRange) {
    val weight = s.weight ?: return
    val points = s.weightSeries.filter { !it.date.isBefore(s.today.minusDays(range.days)) }
    val band = PawseTheme.semantics.baselineBand
    val line = MaterialTheme.colorScheme.onSurfaceVariant
    val last = MaterialTheme.colorScheme.onSurface
    val ring = MaterialTheme.colorScheme.background
    val usual = s.weightUsual
    val values = points.map { it.value } + listOfNotNull(usual?.let { it.mean - it.sigma }, usual?.let { it.mean + it.sigma })
    val lo = (values.minOrNull() ?: weight.value) - 0.3
    val hi = (values.maxOrNull() ?: weight.value) + 0.3
    val description = "Weight over the last ${range.label}, ending at ${oneDecimal(weight.value)} kg."

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .semantics { contentDescription = description },
    ) {
        val pad = 6.dp.toPx()
        fun y(v: Double) = ((size.height - pad) - (v - lo) / (hi - lo) * (size.height - 2 * pad)).toFloat()
        val first = s.today.minusDays(range.days)
        fun x(date: java.time.LocalDate) =
            (java.time.temporal.ChronoUnit.DAYS.between(first, date).toFloat() / range.days) * size.width

        usual?.let {
            val top = y(it.mean + it.sigma)
            drawRoundRect(band, Offset(0f, top), Size(size.width, y(it.mean - it.sigma) - top), CornerRadius(4.dp.toPx()))
        }
        if (points.size > 1) {
            val path = Path()
            points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.date), y(p.value)) else path.lineTo(x(p.date), y(p.value)) }
            drawPath(path, color = line, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        points.dropLast(1).forEach { drawCircle(color = line, radius = 2.5.dp.toPx(), center = Offset(x(it.date), y(it.value))) }
        points.lastOrNull()?.let {
            val c = Offset(x(it.date), y(it.value))
            drawCircle(color = ring, radius = 8.dp.toPx(), center = c)
            drawCircle(color = last, radius = 5.dp.toPx(), center = c)
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            when (range) {
                BodyRange.DAYS_30 -> "30 days ago"
                BodyRange.DAYS_90 -> "90 days ago"
                BodyRange.YEAR -> "A year ago"
            },
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        if (usual != null) {
            UsualRangeKey()
            Spacer(Modifier.weight(1f))
        }
        Text("Today", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CompositionCard(c: BodyComposition, typedIn: Boolean) {
    val parts = listOfNotNull(
        c.waterKg?.let { "Water" to it },
        c.otherLeanKg?.let { (if (c.waterKg == null && c.boneKg == null) "Lean" else "Other lean") to it },
        c.boneKg?.let { "Bone" to it },
        c.fatKg?.let { "Fat" to it },
    )
    val fills = listOf(
        MaterialTheme.colorScheme.onSurface,
        MaterialTheme.colorScheme.onSurfaceVariant,
        MaterialTheme.colorScheme.outline,
        PawseTheme.semantics.baselineBand,
    )
    PawseCard {
        Row {
            Text("What the ${oneDecimal(c.weightKg)} kg is made of", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(if (typedIn) "Typed in" else "From your scale", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        SplitBar(parts.mapIndexed { i, (_, kg) -> kg.toFloat() to fills[i] }, height = 10.dp)
        Spacer(Modifier.height(14.dp))
        parts.chunked(2).forEachIndexed { row, pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                pair.forEachIndexed { col, (name, kg) ->
                    Column(Modifier.weight(1f).padding(bottom = 12.dp)) {
                        KeySwatch(fills[row * 2 + col], name)
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("${oneDecimal(kg)} kg", style = PawseType.MetricValue)
                            Spacer(Modifier.width(4.dp))
                            Text("${(kg / c.weightKg * 100).roundToInt()}%", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))
        Text(
            "Health Connect has no skeletal-muscle record, so a scale's muscle figure can't reach Pawse. " +
                "Lean mass — muscle, water and bone together — is the closest honest number.",
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EnergyCard(e: EnergyToday) {
    val scaleTo = max(e.eatenKcal, e.burnedKcal ?: 0.0).takeIf { it > 0.0 } ?: 1.0
    val on = MaterialTheme.colorScheme.onSurface
    val onv = MaterialTheme.colorScheme.onSurfaceVariant
    PawseCard {
        Row {
            Text("Energy today", style = PawseType.Label, color = onv, modifier = Modifier.weight(1f))
            Text("So far, ${ScoreCopy.clock(e.asOfEpochMs)}", style = PawseType.Label, color = onv)
        }
        Spacer(Modifier.height(14.dp))
        LabelledValue("Eaten", "${kcal(e.eatenKcal)} kcal")
        Spacer(Modifier.height(6.dp))
        Bars(listOf((e.eatenKcal / scaleTo).toFloat() to on))
        if (e.itemsWithoutEnergy > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                "${e.itemsWithoutEnergy} ${if (e.itemsWithoutEnergy == 1) "item has" else "items have"} no energy figure, so this is short.",
                style = PawseType.Label,
                color = onv,
            )
        }
        Spacer(Modifier.height(14.dp))
        LabelledValue("Burned", e.burnedKcal?.let { "${kcal(it)} kcal" } ?: "—")
        Spacer(Modifier.height(6.dp))
        Bars(
            listOf(
                ((e.restingKcal ?: 0.0) / scaleTo).toFloat() to onv,
                ((e.activeKcal ?: 0.0) / scaleTo).toFloat() to on,
            ),
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            e.restingKcal?.let { KeySwatch(onv, "Resting ${kcal(it)}") }
            e.activeKcal?.let { KeySwatch(on, "Active ${kcal(it)}") }
            if (e.restingKcal == null && e.activeKcal == null) {
                Text("Needs a BMR or active energy from your watch", style = PawseType.Label, color = onv)
            }
        }
        e.differenceKcal?.let { diff ->
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Difference", style = PawseType.Label, color = onv, modifier = Modifier.weight(1f))
                Text(
                    when {
                        diff >= 0.5 -> "${kcal(diff)} kcal more burned than eaten"
                        diff <= -0.5 -> "${kcal(-diff)} kcal more eaten than burned"
                        else -> "Even"
                    },
                    style = PawseType.BodyStrong,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "A plain number, not a target. Pawse doesn't know what your day should look like, so it never says.",
                style = PawseType.Label,
                color = onv,
            )
        }
    }
}

/** Segments laid end to end from the left, each a fraction of the full width. */
@Composable
private fun Bars(segments: List<Pair<Float, Color>>) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(10.dp),
    ) {
        var x = 0f
        val gap = 2.dp.toPx()
        val shown = segments.filter { it.first > 0f }
        shown.forEachIndexed { i, (fraction, color) ->
            val w = (fraction.coerceIn(0f, 1f) * size.width - if (i < shown.lastIndex) gap else 0f).coerceAtLeast(0f)
            roundedSegment(color, x, w, first = i == 0, last = i == shown.lastIndex)
            x += w + gap
        }
    }
}

private fun DrawScope.roundedSegment(color: Color, x: Float, w: Float, first: Boolean, last: Boolean) {
    val r = size.height / 2f
    when {
        first && last -> drawRoundRect(color, Offset(x, 0f), Size(w, size.height), CornerRadius(r))
        first -> {
            drawRoundRect(color, Offset(x, 0f), Size(w, size.height), CornerRadius(r))
            drawRect(color, Offset(x + w - r, 0f), Size(r.coerceAtMost(w), size.height))
        }
        last -> {
            drawRoundRect(color, Offset(x, 0f), Size(w, size.height), CornerRadius(r))
            drawRect(color, Offset(x, 0f), Size(r.coerceAtMost(w), size.height))
        }
        else -> drawRect(color, Offset(x, 0f), Size(w, size.height))
    }
}

@Composable
private fun BmrCard(bmr: BmrResult, leanKg: Double?) {
    PawseCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Basal metabolic rate", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(
                if (bmr.source == BmrSource.MEASURED) "Measured" else "Estimated",
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.background, RoundedCornerShape(6.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(kcal(bmr.kcalPerDay), style = PawseType.Numeral)
            Spacer(Modifier.width(6.dp))
            Text(
                "kcal a day",
                style = PawseType.Heading.copy(fontWeight = PawseType.Body.fontWeight),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            when (bmr.source) {
                BmrSource.MEASURED -> "Written to Health Connect by your scale this morning."
                BmrSource.KATCH_MCARDLE -> "370 + 21.6 × ${leanKg?.let(::oneDecimal) ?: "lean"} kg lean mass = ${kcal(bmr.kcalPerDay)}"
                BmrSource.MIFFLIN_ST_JEOR -> "Mifflin–St Jeor, from your age, height and weight."
            },
            style = PawseType.Body,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when (bmr.source) {
                BmrSource.MEASURED ->
                    "Pawse uses the scale's figure as it is. If the scale stops writing it, Pawse works it " +
                        "out from your lean mass instead and says so."
                BmrSource.KATCH_MCARDLE ->
                    "Katch–McArdle, a published formula that only needs lean mass. Without a scale, Pawse " +
                        "falls back to Mifflin–St Jeor from age, height and weight, and labels it that way."
                BmrSource.MIFFLIN_ST_JEOR ->
                    "A published estimate. A scale that writes lean mass would make it more personal."
            },
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LabelledValue(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(value, style = PawseType.Label.copy(fontWeight = PawseType.BodyStrong.fontWeight))
    }
}

/** A body row: grey marker always, words for direction, the scale's own units. */
private fun BodyRow.toRowState(): BaselineRowState {
    val b = baseline
    val zScore = this.z
    val delta = b?.let { latest.value - it.mean }
    return BaselineRowState(
        label = ScoreCopy.metricLabel(metric),
        valueText = ScoreCopy.formatRaw(metric, latest.value),
        baselineText = b?.let {
            "Usually ${ScoreCopy.formatRaw(metric, it.mean)} ± ${ScoreCopy.formatRaw(metric, it.sigma).trimUnitSign()}"
        } ?: "Needs a few more weigh-ins",
        deltaText = when {
            delta == null || zScore == null -> "No usual range yet"
            abs(zScore) < ScoreCopy.NEUTRAL_Z -> "About your usual"
            else -> "${ScoreCopy.formatRaw(metric, abs(delta)).replace("%", if (metric == Metric.BODY_FAT) " points" else "%")} " +
                (if (delta > 0) "above" else "below") + " your usual"
        },
        z = zScore?.toFloat(),
        direction = if (zScore == null) DeviationDirection.UNKNOWN else DeviationDirection.NEUTRAL,
    )
}

private fun String.trimUnitSign() = replace("+", "")

private fun oneDecimal(value: Double) = String.format(Locale.getDefault(), "%.1f", value)

private fun kcal(value: Double) = String.format(Locale.getDefault(), "%,d", value.roundToInt())
