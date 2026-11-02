package app.pawse.scoring.attention

import app.pawse.scoring.config.AttentionConfig
import app.pawse.scoring.math.Stats
import kotlin.math.abs
import kotlin.math.sign

/** What Worth knowing can point at. The first two score Body; the rest are shown, never scored. */
enum class Watched(val scored: Boolean) {
    HRV(true), SLEEPING_HR(true), BREATHING(false), TEMPERATURE(false), SPO2(false)
}

/** One signal: this morning, yesterday morning, and the nights its usual is built from. */
data class WatchedSeries(val signal: Watched, val today: Double?, val yesterday: Double?, val usualNights: List<Double>)

data class WorthKnowing(val signal: Watched, val value: Double, val median: Double, val spread: Double, val z: Double)

/** The candidates the weekly note may name. Weight joins only when the user turns it on. */
enum class WeeklyItem { BODY_AVERAGE, BODY_STEADINESS, ASLEEP, REGULARITY, LOAD_POINTS, STEPS, CHECK_IN, WEIGHT }

/**
 * One candidate's weekly figure, oldest first, the last being this week; null for a
 * week with nothing measured. [dailySd] is the fallback scale until there are 6
 * weeks of changes.
 */
data class WeeklyCandidate(val item: WeeklyItem, val weeks: List<Double?>, val dailySd: Double?)

data class WeeklyChange(val item: WeeklyItem, val change: Double, val z: Double)

/**
 * Attention decides which numbers deserve words.
 *
 * The usual band is +/-1 SD, so each signal sits outside it about one morning in
 * three, too often to be worth saying. With a robust z,
 *
 *   z = (value - median) / (1.4826 x MAD)
 *
 * HRV and sleeping HR appear past |z| 1.5, or past 1 two mornings running; the
 * unscored vitals only past 2. At most two, largest first. None collapses the card
 * to "All within your usual".
 */
object Attention {

    fun worthKnowing(series: List<WatchedSeries>, config: AttentionConfig): List<WorthKnowing> =
        series.mapNotNull { s ->
            val today = s.today ?: return@mapNotNull null
            val median = Stats.median(s.usualNights) ?: return@mapNotNull null
            val spread = Stats.robustSpread(s.usualNights)?.takeIf { it > 1e-9 } ?: return@mapNotNull null
            val z = (today - median) / spread
            val zYesterday = s.yesterday?.let { (it - median) / spread }
            val shown = if (s.signal.scored) {
                abs(z) > config.signalZ ||
                    (abs(z) > config.signalRunZ && zYesterday != null && abs(zYesterday) > config.signalRunZ && sign(z) == sign(zYesterday))
            } else {
                abs(z) > config.vitalZ
            }
            if (shown) WorthKnowing(s.signal, today, median, spread, z) else null
        }.sortedByDescending { abs(it.z) }.take(config.maxItems)

    /**
     * The weekly note, Sunday 20:00. Each change is this week minus last week over s,
     * the SD of week-to-week change across the last 12 weeks (half the daily SD until
     * there are 6). The two largest past 0.5 fill neutral templates; none reads "A
     * steady week."
     */
    fun weeklyNote(candidates: List<WeeklyCandidate>, config: AttentionConfig): List<WeeklyChange> =
        candidates.mapNotNull { c ->
            if (c.weeks.size < 2) return@mapNotNull null
            val now = c.weeks.last() ?: return@mapNotNull null
            val before = c.weeks[c.weeks.size - 2] ?: return@mapNotNull null
            val history = c.weeks.dropLast(1).zipWithNext { a, b -> if (a != null && b != null) b - a else null }
                .filterNotNull()
                .takeLast(config.weeklyHistoryWeeks)
            val s = (if (history.size >= config.weeklyMinWeeks) Stats.sd(history) else c.dailySd?.times(0.5))
                ?.takeIf { it > 1e-9 } ?: return@mapNotNull null
            val change = now - before
            WeeklyChange(c.item, change, change / s).takeIf { abs(it.z) > config.weeklyZ }
        }.sortedByDescending { abs(it.z) }.take(config.weeklyItems)

    /** Daily values, oldest first and ending today, to weekly figures ending today. */
    fun weeks(daily: List<Double?>, aggregate: (List<Double>) -> Double?): List<Double?> =
        daily.reversed().chunked(7).reversed().map { week -> week.filterNotNull().takeIf { it.isNotEmpty() }?.let(aggregate) }
}
