package app.pawse.core.data.body

import app.pawse.core.data.scoring.Rows
import app.pawse.scoring.body.BmrSource
import app.pawse.scoring.model.BiologicalSex
import app.pawse.scoring.model.Metric
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.time.LocalDate

class BodyAssemblerTest {

    private val today = LocalDate.parse("2026-10-19")

    private fun assemble(vararg rows: Pair<Metric, List<app.pawse.core.data.db.MetricSampleEntity>>, active: Double? = 578.0) =
        BodyAssembler.assemble(
            samples = rows.toMap(),
            today = today,
            ageYears = 34,
            sex = BiologicalSex.MALE,
            eatenKcal = 1486.0,
            itemsWithoutEnergy = 1,
            activeKcal = active,
            fractionOfDay = 0.5,
            nowEpochMs = 0L,
        )

    /** A weigh-in every day for [days] days ending today, drifting by [step] kg a day. */
    private fun weighIns(days: Int, end: Double, step: Double = 0.02) = (0 until days).map { back ->
        Rows.sample(Metric.BODY_WEIGHT, end + back * step, today.minusDays(back.toLong()).toString(), "06:58")
    }

    @Test
    fun `composition comes from the same morning and fills in lean from body fat`() {
        val s = assemble(
            Metric.BODY_WEIGHT to weighIns(10, 72.4),
            Metric.BODY_FAT to listOf(Rows.sample(Metric.BODY_FAT, 21.0, today.toString(), "06:58")),
            Metric.BODY_WATER to listOf(Rows.sample(Metric.BODY_WATER, 41.9, today.toString(), "06:58")),
            Metric.BONE_MASS to listOf(Rows.sample(Metric.BONE_MASS, 3.1, today.toString(), "06:58")),
        )
        val c = s.composition.shouldNotBeNull()
        c.fatKg!! shouldBe (15.204 plusOrMinus 1e-6)
        c.leanKg!! shouldBe (57.196 plusOrMinus 1e-6)
        c.otherLeanKg!! shouldBe (12.196 plusOrMinus 1e-6)
        s.weighedToday shouldBe true
    }

    @Test
    fun `bmr prefers katch-mcardle from lean mass and spreads it over the day so far`() {
        val s = assemble(
            Metric.BODY_WEIGHT to weighIns(3, 72.4),
            Metric.LEAN_MASS to listOf(Rows.sample(Metric.LEAN_MASS, 57.2, today.toString(), "06:58")),
        )
        val bmr = s.bmr.shouldNotBeNull()
        bmr.source shouldBe BmrSource.KATCH_MCARDLE
        bmr.kcalPerDay shouldBe (370.0 + 21.6 * 57.2 plusOrMinus 1e-9)
        s.energy.restingKcal!! shouldBe (bmr.kcalPerDay / 2 plusOrMinus 1e-9)
        s.energy.differenceKcal!! shouldBe (bmr.kcalPerDay / 2 + 578.0 - 1486.0 plusOrMinus 1e-9)
    }

    @Test
    fun `a stale measured bmr loses to the formula`() {
        val s = assemble(
            Metric.BODY_WEIGHT to weighIns(3, 72.4),
            Metric.LEAN_MASS to listOf(Rows.sample(Metric.LEAN_MASS, 57.2, today.toString(), "06:58")),
            Metric.BASAL_METABOLIC_RATE to listOf(
                Rows.sample(Metric.BASAL_METABOLIC_RATE, 1700.0, today.minusDays(5).toString(), "06:58"),
            ),
        )
        s.bmr!!.source shouldBe BmrSource.KATCH_MCARDLE
    }

    @Test
    fun `usual range excludes the latest weigh-in and needs five before it`() {
        assemble(Metric.BODY_WEIGHT to weighIns(5, 72.4)).weightUsual.shouldBeNull()
        val usual = assemble(Metric.BODY_WEIGHT to weighIns(12, 72.4)).weightUsual.shouldNotBeNull()
        usual.sampleCount shouldBe 11
        (usual.mean > 72.4) shouldBe true
    }

    @Test
    fun `two weigh-ins in one morning count once, the later one`() {
        val s = assemble(
            Metric.BODY_WEIGHT to listOf(
                Rows.sample(Metric.BODY_WEIGHT, 73.0, today.toString(), "06:50"),
                Rows.sample(Metric.BODY_WEIGHT, 72.4, today.toString(), "06:58"),
            ),
        )
        s.weightSeries.size shouldBe 1
        s.weight!!.value shouldBe 72.4
    }

    @Test
    fun `nothing written means nothing invented`() {
        val s = assemble(active = null)
        s.hasAnyData shouldBe false
        s.bmr.shouldBeNull()
        s.energy.burnedKcal.shouldBeNull()
    }
}
