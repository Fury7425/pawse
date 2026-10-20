package app.pawse.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pawse.core.data.body.BodyWriter
import app.pawse.core.data.body.RecentWeighIn
import app.pawse.core.data.body.WeighIn
import app.pawse.core.data.health.HealthConnectGateway
import app.pawse.scoring.tr
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject

/** The optional figures under "Body composition", with the range a real one falls in. */
enum class BodyFigure(private val en: String, private val ko: String, val unit: String) {
    FAT("Body fat", "체지방률", "%"),
    LEAN("Lean mass", "제지방량", "kg"),
    WATER("Body water", "체수분", "kg"),
    BONE("Bone mass", "골량", "kg");

    val label: String get() = tr(en, ko)

    /** Lean and water can't outweigh the person they were measured on. */
    fun range(weightKg: Double?): ClosedFloatingPointRange<Double> = when (this) {
        FAT -> 2.0..75.0
        LEAN -> 10.0..(weightKg ?: WeighInUiState.MAX_KG)
        WATER -> 5.0..(weightKg ?: WeighInUiState.MAX_KG)
        BONE -> 0.5..10.0
    }
}

data class WeighInUiState(
    val weightText: String = "",
    val earlier: Boolean = false,
    val earlierAt: LocalDateTime = LocalDateTime.now().withSecond(0).withNano(0),
    val compositionOpen: Boolean = true,
    val figures: Map<BodyFigure, String> = emptyMap(),
    val recent: List<RecentWeighIn> = emptyList(),
    /** Removed in this visit, keyed by time, kept so Undo can write them back. */
    val removed: Map<Long, WeighIn> = emptyMap(),
    /** Grants to ask Health Connect for; the screen launches the sheet and clears it. */
    val askFor: Set<String>? = null,
    val problem: String? = null,
    val busy: Boolean = false,
    val saved: Boolean = false,
) {
    val weightKg: Double? get() = parse(weightText)?.takeIf { it in MIN_KG..MAX_KG }
    val weightBad: Boolean get() = weightText.isNotBlank() && weightKg == null

    fun figure(figure: BodyFigure): Double? = parse(figures[figure].orEmpty())

    fun figureError(figure: BodyFigure): String? {
        val text = figures[figure].orEmpty()
        if (text.isBlank()) return null
        val range = figure.range(weightKg)
        val value = parse(text)
        if (value != null && value in range) return null
        return if (figure.unit == "%") {
            val lo = range.start.toInt()
            val hi = range.endInclusive.toInt()
            tr("Between $lo and $hi%", "$lo–$hi% 사이로 입력하세요")
        } else {
            val lo = oneDecimal(range.start)
            val hi = oneDecimal(range.endInclusive)
            tr("Between $lo and $hi kg", "$lo–$hi kg 사이로 입력하세요")
        }
    }

    val filledFigures: Int get() = BodyFigure.entries.count { figure(it) != null && figureError(it) == null }
    val anyFigureBad: Boolean get() = BodyFigure.entries.any { figureError(it) != null }

    /** A weigh-in in the future can't be true yet. */
    val timeBad: Boolean get() = earlier && earlierAt.isAfter(LocalDateTime.now())

    val canSave: Boolean get() = weightKg != null && !anyFigureBad && !timeBad && !busy

    val saveLabel: String
        get() = when {
            weightKg == null -> tr("Enter a weight", "체중을 입력하세요")
            anyFigureBad -> tr("Check the figure in red", "빨간색 값을 확인하세요")
            timeBad -> tr("Pick a time before now", "지금보다 이전 시간을 고르세요")
            else -> {
                val kg = oneDecimal(weightKg!!)
                tr(
                    "Save $kg kg" + if (filledFigures > 0) " and $filledFigures more" else "",
                    "$kg kg" + (if (filledFigures > 0) " 외 ${filledFigures}개" else "") + " 저장",
                )
            }
        }

    fun toWeighIn(zone: ZoneId = ZoneId.systemDefault()): WeighIn {
        fun ok(f: BodyFigure) = figure(f)?.takeIf { figureError(f) == null }
        return WeighIn(
            at = if (earlier) earlierAt.atZone(zone).toInstant() else java.time.Instant.now(),
            weightKg = weightKg!!,
            fatPercent = ok(BodyFigure.FAT),
            leanKg = ok(BodyFigure.LEAN),
            waterKg = ok(BodyFigure.WATER),
            boneKg = ok(BodyFigure.BONE),
        )
    }

    companion object {
        const val MIN_KG = 20.0
        const val MAX_KG = 300.0

        fun parse(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

        fun oneDecimal(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)
    }
}

@HiltViewModel
class WeighInViewModel @Inject constructor(
    private val writer: BodyWriter,
    private val gateway: HealthConnectGateway,
) : ViewModel() {

    private val _state = MutableStateFlow(WeighInUiState())
    val state: StateFlow<WeighInUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val recent = writer.recent()
            // Start from the last weigh-in: most mornings are a tenth or two away from it.
            _state.update { s ->
                s.copy(
                    recent = recent,
                    weightText = s.weightText.ifEmpty { recent.firstOrNull()?.let { WeighInUiState.oneDecimal(it.weightKg) }.orEmpty() },
                )
            }
        }
    }

    fun setWeight(text: String) = _state.update { it.copy(weightText = clean(text), problem = null) }

    fun setWeightKg(kg: Double) = setWeight(WeighInUiState.oneDecimal(kg))

    fun setEarlier(earlier: Boolean) = _state.update { it.copy(earlier = earlier) }

    fun setEarlierAt(at: LocalDateTime) = _state.update { it.copy(earlierAt = at) }

    fun toggleComposition() = _state.update { it.copy(compositionOpen = !it.compositionOpen) }

    fun setFigure(figure: BodyFigure, text: String) =
        _state.update { it.copy(figures = it.figures + (figure to clean(text)), problem = null) }

    /** Asks for whichever write grants this weigh-in needs and doesn't have, then saves. */
    fun save() {
        val s = _state.value
        if (!s.canSave) return
        viewModelScope.launch {
            val missing = writer.permissionsFor(s.toWeighIn()) - granted()
            if (missing.isEmpty()) write() else _state.update { it.copy(askFor = missing) }
        }
    }

    fun onAskLaunched() = _state.update { it.copy(askFor = null) }

    /** Re-reads the grants rather than trusting the sheet's answer, then saves or says why not. */
    fun onGrantResult() {
        viewModelScope.launch {
            if ((writer.permissionsFor(_state.value.toWeighIn()) - granted()).isEmpty()) {
                write()
            } else {
                _state.update { it.copy(problem = NOT_ALLOWED) }
            }
        }
    }

    fun remove(entry: RecentWeighIn) {
        viewModelScope.launch {
            val removed = writer.remove(entry.atEpochMs)
            _state.update {
                if (removed == null) {
                    it.copy(problem = tr("Couldn't remove it from Health Connect, so it's still there.", "헬스 커넥트에서 삭제하지 못해서 그대로 남아 있어요."))
                } else {
                    it.copy(removed = it.removed + (entry.atEpochMs to removed))
                }
            }
        }
    }

    fun undoRemove(atEpochMs: Long) {
        val weighIn = _state.value.removed[atEpochMs] ?: return
        viewModelScope.launch {
            if (writer.write(weighIn)) {
                val recent = writer.recent()
                _state.update { it.copy(removed = it.removed - atEpochMs, recent = recent) }
            } else {
                _state.update { it.copy(problem = tr("Health Connect didn't take it back. It stays removed.", "헬스 커넥트가 되돌리기를 받지 않아서 삭제된 채로 있어요.")) }
            }
        }
    }

    private suspend fun write() {
        _state.update { it.copy(busy = true, problem = null) }
        val ok = writer.write(_state.value.toWeighIn())
        _state.update {
            if (ok) {
                it.copy(busy = false, saved = true)
            } else {
                it.copy(busy = false, problem = tr("Health Connect didn't take it. Nothing was saved.", "헬스 커넥트가 받지 않아서 아무것도 저장되지 않았어요."))
            }
        }
    }

    private suspend fun granted(): Set<String> = runCatching { gateway.grantedPermissions() }.getOrDefault(emptySet())

    private fun clean(text: String) = text.filter { it.isDigit() || it == '.' || it == ',' }.take(6)

    companion object {
        val NOT_ALLOWED: String
            get() = tr(
                "Health Connect hasn't allowed Pawse to save body measurements.",
                "헬스 커넥트에서 Pawse의 신체 측정값 저장을 허용하지 않았어요.",
            )
    }
}
