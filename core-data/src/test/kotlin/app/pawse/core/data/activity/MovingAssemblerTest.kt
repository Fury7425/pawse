package app.pawse.core.data.activity

import io.kotest.matchers.shouldBe
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class MovingAssemblerTest {

    private val zone = ZoneOffset.UTC
    private val day = LocalDate.parse("2026-11-02")
    private val midnight = day.atStartOfDay(zone).toInstant().toEpochMilli()
    private val slice = 15 * 60_000L

    @Test
    fun `a past day runs from its own wake and an empty day is left out`() {
        // 100 steps in each of the first four slices after a 07:00 wake, and some before it.
        val wake = midnight + 7 * 3_600_000L
        val buckets = (0 until 4).associate { wake + it * slice to 100.0 } + (midnight to 500.0)
        val days = MovingAssembler.days(buckets, listOf(day, day.plusDays(1)), mapOf(day to wake + 5 * 60_000L), zone)
        days.size shouldBe 1
        days[0].cumulativeFromWake.take(5) shouldBe listOf(100.0, 200.0, 300.0, 400.0, 400.0)
        days[0].weekend shouldBe false
    }

    @Test
    fun `through the day runs by the clock against past days and keeps a week`() {
        val hour = 3_600_000L
        val config = app.pawse.scoring.config.ScoringConfig.DEFAULT.moving
        val today = day.plusDays(10)
        val todayMidnight = today.atStartOfDay(zone).toInstant().toEpochMilli()
        // Ten past days with 100 an hour from 08:00 to 10:00, one empty day, and today 50 at 08:15.
        val past = (10 downTo 1).map { today.minusDays(it.toLong()) }
        val buckets = past.filter { it != today.minusDays(3) }.flatMap { d ->
            val m = d.atStartOfDay(zone).toInstant().toEpochMilli()
            listOf(m + 8 * hour to 100.0, m + 9 * hour to 100.0)
        }.toMap() + (todayMidnight + 8 * hour + slice to 50.0)

        val t = MovingAssembler.throughDay(buckets, today, todayMidnight + 9 * hour + 1, past, zone, weekend = false, config)
        t.perHour.size shouldBe 10
        t.perHour[8] shouldBe 50.0
        t.today.last() shouldBe 50.0
        // Nine days with data: the usual reaches 200 by 10:00 and stays there.
        t.usualHigh[10] shouldBe 200.0
        t.usualLow[8] shouldBe 0.0
        t.week.map { it.second } shouldBe listOf(200.0, 200.0, 200.0, 0.0, 200.0, 200.0, 50.0)
    }

    @Test
    fun `today counts the slice a wake falls in and nothing after now`() {
        val buckets = mapOf(midnight to 10.0, midnight + slice to 20.0, midnight + 2 * slice to 40.0)
        MovingAssembler.since(buckets, midnight + 5 * 60_000L, midnight + 2 * slice) shouldBe 30.0
        MovingAssembler.weekend(LocalDate.parse("2026-11-01")) shouldBe true
    }
}
