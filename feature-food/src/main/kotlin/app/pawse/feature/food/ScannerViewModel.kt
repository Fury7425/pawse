package app.pawse.feature.food

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pawse.core.data.food.FoodLookupFailure
import app.pawse.core.data.food.FoodLookupResult
import app.pawse.core.data.food.FoodPreferences
import app.pawse.core.data.food.FoodProduct
import app.pawse.core.data.food.FoodRepository
import app.pawse.core.data.food.FoodSource
import app.pawse.core.data.food.Nutrients
import app.pawse.core.data.food.ocr.LabelBasis
import app.pawse.core.data.food.ocr.LabelReading
import app.pawse.core.data.food.ocr.NutritionLabelParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ScanMode {
    /** Point at the barcode; the chain answers. */
    BARCODE,

    /** Point at the nutrition panel; the phone reads it. Works with the network off. */
    LABEL,
}

sealed interface ScanState {
    data object Scanning : ScanState
    data class LookingUp(val barcode: String) : ScanState
    data class Found(val product: FoodProduct, val fromCache: Boolean) : ScanState
    data class NotFound(val barcode: String, val reason: FoodLookupFailure) : ScanState

    /** A panel read well enough to offer. Still the user's to confirm. */
    data class LabelRead(val reading: LabelReading) : ScanState
}

@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val repository: FoodRepository,
    private val preferences: FoodPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow<ScanState>(ScanState.Scanning)
    val state: StateFlow<ScanState> = _state.asStateFlow()

    private val _mode = MutableStateFlow(ScanMode.BARCODE)
    val mode: StateFlow<ScanMode> = _mode.asStateFlow()

    val networkEnabled: StateFlow<Boolean> = preferences.networkLookupEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val networkAsked: StateFlow<Boolean> = preferences.networkLookupAsked
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Set when a scan needs the consent sheet: asked here, once, in context. */
    private val _consentRequested = MutableStateFlow(false)
    val consentRequested: StateFlow<Boolean> = _consentRequested.asStateFlow()

    private var lastBarcode: String? = null
    private var bestReading: LabelReading? = null

    /**
     * A barcode from the camera.
     *
     * The camera fires this many times a second on the same code, so anything but
     * the first is ignored while a lookup is in flight or a result is on screen.
     * Without that guard, one tin held steady issues a dozen network requests.
     */
    fun onBarcode(raw: String) {
        if (_mode.value != ScanMode.BARCODE) return
        if (_state.value !is ScanState.Scanning) return
        if (raw == lastBarcode) return

        lastBarcode = raw
        _state.value = ScanState.LookingUp(raw)

        viewModelScope.launch {
            when (val result = repository.lookup(raw)) {
                is FoodLookupResult.Found ->
                    _state.value = ScanState.Found(result.resolution.product, result.resolution.fromCache)

                is FoodLookupResult.NotFound -> {
                    _state.value = ScanState.NotFound(result.barcode, result.reason)
                    // The one moment asking about network lookup is useful: they are
                    // holding the product and we could not answer.
                    if (result.reason == FoodLookupFailure.NETWORK_NOT_PERMITTED && !networkAsked.value) {
                        _consentRequested.value = true
                    }
                }
            }
        }
    }

    /**
     * Lines from the text recogniser.
     *
     * Frames arrive continuously and each is a slightly different reading of the
     * same panel, so the best one so far is kept rather than the latest: a hand
     * shake blurs one frame, not all of them, and taking whichever arrived last
     * would make the result a lottery.
     */
    fun onLabelLines(lines: List<String>) {
        if (_mode.value != ScanMode.LABEL) return
        if (_state.value is ScanState.LabelRead) return

        val reading = NutritionLabelParser.parse(lines)
        val best = bestReading
        if (best == null || reading.fieldsFound > best.fieldsFound) {
            bestReading = reading
            if (reading.usable) _state.value = ScanState.LabelRead(reading)
        }
    }

    fun setMode(mode: ScanMode) {
        _mode.value = mode
        bestReading = null
        lastBarcode = null
        _state.value = ScanState.Scanning
    }

    fun scanAgain() {
        lastBarcode = null
        bestReading = null
        _state.value = ScanState.Scanning
    }

    fun dismissConsent() {
        _consentRequested.value = false
        viewModelScope.launch { preferences.setNetworkLookup(false) }
    }

    /** Accepting the explanation immediately retries the scan that prompted it. */
    fun acceptNetworkLookup() {
        _consentRequested.value = false
        viewModelScope.launch {
            preferences.setNetworkLookup(true)
            val barcode = lastBarcode ?: return@launch
            lastBarcode = null
            _state.value = ScanState.Scanning
            onBarcode(barcode)
        }
    }

    /**
     * Turns a confirmed label reading into a product in the local library.
     *
     * [basisGrams] is what the figures are per. The parser supplies it when the
     * panel says so and leaves it null when it does not, in which case the user is
     * asked rather than a hundred grams being assumed — Korean packaging states per
     * serving or per package at least as often as per 100 g, and guessing wrong is
     * a factor-of-two error in someone's day.
     */
    fun saveLabelProduct(
        name: String,
        reading: LabelReading,
        basisGrams: Double,
        onSaved: (Long) -> Unit,
    ) {
        if (basisGrams <= 0.0) return
        viewModelScope.launch {
            val per100g: Nutrients = reading.nutrients.scaled(100.0 / basisGrams)
            val saved = repository.saveProduct(
                FoodProduct(
                    barcode = lastBarcode,
                    name = name.trim().ifBlank { "Photographed label" },
                    brand = null,
                    per100g = per100g,
                    servingGrams = reading.servingGrams ?: basisGrams,
                    servingLabel = null,
                    source = FoodSource.LABEL_OCR,
                    sourceDetail = "Read from the label with the camera" +
                        if (reading.basis != LabelBasis.UNKNOWN) ", stated per ${format(basisGrams)} g" else "",
                    fetchedAtEpochMs = System.currentTimeMillis(),
                ),
            )
            onSaved(saved.id)
        }
    }

    private fun format(grams: Double): String =
        if (grams % 1.0 == 0.0) grams.toInt().toString() else grams.toString()
}
