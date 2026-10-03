package app.pawse.feature.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pawse.core.data.food.FoodRepository
import app.pawse.core.data.food.Meal
import app.pawse.core.data.food.Nutrients
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalTime
import javax.inject.Inject

data class ManualEntryUiState(
    val name: String = "",
    val gramsText: String = "",
    val kcalText: String = "",
    val proteinText: String = "",
    val carbsText: String = "",
    val fatText: String = "",
    val meal: Meal = Meal.SNACK,
    val logged: Boolean = false,
) {
    /** A name is the only requirement: it is the one thing the user certainly knows. */
    val canLog: Boolean get() = name.isNotBlank()

    val nutrients: Nutrients
        get() = Nutrients(
            kcal = kcalText.toNumber(),
            proteinGrams = proteinText.toNumber(),
            carbsGrams = carbsText.toNumber(),
            fatGrams = fatText.toNumber(),
        )

    val grams: Double get() = gramsText.toNumber() ?: 0.0

    private fun String.toNumber(): Double? = replace(',', '.').toDoubleOrNull()
}

@HiltViewModel
class ManualEntryViewModel @Inject constructor(
    private val repository: FoodRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ManualEntryUiState(meal = mealForTimeOfDay()))
    val state: StateFlow<ManualEntryUiState> = _state.asStateFlow()

    fun setName(value: String) = _state.update { it.copy(name = value) }
    fun setGrams(value: String) = _state.update { it.copy(gramsText = value.numeric()) }
    fun setKcal(value: String) = _state.update { it.copy(kcalText = value.numeric()) }
    fun setProtein(value: String) = _state.update { it.copy(proteinText = value.numeric()) }
    fun setCarbs(value: String) = _state.update { it.copy(carbsText = value.numeric()) }
    fun setFat(value: String) = _state.update { it.copy(fatText = value.numeric()) }
    fun setMeal(meal: Meal) = _state.update { it.copy(meal = meal) }

    /**
     * Logged as a one-off rather than as a product.
     *
     * "Mum's stew, 620 kcal" is a record of a meal, not a reusable food, and adding
     * it to the scanned-foods library would fill that library with things that will
     * never match a barcode again.
     */
    fun log() {
        val current = _state.value
        if (!current.canLog) return
        viewModelScope.launch {
            repository.logOneOff(
                name = current.name.trim(),
                nutrients = current.nutrients,
                meal = current.meal,
                grams = current.grams,
            )
            _state.update { it.copy(logged = true) }
        }
    }

    private fun String.numeric(): String = filter { it.isDigit() || it == '.' || it == ',' }

    private companion object {
        fun mealForTimeOfDay(now: LocalTime = LocalTime.now()): Meal = when (now.hour) {
            in 4..10 -> Meal.BREAKFAST
            in 11..14 -> Meal.LUNCH
            in 17..21 -> Meal.DINNER
            else -> Meal.SNACK
        }
    }
}
