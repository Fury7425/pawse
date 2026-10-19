package app.pawse.feature.food

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pawse.core.data.food.FoodProduct
import app.pawse.core.data.food.Meal
import app.pawse.core.data.food.Nutrients
import app.pawse.core.ui.component.BackLink
import app.pawse.core.ui.component.BottomAction
import app.pawse.core.ui.component.Chip
import app.pawse.core.ui.component.ChoiceRow
import app.pawse.core.ui.component.Glyph
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.KeySwatch
import app.pawse.core.ui.component.PawseCard
import app.pawse.core.ui.component.PawseField
import app.pawse.core.ui.component.Segmented
import app.pawse.core.ui.component.SplitBar
import app.pawse.core.ui.component.cardSurface
import app.pawse.core.ui.theme.PawseType
import kotlin.math.roundToInt

/**
 * How much of it, and when.
 *
 * The portion figures update as the number is typed and are never stored — the
 * entry keeps grams and the nutrition that follows from them, so a portion shown
 * here and the entry that lands in the log cannot disagree. The button says the
 * result: "Add 204 kcal to Snack".
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

    Scaffold(modifier = modifier, containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = padding.calculateTopPadding() + 12.dp, bottom = 140.dp)
                    .padding(horizontal = 24.dp),
            ) {
                BackLink("Back", onClick = onBack)

                val product = state.product
                if (product == null) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = if (state.loading) "Loading…" else "That food is no longer saved.",
                        style = PawseType.Body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@Column
                }

                ProductHeader(product)
                Spacer(Modifier.height(16.dp))
                NutritionPanel(product)

                Spacer(Modifier.height(28.dp))
                Caption("How much?")
                Spacer(Modifier.height(8.dp))
                PawseField(
                    value = state.amountText,
                    onValueChange = viewModel::setAmount,
                    suffix = if (state.usingServings) {
                        if (state.amountText == "1") "serving" else "servings"
                    } else {
                        "g"
                    },
                    numeric = true,
                    height = 64.dp,
                    textStyle = PawseType.Numeral.copy(fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp),
                )
                Spacer(Modifier.height(8.dp))
                val quick = if (state.usingServings) {
                    listOf("½" to "0.5", "1" to "1", "1½" to "1.5", "2" to "2")
                } else {
                    val serving = product.servingGrams?.let { FoodCopy.trim(it) } ?: "300"
                    listOf("100", "200", serving, "500").distinct().map { "$it g" to it }
                }
                val typed = state.amountText.replace(',', '.').toDoubleOrNull()
                ChoiceRow(
                    options = quick,
                    selected = quick.firstOrNull { it.second.toDouble() == typed },
                    onSelect = { viewModel.setAmount(it.second) },
                    label = { it.first },
                )

                product.servingGrams?.let { serving ->
                    Spacer(Modifier.height(8.dp))
                    Segmented(
                        options = listOf(true, false),
                        selected = state.usingServings,
                        onSelect = viewModel::setUsingServings,
                        label = { servings ->
                            if (servings) "Servings" + (product.servingLabel?.let { " · $it" } ?: "") else "Grams"
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    Caption("One serving is ${FoodCopy.grams(serving)}.")
                }

                Spacer(Modifier.height(24.dp))
                Caption("Which meal?")
                Spacer(Modifier.height(8.dp))
                ChoiceRow(
                    options = Meal.entries,
                    selected = state.meal,
                    onSelect = viewModel::setMeal,
                    label = FoodCopy::meal,
                )

                Spacer(Modifier.height(24.dp))
                PortionCard(state.portion)
            }

            val kcal = state.portion?.kcal?.roundToInt()
            BottomAction(
                text = when {
                    !state.canLog -> "Enter an amount"
                    kcal != null -> "Add $kcal kcal to ${FoodCopy.meal(state.meal)}"
                    else -> "Add to ${FoodCopy.meal(state.meal)}"
                },
                onClick = viewModel::log,
                enabled = state.canLog,
            )
        }
    }
}

@Composable
private fun ProductHeader(product: FoodProduct) {
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(56.dp)
                .cardSurface(),
            contentAlignment = Alignment.Center,
        ) {
            Glyph(Glyphs.Carton, size = 26.dp, strokeWidth = 1.6f, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(product.name, style = PawseType.Title)
            val sub = listOfNotNull(
                product.brand,
                product.servingGrams?.let { g -> (product.servingLabel ?: "1 serving") + " is " + FoodCopy.grams(g) },
            )
            if (sub.isNotEmpty()) Caption(sub.joinToString(" · "))
        }
    }
}

/** The packet's own panel, per 100 g, with where the figures came from. */
@Composable
private fun NutritionPanel(product: FoodProduct) {
    val n = product.per100g
    val rows = listOfNotNull(
        n.kcal?.let { "Energy" to "${it.roundToInt()} kcal" },
        n.proteinGrams?.let { "Protein" to FoodCopy.grams(it) },
        n.carbsGrams?.let { "Carbohydrate" to FoodCopy.grams(it) },
        n.fatGrams?.let { "Fat" to FoodCopy.grams(it) },
        n.sugarGrams?.let { "Sugars" to FoodCopy.grams(it) },
        n.sodiumMilligrams?.let { "Sodium" to "${it.roundToInt()} mg" },
    )
    PawseCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Nutrition", style = PawseType.Heading, modifier = Modifier.weight(1f))
            Caption("per 100 g")
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(3.dp).background(MaterialTheme.colorScheme.onSurface))
        rows.forEachIndexed { i, (name, value) ->
            val strong = if (i == 0) PawseType.BodyStrong else PawseType.Body
            Row(Modifier.padding(vertical = 8.dp)) {
                Text(name, style = strong, modifier = Modifier.weight(1f))
                Text(value, style = strong)
            }
            if (i < rows.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Chip(FoodCopy.source(product.source))
        }
        Spacer(Modifier.height(6.dp))
        Caption(FoodCopy.sourceNote(product.source, fromCache = false))
    }
}

