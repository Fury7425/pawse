package app.pawse.feature.food

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pawse.core.data.food.FoodDay
import app.pawse.core.data.food.FoodLogEntry
import app.pawse.core.data.food.FoodSource
import app.pawse.core.data.food.Meal
import app.pawse.core.data.food.Nutrients
import app.pawse.core.ui.component.Glyph
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.IconAction
import app.pawse.core.ui.component.PawseCard
import app.pawse.core.ui.component.PawseSheet
import app.pawse.core.ui.component.Pictogram
import app.pawse.core.ui.component.QuietButton
import app.pawse.core.ui.component.SectionLabel
import app.pawse.core.ui.component.StatePictogram
import app.pawse.core.ui.component.UndoToast
import app.pawse.core.ui.component.bleed
import app.pawse.core.ui.component.press
import app.pawse.core.ui.component.rememberReducedMotion
import app.pawse.core.ui.component.reveal
import app.pawse.core.ui.theme.PawseType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The food log: a day, its meals, and what they add up to.
 *
 * The total is never shown alone. Every entry carries where its figures came from,
 * and the line under the total says how many items had no energy figure at all —
 * the same discipline the score screens use, for the same reason. A calorie total
 * that quietly drops the three items it could not price is a worse number than no
 * total.
 *
 * There are no targets here, and no ring to close. Whether 1,900 kcal was the right
 * number for this person on this day is not something this app knows, and inventing
 * a goal would be inventing the one number the user might actually act on.
 */
@Composable
fun FoodLogScreen(
    onManualEntry: () -> Unit,
    onOpenBody: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FoodLogViewModel = hiltViewModel(),
) {
    val date by viewModel.date.collectAsStateWithLifecycle()
    val day by viewModel.day.collectAsStateWithLifecycle()
    val week by viewModel.week.collectAsStateWithLifecycle()
    val again by viewModel.again.collectAsStateWithLifecycle()
    val energy by viewModel.energy.collectAsStateWithLifecycle()
    val undo by viewModel.undo.collectAsStateWithLifecycle()
    val networkEnabled by viewModel.networkEnabled.collectAsStateWithLifecycle()
    val mirroring by viewModel.mirroring.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var open by rememberSaveable { mutableStateOf<Long?>(null) }
    val today = remember { LocalDate.now() }
    val isToday = date == today
    val meals = day?.byMeal.orEmpty()
    val still = rememberReducedMotion()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = top + 12.dp, bottom = bottom + 140.dp),
        ) {
            item(key = "header") {
                Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Food", style = PawseType.Headline, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    IconAction(Glyphs.SLIDERS, "Food settings", { showSettings = true }, Modifier.offset(x = 10.dp))
                }
            }

            item(key = "week") {
                WeekStrip(
                    week = week,
                    selected = date,
                    onPick = {
                        open = null
                        viewModel.showDay(it)
                    },
                    modifier = Modifier.padding(top = 12.dp),
                )
                Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.Bottom) {
                    Text(dayTitle(date, today), style = PawseType.Title, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Text(
                        date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())),
                        style = PawseType.Label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item(key = "total") { DayTotal(day, Modifier.padding(top = 8.dp)) }

            if (isToday) {
                energy?.burnedKcal?.let { burned ->
                    item(key = "burned") {
                        BurnedCard(
                            burnedKcal = burned,
                            differenceKcal = energy?.differenceKcal ?: 0.0,
                            onClick = onOpenBody,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
                if (again.isNotEmpty()) {
                    item(key = "again") { Again(again, viewModel::logAgain, Modifier.padding(top = 24.dp)) }
                }
                meals.forEach { (meal, entries) ->
                    item(key = "meal-${meal.name}") { MealHeading(meal, entries, Modifier.padding(top = 24.dp)) }
                    items(entries, key = { it.id }) { entry ->
                        SwipeEntry(
                            entry = entry,
                            isOpen = open == entry.id,
                            animate = !still,
                            onToggle = { open = if (open == entry.id) null else entry.id },
                            onRemove = {
                                open = null
                                viewModel.delete(entry)
                            },
                        )
                    }
                }
                if (meals.isEmpty() && day != null) {
                    item(key = "empty") {
                        EmptyDay(
                            title = "Nothing logged yet today",
                            body = "Scan a barcode, photograph a nutrition panel, or type it in. Everything you " +
                                "scan is saved on this phone, so the second time is instant.",
                        )
                    }
                }
            } else if (meals.isEmpty()) {
                item(key = "empty") {
                    EmptyDay(
                        title = if (date == today.minusDays(1)) "Nothing logged yesterday" else "Nothing logged on ${dayTitle(date, today)}",
                        body = "A gap is a true record too. Add what you remember, or leave it.",
                    )
                }
            } else {
                item(key = "past") { PastMeals(meals, Modifier.padding(top = 24.dp)) }
            }

            item(key = "manual") {
                QuietButton(
                    text = "Add something without a barcode",
                    onClick = onManualEntry,
                    glyph = Glyphs.PENCIL,
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .offset(x = (-12).dp),
                )
            }
        }

        undo?.let { bar ->
            key(bar) {
                UndoToast(
                    text = bar.text,
                    onUndo = bar.undo,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 16.dp, end = 16.dp, bottom = bottom + 100.dp)
                        .reveal(!still),
                )
            }
        }
    }

    if (showSettings) {
        FoodSettingsSheet(
            networkEnabled = networkEnabled,
            mirroring = mirroring,
            mirroredToday = week.lastOrNull()?.entries?.count { it.healthConnectId != null } ?: 0,
            onNetworkChange = viewModel::setNetworkLookup,
            onMirroringChange = viewModel::setMirroring,
            onDismiss = { showSettings = false },
        )
    }
}

