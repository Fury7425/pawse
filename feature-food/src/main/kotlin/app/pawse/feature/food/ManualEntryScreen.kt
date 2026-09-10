package app.pawse.feature.food

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pawse.core.data.food.Meal
import app.pawse.core.ui.theme.PawseType

/**
 * Type it in.
 *
 * The path that always works: no camera, no network, no database that has heard of
 * your lunch. Everything is optional except a name — an entry that records only
 * "porridge, breakfast" is still a true record of the day, and refusing it because
 * the user does not know the calories would be refusing the thing they do know.
 *
 * Figures are entered for the portion actually eaten rather than per 100 g. Asking
 * someone to convert their own bowl of rice into a per-hundred-gram basis is asking
 * them to do arithmetic to satisfy a database schema.
 */
@Composable
fun ManualEntryScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ManualEntryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.logged) {
        if (state.logged) onDone()
    }

    Scaffold(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(modifier = Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("Back") }
            }

            Column(Modifier.padding(horizontal = 24.dp)) {
                Text("Add it by hand", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Only the name is required. Fill in what you know and leave the rest — " +
                        "a blank stays blank rather than becoming a zero, and the day's total says " +
                        "how many items it could not price.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = state.name,
                    onValueChange = viewModel::setName,
                    label = { Text("What was it?") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))
                NumberField(
                    value = state.gramsText,
                    onValueChange = viewModel::setGrams,
                    label = "Grams (optional)",
                )

                Spacer(Modifier.height(20.dp))
                Text(
                    "For the portion you ate",
                    style = PawseType.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                NumberField(state.kcalText, viewModel::setKcal, "Calories (kcal)")
                Spacer(Modifier.height(8.dp))
                NumberField(state.proteinText, viewModel::setProtein, "Protein (g)")
                Spacer(Modifier.height(8.dp))
                NumberField(state.carbsText, viewModel::setCarbs, "Carbohydrate (g)")
                Spacer(Modifier.height(8.dp))
                NumberField(state.fatText, viewModel::setFat, "Fat (g)")

                Spacer(Modifier.height(20.dp))
                Text("Which meal?", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Meal.entries.forEach { meal ->
                        FilterChip(
                            selected = state.meal == meal,
                            onClick = { viewModel.setMeal(meal) },
                            label = { Text(FoodCopy.meal(meal)) },
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = viewModel::log,
                    enabled = state.canLog,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add to log") }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun NumberField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}
