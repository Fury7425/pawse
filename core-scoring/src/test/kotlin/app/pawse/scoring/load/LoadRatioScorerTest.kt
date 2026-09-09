package app.pawse.scoring.load

import app.pawse.scoring.config.ScoringConfig
import app.pawse.scoring.fixtures.Fixtures
import app.pawse.scoring.model.Band
import app.pawse.scoring.model.DailyLoad
import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.ScoreType
import app.pawse.scoring.model.ScoreUnavailable
import app.pawse.scoring.model.ScoringOutcome
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test
import kotlin.math.roundToInt

class LoadRatioScorerTest {

    private val config = ScoringConfig.DEFAULT
    private val ratioConfig = config.loadRatio
    private val scorer = LoadRatioScorer(config)

    private fun scored(outcome: ScoringOutcome) = (outcome as ScoringOutcome.Scored).score
    private fun unavailable(outcome: ScoringOutcome) = (outcome as ScoringOutcome.NotScored).unavailable

    /** EWMA recomputed independently, oldest to newest, seeded on the first value. */
    private fun ewmaReference(loads: List<DailyLoad>, spanDays: Int): Double {
        val byDay = loads.groupBy { it.daysAgo }.mapValues { (_, d) -> d.sumOf { it.load } }
        val series = (byDay.keys.max() downTo 0).map { byDay[it] ?: 0.0 }
        val lambda = 2.0 / (spanDays + 1.0)
        var value = series.first()
        for (i in 1 until series.size) value = lambda * series[i] + (1.0 - lambda) * value
        return value
    }

    @Test
    fun `steady training gives a ratio of exactly one`() {
        val result = scorer.ratio(Fixtures.steadyLoads())!!
        result.acute shouldBe (120.0 plusOrMinus 1e-9)
        result.chronic shouldBe (120.0 plusOrMinus 1e-9)
        result.ratio shouldBe (1.0 plusOrMinus 1e-12)

        val score = scored(scorer.score("2026-03-10", Fixtures.steadyLoads()))
        score.type shouldBe ScoreType.LOAD_RATIO
        score.value shouldBe 100
        score.band shouldBe Band.HIGH
    }

    @Test
    fun `both EWMAs match an independent recomputation`() {
        val loads = Fixtures.blockLoads()
        val result = scorer.ratio(loads)!!
        result.acute shouldBe (ewmaReference(loads, ratioConfig.acuteDays) plusOrMinus 1e-9)
        result.chronic shouldBe (ewmaReference(loads, ratioConfig.chronicDays) plusOrMinus 1e-9)
    }

    @Test
    fun `the value is the ratio times a hundred, as documented`() {
        val loads = Fixtures.blockLoads()
        val result = scorer.ratio(loads)!!
        val score = scored(scorer.score("2026-03-10", loads))
        score.value shouldBe (result.ratio * 100.0).roundToInt()
    }

    @Test
    fun `ramping up puts the ratio above one and tapering puts it below`() {
        val ramp = scorer.ratio(Fixtures.blockLoads(recentLoad = 240.0, baseLoad = 120.0))!!
        val taper = scorer.ratio(Fixtures.blockLoads(recentLoad = 20.0, baseLoad = 240.0))!!

        ramp.ratio shouldBeGreaterThan 1.0
        taper.ratio shouldBeLessThan 1.0
        // The acute end moves faster than the chronic end. That is the whole
        // mechanism, and it is also exactly why the two are not independent.
        ramp.acute shouldBeGreaterThan ramp.chronic
        taper.acute shouldBeLessThan taper.chronic
    }

    @Test
    fun `a week inside the sweet spot reads as the only good region`() {
        val steadyish = Fixtures.blockLoads(recentLoad = 180.0, baseLoad = 120.0)
        val score = scored(scorer.score("2026-03-10", steadyish))
        (score.value in 80..130) shouldBe true
        score.band shouldBe Band.HIGH
    }

    @Test
    fun `a spike lands in the flagged band`() {
        val spike = Fixtures.blockLoads(recentLoad = 600.0, baseLoad = 100.0)
        val score = scored(scorer.score("2026-03-10", spike))
        (score.value > (ratioConfig.elevatedAbove * 100).toInt()) shouldBe true
        score.band shouldBe Band.LOW
    }

    @Test
    fun `both tails of the sweet spot are moderate, and only the far end is low`() {
        fun band(ratio: Double) = Band.ofLoadRatio(
            value = (ratio * 100).toInt(),
            sweetSpotLow = ratioConfig.sweetSpotLow,
            sweetSpotHigh = ratioConfig.sweetSpotHigh,
            elevatedAbove = ratioConfig.elevatedAbove,
        )
        band(0.40) shouldBe Band.MODERATE
        band(0.79) shouldBe Band.MODERATE
        band(0.80) shouldBe Band.HIGH
        band(1.00) shouldBe Band.HIGH
        band(1.30) shouldBe Band.HIGH
        band(1.31) shouldBe Band.MODERATE
        band(1.50) shouldBe Band.MODERATE
        band(1.51) shouldBe Band.LOW
        band(3.00) shouldBe Band.LOW
    }

