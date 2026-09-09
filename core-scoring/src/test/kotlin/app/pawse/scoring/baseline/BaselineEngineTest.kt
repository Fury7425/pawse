package app.pawse.scoring.baseline

import app.pawse.scoring.config.BaselineConfig
import app.pawse.scoring.config.BaselineMode
import app.pawse.scoring.fixtures.Fixtures
import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.MetricHistory
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

class BaselineEngineTest {

    /** Half-life large enough that the exponential weighting is effectively flat. */
    private val unweighted = BaselineConfig(
        windowDays = 60,
        halfLifeDays = 1e9,
        warmupSamples = 3,
    )

    private fun samples(vararg values: Double) = MetricHistory(
        metric = Metric.HRV_RMSSD,
        samples = values.mapIndexed { i, v -> MetricHistory.Sample(daysAgo = i + 1, value = v) },
    )

    @Test
    fun `unweighted mean and unbiased sample SD, computed by hand`() {
        val baseline = BaselineEngine(unweighted).baseline(samples(10.0, 12.0, 14.0, 16.0, 18.0))!!
        // mean 14; sum of squared deviations 40; /(n-1) = 10; sd = sqrt(10)
        baseline.mean shouldBe (14.0 plusOrMinus 1e-9)
        baseline.sigma shouldBe (3.1622776601 plusOrMinus 1e-6)
        baseline.sampleCount shouldBe 5
        baseline.warmingUp shouldBe false
    }

    @Test
    fun `tonight is excluded from its own baseline`() {
        // daysAgo 0 is tonight. Including it would drag the mean toward the value
        // being judged and shrink the very z-score the night is about.
        val history = MetricHistory(
            Metric.HRV_RMSSD,
            listOf(
                MetricHistory.Sample(0, 1000.0),
                MetricHistory.Sample(1, 10.0),
                MetricHistory.Sample(2, 12.0),
                MetricHistory.Sample(3, 14.0),
            ),
        )
        BaselineEngine(unweighted).baseline(history)!!.mean shouldBe (12.0 plusOrMinus 1e-9)
    }

    @Test
    fun `samples beyond the window are dropped`() {
        val history = MetricHistory(
            Metric.HRV_RMSSD,
            listOf(MetricHistory.Sample(1, 10.0), MetricHistory.Sample(999, 900.0)),
        )
        BaselineEngine(unweighted).baseline(history)!!.sampleCount shouldBe 1
    }

    @Test
    fun `two writers covering the same day count once, averaged`() {
        // Duplicate sources are kept at ingest and resolved at read time, so the
        // engine has to assume it may see the same night twice.
        val history = MetricHistory(
            Metric.HRV_RMSSD,
            listOf(
                MetricHistory.Sample(1, 10.0),
                MetricHistory.Sample(1, 20.0),
                MetricHistory.Sample(2, 15.0),
            ),
        )
        val baseline = BaselineEngine(unweighted).baseline(history)!!
        baseline.sampleCount shouldBe 2
        baseline.mean shouldBe (15.0 plusOrMinus 1e-9)
    }

    @Test
    fun `recency weighting pulls the baseline toward the recent block`() {
        // Thirty nights at 50, then thirty nights at 70, the 70s being the recent ones.
        val history = MetricHistory(
            Metric.HRV_RMSSD,
            (1..60).map { MetricHistory.Sample(it, if (it <= 30) 70.0 else 50.0) },
        )
        val flat = BaselineEngine(unweighted).baseline(history)!!
        val decayed = BaselineEngine(BaselineConfig()).baseline(history)!!
        flat.mean shouldBe (60.0 plusOrMinus 1e-9)
        // A 14-day half-life over a 60-day window puts about 82% of the weight on
        // the recent block, which lands the baseline near 66 rather than 60.
        decayed.mean shouldBeGreaterThan 65.0
        decayed.mean shouldBeGreaterThan flat.mean
    }

    @Test
    fun `an empty window yields no baseline at all`() {
        BaselineEngine(unweighted).baseline(MetricHistory(Metric.HRV_RMSSD, emptyList())).shouldBeNull()
    }

    @Test
    fun `warm-up is flagged, not hidden`() {
        val warm = BaselineEngine(BaselineConfig()).baseline(
            MetricHistory(Metric.HRV_RMSSD, (1..6).map { MetricHistory.Sample(it, 60.0 + it) }),
        )!!
        warm.warmingUp shouldBe true
        warm.sampleCount shouldBe 6
    }

