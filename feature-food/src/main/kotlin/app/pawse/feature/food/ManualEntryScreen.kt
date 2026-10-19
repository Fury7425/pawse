package app.pawse.feature.food

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pawse.core.data.food.Meal
import app.pawse.core.ui.component.BottomAction
import app.pawse.core.ui.component.Choices
import app.pawse.core.ui.component.FieldLabel
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.QuietButton
import app.pawse.core.ui.component.SectionLabel
import app.pawse.core.ui.component.UnitField
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
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LaunchedEffect(state.logged) {
        if (state.logged) onDone()
    }

    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = top + 12.dp, bottom = bottom + 140.dp),
        ) {
            QuietButton("Food", onBack, Modifier.offset(x = (-12).dp), glyph = Glyphs.BACK)
            Text(
                "Add without a barcode",
                style = PawseType.Headline,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 12.dp),
            )

            FieldLabel("What was it?", Modifier.padding(top = 20.dp))
            UnitField(
                value = state.name,
                onValueChange = viewModel::setName,
                unit = "",
                keyboardType = KeyboardType.Text,
            )

            FieldLabel("Which meal?", Modifier.padding(top = 16.dp))
            Choices(
                options = Meal.entries.map(FoodCopy::meal),
                selected = Meal.entries.indexOf(state.meal),
                onSelect = { viewModel.setMeal(Meal.entries[it]) },
            )

            SectionLabel("Figures, if you know them", Modifier.padding(top = 20.dp, bottom = 6.dp), trailing = "per portion")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Figure("Amount", state.gramsText, viewModel::setGrams, "g", Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Figure("Energy", state.kcalText, viewModel::setKcal, "kcal", Modifier.weight(1f))
                    Figure("Protein", state.proteinText, viewModel::setProtein, "g", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Figure("Carbs", state.carbsText, viewModel::setCarbs, "g", Modifier.weight(1f))
                    Figure("Fat", state.fatText, viewModel::setFat, "g", Modifier.weight(1f))
                }
            }
            Text(
                "Leave the figures blank and it's still a true record. The day's total will say it's one item short.",
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        BottomAction(
            text = if (state.canLog) "Add ${state.name.trim()} to ${FoodCopy.meal(state.meal)}" else "Name it first",
            onClick = viewModel::log,
            enabled = state.canLog,
        )
    }
}

/** One figure in its own tile: the name above, the number and its unit below. Blank reads "—". */
@Composable
private fun Figure(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    unit: String,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val style = PawseType.MetricValue.copy(color = MaterialTheme.colorScheme.onSurface)
    Column(
        modifier
            .height(64.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .border(1.dp, if (focused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(label, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) Text("—", style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = style,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused }
                        .semantics { contentDescription = "$label in $unit" },
                )
            }
            Text(unit, style = PawseType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
