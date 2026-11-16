package app.pawse.core.model

import android.app.ActivityManager
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.pawse.scoring.attention.Fact
import app.pawse.scoring.attention.NumberCheck
import app.pawse.scoring.tr
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** What the switch in settings shows. */
sealed interface ModelState {
    data object Off : ModelState

    /** On, waiting for the user to start the download. */
    data object NotDownloaded : ModelState

    /** [waiting]: queued for Wi-Fi, a connection or a retry. [wifiOnly]: the user did not allow mobile data. */
    data class Downloading(val bytes: Long, val waiting: Boolean, val wifiOnly: Boolean) : ModelState

    /** The last download stopped for good or did not match its hash. Nothing was kept. */
    data object Failed : ModelState

    data class Ready(val engine: Engine, val since: LocalDate?) : ModelState
}

/** Every number the morning shows: lines for the model to read, and the facts its sentences are held to. */
data class FactSheet(val lines: List<String>, val facts: List<Fact>, val guidance: String)

data class Answer(val text: String, val kind: Kind) {
    enum class Kind { SYMPTOM, CHECKED, GENERAL, UNCHECKED }
}

/** One question and its answer; the answer is null while it is being written. */
data class Turn(val question: String, val answer: Answer?)

/**
 * The optional on-device model. It words numbers Pawse already worked out and never
 * works anything out itself: it is given only the morning's fact sheet, every number
 * it writes goes through [NumberCheck], and a sentence that fails is left out.
 *
 * LFM3-1.7B runs through llama.cpp after a download the user asked for; a phone that
 * already has Gemini Nano uses that instead and downloads nothing.
 */
