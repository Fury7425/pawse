package app.pawse.feature.home

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pawse.core.data.body.BodyWriter
import app.pawse.core.data.body.InBodyExtra
import app.pawse.core.data.body.InBodyStore
import app.pawse.core.data.body.RecentWeighIn
import app.pawse.core.data.body.WeighIn
import app.pawse.core.data.health.HealthConnectGateway
import app.pawse.scoring.tr
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/**
 * InBody's four numbers: the ruler's step, the range a real one falls in, and
 * where the ruler parks for a row the last result didn't know.
 */
enum class InBodyRow(private val en: String, private val ko: String, val step: Double, val min: Double, val max: Double, val park: Double) {
    WEIGHT("Weight", "체중", 0.1, WeighInUiState.MIN_KG, WeighInUiState.MAX_KG, 70.0),
    MUSCLE("Skeletal muscle mass", "골격근량", 0.1, 10.0, 80.0, 33.0),
    FAT("Body fat mass", "체지방량", 0.1, 2.0, 80.0, 13.0),
    VISCERAL("Visceral fat level", "내장지방 레벨", 1.0, 1.0, 20.0, 6.0),
    ;

    val label: String get() = tr(en, ko)

    fun format(value: Double): String = if (this == VISCERAL) value.roundToInt().toString() else WeighInUiState.oneDecimal(value) + " kg"
}

data class WeighInUiState(
    /** Weigh-in's two ways in: a scale reading, or a whole InBody sheet. */
    val inBody: Boolean = false,
    val kg: Double = InBodyRow.WEIGHT.park,
    val lastKg: Double? = null,
    val recent: List<RecentWeighIn> = emptyList(),
    /** Removed in this visit, keyed by time, kept so Undo can write them back. */
    val removed: Map<Long, WeighIn> = emptyMap(),
    val rows: Map<InBodyRow, Double> = InBodyRow.entries.associateWith { it.park },
    /** Rows marked Don't know: shown as "—", left out of the save, never guessed. */
    val unknown: Set<InBodyRow> = emptySet(),
    /** The row the ruler sets. */
    val picked: InBodyRow = InBodyRow.WEIGHT,
    /** The last result, for each row's "last result" line. */
    val last: Map<InBodyRow, Double> = emptyMap(),
    val lastDate: LocalDate? = null,
    /** The rows came from a photo of the sheet, not the last result. */
    val fromSheet: Boolean = false,
    val reading: Boolean = false,
    /** What the last photo gave, when it gave nothing to check. */
    val readNote: String? = null,
    /** Grants to ask Health Connect for; the screen launches the sheet and clears it. */
    val askFor: Set<String>? = null,
    val problem: String? = null,
    val busy: Boolean = false,
    val saved: Boolean = false,
) {
    fun value(row: InBodyRow): Double = rows.getValue(row)

    /** Fat can never reach the weight it was measured in. */
    fun max(row: InBodyRow): Double = if (row == InBodyRow.FAT) (value(InBodyRow.WEIGHT) - 1.0).coerceAtLeast(row.min) else row.max

    val fatMass: Double? get() = value(InBodyRow.FAT).takeIf { InBodyRow.FAT !in unknown }
    val fatPercent: Double? get() = fatMass?.let { it / value(InBodyRow.WEIGHT) * 100.0 }
    val leanKg: Double? get() = fatMass?.let { value(InBodyRow.WEIGHT) - it }

    /** Katch–McArdle, from lean mass. Shown, not saved: Health Connect would take it for a measurement. */
    val bmr: Double? get() = leanKg?.let { 370.0 + 21.6 * it }

    val saveLabel: String get() = if (inBody) tr("Save InBody result", "인바디 결과 저장") else tr("Save weigh-in", "체중 저장")

    fun toWeighIn(): WeighIn = if (inBody) {
        WeighIn(at = Instant.now(), weightKg = value(InBodyRow.WEIGHT), fatPercent = fatPercent, leanKg = leanKg)
    } else {
        WeighIn(at = Instant.now(), weightKg = kg)
    }

    /** What only Pawse keeps from this result. */
    fun extra(): InBodyExtra? = if (!inBody) {
        null
    } else {
        InBodyExtra(
            date = LocalDate.now(),
            skeletalMuscleKg = value(InBodyRow.MUSCLE).takeIf { InBodyRow.MUSCLE !in unknown },
            visceralFat = value(InBodyRow.VISCERAL).roundToInt().takeIf { InBodyRow.VISCERAL !in unknown },
        )
    }

    companion object {
        const val MIN_KG = 20.0
        const val MAX_KG = 300.0

        fun oneDecimal(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)
    }
}

