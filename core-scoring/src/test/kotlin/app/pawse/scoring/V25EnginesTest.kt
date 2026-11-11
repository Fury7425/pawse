package app.pawse.scoring

import app.pawse.scoring.activity.MovingDay
import app.pawse.scoring.activity.MovingToday
import app.pawse.scoring.activity.MovingWord
import app.pawse.scoring.attention.Attention
import app.pawse.scoring.attention.Checked
import app.pawse.scoring.attention.Fact
import app.pawse.scoring.attention.FactUnit
import app.pawse.scoring.attention.NumberCheck
import app.pawse.scoring.attention.Watched
import app.pawse.scoring.attention.WatchedSeries
import app.pawse.scoring.attention.WeeklyCandidate
import app.pawse.scoring.attention.WeeklyItem
import app.pawse.scoring.body.GoalEstimate
import app.pawse.scoring.body.TrendPoint
import app.pawse.scoring.body.WeighInPoint
import app.pawse.scoring.body.Weight
import app.pawse.scoring.config.AttentionConfig
import app.pawse.scoring.config.EnergyBalanceConfig
import app.pawse.scoring.config.FitnessConfig
import app.pawse.scoring.config.LoadConfig
import app.pawse.scoring.config.MovingConfig
import app.pawse.scoring.config.PointsConfig
import app.pawse.scoring.config.TonightConfig
import app.pawse.scoring.config.WeightConfig
import app.pawse.scoring.energy.EnergyBalance
import app.pawse.scoring.energy.LoggedDay
import app.pawse.scoring.fitness.CardioFitness
import app.pawse.scoring.fitness.Vo2Reading
import app.pawse.scoring.load.CadenceMinutes
import app.pawse.scoring.load.LoadPoints
import app.pawse.scoring.model.BiologicalSex
import app.pawse.scoring.model.PointsRange
import app.pawse.scoring.sleep.Tonight
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test
import kotlin.math.roundToInt

/** Every worked example in the v2.5 engines doc, run as written. */
class V25EnginesTest {

    @Test
    fun `burned so far and by bedtime match the doc's 14-20 example`() {
        val config = EnergyBalanceConfig()
        val soFar = EnergyBalance.burnedSoFar(1680.0, 14 * 60 + 20.0, 650.0, 1566.0, 1.0, config)
        EnergyBalance.shown(soFar, config) shouldBe 1810
        val bed = EnergyBalance.byBedtime(soFar, 1680.0, 14 * 60 + 20.0, 23 * 60.0, 120.0, 1.0)
        EnergyBalance.shown(bed, config) shouldBe 2540
        // A watch total already holds resting burn, so BMR is not added again.
        EnergyBalance.burnedSoFar(1680.0, 860.0, 1653.0, 0.0, 1.0, config, watchTotal = true) shouldBe (1653.0 plusOrMinus 1e-9)
    }

    @Test
    fun `the weight check waits for enough days, shrinks toward 1 and clips`() {
        val config = EnergyBalanceConfig()
        fun days(n: Int, eaten: Double) = List(n) { LoggedDay(eaten, 3, false, 2200.0) }
        EnergyBalance.weightCheck(days(13, 2000.0), -0.8, 28, 10, config) shouldBe 1.0
        EnergyBalance.weightCheck(days(28, 2000.0), -0.8, 28, 7, config) shouldBe 1.0
        // k_obs = (2000 + 7700 x 0.8 / 28) / 2200 = 1.009; (28 x 1.009 + 14) / 42 = 1.006.
        EnergyBalance.weightCheck(days(28, 2000.0), -0.8, 28, 10, config) shouldBe (1.006 plusOrMinus 0.001)
        EnergyBalance.weightCheck(days(28, 3000.0), -0.8, 28, 10, config) shouldBe 1.15
        // Days marked incomplete or with one meal do not count.
        val partial = days(20, 2000.0) + List(10) { LoggedDay(800.0, 1, false, 2200.0) }
        EnergyBalance.weightCheck(partial, -0.8, 28, 10, config) shouldBe (EnergyBalance.weightCheck(days(20, 2000.0), -0.8, 28, 10, config))
    }

    @Test
    fun `trend steps grow with the gap and odd readings count half once`() {
        val config = WeightConfig()
        Weight.trend(listOf(WeighInPoint(0, 70.0), WeighInPoint(1, 71.0)), config).last().trend shouldBe (70.1 plusOrMinus 1e-9)
        // After 3 days alpha = 1 - 0.9^3 = 0.271.
        Weight.trend(listOf(WeighInPoint(0, 70.0), WeighInPoint(3, 71.0)), config).last().trend shouldBe (70.271 plusOrMinus 1e-9)
        // 2.9% above the trend: half weight and "likely water", then the same direction again counts fully.
        val odd = Weight.trend(listOf(WeighInPoint(0, 70.0), WeighInPoint(1, 72.0), WeighInPoint(2, 72.0)), config)
        odd[1].likelyWater.shouldBeTrue()
        odd[1].trend shouldBe (70.1 plusOrMinus 1e-9)
        odd[2].likelyWater.shouldBeFalse()
        odd[2].trend shouldBe (70.29 plusOrMinus 1e-9)
        // Four hours off the usual weigh-in time is odd too.
        val late = Weight.trend(listOf(WeighInPoint(0, 70.0, 420.0), WeighInPoint(1, 70.0, 420.0), WeighInPoint(2, 71.0, 720.0)), config)
        late.last().likelyWater.shouldBeTrue()
        // 26 days away, then 6% down: the trend is stale, so the reading counts fully.
        val back = Weight.trend(listOf(WeighInPoint(0, 97.7), WeighInPoint(2, 96.8), WeighInPoint(28, 90.9)), config)
        back.last().likelyWater.shouldBeFalse()
        back.last().trend shouldBe (91.33 plusOrMinus 0.01)
    }

