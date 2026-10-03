package app.pawse.core.data.food.ocr

import app.pawse.core.data.food.Nutrients

/** What the figures on a label refer to. Without this, the numbers mean nothing. */
enum class LabelBasis {
    PER_100G,
    PER_SERVING,
    PER_PACKAGE,
    UNKNOWN,
}

enum class LabelWarning {
    /** Nothing on the label said what the numbers are per. */
    NO_BASIS,

    /** Under three fields read: probably a bad photo rather than a sparse label. */
    TOO_FEW_FIELDS,

    /** Stated energy and the macros disagree by more than Atwater's slack. */
    ENERGY_DISAGREES_WITH_MACROS,
}

/**
 * A nutrition panel, as read.
 *
 * Deliberately not a [app.pawse.core.data.food.FoodProduct]: this is what the
 * label said, not a product we are confident about. The conversion to per-100 g
 * figures is available only when the basis is known, and the warnings travel with
 * it so the confirmation screen can show the user what to check.
 */
data class LabelReading(
    val nutrients: Nutrients,
    val basis: LabelBasis,
    /** Grams the figures refer to, when stated. */
    val basisGrams: Double?,
    val servingGrams: Double?,
    val packageGrams: Double?,
    val fieldsFound: Int,
    val warnings: List<LabelWarning>,
) {
    /**
     * The figures rebased to 100 g, or null when the label never said what they
     * were per. Guessing 100 g would be wrong for most Korean packaging, which
     * states per serving or per package.
     */
    fun per100g(): Nutrients? {
        val grams = basisGrams ?: return null
        if (grams <= 0.0) return null
        return nutrients.scaled(100.0 / grams)
    }

    val usable: Boolean get() = fieldsFound >= MIN_USABLE_FIELDS

    companion object {
        const val MIN_USABLE_FIELDS = 3
    }
}

/**
 * Reads a nutrition panel out of recognised text.
 *
 * Pure text in, figures out — no ML Kit type, no Android type, no bitmap. The
 * recogniser's job ends at "here are the lines"; everything that can be wrong
 * after that is arithmetic and language, and both are testable without a camera.
 *
 * Korean panels are the design target and English is handled alongside, because a
 * Korean shelf holds both. Three things make this harder than a regex:
 *
 *  1. **Percentages sit next to amounts.** `나트륨 320mg 16%` — the 16 is a share
 *     of a daily reference value, and a parser that grabs the first number logs a
 *     16 mg sodium biscuit. A figure counts only if a unit follows it.
 *  2. **Nutrient names nest.** `포화지방` and `트랜스지방` both end in `지방`, and
 *     `saturated fat` contains `fat`. Longest match wins, always.
 *  3. **The basis is the whole ballgame.** `100g당`, `총 내용량 90g당` and
 *     `1회 제공량 45g` are three different denominators, and getting it wrong is a
 *     factor-of-two error in the user's day. When the label does not say, we
 *     report that rather than assuming.
 */
object NutritionLabelParser {

    fun parse(lines: List<String>): LabelReading {
        val normalised = lines.map(::normalise).filter { it.isNotBlank() }
        val joined = normalised.joinToString("\n")

        val servingGrams = grams(joined, SERVING_SIZE_PATTERNS)
        val packageGrams = grams(joined, PACKAGE_SIZE_PATTERNS)
        val (basis, basisGrams) = resolveBasis(joined, servingGrams, packageGrams)

        var found = 0
        val values = mutableMapOf<Field, Double>()
        for (line in normalised) {
            for ((field, value) in fieldsIn(line)) {
                if (values.putIfAbsent(field, value) == null) found++
            }
        }

        val nutrients = Nutrients(
            kcal = values[Field.ENERGY],
            proteinGrams = values[Field.PROTEIN],
            carbsGrams = values[Field.CARBS],
            fatGrams = values[Field.FAT],
            saturatedFatGrams = values[Field.SATURATED_FAT],
            sugarGrams = values[Field.SUGAR],
            fiberGrams = values[Field.FIBER],
            sodiumMilligrams = values[Field.SODIUM],
        )

        val warnings = buildList {
            if (basis == LabelBasis.UNKNOWN) add(LabelWarning.NO_BASIS)
            if (found < LabelReading.MIN_USABLE_FIELDS) add(LabelWarning.TOO_FEW_FIELDS)
            if (nutrients.energyDisagreesWithMacros() == true) {
                add(LabelWarning.ENERGY_DISAGREES_WITH_MACROS)
            }
        }

        return LabelReading(
            nutrients = nutrients,
            basis = basis,
            basisGrams = basisGrams,
            servingGrams = servingGrams,
            packageGrams = packageGrams,
            fieldsFound = found,
            warnings = warnings,
        )
    }

