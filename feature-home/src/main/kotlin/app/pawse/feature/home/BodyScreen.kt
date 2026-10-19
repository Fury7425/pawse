package app.pawse.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import app.pawse.core.ui.component.Glyph
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.PawseCard
import app.pawse.core.ui.component.Segmented
import app.pawse.core.ui.component.UsualRangeKey
import app.pawse.core.ui.theme.PawseTheme
import app.pawse.core.ui.theme.PawseType
import app.pawse.scoring.body.BmrResult
import app.pawse.scoring.body.BmrSource
import app.pawse.scoring.model.Metric
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Body: what the scale wrote, against your own usual range, and today's energy.
 *
 * Every change stays grey. Up or down is not good or bad on its own, so the screen
 * says which way and by how much, and leaves the verdict to the person.
 */
@Composable
fun BodyScreen(
    modifier: Modifier = Modifier,
    viewModel: BodyViewModel = hiltViewModel(),
) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val range by viewModel.range.collectAsStateWithLifecycle()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = top + 12.dp, bottom = bottom + 140.dp),
    ) {
        item(key = "header") {
            Text(
                "Body",
                style = PawseType.Headline,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .padding(top = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val s = snapshot
                Glyph(
                    if (s?.weighedToday == true) Glyphs.CHECK else Glyphs.CLOCK,
                    size = 14.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    strokeWidth = 2f,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = s?.let(::statusLine) ?: "Reading your scale",
                    style = PawseType.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val s = snapshot ?: return@LazyColumn
        if (!s.hasAnyData) {
            item(key = "empty") {
                Text(
                    "No scale readings yet. When a scale writes weight to Health Connect, it shows here on its own.",
                    style = PawseType.Body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        }

        if (s.weight != null) {
            item(key = "weight") { WeightSection(s, range, viewModel::setRange) }
        }

        s.composition?.let { composition ->
            item(key = "composition") { CompositionCard(composition, Modifier.padding(top = 28.dp)) }
        }

        if (s.rows.isNotEmpty()) {
            item(key = "rows-head") {
                Row(
                    modifier = Modifier.padding(top = 28.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
                item(key = row.metric.name) {
                    BaselineBandRow(state = row.toRowState())
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            item(key = "rows-note") {
                Note(
                    "Body changes stay grey. Up or down isn't good or bad on its own, so Pawse says " +
                        "which way and by how much, and leaves the verdict to you.",
                    Modifier.padding(top = 12.dp),
                )
            }
        }

        item(key = "energy") { EnergyCard(s.energy, Modifier.padding(top = 28.dp)) }

        s.bmr?.let { bmr ->
            item(key = "bmr") { BmrCard(bmr, s.composition?.leanKg, Modifier.padding(top = 16.dp)) }
        }

        item(key = "footer") {
            Note(
                "Scales estimate composition from a small electric current, so water and a meal can move it " +
                    "by a percent or two. Weigh at the same time each day for the steadiest numbers. " +
                    "Not a medical device.",
                Modifier.padding(top = 24.dp),
            )
        }
    }
}

private fun statusLine(s: BodySnapshot): String {
    val weight = s.weight ?: return "No weigh-ins yet"
    val days = s.daysSinceWeighIn ?: 0L
    return if (days == 0L) {
        "Weighed today at ${ScoreCopy.clock(weight.atEpochMs)}, from your scale"
    } else {
        val date = weight.date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
        "Last weighed $date, ${if (days == 1L) "yesterday" else "$days days ago"}"
    }
}

@Composable
private fun WeightSection(s: BodySnapshot, range: BodyRange, onRange: (BodyRange) -> Unit) {
    val weight = s.weight ?: return
    Column(Modifier.padding(top = 24.dp)) {
        Text("Weight", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier
                .padding(top = 4.dp)
                .alpha(if (s.weighedToday) 1f else 0.6f),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(oneDecimal(weight.value), style = PawseType.Hero, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "kg",
                style = PawseType.Title.copy(fontWeight = FontWeight.Normal),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, bottom = 12.dp),
            )
        }
        Text(
            weightLine(s),
            style = PawseType.Body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Chip(
            text = when (val days = s.daysSinceWeighIn ?: 0L) {
                0L -> "Measured"
                1L -> "1 day old"
                else -> "$days days old"
            },
            modifier = Modifier.padding(top = 10.dp),
        )

        Segmented(
            options = BodyRange.entries.map { it.label },
            selected = range.ordinal,
            onSelect = { onRange(BodyRange.entries[it]) },
            height = 36.dp,
            modifier = Modifier.padding(top = 20.dp),
        )
        WeightChart(s, range)
    }
}

private fun weightLine(s: BodySnapshot): String {
    val weight = s.weight ?: return ""
    if (!s.weighedToday) return "Step on the scale and it updates here on its own."
    val parts = buildList {
        s.weightUsual?.let { add(kgDelta(weight.value - it.mean, "your 30-day usual")) }
        s.weightYearAgo?.let { add(kgDelta(weight.value - it, "a year ago")) }
    }
    return if (parts.isEmpty()) {
        "Pawse needs a few more weigh-ins to know your usual."
    } else {
        parts.joinToString(", and ") { it.replaceFirstChar { c -> c.lowercase() } }
            .replaceFirstChar { it.uppercase() } + "."
    }
}

private fun kgDelta(delta: Double, against: String): String = when {
    abs(delta) < 0.05 -> "about the same as $against"
    delta > 0 -> "${oneDecimal(delta)} kg over $against"
    else -> "${oneDecimal(-delta)} kg under $against"
}

/** The weigh-ins as dots on a line, over the shaded usual range; today is the big dot. */
@Composable
private fun WeightChart(s: BodySnapshot, range: BodyRange) {
    val weight = s.weight ?: return
    val first = s.today.minusDays(range.days)
    val points = s.weightSeries.filter { !it.date.isBefore(first) }
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
            .padding(top = 16.dp)
            .fillMaxWidth()
            .height(120.dp)
            .semantics { contentDescription = description },
    ) {
        val pad = 6.dp.toPx()
        fun y(v: Double) = ((size.height - pad) - (v - lo) / (hi - lo) * (size.height - 2 * pad)).toFloat()
        fun x(date: java.time.LocalDate) = ChronoUnit.DAYS.between(first, date).toFloat() / range.days * size.width

        usual?.let {
            val top = y(it.mean + it.sigma)
            drawRoundRect(
                color = band,
                topLeft = Offset(0f, top),
                size = Size(size.width, (y(it.mean - it.sigma) - top).coerceAtLeast(1f)),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
        }
        if (points.size > 1) {
            val path = Path()
            points.forEachIndexed { i, p ->
                if (i == 0) path.moveTo(x(p.date), y(p.value)) else path.lineTo(x(p.date), y(p.value))
            }
            drawPath(path, color = line, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        points.dropLast(1).forEach { drawCircle(line, radius = 2.5.dp.toPx(), center = Offset(x(it.date), y(it.value))) }
        points.lastOrNull()?.let {
            val c = Offset(x(it.date), y(it.value))
            drawCircle(ring, radius = 8.dp.toPx(), center = c)
            drawCircle(last, radius = 5.dp.toPx(), center = c)
        }
    }
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
private fun CompositionCard(c: BodyComposition, modifier: Modifier = Modifier) {
    val fills = listOf(
        MaterialTheme.colorScheme.onSurface,
        MaterialTheme.colorScheme.onSurfaceVariant,
        MaterialTheme.colorScheme.outline,
        PawseTheme.semantics.baselineBand,
    )
    val parts = listOfNotNull(
        c.waterKg?.let { "Water" to it },
        c.otherLeanKg?.let { (if (c.waterKg == null && c.boneKg == null) "Lean" else "Other lean") to it },
        c.boneKg?.let { "Bone" to it },
        c.fatKg?.let { "Fat" to it },
    ).mapIndexed { i, (name, kg) -> Triple(name, kg, fills[i]) }

    PawseCard(modifier) {
        Header("What the ${oneDecimal(c.weightKg)} kg is made of", "From your scale")
        Row(
            modifier = Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .semantics {
                    contentDescription = parts.joinToString { (name, kg, _) -> "$name ${oneDecimal(kg)} kg" }
                },
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            parts.forEach { (_, kg, fill) ->
                Box(
                    Modifier
                        .weight(kg.toFloat().coerceAtLeast(0.01f))
                        .fillMaxHeight()
                        .background(fill),
                )
            }
        }
        Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            parts.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    pair.forEach { (name, kg, fill) ->
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).background(fill, RoundedCornerShape(2.dp)))
                                Spacer(Modifier.width(6.dp))
                                Text(name, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.Bottom) {
                                Text("${oneDecimal(kg)} kg", style = PawseType.MetricValue, color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    " ${(kg / c.weightKg * 100).roundToInt()}%",
                                    style = PawseType.Label,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 14.dp))
        Note(
            "Health Connect has no skeletal-muscle record, so a scale's muscle figure can't reach Pawse. " +
                "Lean mass — muscle, water and bone together — is the closest honest number.",
            Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun EnergyCard(e: EnergyToday, modifier: Modifier = Modifier) {
    val burned = e.burnedKcal
    val scale = maxOf(e.eatenKcal, burned ?: 0.0).takeIf { it > 0 }?.times(1.1) ?: 1.0
    PawseCard(modifier) {
        Header("Energy today", "So far, ${ScoreCopy.clock(e.asOfEpochMs)}")

        Figure("Eaten", "${kcal(e.eatenKcal)} kcal", Modifier.padding(top = 14.dp))
        Bars(listOf(e.eatenKcal / scale to MaterialTheme.colorScheme.onSurface))
        if (e.itemsWithoutEnergy > 0) {
            Note(
                "${e.itemsWithoutEnergy} ${if (e.itemsWithoutEnergy == 1) "item has" else "items have"} " +
                    "no energy figure, so this is short.",
                Modifier.padding(top = 4.dp),
            )
        }

        Figure("Burned", burned?.let { "${kcal(it)} kcal" } ?: "—", Modifier.padding(top = 14.dp))
        if (burned != null) {
            Bars(
                listOfNotNull(
                    e.restingKcal?.let { it / scale to MaterialTheme.colorScheme.onSurfaceVariant },
                    e.activeKcal?.let { it / scale to MaterialTheme.colorScheme.onSurface },
                ),
            )
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (e.restingKcal == null && e.activeKcal == null) {
                Note("Needs a BMR or active energy from your watch", Modifier)
            }
            e.restingKcal?.let { Key("Resting ${kcal(it)}", MaterialTheme.colorScheme.onSurfaceVariant) }
            e.activeKcal?.let { Key("Active ${kcal(it)}", MaterialTheme.colorScheme.onSurface) }
        }

        e.differenceKcal?.let { diff ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 16.dp))
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.Bottom) {
                Text("Difference", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(
                    when {
                        diff >= 0.5 -> "${kcal(diff)} kcal more burned than eaten"
                        diff <= -0.5 -> "${kcal(-diff)} kcal more eaten than burned"
                        else -> "Even"
                    },
                    style = PawseType.Action,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Note(
                "A plain number, not a target. Pawse doesn't know what your day should look like, so it never says.",
                Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun BmrCard(bmr: BmrResult, leanKg: Double?, modifier: Modifier = Modifier) {
    PawseCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Basal metabolic rate", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Chip(if (bmr.source == BmrSource.MEASURED) "Measured" else "Estimated")
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text(kcal(bmr.kcalPerDay), style = PawseType.Display, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "kcal a day",
                style = PawseType.Title.copy(fontWeight = FontWeight.Normal),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
            )
        }
        Text(
            when (bmr.source) {
                BmrSource.MEASURED -> "Written to Health Connect by your scale this morning."
                BmrSource.KATCH_MCARDLE -> "370 + 21.6 × ${leanKg?.let(::oneDecimal) ?: "lean"} kg lean mass = ${kcal(bmr.kcalPerDay)}"
                BmrSource.MIFFLIN_ST_JEOR -> "Mifflin–St Jeor, from your age, height and weight."
            },
            style = PawseType.Body,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 6.dp),
        )
        Note(
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
            Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun Header(left: String, right: String) {
    Row {
        Text(left, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(right, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Figure(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier) {
        Text(label, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = PawseType.Label.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface)
    }
}

/** One 10 dp bar made of parts, each a fraction of the card's shared scale. */
@Composable
private fun Bars(parts: List<Pair<Double, Color>>) {
    Row(
        modifier = Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .height(10.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val total = parts.sumOf { it.first }.coerceIn(0.0, 1.0)
        Row(
            Modifier
                .weight(total.toFloat().coerceAtLeast(0.001f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(5.dp)),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            parts.forEach { (fraction, color) ->
                Box(
                    Modifier
                        .weight(fraction.toFloat().coerceAtLeast(0.001f))
                        .fillMaxHeight()
                        .background(color),
                )
            }
        }
        if (total < 1.0) Spacer(Modifier.weight((1.0 - total).toFloat()))
    }
}

@Composable
private fun Key(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(6.dp))
        Text(text, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
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