    @Test
    fun `goal reads in whole months and only toward the goal`() {
        val config = WeightConfig()
        // Trend 72.2 today, falling 0.25 kg a week over 28 days.
        val trend = (0..28 step 3).map { d -> TrendPoint(d.toLong(), 0.0, 72.2 + 0.25 / 7.0 * (28 - d), false) }
        Weight.goal(trend, 70.0, 28, config) shouldBe GoalEstimate.Months(2, 2, 2)
        Weight.goal(trend, 75.0, 28, config) shouldBe GoalEstimate.NoClearTrend
        Weight.goal(trend.takeLast(5), 70.0, 28, config) shouldBe GoalEstimate.TooEarly
        // 1.2% a week for three weeks stops the projection.
        val fast = (0..21).map { d -> TrendPoint(d.toLong(), 0.0, 80.0 * (1 - 0.012 / 7 * d), false) }
        Weight.goal(fast, 60.0, 21, config) shouldBe GoalEstimate.TooFast
    }

    @Test
    fun `composition usual is median and MAD, and small changes read about the same`() {
        val config = WeightConfig()
        val fat = listOf(18.2, 18.6, 18.6, 19.0, 18.4, 18.8).mapIndexed { i, v -> WeighInPoint(i.toLong(), v) }
        val usual = Weight.usual(fat, 5, config).shouldNotBeNull()
        usual.median shouldBe (18.6 plusOrMinus 1e-9)
        usual.readings shouldBe 6
        Weight.usual(fat.take(3), 5, config).shouldBeNull()
        Weight.aboutSame(0.05, fat).shouldBeTrue()
        Weight.aboutSame(0.6, fat).shouldBeFalse()
    }

    @Test
    fun `fitness age follows FRIEND's curve with a range`() {
        val config = FitnessConfig()
        CardioFitness.ageFor(46.0, BiologicalSex.MALE, config).roundToInt() shouldBe 29
        CardioFitness.ageFor(49.0, BiologicalSex.MALE, config).roundToInt() shouldBe 23
        CardioFitness.ageFor(43.0, BiologicalSex.MALE, config).roundToInt() shouldBe 34
        CardioFitness.ageFor(10.0, BiologicalSex.MALE, config) shouldBe 85.0
        CardioFitness.ageFor(70.0, BiologicalSex.FEMALE, config) shouldBe 20.0
        // One reading, or two too close together, is not enough.
        CardioFitness.age(listOf(Vo2Reading(0, 46.0, "w")), BiologicalSex.MALE, config).shouldBeNull()
        CardioFitness.age(listOf(Vo2Reading(0, 46.0, "w"), Vo2Reading(5, 46.0, "w")), BiologicalSex.MALE, config).shouldBeNull()
        val age = CardioFitness.age(listOf(Vo2Reading(0, 46.0, "w"), Vo2Reading(20, 46.0, "w")), BiologicalSex.MALE, config).shouldNotBeNull()
        age.age shouldBe 29
        (age.low < age.age && age.age < age.high).shouldBeTrue()
    }

    @Test
    fun `load points scale to your usual day and the range follows Body`() {
        val points = PointsConfig()
        LoadPoints.range(50, false, 0, points) shouldBe PointsRange(8, 13, false)
        LoadPoints.range(78, false, 0, points) shouldBe PointsRange(9, 14, false)
        LoadPoints.range(78, true, 0, points) shouldBe PointsRange(0, 5, true)
        LoadPoints.range(10, false, 2, points) shouldBe PointsRange(0, 5, true)
        LoadPoints.reference(List(9) { 90.0 }, points) shouldBe 60.0
        LoadPoints.reference(List(10) { 90.0 }, points) shouldBe 90.0
        // 40 minutes at 100-130 steps a minute is 27 TRIMP for a man, 4.6 points at L_ref 60.
        val walk = LoadPoints.cadenceTrimp(CadenceMinutes(40.0, 0.0), BiologicalSex.MALE, points, LoadConfig())
        walk shouldBe (27.3 plusOrMinus 0.1)
        LoadPoints.points(walk, 60.0, points) shouldBe (4.6 plusOrMinus 0.05)
        LoadPoints.ringFraction(20.0, points) shouldBe 1f
    }

