package app.pawse.scoring

import app.pawse.scoring.body.SignalFilter
import app.pawse.scoring.config.BodyConfig
import app.pawse.scoring.config.ContextConfig
import app.pawse.scoring.config.EnergyConfig
import app.pawse.scoring.config.LearningConfig
import app.pawse.scoring.config.LoadConfig
import app.pawse.scoring.config.SleepConfig
import app.pawse.scoring.context.Cycle
import app.pawse.scoring.energy.EnergyForecastModel
import app.pawse.scoring.guidance.GuidanceRules
import app.pawse.scoring.learning.CheckInCalibration
import app.pawse.scoring.learning.DoseResponse
import app.pawse.scoring.load.TrainingLoad
import app.pawse.scoring.model.BodyBand
import app.pawse.scoring.model.BodyScore
import app.pawse.scoring.model.BodyStatus
import app.pawse.scoring.model.Confidence
import app.pawse.scoring.model.EngineInput
import app.pawse.scoring.model.FormBand
import app.pawse.scoring.model.Guidance
import app.pawse.scoring.model.IllnessLevel
import app.pawse.scoring.model.NightInput
import app.pawse.scoring.model.Predictor
import app.pawse.scoring.model.ReasonKind
import app.pawse.scoring.model.Signal
import app.pawse.scoring.model.SleepInterval
import app.pawse.scoring.model.SleepNight
import app.pawse.scoring.signal.SignalLayer
import app.pawse.scoring.sleep.SleepMath
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import java.time.LocalDate
import kotlin.math.ln
import kotlin.math.sin

class EngineTest {

    private val start = LocalDate.parse("2026-01-05")

    private fun nightAt(d: LocalDate, hours: Double = 8.0, bedHour: Int = 23): SleepNight {
        val bed = d.toEpochDay() * DAY - (24 - bedHour) * HOUR
        return SleepNight(bed, bed + (hours * HOUR).toLong(), 0, 0, hours * 60, hours * 60 + 10)
    }

    /**
     * n steady nights with a little seeded noise, so there is a personal spread.
     * [alternating] swaps the noise for a -1, 0, +1 cycle, which pins sleeping HR's
     * median at 50 and its robust spread at exactly 1.4826 bpm for the illness tests.
     */
    private fun steady(
        n: Int,
        alternating: Boolean = false,
        edit: (Int, NightInput) -> NightInput = { _, it -> it },
    ): EngineInput {
        val days = (0 until n).map { start.plusDays(it.toLong()) }
        val random = java.util.Random(7)
        val nights = days.mapIndexed { i, d ->
            val wiggle = if (alternating) (i % 3 - 1).toDouble() else random.nextGaussian()
            val hrvWiggle = if (alternating) wiggle else random.nextGaussian()
            d to edit(
                i,
                NightInput(
                    date = d,
                    lnHrv = Signal(ln(55.0) + 0.05 * hrvWiggle, "watch"),
                    sleepingHr = Signal(50.0 + wiggle, "watch"),
                    restingHr = 55.0,
                    skinTempDelta = 0.05 * wiggle,
                    breathingRate = 14.0,
                    sleep = nightAt(d),
                ),
            )
        }.toMap()
        return EngineInput(
            days = days,
            nights = nights,
            sleepIntervals = nights.values.mapNotNull { it.sleep }.map { SleepInterval(it.startEpochMs, it.endEpochMs, 0) },
        )
    }

    @Test
    fun `the filter settles at the spec's steady gain of about 0_39`() {
        val f = SignalFilter(BodyConfig(), defaultNoise = 1.0)
        repeat(200) { f.step(it.toLong(), Signal(0.0, "w"), 1.0) }
        val jumped = f.step(200, Signal(1.0, "w"), 1.0)!!
        jumped.x shouldBe (0.39 plusOrMinus 0.01)
    }

