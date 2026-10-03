package app.pawse.core.data.scoring

import androidx.health.connect.client.records.SleepSessionRecord
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

class NightAssemblerTest {

    private val tolerance = 1e-6

    @Test
    fun `a plain envelope gives duration and nothing it cannot know`() {
        val session = Rows.night(1, "2026-03-01", "23:00", "2026-03-02", "07:00")
        val night = NightAssembler.summarise("2026-03-02", listOf(session), emptyMap())

        night.hasSession shouldBe true
        night.hasStages shouldBe false
        night.inBedMinutes shouldBe (480.0 plusOrMinus tolerance)
        night.asleepMinutes shouldBe (480.0 plusOrMinus tolerance)
        // Without stages we do not know when they lay down versus fell asleep, and
        // zero would read as "fell asleep instantly", which is its own signal.
        night.latencyMinutes.shouldBeNull()
        night.interruptions.shouldBeNull()
        night.remMinutes.shouldBeNull()
        night.bedtimeMinutes!! shouldBe (23 * 60.0 plusOrMinus tolerance)
        night.midpointMinutes!! shouldBe (3 * 60.0 plusOrMinus tolerance)
    }

    @Test
    fun `split sleep is one night with a hole in it`() {
        // 23:00-03:00, awake half an hour, 03:30-07:00.
        val sessions = listOf(
            Rows.night(1, "2026-03-01", "23:00", "2026-03-02", "03:00"),
            Rows.night(2, "2026-03-02", "03:30", "2026-03-02", "07:00"),
        )
        val night = NightAssembler.summarise("2026-03-02", sessions, emptyMap())

        // Time in bed spans the whole night; the gap is awake, not deleted.
        night.inBedMinutes shouldBe (480.0 plusOrMinus tolerance)
        night.asleepMinutes shouldBe (450.0 plusOrMinus tolerance)
        night.efficiencyPercent!! shouldBe (93.75 plusOrMinus 1e-3)
        night.wasoMinutes!! shouldBe (30.0 plusOrMinus tolerance)
        night.awakenings shouldBe 1
        night.interruptions shouldBe 1
    }

    @Test
    fun `a staged night reports stages, latency and memorable wakes`() {
        val session = Rows.night(7, "2026-03-01", "23:00", "2026-03-02", "07:00", hasStages = true)
        val stages = listOf(
            Rows.stage(7, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED, "2026-03-01", "23:00", "2026-03-01", "23:15"),
            Rows.stage(7, SleepSessionRecord.STAGE_TYPE_LIGHT, "2026-03-01", "23:15", "2026-03-02", "01:00"),
            Rows.stage(7, SleepSessionRecord.STAGE_TYPE_DEEP, "2026-03-02", "01:00", "2026-03-02", "02:30"),
            Rows.stage(7, SleepSessionRecord.STAGE_TYPE_REM, "2026-03-02", "02:30", "2026-03-02", "03:30"),
            Rows.stage(7, SleepSessionRecord.STAGE_TYPE_AWAKE, "2026-03-02", "03:30", "2026-03-02", "03:40"),
            Rows.stage(7, SleepSessionRecord.STAGE_TYPE_LIGHT, "2026-03-02", "03:40", "2026-03-02", "07:00"),
        )

        val night = NightAssembler.summarise("2026-03-02", listOf(session), mapOf(7L to stages))

        night.hasStages shouldBe true
        // 105 light + 90 deep + 60 REM + 200 light
        night.asleepMinutes shouldBe (455.0 plusOrMinus tolerance)
        night.deepMinutes!! shouldBe (90.0 plusOrMinus tolerance)
        night.remMinutes!! shouldBe (60.0 plusOrMinus tolerance)
        night.latencyMinutes!! shouldBe (15.0 plusOrMinus tolerance)
        night.wasoMinutes!! shouldBe (10.0 plusOrMinus tolerance)
        night.awakenings shouldBe 1
        night.interruptions shouldBe 1
    }

    @Test
    fun `a brief stirring is a wake but not an interruption`() {
        val session = Rows.night(9, "2026-03-01", "23:00", "2026-03-02", "07:00", hasStages = true)
        val stages = listOf(
            Rows.stage(9, SleepSessionRecord.STAGE_TYPE_LIGHT, "2026-03-01", "23:00", "2026-03-02", "02:00"),
            Rows.stage(9, SleepSessionRecord.STAGE_TYPE_AWAKE, "2026-03-02", "02:00", "2026-03-02", "02:02"),
            Rows.stage(9, SleepSessionRecord.STAGE_TYPE_LIGHT, "2026-03-02", "02:02", "2026-03-02", "07:00"),
        )

        val night = NightAssembler.summarise("2026-03-02", listOf(session), mapOf(9L to stages))

        night.awakenings shouldBe 1
        // Apple's bucket counts wake periods a person would remember, not every
        // two-minute micro-arousal.
        night.interruptions shouldBe 0
    }

    @Test
    fun `stages that cover only a sliver of the night do not make it a staged night`() {
        val session = Rows.night(11, "2026-03-01", "23:00", "2026-03-02", "07:00", hasStages = true)
        val stages = listOf(
            Rows.stage(11, SleepSessionRecord.STAGE_TYPE_DEEP, "2026-03-02", "01:00", "2026-03-02", "01:40"),
        )

        val night = NightAssembler.summarise("2026-03-02", listOf(session), mapOf(11L to stages))

        // Forty staged minutes inside an eight-hour session is partial reporting,
        // not a forty-minute night.
        night.hasStages shouldBe false
        night.asleepMinutes shouldBe (480.0 plusOrMinus tolerance)
        night.deepMinutes.shouldBeNull()
    }

    @Test
    fun `naps are credited separately and never counted as the night`() {
        val sessions = listOf(
            Rows.night(1, "2026-03-01", "23:00", "2026-03-02", "07:00"),
            Rows.nap(2, "2026-03-02", "14:00", "14:45"),
        )
        val night = NightAssembler.summarise("2026-03-02", sessions, emptyMap())

        night.asleepMinutes shouldBe (480.0 plusOrMinus tolerance)
        night.napMinutes shouldBe 45
    }

    @Test
    fun `a night with no session at all still reports its naps`() {
        val night = NightAssembler.summarise("2026-03-02", listOf(Rows.nap(3, "2026-03-02", "13:00", "13:30")), emptyMap())

        night.hasSession shouldBe false
        night.napMinutes shouldBe 30
        night.midpointMinutes.shouldBeNull()
    }

    @Test
    fun `crossing a timezone is recorded rather than punished`() {
        // Went to bed at UTC+2, woke at UTC+0: the clock moved, not the sleeper.
        val session = Rows.night(
            id = 1,
            bedDate = "2026-03-01",
            bedTime = "23:00",
            wakeDate = "2026-03-02",
            wakeTime = "05:00",
            offsetSeconds = 0,
            startOffsetSeconds = 7200,
        )
        val night = NightAssembler.summarise("2026-03-02", listOf(session), emptyMap())

        night.timezoneShiftMinutes shouldBe -120
    }
}
