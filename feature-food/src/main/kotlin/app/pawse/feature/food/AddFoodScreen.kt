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
import app.pawse.scoring.tr
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
                BackLink(tr("Back", "뒤로"), onClick = onBack)

                val product = state.product
                if (product == null) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = if (state.loading) tr("Loading…", "불러오는 중…") else tr("That food is no longer saved.", "이 음식은 더 이상 저장되어 있지 않아요."),
                        style = PawseType.Body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@Column
                }

                ProductHeader(product)
                Spacer(Modifier.height(16.dp))
                NutritionPanel(product)

                Spacer(Modifier.height(28.dp))
                Caption(tr("How much?", "얼마나 먹었나요?"))
                Spacer(Modifier.height(8.dp))
                PawseField(
                    value = state.amountText,
                    onValueChange = viewModel::setAmount,
                    suffix = if (state.usingServings) {
                        tr(if (state.amountText == "1") "serving" else "servings", "회 제공량")
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
                            if (servings) tr("Servings", "제공량") + (product.servingLabel?.let { " · $it" } ?: "") else tr("Grams", "그램")
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    Caption(tr("One serving is ${FoodCopy.grams(serving)}.", "1회 제공량은 ${FoodCopy.grams(serving)}이에요."))
                }

                Spacer(Modifier.height(24.dp))
                Caption(tr("Which meal?", "어느 끼니인가요?"))
                Spacer(Modifier.height(8.dp))
                ChoiceRow(
                    options = Meal.entries,
                    selected = state.meal,
                    onSelect = viewModel::setMeal,
                    label = FoodCopy::meal,
                )

                state.portion?.caffeineMg?.let { mg ->
                    Spacer(Modifier.height(24.dp))
                    Caption(
                        tr(
                            "About ${mg.roundToInt()} mg of caffeine, by the label. Count it toward tonight?",
                            "라벨 기준 카페인 약 ${mg.roundToInt()} mg이에요. 오늘 밤 계산에 넣을까요?",
                        ),
                    )
                    Spacer(Modifier.height(8.dp))
                    ChoiceRow(
                        options = listOf(true, false),
                        selected = state.countCaffeine,
                        onSelect = viewModel::setCountCaffeine,
                        label = { if (it) tr("Count it", "넣기") else tr("Leave it out", "빼기") },
                    )
                }

                Spacer(Modifier.height(24.dp))
                PortionCard(state.portion)
            }

            val kcal = state.portion?.kcal?.roundToInt()
            BottomAction(
                text = when {
                    !state.canLog -> tr("Enter an amount", "양을 입력하세요")
                    kcal != null -> tr("Add $kcal kcal to ${FoodCopy.meal(state.meal)}", "${FoodCopy.meal(state.meal)}에 $kcal kcal 추가")
                    else -> tr("Add to ${FoodCopy.meal(state.meal)}", "${FoodCopy.meal(state.meal)}에 추가")
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
                product.servingGrams?.let { g ->
                    (product.servingLabel ?: tr("1 serving", "1회 제공량")) + tr(" is ", " = ") + FoodCopy.grams(g)
                },
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
        n.kcal?.let { tr("Energy", "열량") to "${it.roundToInt()} kcal" },
        n.proteinGrams?.let { tr("Protein", "단백질") to FoodCopy.grams(it) },
        n.carbsGrams?.let { tr("Carbohydrate", "탄수화물") to FoodCopy.grams(it) },
        n.fatGrams?.let { tr("Fat", "지방") to FoodCopy.grams(it) },
        n.sugarGrams?.let { tr("Sugars", "당류") to FoodCopy.grams(it) },
        n.sodiumMilligrams?.let { tr("Sodium", "나트륨") to "${it.roundToInt()} mg" },
        n.caffeineMg?.let { tr("Caffeine", "카페인") to "${it.roundToInt()} mg" },
        n.alcoholGrams?.let { tr("Alcohol", "알코올") to FoodCopy.grams(it) },
    )
    PawseCard(padding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(tr("Nutrition", "영양 정보"), style = PawseType.Heading, modifier = Modifier.weight(1f))
            Caption(tr("per 100 g", "100 g당"))
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
        Caption(tr("This portion", "이번 양"))
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
                    p.proteinGrams?.let { tr("${FoodCopy.trim(it)} g protein", "단백질 ${FoodCopy.trim(it)} g") },
                    p.carbsGrams?.let { tr("${FoodCopy.trim(it)} g carbs", "탄수화물 ${FoodCopy.trim(it)} g") },
                    p.fatGrams?.let { tr("${FoodCopy.trim(it)} g fat", "지방 ${FoodCopy.trim(it)} g") },
                ).joinToString(" · ").ifEmpty { tr("No protein, carbs or fat figures.", "단백질·탄수화물·지방 값이 없어요.") }
            } ?: tr("Enter an amount.", "양을 입력하세요."),
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
                KeySwatch(on, tr("Protein", "단백질") + " $ps%")
                KeySwatch(onv, tr("Carbs", "탄수화물") + " $cs%")
                KeySwatch(ol, tr("Fat", "지방") + " $fs%")
            }
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