    @Test
    fun `a missed night widens the range on its own`() {
        val f = SignalFilter(BodyConfig(), defaultNoise = 1.0)
        repeat(50) { f.step(it.toLong(), Signal(0.0, "w"), 1.0) }
        val seen = f.step(50, Signal(0.0, "w"), 1.0)!!
        val missed = f.step(51, null, 1.0)!!
        missed.p shouldBeGreaterThan seen.p
    }

    @Test
    fun `a patchy night barely moves the estimate`() {
        val clean = SignalFilter(BodyConfig(), 1.0)
        val patchy = SignalFilter(BodyConfig(), 1.0)
        repeat(50) {
            clean.step(it.toLong(), Signal(0.0, "w"), 1.0)
            patchy.step(it.toLong(), Signal(0.0, "w"), 1.0)
        }
        val a = clean.step(50, Signal(1.0, "w"), 1.0)!!.x
        val b = patchy.step(50, Signal(1.0, "w"), 0.2)!!.x
        b shouldBeLessThan a
    }

    @Test
    fun `no score before seven valid nights`() {
        val result = PawseEngine().run(steady(5))
        result.days.last().bodyStatus shouldBe BodyStatus.WARMING_UP
        result.days.last().body.shouldBeNull()
    }

    @Test
    fun `a usual night reads Typical, with a range around it`() {
        val input = steady(40) { i, n ->
            if (i == 39) n.copy(lnHrv = Signal(ln(55.0), "watch"), sleepingHr = Signal(50.0, "watch")) else n
        }
        val body = PawseEngine().run(input).days.last().body.shouldNotBeNull()
        body.band shouldBe BodyBand.TYPICAL
        body.score shouldBeInRange body.low..body.high
        body.confidence shouldNotBe Confidence.LOW
    }

    @Test
    fun `a bad night reads below normal and names sleeping HR first`() {
        val input = steady(40) { i, n ->
            if (i == 39) n.copy(lnHrv = Signal(ln(38.0), "watch"), sleepingHr = Signal(58.0, "watch")) else n
        }
        val body = PawseEngine().run(input).days.last().body.shouldNotBeNull()
        body.score shouldBeLessThan 31
        body.reasons.map { it.kind } shouldContain ReasonKind.SLEEPING_HR_ABOVE
    }

    @Test
    fun `a fake cold reaches Watch on night two and Alert on night three`() {
        // Sleeping HR 2.5 spreads above normal for four nights, temperature up on the third.
        val spread = 1.4826
        val input = steady(44, alternating = true) { i, n ->
            when {
                i >= 40 -> n.copy(
                    sleepingHr = Signal(50.0 + 2.5 * spread, "watch"),
                    skinTempDelta = if (i >= 42) 0.4 else n.skinTempDelta,
                )
                else -> n
            }
        }
        val days = PawseEngine().run(input).days
        days[40].illness.level shouldBe IllnessLevel.NONE
        days[41].illness.level shouldBe IllnessLevel.WATCH
        days[42].illness.level shouldBe IllnessLevel.ALERT
        days[42].guidance shouldBe Guidance.WATCH_SYMPTOMS
        days[42].load.rest shouldBe true
    }

    @Test
    fun `logged drinks hold an alert back and are named instead`() {
        val spread = 1.4826
        val base = steady(44, alternating = true) { i, n ->
            if (i >= 40) n.copy(sleepingHr = Signal(50.0 + 2.5 * spread, "watch"), skinTempDelta = 0.4) else n
        }
        val bedtime = base.nights.getValue(start.plusDays(42)).sleep!!.startEpochMs
        val input = base.copy(intakes = listOf(app.pawse.scoring.model.Intake(bedtime - 2 * HOUR, alcoholGrams = 30.0)))
        val illness = PawseEngine().run(input).days[42].illness
        illness.level shouldBe IllnessLevel.WATCH
        illness.likelyCauses shouldContain ReasonKind.DRINKS
    }

