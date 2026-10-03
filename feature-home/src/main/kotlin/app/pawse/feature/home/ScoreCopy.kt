package app.pawse.feature.home

import app.pawse.scoring.config.StrainScale
import app.pawse.scoring.model.Band
import app.pawse.scoring.model.Contribution
import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.Provenance
import app.pawse.scoring.model.Score
import app.pawse.scoring.model.ScoreType
import app.pawse.scoring.model.ScoreUnavailable
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Every word and every formatted number the score screens show.
 *
 * Kept in one file on purpose. The app's voice is a feature, and three rules run
 * through all of it:
 *
 *  1. **Nothing here is a diagnosis.** A temperature or oxygen excursion is
 *     "outside your usual range", never a sign of anything. This is not a medical
 *     device and the copy never lets a user believe otherwise.
 *  2. **Colour is always paired with a word.** [bandWord] is the other half of
 *     every dot in the app.
 *  3. **A number never appears without its coverage.** [coverageLine] is rendered
 *     next to every score, degraded or not.
 */
object ScoreCopy {

    fun name(type: ScoreType): String = when (type) {
        ScoreType.RECOVERY -> "Recovery"
        ScoreType.SLEEP -> "Sleep"
        ScoreType.STRAIN -> "Strain"
        ScoreType.ENERGY_BANK -> "Energy Bank"
        ScoreType.LOAD_RATIO -> "Training load"
    }

    /**
     * The word beside the dot.
     *
     * Strain gets its own vocabulary because its bands are magnitude, not verdict:
     * a high strain day is a lot of load, which is neither good news nor bad. The
     * training-load ratio gets a third, because only its middle is good and both
     * tails mean something different.
     */
    fun bandWord(type: ScoreType, band: Band): String = when (type) {
        ScoreType.STRAIN -> when (band) {
            Band.LOW -> "Light"
            Band.MODERATE -> "Moderate"
            Band.HIGH -> "Hard"
        }

        ScoreType.LOAD_RATIO -> when (band) {
            Band.HIGH -> "Steady"
            Band.MODERATE -> "Uneven"
            Band.LOW -> "Ramping fast"
        }

        else -> when (band) {
            Band.LOW -> "Low"
            Band.MODERATE -> "Moderate"
            Band.HIGH -> "High"
        }
    }

    /** One plain sentence saying what the number came from. */
    fun sentence(type: ScoreType, score: Score, snapshot: HomeSnapshot): String = when (type) {
        ScoreType.RECOVERY -> recoverySentence(score)
        ScoreType.SLEEP -> sleepSentence(score, snapshot)
        ScoreType.STRAIN -> strainSentence(score, snapshot)
        ScoreType.ENERGY_BANK -> energySentence(score)
        ScoreType.LOAD_RATIO -> loadSentence(score)
    }

    private fun recoverySentence(score: Score): String {
        val driver = score.contributions
            .filter { it.present && !it.anomalyFlag && it.z != null }
            .maxByOrNull { abs(it.points) }
            ?: return "Scored from what your device recorded overnight."

        val direction = if ((driver.z ?: 0.0) >= 0) "above" else "below"
        val sigmas = String.format(Locale.getDefault(), "%.1f", abs(driver.z ?: 0.0))
        val lead = "${metricLabel(driver.metric)} came in $sigmas SD $direction your usual range"

        val flagged = score.contributions.firstOrNull { it.anomalyFlag && it.points < 0.0 }
        return if (flagged != null) {
            // Never illness, never a diagnosis. Distance from the user's own range,
            // and nothing more.
            "$lead, and your ${metricLabel(flagged.metric).lowercase(Locale.getDefault())} " +
                "was outside your usual range."
        } else {
            "$lead."
        }
    }

    private fun sleepSentence(score: Score, snapshot: HomeSnapshot): String {
        val asleep = score.contributions.firstOrNull { it.metric == Metric.TIME_ASLEEP }?.raw
        val need = snapshot.sleepNeedHours
        return when {
            asleep != null && need != null ->
                "You slept ${formatMinutes(asleep)} against a need of ${formatHours(need)}."
            asleep != null -> "You slept ${formatMinutes(asleep)}."
            else -> "Scored against tonight's target rather than against your history."
        }
    }