private fun dayTitle(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> date.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()))
}

/** Seven days, a dot under each one that has anything logged. */
@Composable
private fun WeekStrip(
    week: List<FoodDay>,
    selected: LocalDate,
    onPick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        week.forEach { foodDay ->
            val isSelected = foodDay.date == selected
            val logged = foodDay.entries.isNotEmpty()
            val shape = RoundedCornerShape(12.dp)
            val described = foodDay.date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())) +
                if (logged) "" else ", nothing logged"
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp)
                    .clip(shape)
                    .background(if (isSelected) MaterialTheme.colorScheme.surfaceContainer else androidx.compose.ui.graphics.Color.Transparent, shape)
                    .border(1.dp, if (isSelected) MaterialTheme.colorScheme.outlineVariant else androidx.compose.ui.graphics.Color.Transparent, shape)
                    .selectable(selected = isSelected, role = Role.Tab) { onPick(foodDay.date) }
                    .clearAndSetSemantics { contentDescription = described }
                    .padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    foodDay.date.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())),
                    style = PawseType.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(foodDay.date.dayOfMonth.toString(), style = PawseType.Title, color = MaterialTheme.colorScheme.onSurface)
                Box(
                    Modifier
                        .size(4.dp)
                        .alpha(if (logged) 1f else 0f)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
                )
            }
        }
    }
}

@Composable
private fun DayTotal(day: FoodDay?, modifier: Modifier = Modifier) {
    val totals = day?.totals
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = totals?.kcalRounded?.let { "%,d".format(it) } ?: "—",
                style = PawseType.Hero,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "kcal",
                style = PawseType.Title.copy(fontWeight = FontWeight.Normal),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, bottom = 12.dp),
            )
        }
        Text(
            text = totals?.let { FoodCopy.totalsNote(it) } ?: "Nothing logged yet.",
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        totals?.totals?.let { MacroCard(it, Modifier.padding(top = 16.dp)) }
    }
}