    @Test
    fun `one long lie-in repays at most an hour`() {
        val d = LocalDate.parse("2026-03-01")
        val nights = (0..6).associate { k -> d.minusDays(k.toLong()) to nightAt(d.minusDays(k.toLong()), if (k == 0) 12.0 else 5.0) }
        val debt = SleepMath.debtHours(nights, d, 8.0, SleepConfig())!!
        val expected = -1.0 + (1..6).sumOf { k -> Math.pow(0.85, k.toDouble()) * 3.0 }
        debt shouldBe (expected plusOrMinus 1e-9)
    }

    @Test
    fun `a rigid schedule has a regularity index of 100 and a drifting one less`() {
        val end = LocalDate.parse("2026-03-15")
        fun intervals(drift: Boolean) = (0 until 15).map { k ->
            val n = nightAt(end.minusDays(k.toLong()), bedHour = if (drift && k % 2 == 0) 21 else 23)
            SleepInterval(n.startEpochMs, n.endEpochMs, 0)
        }
        val recorded = (0 until 15).map { end.minusDays(it.toLong()) }.toSet()
        val wake = nightAt(end).endEpochMs
        SleepMath.sri(intervals(false), wake, 0, recorded, SleepConfig())!! shouldBe (100.0 plusOrMinus 1e-9)
        SleepMath.sri(intervals(true), wake, 0, recorded, SleepConfig())!! shouldBeLessThan 100.0
    }

    @Test
    fun `range scales the typical day by this morning's Body`() {
        val config = LoadConfig()
        TrainingLoad.range(100.0, 50, config) shouldBe (75.0 to 125.0)
        TrainingLoad.range(100.0, 80, config).first shouldBe (97.5 plusOrMinus 1e-9)
        TrainingLoad.formBand(-0.05, config) shouldBe FormBand.FRESH
        TrainingLoad.formBand(-0.2, config) shouldBe FormBand.PRODUCTIVE
        TrainingLoad.formBand(-0.4, config) shouldBe FormBand.HEAVY
    }

    @Test
    fun `guidance follows the table`() {
        fun body(band: BodyBand) = BodyScore(50, 40, 60, band, Confidence.HIGH, 0.0, 0.5, null, null, 0.5, 0.6, null, emptyList(), 30)
        val l = LoadConfig()
        GuidanceRules.pick(body(BodyBand.TYPICAL), 0, IllnessLevel.ALERT, 0.0, l) shouldBe Guidance.WATCH_SYMPTOMS
        GuidanceRules.pick(body(BodyBand.STRAINED), 2, IllnessLevel.NONE, 0.0, l) shouldBe Guidance.REST_DAY
        GuidanceRules.pick(body(BodyBand.STRAINED), 1, IllnessLevel.NONE, 0.0, l) shouldBe Guidance.KEEP_LIGHT
        GuidanceRules.pick(body(BodyBand.TYPICAL), 0, IllnessLevel.NONE, -0.4, l) shouldBe Guidance.GO_MODERATE
        GuidanceRules.pick(body(BodyBand.ABOVE_NORMAL), 0, IllnessLevel.NONE, -0.1, l) shouldBe Guidance.GOOD_DAY
        GuidanceRules.pick(null, 0, IllnessLevel.NONE, null, l) shouldBe Guidance.SETTLING
    }

    @Test
    fun `ridge regression finds a planted alcohol effect and nothing else`() {
        val x = (0 until 60).map { i ->
            DoubleArray(Predictor.entries.size).also {
                it[Predictor.ALCOHOL.ordinal] = if (i % 3 == 0) 2.0 else 0.0
                it[Predictor.SLEEP_DEBT.ordinal] = 1.0 + (i % 5) * 0.1
            }
        }
        val y = x.mapIndexed { i, row -> -0.4 * row[Predictor.ALCOHOL.ordinal] + 0.05 * sin(i.toDouble()) }
        val insights = DoseResponse.fit(x, y, LearningConfig())
        val alcohol = insights.single { it.predictor == Predictor.ALCOHOL }
        // Ridge shrinks a little toward zero, by design.
        alcohol.effect shouldBe (-0.4 plusOrMinus 0.08)
        alcohol.high shouldBeLessThan 0.0
    }

