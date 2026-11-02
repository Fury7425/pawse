package app.pawse.feature.home

import app.pawse.core.data.scoring.ScoringSnapshot
import app.pawse.core.model.FactSheet
import app.pawse.scoring.attention.Fact
import app.pawse.scoring.attention.FactUnit
import app.pawse.scoring.model.BodyReason
import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.ReasonKind
import app.pawse.scoring.tr
import kotlin.math.roundToInt

/**
 * Every number Scores shows this morning, worded the way Scores words it. The lines
 * are all the on-device model is given; the facts are what NumberCheck holds its
 * sentences to. What settings hide stays out.
 *
 * ponytail: NumberCheck reads no signs, so a figure that can go negative (form,
 * regularity, skin temperature) joins only when it is not.
 */
internal fun ScoringSnapshot.factSheet(): FactSheet {
    val lines = mutableListOf<String>()
    val facts = mutableListOf<Fact>()
    fun add(line: String, vararg fact: Fact?) {
        lines += line
        facts += fact.filterNotNull()
    }

    val body = today.body
    if (body != null) {
        val word = ScoreCopy.bandWord(body.band)
        if (showNumbers) {
            add(
                tr("Body: ${body.score}, likely ${body.low}–${body.high}, $word", "컨디션: ${body.score}, 범위 ${body.low}–${body.high}, $word"),
                Fact("body", BODY, body.score.toDouble(), FactUnit.PLAIN),
                Fact("body", BODY, body.low.toDouble(), FactUnit.PLAIN),
                Fact("body", BODY, body.high.toDouble(), FactUnit.PLAIN),
            )
        } else {
            add(tr("Body: $word", "컨디션: $word"))
        }
        body.hrv?.let { s ->
            s.tonight?.let { add(tr("HRV last night: ${it.roundToInt()} ms", "지난밤 HRV: ${it.roundToInt()} ms"), Fact("hrv", HRV, it, FactUnit.MS)) }
            add(tr("Usual HRV: ${s.baseline.roundToInt()} ms", "평소 HRV: ${s.baseline.roundToInt()} ms"), Fact("hrv", HRV, s.baseline, FactUnit.MS))
        }
        body.sleepingHr?.let { s ->
            s.tonight?.let {
                add(tr("Sleeping heart rate last night: ${it.roundToInt()} bpm", "지난밤 수면 중 심박수: ${it.roundToInt()} bpm"), Fact("hr", HR, it, FactUnit.BPM))
            }
            add(tr("Usual sleeping heart rate: ${s.baseline.roundToInt()} bpm", "평소 수면 중 심박수: ${s.baseline.roundToInt()} bpm"), Fact("hr", HR, s.baseline, FactUnit.BPM))
        }
        body.reasons.forEach { r -> add(ScoreCopy.reasons(listOf(r)), reasonFact(r)) }
    }

    val sleep = today.sleep
    sleep.lastNightMinutes?.let {
        add(tr("Slept last night: ${ScoreCopy.formatMinutes(it)}", "지난밤 수면 시간: ${ScoreCopy.formatMinutes(it)}"), Fact("asleep", ASLEEP, it, FactUnit.DURATION))
    }
    add(
        tr("Sleep need: ${ScoreCopy.formatHours(sleep.needHours)}", "필요 수면: ${ScoreCopy.formatHours(sleep.needHours)}"),
        Fact("need", NEED, sleep.needHours * 60.0, FactUnit.DURATION),
    )
    sleep.debtHours?.let { h ->
        add(tr("Sleep debt: ", "수면 부채: ") + ScoreCopy.debt(h), Fact("debt", DEBT, h * 60.0, FactUnit.DURATION).takeIf { h >= 0.25 })
    }
    if (showNumbers) sleep.score?.let { add(tr("Sleep score: $it", "수면 점수: $it"), Fact("sleep_score", SLEEP_SCORE, it.toDouble(), FactUnit.PLAIN)) }
    sleep.sri?.takeIf { it >= 0.0 }?.let {
        add(tr("Regularity: ${it.roundToInt()}", "규칙성: ${it.roundToInt()}"), Fact("regularity", REGULARITY, it, FactUnit.PLAIN))
    }
    sleep.efficiencyPercent?.let {
        add(tr("Continuity: ${it.roundToInt()}%", "연속성: ${it.roundToInt()}%"), Fact("continuity", CONTINUITY, it, FactUnit.PERCENT))
    }

    val load = today.load
    val points = load.points.roundToInt()
    val range = load.pointsRange
    add(
        when {
            range == null -> tr("Load today: $points points", "오늘 부하: ${points}점")
            range.rest -> tr("Load today: $points points; today is for rest", "오늘 부하: ${points}점, 오늘은 쉬는 날")
            else -> tr("Load today: $points points, of a ${range.low}–${range.high} range", "오늘 부하: ${range.low}–${range.high} 범위 중 ${points}점")
        },
        Fact("load", LOAD, load.points, FactUnit.PLAIN),
        range?.let { Fact("load", LOAD, it.low.toDouble(), FactUnit.PLAIN) },
        range?.let { Fact("load", LOAD, it.high.toDouble(), FactUnit.PLAIN) },
    )
    load.formBand?.let { band ->
        val form = load.form?.times(100)?.takeIf { showNumbers && it >= 0.0 }
        add(
            tr("Form: ", "폼: ") + ScoreCopy.form(band) + (form?.let { " (${it.roundToInt()}%)" } ?: ""),
            form?.let { Fact("form", FORM, it, FactUnit.PERCENT) },
        )
    }
    val fitness = load.fitness
    val fatigue = load.fatigue
    if (fitness != null && fatigue != null) {
        add(
            tr("Fitness ${ScoreCopy.load(fitness)}, fatigue ${ScoreCopy.load(fatigue)}", "체력 ${ScoreCopy.load(fitness)}, 피로 ${ScoreCopy.load(fatigue)}"),
            Fact("fitness", FITNESS, fitness, FactUnit.PLAIN),
            Fact("fatigue", FATIGUE, fatigue, FactUnit.PLAIN),
        )
    }

    energy?.let { e ->
        fun clock(minute: Int) = Math.floorMod(minute, 1440).toDouble()
        add(
            tr("Best focus: ${ScoreCopy.minuteOfDay(e.focusStart)}–${ScoreCopy.minuteOfDay(e.focusEnd)}", "집중하기 좋은 시간: ${ScoreCopy.minuteOfDay(e.focusStart)}–${ScoreCopy.minuteOfDay(e.focusEnd)}"),
            Fact("focus", FOCUS, clock(e.focusStart), FactUnit.CLOCK),
            Fact("focus", FOCUS, clock(e.focusEnd), FactUnit.CLOCK),
        )
        add(tr("Dip around ${ScoreCopy.minuteOfDay(e.dipMinute)}", "${ScoreCopy.minuteOfDay(e.dipMinute)}쯤 처짐"), Fact("dip", DIP, clock(e.dipMinute), FactUnit.CLOCK))
        add(
            tr("Wind down from ${ScoreCopy.minuteOfDay(e.windDownMinute)}", "${ScoreCopy.minuteOfDay(e.windDownMinute)}부터 잘 준비"),
            Fact("wind", WIND, clock(e.windDownMinute), FactUnit.CLOCK),
        )
        e.caffeineAtBedMg.takeIf { it >= 1.0 }?.let {
            add(tr("Caffeine left at bedtime: about ${it.roundToInt()} mg", "잠자리에 들 때 남은 카페인: 약 ${it.roundToInt()} mg"), Fact("caffeine", CAFFEINE, it, FactUnit.PLAIN))
        }
    }

    rows(1).single().rec?.signals?.let { s ->
        s.spo2?.let { add(tr("Blood oxygen: ", "혈중 산소: ") + ScoreCopy.formatRaw(Metric.SPO2, it), Fact("spo2", SPO2, it, FactUnit.PERCENT, 1)) }
        // Korean writes it as 회/분, which NumberCheck reads as a rate.
        s.breathingRate?.let { add(tr("Breathing rate: ", "호흡수: ") + ScoreCopy.formatRaw(Metric.RESPIRATORY_RATE, it), Fact("breathing", BREATH, it, FactUnit.BPM, 1)) }
    }

    return FactSheet(lines, facts, ScoreCopy.guidance(today.guidance))
}