    @Test
    fun `tonight's need moves with the day and sets bed and wind-down`() {
        val config = TonightConfig()
        val plan = Tonight.plan(450.0, 12.0, 7.0, false, 0.0, 7 * 60 + 5.0, 15.0, config)
        plan.needMinutes shouldBe 470.0
        plan.bedMinute shouldBe 1380.0
        plan.windDownMinute shouldBe 1330.0
        // Never more than 45 minutes above base need, and a nap takes off half its length.
        Tonight.plan(450.0, 30.0, 7.0, true, 0.0, 425.0, 15.0, config).needMinutes shouldBe 495.0
        Tonight.plan(450.0, 7.0, 7.0, false, 40.0, 425.0, 15.0, config).needMinutes shouldBe 430.0
        Tonight.plan(450.0, 7.0, 7.0, false, 15.0, 425.0, 15.0, config).needMinutes shouldBe 450.0
    }

    @Test
    fun `moving today reads against the same stretch since waking`() {
        val config = MovingConfig()
        val days = (0 until 20).map { k -> MovingDay(false, List(64) { i -> (i + 1) * 100.0 * (0.5 + 0.05 * k) }) }
        val r = MovingToday.reading(250.0, 60.0, false, days, config)
        r.word shouldBe MovingWord.BELOW
        r.low!! shouldBe (295.0 plusOrMinus 1e-9)
        r.high!! shouldBe (485.0 plusOrMinus 1e-9)
        MovingToday.reading(400.0, 60.0, false, days, config).word shouldBe MovingWord.ABOUT
        MovingToday.reading(400.0, 60.0, false, days.take(6), config).word shouldBe MovingWord.LEARNING
    }

    @Test
    fun `worth knowing names only what is well outside your usual`() {
        val config = AttentionConfig()
        val usual = List(30) { listOf(50.0, 52.0, 54.0)[it % 3] }
        val shown = Attention.worthKnowing(
            listOf(
                WatchedSeries(Watched.HRV, 58.0, 53.0, usual),
                WatchedSeries(Watched.SLEEPING_HR, 54.9, 54.9, usual),
                WatchedSeries(Watched.BREATHING, 57.5, null, usual),
            ),
            config,
        )
        // HRV z = 6 / 2.97 = 2.0; sleeping HR 0.98 twice running is not past 1; breathing 1.85 is under 2.
        shown.map { it.signal } shouldBe listOf(Watched.HRV)
        Attention.worthKnowing(listOf(WatchedSeries(Watched.SLEEPING_HR, 55.5, 55.5, usual)), config).map { it.signal } shouldBe listOf(Watched.SLEEPING_HR)
    }

    @Test
    fun `the weekly note names the two biggest changes or none`() {
        val config = AttentionConfig()
        val steady = WeeklyCandidate(WeeklyItem.STEPS, listOf(8000.0, 8100.0, 7900.0, 8050.0, 7950.0, 8000.0, 8020.0, 8010.0), 1200.0)
        val sleep = WeeklyCandidate(WeeklyItem.ASLEEP, listOf(440.0, 445.0, 438.0, 442.0, 441.0, 439.0, 443.0, 460.0), 30.0)
        Attention.weeklyNote(listOf(steady), config).shouldBeEmpty()
        Attention.weeklyNote(listOf(steady, sleep), config).map { it.item } shouldBe listOf(WeeklyItem.ASLEEP)
        Attention.weeks(List(14) { it.toDouble() }) { it.average() } shouldBe listOf(3.0, 10.0)
    }

    @Test
    fun `the number check keeps matching sentences and drops the rest`() {
        val facts = listOf(
            Fact("asleep", listOf("slept", "asleep", "잤"), 444.0, FactUnit.DURATION),
            Fact("need", listOf("need", "필요"), 470.0, FactUnit.DURATION),
            Fact("hrv", listOf("HRV"), 58.0, FactUnit.MS),
            Fact("hrv", listOf("HRV", "usual"), 52.0, FactUnit.MS),
            Fact("bed", listOf("bed", "취침"), 1380.0, FactUnit.CLOCK),
        )
        val text = "You slept 7 h 24, under your 7 h 50 need. HRV was 58 ms against your usual 52 ms. Aim for bed by 23:00."
        NumberCheck.check(text, facts) shouldBe Checked(text, 0)
        NumberCheck.check("HRV was 64 ms. You slept 7 h 24.", facts).let {
            it.text shouldBe "You slept 7 h 24."
            it.dropped shouldBe 1
        }
        NumberCheck.check("7시간 24분 잤어요.", facts).text shouldBe "7시간 24분 잤어요."
        NumberCheck.check("Melatonin can help you reach 23:00.", facts).text.shouldBeNull()
        NumberCheck.symptoms("I have chest pain since last night").shouldBeTrue()
        NumberCheck.symptoms("가슴이 두근거려요").shouldBeTrue()
        NumberCheck.symptoms("Why was my HRV low?").shouldBeFalse()
    }
}
