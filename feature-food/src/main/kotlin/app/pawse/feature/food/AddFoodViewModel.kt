package app.pawse.feature.food

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pawse.core.data.food.FoodProduct
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
import java.util.Locale
import javax.inject.Inject

data class AddFoodUiState(
    val product: FoodProduct? = null,
    val loading: Boolean = true,
    /** What the user typed, kept as text so a half-typed "1." is not a parse error. */
    val amountText: String = "100",
    val usingServings: Boolean = false,
    val meal: Meal = Meal.SNACK,
    val logged: Boolean = false,
) {
    /** Grams the entry will be logged as, or null while the field is unusable. */
    val grams: Double?
        get() {
            val amount = amountText.replace(',', '.').toDoubleOrNull() ?: return null
            if (amount <= 0.0) return null
            val serving = product?.servingGrams
            return if (usingServings && serving != null) amount * serving else amount
        }

    val servings: Double?
        get() = if (usingServings) amountText.replace(',', '.').toDoubleOrNull() else null

    /** What this portion contains. Recomputed as the field changes, never stored. */
    val portion: Nutrients?
        get() = grams?.let { product?.per100g?.forGrams(it) }

    val canLog: Boolean get() = product != null && grams != null
}

@HiltViewModel
class AddFoodViewModel @Inject constructor(
    private val repository: FoodRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val productId: Long = savedStateHandle.get<String>(FoodDestinations.PRODUCT_ARG)
        ?.toLongOrNull() ?: 0L

    private val _state = MutableStateFlow(AddFoodUiState(meal = mealForTimeOfDay()))
    val state: StateFlow<AddFoodUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val product = repository.productById(productId)
            _state.update { current ->
                current.copy(
                    product = product,
                    loading = false,
                    // A serving is what is printed on the packet, so it is the
                    // default when the source stated one. Grams otherwise, because
                    // "1 serving" of something with no stated serving is not a
                    // quantity at all.
                    usingServings = product?.servingGrams != null,
                    amountText = if (product?.servingGrams != null) "1" else "100",
                )
            }
        }
    }

    fun setAmount(text: String) {
        _state.update { it.copy(amountText = text.filter { char -> char.isDigit() || char == '.' || char == ',' }) }
    }

    fun setUsingServings(usingServings: Boolean) {
        _state.update { current ->
            if (usingServings == current.usingServings) return@update current
            val serving = current.product?.servingGrams
            val amount = current.amountText.replace(',', '.').toDoubleOrNull()
            // Converting the number the user already typed, rather than resetting
            // it: switching units should not silently change the portion.
            val converted = when {
                amount == null || serving == null || serving <= 0.0 -> current.amountText
                usingServings -> trim(amount / serving)
                else -> trim(amount * serving)
            }
            current.copy(usingServings = usingServings, amountText = converted)
        }
    }

    fun setMeal(meal: Meal) {
        _state.update { it.copy(meal = meal) }
    }

    fun log() {
        val current = _state.value
        val product = current.product ?: return
        val grams = current.grams ?: return
        viewModelScope.launch {
            repository.logProduct(
                product = product,
                meal = current.meal,
                grams = grams,
                servings = current.servings,
            )
            _state.update { it.copy(logged = true) }
        }
    }

    private fun trim(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.getDefault(), "%.1f", value)

    private companion object {
        /**
         * The meal it probably is, by the clock. A guess the user can override in
         * one tap beats making them choose every time.
         */
        fun mealForTimeOfDay(now: LocalTime = LocalTime.now()): Meal = when (now.hour) {
            in 4..10 -> Meal.BREAKFAST
            in 11..14 -> Meal.LUNCH
            in 17..21 -> Meal.DINNER
            else -> Meal.SNACK
        }
    }
}
