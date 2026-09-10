package app.pawse.feature.food

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pawse.core.data.food.FoodLogEntry
import app.pawse.core.data.food.Meal
import app.pawse.core.ui.component.Chip
import app.pawse.core.ui.theme.PawseType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

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
    onBack: () -> Unit,
    onScan: () -> Unit,
    onManualEntry: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FoodLogViewModel = hiltViewModel(),
) {
    val date by viewModel.date.collectAsStateWithLifecycle()
    val day by viewModel.day.collectAsStateWithLifecycle()
    val networkEnabled by viewModel.networkEnabled.collectAsStateWithLifecycle()
    val mirroring by viewModel.mirroring.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onScan,
                text = { Text("Scan") },
                icon = {},
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 16.dp,
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onBack) { Text("Back") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { showSettings = !showSettings }) { Text("Settings") }
                }
            }

            item {
                DayHeader(
                    date = date,
                    onPrevious = viewModel::previousDay,
                    onNext = viewModel::nextDay,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(16.dp))
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
                            text = "Scan a barcode, photograph a nutrition panel, or type it in. " +
                                "Everything you scan is saved on this phone, so the second time is instant.",
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
private fun DayHeader(
    date: LocalDate,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onPrevious) { Text("←") }
        Text(
            text = when (date) {
                LocalDate.now() -> "Today"
                LocalDate.now().minusDays(1) -> "Yesterday"
                else -> date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()))
            },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(Modifier.weight(1f))
        if (date.isBefore(LocalDate.now())) {
            TextButton(onClick = onNext) { Text("→") }
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
