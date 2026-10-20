package app.pawse.scoring

import app.pawse.scoring.body.BodyMath
import app.pawse.scoring.body.SignalFilter
import app.pawse.scoring.body.SignalState
import app.pawse.scoring.config.ScoringConfig
import app.pawse.scoring.context.Cycle
import app.pawse.scoring.context.IllnessDetector
import app.pawse.scoring.energy.EnergyForecastModel
import app.pawse.scoring.guidance.GuidanceRules
import app.pawse.scoring.learning.CheckInCalibration
import app.pawse.scoring.learning.DoseResponse
import app.pawse.scoring.load.TrainingLoad
import app.pawse.scoring.math.Stats
import app.pawse.scoring.model.BodyBand
import app.pawse.scoring.model.BodyReason
import app.pawse.scoring.model.BodyScore
import app.pawse.scoring.model.BodyStatus
import app.pawse.scoring.model.Calibration
import app.pawse.scoring.model.CycleNote
import app.pawse.scoring.model.DayReport
import app.pawse.scoring.model.DayTag
import app.pawse.scoring.model.EngineInput
import app.pawse.scoring.model.EngineResult
import app.pawse.scoring.model.FormBand
import app.pawse.scoring.model.IllnessLevel
import app.pawse.scoring.model.IllnessState
import app.pawse.scoring.model.LoadMethod
import app.pawse.scoring.model.LoadReading
import app.pawse.scoring.model.ModelChange
import app.pawse.scoring.model.NightInput
import app.pawse.scoring.model.Predictor
import app.pawse.scoring.model.ReasonKind
import app.pawse.scoring.model.Signal
import app.pawse.scoring.model.SignalDetail
import app.pawse.scoring.model.SleepReading
import app.pawse.scoring.model.WeeklyPattern
import app.pawse.scoring.model.WeeklyReading
import app.pawse.scoring.sleep.SleepMath
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Pawse v2: Body, Sleep and Load stay separate, and only the guidance line
 * combines them.
 *
 * The order below is the dependency graph:
 *
 *  1. **Context first.** The cycle model decides which nights get their luteal
 *     offset removed, before anything is scored.
 *  2. **Body filter.** One Kalman track per signal and source.
 *  3. **Sleep.** Need, debt and regularity, in real units.
 *  4. **Load.** Session TRIMP, fitness and fatigue.
 *  5. **Calibration.** Check-ins refit HRV's weight and the debt effect, shrunk.
 *  6. **Body score.** The two filtered signals combined with today's weight.
 *  7. **Illness, weekly reading, reasons, guidance.**
 *  8. **Dose-response and today's energy curve.**
 *
 * Pure: no clock, no I/O. Replaying stored data gives the same numbers forever.
 */
class PawseEngine(private val config: ScoringConfig = ScoringConfig.DEFAULT) {