    // --- Basis ---------------------------------------------------------------

    private fun resolveBasis(
        text: String,
        servingGrams: Double?,
        packageGrams: Double?,
    ): Pair<LabelBasis, Double?> {
        // The Korean convention states the denominator outright: "100g당",
        // "총 내용량 90g당". Whatever number carries the 당 particle wins.
        PER_UNIT_PATTERN.find(text)?.let { match ->
            val grams = match.groupValues[1].toDoubleOrNull()
            if (grams != null && grams > 0.0) {
                val basis = when {
                    grams == 100.0 -> LabelBasis.PER_100G
                    packageGrams != null && grams == packageGrams -> LabelBasis.PER_PACKAGE
                    servingGrams != null && grams == servingGrams -> LabelBasis.PER_SERVING
                    else -> LabelBasis.PER_SERVING
                }
                return basis to grams
            }
        }

        if (PER_100_PATTERN.containsMatchIn(text)) return LabelBasis.PER_100G to 100.0
        if (PER_SERVING_PATTERN.containsMatchIn(text) && servingGrams != null) {
            return LabelBasis.PER_SERVING to servingGrams
        }
        if (servingGrams != null) return LabelBasis.PER_SERVING to servingGrams
        if (packageGrams != null) return LabelBasis.PER_PACKAGE to packageGrams
        return LabelBasis.UNKNOWN to null
    }