    private fun strainSentence(score: Score, snapshot: HomeSnapshot): String {
        val target = snapshot.strainTargetDisplay ?: return "Today's cardiovascular and muscular load."
        val value = snapshot.strainDisplay ?: score.value.toDouble()
        val delta = value - target
        val targetText = formatStrain(target, snapshot.strainScale)
        return when {
            abs(delta) <= STRAIN_NEAR_TARGET -> "About your usual for a day like this. Target was $targetText."
            delta > 0 -> "Above the $targetText your recent weeks and this morning's Recovery pointed at."
            else -> "Below the $targetText your recent weeks and this morning's Recovery pointed at."
        }
    }

    private fun energySentence(score: Score): String {
        fun points(metric: Metric) = score.contributions.firstOrNull { it.metric == metric }?.points
        val carryover = points(Metric.CARRYOVER)?.roundToInt()
        val recharge = points(Metric.OVERNIGHT_RECHARGE)?.roundToInt()
        val drain = points(Metric.STRAIN_DRAIN)?.roundToInt()
        return if (carryover != null && recharge != null && drain != null) {
            "Started at $carryover, the night put back $recharge, the day spent ${abs(drain)}."
        } else {
            "Yesterday's leftover energy, plus the night, minus the day."
        }
    }

    private fun loadSentence(score: Score): String {
        val ratio = String.format(Locale.getDefault(), "%.2f", score.value / 100.0)
        return "This week's training load is $ratio times the month behind it."
    }

    /** Shown under every score, always, degraded or not. */
    fun coverageLine(score: Score): String {
        val present = score.contributions.count { it.present && !it.anomalyFlag }
        val total = score.contributions.count { !it.anomalyFlag }
        val percent = (score.dataCoverage * 100).roundToInt()
        return "$present of $total inputs · $percent% of the weight"
    }

    /** Qualifiers shown as chips beside a score. */
    fun flags(score: Score): List<String> = buildList {
        if (score.warmingUp) add("Still settling")
        if (score.degraded && !score.warmingUp) add("Lower confidence")
    }

    /**
     * The empty state.
     *
     * "Refuse rather than guess" only works if the refusal explains itself, so each
     * reason gets its own sentence rather than a shared apology.
     */
    fun unavailableSentence(type: ScoreType, unavailable: ScoreUnavailable): String =
        when (unavailable.reason) {
            ScoreUnavailable.Reason.NO_SLEEP_SESSION ->
                "No sleep session for this night, so there is nothing to score."

            ScoreUnavailable.Reason.NO_REQUIRED_INPUT -> when (type) {
                ScoreType.STRAIN ->
                    "No workouts and no waking hours to score. Nothing to measure yet."
                ScoreType.LOAD_RATIO ->
                    "No training history to compare this week against."
                else ->
                    "Your device did not write the inputs this needs: " +
                        unavailable.missing.joinToString { metricLabel(it).lowercase(Locale.getDefault()) } + "."
            }

            ScoreUnavailable.Reason.BASELINE_WARMUP ->
                "Still learning your usual range. This needs about two weeks of nights " +
                    "before it means anything, and we would rather show nothing than a " +
                    "number built on three."

            ScoreUnavailable.Reason.COVERAGE_TOO_LOW -> when (type) {
                ScoreType.STRAIN ->
                    "A workout today could not be scored — usually a missing heart rate or " +
                        "a missing age — so reporting a rest day would be wrong."
                else ->
                    "Too much of tonight is missing to score honestly. Missing: " +
                        unavailable.missing.joinToString { metricLabel(it).lowercase(Locale.getDefault()) } + "."
            }
        }