    fun run(input: EngineInput): EngineResult {
        val days = input.days
        val nights = input.nights
        val sleepNights = nights.mapNotNull { (d, n) -> n.sleep?.let { d to it } }.toMap()
        val body = config.body
        val clamp = body.zClamp

        // --- 1. Cycle ------------------------------------------------------------
        val cycle = Cycle.model(
            periodDays = input.periodDays,
            today = days.lastOrNull() ?: LocalDate.MIN,
            lnHrv = nights.mapNotNull { (d, n) -> n.lnHrv?.let { d to it.value } }.toMap(),
            sleepingHr = nights.mapNotNull { (d, n) -> n.sleepingHr?.let { d to it.value } }.toMap(),
            temp = nights.mapNotNull { (d, n) -> n.skinTempDelta?.let { d to it } }.toMap(),
            config = config.context,
        )
        fun adjusted(d: LocalDate, s: Signal?, delta: Double?): Signal? =
            if (s != null && delta != null && d in cycle.luteal) s.copy(value = s.value - delta) else s

        // --- 2. Body filter ------------------------------------------------------
        val hFilter = SignalFilter(body, body.defaultNoiseLnHrv)
        val rFilter = SignalFilter(body, body.defaultNoiseSleepingHr)
        val hStates = ArrayList<SignalState?>()
        val rStates = ArrayList<SignalState?>()
        val validCounts = IntArray(days.size)
        var valid = 0
        days.forEachIndexed { i, d ->
            val n = nights[d]
            val h = adjusted(d, n?.lnHrv, cycle.deltaLnHrv)
            val r = adjusted(d, n?.sleepingHr, cycle.deltaSleepingHr)
            if (h != null || r != null) valid++
            validCounts[i] = valid
            hStates += hFilter.step(d.toEpochDay(), h, n?.quality ?: 1.0)
            rStates += rFilter.step(d.toEpochDay(), r, n?.quality ?: 1.0)
        }
        val zh = hStates.map { it?.z(clamp) }
        val zr = rStates.map { it?.z(clamp)?.let { z -> -z } }
        val rho = days.mapIndexed { i, d ->
            val hs = hStates[i]
            val rs = rStates[i]
            if (hs == null || rs == null) return@mapIndexed body.rhoPrior
            val from = d.toEpochDay() - body.baselineDays
            val to = d.toEpochDay() - 1
            val hn = hFilter.nights(hs.source, from, to)
            val rn = rFilter.nights(rs.source, from, to)
            val both = hn.keys.intersect(rn.keys).toList()
            BodyMath.shrunkRho(
                Stats.pearson(both.map { hn.getValue(it) }, both.map { -rn.getValue(it) }),
                both.size,
                body,
            )
        }

        // --- 3. Sleep -------------------------------------------------------------
        var lastOffset = sleepNights.values.firstOrNull()?.endOffsetSeconds ?: 0
        val needs = days.map { SleepMath.need(sleepNights, it, config.sleep) }
        val debts = days.mapIndexed { i, d -> SleepMath.debtHours(sleepNights, d, needs[i].hours, config.sleep) }
        val sris = days.map { d ->
            val night = sleepNights[d]
            if (night != null) lastOffset = night.endOffsetSeconds
            val end = night?.endEpochMs ?: (d.toEpochDay() * DAY_MS + DAY_MS / 2 - lastOffset * 1000L)
            SleepMath.sri(input.sleepIntervals, end, night?.endOffsetSeconds ?: lastOffset, sleepNights.keys, config.sleep)
        }

        // --- 4. Load --------------------------------------------------------------
        val restingByDay = nights.mapNotNull { (d, n) -> n.restingHr?.let { d to it } }.toMap()
        val perMinute = mutableListOf<Double>()
        val sessions = days.mapIndexed { i, d ->
            val rest = Stats.median(
                restingByDay.filterKeys { it.isBefore(d) && it.isAfter(d.minusDays(body.baselineDays.toLong())) }.values.toList(),
            ) ?: rStates[i]?.baseline
            val rate = Stats.median(perMinute)?.div(config.load.defaultRpe) ?: config.load.defaultLoadPerRpeMinute
            val list = TrainingLoad.sessions(input.workouts[d].orEmpty(), input.profile, rest, rate, config.load)
            list.filter { it.load > 0.0 && it.method != LoadMethod.RPE }.forEach { perMinute += it.load / it.durationMinutes }
            list
        }
        val dayLoads = sessions.map { list -> list.sumOf { it.load } }
        val fitFat = TrainingLoad.fitnessFatigue(dayLoads, config.load)
        val typicalLoads = days.indices.map { i ->
            Stats.median(((i - config.load.typicalWindowDays).coerceAtLeast(0) until i).map { dayLoads[it] }.filter { it > 0.0 })
        }
        val forms = fitFat.map { (fit, fat) -> TrainingLoad.form(fit, fat) }

        // --- Intake per night --------------------------------------------------------
        val intakes = input.intakes.sortedBy { it.epochMs }
        val nightIntake = days.associateWith { d ->
            val bed = sleepNights[d]?.startEpochMs ?: (d.toEpochDay() * DAY_MS - HOUR_MS - lastOffset * 1000L)
            val ctx = config.context
            NightIntake(
                drinks = intakes.filter { it.epochMs in (bed - (ctx.eveningHours * HOUR_MS).toLong())..bed }
                    .sumOf { it.alcoholGrams ?: 0.0 } / ctx.gramsPerDrink,
                lateMealKcal = intakes.filter { it.epochMs in (bed - (ctx.lateMealHours * HOUR_MS).toLong())..bed }
                    .sumOf { it.kcal ?: 0.0 },
                caffeineAtBedMg = intakes.filter { it.epochMs in (bed - 48 * HOUR_MS)..bed }.sumOf {
                    (it.caffeineMg ?: 0.0) * 0.5.pow((bed - it.epochMs).toDouble() / HOUR_MS / config.energy.caffeineHalfLifeHours)
                },
            )
        }

        // --- 5. Calibration -------------------------------------------------------
        val since = input.calibrationSince
        val rowsW = mutableListOf<Int>()
        var w = body.hrvWeight
        var kappa = config.energy.kappa
        val weights = DoubleArray(days.size)
        val changes = mutableListOf<ModelChange>()
        days.forEachIndexed { i, d ->
            weights[i] = w
            // Today's check-in calibrates tomorrow, never today's own score.
            val c = input.checkIns[d]
            if (c != null && (since == null || !d.isBefore(since)) && (zh[i] != null || zr[i] != null)) {
                rowsW += i
                val nextW = CheckInCalibration.hrvWeight(
                    rowsW.map { zh[it] }, rowsW.map { zr[it] }, rowsW.map { rho[it] },
                    rowsW.map { input.checkIns.getValue(days[it]).toDouble() }, body.hrvWeight, config.learning,
                )
                val kRows = rowsW.filter { debts[it] != null }
                val nextK = CheckInCalibration.kappa(
                    kRows.map { debts[it]!! }, kRows.map { input.checkIns.getValue(days[it]).toDouble() },
                    config.energy.kappa, config.learning,
                )
                if (abs(nextW - w) >= 0.01) changes += ModelChange(d, tr("HRV's share of Body", "컨디션 내 HRV 비중"), w, nextW)
                if (abs(nextK - kappa) >= 0.001) {
                    changes += ModelChange(d, tr("How much sleep debt lowers your energy", "수면 부채가 에너지를 낮추는 정도"), kappa, nextK)
                }
                w = nextW
                kappa = nextK
            }
        }

        // --- 6-7. Body score, illness, weekly, reasons, guidance -----------------------
        val detector = IllnessDetector(config.context)
        val reports = ArrayList<DayReport>(days.size)
        val m7 = HashMap<Long, Double>()
        val cv7 = HashMap<Long, Double>()
        var strainedRun = 0
        days.forEachIndexed { i, d ->
            val day = d.toEpochDay()
            val n = nights[d]
            val hs = hStates[i]
            val rs = rStates[i]
            val lastNight = listOfNotNull(hs?.lastNightDay, rs?.lastNightDay).maxOrNull()
            val status = when {
                validCounts[i] < body.minValidNights || (zh[i] == null && zr[i] == null) -> BodyStatus.WARMING_UP
                lastNight == null || day - lastNight > body.maxNightAgeDays -> BodyStatus.NO_RECENT_NIGHT
                else -> BodyStatus.READY
            }

            // Illness: raw residual, so the filter's smoothing cannot hide three high nights.
            val residual = rs?.let { s ->
                val t = s.tonight ?: return@let null
                val b = s.baseline ?: return@let null
                (t - b) / (s.spread ?: return@let null)
            }
            detector.step(residual)
            val tempUp = above(nights, d, { it.skinTempDelta }, config.context.tempRiseC)
            val breathingUp = above(nights, d, { it.breathingRate }, config.context.breathingRise)
            val causes = buildList {
                if (input.tags.near(d, 1).contains(DayTag.SICK)) add(ReasonKind.SICK_TAG)
                if ((0 until config.context.holdBackNights).any { (nightIntake[d.minusDays(it.toLong())]?.drinks ?: 0.0) > 0.0 }) add(ReasonKind.DRINKS)
                if (input.tags.near(d, config.context.holdBackNights - 1).contains(DayTag.TRAVEL)) add(ReasonKind.TRAVEL_TAG)
                if (input.tags.near(d, config.context.holdBackNights - 1).contains(DayTag.STRESS)) add(ReasonKind.STRESS_TAG)
            }
            var level = detector.level(tempUp, breathingUp)
            val explained = causes.any { it != ReasonKind.SICK_TAG } && ReasonKind.SICK_TAG !in causes
            if (level == IllnessLevel.ALERT && explained) level = IllnessLevel.WATCH
            val illness = IllnessState(level, detector.cusum, tempUp, breathingUp, if (level == IllnessLevel.NONE) emptyList() else causes)

            // Body.
            val score = if (status == BodyStatus.READY) {
                val wi = weights[i]
                val z = BodyMath.combine(zh[i], zr[i], wi, rho[i])!!
                val variance = BodyMath.combinedVariance(
                    hs?.zVariance?.takeIf { zh[i] != null },
                    rs?.zVariance?.takeIf { zr[i] != null },
                    wi,
                    rho[i],
                ) ?: 1.0
                val sd = sqrt(variance)
                val value = BodyMath.score(z)
                val luteal = d in cycle.luteal
                BodyScore(
                    score = value,
                    low = BodyMath.score(z - body.rangeZ * sd),
                    high = BodyMath.score(z + body.rangeZ * sd),
                    band = BodyBand.of(value),
                    confidence = BodyMath.confidence(sd, body),
                    z = z,
                    zSd = sd,
                    hrv = hs?.takeIf { zh[i] != null }?.let { s ->
                        SignalDetail(
                            tonight = n?.lnHrv?.value?.let { exp(it) },
                            filtered = exp(s.x),
                            baseline = exp(s.baseline!!),
                            spread = s.spread!!,
                            z = zh[i]!!,
                            rawZ = s.rawZ(clamp),
                            source = s.source,
                            baselineNights = s.baselineNights,
                            cycleAdjusted = luteal && cycle.deltaLnHrv != null,
                        )
                    },
                    sleepingHr = rs?.takeIf { zr[i] != null }?.let { s ->
                        SignalDetail(
                            tonight = n?.sleepingHr?.value,
                            filtered = s.x,
                            baseline = s.baseline!!,
                            spread = s.spread!!,
                            z = zr[i]!!,
                            rawZ = s.rawZ(clamp)?.let { -it },
                            source = s.source,
                            baselineNights = s.baselineNights,
                            cycleAdjusted = luteal && cycle.deltaSleepingHr != null,
                        )
                    },
                    rho = rho[i],
                    hrvWeight = wi,
                    rawZ = BodyMath.combine(hs?.rawZ(clamp), rs?.rawZ(clamp)?.let { -it }, wi, rho[i]),
                    reasons = emptyList(),
                    validNights = validCounts[i],
                )
            } else {
                null
            }

            // Load reading.
            val form = forms.getOrNull(i)
            val formBand = form?.let { TrainingLoad.formBand(it, config.load) }
            val typical = typicalLoads[i]
            val range = typical?.let { TrainingLoad.range(it, score?.score, config.load) }
            val rest = illness.level == IllnessLevel.ALERT
            val load = LoadReading(
                todayLoad = dayLoads[i],
                sessions = sessions[i],
                fitness = fitFat.getOrNull(i)?.first,
                fatigue = fitFat.getOrNull(i)?.second,
                form = form,
                formBand = formBand,
                typicalDayLoad = typical,
                rangeLow = if (rest) 0.0 else range?.first,
                rangeHigh = if (rest) 0.0 else range?.second,
                rest = rest,
            )

            // Sleep reading.
            val night = sleepNights[d]
            val need = needs[i]
            val scored = night?.let {
                SleepMath.score(it.asleepMinutes, need.hours, sris[i], it.efficiencyPercent, it.wasoMinutes, config.sleep)
            }
            val doctorWindow = (0 until config.sleep.doctorDays).map { debts.getOrNull(i - it) }
            val sleep = SleepReading(
                needHours = need.hours,
                needLearned = need.learned,
                freeNights = need.freeNights,
                debtHours = debts[i],
                sri = sris[i],
                lastNightMinutes = night?.asleepMinutes,
                score = scored?.first,
                parts = scored?.second,
                efficiencyPercent = night?.efficiencyPercent,
                wasoMinutes = night?.wasoMinutes,
                remMinutes = night?.remMinutes,
                deepMinutes = night?.deepMinutes,
                suggestDoctor = doctorWindow.all { it != null && it > config.sleep.doctorDebtHours } ||
                    input.tags.near(d, config.sleep.doctorDays - 1).contains(DayTag.TROUBLE_SLEEPING),
            )

            // Weekly reading.
            val weekly = hs?.let { s -> weekly(s, rs, hFilter, rFilter, day, m7, cv7, formBand) }

            // Reasons, now that everything they can point at exists.
            val withReasons = score?.let { s ->
                s.copy(
                    reasons = reasons(
                        s, illness, sleep, nightIntake.getValue(d), dayLoads.getOrNull(i - 1), typical,
                        input.tags.near(d, 1), d in cycle.luteal && input.periodDays.isNotEmpty(),
                    ),
                )
            }
            strainedRun = if (withReasons?.band == BodyBand.STRAINED) strainedRun + 1 else 0

            reports += DayReport(
                date = d,
                body = withReasons,
                bodyStatus = status,
                validNights = validCounts[i],
                weekly = weekly,
                sleep = sleep,
                load = load,
                illness = illness,
                cycle = if (input.periodDays.isEmpty()) null else CycleNote(d in cycle.luteal, cycle.learned),
                guidance = GuidanceRules.pick(withReasons, strainedRun, illness.level, form, config.load),
                strainedRun = strainedRun,
            )
        }

        // --- 8. Dose-response and today's energy curve ------------------------------
        val doseX = mutableListOf<DoubleArray>()
        val doseY = mutableListOf<Double>()
        val firstDose = days.size - config.learning.doseWindowDays
        reports.forEachIndexed { i, report ->
            if (i < firstDose || i == 0) return@forEachIndexed
            val y = report.body?.rawZ ?: return@forEachIndexed
            val debt = debts[i] ?: return@forEachIndexed
            val intake = nightIntake.getValue(report.date)
            val yesterday = dayLoads[i - 1]
            val typical = typicalLoads[i - 1]
            val training = if (typical != null && typical > 0.0) ln(1.0 + yesterday / typical) / ln(2.0) else 0.0
            val x = DoubleArray(Predictor.entries.size)
            x[Predictor.TRAINING.ordinal] = training
            x[Predictor.SLEEP_DEBT.ordinal] = debt
            x[Predictor.ALCOHOL.ordinal] = intake.drinks
            x[Predictor.CAFFEINE.ordinal] = intake.caffeineAtBedMg / 100.0
            x[Predictor.LATE_MEAL.ordinal] = intake.lateMealKcal / 500.0
            doseX += x
            doseY += y
        }
        val insights = DoseResponse.fit(doseX, doseY, config.learning)

        val last = days.lastOrNull()
        val energy = last?.let { d ->
            val night = sleepNights[d] ?: return@let null
            val recent = sleepNights.filterKeys { it.isBefore(d) }.toSortedMap().toList()
            EnergyForecastModel.forecast(
                lastNight = night,
                recentNights = recent,
                day = d,
                intervals = input.sleepIntervals,
                debtHours = debts.lastOrNull() ?: 0.0,
                kappa = kappa,
                intakes = intakes,
                config = config.energy,
            )
        }

        val matched = reports.filter { r -> r.body != null && input.checkIns[r.date] != null && (since == null || !r.date.isBefore(since)) }
        val calibration = Calibration(
            checkIns = input.checkIns.keys.count { since == null || !it.isBefore(since) },
            bodyMatch = Stats.spearman(
                matched.map { it.body!!.score.toDouble() },
                matched.map { input.checkIns.getValue(it.date).toDouble() },
            ),
            hrvWeight = w,
            kappa = kappa,
            changes = changes,
        )

        return EngineResult(reports, energy, insights, calibration, ScoringConfig.SCORING_VERSION, config.configHash)
    }

