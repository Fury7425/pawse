package app.pawse.scoring.attention

import app.pawse.scoring.tr
import kotlin.math.abs
import kotlin.math.pow

/** How a fact's number is written. Durations and clock times are held in minutes. */
enum class FactUnit { DURATION, CLOCK, MS, BPM, KG, KCAL, PERCENT, STEPS, PLAIN }

/**
 * One number the morning would show: "HRV 58 ms", "need 7 h 50". [words] are the
 * metric's names in both languages, which tie a written number to its fact.
 * [decimals] is the precision it is shown at.
 */
data class Fact(val metric: String, val words: List<String>, val value: Double, val unit: FactUnit, val decimals: Int = 0)

/** What survived the check, or null to fall back to Pawse's own line. [dropped] is counted on the phone. */
data class Checked(val text: String?, val dropped: Int)

/**
 * The number check on everything the on-device model writes. It words; it never
 * works out.
 *
 *  1. The model is given only the morning's fact sheet.
 *  2. Every number it writes is pulled out with its unit, in English and Korean
 *     ("7 h 24", "7시간 24분", "58 ms"), plus the metric word nearest it.
 *  3. A number passes only if a fact for that metric matches within half its last
 *     shown digit, or one minute for times.
 *  4. A sentence with a failing number or a disease or medicine name is dropped,
 *     which also catches an instruction to take something. Nothing left: Pawse's
 *     template.
 *
 * Symptom questions never reach the model: [symptoms] catches them first.
 */
object NumberCheck {

    fun check(text: String, facts: List<Fact>): Checked {
        val sentences = text.split(SENTENCE).map { it.trim() }.filter { it.isNotEmpty() }
        val kept = sentences.filter { s -> BLOCKED.none { it.containsMatchIn(s) } && numbers(s).all { passes(it, s, facts) } }
        return Checked(kept.joinToString(" ").ifEmpty { null }, sentences.size - kept.size)
    }

    fun symptoms(question: String): Boolean = SYMPTOMS.any { it.containsMatchIn(question) }

    /** The fixed answer to any symptom question. Never the model's. */
    fun symptomAnswer(): String = tr(
        "Pawse can't help with symptoms. If this is sudden or severe, call 119 or your local emergency number. " +
            "Otherwise, please talk to a doctor.",
        "증상에 대해서는 Pawse가 도와드릴 수 없어요. 갑작스럽거나 심하면 119 또는 지역 응급 번호로 연락하세요. " +
            "그렇지 않다면 의사와 상담해 주세요.",
    )

    internal data class Found(val value: Double, val unit: FactUnit, val decimals: Int, val at: Int)

    internal fun numbers(s: String): List<Found> {
        val out = mutableListOf<Found>()
        val taken = BooleanArray(s.length)
        fun claim(m: MatchResult, f: Found) {
            if (m.range.any { taken[it] }) return
            m.range.forEach { taken[it] = true }
            out += f
        }
        DURATION.findAll(s).forEach { m ->
            val h = m.groupValues[1].toDouble()
            val min = m.groupValues[2].takeIf { it.isNotEmpty() }?.toDouble() ?: 0.0
            claim(m, Found(h * 60 + min, FactUnit.DURATION, 0, m.range.first))
        }
        MINUTES.findAll(s).forEach { m -> claim(m, Found(m.groupValues[1].toDouble(), FactUnit.DURATION, 0, m.range.first)) }
        CLOCK.findAll(s).forEach { m ->
            claim(m, Found(m.groupValues[1].toDouble() * 60 + m.groupValues[2].toDouble(), FactUnit.CLOCK, 0, m.range.first))
        }
        UNITS.forEach { (unit, regex) ->
            regex.findAll(s).forEach { m -> claim(m, number(m.groupValues[1], unit, m.range.first)) }
        }
        PLAIN.findAll(s).forEach { m -> claim(m, number(m.value, FactUnit.PLAIN, m.range.first)) }
        return out
    }

    private fun number(text: String, unit: FactUnit, at: Int): Found {
        val clean = text.replace(",", "")
        return Found(clean.toDouble(), unit, clean.substringAfter('.', "").length, at)
    }