@Composable
private fun PortionCard(portion: Nutrients?) {
    PawseCard {
        Caption("This portion")
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(portion?.kcal?.roundToInt()?.toString() ?: "—", style = PawseType.Numeral)
            Spacer(Modifier.width(6.dp))
            Text(
                "kcal",
                style = PawseType.Heading.copy(fontWeight = PawseType.Body.fontWeight),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Caption(
            portion?.let { p ->
                listOfNotNull(
                    p.proteinGrams?.let { "${FoodCopy.trim(it)} g protein" },
                    p.carbsGrams?.let { "${FoodCopy.trim(it)} g carbs" },
                    p.fatGrams?.let { "${FoodCopy.trim(it)} g fat" },
                ).joinToString(" · ").ifEmpty { "No protein, carbs or fat figures." }
            } ?: "Enter an amount.",
        )
        val p = (portion?.proteinGrams ?: 0.0) * 4
        val c = (portion?.carbsGrams ?: 0.0) * 4
        val f = (portion?.fatGrams ?: 0.0) * 9
        val total = p + c + f
        if (total > 0) {
            val ps = (p / total * 100).roundToInt()
            val cs = (c / total * 100).roundToInt()
            val fs = 100 - ps - cs
            val on = MaterialTheme.colorScheme.onSurface
            val onv = MaterialTheme.colorScheme.onSurfaceVariant
            val ol = MaterialTheme.colorScheme.outline
            Spacer(Modifier.height(12.dp))
            SplitBar(listOf(ps.toFloat() to on, cs.toFloat() to onv, fs.toFloat() to ol))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                KeySwatch(on, "Protein $ps%")
                KeySwatch(onv, "Carbs $cs%")
                KeySwatch(ol, "Fat $fs%")
            }
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
