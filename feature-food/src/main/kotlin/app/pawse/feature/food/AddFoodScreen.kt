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
import app.pawse.core.ui.component.Chip
import app.pawse.core.ui.theme.PawseType

/**
 * How much of it, and when.
 *
 * The portion figures update as the number is typed and are never stored — the
 * entry keeps grams and the nutrition that follows from them, so a portion shown
 * here and the entry that lands in the log cannot disagree.
 */
@Composable
fun AddFoodScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddFoodViewModel = hiltViewModel(),
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

            val product = state.product
            if (product == null) {
                Column(Modifier.padding(24.dp)) {
                    Text(
                        text = if (state.loading) "Loading…" else "That food is no longer saved.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            Column(Modifier.padding(horizontal = 24.dp)) {
                Text(product.name, style = MaterialTheme.typography.headlineSmall)
                product.brand?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(Modifier.height(12.dp))
                Chip(text = FoodCopy.source(product.source))
                Spacer(Modifier.height(6.dp))
                Text(
                    text = FoodCopy.sourceNote(product.source, fromCache = false),
                    style = PawseType.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(24.dp))
                Text("How much?", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = state.amountText,
                    onValueChange = viewModel::setAmount,
                    label = { Text(if (state.usingServings) "Servings" else "Grams") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (product.servingGrams != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.usingServings,
                            onClick = { viewModel.setUsingServings(true) },
                            label = {
                                Text(
                                    "Servings" + (product.servingLabel?.let { " · $it" } ?: ""),
                                )
                            },
                        )
                        FilterChip(
                            selected = !state.usingServings,
                            onClick = { viewModel.setUsingServings(false) },
                            label = { Text("Grams") },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "One serving is ${FoodCopy.grams(product.servingGrams!!)}.",
                        style = PawseType.Label,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(24.dp))
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
                val portion = state.portion
                Text(
                    text = if (portion == null) {
                        "Enter an amount."
                    } else {
                        "This portion: ${FoodCopy.summary(portion)}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

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
