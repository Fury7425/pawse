package app.pawse.feature.home

import app.pawse.scoring.model.BodyBand
import app.pawse.scoring.model.BodyReason
import app.pawse.scoring.model.BodyStatus
import app.pawse.scoring.model.Confidence
import app.pawse.scoring.model.DayTag
import app.pawse.scoring.model.FormBand
import app.pawse.scoring.model.Guidance
import app.pawse.scoring.model.Insight
import app.pawse.scoring.model.LoadMethod
import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.Predictor
import app.pawse.scoring.model.Provenance
import app.pawse.scoring.model.ReasonKind
import app.pawse.scoring.model.ScoreType
import app.pawse.scoring.model.WeeklyPattern
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Every word and every formatted number the Today and explain screens show.
 *
 * Rules against score anxiety run through all of it:
 *  1. **Band word first.** The exact number can be hidden in settings.
 *  2. **"Below your normal", never "poor" or "bad".**
 *  3. **Nothing here is a diagnosis.** The illness card names likely causes, never a disease.
 *  4. **No streaks, badges or "perfect sleep" goals.**
 */
object ScoreCopy {

    fun name(type: ScoreType): String = when (type) {
        ScoreType.BODY -> "Body"
        ScoreType.SLEEP -> "Sleep"
        ScoreType.LOAD -> "Load"
    }

    fun guidance(g: Guidance): String = when (g) {
        Guidance.WATCH_SYMPTOMS -> "Take it easy today and watch for symptoms."
        Guidance.REST_DAY -> "An easy or rest day will pay off."
        Guidance.KEEP_LIGHT -> "Keep it light; tonight’s sleep is your best lever."
        Guidance.GO_MODERATE -> "Body’s fine but fatigue is building; go moderate."
        Guidance.GOOD_DAY -> "Good day for hard training or a big study block."
        Guidance.SETTLING -> "Still learning your normal. Here is what last night says."
    }

    fun bandWord(band: BodyBand): String = when (band) {
        BodyBand.ABOVE_NORMAL -> "Above normal"
        BodyBand.TYPICAL -> "Typical"
        BodyBand.BELOW_NORMAL -> "Below your normal"
        BodyBand.STRAINED -> "Clearly off your normal"
    }

    fun bandMeaning(band: BodyBand): String = when (band) {
        BodyBand.ABOVE_NORMAL -> "Fresher than usual."
        BodyBand.TYPICAL -> "Inside your normal range."
        BodyBand.BELOW_NORMAL -> "Something is costing you."
        BodyBand.STRAINED -> "Clearly off your normal."
    }

    fun confidence(c: Confidence): String = when (c) {
        Confidence.HIGH -> "High confidence"
        Confidence.MEDIUM -> "Medium confidence"
        Confidence.LOW -> "Low confidence: missed or patchy nights"
    }

    fun bodyStatus(status: BodyStatus, validNights: Int, needed: Int): String = when (status) {
        BodyStatus.READY -> ""
        BodyStatus.WARMING_UP -> if (validNights < needed) {
            "Night ${validNights + 1} of $needed. Body compares each night with your own normal, " +
                "and a reading built on fewer nights would be a guess."
        } else {
            "Learning the normal for this watch. A new device starts its own baseline, so numbers are never mixed."
        }
        BodyStatus.NO_RECENT_NIGHT -> "No night recorded in the last two days, so there is no Body reading for today."
    }

    /** "sleeping HR 4 bpm above normal", "2 drinks logged". */
    fun reason(r: BodyReason): String {
        val a = r.amount
        return when (r.kind) {
            ReasonKind.SLEEPING_HR_ABOVE -> "sleeping HR ${a.roundToInt()} bpm above normal"
            ReasonKind.SLEEPING_HR_BELOW -> "sleeping HR ${a.roundToInt()} bpm below normal"
            ReasonKind.HRV_BELOW -> "HRV ${a.roundToInt()}% below normal"
            ReasonKind.HRV_ABOVE -> "HRV ${a.roundToInt()}% above normal"
            ReasonKind.ILLNESS_WATCH -> "overnight heart rate high for several nights"
            ReasonKind.SICK_TAG -> "you tagged feeling sick"
            ReasonKind.DRINKS -> "${drinks(a)} logged"
            ReasonKind.SHORT_NIGHT -> "${formatHours(a)} short of your need last night"
            ReasonKind.SLEEP_DEBT -> "${formatHours(a)} of sleep debt"
            ReasonKind.HARD_TRAINING -> "yesterday’s training was ${one(a)}× your usual"
            ReasonKind.LATE_MEAL -> "${a.roundToInt()} kcal within 3 h of bed"
            ReasonKind.CAFFEINE_AT_BED -> "about ${a.roundToInt()} mg of caffeine left at bedtime"
            ReasonKind.TRAVEL_TAG -> "you tagged travel"
            ReasonKind.STRESS_TAG -> "you tagged an exam or stress week"
            ReasonKind.CYCLE -> "part of this may be your cycle"
        }
    }

