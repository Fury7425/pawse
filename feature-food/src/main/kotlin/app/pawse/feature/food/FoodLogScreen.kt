package app.pawse.feature.food

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pawse.core.data.food.FoodDay
import app.pawse.core.data.food.FoodLogEntry
import app.pawse.core.ui.component.Chip
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
    var showSettings by remember { mutableStateOf(false) }
    val isToday = date == LocalDate.now()

    Scaffold(
        modifier = modifier,
        snackbarHost = { undo?.let { UndoBarView(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) } },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 16.dp,
                bottom = padding.calculateBottomPadding() + 120.dp,
            ),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Food", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { showSettings = !showSettings }) { Text("Settings") }
                }
            }

            item {
                WeekStrip(
                    week = week,
                    selected = date,
                    onPick = viewModel::showDay,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                DayHeader(date = date, modifier = Modifier.padding(horizontal = 24.dp))
                Spacer(Modifier.height(8.dp))
            }

            item {
                val totals = day?.totals
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Text(
                        text = totals?.kcalRounded?.toString() ?: "—",
                        style = PawseType.Hero,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "kcal",
                        style = PawseType.Label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = totals?.let { FoodCopy.totalsNote(it) } ?: "Nothing logged yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    day?.totals?.totals?.let { nutrients ->
                        if (!nutrients.isEmpty) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = FoodCopy.summary(nutrients),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }

            if (isToday) {
                energy?.burnedKcal?.let { burned ->
                    item {
                        BurnedCard(
                            burnedKcal = burned,
                            differenceKcal = energy?.differenceKcal ?: 0.0,
                            onClick = onOpenBody,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                        Spacer(Modifier.height(20.dp))
                    }
                }
                if (again.isNotEmpty()) {
                    item {
                        Text(
                            text = "Again · one tap logs it as a snack",
                            style = PawseType.Label,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            again.forEach { entry ->
                                AssistChip(
                                    onClick = { viewModel.logAgain(entry) },
                                    label = { Text("${entry.name} · ${FoodCopy.energy(entry.nutrients)}") },
                                )
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                    }
                }
            }

            if (showSettings) {
                item {
                    SettingsPanel(
                        networkEnabled = networkEnabled,
                        mirroring = mirroring,
                        onNetworkChange = viewModel::setNetworkLookup,
                        onMirroringChange = viewModel::setMirroring,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Spacer(Modifier.height(20.dp))
                }
            }

            val meals = day?.byMeal.orEmpty()
            if (meals.isEmpty()) {
                item {
                    Column(Modifier.padding(horizontal = 24.dp)) {
                        Text(
                            text = if (isToday) {
                                "Scan a barcode, photograph a nutrition panel, or type it in. " +
                                    "Everything you scan is saved on this phone, so the second time is instant."
                            } else {
                                "A gap is a true record too. Add what you remember, or leave it."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onManualEntry) { Text("Type something in") }
                    }
                }
            }

            meals.forEach { (meal, entries) ->
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = FoodCopy.meal(meal),
                        style = PawseType.Label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
                items(entries) { entry ->
                    EntryRow(entry = entry, onDelete = { viewModel.delete(entry) })
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }

            if (meals.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(20.dp))
                    TextButton(
                        onClick = onManualEntry,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) { Text("Add something without a barcode") }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(date: LocalDate, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(
            text = when (date) {
                LocalDate.now() -> "Today"
                LocalDate.now().minusDays(1) -> "Yesterday"
                else -> date.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()))
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())),
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
            Surface(
                onClick = { onPick(foodDay.date) },
                shape = RoundedCornerShape(12.dp),
                color = if (isSelected) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent,
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = foodDay.date.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())),
                        style = PawseType.Label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(foodDay.date.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium)
                    Box(
                        Modifier
                            .size(4.dp)
                            .background(
                                color = if (foodDay.entries.isNotEmpty()) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    Color.Transparent
                                },
                                shape = CircleShape,
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun BurnedCard(
    burnedKcal: Double,
    differenceKcal: Double,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text("Burned so far", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = "${"%,d".format(burnedKcal.roundToInt())} kcal · " + when {
                    differenceKcal >= 0.5 -> "${"%,d".format(differenceKcal.roundToInt())} more than eaten"
                    differenceKcal <= -0.5 -> "${"%,d".format((-differenceKcal).roundToInt())} less than eaten"
                    else -> "the same as eaten"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun UndoBarView(bar: UndoBar, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.onSurface,
        contentColor = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(bar.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = bar.undo) {
                Text("Undo", color = MaterialTheme.colorScheme.surface)
            }
        }
    }
}

@Composable
private fun EntryRow(entry: FoodLogEntry, onDelete: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .clickable { confirming = !confirming }
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = listOfNotNull(entry.brand, FoodCopy.grams(entry.quantityGrams))
                        .joinToString(" · "),
                    style = PawseType.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = FoodCopy.energy(entry.nutrients),
                style = PawseType.MetricValue,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip(text = FoodCopy.source(entry.source))
            if (entry.healthConnectId != null) Chip(text = "In Health Connect")
        }

        if (confirming) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = FoodCopy.summary(entry.nutrients),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onDelete, contentPadding = PaddingValues(0.dp)) {
                Text(
                    text = if (entry.healthConnectId != null) {
                        "Remove, here and from Health Connect"
                    } else {
                        "Remove"
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingsPanel(
    networkEnabled: Boolean,
    mirroring: Boolean,
    onNetworkChange: (Boolean) -> Unit,
    onMirroringChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SettingRow(
            title = "Look barcodes up online",
            body = "Sends the barcode to Open Food Facts and nothing else. Off by default. " +
                "Reading the label with the camera works either way.",
            checked = networkEnabled,
            onCheckedChange = onNetworkChange,
        )
        Spacer(Modifier.height(16.dp))
        SettingRow(
            title = "Copy food into Health Connect",
            body = "The only thing this app ever writes. Removing an entry here removes it there too.",
            checked = mirroring,
            onCheckedChange = onMirroringChange,
        )
    }
}

@Composable
private fun SettingRow(
    title: String,
    body: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = body,
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.padding(horizontal = 8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
