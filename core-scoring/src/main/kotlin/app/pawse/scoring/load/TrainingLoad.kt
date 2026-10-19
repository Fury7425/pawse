package app.pawse.scoring.load

import app.pawse.scoring.config.LoadConfig
import app.pawse.scoring.model.FormBand
import app.pawse.scoring.model.LoadMethod
import app.pawse.scoring.model.Provenance
import app.pawse.scoring.model.SessionLoad
import app.pawse.scoring.model.UserProfile
import app.pawse.scoring.model.WorkoutSample
import kotlin.math.roundToInt

/**
 * Load: per-session TRIMP, then fitness and fatigue as a slow and a fast moving
 * average, and form as the gap between them as a share of fitness, so it means
 * the same for a beginner and an athlete.
 */
object TrainingLoad {

    /**
     * Cardio uses Banister's TRIMP above 30% of heart-rate reserve. A session with
     * no usable heart rate uses session RPE x minutes, calibrated so an RPE-5
     * minute matches the user's median cardio minute.
     *
     * ponytail: TRIMP runs on the session's mean heart rate, so "minutes above 30%
     * HRR" is all-or-nothing per session. Summing per sample needs the series the
     * enricher already reads; store zone minutes there if easy sessions matter.
     *
     * ponytail: Health Connect has no RPE field, so RPE is always the default 5.
     */
    fun sessions(
        workouts: List<WorkoutSample>,
        profile: UserProfile,
        restingHeartRate: Double?,
        loadPerRpeMinute: Double,
        config: LoadConfig,
    ): List<SessionLoad> {
        val observedMax = workouts.mapNotNull { it.maxHeartRate }.maxOrNull()
        val maxHr = HeartRateModel.maxHeartRate(profile, config, observedMax)
        val rest = profile.restingHeartRate ?: restingHeartRate

        return workouts.map { w ->
            fun session(load: Double, method: LoadMethod?, x: Double?, provenance: Provenance, citation: String, skipped: String? = null) =
                SessionLoad(w.id, w.title, w.durationMinutes, x, load, method, provenance, citation, skipped)

            if (w.durationMinutes < config.minSessionMinutes) {
                return@map session(0.0, null, null, Provenance.OUR_CHOICE, "", "Shorter than ${config.minSessionMinutes.roundToInt()} minutes.")
            }
            val x = if (w.meanHeartRate != null && rest != null && maxHr != null) {
                HeartRateModel.reserveFraction(w.meanHeartRate, rest, maxHr.bpm)
            } else {
                null
            }
            when {
                x != null && x < config.minReserveFraction -> session(
                    0.0, LoadMethod.BANISTER, x, Provenance.PUBLISHED, "",
                    "Under ${(config.minReserveFraction * 100).roundToInt()}% of your heart-rate reserve, which does not count as training.",
                )
                x != null -> {
                    val r = Trimp.banister(w.durationMinutes, x, profile.biologicalSex, config)
                    val estimated = maxHr!!.estimated
                    session(
                        r.load, LoadMethod.BANISTER, x,
                        if (estimated && r.provenance == Provenance.PUBLISHED) Provenance.INFERRED else r.provenance,
                        if (estimated) "${r.citation} ${maxHr.citation}" else r.citation,
                    )
                }
                w.zoneMinutes.isNotEmpty() -> {
                    val r = Trimp.edwards(w.zoneMinutes, config)
                    session(r.load, LoadMethod.EDWARDS, null, r.provenance, r.citation)
                }
                else -> session(
                    w.durationMinutes * config.defaultRpe * loadPerRpeMinute,
                    LoadMethod.RPE,
                    null,
                    Provenance.OUR_CHOICE,
                    "No usable heart rate, so session effort x minutes, effort ${config.defaultRpe.roundToInt()} of 10 " +
                        "assumed and matched to your typical cardio minute.",
                )
            }
        }
    }

    /**
     * Fit_t = Fit_t-1 + (L_t - Fit_t-1) / 42
     * Fat_t = Fat_t-1 + (L_t - Fat_t-1) / 7
     *
     * Both start at the mean of the first two weeks rather than at zero, so a new
     * user is not told they are wildly fresh for six weeks.
     */
    fun fitnessFatigue(loads: List<Double>, config: LoadConfig): List<Pair<Double, Double>> {
        if (loads.isEmpty()) return emptyList()
        val seed = loads.take(config.seedDays).average()
        var fit = seed
        var fat = seed
        return loads.map { l ->
            fit += (l - fit) / config.fitnessDays
            fat += (l - fat) / config.fatigueDays
            fit to fat
        }
    }

    /** (Fit - Fat) / Fit. Null without any fitness to divide by. */
    fun form(fit: Double, fat: Double): Double? = if (fit > 1e-6) (fit - fat) / fit else null

    fun formBand(form: Double, config: LoadConfig): FormBand = when {
        form > config.freshAbove -> FormBand.FRESH
        form >= config.heavyBelow -> FormBand.PRODUCTIVE
        else -> FormBand.HEAVY
    }

    /**
     * Today's suggested range: L~ (0.5 + Body/100) x [0.75, 1.25].
     * Body 50 is roughly a usual day; Body 80 about 30% more; Body 15 about two thirds.
     */
    fun range(typicalDayLoad: Double, body: Int?, config: LoadConfig): Pair<Double, Double> {
        val scale = 0.5 + (body ?: 50) / 100.0
        return typicalDayLoad * scale * config.rangeLow to typicalDayLoad * scale * config.rangeHigh
    }
}
