package app.pawse.feature.home

/** One line of text read off a result sheet, with where it sat in the photo. */
data class SheetLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

enum class SheetField { WEIGHT, MUSCLE, FAT_MASS, FAT_PERCENT, VISCERAL, LEAN, WATER }

/**
 * Reads the numbers off a photo of an InBody result sheet, English or Korean.
 *
 * A sheet is a table: a label, then its value on the same line or in a column to
 * its right. Each label takes the first number in its own line, else the nearest
 * line to its right at the same height. A value outside the range a real one falls
 * in is dropped rather than guessed at; the screen shows every number for checking.
 */
object InBodySheet {
    private val LABELS = mapOf(
        SheetField.WEIGHT to listOf("weight", "체중"),
        SheetField.MUSCLE to listOf("skeletalmusclemass", "smm", "골격근량"),
        SheetField.FAT_MASS to listOf("bodyfatmass", "체지방량"),
        SheetField.FAT_PERCENT to listOf("percentbodyfat", "pbf", "체지방률"),
        SheetField.VISCERAL to listOf("visceralfat", "내장지방"),
        SheetField.LEAN to listOf("fatfreemass", "제지방량"),
        SheetField.WATER to listOf("totalbodywater", "체수분"),
    )

    /** Lines that name a field but hold a target or a change, not the reading. */
    private val SKIP = listOf("control", "target", "history", "조절", "적정", "변화", "이력")

    private val RANGES = mapOf(
        SheetField.WEIGHT to 20.0..300.0,
        SheetField.MUSCLE to 5.0..80.0,
        SheetField.FAT_MASS to 1.0..150.0,
        SheetField.FAT_PERCENT to 2.0..75.0,
        SheetField.VISCERAL to 1.0..30.0,
        SheetField.LEAN to 10.0..200.0,
        SheetField.WATER to 5.0..150.0,
    )

    private val NUMBER = Regex("""\d{1,3}(?:[.,]\d{1,2})?""")

    fun parse(lines: List<SheetLine>): Map<SheetField, Double> {
        val found = linkedMapOf<SheetField, Double>()
        for (line in lines) {
            val key = line.text.lowercase().filterNot { it.isWhitespace() }
            if (SKIP.any { it in key }) continue
            val fields = LABELS.filter { (_, labels) -> labels.any { it in key } }.keys
            // A header row names several fields at once and holds none of their values.
            val field = fields.singleOrNull() ?: continue
            if (field in found) continue
            val value = (number(line.text) ?: beside(line, lines))?.takeIf { it in RANGES.getValue(field) } ?: continue
            found[field] = value
        }
        return found
    }

    private fun number(text: String): Double? = NUMBER.find(text)?.value?.replace(',', '.')?.toDoubleOrNull()

    /** The nearest line to the right whose middle sits within this line's height. */
    private fun beside(label: SheetLine, lines: List<SheetLine>): Double? {
        val height = label.bottom - label.top
        val band = (label.top - height / 2)..(label.bottom + height / 2)
        return lines
            .filter { it !== label && it.left >= label.right - height && (it.top + it.bottom) / 2 in band }
            .sortedBy { it.left }
            .firstNotNullOfOrNull { number(it.text) }
    }
}
