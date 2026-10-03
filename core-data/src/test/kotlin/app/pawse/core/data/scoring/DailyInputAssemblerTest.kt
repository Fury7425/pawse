package app.pawse.core.data.scoring

import app.pawse.core.data.db.MetricSampleEntity
import app.pawse.core.data.db.SleepSessionEntity
import app.pawse.scoring.model.Metric
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import java.time.LocalDate

class DailyInputAssemblerTest {

    private val date = LocalDate.parse("2026-03-02")

    private fun night(wake: String = "2026-03-02"): SleepSessionEntity {
        val bed = LocalDate.parse(wake).minusDays(1).toString()
        return Rows.night(id = 1, bedDate = bed, bedTime = "23:00", wakeDate = wake, wakeTime = "07:00")
    }

    private fun assemble(
        samples: List<MetricSampleEntity>,
        sessions: List<SleepSessionEntity> = listOf(night()),
        dates: List<LocalDate> = listOf(date),
        priority: List<String> = emptyList(),
    ): List<DayBundle> {
        val nights = sessions.groupBy { it.localDate }
            .mapValues { (day, forDay) -> NightAssembler.summarise(day, forDay, emptyMap()) }
        return DailyInputAssembler(sourcePriority = priority)
            .assemble(dates, samples, nights, emptyList())
    }

    @Test
    fun `HRV measured inside the sleep window is used`() {
        val bundle = assemble(
            listOf(Rows.sample(Metric.HRV_RMSSD, 48.0, "2026-03-02", "03:00")),
        ).single()

        bundle.values[Metric.HRV_RMSSD]!! shouldBe (48.0 plusOrMinus 1e-9)
    }

    @Test
    fun `a midday HRV spot reading is dropped rather than fed to Recovery`() {
        // This is the probe's DAYTIME_SPOT_ONLY case. Substituting it would build a
        // Recovery score out of a measurement taken in a meeting.
        val bundle = assemble(
            listOf(Rows.sample(Metric.HRV_RMSSD, 48.0, "2026-03-02", "14:00")),
        ).single()

        bundle.values[Metric.HRV_RMSSD].shouldBeNull()
    }

    @Test
    fun `a nightly aggregate stamped just after waking is accepted`() {
        // Several writer apps stamp the night's value at wake time, which is outside
        // the session envelope by a few minutes.
        val bundle = assemble(
            listOf(Rows.sample(Metric.HRV_RMSSD, 51.0, "2026-03-02", "08:30")),
        ).single()

        bundle.values[Metric.HRV_RMSSD]!! shouldBe (51.0 plusOrMinus 1e-9)
    }

    @Test
    fun `duplicate writers resolve by priority instead of averaging`() {
        val samples = listOf(
            Rows.sample(Metric.RESTING_HEART_RATE, 52.0, "2026-03-02", "04:00", source = "com.mirror"),
            Rows.sample(Metric.RESTING_HEART_RATE, 58.0, "2026-03-02", "04:00", source = "com.watch"),
        )

        val bundle = assemble(samples, priority = listOf("com.watch", "com.mirror")).single()

        // 55 would be the average of two devices and the measurement of neither.
        bundle.values[Metric.RESTING_HEART_RATE]!! shouldBe (58.0 plusOrMinus 1e-9)
    }

    @Test
    fun `sleep architecture is derived from the session, not from a sample`() {
        val bundle = assemble(emptyList()).single()

        bundle.night.hasSession shouldBe true
        bundle.values[Metric.TIME_ASLEEP]!! shouldBe (480.0 plusOrMinus 1e-6)
        bundle.values[Metric.SLEEP_EFFICIENCY]!! shouldBe (100.0 plusOrMinus 1e-6)
    }

    @Test
    fun `circadian contributors wait until there is a habit to compare against`() {
        val wakeDates = (2..9).map { LocalDate.parse("2026-03-0$it") }
        val sessions = wakeDates.mapIndexed { index, wake ->
            Rows.night(
                id = index.toLong() + 1,
                bedDate = wake.minusDays(1).toString(),
                bedTime = "23:00",
                wakeDate = wake.toString(),
                wakeTime = "07:00",
            )
        }

        val bundles = assemble(emptyList(), sessions = sessions, dates = wakeDates)

        // Two nights in, there is no habitual midpoint to drift from.
        bundles.first().values[Metric.BEDTIME_CONSISTENCY].shouldBeNull()
        bundles.first().values[Metric.SLEEP_TIMING].shouldBeNull()

        // A week in, with a rigidly identical schedule, both are full marks.
        val last = bundles.last()
        last.values[Metric.BEDTIME_CONSISTENCY]!! shouldBe (100.0 plusOrMinus 1e-6)
        last.values[Metric.SLEEP_TIMING]!! shouldBe (100.0 plusOrMinus 1e-6)
    }

    @Test
    fun `an absent input stays absent`() {
        val bundle = assemble(emptyList()).single()

        // No population defaults, no zeros, no carrying yesterday forward.
        bundle.values[Metric.RESPIRATORY_RATE].shouldBeNull()
        bundle.values[Metric.SPO2].shouldBeNull()
        bundle.values[Metric.SKIN_TEMPERATURE].shouldBeNull()
        bundle.values shouldNotBe emptyMap<Metric, Double>()
    }
}