@Singleton
class PawseModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: ModelPreferences,
) {
    private val work = WorkManager.getInstance(context)
    private val nano by lazy { Generation.getClient() }
    private val lock = Mutex()
    private val summaryLock = Mutex()
    private var handle = 0L

    // ponytail: in memory, so a killed app forgets today's questions early. Room if
    // people want them back after that.
    private val talk = MutableStateFlow<Pair<LocalDate, List<Turn>>?>(null)

    val state: Flow<ModelState> = combine(
        prefs.enabled,
        prefs.engine,
        prefs.readySince,
        work.getWorkInfosForUniqueWorkFlow(DOWNLOAD),
    ) { on, engine, since, infos ->
        // REPLACE leaves the cancelled request in the list; the live one is unfinished.
        val job = infos.firstOrNull { !it.state.isFinished } ?: infos.lastOrNull()
        val part = ModelFile.part(context)
        val wifiOnly = job?.constraints?.requiredNetworkType == NetworkType.UNMETERED
        when {
            !on -> ModelState.Off
            engine == Engine.NANO || ModelFile.done(context).exists() -> ModelState.Ready(engine, since)
            job?.state == WorkInfo.State.RUNNING ->
                ModelState.Downloading(job.progress.getLong(PROGRESS_BYTES, part.length()), waiting = false, wifiOnly = wifiOnly)
            job?.state == WorkInfo.State.ENQUEUED || job?.state == WorkInfo.State.BLOCKED ->
                ModelState.Downloading(part.length(), waiting = true, wifiOnly = wifiOnly)
            job?.state == WorkInfo.State.FAILED -> ModelState.Failed
            else -> ModelState.NotDownloaded
        }
    }.flowOn(Dispatchers.IO)

    val dropped: Flow<Int> = prefs.dropped

    /** The switch. False when this phone can run neither engine; Gemini Nano, when present, needs no download. */
    suspend fun turnOn(): Boolean {
        val today = LocalDate.now()
        return when {
            nanoReady() -> {
                prefs.turnOn(Engine.NANO, today)
                true
            }
            canRunLlama -> {
                prefs.turnOn(Engine.LLAMA, today.takeIf { ModelFile.done(context).exists() })
                true
            }
            else -> false
        }
    }

    /** Whether this phone can run LFM3 through llama.cpp at all, Gemini Nano or not. */
    val canRunLlama: Boolean by lazy { llamaRuns() && enoughMemory() }

    /**
     * LFM3 instead of Gemini Nano, for people who would rather have it. It needs its
     * download, and summaries start again the morning after that finishes.
     */
    suspend fun preferLlama(): Boolean {
        if (!canRunLlama) return false
        prefs.turnOn(Engine.LLAMA, LocalDate.now().takeIf { ModelFile.done(context).exists() })
        return true
    }

    /**
     * Asked first, with its size. Wi-Fi only unless the user chose mobile data; choosing
     * it while waiting replaces the queued request, and the download resumes where it was.
     */
    fun download(mobileData: Boolean) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (mobileData) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        work.enqueueUniqueWork(DOWNLOAD, ExistingWorkPolicy.REPLACE, request)
    }

    /** Room for what is left of the download, with some to spare. */
    fun hasRoom(): Boolean =
        context.noBackupFilesDir.usableSpace > BYTES - ModelFile.part(context).length() + SPARE_BYTES

    /** Off, or Cancel: the download stops and nothing is kept, file, summary or conversation. */
    suspend fun turnOff() {
        prefs.turnOff()
        work.cancelUniqueWork(DOWNLOAD)
        withTimeoutOrNull(STOP_WAIT_MS) {
            work.getWorkInfosForUniqueWorkFlow(DOWNLOAD).first { infos -> infos.all { it.state.isFinished } }
        }
        release()
        ModelFile.part(context).delete()
        ModelFile.done(context).delete()
        talk.value = null
    }

    fun summary(date: LocalDate): Flow<Summary?> = prefs.summary.map { s -> s?.takeIf { it.date == date } }

    /** True the first time Scores shows [date]'s summary, if the model wrote it. Pawse's own line is already on top. */
    fun rises(date: LocalDate): Flow<Boolean> =
        combine(prefs.summary, prefs.risen) { s, risen -> s?.date == date && s.written && risen != date }

    suspend fun markRisen(date: LocalDate) = prefs.markRisen(date)

    /**
     * This morning's summary, written once: the first time Pawse opens after a night,
     * starting the morning after the model became ready. When nothing survives the
     * number check, Pawse's own guidance line stands in. Never a notification.
     */
    suspend fun writeSummary(date: LocalDate, sheet: FactSheet) = summaryLock.withLock {
        val ready = state.first() as? ModelState.Ready ?: return@withLock
        val since = ready.since ?: return@withLock
        if (!date.isAfter(since) || prefs.summary.first()?.date == date) return@withLock
        val checked = write(summarySystem(), summaryUser(sheet), SUMMARY_TOKENS)?.let { NumberCheck.check(it, sheet.facts) }
        prefs.addDropped(checked?.dropped ?: 0)
        val text = checked?.text
        prefs.setSummary(Summary(date, text ?: sheet.guidance, written = text != null, System.currentTimeMillis()))
    }

    /** Today's questions. A new morning starts empty. */
    fun conversation(date: LocalDate): Flow<List<Turn>> = talk.map { t -> t?.takeIf { it.first == date }?.second.orEmpty() }

    /** Ask Pawse. Symptom questions never reach the model, and everything it writes is checked. */
    suspend fun ask(date: LocalDate, question: String, sheet: FactSheet) {
        val q = question.trim().takeIf { it.isNotEmpty() } ?: return
        turns(date) { it + Turn(q, null) }
        val answer = answer(q, sheet)
        turns(date) { list -> list.map { if (it.question == q && it.answer == null) Turn(q, answer) else it } }
    }

    /** Frees the model's memory; the next question loads it again in a second or two. */
    suspend fun release() = lock.withLock {
        if (handle != 0L) {
            Llama.free(handle)
            handle = 0L
        }
    }

    private fun turns(date: LocalDate, change: (List<Turn>) -> List<Turn>) {
        talk.update { t -> date to change(t?.takeIf { it.first == date }?.second.orEmpty()) }
    }

    private suspend fun answer(question: String, sheet: FactSheet): Answer {
        if (NumberCheck.symptoms(question)) return Answer(NumberCheck.symptomAnswer(), Answer.Kind.SYMPTOM)
        val written = write(askSystem(), askUser(question, sheet), ANSWER_TOKENS)
            ?: return Answer(
                tr("Pawse couldn’t write an answer just now. Try again in a moment.", "지금은 답을 쓰지 못했어요. 잠시 후 다시 물어봐 주세요."),
                Answer.Kind.UNCHECKED,
            )
        val checked = NumberCheck.check(written, sheet.facts)
        prefs.addDropped(checked.dropped)
        return label(checked.text)
    }

    /** One reply from whichever engine is on, markup stripped. Null when it wrote nothing usable. */
    private suspend fun write(system: String, user: String, maxTokens: Int): String? = lock.withLock {
        withContext(Dispatchers.Default) {
            val reply = try {
                when (prefs.engine.first()) {
                    Engine.NANO -> nano.generateContent(
                        generateContentRequest(TextPart("$system\n\n$user")) {
                            temperature = TEMPERATURE
                            topK = TOP_K
                            maxOutputTokens = maxTokens
                        },
                    ).candidates.firstOrNull()?.text
                    Engine.LLAMA -> llama(system, user, maxTokens)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            reply?.let(::clean)?.takeIf { it.isNotEmpty() }
        }
    }

    private fun llama(system: String, user: String, maxTokens: Int): String? {
        if (handle == 0L) {
            val file = ModelFile.done(context).takeIf { it.exists() } ?: return null
            handle = Llama.load(file.path, (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 4), CONTEXT)
            if (handle == 0L) return null
        }
        return Llama.generate(handle, lfmPrompt(system, user).encodeToByteArray(), maxTokens, TEMPERATURE, TOP_K)
            ?.decodeToString()
    }

    private suspend fun nanoReady(): Boolean = try {
        nano.checkStatus() == FeatureStatus.AVAILABLE
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** A 1.7B model beside the app wants about 3 GB of memory on the phone. */
    private fun enoughMemory(): Boolean {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return info.totalMem >= MIN_MEMORY_BYTES
    }

    companion object {
        /** LFM3-1.7B-QAD-Q4_0.gguf, exactly. */
        const val BYTES = 1_049_615_680L
        const val PROGRESS_BYTES = "bytes"

        /** Megabytes as a download shows them: 1,050. */
        fun megabytes(bytes: Long): String = String.format(Locale.getDefault(), "%,d", (bytes + 500_000) / 1_000_000)

        /** "412 of 1,050 MB". */
        fun downloaded(bytes: Long): String =
            tr("${megabytes(bytes)} of ${megabytes(BYTES)} MB", "${megabytes(BYTES)}MB 중 ${megabytes(bytes)}MB")

        private const val DOWNLOAD = "pawse_model_download"
        private const val BACKOFF_SECONDS = 30L
        private const val STOP_WAIT_MS = 5_000L
        private const val SPARE_BYTES = 200_000_000L
        private const val MIN_MEMORY_BYTES = 3_000_000_000L
        private const val CONTEXT = 2048
        private const val SUMMARY_TOKENS = 160
        private const val ANSWER_TOKENS = 200

        // Above Liquid AI's 0.1 so the wording varies and reads less stiff; NumberCheck still
        // drops any sentence whose number drifts. ponytail: lower again if drops climb.
        private const val TEMPERATURE = 0.35f
        private const val TOP_K = 50
    }
}

/** Numbers that survived the check make an answer about your numbers; none, a general tip. */
internal fun label(text: String?): Answer = when {
    text == null -> Answer(
        tr(
            "Pawse couldn’t check that answer against your numbers, so it isn’t shown.",
            "그 답을 내 수치와 대조할 수 없어서 보여드리지 않아요.",
        ),
        Answer.Kind.UNCHECKED,
    )
    text.any(Char::isDigit) -> Answer(text, Answer.Kind.CHECKED)
    else -> Answer(text, Answer.Kind.GENERAL)
}

/** LFM3's chat template with thinking off, as its own template writes it. */
internal fun lfmPrompt(system: String, user: String): String =
    "<|startoftext|><|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n" +
        "<|im_start|>assistant\n<think></think>"

/** Drops thinking, template tokens and markdown, so only plain sentences reach the check. */
internal fun clean(text: String): String = text.replace(THINKING, "").replace(MARKUP, "").trim()

private val THINKING = Regex("<think>.*?(</think>|$)", RegexOption.DOT_MATCHES_ALL)
private val MARKUP = Regex("<\\|[^|>]*\\|>|</?think>|\\*|^#+\\s*|^[-•]\\s+", RegexOption.MULTILINE)

// A small model copies the shape of what it is shown more than it follows rules, so the
// prompts lead with a voice and a worked example, and keep the hard limits short.
private fun voice(): String = tr(
    "You are Pawse, the user's sleep and training companion on their phone. You talk like a friend who coaches: " +
        "warm, direct, a little upbeat, never stiff. Say what the morning means for their day first, then the one " +
        "or two numbers that explain it, then something useful they can do. Speak to them as \"you\". ",
    "당신은 사용자의 휴대폰 속 수면·운동 친구 Pawse예요. 코치 같은 친구처럼 따뜻하고 솔직하게, 살짝 밝게 말하고 " +
        "딱딱하게 굴지 마세요. 먼저 오늘 하루에 어떤 의미인지 말하고, 그 이유가 되는 숫자 한두 개, 그다음 해볼 만한 " +
        "것 하나를 말하세요. 자연스러운 존댓말로 말을 거세요. ",
)

private fun rules(): String = tr(
    "Numbers come only from the facts, copied exactly with their units; if a number isn't there, describe it in " +
        "words instead. Leave out illnesses, medicines and supplements, and anything that sounds like a diagnosis. " +
        "Write flowing sentences, not a list.",
    "숫자는 사실에 있는 것만 단위와 함께 그대로 쓰고, 없으면 말로 풀어 주세요. 질병, 약, 영양제, 진단처럼 들리는 " +
        "말은 빼세요. 목록 말고 이어지는 문장으로 쓰세요.",
)

private fun summarySystem(): String = voice() + tr(
    "Write this morning's note in two or three sentences. " +
        "Example of the tone: \"You're in good shape today, so a hard session or a long study block should go well. " +
        "Your Body is 78, helped by an HRV of 58 ms. Keep the coffee before 14:00 and tonight stays easy.\" ",
    "오늘 아침 메모를 두세 문장으로 쓰세요. 말투 예시: \"오늘 컨디션이 좋아서 힘든 운동이나 긴 공부도 잘 될 거예요. " +
        "HRV 58 ms 덕분에 컨디션이 78이에요. 커피는 14:00 전까지만 마시면 오늘 밤도 편할 거예요.\" ",
) + rules()

private fun askSystem(): String = voice() + tr(
    "Answer the question in two or three sentences, starting with the answer itself. If it is about them, use " +
        "their facts below. If it is a general question, give a practical tip without numbers. ",
    "질문에 두세 문장으로 답하되, 답부터 말하세요. 사용자에 관한 질문이면 아래 사실을 쓰고, 일반적인 질문이면 숫자 " +
        "없이 실용적인 팁을 주세요. ",
) + rules()

private fun facts(sheet: FactSheet): String =
    tr("Facts for this morning:\n", "오늘 아침의 사실:\n") + sheet.lines.joinToString("\n") { "- $it" }

private fun summaryUser(sheet: FactSheet): String =
    facts(sheet) + "\n\n" + tr("Pawse’s line for today: ", "오늘 Pawse의 한 줄: ") + sheet.guidance

private fun askUser(question: String, sheet: FactSheet): String =
    facts(sheet) + "\n\n" + tr("Question: ", "질문: ") + question