/** Protein, carbs and fat in grams, and each one's share of the energy. */
@Composable
private fun MacroCard(n: Nutrients, modifier: Modifier = Modifier) {
    val p = n.proteinGrams
    val c = n.carbsGrams
    val f = n.fatGrams
    if (p == null && c == null && f == null) return
    val pk = (p ?: 0.0) * 4
    val ck = (c ?: 0.0) * 4
    val fk = (f ?: 0.0) * 9
    val total = pk + ck + fk
    val pShare = if (total > 0) (pk / total * 100).roundToInt() else 0
    val cShare = if (total > 0) (ck / total * 100).roundToInt() else 0
    val fShare = if (total > 0) 100 - pShare - cShare else 0
    val on = MaterialTheme.colorScheme.onSurface
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    val outline = MaterialTheme.colorScheme.outline

    PawseCard(modifier, contentPadding = PaddingValues(vertical = 14.dp)) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            listOf("Protein" to p, "Carbs" to c, "Fat" to f).forEachIndexed { i, (name, grams) ->
                if (i > 0) VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                ) {
                    Text(grams?.let { "${it.roundToInt()} g" } ?: "— g", style = PawseType.MetricValue, color = on)
                    Text(name, style = PawseType.Label, color = variant, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        if (total > 0) {
            Column(
                Modifier
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp)
                    .semantics { contentDescription = "Share of energy: protein $pShare%, carbs $cShare%, fat $fShare%" },
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    listOf(pShare to on, cShare to variant, fShare to outline).filter { it.first > 0 }.forEach { (share, color) ->
                        Box(
                            Modifier
                                .weight(share.toFloat())
                                .fillMaxHeight()
                                .background(color),
                        )
                    }
                }
                Row(Modifier.padding(top = 8.dp)) {
                    Key("$pShare% of energy", on, Modifier.weight(1f))
                    Key("$cShare%", variant, Modifier.weight(1f))
                    Key("$fShare%", outline, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Key(text: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(6.dp))
        Text(text, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BurnedCard(
    burnedKcal: Double,
    differenceKcal: Double,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PawseCard(modifier, onClick = onClick, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Burned so far", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = "${"%,d".format(burnedKcal.roundToInt())} kcal · " + when {
                        differenceKcal >= 0.5 -> "${"%,d".format(differenceKcal.roundToInt())} more than eaten"
                        differenceKcal <= -0.5 -> "${"%,d".format((-differenceKcal).roundToInt())} less than eaten"
                        else -> "the same as eaten"
                    },
                    style = PawseType.Action,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Glyph(Glyphs.CHEVRON, size = 18.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Most meals repeat, so yesterday's foods log again in one tap. */
@Composable
private fun Again(entries: List<FoodLogEntry>, onAdd: (FoodLogEntry) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        SectionLabel("Again · one tap logs it as a snack", Modifier.padding(bottom = 8.dp))
        Row(
            modifier = Modifier
                .bleed(24.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            entries.forEach { entry ->
                val shape = RoundedCornerShape(12.dp)
                Row(
                    modifier = Modifier
                        .height(44.dp)
                        .clip(shape)
                        .press({ onAdd(entry) })
                        .background(MaterialTheme.colorScheme.surfaceContainer, shape)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                        .padding(start = 10.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Glyph(Glyphs.PLUS, size = 16.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant, strokeWidth = 2f)
                    Text(entry.name, style = PawseType.Action, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                    Text(FoodCopy.energy(entry.nutrients), style = PawseType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun MealHeading(meal: Meal, entries: List<FoodLogEntry>, modifier: Modifier = Modifier) {
    SectionLabel(
        text = FoodCopy.meal(meal),
        trailing = "${"%,d".format(entries.sumOf { it.nutrients.kcal ?: 0.0 }.roundToInt())} kcal",
        modifier = modifier.padding(bottom = 2.dp),
    )
}

/**
 * One entry: swipe it left past 40% and it is removed, with Undo right after.
 * Tapping opens where its figures came from, and the same remove as a button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeEntry(
    entry: FoodLogEntry,
    isOpen: Boolean,
    animate: Boolean,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
) {
    var removed by remember { mutableStateOf(false) }
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart && !removed) {
                removed = true
                onRemove()
                true
            } else {
                false
            }
        },
        positionalThreshold = { distance -> distance * 0.4f },
    )
    Column(Modifier.bleed(24.dp)) {
        SwipeToDismissBox(
            state = state,
            enableDismissFromStartToEnd = false,
            backgroundContent = {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(end = 24.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph(Glyphs.TRASH, size = 18.dp, tint = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (state.targetValue == SwipeToDismissBoxValue.EndToStart) "Release to remove" else "Remove",
                        style = PawseType.Action,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
        ) {
            EntryRow(entry, isOpen, animate, onToggle, onRemove)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 24.dp))
    }
}

@Composable
private fun EntryRow(
    entry: FoodLogEntry,
    isOpen: Boolean,
    animate: Boolean,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
) {
    val inHealthConnect = entry.healthConnectId != null
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .semantics { stateDescription = if (isOpen) "Expanded" else "Collapsed" }
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val tile = RoundedCornerShape(12.dp)
            Box(
                Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainer, tile)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, tile),
                contentAlignment = Alignment.Center,
            ) {
                Glyph(sourceGlyph(entry.source), size = 18.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = PawseType.Body, color = MaterialTheme.colorScheme.onSurface)
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        listOfNotNull(entry.brand, FoodCopy.grams(entry.quantityGrams)).joinToString(" · "),
                        style = PawseType.Label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (inHealthConnect) {
                        Glyph(
                            Glyphs.IN_HEALTH_CONNECT,
                            size = 13.dp,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            strokeWidth = 2f,
                            modifier = Modifier.semantics { contentDescription = "In Health Connect" },
                        )
                    }
                }
            }
            Text(
                FoodCopy.energy(entry.nutrients),
                style = PawseType.MetricValue,
                color = if (entry.nutrients.kcal == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }

        if (isOpen) {
            Column(
                Modifier
                    .padding(start = 24.dp + 52.dp, end = 24.dp, bottom = 16.dp)
                    .reveal(animate),
            ) {
                Text(
                    FoodCopy.source(entry.source) + if (inHealthConnect) " · in Health Connect" else " · only on this phone",
                    style = PawseType.Label,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    FoodCopy.summary(entry.nutrients),
                    style = PawseType.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                val shape = RoundedCornerShape(12.dp)
                Row(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(shape)
                        .press(onRemove)
                        .background(MaterialTheme.colorScheme.surfaceContainer, shape)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph(Glyphs.TRASH, size = 16.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (inHealthConnect) "Remove · also from Health Connect" else "Remove",
                        style = PawseType.Action,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

private fun sourceGlyph(source: FoodSource): String = when (source) {
    FoodSource.OPEN_FOOD_FACTS -> Glyphs.SOURCE_DATABASE
    FoodSource.LABEL_OCR -> Glyphs.SOURCE_LABEL
    FoodSource.MANUAL -> Glyphs.SOURCE_TYPED
}

/** Earlier days fold to one line per meal. */
@Composable
private fun PastMeals(meals: Map<Meal, List<FoodLogEntry>>, modifier: Modifier = Modifier) {
    Column(modifier) {
        meals.forEach { (meal, entries) ->
            Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(FoodCopy.meal(meal), style = PawseType.Body, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        if (entries.size == 1) "1 item" else "${entries.size} items",
                        style = PawseType.Label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    "${"%,d".format(entries.sumOf { it.nutrients.kcal ?: 0.0 }.roundToInt())} kcal",
                    style = PawseType.MetricValue,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun EmptyDay(title: String, body: String) {
    PawseCard(Modifier.padding(top = 24.dp), contentPadding = PaddingValues(20.dp)) {
        StatePictogram(Pictogram.EMPTY_DAY)
        Text(title, style = PawseType.Title, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 12.dp))
        Text(body, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Switches apply at once; there is no Save button. */
@Composable
private fun FoodSettingsSheet(
    networkEnabled: Boolean,
    mirroring: Boolean,
    mirroredToday: Int,
    onNetworkChange: (Boolean) -> Unit,
    onMirroringChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    PawseSheet(title = "Food settings", onDismiss = onDismiss) { _ ->
        SettingSwitch(
            title = "Look barcodes up online",
            body = "Sends the barcode to Open Food Facts and nothing else. Reading the label with the camera works either way.",
            now = if (networkEnabled) {
                "Now: an unknown barcode is looked up once, then saved."
            } else {
                "Now: an unknown barcode asks you to read the label instead."
            },
            checked = networkEnabled,
            onCheckedChange = onNetworkChange,
        )
        SettingSwitch(
            title = "Copy food into Health Connect",
            body = "The only thing Pawse ever writes. Removing an entry here removes it there too.",
            now = when {
                !mirroring -> "Now: food stays on this phone only."
                mirroredToday == 0 -> "Now: food you log is copied into Health Connect."
                mirroredToday == 1 -> "Now: today's 1 entry with figures is in Health Connect."
                else -> "Now: today's $mirroredToday entries with figures are in Health Connect."
            },
            checked = mirroring,
            onCheckedChange = onMirroringChange,
        )
        Text(
            "Your health data never leaves this phone, with either switch on or off.",
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 14.dp),
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    body: String,
    now: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(title, style = PawseType.Title, color = MaterialTheme.colorScheme.onSurface)
            Text(body, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(Glyphs.ARROW, size = 14.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant, strokeWidth = 2f)
                Spacer(Modifier.width(6.dp))
                Text(now, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.background,
                checkedTrackColor = MaterialTheme.colorScheme.onSurface,
                checkedBorderColor = MaterialTheme.colorScheme.onSurface,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