    fun reasons(list: List<BodyReason>): String =
        list.joinToString("; ") { reason(it) }.replaceFirstChar { it.uppercase(Locale.getDefault()) }

    fun weekly(p: WeeklyPattern): String = when (p) {
        WeeklyPattern.STEADY -> "Steady week"
        WeeklyPattern.ADAPTING -> "Adapting well"
        WeeklyPattern.STRAINED -> "Strained week: stress, short sleep, illness or a hard block"
        WeeklyPattern.POSSIBLE_OVERREACHING -> "HRV drifting down under heavy load: check how you feel"
        WeeklyPattern.MIXED -> "Mixed week"
    }

    fun form(f: FormBand): String = when (f) {
        FormBand.FRESH -> "Fresh"
        FormBand.PRODUCTIVE -> "Productive training"
        FormBand.HEAVY -> "Heavy"
    }

    /** "You're 3 h 10 m behind", or nothing to catch up. */
    fun debt(hours: Double?): String = when {
        hours == null -> "Not enough nights yet"
        hours < 0.25 -> "No sleep debt"
        else -> "${formatHours(hours)} behind"
    }

    /** Sleep Regularity Index in a word; the UK Biobank median was 81. */
    fun regularity(sri: Double?): String = when {
        sri == null -> "Regularity needs a week of nights"
        sri >= 81 -> "Regular (${sri.roundToInt()})"
        sri >= 60 -> "Fairly regular (${sri.roundToInt()})"
        else -> "Irregular (${sri.roundToInt()})"
    }

    const val ILLNESS_ALERT =
        "Your overnight heart rate has been above your normal for several nights. " +
            "This often happens with a cold, stress, alcohol or travel."

    const val ILLNESS_WATCH = "Overnight heart rate has run a little high for a few nights. Pawse is keeping an eye on it."

    const val DOCTOR =
        "Your sleep debt has stayed high for two weeks, or you said you are having trouble sleeping. " +
            "Talking to a doctor will help more than more tracking; CBT-I is the first-line treatment for insomnia."

    const val FOOTER =
        "Wellness information, not a diagnosis. Pawse doesn’t account for medical conditions or medication."

    const val FORECAST_NOTE =
        "A forecast from the two-process model of sleep: it models typical physiology rather than measuring yours."

    fun likelyCauses(causes: List<ReasonKind>): String? {
        val named = causes.mapNotNull {
            when (it) {
                ReasonKind.DRINKS -> "drinks you logged"
                ReasonKind.TRAVEL_TAG -> "travel"
                ReasonKind.STRESS_TAG -> "a stress week"
                ReasonKind.SICK_TAG -> "feeling sick, as you tagged"
                else -> null
            }
        }
        return if (named.isEmpty()) null else "Likely linked to " + named.joinToString(" and ") + "."
    }

    fun tag(t: DayTag): String = when (t) {
        DayTag.SICK -> "Sick"
        DayTag.TRAVEL -> "Travel"
        DayTag.STRESS -> "Exam or stress"
        DayTag.TROUBLE_SLEEPING -> "Trouble sleeping"
        DayTag.OTHER -> "Other"
    }

    /**
     * Check-in mapped onto Body's four bands, 0 = strained .. 3 = above normal.
     * Two or more apart, both are shown as information and neither wins.
     */
    fun disagrees(feeling: Int, band: BodyBand): Boolean {
        val felt = when (feeling) {
            1 -> 0
            2 -> 1
            5 -> 3
            else -> 2
        }
        val body = when (band) {
            BodyBand.STRAINED -> 0
            BodyBand.BELOW_NORMAL -> 1
            BodyBand.TYPICAL -> 2
            BodyBand.ABOVE_NORMAL -> 3
        }
        return abs(felt - body) >= 2
    }

    const val DISAGREE =
        "How you feel and what your body shows disagree today. Both are information; neither is the winner."

    /** "After evenings with a drink, your Body signal ran 0.4 SD lower (0.2 to 0.6), seen 11 times." */
    fun insight(i: Insight): String {
        val lead = when (i.predictor) {
            Predictor.TRAINING -> "After a typical training day"
            Predictor.SLEEP_DEBT -> "For each hour of sleep debt"
            Predictor.ALCOHOL -> "For each drink the evening before"
            Predictor.CAFFEINE -> "For each 100 mg of caffeine left at bedtime"
            Predictor.LATE_MEAL -> "For each 500 kcal within 3 h of bed"
        }
        val dir = if (i.effect < 0) "lower" else "higher"
        val (lo, hi) = listOf(abs(i.low), abs(i.high)).sorted()
        return "$lead, your Body signal the next morning ran ${one(abs(i.effect))} SD $dir " +
            "(${one(lo)} to ${one(hi)}), seen on ${i.exposureDays} days."
    }