    private fun grams(text: String, patterns: List<Regex>): Double? {
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val value = match.groupValues[1].toDoubleOrNull() ?: continue
            if (value > 0.0) return value
        }
        return null
    }

    // --- Fields --------------------------------------------------------------

    private enum class Field { ENERGY, PROTEIN, CARBS, SUGAR, FAT, SATURATED_FAT, FIBER, SODIUM, IGNORED }

    /**
     * Every field mentioned on one line, with its value.
     *
     * Done per line and per keyword position, because a single OCR line often
     * carries two nutrients — `탄수화물 30g 9% 당류 12g 12%` — and a value belongs to
     * the keyword before it, not to whichever keyword the scan reached first.
     */
    private fun fieldsIn(line: String): List<Pair<Field, Double>> {
        val hits = KEYWORDS.flatMap { (field, pattern) ->
            pattern.findAll(line).map { Hit(field, it.range) }
        }.sortedBy { it.range.first }

        // Longest match wins: `포화지방` swallows the `지방` inside it, and
        // `saturated fat` swallows its `fat`.
        val kept = hits.filter { candidate ->
            hits.none { other -> other !== candidate && other.range.contains(candidate.range) }
        }

        val out = mutableListOf<Pair<Field, Double>>()
        for ((index, hit) in kept.withIndex()) {
            if (hit.field == Field.IGNORED) continue
            val until = kept.getOrNull(index + 1)?.range?.first ?: line.length
            val segment = line.substring(hit.range.last + 1, until.coerceAtLeast(hit.range.last + 1))
            valueIn(segment, hit.field)?.let { out += hit.field to it }
        }
        return out
    }

    /**
     * The first figure in [segment] that carries a unit.
     *
     * The unit requirement is what keeps a daily-value percentage out of the log.
     * Energy is the exception: `열량 210` with the kcal on the next line is common
     * enough that a bare number is accepted for it, and only for it.
     */
    private fun valueIn(segment: String, field: Field): Double? {
        VALUE_PATTERN.findAll(segment).forEach { match ->
            val raw = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return@forEach
            val unit = match.groupValues[2].lowercase()
            val converted = when (field) {
                Field.ENERGY -> if (unit == "kcal") raw else return@forEach
                Field.SODIUM -> when (unit) {
                    "mg" -> raw
                    "g" -> raw * 1000.0
                    else -> return@forEach
                }
                else -> when (unit) {
                    "g" -> raw
                    "mg" -> raw / 1000.0
                    else -> return@forEach
                }
            }
            return converted
        }
        if (field == Field.ENERGY) {
            return BARE_NUMBER_PATTERN.find(segment)?.groupValues?.get(1)
                ?.replace(",", "")?.toDoubleOrNull()
        }
        return null
    }

    private data class Hit(val field: Field, val range: IntRange)

    private fun IntRange.contains(other: IntRange): Boolean =
        first <= other.first && last >= other.last && (first < other.first || last > other.last)

    // --- Text normalisation ---------------------------------------------------

    /**
     * OCR output, tidied.
     *
     * Full-width and CJK-composed unit glyphs are what a phone camera actually
     * returns from a Korean package — `㎎`, `㎉`, `ｇ` — and each would otherwise
     * miss every pattern below.
     */
    private fun normalise(line: String): String {
        val builder = StringBuilder(line.length)
        for (char in line) {
            when (char) {
                '㎎' -> builder.append("mg")
                '㎍' -> builder.append("ug")
                '㎏' -> builder.append("kg")
                '㎉' -> builder.append("kcal")
                '㎖' -> builder.append("ml")
                '，' -> builder.append(',')
                '．' -> builder.append('.')
                '％' -> builder.append('%')
                else ->
                    // Full-width ASCII block maps onto ASCII with a fixed offset.
                    if (char.code in 0xFF01..0xFF5E) {
                        builder.append((char.code - 0xFEE0).toChar())
                    } else {
                        builder.append(char)
                    }
            }
        }
        return builder.toString()
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun keyword(vararg alternatives: String): Regex =
        Regex(alternatives.joinToString("|"), RegexOption.IGNORE_CASE)

    /**
     * Keyword order does not matter — overlaps are resolved by length — but
     * coverage does. Trans fat and cholesterol are listed only so their `지방` and
     * their milligrams are consumed rather than being mistaken for total fat.
     */
    private val KEYWORDS: List<Pair<Field, Regex>> = listOf(
        Field.ENERGY to keyword("열량", "칼로리", "에너지", "calories", "energy"),
        Field.PROTEIN to keyword("단백질", "protein"),
        Field.CARBS to keyword("탄수화물", "total carbohydrate", "carbohydrates?", "carbs"),
        Field.SUGAR to keyword("당류", "당분", "total sugars?", "sugars?"),
        Field.SATURATED_FAT to keyword("포화지방산?", "saturated fat", "saturates"),
        Field.IGNORED to keyword("트랜스지방산?", "trans fat", "콜레스테롤", "cholesterol"),
        Field.FAT to keyword("지방", "total fat", "fat"),
        // Both spellings: an imported packet says "fibre", an American one "fiber".
        Field.FIBER to keyword("식이섬유", "dietary fib(?:er|re)s?", "fib(?:er|re)s?"),
        Field.SODIUM to keyword("나트륨", "sodium"),
    )

    /** A number followed by a unit, which is what distinguishes an amount from a %. */
    private val VALUE_PATTERN = Regex("""(\d+(?:[.,]\d+)?)\s*(kcal|mg|g)(?![a-z])""", RegexOption.IGNORE_CASE)

    /**
     * A bare number that is not a percentage. Accepted for energy alone, where a
     * line break between `열량` and `kcal` is common enough to be worth handling.
     */
    private val BARE_NUMBER_PATTERN = Regex("""(\d+(?:[.,]\d+)?)(?!\s*%)""")

    /** `100g당`, `90g당`, `240ml당` — the Korean statement of the denominator. */
    private val PER_UNIT_PATTERN = Regex("""(\d+(?:\.\d+)?)\s*(?:g|ml)\s*당""")

    private val PER_100_PATTERN = Regex("""per\s*100\s*(?:g|ml)|100\s*(?:g|ml)\s*(?:기준|당)""", RegexOption.IGNORE_CASE)

    private val PER_SERVING_PATTERN =
        Regex("""1회\s*(?:제공량|섭취참고량)|amount per serving|per serving""", RegexOption.IGNORE_CASE)

    private val SERVING_SIZE_PATTERNS = listOf(
        Regex("""1회\s*(?:제공량|섭취참고량)[^0-9]{0,10}(\d+(?:\.\d+)?)\s*(?:g|ml)"""),
        Regex("""serving size[^0-9]{0,10}(\d+(?:\.\d+)?)\s*(?:g|ml)""", RegexOption.IGNORE_CASE),
    )

    private val PACKAGE_SIZE_PATTERNS = listOf(
        Regex("""총\s*내용량[^0-9]{0,10}(\d+(?:\.\d+)?)\s*(?:g|ml)"""),
        Regex("""내용량[^0-9]{0,10}(\d+(?:\.\d+)?)\s*(?:g|ml)"""),
    )
}