    fun metricLabel(metric: Metric): String = when (metric) {
        Metric.HRV_RMSSD -> "Heart rate variability"
        Metric.RESTING_HEART_RATE -> "Resting heart rate"
        Metric.SLEEP_SCORE -> "Last night's sleep"
        Metric.RESPIRATORY_RATE -> "Breathing rate"
        Metric.SKIN_TEMPERATURE -> "Skin temperature"
        Metric.SPO2 -> "Blood oxygen"
        Metric.TIME_ASLEEP -> "Time asleep"
        Metric.SLEEP_EFFICIENCY -> "Sleep efficiency"
        Metric.SLEEP_LATENCY -> "Time to fall asleep"
        Metric.REM_MINUTES -> "REM sleep"
        Metric.DEEP_MINUTES -> "Deep sleep"
        Metric.RESTFULNESS -> "Restfulness"
        Metric.SLEEP_TIMING -> "Sleep timing"
        Metric.BEDTIME_CONSISTENCY -> "Bedtime consistency"
        Metric.SLEEP_INTERRUPTIONS -> "Interruptions"
        Metric.HEART_RATE_DIP -> "Overnight heart-rate dip"
        Metric.HEART_RATE -> "Heart rate"
        Metric.WORKOUT_LOAD -> "Workouts"
        Metric.PASSIVE_LOAD -> "Rest of the day"
        Metric.CARRYOVER -> "Yesterday's leftover"
        Metric.OVERNIGHT_RECHARGE -> "Overnight recharge"
        Metric.STRAIN_DRAIN -> "Spent today"
        Metric.NAP_CREDIT -> "Naps"
        Metric.ACUTE_LOAD -> "This week"
        Metric.CHRONIC_LOAD -> "Last four weeks"
    }

    /** A raw value in its own unit, rounded to the precision the sensor deserves. */
    fun formatRaw(metric: Metric, raw: Double): String = when (metric) {
        Metric.TIME_ASLEEP -> formatMinutes(raw)
        Metric.HRV_RMSSD -> "${raw.roundToInt()} ms"
        Metric.RESTING_HEART_RATE, Metric.HEART_RATE -> "${raw.roundToInt()} bpm"
        Metric.RESPIRATORY_RATE -> String.format(Locale.getDefault(), "%.1f br/min", raw)
        // Health Connect delivers skin temperature as a signed delta from the
        // device's own baseline, so it is shown as one.
        Metric.SKIN_TEMPERATURE -> String.format(Locale.getDefault(), "%+.2f °C", raw)
        Metric.SPO2, Metric.SLEEP_EFFICIENCY, Metric.HEART_RATE_DIP ->
            String.format(Locale.getDefault(), "%.1f%%", raw)
        Metric.SLEEP_LATENCY, Metric.REM_MINUTES, Metric.DEEP_MINUTES -> "${raw.roundToInt()} min"
        Metric.SLEEP_INTERRUPTIONS -> raw.roundToInt().toString()
        Metric.RESTFULNESS, Metric.SLEEP_TIMING, Metric.BEDTIME_CONSISTENCY, Metric.SLEEP_SCORE ->
            "${raw.roundToInt()} / 100"
        Metric.WORKOUT_LOAD, Metric.PASSIVE_LOAD, Metric.ACUTE_LOAD, Metric.CHRONIC_LOAD ->
            "${raw.roundToInt()} TRIMP"
        Metric.CARRYOVER, Metric.OVERNIGHT_RECHARGE, Metric.STRAIN_DRAIN -> "${raw.roundToInt()} pts"
        Metric.NAP_CREDIT -> "${raw.roundToInt()} min"
    }

    /** "Usually 52 ± 6 ms over 43 nights", or why there is no such line. */
    fun baselineText(contribution: Contribution): String {
        val mean = contribution.baselineMean
        val sd = contribution.baselineSd
        if (mean == null || sd == null) {
            return "Scored against a target, not against your history"
        }
        val meanText = formatRaw(contribution.metric, mean)
        val sdText = formatRaw(contribution.metric, sd).replace("+", "")
        return "Usually $meanText ± $sdText over ${contribution.baselineSampleCount} nights"
    }

