package app.pawse.scoring.load

import app.pawse.scoring.config.ScoringConfig
import app.pawse.scoring.model.Band
import app.pawse.scoring.model.Contribution
import app.pawse.scoring.model.DailyLoad
import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.Provenance
import app.pawse.scoring.model.Score
import app.pawse.scoring.model.ScoreType
import app.pawse.scoring.model.ScoreUnavailable
import app.pawse.scoring.model.ScoringOutcome
import kotlin.math.roundToInt

/** Acute and chronic load, and the ratio between them. */
data class LoadRatio(
    val acute: Double,
    val chronic: Double,
    val ratio: Double,
    val acuteDays: Int,
    val chronicDays: Int,
    val distinctDays: Int,
)

/**
 * Acute:chronic workload ratio.
 *
 *   acute   = EWMA of daily load, 7-day time constant
 *   chronic = EWMA of daily load, 28-day time constant
 *   ratio   = acute / chronic
 *
 * EWMA rather than rolling averages, because it explains more non-contact injury
 * variance in the literature the reports cite: 21-52% against 17-39% in one soccer
 * cohort (Murray and Gabbett 2017, claude.md §10).
 *
 * ### Why this score ships with a disclaimer attached
 *
 * This is the only number in the app whose own construct is disputed in the
 * source material. claude.md §10 records Impellizzeri et al. (2021, *Sports
 * Medicine* 51(3):581-592) arguing the ratio is mathematically coupled and
 * confounded, and calling to "dismiss ACWR and its underlying theory". The acute
 * load is inside the chronic load, so the two are not independent, and a ratio of
 * dependent quantities is a poor inferential tool.
 *
 * We ship it anyway, for one reason: as a coarse ramp-rate guard it still tells a
 * user something true and useful, which is that this week looks very different from
 * the last month. What we do not do is present it as an injury predictor. [CRITIQUE]
 * is rendered next to the number, verbatim, every time it is shown. That is not
 * decoration; it is the condition under which the number is honest.
 *
 * [Score.value] stores the ratio times 100, so 1.15 persists as 115. The score
 * table is keyed on integers and inventing a second numeric column for one score
 * type would be worse than documenting the factor of a hundred.
 */
class LoadRatioScorer(private val config: ScoringConfig) {

    companion object {
        /**
         * Shown next to the ratio, every time. Kept here rather than in a string
         * resource so that :core-scoring stays pure Kotlin and so that the caveat
         * cannot be dropped by a UI refactor that never touched the engine.
         */
        const val CRITIQUE: String =
            "Treat this as a ramp-rate guard, not an injury predictor. The acute load is " +
                "part of the chronic load, so the two are not independent, and Impellizzeri " +
                "and colleagues (Sports Medicine, 2021) argue the ratio is mathematically " +
                "coupled and confounded. A week that looks very different from the month " +
                "behind it is worth noticing. It is not a diagnosis."
    }

