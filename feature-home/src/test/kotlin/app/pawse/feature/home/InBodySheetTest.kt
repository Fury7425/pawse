package app.pawse.feature.home

import org.junit.Assert.assertEquals
import org.junit.Test

class InBodySheetTest {

    private fun line(text: String, left: Int, top: Int) = SheetLine(text, left, top, left + text.length * 12, top + 30)

    @Test
    fun readsValuesInTheColumnToTheRight() {
        val sheet = listOf(
            line("Muscle-Fat Analysis", 40, 100),
            line("Weight", 40, 160),
            line("72.4", 400, 162),
            line("(55.0~74.4)", 520, 160),
            line("SMM", 40, 220),
            line("33.0", 400, 221),
            line("Body Fat Mass", 40, 280),
            line("9.6", 400, 279),
            line("PBF Percent Body Fat", 40, 340),
            line("13.3", 400, 342),
            line("Weight Control", 40, 400),
            line("-2.4", 400, 400),
            line("Visceral Fat Level", 40, 460),
            line("6", 400, 461),
        )
        val read = InBodySheet.parse(sheet)
        assertEquals(72.4, read[SheetField.WEIGHT]!!, 1e-9)
        assertEquals(33.0, read[SheetField.MUSCLE]!!, 1e-9)
        assertEquals(9.6, read[SheetField.FAT_MASS]!!, 1e-9)
        assertEquals(13.3, read[SheetField.FAT_PERCENT]!!, 1e-9)
        assertEquals(6.0, read[SheetField.VISCERAL]!!, 1e-9)
    }

    @Test
    fun readsKoreanLabelsWithTheValueOnTheSameLine() {
        val sheet = listOf(
            line("체중 (kg) 68,2", 40, 100),
            line("골격근량 29.8", 40, 160),
            line("체지방률 18.1 %", 40, 220),
            line("적정체중 65.0", 40, 280),
            line("내장지방레벨 7", 40, 340),
        )
        val read = InBodySheet.parse(sheet)
        assertEquals(68.2, read[SheetField.WEIGHT]!!, 1e-9)
        assertEquals(29.8, read[SheetField.MUSCLE]!!, 1e-9)
        assertEquals(18.1, read[SheetField.FAT_PERCENT]!!, 1e-9)
        assertEquals(7.0, read[SheetField.VISCERAL]!!, 1e-9)
    }

    @Test
    fun dropsAValueNoSheetCouldHold() {
        val read = InBodySheet.parse(listOf(line("Weight", 40, 100), line("724", 400, 100)))
        assertEquals(null, read[SheetField.WEIGHT])
    }
}