    private fun passes(n: Found, sentence: String, facts: List<Fact>): Boolean {
        val metric = nearestMetric(n.at, sentence, facts)
        val pool = facts.filter { (metric == null || it.metric == metric) && (n.unit == FactUnit.PLAIN || it.unit == n.unit) }
        return pool.any { f ->
            val tolerance = when (f.unit) {
                FactUnit.DURATION, FactUnit.CLOCK -> 1.0
                else -> 0.5 * 10.0.pow(-minOf(f.decimals, n.decimals).toDouble())
            }
            val gap = if (f.unit == FactUnit.CLOCK) abs(n.value - f.value).let { minOf(it, 1440 - it) } else abs(n.value - f.value)
            gap <= tolerance + 1e-9
        }
    }

    private fun nearestMetric(at: Int, sentence: String, facts: List<Fact>): String? {
        val lower = sentence.lowercase()
        return facts.flatMap { f -> f.words.map { f.metric to it.lowercase() } }
            .flatMap { (metric, word) -> Regex(Regex.escape(word)).findAll(lower).map { metric to abs(it.range.first - at) }.toList() }
            .minByOrNull { it.second }?.first
    }

    private val SENTENCE = Regex("(?<=[.!?。])\\s+|\\n+")
    private const val NUM = "(\\d{1,3}(?:,\\d{3})+|\\d+(?:\\.\\d+)?)"
    // English unit words end on a word boundary so "58 heart" is not 58 hours; Korean
    // ones cannot, since 시간 and 분 run straight into the next syllable.
    private val DURATION = Regex(
        "(\\d+)\\s*(?:(?:h|hr|hrs|hours?)\\b|시간)\\s*(?:(\\d+)\\s*(?:(?:m|min|mins|minutes?)\\b|분)?)?",
        RegexOption.IGNORE_CASE,
    )
    private val MINUTES = Regex("(\\d+)\\s*(?:(?:min|mins|minutes?)\\b|분)", RegexOption.IGNORE_CASE)
    private val CLOCK = Regex("\\b(\\d{1,2}):(\\d{2})\\b")
    private val UNITS = listOf(
        FactUnit.MS to Regex("$NUM\\s*ms\\b", RegexOption.IGNORE_CASE),
        FactUnit.BPM to Regex("$NUM\\s*(?:bpm|회/분)", RegexOption.IGNORE_CASE),
        FactUnit.KCAL to Regex("$NUM\\s*(?:kcal|칼로리|킬로칼로리)", RegexOption.IGNORE_CASE),
        FactUnit.KG to Regex("$NUM\\s*(?:kg|킬로그램)", RegexOption.IGNORE_CASE),
        FactUnit.PERCENT to Regex("$NUM\\s*(?:%|퍼센트|percent)", RegexOption.IGNORE_CASE),
        FactUnit.STEPS to Regex("$NUM\\s*(?:steps?|걸음)", RegexOption.IGNORE_CASE),
    )
    private val PLAIN = Regex(NUM)

    // ponytail: word lists, not a classifier. Korean has no word boundaries, so
    // medicine words are matched with their particles to keep 약간 and 요약 out.
    private val BLOCKED = listOf(
        Regex(
            "\\b(medicine|medication|pills?|tablets?|supplements?|melatonin|ibuprofen|paracetamol|acetaminophen|aspirin|" +
                "antibiotics?|dose|dosage|covid|flu|influenza|infection|diabetes|apn(o)?ea|insomnia|disease|disorder|fever|" +
                "cancer|arrhythmia|afib|depression)\\b",
            RegexOption.IGNORE_CASE,
        ),
        Regex("약을|약은|약이|약물|복용|영양제|멜라토닌|진통제|해열제|항생제|코로나|독감|감염|당뇨|무호흡|불면증|질환|질병|발열|부정맥|우울증"),
    )

    private val SYMPTOMS = listOf(
        Regex(
            "\\b(pain|hurts?|chest|dizzy|dizziness|faint(ed|ing)?|short of breath|breathless|palpitations?|numb|bleeding|" +
                "vomit(ing)?|fever|rash|swelling|swollen|headache|symptoms?)\\b",
            RegexOption.IGNORE_CASE,
        ),
        Regex("통증|아파|아프|가슴|어지러|숨이 차|숨쉬기|두근|저려|저림|출혈|피가|구토|토했|열이|발진|부었|붓기|두통|증상"),
    )
}
