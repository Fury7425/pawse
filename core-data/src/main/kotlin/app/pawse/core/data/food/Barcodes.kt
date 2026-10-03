package app.pawse.core.data.food

/**
 * Barcode sanity checks, before anything is looked up.
 *
 * EAN-13, EAN-8, UPC-A and UPC-E all carry a check digit computed from the others.
 * Verifying it locally costs nothing and catches the case that matters in a shop:
 * a camera that read twelve digits correctly and one wrongly produces a plausible
 * number for a different product entirely. Refusing it is better than sending it
 * off and logging whatever comes back.
 */
object Barcodes {

    /** Lengths worth checking. Anything else is passed through untouched. */
    private val CHECKED_LENGTHS = setOf(8, 12, 13, 14)

    fun normalise(raw: String): String = raw.trim().filter { it.isDigit() }

    /**
     * @return true when the code could be a real product barcode: all digits, a
     *   sensible length, and a check digit that agrees.
     */
    fun isPlausible(raw: String): Boolean {
        val code = normalise(raw)
        if (code.length !in MIN_LENGTH..MAX_LENGTH) return false
        if (code.length !in CHECKED_LENGTHS) return true
        return checkDigitValid(code)
    }

    /**
     * GS1 modulo-10: from the right, digits alternate weight 3 and 1; the total
     * including the check digit must be a multiple of ten.
     */
    fun checkDigitValid(code: String): Boolean {
        if (code.length < 2 || code.any { !it.isDigit() }) return false
        var sum = 0
        // The check digit is the last character; weights alternate leftward from it.
        for ((index, char) in code.dropLast(1).reversed().withIndex()) {
            val digit = char - '0'
            sum += if (index % 2 == 0) digit * 3 else digit
        }
        val expected = (10 - sum % 10) % 10
        return expected == code.last() - '0'
    }

    private const val MIN_LENGTH = 8
    private const val MAX_LENGTH = 14
}
