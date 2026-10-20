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
import app.pawse.core.ui.component.aura
import app.pawse.core.ui.theme.PawseType
import app.pawse.scoring.tr

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
        Box(Modifier.fillMaxSize().aura()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = padding.calculateTopPadding() + 12.dp, bottom = 140.dp)
                    .padding(horizontal = 24.dp),
            ) {
                BackLink(tr("Food", "음식"), onClick = onBack)
                Spacer(Modifier.height(12.dp))
                Text(tr("Add without a barcode", "바코드 없이 추가"), style = PawseType.Title)

                Spacer(Modifier.height(20.dp))
                Caption(tr("What was it?", "무엇을 먹었나요?"))
                Spacer(Modifier.height(6.dp))
                PawseField(value = state.name, onValueChange = viewModel::setName)

                Spacer(Modifier.height(16.dp))
                Caption(tr("Which meal?", "어느 끼니인가요?"))
                Spacer(Modifier.height(6.dp))
                ChoiceRow(
                    options = Meal.entries,
                    selected = state.meal,
                    onSelect = viewModel::setMeal,
                    label = FoodCopy::meal,
                )

                Spacer(Modifier.height(20.dp))
                Row {
                    Caption(tr("Figures, if you know them", "아는 값만 입력하세요"), Modifier.weight(1f))
                    Caption(tr("per portion", "먹은 양 기준"))
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Figure(tr("Amount", "양"), state.gramsText, viewModel::setGrams, "g", Modifier.weight(1f))
                    Figure(tr("Energy", "열량"), state.kcalText, viewModel::setKcal, "kcal", Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Figure(tr("Protein", "단백질"), state.proteinText, viewModel::setProtein, "g", Modifier.weight(1f))
                    Figure(tr("Carbs", "탄수화물"), state.carbsText, viewModel::setCarbs, "g", Modifier.weight(1f))
                    Figure(tr("Fat", "지방"), state.fatText, viewModel::setFat, "g", Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Figure(tr("Caffeine", "카페인"), state.caffeineText, viewModel::setCaffeine, "mg", Modifier.weight(1f))
                    Figure(tr("Alcohol", "알코올"), state.abvText, viewModel::setAbv, "% vol", Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Caption(
                    tr(
                        "Caffeine shapes tonight's energy curve. Alcohol, with the amount in ml, " +
                            "helps explain an odd morning instead of calling it poor recovery.",
                        "카페인은 오늘 밤 에너지 곡선에 반영돼요. 알코올 도수와 양(ml)을 넣으면, 이상한 아침을 " +
                            "회복 부족으로 단정하는 대신 이유를 설명하는 데 도움이 돼요.",
                    ),
                )
                Spacer(Modifier.height(12.dp))
                Caption(
                    tr(
                        "Leave the figures blank and it’s still a true record. A blank stays blank rather " +
                            "than becoming a zero, and the day’s total says it’s one item short.",
                        "값을 비워 둬도 사실 그대로의 기록이에요. 빈칸은 0이 되지 않고 빈칸으로 남으며, " +
                            "하루 합계에 한 항목이 빠졌다고 표시돼요.",
                    ),
                )
            }

            BottomAction(
                text = if (state.canLog) {
                    tr(
                        "Add ${state.name.trim()} to ${FoodCopy.meal(state.meal)}",
                        "${FoodCopy.meal(state.meal)}에 ${state.name.trim()} 추가",
                    )
                } else {
                    tr("Name it to add it", "이름을 입력하면 추가할 수 있어요")
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