    fun score(date: String, loads: List<DailyLoad>): ScoringOutcome {
        val ratioConfig = config.loadRatio

        // One value per day. A duplicated day would be counted twice by the EWMA
        // and would move the acute end far more than the chronic one.
        val byDay = loads
            .filter { it.daysAgo >= 0 }
            .groupBy { it.daysAgo }
            .mapValues { (_, dupes) -> dupes.sumOf { it.load } }

        if (byDay.isEmpty()) {
            return notScored(date, ScoreUnavailable.Reason.NO_REQUIRED_INPUT, listOf(Metric.CHRONIC_LOAD))
        }
        if (byDay.size < ratioConfig.minimumChronicDays) {
            return notScored(date, ScoreUnavailable.Reason.BASELINE_WARMUP, listOf(Metric.CHRONIC_LOAD))
        }

        val result = compute(byDay, ratioConfig.acuteDays, ratioConfig.chronicDays)
        if (result == null || result.chronic <= 0.0) {
            // No chronic load means no denominator. A ratio against zero is not
            // "very high", it is undefined, and printing infinity as a training
            // recommendation would be absurd.
            return notScored(date, ScoreUnavailable.Reason.NO_REQUIRED_INPUT, listOf(Metric.CHRONIC_LOAD))
        }

        val value = (result.ratio * 100.0).roundToInt()
        val warmingUp = result.distinctDays < ratioConfig.chronicDays

        val contributions = listOf(
            Contribution(
                metric = Metric.ACUTE_LOAD,
                raw = result.acute,
                baselineMean = null,
                baselineSd = null,
                baselineWindowDays = ratioConfig.acuteDays,
                baselineSampleCount = result.distinctDays,
                z = null,
                weight = 0.0,
                nominalWeight = 0.0,
                // A ratio has no additive decomposition. Zero here is the honest
                // value; the two raws above are the whole story.
                points = 0.0,
                provenance = Provenance.PUBLISHED,
                citation = "${ratioConfig.acuteDays}-day EWMA of daily load. EWMA rather than " +
                    "a rolling mean: Murray and Gabbett 2017 (claude.md §10). $CRITIQUE",
                present = true,
            ),
            Contribution(
                metric = Metric.CHRONIC_LOAD,
                raw = result.chronic,
                baselineMean = null,
                baselineSd = null,
                baselineWindowDays = ratioConfig.chronicDays,
                baselineSampleCount = result.distinctDays,
                z = null,
                weight = 0.0,
                nominalWeight = 0.0,
                points = 0.0,
                provenance = Provenance.PUBLISHED,
                citation = "${ratioConfig.chronicDays}-day EWMA of daily load. Sweet spot " +
                    "${ratioConfig.sweetSpotLow}-${ratioConfig.sweetSpotHigh}, elevated above " +
                    "${ratioConfig.elevatedAbove} (Gabbett 2016). $CRITIQUE",
                present = true,
            ),
        )

        val coverage = (result.distinctDays.toFloat() / ratioConfig.chronicDays).coerceIn(0f, 1f)
        return ScoringOutcome.Scored(
            Score(
                type = ScoreType.LOAD_RATIO,
                date = date,
                value = value,
                band = Band.ofLoadRatio(
                    value = value,
                    sweetSpotLow = ratioConfig.sweetSpotLow,
                    sweetSpotHigh = ratioConfig.sweetSpotHigh,
                    elevatedAbove = ratioConfig.elevatedAbove,
                ),
                contributions = contributions,
                dataCoverage = coverage,
                degraded = warmingUp,
                warmingUp = warmingUp,
                scoringVersion = ScoringConfig.SCORING_VERSION,
                configHash = config.configHash,
            ),
        )
    }

    /** Exposed so a trend chart can plot the two curves without going through Score. */
    fun ratio(loads: List<DailyLoad>): LoadRatio? {
        val byDay = loads.filter { it.daysAgo >= 0 }
            .groupBy { it.daysAgo }
            .mapValues { (_, dupes) -> dupes.sumOf { it.load } }
        return compute(byDay, config.loadRatio.acuteDays, config.loadRatio.chronicDays)
    }

    private fun compute(byDay: Map<Int, Double>, acuteDays: Int, chronicDays: Int): LoadRatio? {
        if (byDay.isEmpty()) return null
        val oldest = byDay.keys.max()

        // Walk forward in time from the oldest day to today, filling gaps with zero.
        // A rest day is a real zero-load day and has to decay the averages; skipping
        // it would let a fortnight off look like continuous training.
        val series = (oldest downTo 0).map { byDay[it] ?: 0.0 }

        val acute = ewma(series, acuteDays) ?: return null
        val chronic = ewma(series, chronicDays) ?: return null
        if (chronic <= 0.0) {
            return LoadRatio(acute, chronic, 0.0, acuteDays, chronicDays, byDay.size)
        }
        return LoadRatio(
            acute = acute,
            chronic = chronic,
            ratio = acute / chronic,
            acuteDays = acuteDays,
            chronicDays = chronicDays,
            distinctDays = byDay.size,
        )
    }

    /**
     * Exponentially weighted moving average with lambda = 2 / (N + 1), the
     * standard span-to-decay conversion, seeded on the first value and iterated
     * oldest to newest.
     */
    private fun ewma(series: List<Double>, spanDays: Int): Double? {
        if (series.isEmpty() || spanDays <= 0) return null
        val lambda = 2.0 / (spanDays + 1.0)
        var value = series.first()
        for (i in 1 until series.size) {
            value = lambda * series[i] + (1.0 - lambda) * value
        }
        return value
    }

    private fun notScored(date: String, reason: ScoreUnavailable.Reason, missing: List<Metric>) =
        ScoringOutcome.NotScored(
            ScoreUnavailable(type = ScoreType.LOAD_RATIO, date = date, reason = reason, missing = missing),
        )
}
