package app.pawse.feature.food

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pawse.core.data.food.FoodProduct
import app.pawse.core.data.food.Meal
import app.pawse.core.data.food.Nutrients
import app.pawse.core.ui.component.BottomAction
import app.pawse.core.ui.component.Chip
import app.pawse.core.ui.component.Choices
import app.pawse.core.ui.component.FieldLabel
import app.pawse.core.ui.component.Glyph
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.PawseCard
import app.pawse.core.ui.component.QuietButton
import app.pawse.core.ui.component.Segmented
import app.pawse.core.ui.theme.PawseType
import kotlin.math.roundToInt

/**
 * How much of it, and when.
 *
 * The portion figures update as the number is typed and are never stored — the
 * entry keeps grams and the nutrition that follows from them, so a portion shown
 * here and the entry that lands in the log cannot disagree.
 *
 * Every amount is on screen at once rather than behind a stepper, and the button
 * says the result: "Add 204 kcal to Snack".
 */
@Composable
fun AddFoodScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddFoodViewModel = hiltViewModel(),
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
            QuietButton("Scan", onBack, Modifier.offset(x = (-12).dp), glyph = Glyphs.BACK)

            val product = state.product
            if (product == null) {
                Text(
                    text = if (state.loading) "Loading…" else "That food is no longer saved.",
                    style = PawseType.Body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                return@Column
            }

            ProductHeader(product, Modifier.padding(top = 12.dp))
            NutritionPanel(product, Modifier.padding(top = 16.dp))

            val serving = product.servingGrams
            val servings = state.usingServings && serving != null
            val amount = state.amountText.replace(',', '.').toDoubleOrNull()
            FieldLabel("How much?", Modifier.padding(top = 28.dp, bottom = 2.dp))
            AmountField(
                value = state.amountText,
                onValueChange = viewModel::setAmount,
                unit = when {
                    !servings -> "g"
                    amount == 1.0 -> "serving"
                    else -> "servings"
                },
            )

            val quick = if (servings) {
                listOf("½" to 0.5, "1" to 1.0, "1½" to 1.5, "2" to 2.0)
            } else {
                (listOf(100.0, 200.0) + listOfNotNull(serving) + listOf(500.0))
                    .distinct()
                    .sorted()
                    .let { if (it.size < 4) listOf(50.0) + it else it }
                    .take(4)
                    .map { "${FoodCopy.trim(it)} g" to it }
            }
            Choices(
                options = quick.map { it.first },
                selected = quick.indexOfFirst { it.second == amount },
                onSelect = { viewModel.setAmount(FoodCopy.trim(quick[it].second)) },
                modifier = Modifier.padding(top = 8.dp),
            )

            if (serving != null) {
                Segmented(
                    options = listOf("Servings" + (product.servingLabel?.let { " · $it" } ?: ""), "Grams"),
                    selected = if (state.usingServings) 0 else 1,
                    onSelect = { viewModel.setUsingServings(it == 0) },
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    "One serving is ${FoodCopy.grams(serving)}.",
                    style = PawseType.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Text(
                text = buildAnnotatedString {
                    append("Which meal? ")
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) {
                        append(FoodCopy.meal(state.suggestedMeal))
                    }
                    append(" fits the time")
                },
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
            )
            Choices(
                options = Meal.entries.map(FoodCopy::meal),
                selected = Meal.entries.indexOf(state.meal),
                onSelect = { viewModel.setMeal(Meal.entries[it]) },
            )

            PortionCard(state.portion, Modifier.padding(top = 24.dp))
        }

        val kcal = state.portion?.kcal?.roundToInt()
        val meal = FoodCopy.meal(state.meal)
        BottomAction(
            text = when {
                !state.canLog -> "Enter an amount"
                kcal != null -> "Add $kcal kcal to $meal"
                else -> "Add to $meal"
            },
            onClick = viewModel::log,
            enabled = state.canLog,
        )
    }
}