    /** The deviation, in words. The band alone never has to carry it. */
    fun deltaText(contribution: Contribution): String {
        if (!contribution.present) return "Not recorded"
        val z = contribution.z ?: return "No baseline yet"
        val sigmas = String.format(Locale.getDefault(), "%.1f", abs(z))
        return when {
            abs(z) < NEUTRAL_Z -> "About your usual"
            z > 0 -> "$sigmas SD better than usual"
            else -> "$sigmas SD worse than usual"
        }
    }

    fun pointsText(contribution: Contribution): String? {
        if (!contribution.present) return null
        val rounded = contribution.points.roundToInt()
        if (rounded == 0 && abs(contribution.points) < 0.5) return "0 pts"
        return "${if (rounded > 0) "+" else ""}$rounded pts"
    }

    fun weightText(contribution: Contribution): String {
        if (contribution.anomalyFlag) return "Flag, not a weighted term"
        val effective = (contribution.weight * 100).roundToInt()
        val nominal = (contribution.nominalWeight * 100).roundToInt()
        return if (contribution.present && effective != nominal) {
            "Weight $effective% (normally $nominal%, raised to cover a missing input)"
        } else {
            "Weight $nominal%"
        }
    }

    fun provenanceLabel(provenance: Provenance): String = when (provenance) {
        Provenance.PUBLISHED -> "Published"
        Provenance.RECOVERED -> "Recovered"
        Provenance.INFERRED -> "Inferred"
        Provenance.OUR_CHOICE -> "Our default"
    }

    /**
     * How a score's terms relate to its total. This differs per score and saying so
     * is the whole reason the explainability screen is trustworthy.
     */
    fun arithmeticNote(type: ScoreType): String = when (type) {
        ScoreType.RECOVERY ->
            "Recovery squashes a weighted sum of deviations through a logistic curve, " +
                "which is not a sum. Each figure below is that term's own effect — how far " +
                "the score moves if it alone is removed — so they deliberately do not add " +
                "up to the total."

        ScoreType.SLEEP ->
            "Sleep is a weighted sum of sub-scores, each 0-100, so these figures do add up " +
                "to the total exactly. Each contributor is scored against a physiological " +
                "target rather than against your own history, which is how both Oura and " +
                "Apple work and why there is no baseline range on these rows."

        ScoreType.STRAIN ->
            "Load is converted to strain through a saturating curve, so two hard sessions " +
                "do not add to twice one. Each figure is how much of today's strain that " +
                "part accounts for at the margin."

        ScoreType.ENERGY_BANK ->
            "These are exact. They are the realised change in the gauge at each step, after " +
                "the resistance near the top and bottom of the scale, so yesterday's leftover " +
                "plus the three below is today's closing level."

        ScoreType.LOAD_RATIO ->
            "A ratio has no additive parts, so there are no points here — only the two " +
                "averages it divides."
    }

    fun formatMinutes(minutes: Double): String {
        val total = minutes.roundToInt().coerceAtLeast(0)
        return "${total / 60} h ${String.format(Locale.getDefault(), "%02d", total % 60)}"
    }

    fun formatHours(hours: Double): String = formatMinutes(hours * 60.0)

    /**
     * Strain, on whichever scale the user is reading.
     *
     * Whoop's ceiling is 21 and Bevel's is 100: the same curve with a different
     * label. The stored value is always the canonical 0-100, so switching scales
     * relabels a number and never reshapes a history.
     */
    fun formatStrain(value: Double, scale: StrainScale): String = when (scale) {
        StrainScale.WHOOP_21 -> String.format(Locale.getDefault(), "%.1f / 21", value)
        StrainScale.BEVEL_100 -> value.roundToInt().toString()
    }

    /** Within this many sigmas of the baseline, a night is simply usual. */
    const val NEUTRAL_Z = 0.4

    /** Within this much of target strain, today was about normal. */
    private const val STRAIN_NEAR_TARGET = 8.0
}
