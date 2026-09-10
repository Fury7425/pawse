package app.pawse.core.data.scoring

import app.pawse.scoring.baseline.BaselineEngine
import app.pawse.scoring.config.ScoringConfig
import app.pawse.scoring.config.StrainScale
import app.pawse.scoring.energy.EnergyBankInputs
import app.pawse.scoring.energy.EnergyBankScorer
import app.pawse.scoring.load.LoadRatioScorer
import app.pawse.scoring.model.DailyInputs
import app.pawse.scoring.model.DailyLoad
import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.MetricHistory
import app.pawse.scoring.model.ScoringOutcome
import app.pawse.scoring.model.UserProfile
import app.pawse.scoring.recovery.RecoveryScorer
import app.pawse.scoring.sleep.SleepContext
import app.pawse.scoring.sleep.SleepNeed
import app.pawse.scoring.sleep.SleepScorer
import app.pawse.scoring.strain.SessionLoad
import app.pawse.scoring.strain.StrainScorer
import app.pawse.scoring.strain.StrainTarget
import app.pawse.scoring.strain.Trimp
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Everything one day produced, scored and unscored alike. */
data class DayScores(
    val date: LocalDate,
    val recovery: ScoringOutcome,
    val sleep: ScoringOutcome,
    val strain: ScoringOutcome,
    val energyBank: ScoringOutcome,
    val loadRatio: ScoringOutcome,
    /** Tonight's sleep need, decomposed. Shown next to the Sleep score. */
    val sleepNeed: SleepNeed,
    /** Target strain for the day, and what moved it. */
    val strainTarget: StrainTarget,
    /** Per-session breakdown, derived rather than stored. */
    val sessionLoads: List<SessionLoad>,
    /** The day's total TRIMP, which is what the acute:chronic ratio is built from. */
    val dayLoad: Double,
)

/**
 * Runs the whole engine over a window of days.
 *
 * The order below is not stylistic — it is the dependency graph, and getting it
 * wrong produces numbers that look plausible and are wrong:
 *
 *  1. **Strain first.** It depends on nothing else, and tonight's sleep need
 *     depends on today's strain (Whoop's published logistic, patent US 9,538,923).
 *  2. **Sleep second**, with yesterday's strain and the running sleep debt.
 *  3. **Recovery third**, because last night's Sleep Score is one of its four
 *     weighted terms, and its baseline needs a *history* of sleep scores — which
 *     only exists once every night in the window has been scored.
 *  4. **Energy Bank fourth**, chained day by day. It is a running integral, so it
 *     has to be walked forward from the oldest day; starting it in the middle
 *     would seed it from the config default and report a warming-up gauge forever.
 *  5. **Load ratio last**, from the daily loads step 1 produced.
 *
 * Everything is recomputed from stored samples rather than read back from the
 * score table. That is deliberate: the score rows are a record of what was shown,
 * carrying the config hash that produced them, and the pipeline is the thing that
 * decides what is true now. Recomputing 90 days costs a few milliseconds.
 */
