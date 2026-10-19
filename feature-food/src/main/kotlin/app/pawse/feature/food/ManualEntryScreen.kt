package app.pawse.feature.food

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pawse.core.data.food.Meal
import app.pawse.core.ui.component.BackLink
import app.pawse.core.ui.component.BottomAction
import app.pawse.core.ui.component.ChoiceRow
import app.pawse.core.ui.component.PawseField
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

    Scaffold(modifier = modifier, containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = padding.calculateTopPadding() + 12.dp, bottom = 140.dp)
                    .padding(horizontal = 24.dp),
            ) {
                BackLink("Food", onClick = onBack)
                Spacer(Modifier.height(12.dp))
                Text("Add without a barcode", style = PawseType.Title)

                Spacer(Modifier.height(20.dp))
                Caption("What was it?")
                Spacer(Modifier.height(6.dp))
                PawseField(value = state.name, onValueChange = viewModel::setName)

                Spacer(Modifier.height(16.dp))
                Caption("Which meal?")
                Spacer(Modifier.height(6.dp))
                ChoiceRow(
                    options = Meal.entries,
                    selected = state.meal,
                    onSelect = viewModel::setMeal,
                    label = FoodCopy::meal,
                )

                Spacer(Modifier.height(20.dp))
                Row {
                    Caption("Figures, if you know them", Modifier.weight(1f))
                    Caption("per portion")
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Figure("Amount", state.gramsText, viewModel::setGrams, "g", Modifier.weight(1f))
                    Figure("Energy", state.kcalText, viewModel::setKcal, "kcal", Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Figure("Protein", state.proteinText, viewModel::setProtein, "g", Modifier.weight(1f))
                    Figure("Carbs", state.carbsText, viewModel::setCarbs, "g", Modifier.weight(1f))
                    Figure("Fat", state.fatText, viewModel::setFat, "g", Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Caption(
                    "Leave the figures blank and it’s still a true record. A blank stays blank rather " +
                        "than becoming a zero, and the day’s total says it’s one item short.",
                )
            }

            BottomAction(
                text = if (state.canLog) {
                    "Add ${state.name.trim()} to ${FoodCopy.meal(state.meal)}"
                } else {
                    "Name it to add it"
                },
                onClick = viewModel::log,
                enabled = state.canLog,
            )
        }
    }
}

@Composable
private fun Figure(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    unit: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Caption(label)
        Spacer(Modifier.height(4.dp))
        PawseField(value = value, onValueChange = onValueChange, suffix = unit, numeric = true)
    }
}

@Composable
private fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(text, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}