    const val INSIGHT_NOTE = "Associations in your own data, not proof of cause."

    fun loadMethod(m: LoadMethod?): String = when (m) {
        LoadMethod.BANISTER -> "Heart rate (Banister TRIMP)"
        LoadMethod.EDWARDS -> "Heart-rate zones (Edwards)"
        LoadMethod.RPE -> "Effort × minutes"
        null -> "Not counted"
    }

    fun provenanceLabel(provenance: Provenance): String = when (provenance) {
        Provenance.PUBLISHED -> "Published"
        Provenance.RECOVERED -> "Recovered"
        Provenance.INFERRED -> "Inferred"
        Provenance.OUR_CHOICE -> "Our default"
    }

    fun metricLabel(metric: Metric): String = when (metric) {
        Metric.HRV_RMSSD -> "Heart rate variability"
        Metric.RESTING_HEART_RATE -> "Resting heart rate"
        Metric.RESPIRATORY_RATE -> "Breathing rate"
        Metric.SKIN_TEMPERATURE -> "Skin temperature"
        Metric.SPO2 -> "Blood oxygen"
        Metric.HEART_RATE -> "Heart rate"
        Metric.SLEEPING_HEART_RATE -> "Sleeping heart rate"
        Metric.HR_NADIR -> "Heart-rate low point"
        Metric.NIGHT_HR_COVERAGE -> "Night coverage"
        Metric.MENSTRUATION -> "Period"
        Metric.BODY_WEIGHT -> "Weight"
        Metric.BODY_FAT -> "Body fat"
        Metric.LEAN_MASS -> "Lean mass"
        Metric.BONE_MASS -> "Bone mass"
        Metric.BODY_WATER -> "Body water"
        Metric.BODY_HEIGHT -> "Height"
        Metric.BASAL_METABOLIC_RATE -> "Basal metabolic rate"
        Metric.ACTIVE_ENERGY -> "Active energy"
    }

    /** A raw value in its own unit, rounded to the precision the sensor deserves. */
    fun formatRaw(metric: Metric, raw: Double): String = when (metric) {
        Metric.HRV_RMSSD -> "${raw.roundToInt()} ms"
        Metric.RESTING_HEART_RATE, Metric.HEART_RATE, Metric.SLEEPING_HEART_RATE -> "${raw.roundToInt()} bpm"
        Metric.RESPIRATORY_RATE -> String.format(Locale.getDefault(), "%.1f br/min", raw)
        Metric.SKIN_TEMPERATURE -> String.format(Locale.getDefault(), "%+.2f °C", raw)
        Metric.SPO2 -> String.format(Locale.getDefault(), "%.1f%%", raw)
        Metric.HR_NADIR, Metric.NIGHT_HR_COVERAGE -> "${(raw * 100).roundToInt()}%"
        Metric.MENSTRUATION -> ""
        Metric.BODY_WEIGHT, Metric.LEAN_MASS, Metric.BONE_MASS, Metric.BODY_WATER ->
            String.format(Locale.getDefault(), "%.1f kg", raw)
        Metric.BODY_FAT -> String.format(Locale.getDefault(), "%.1f%%", raw)
        Metric.BODY_HEIGHT -> String.format(Locale.getDefault(), "%.2f m", raw)
        Metric.BASAL_METABOLIC_RATE, Metric.ACTIVE_ENERGY -> "${raw.roundToInt()} kcal"
    }

    fun formatMinutes(minutes: Double): String {
        val total = minutes.roundToInt().coerceAtLeast(0)
        return "${total / 60} h ${String.format(Locale.getDefault(), "%02d", total % 60)}"
    }

    fun formatHours(hours: Double): String = formatMinutes(hours * 60.0)

    /** Minutes since midnight of the shown day as a wall clock: "21:30". Past midnight wraps. */
    fun minuteOfDay(minute: Int): String {
        val m = Math.floorMod(minute, 1440)
        return String.format(Locale.getDefault(), "%02d:%02d", m / 60, m % 60)
    }

    fun load(value: Double): String = value.roundToInt().toString()

    fun drinks(n: Double): String {
        val shown = if (n < 1.5) "1 drink" else "${n.roundToInt()} drinks"
        return shown
    }

    /** Local wall-clock time, 24-hour: "07:12". */
    fun clock(epochMs: Long): String =
        java.time.Instant.ofEpochMilli(epochMs)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))

    private fun one(v: Double): String = String.format(Locale.getDefault(), "%.1f", v)

    /** Within this many sigmas of the baseline, a value is simply usual. */
    const val NEUTRAL_Z = 0.4
}
