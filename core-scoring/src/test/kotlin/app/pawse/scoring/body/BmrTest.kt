package app.pawse.scoring.body

import app.pawse.scoring.model.BiologicalSex
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

class BmrTest {

    @Test
    fun `a measured value wins over every formula`() {
        val r = Bmr.select(1612.0, 57.2, 72.4, 1.75, 34, BiologicalSex.MALE)!!
        r.source shouldBe BmrSource.MEASURED
        r.kcalPerDay shouldBe 1612.0
    }

    @Test
    fun `lean mass uses Katch-McArdle`() {
        val r = Bmr.select(null, 57.2, 72.4, 1.75, 34, BiologicalSex.MALE)!!
        r.source shouldBe BmrSource.KATCH_MCARDLE
        r.kcalPerDay shouldBe (370.0 + 21.6 * 57.2 plusOrMinus 1e-9)
    }

    @Test
    fun `without lean mass Mifflin-St Jeor uses the published constants`() {
        Bmr.select(null, null, 70.0, 1.75, 30, BiologicalSex.MALE)!!.kcalPerDay shouldBe (1648.75 plusOrMinus 1e-9)
        Bmr.select(null, null, 70.0, 1.75, 30, BiologicalSex.FEMALE)!!.kcalPerDay shouldBe (1482.75 plusOrMinus 1e-9)
        Bmr.select(null, null, 70.0, 1.75, 30, BiologicalSex.UNSPECIFIED)!!.kcalPerDay shouldBe (1565.75 plusOrMinus 1e-9)
    }

    @Test
    fun `nothing usable means no number`() {
        Bmr.select(null, null, 70.0, null, 30, BiologicalSex.MALE).shouldBeNull()
        Bmr.select(0.0, 0.0, null, null, null, BiologicalSex.MALE).shouldBeNull()
    }

    @Test
    fun `resting energy so far is spread across the day`() {
        Bmr.restingSoFar(1600.0, 0.5) shouldBe (800.0 plusOrMinus 1e-9)
        Bmr.restingSoFar(1600.0, 1.4) shouldBe (1600.0 plusOrMinus 1e-9)
    }
}