@HiltViewModel
class WeighInViewModel @Inject constructor(
    private val writer: BodyWriter,
    private val gateway: HealthConnectGateway,
    private val inBodyStore: InBodyStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(WeighInUiState())
    val state: StateFlow<WeighInUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val recent = writer.recent()
            val lastKg = recent.firstOrNull()?.weightKg
            // InBody starts from the last result; a row it didn't know starts as Don't know.
            val extra = inBodyStore.results.first().lastOrNull()
            val fat = writer.lastFatPercent()
            val weight = lastKg ?: InBodyRow.WEIGHT.park
            val last = buildMap {
                lastKg?.let { put(InBodyRow.WEIGHT, it) }
                extra?.skeletalMuscleKg?.let { put(InBodyRow.MUSCLE, it) }
                fat?.let { put(InBodyRow.FAT, it.second / 100.0 * weight) }
                extra?.visceralFat?.let { put(InBodyRow.VISCERAL, it.toDouble()) }
            }
            _state.update { s ->
                s.copy(
                    recent = recent,
                    kg = weight,
                    lastKg = lastKg,
                    rows = InBodyRow.entries.associateWith { last[it] ?: it.park },
                    unknown = InBodyRow.entries.filter { it != InBodyRow.WEIGHT && it !in last }.toSet(),
                    last = last,
                    lastDate = listOfNotNull(extra?.date, fat?.first).maxOrNull(),
                )
            }
        }
    }

    fun setInBody(inBody: Boolean) = _state.update { it.copy(inBody = inBody, problem = null) }

    fun setKg(kg: Double) = _state.update { it.copy(kg = kg.coerceIn(WeighInUiState.MIN_KG, WeighInUiState.MAX_KG), problem = null) }

    /** The ruler's value for the picked row. A lighter weight pulls fat down with it. */
    fun setRow(row: InBodyRow, value: Double) = _state.update { s ->
        val rows = s.rows + (row to value.coerceIn(row.min, s.max(row)))
        val next = s.copy(rows = rows, problem = null)
        next.copy(rows = rows + (InBodyRow.FAT to rows.getValue(InBodyRow.FAT).coerceAtMost(next.max(InBodyRow.FAT))))
    }

    /** Tapping a row hands it the ruler; tapping a Don't know row brings it back. */
    fun pick(row: InBodyRow) = _state.update { it.copy(picked = row, unknown = it.unknown - row) }

    fun toggleUnknown(row: InBodyRow) {
        if (row == InBodyRow.WEIGHT) return
        _state.update { s ->
            if (row in s.unknown) {
                s.copy(unknown = s.unknown - row, picked = row)
            } else {
                s.copy(unknown = s.unknown + row, picked = if (s.picked == row) InBodyRow.WEIGHT else s.picked)
            }
        }
    }

    /**
     * Reads a photo of the result sheet on this phone, fills in what it finds, and
     * deletes the photo. A row it couldn't read becomes Don't know. Nothing is saved
     * until the user checks the numbers and taps Save.
     */
    fun readSheet(photo: Uri, file: File) {
        _state.update { it.copy(reading = true, readNote = null) }
        viewModelScope.launch {
            val lines = runCatching { recognize(photo) }.getOrNull()
            file.delete()
            val read = lines?.let(InBodySheet::parse).orEmpty()
            _state.update { s ->
                val weight = read[SheetField.WEIGHT]
                // Fat mass from a printed percent and weight is arithmetic, not a guess.
                val fat = read[SheetField.FAT_MASS]
                    ?: read[SheetField.FAT_PERCENT]?.let { pct -> (weight ?: s.value(InBodyRow.WEIGHT)) * pct / 100.0 }
                val found = mapOf(
                    InBodyRow.WEIGHT to weight,
                    InBodyRow.MUSCLE to read[SheetField.MUSCLE],
                    InBodyRow.FAT to fat,
                    InBodyRow.VISCERAL to read[SheetField.VISCERAL],
                ).filterValues { it != null }.mapValues { it.value!! }
                if (found.isEmpty()) {
                    s.copy(
                        reading = false,
                        readNote = if (lines == null) {
                            tr("Couldn't read the photo. Try again, or set the numbers with the ruler.", "사진을 읽지 못했어요. 다시 찍거나 눈금자로 입력하세요.")
                        } else {
                            tr("No InBody numbers found. Try a flat, well-lit photo of the whole sheet.", "인바디 숫자를 찾지 못했어요. 결과지 전체를 평평하고 밝게 찍어 보세요.")
                        },
                    )
                } else {
                    s.copy(
                        reading = false,
                        fromSheet = true,
                        rows = s.rows + found,
                        unknown = InBodyRow.entries.filter { it != InBodyRow.WEIGHT && it !in found }.toSet(),
                        picked = InBodyRow.WEIGHT,
                    )
                }
            }
        }
    }

    private suspend fun recognize(photo: Uri): List<SheetLine> = suspendCancellableCoroutine { cont ->
        val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        cont.invokeOnCancellation { recognizer.close() }
        recognizer.process(InputImage.fromFilePath(context, photo))
            .addOnSuccessListener { text ->
                cont.resume(
                    text.textBlocks.flatMap { block ->
                        block.lines.mapNotNull { line -> line.boundingBox?.let { SheetLine(line.text, it.left, it.top, it.right, it.bottom) } }
                    },
                )
            }
            .addOnFailureListener { cont.resumeWithException(it) }
            .addOnCompleteListener { recognizer.close() }
    }

    /** Asks for whichever write grants this weigh-in needs and doesn't have, then saves. */
    fun save() {
        val s = _state.value
        if (s.busy) return
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
        val s = _state.value
        val ok = writer.write(s.toWeighIn())
        if (ok) s.extra()?.let { inBodyStore.add(it) }
        _state.update {
            if (ok) {
                it.copy(busy = false, saved = true)
            } else {
                it.copy(busy = false, problem = tr("Health Connect didn't take it. Nothing was saved.", "헬스 커넥트가 받지 않아서 아무것도 저장되지 않았어요."))
            }
        }
    }

    private suspend fun granted(): Set<String> = runCatching { gateway.grantedPermissions() }.getOrDefault(emptySet())

    companion object {
        val NOT_ALLOWED: String
            get() = tr(
                "Health Connect hasn't allowed Pawse to save body measurements.",
                "헬스 커넥트에서 Pawse의 신체 측정값 저장을 허용하지 않았어요.",
            )
    }
}