    @Test
    fun `the Impellizzeri critique ships with the number, every time`() {
        // Not decoration. This is the condition under which the number is honest,
        // so it lives in the engine where a UI refactor cannot drop it.
        val score = scored(scorer.score("2026-03-10", Fixtures.steadyLoads()))
        score.contributions.size shouldBe 2
        score.contributions.forEach {
            it.citation.contains("ramp-rate guard") shouldBe true
            it.citation.contains("not an injury predictor") shouldBe true
        }
        LoadRatioScorer.CRITIQUE.contains("Impellizzeri") shouldBe true
    }

    @Test
    fun `a ratio has no additive decomposition, and does not pretend otherwise`() {
        val score = scored(scorer.score("2026-03-10", Fixtures.steadyLoads()))
        score.contributions.forEach {
            it.points shouldBe (0.0 plusOrMinus 1e-12)
            it.present shouldBe true
        }
        score.contributions.map { it.metric } shouldBe listOf(Metric.ACUTE_LOAD, Metric.CHRONIC_LOAD)
    }

    @Test
    fun `nine days of history is a refusal, not a cautious estimate`() {
        val young = Fixtures.steadyLoads(days = 9)
        val reason = unavailable(scorer.score("2026-03-10", young))
        reason.reason shouldBe ScoreUnavailable.Reason.BASELINE_WARMUP
        reason.missing shouldBe listOf(Metric.CHRONIC_LOAD)
    }

    @Test
    fun `between two and four weeks the ratio is printed but flagged as warming up`() {
        val twentyDays = Fixtures.steadyLoads(days = 20)
        val score = scored(scorer.score("2026-03-10", twentyDays))
        score.warmingUp shouldBe true
        score.degraded shouldBe true
        score.dataCoverage.toDouble() shouldBe (20.0 / 28.0 plusOrMinus 1e-6)
    }

    @Test
    fun `a full four weeks is not warming up`() {
        val score = scored(scorer.score("2026-03-10", Fixtures.steadyLoads(days = 28)))
        score.warmingUp shouldBe false
        score.degraded shouldBe false
        score.dataCoverage.toDouble() shouldBe (1.0 plusOrMinus 1e-6)
    }

    @Test
    fun `no history at all is a different refusal from too little history`() {
        unavailable(scorer.score("2026-03-10", emptyList())).reason shouldBe
            ScoreUnavailable.Reason.NO_REQUIRED_INPUT
    }

    @Test
    fun `a chronic load of zero has no ratio, rather than an infinite one`() {
        val allRest = (0 until 40).map { DailyLoad(daysAgo = it, load = 0.0) }
        unavailable(scorer.score("2026-03-10", allRest)).reason shouldBe
            ScoreUnavailable.Reason.NO_REQUIRED_INPUT
    }

    @Test
    fun `two sessions on one day are one day's load, not two`() {
        val split = (0 until 40).flatMap {
            listOf(DailyLoad(it, 60.0), DailyLoad(it, 60.0))
        }
        val merged = (0 until 40).map { DailyLoad(it, 120.0) }
        scorer.ratio(split)!!.acute shouldBe (scorer.ratio(merged)!!.acute plusOrMinus 1e-9)
        scorer.ratio(split)!!.distinctDays shouldBe 40
    }

    @Test
    fun `missing days decay the averages instead of being skipped`() {
        // A fortnight off is not continuous training. The gap has to be a real
        // zero, or two weeks of rest would leave the acute load untouched.
        val everyOtherDay = (0 until 28 step 2).map { DailyLoad(it, 240.0) }
        val everyDay = (0 until 14).map { DailyLoad(it, 240.0) }

        val sparse = scorer.ratio(everyOtherDay)!!
        sparse.distinctDays shouldBe 14
        sparse.acute shouldBeLessThan 240.0

        val dense = scorer.ratio(everyDay)!!
        dense.acute shouldBe (240.0 plusOrMinus 1e-9)
    }

    @Test
    fun `days in the future are ignored`() {
        val withFuture = Fixtures.steadyLoads() + DailyLoad(daysAgo = -3, load = 9999.0)
        scorer.ratio(withFuture)!!.ratio shouldBe (1.0 plusOrMinus 1e-12)
    }

    @Test
    fun `an empty series has no ratio`() {
        scorer.ratio(emptyList()).shouldBeNull()
    }

    @Test
    fun `the score carries the version and config hash that produced it`() {
        val score = scored(scorer.score("2026-03-10", Fixtures.steadyLoads()))
        score.scoringVersion shouldBe ScoringConfig.SCORING_VERSION
        score.configHash shouldBe config.configHash
    }
}