    @Test
    fun `a sensor stuck on one value produces no z-score rather than an infinite one`() {
        val engine = BaselineEngine(unweighted)
        val flat = engine.baseline(samples(60.0, 60.0, 60.0, 60.0, 60.0))!!
        flat.sigma shouldBe (0.0 plusOrMinus 1e-12)
        engine.z(flat, 45.0).shouldBeNull()
        engine.deviationSigma(flat, 45.0).shouldBeNull()
    }

    @Test
    fun `direction is applied so that positive always means better than baseline`() {
        val engine = BaselineEngine(unweighted)

        val hrv = engine.baseline(samples(50.0, 55.0, 60.0, 65.0, 70.0))!!
        // Higher HRV is better, so above baseline is positive.
        engine.z(hrv, 70.0)!! shouldBeGreaterThan 0.0

        val rhrHistory = MetricHistory(
            Metric.RESTING_HEART_RATE,
            listOf(50.0, 51.0, 52.0, 53.0, 54.0).mapIndexed { i, v -> MetricHistory.Sample(i + 1, v) },
        )
        val rhr = engine.baseline(rhrHistory)!!
        // Higher resting HR is worse, so above baseline is negative.
        engine.z(rhr, 60.0)!! shouldBeLessThan 0.0
    }

    @Test
    fun `temperature is scored on distance, so both directions read negative`() {
        val engine = BaselineEngine(unweighted)
        val history = MetricHistory(
            Metric.SKIN_TEMPERATURE,
            listOf(-0.2, -0.1, 0.0, 0.1, 0.2).mapIndexed { i, v -> MetricHistory.Sample(i + 1, v) },
        )
        val temp = engine.baseline(history)!!
        engine.z(temp, 0.6)!! shouldBeLessThan 0.0
        engine.z(temp, -0.6)!! shouldBeLessThan 0.0
        engine.deviationSigma(temp, 0.6)!! shouldBe (engine.deviationSigma(temp, -0.6)!! plusOrMinus 1e-9)
    }

    @Test
    fun `z is clamped so one wild reading cannot dominate the composite`() {
        val engine = BaselineEngine(unweighted)
        val hrv = engine.baseline(samples(60.0, 61.0, 62.0, 63.0, 64.0))!!
        engine.z(hrv, 5000.0)!! shouldBe (unweighted.zClamp plusOrMinus 1e-9)
        engine.z(hrv, -5000.0)!! shouldBe (-unweighted.zClamp plusOrMinus 1e-9)
    }

    @Test
    fun `the robust mode shrugs off a single slipped-strap night`() {
        // One 8 ms reading among sixty nights near 65 ms: the kind of artefact
        // grok.txt §12 warns about when it says a 10 ms RMSSD swing can be a
        // loose strap rather than physiology.
        val clean = (1..60).map { MetricHistory.Sample(it, 65.0 + (it % 5) - 2.0) }
        val dirty = clean.toMutableList().also { it[0] = MetricHistory.Sample(1, 8.0) }

        val meanSd = BaselineConfig(mode = BaselineMode.MEAN_SD)
        val medianMad = BaselineConfig(mode = BaselineMode.MEDIAN_MAD)

        val meanShift = kotlin.math.abs(
            BaselineEngine(meanSd).baseline(MetricHistory(Metric.HRV_RMSSD, clean))!!.mean -
                BaselineEngine(meanSd).baseline(MetricHistory(Metric.HRV_RMSSD, dirty))!!.mean,
        )
        val medianShift = kotlin.math.abs(
            BaselineEngine(medianMad).baseline(MetricHistory(Metric.HRV_RMSSD, clean))!!.mean -
                BaselineEngine(medianMad).baseline(MetricHistory(Metric.HRV_RMSSD, dirty))!!.mean,
        )
        medianShift shouldBeLessThan meanShift
    }

    @Test
    fun `fixture histories are centred, so a fixture baseline is exactly its stated mean`() {
        // The fixtures depend on this: "8 bpm above baseline" has to mean 8 bpm.
        val engine = BaselineEngine(Fixtures.BASELINE)
        val hrv = engine.baseline(Fixtures.history(Metric.HRV_RMSSD, Fixtures.HRV_MEAN, Fixtures.HRV_SD))!!
        hrv.mean shouldBe (Fixtures.HRV_MEAN plusOrMinus 1e-9)
        // And sigma should land close to the stated SD, not wander off it.
        hrv.sigma shouldBeGreaterThan Fixtures.HRV_SD * 0.75
        hrv.sigma shouldBeLessThan Fixtures.HRV_SD * 1.35
    }
}
