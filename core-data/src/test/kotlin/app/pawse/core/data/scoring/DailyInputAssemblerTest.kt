package app.pawse.core.data.scoring

import app.pawse.core.data.db.DayNoteEntity
import app.pawse.core.data.db.MetricSampleEntity
import app.pawse.core.data.db.SleepSessionEntity
import app.pawse.scoring.model.DayTag
import app.pawse.scoring.model.EngineInput
import app.pawse.scoring.model.Metric
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.time.LocalDate
import kotlin.math.ln

class DailyInputAssemblerTest {

    private val date = LocalDate.parse("2026-03-02")

    private fun night(wake: String = "2026-03-02"): SleepSessionEntity {
        val bed = LocalDate.parse(wake).minusDays(1).toString()
        return Rows.night(id = 1, bedDate = bed, bedTime = "23:00", wakeDate = wake, wakeTime = "07:00")
    }

    private fun assemble(
        samples: List<MetricSampleEntity>,
        sessions: List<SleepSessionEntity> = listOf(night()),
        priority: List<String> = emptyList(),
        notes: List<DayNoteEntity> = emptyList(),
    ): EngineInput {
        val nights = sessions.groupBy { it.localDate }
            .mapValues { (day, forDay) -> NightAssembler.summarise(day, forDay, emptyMap()) }
        return DailyInputAssembler(sourcePriority = priority)
            .assemble(listOf(date), samples, nights, sessions = sessions, notes = notes)
    }

    @Test
    fun `HRV is the log of the median of the night's records`() {
        val input = assemble(
            listOf(40.0, 50.0, 60.0).mapIndexed { i, v -> Rows.sample(Metric.HRV_RMSSD, v, "2026-03-02", "0$i:30") },
        )
        input.nights.getValue(date).lnHrv!!.value shouldBe (ln(50.0) plusOrMinus 1e-9)
    }

    @Test
    fun `a midday HRV spot reading is dropped rather than read as a night`() {
        val input = assemble(listOf(Rows.sample(Metric.HRV_RMSSD, 48.0, "2026-03-02", "14:00")))
        input.nights.getValue(date).lnHrv.shouldBeNull()
    }

    @Test
    fun `a single nightly value is used, at lower quality`() {
        val single = assemble(listOf(Rows.sample(Metric.HRV_RMSSD, 51.0, "2026-03-02", "08:30")))
        val n = single.nights.getValue(date)
        n.lnHrv!!.value shouldBe (ln(51.0) plusOrMinus 1e-9)
        (n.quality < 1.0) shouldBe true
    }

    @Test
    fun `Pawse's own sleeping HR and the vendor's resting HR never share a source key`() {
        val vendor = assemble(listOf(Rows.sample(Metric.RESTING_HEART_RATE, 55.0, "2026-03-02", "04:00")))
        vendor.nights.getValue(date).sleepingHr!!.source shouldBe "com.example.watch#resting"

        val own = assemble(
            listOf(
                Rows.sample(Metric.RESTING_HEART_RATE, 55.0, "2026-03-02", "04:00"),
                Rows.sample(Metric.SLEEPING_HEART_RATE, 49.0, "2026-03-02", "07:00"),
            ),
        )
        val hr = own.nights.getValue(date).sleepingHr!!
        hr.value shouldBe (49.0 plusOrMinus 1e-9)
        hr.source shouldBe "com.example.watch#series"
    }

    @Test
    fun `duplicate writers resolve by priority instead of averaging`() {
        val samples = listOf(
            Rows.sample(Metric.RESTING_HEART_RATE, 52.0, "2026-03-02", "04:00", source = "com.mirror"),
            Rows.sample(Metric.RESTING_HEART_RATE, 58.0, "2026-03-02", "04:00", source = "com.watch"),
        )
        val input = assemble(samples, priority = listOf("com.watch", "com.mirror"))
        // 55 would be the average of two devices and the measurement of neither.
        input.nights.getValue(date).restingHr!! shouldBe (58.0 plusOrMinus 1e-9)
    }

    @Test
    fun `breathing needs three records in the night, or it is skipped`() {
        val one = assemble(listOf(Rows.sample(Metric.RESPIRATORY_RATE, 14.0, "2026-03-02", "03:00")))
        one.nights.getValue(date).breathingRate.shouldBeNull()
        val three = assemble(
            listOf("01:00", "03:00", "05:00").map { Rows.sample(Metric.RESPIRATORY_RATE, 14.0, "2026-03-02", it) },
        )
        three.nights.getValue(date).breathingRate!! shouldBe (14.0 plusOrMinus 1e-9)
    }

    @Test
    fun `check-ins and tags come from the day note`() {
        val input = assemble(
            emptyList(),
            notes = listOf(DayNoteEntity("2026-03-02", 4, "TRAVEL,SICK", 0L)),
        )
        input.checkIns[date] shouldBe 4
        input.tags[date] shouldBe setOf(DayTag.TRAVEL, DayTag.SICK)
    }

    @Test
    fun `the night carries its sleep and its offsets`() {
        val sleep = assemble(emptyList()).nights.getValue(date).sleep!!
        sleep.asleepMinutes shouldBe (480.0 plusOrMinus 1e-6)
        sleep.bedtimeMinute shouldBe (23 * 60.0 plusOrMinus 1e-6)
        sleep.wakeMinute shouldBe (7 * 60.0 plusOrMinus 1e-6)
    }
}