    private data class NightIntake(val drinks: Double, val lateMealKcal: Double, val caffeineAtBedMg: Double)

    /** Tonight's value at least [rise] above the median of the 60 nights before it. */
    private fun above(
        nights: Map<LocalDate, NightInput>,
        d: LocalDate,
        value: (NightInput) -> Double?,
        rise: Double,
    ): Boolean {
        val tonight = nights[d]?.let(value) ?: return false
        val before = (1..config.body.baselineDays).mapNotNull { nights[d.minusDays(it.toLong())]?.let(value) }
        if (before.size < config.body.minValidNights) return false
        return tonight - Stats.median(before)!! >= rise
    }

    /**
     * The week, not just the day. An overreached athlete shows a falling 7-day
     * mean of lnRMSSD and a shrinking day-to-day variation (Plews et al., 2012),
     * which is why v2 no longer assumes higher HRV is always better.
     */
    private fun weekly(
        hs: SignalState,
        rs: SignalState?,
        hFilter: SignalFilter,
        rFilter: SignalFilter,
        day: Long,
        m7: HashMap<Long, Double>,
        cv7: HashMap<Long, Double>,
        formBand: FormBand?,
    ): WeeklyReading? {
        val week = hFilter.nights(hs.source, day - 6, day).values.toList()
        if (week.size < config.body.weeklyMinNights) return null
        val mean = week.average()
        val cv = 100.0 * (Stats.sd(week) ?: 0.0) / mean
        m7[day] = mean
        cv7[day] = cv
        val b = hs.baseline ?: return null
        val s = hs.spread ?: return null
        val usualCv = Stats.median(cv7.filterKeys { it in (day - config.body.baselineDays)..(day - 8) }.values.toList())

        val swc = 0.5 * s
        val hrvBand = when {
            mean > b + swc -> 1
            mean < b - swc -> -1
            else -> 0
        }
        val cvState = when {
            usualCv == null -> 0
            cv > usualCv * (1 + config.body.cvBandRatio) -> 1
            cv < usualCv * (1 - config.body.cvBandRatio) -> -1
            else -> 0
        }
        val hrWeek = rs?.let { r -> rFilter.nights(r.source, day - 6, day).values.toList() }.orEmpty()
        val hrBaseline = rs?.baseline
        val hrSpread = rs?.spread
        val hrBand = if (hrBaseline == null || hrSpread == null || hrWeek.size < config.body.weeklyMinNights) {
            null
        } else {
            val hm = hrWeek.average()
            when {
                hm > hrBaseline + 0.5 * hrSpread -> 1
                hm < hrBaseline - 0.5 * hrSpread -> -1
                else -> 0
            }
        }

        val twoWeeksAgo = m7[day - 14]
        val weekAgo = m7[day - 7]
        val overreaching = twoWeeksAgo != null && weekAgo != null &&
            mean < twoWeeksAgo - swc && mean < weekAgo &&
            (cv7[day - 7]?.let { cv < it } ?: false) &&
            formBand == FormBand.HEAVY

        val strained = listOf(hrvBand == -1, cvState == 1, hrBand == 1).count { it }
        val adapting = listOf(hrvBand == 1, cvState <= 0, hrBand == -1).count { it }
        val steady = listOf(hrvBand == 0, cvState == 0, hrBand == 0).count { it }
        val pattern = when {
            overreaching -> WeeklyPattern.POSSIBLE_OVERREACHING
            strained >= 2 -> WeeklyPattern.STRAINED
            adapting >= 2 && hrvBand == 1 -> WeeklyPattern.ADAPTING
            steady >= 2 -> WeeklyPattern.STEADY
            else -> WeeklyPattern.MIXED
        }
        return WeeklyReading(pattern, exp(mean), cv, usualCv, week.size)
    }