class ScoringPipeline(
    private val config: ScoringConfig = ScoringConfig.DEFAULT,
) {

    private val baselineEngine = BaselineEngine(config.baseline)
    private val recoveryScorer = RecoveryScorer(config, baselineEngine)
    private val sleepScorer = SleepScorer(config)
    private val strainScorer = StrainScorer(config)
    private val energyBankScorer = EnergyBankScorer(config)
    private val loadRatioScorer = LoadRatioScorer(config)

    /** Trailing nights whose shortfall still counts as debt owed tonight. */
    private val debtWindowNights = 7

    fun run(bundles: List<DayBundle>, profile: UserProfile): List<DayScores> {
        if (bundles.isEmpty()) return emptyList()
        val ordered = bundles.sortedBy { it.date }

        val series: MutableMap<Metric, MutableMap<LocalDate, Double>> = mutableMapOf()
        for (bundle in ordered) {
            for ((metric, value) in bundle.values) {
                series.getOrPut(metric) { mutableMapOf() }[bundle.date] = value
            }
        }

        // --- 1. Strain ---------------------------------------------------------
        val strainOutcomes = mutableMapOf<LocalDate, ScoringOutcome>()
        val sessionLoads = mutableMapOf<LocalDate, List<SessionLoad>>()
        val dayLoads = mutableMapOf<LocalDate, Double>()
        for (bundle in ordered) {
            val restingBaseline = baselineEngine
                .baseline(historyOf(Metric.RESTING_HEART_RATE, bundle.date, series))
                ?.takeIf { !it.warmingUp }
                ?.mean
            strainOutcomes[bundle.date] = strainScorer.score(bundle.activity, profile, restingBaseline)
            sessionLoads[bundle.date] = strainScorer.sessionLoads(bundle.activity, profile, restingBaseline)
            dayLoads[bundle.date] = strainScorer.dayLoad(bundle.activity, profile, restingBaseline)
        }

        // --- 2. Sleep ----------------------------------------------------------
        val sleepOutcomes = mutableMapOf<LocalDate, ScoringOutcome>()
        val sleepNeeds = mutableMapOf<LocalDate, SleepNeed>()
        val deficitHours = mutableMapOf<LocalDate, Double>()
        for (bundle in ordered) {
            val yesterdayStrain = strainOutcomes[bundle.date.minusDays(1)]?.scoreOrNull?.value
            val context = SleepContext(
                // A learned personal rest-day need is step 6 work. Until then the
                // engine's AASM midpoint stands in, and the adders do the moving.
                personalBaselineNeedHours = null,
                strainWhoop21 = yesterdayStrain?.let { it * 21.0 / Trimp.CANONICAL_MAX } ?: 0.0,
                sleepDebtHours = (1..debtWindowNights)
                    .mapNotNull { back -> deficitHours[bundle.date.minusDays(back.toLong())] }
                    .sum(),
            )
            val inputs = inputsOf(bundle)
            val need = sleepScorer.need(inputs, context)
            sleepNeeds[bundle.date] = need
            sleepOutcomes[bundle.date] = sleepScorer.score(inputs, context)
            deficitHours[bundle.date] = if (bundle.night.hasSession) {
                ((need.totalMinutes - bundle.night.asleepMinutes) / 60.0).coerceAtLeast(0.0)
            } else {
                // No session is no evidence. A night the watch missed is not a
                // night the user did not sleep, and charging debt for it would
                // inflate every sleep need for the following week.
                0.0
            }
        }

        for (bundle in ordered) {
            sleepOutcomes[bundle.date]?.scoreOrNull?.let { score ->
                series.getOrPut(Metric.SLEEP_SCORE) { mutableMapOf() }[bundle.date] = score.value.toDouble()
            }
        }

        // --- 3. Recovery -------------------------------------------------------
        val recoveryOutcomes = mutableMapOf<LocalDate, ScoringOutcome>()
        for (bundle in ordered) {
            val values = bundle.values.toMutableMap()
            sleepOutcomes[bundle.date]?.scoreOrNull?.let { values[Metric.SLEEP_SCORE] = it.value.toDouble() }
            val inputs = inputsOf(bundle, values)
            val histories = config.recovery.weights.keys
                .plus(listOf(Metric.SKIN_TEMPERATURE, Metric.SPO2))
                .associateWith { metric -> historyOf(metric, bundle.date, series) }
            recoveryOutcomes[bundle.date] = recoveryScorer.score(inputs, histories)
        }

        // --- 4. Energy Bank ----------------------------------------------------
        val energyOutcomes = mutableMapOf<LocalDate, ScoringOutcome>()
        var previousLevel: Double? = null
        var previousDate: LocalDate? = null
        for (bundle in ordered) {
            // A gap in the data breaks the chain rather than carrying a fortnight-old
            // level forward as though nothing had happened in between.
            val carryover = if (previousDate == bundle.date.minusDays(1)) previousLevel else null
            val outcome = energyBankScorer.score(
                EnergyBankInputs(
                    date = bundle.date.toString(),
                    previousLevel = carryover,
                    recoveryScore = recoveryOutcomes[bundle.date]?.scoreOrNull?.value,
                    sleepScore = sleepOutcomes[bundle.date]?.scoreOrNull?.value,
                    dayStrain = strainOutcomes[bundle.date]?.scoreOrNull?.value,
                    napMinutes = bundle.night.napMinutes,
                ),
            )
            energyOutcomes[bundle.date] = outcome
            outcome.scoreOrNull?.let {
                previousLevel = it.value.toDouble()
                previousDate = bundle.date
            }
        }

        // --- 5. Load ratio -----------------------------------------------------
        val loadOutcomes = mutableMapOf<LocalDate, ScoringOutcome>()
        for (bundle in ordered) {
            val loads = dayLoads
                .filterKeys { !it.isAfter(bundle.date) }
                .map { (date, load) -> DailyLoad(daysAgo = daysBetween(date, bundle.date), load = load) }
            loadOutcomes[bundle.date] = loadRatioScorer.score(bundle.date.toString(), loads)
        }

        return ordered.map { bundle ->
            val recentStrains = (1..config.strain.targetStrainWindowDays)
                .mapNotNull { back ->
                    strainOutcomes[bundle.date.minusDays(back.toLong())]?.scoreOrNull?.value?.toDouble()
                }
            DayScores(
                date = bundle.date,
                recovery = recoveryOutcomes.getValue(bundle.date),
                sleep = sleepOutcomes.getValue(bundle.date),
                strain = strainOutcomes.getValue(bundle.date),
                energyBank = energyOutcomes.getValue(bundle.date),
                loadRatio = loadOutcomes.getValue(bundle.date),
                sleepNeed = sleepNeeds.getValue(bundle.date),
                strainTarget = strainScorer.target(
                    recentStrains = recentStrains,
                    recoveryScore = recoveryOutcomes[bundle.date]?.scoreOrNull?.value,
                ),
                sessionLoads = sessionLoads[bundle.date].orEmpty(),
                dayLoad = dayLoads.getValue(bundle.date),
            )
        }
    }

    /** Canonical 0-100 strain converted to whichever scale the user is reading. */
    fun strainDisplayValue(canonical: Int, scale: StrainScale = config.strain.scale): Double =
        strainScorer.displayValue(canonical, scale)

    private fun inputsOf(bundle: DayBundle, values: Map<Metric, Double> = bundle.values): DailyInputs =
        DailyInputs(
            date = bundle.date.toString(),
            values = values,
            hasSleepStages = bundle.night.hasStages,
            hasSleepSession = bundle.night.hasSession,
            timezoneShiftMinutes = bundle.night.timezoneShiftMinutes,
            napMinutes = bundle.night.napMinutes,
        )

    private fun historyOf(
        metric: Metric,
        target: LocalDate,
        series: Map<Metric, Map<LocalDate, Double>>,
    ): MetricHistory = MetricHistory(
        metric = metric,
        samples = series[metric].orEmpty()
            .filterKeys { !it.isAfter(target) }
            .map { (date, value) -> MetricHistory.Sample(daysBetween(date, target), value) }
            .sortedByDescending { it.daysAgo },
    )

    private fun daysBetween(from: LocalDate, to: LocalDate): Int =
        ChronoUnit.DAYS.between(from, to).toInt()
}
