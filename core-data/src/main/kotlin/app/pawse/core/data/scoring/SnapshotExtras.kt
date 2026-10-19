package app.pawse.core.data.scoring

import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.ScoreType
import java.time.LocalDate
import kotlin.math.sqrt

/** A score's last seven days, oldest first, over the range it usually sits in. */
data class ScoreTrend(
    /** Seven slots ending on the snapshot date. Null where nothing scored. */
    val values: List<Int?>,
    /** Mean and spread of every earlier scored day in the window. Null under five. */
    val usualMean: Double?,
    val usualSd: Double?,
)

/**
 * How far Recovery's baseline is from existing: "night 3 of 14, ready around
 * Fri 30 Oct". A count and a date, never just "not yet".
 */
data class Warmup(
    /** Nights before the scored one with an overnight HRV, which is what the baseline holds. */
    val nightsCollected: Int,
    val nightsNeeded: Int,
    val startedOn: LocalDate,
    val readyAround: LocalDate,
)

/**
 * The parts of a snapshot that are about more than one day: trends, the warm-up
 * countdown, and the earlier nights an explanation ranks tonight against.
 *
 * Pure functions over what the pipeline already produced, so nothing here is a
 * second engine. It only reads.
 */
object SnapshotExtras {

    const val TREND_DAYS = 7
    private const val MIN_USUAL_SAMPLES = 5

    fun trends(days: List<DayScores>, newest: LocalDate): Map<ScoreType, ScoreTrend> {
        val byDate = days.associateBy { it.date }
        return ScoreType.entries.associateWith { type ->
            val values = (TREND_DAYS - 1 downTo 0).map { back ->
                byDate[newest.minusDays(back.toLong())]?.outcomes?.get(type)?.scoreOrNull?.value
            }
            val earlier = days.filter { it.date.isBefore(newest) }
                .mapNotNull { it.outcomes[type]?.scoreOrNull?.value?.toDouble() }
            if (earlier.size < MIN_USUAL_SAMPLES) {
                ScoreTrend(values, null, null)
            } else {
                val mean = earlier.average()
                ScoreTrend(values, mean, sqrt(earlier.sumOf { (it - mean) * (it - mean) } / earlier.size))
            }
        }
    }

    /** Null once the baseline has enough nights; the countdown is over. */
    fun warmup(bundles: List<DayBundle>, newest: LocalDate, needed: Int, windowDays: Int): Warmup? {
        val nights = bundles
            .filter { it.date.isBefore(newest) && !it.date.isBefore(newest.minusDays(windowDays.toLong())) }
            .filter { it.values.containsKey(Metric.HRV_RMSSD) }
            .map { it.date }
        if (nights.size >= needed) return null
        return Warmup(
            nightsCollected = nights.size,
            nightsNeeded = needed,
            startedOn = nights.minOrNull() ?: newest,
            readyAround = newest.plusDays((needed - nights.size).toLong()),
        )
    }

    /**
     * Earlier nights' raw values for each Recovery input, so the explanation can say
     * "better than 35 of 43" from the same numbers the baseline was built on.
     */
    fun nightValues(
        bundles: List<DayBundle>,
        days: List<DayScores>,
        newest: LocalDate,
        windowDays: Int,
    ): Map<Metric, List<Double>> {
        fun inWindow(date: LocalDate) =
            date.isBefore(newest) && !date.isBefore(newest.minusDays(windowDays.toLong()))

        val fromBundles = listOf(
            Metric.HRV_RMSSD,
            Metric.RESTING_HEART_RATE,
            Metric.RESPIRATORY_RATE,
            Metric.SKIN_TEMPERATURE,
            Metric.SPO2,
        ).associateWith { metric ->
            bundles.filter { inWindow(it.date) }.mapNotNull { it.values[metric] }
        }
        val sleep = days.filter { inWindow(it.date) }.mapNotNull { it.sleep.scoreOrNull?.value?.toDouble() }
        return fromBundles + (Metric.SLEEP_SCORE to sleep)
    }
}