/** The number inside a Body reason, under the same metric as its signal. */
private fun reasonFact(r: BodyReason): Fact? {
    val a = r.amount
    return when (r.kind) {
        ReasonKind.SLEEPING_HR_ABOVE, ReasonKind.SLEEPING_HR_BELOW -> Fact("hr", HR, a, FactUnit.BPM)
        ReasonKind.HRV_BELOW, ReasonKind.HRV_ABOVE -> Fact("hrv", HRV, a, FactUnit.PERCENT)
        ReasonKind.DRINKS -> Fact("drinks", DRINKS, if (a < 1.5) 1.0 else a.roundToInt().toDouble(), FactUnit.PLAIN)
        ReasonKind.SHORT_NIGHT -> Fact("short", SHORT, a * 60.0, FactUnit.DURATION)
        ReasonKind.SLEEP_DEBT -> Fact("debt", DEBT, a * 60.0, FactUnit.DURATION)
        ReasonKind.HARD_TRAINING -> Fact("training", TRAINING, a, FactUnit.PLAIN, 1)
        ReasonKind.LATE_MEAL -> Fact("late_meal", LATE_MEAL, a, FactUnit.KCAL)
        ReasonKind.CAFFEINE_AT_BED -> Fact("caffeine", CAFFEINE, a, FactUnit.PLAIN)
        else -> null
    }
}

// What ties a written number to its fact: each metric's names in both languages.
private val BODY = listOf("Body", "컨디션")
private val HRV = listOf("HRV", "heart rate variability", "심박 변이도")
private val HR = listOf("sleeping heart rate", "sleeping HR", "heart rate", "수면 중 심박수", "심박수")
private val ASLEEP = listOf("slept", "asleep", "잤", "수면 시간")
private val NEED = listOf("need", "필요")
private val DEBT = listOf("debt", "behind", "부채")
private val SHORT = listOf("short", "부족")
private val SLEEP_SCORE = listOf("sleep score", "수면 점수")
private val REGULARITY = listOf("regularity", "regular", "규칙")
private val CONTINUITY = listOf("continuity", "efficiency", "연속성", "효율")
private val LOAD = listOf("load", "points", "부하")
private val FORM = listOf("form", "폼")
private val FITNESS = listOf("fitness", "체력")
private val FATIGUE = listOf("fatigue", "피로")
private val FOCUS = listOf("focus", "집중")
private val DIP = listOf("dip", "처지", "처짐")
private val WIND = listOf("wind down", "wind-down", "잘 준비")
private val CAFFEINE = listOf("caffeine", "카페인")
private val SPO2 = listOf("blood oxygen", "oxygen", "SpO2", "SpO₂", "산소")
private val BREATH = listOf("breathing", "breath", "호흡")
private val DRINKS = listOf("drink", "술")
private val TRAINING = listOf("training", "운동")
private val LATE_MEAL = listOf("kcal", "meal", "잠들기")