    /**
     * The top two reasons, physiology first: "sleeping HR 4 bpm above normal; 2
     * drinks logged". On a day above your usual, only the signals are named.
     */
    private fun reasons(
        score: BodyScore,
        illness: IllnessState,
        sleep: SleepReading,
        intake: NightIntake,
        yesterdayLoad: Double?,
        typicalLoad: Double?,
        tags: Set<DayTag>,
        luteal: Boolean,
    ): List<BodyReason> {
        val threshold = config.body.reasonZ
        val physiology = buildList {
            score.sleepingHr?.let { s ->
                val raw = s.rawZ ?: return@let
                val bpm = (s.tonight ?: return@let) - s.baseline
                if (abs(raw) >= threshold) add(abs(raw) to BodyReason(if (bpm > 0) ReasonKind.SLEEPING_HR_ABOVE else ReasonKind.SLEEPING_HR_BELOW, abs(bpm)))
            }
            score.hrv?.let { s ->
                val raw = s.rawZ ?: return@let
                val pct = 100.0 * ((s.tonight ?: return@let) / s.baseline - 1.0)
                if (abs(raw) >= threshold) add(abs(raw) to BodyReason(if (pct < 0) ReasonKind.HRV_BELOW else ReasonKind.HRV_ABOVE, abs(pct)))
            }
        }.sortedByDescending { it.first }.map { it.second }
            .filter { score.z < 0 || it.kind == ReasonKind.HRV_ABOVE || it.kind == ReasonKind.SLEEPING_HR_BELOW }

        val context = if (score.z >= 0) {
            emptyList()
        } else {
            buildList {
                if (illness.level != IllnessLevel.NONE) add(BodyReason(ReasonKind.ILLNESS_WATCH, illness.cusum))
                if (DayTag.SICK in tags) add(BodyReason(ReasonKind.SICK_TAG, 1.0))
                if (intake.drinks >= 0.5) add(BodyReason(ReasonKind.DRINKS, intake.drinks))
                val last = sleep.lastNightMinutes
                if (last != null && last / 60.0 < sleep.needHours - 1.0) add(BodyReason(ReasonKind.SHORT_NIGHT, sleep.needHours - last / 60.0))
                sleep.debtHours?.takeIf { it >= 2.0 }?.let { add(BodyReason(ReasonKind.SLEEP_DEBT, it)) }
                if (yesterdayLoad != null && typicalLoad != null && typicalLoad > 0.0 &&
                    yesterdayLoad >= config.load.hardDayMultiple * typicalLoad
                ) {
                    add(BodyReason(ReasonKind.HARD_TRAINING, yesterdayLoad / typicalLoad))
                }
                if (intake.lateMealKcal >= 300.0) add(BodyReason(ReasonKind.LATE_MEAL, intake.lateMealKcal))
                if (intake.caffeineAtBedMg >= 50.0) add(BodyReason(ReasonKind.CAFFEINE_AT_BED, intake.caffeineAtBedMg))
                if (DayTag.TRAVEL in tags) add(BodyReason(ReasonKind.TRAVEL_TAG, 1.0))
                if (DayTag.STRESS in tags) add(BodyReason(ReasonKind.STRESS_TAG, 1.0))
                if (luteal) add(BodyReason(ReasonKind.CYCLE, 1.0))
            }
        }
        return (physiology.take(1) + context.take(1) + physiology.drop(1) + context.drop(1)).take(2)
    }

    private fun Map<LocalDate, Set<DayTag>>.near(d: LocalDate, daysBack: Int): Set<DayTag> =
        (0..daysBack).flatMap { this[d.minusDays(it.toLong())].orEmpty() }.toSet()

    private companion object {
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 86_400_000L
    }
}