    @Test
    fun `calibration is shrunk toward the default and clipped`() {
        val n = 30
        val zh = (0 until n).map { sin(it.toDouble()) }
        val zr = (0 until n).map { sin(it * 2.7) }
        val checkIns = zh.map { 3.0 + it }
        CheckInCalibration.hrvWeight(zh, zr, List(n) { 0.5 }, checkIns, 0.6, LearningConfig()) shouldBe (0.8 plusOrMinus 1e-9)
        CheckInCalibration.hrvWeight(zh.take(10), zr.take(10), List(10) { 0.5 }, checkIns.take(10), 0.6, LearningConfig()) shouldBe 0.6
    }

    @Test
    fun `cycle offsets are learned from two complete cycles`() {
        val first = LocalDate.parse("2026-01-01")
        val starts = (0..3).map { first.plusDays(28L * it) }
        val periods = starts.flatMap { s -> (0..4).map { s.plusDays(it.toLong()) } }.toSet()
        val hrv = (0 until 84).associate { i ->
            val d = first.plusDays(i.toLong())
            d to if (i % 28 >= 14) 3.9 else 4.0
        }
        val model = Cycle.model(periods, first.plusDays(90), hrv, emptyMap(), emptyMap(), ContextConfig())
        model.deltaLnHrv!! shouldBe (-0.1 plusOrMinus 1e-9)
    }

    @Test
    fun `HRV is the log of the night's median, and a sparse night is penalised`() {
        val full = SignalLayer.hrv(listOf(40.0, 50.0, 60.0, 500.0), fallback = null)!!
        full.lnRmssd shouldBe (ln(50.0) plusOrMinus 1e-9)
        val sparse = SignalLayer.hrv(listOf(45.0), fallback = 48.0)!!
        sparse.lnRmssd shouldBe (ln(48.0) plusOrMinus 1e-9)
        sparse.penalty shouldBeLessThan full.penalty
    }

    @Test
    fun `sleeping HR is the lowest half hour, and a sparse series falls back`() {
        val startMs = 0L
        val endMs = 8 * HOUR
        val dense = (0 until 480).map { m -> m * 60_000L to if (m in 200..240) 46.0 else 52.0 }
        val night = SignalLayer.heartRate(dense, startMs, endMs)
        night.sleepingBpm!! shouldBe (46.0 plusOrMinus 0.5)
        night.nadirFraction!! shouldBe (220.0 / 480.0 plusOrMinus 0.05)
        val sparse = (0 until 48).map { k -> k * 10 * 60_000L to 50.0 }
        SignalLayer.heartRate(sparse, startMs, endMs).sleepingBpm.shouldBeNull()
    }

    @Test
    fun `the energy curve puts focus after waking and wind-down an hour before bed`() {
        val d = LocalDate.parse("2026-03-10")
        val nights = (1..14).map { k -> d.minusDays(k.toLong()) to nightAt(d.minusDays(k.toLong())) }.reversed()
        val last = nightAt(d)
        val intervals = (nights.map { it.second } + last).map { SleepInterval(it.startEpochMs, it.endEpochMs, 0) }
        val f = EnergyForecastModel.forecast(last, nights, d, intervals, 0.0, 0.02, emptyList(), EnergyConfig())
        f.wakeMinute shouldBe 7 * 60
        f.windDownMinute shouldBe 22 * 60
        (f.focusStart >= f.wakeMinute + 60) shouldBe true
        (f.dipMinute in (12 * 60)..(17 * 60)) shouldBe true
        f.clockLagMinutes shouldBe 0
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val DAY = 86_400_000L
    }
}