@Composable
private fun ProductHeader(product: FoodProduct, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val tile = RoundedCornerShape(16.dp)
        Box(
            Modifier
                .size(56.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer, tile)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, tile),
            contentAlignment = Alignment.Center,
        ) {
            Glyph(Glyphs.PACKAGE, size = 26.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant, strokeWidth = 1.6f)
        }
        Column(Modifier.weight(1f)) {
            Text(product.name, style = PawseType.Headline, color = MaterialTheme.colorScheme.onSurface)
            val sub = listOfNotNull(
                product.brand,
                product.servingGrams?.let { "1 serving is ${FoodCopy.grams(it)}" },
            ).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The packet's own panel, per 100 g, and where it came from. */
@Composable
private fun NutritionPanel(product: FoodProduct, modifier: Modifier = Modifier) {
    val n = product.per100g
    val rows = listOf(
        "Energy" to n.kcal?.let { "${it.roundToInt()} kcal" },
        "Protein" to n.proteinGrams?.let { FoodCopy.grams(it) },
        "Carbohydrate" to n.carbsGrams?.let { FoodCopy.grams(it) },
        "Fat" to n.fatGrams?.let { FoodCopy.grams(it) },
    )
    PawseCard(modifier, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)) {
        Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
            Text("Nutrition", style = PawseType.Title, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text("per 100 g", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(thickness = 3.dp, color = MaterialTheme.colorScheme.onSurface)
        rows.forEachIndexed { i, (name, value) ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val style = if (i == 0) PawseType.Action else PawseType.Body
            Row(Modifier.padding(vertical = 8.dp)) {
                Text(name, style = style, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Text(value ?: "—", style = style, color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            }
        }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(FoodCopy.source(product.source))
            Text(
                FoodCopy.sourceShortNote(product.source),
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** The amount, large, with its unit written beside it. */
@Composable
private fun AmountField(value: String, onValueChange: (String) -> Unit, unit: String) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .border(1.dp, if (focused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline, shape)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = PawseType.Display.copy(
                fontSize = 28.sp,
                lineHeight = 36.sp,
                letterSpacing = (-0.5).sp,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = "Amount in $unit" },
        )
        Text(unit, style = PawseType.Title.copy(fontWeight = FontWeight.Normal), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** What this portion holds, and each macro's share of its energy. */
@Composable
private fun PortionCard(portion: Nutrients?, modifier: Modifier = Modifier) {
    PawseCard(modifier) {
        Text("This portion", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text(portion?.kcal?.roundToInt()?.toString() ?: "—", style = PawseType.Display, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "kcal",
                style = PawseType.Title.copy(fontWeight = FontWeight.Normal),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
            )
        }
        Text(
            text = portion?.let { macroLine(it) } ?: "Enter an amount.",
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        portion?.let { EnergyShare(it) }
    }
}

private fun macroLine(n: Nutrients): String = listOfNotNull(
    n.proteinGrams?.let { "${FoodCopy.trim(it)} g protein" },
    n.carbsGrams?.let { "${FoodCopy.trim(it)} g carbs" },
    n.fatGrams?.let { "${FoodCopy.trim(it)} g fat" },
).joinToString(" · ").ifEmpty { "No protein, carb or fat figures for this food." }

@Composable
private fun EnergyShare(n: Nutrients) {
    val pk = (n.proteinGrams ?: 0.0) * 4
    val ck = (n.carbsGrams ?: 0.0) * 4
    val fk = (n.fatGrams ?: 0.0) * 9
    val total = pk + ck + fk
    if (total <= 0) return
    val p = (pk / total * 100).roundToInt()
    val c = (ck / total * 100).roundToInt()
    val f = 100 - p - c
    val parts = listOf(
        Triple("Protein", p, MaterialTheme.colorScheme.onSurface),
        Triple("Carbs", c, MaterialTheme.colorScheme.onSurfaceVariant),
        Triple("Fat", f, MaterialTheme.colorScheme.outline),
    )
    Row(
        Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .semantics { contentDescription = "Share of energy: protein $p%, carbs $c%, fat $f%" },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        parts.filter { it.second > 0 }.forEach { (_, share, color) ->
            Box(
                Modifier
                    .weight(share.toFloat())
                    .fillMaxHeight()
                    .background(color),
            )
        }
    }
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        parts.forEach { (name, share, color) -> ShareKey("$name $share%", color) }
    }
}

@Composable
private fun ShareKey(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(6.dp))
        Text(text, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
