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
import app.pawse.scoring.tr
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
        ScoreType.BODY -> tr("Body", "컨디션")
        ScoreType.SLEEP -> tr("Sleep", "수면")
        ScoreType.LOAD -> tr("Load", "부하")
    }

    fun guidance(g: Guidance): String = when (g) {
        Guidance.WATCH_SYMPTOMS -> tr("Take it easy today and watch for symptoms.", "오늘은 무리하지 말고 증상이 있는지 살펴보세요.")
        Guidance.REST_DAY -> tr("An easy or rest day will pay off.", "가볍게 보내거나 쉬는 날로 삼으면 도움이 돼요.")
        Guidance.KEEP_LIGHT -> tr("Keep it light; tonight’s sleep is your best lever.", "가볍게 보내세요. 오늘 밤 잠이 가장 큰 도움이 돼요.")
        Guidance.GO_MODERATE -> tr("Body’s fine but fatigue is building; go moderate.", "몸은 괜찮지만 피로가 쌓이고 있어요. 적당히 하세요.")
        Guidance.GOOD_DAY -> tr("Good day for hard training or a big study block.", "강도 높은 운동이나 긴 공부에 좋은 날이에요.")
        Guidance.SETTLING -> tr("Still learning your normal. Here is what last night says.", "아직 평소 상태를 알아가는 중이에요. 지난밤 기록은 이래요.")
    }

    fun bandWord(band: BodyBand): String = when (band) {
        BodyBand.ABOVE_NORMAL -> tr("Above normal", "평소보다 높음")
        BodyBand.TYPICAL -> tr("Typical", "평소 수준")
        BodyBand.BELOW_NORMAL -> tr("Below your normal", "평소보다 낮음")
        BodyBand.STRAINED -> tr("Clearly off your normal", "평소와 확연히 다름")
    }

    fun bandMeaning(band: BodyBand): String = when (band) {
        BodyBand.ABOVE_NORMAL -> tr("Fresher than usual.", "평소보다 개운한 상태예요.")
        BodyBand.TYPICAL -> tr("Inside your normal range.", "평소 범위 안이에요.")
        BodyBand.BELOW_NORMAL -> tr("Something is costing you.", "무언가 몸에 부담을 주고 있어요.")
        BodyBand.STRAINED -> tr("Clearly off your normal.", "평소와 확연히 달라요.")
    }

    fun confidence(c: Confidence): String = when (c) {
        Confidence.HIGH -> tr("High confidence", "신뢰도 높음")
        Confidence.MEDIUM -> tr("Medium confidence", "신뢰도 보통")
        Confidence.LOW -> tr("Low confidence: missed or patchy nights", "신뢰도 낮음: 빠졌거나 끊긴 밤이 있어요")
    }

    fun bodyStatus(status: BodyStatus, validNights: Int, needed: Int): String = when (status) {
        BodyStatus.READY -> ""
        BodyStatus.WARMING_UP -> if (validNights < needed) {
            tr(
                "Night ${validNights + 1} of $needed. Body compares each night with your own normal, " +
                    "and a reading built on fewer nights would be a guess.",
                "${needed}밤 중 ${validNights + 1}번째 밤이에요. 컨디션은 매일 밤을 내 평소와 비교하는데, " +
                    "그보다 적은 밤으로 읽으면 추측에 불과해요.",
            )
        } else {
            tr(
                "Learning the normal for this watch. A new device starts its own baseline, so numbers are never mixed.",
                "이 기기의 평소 상태를 알아가는 중이에요. 새 기기는 기준을 따로 잡아서 수치가 섞이지 않아요.",
            )
        }
        BodyStatus.NO_RECENT_NIGHT -> tr(
            "No night recorded in the last two days, so there is no Body reading for today.",
            "최근 이틀 동안 기록된 밤이 없어서 오늘은 컨디션 값이 없어요.",
        )
    }

    /** "sleeping HR 4 bpm above normal", "2 drinks logged". */
    fun reason(r: BodyReason): String {
        val a = r.amount
        val n = a.roundToInt()
        return when (r.kind) {
            ReasonKind.SLEEPING_HR_ABOVE -> tr("sleeping HR $n bpm above normal", "수면 중 심박수가 평소보다 $n bpm 높음")
            ReasonKind.SLEEPING_HR_BELOW -> tr("sleeping HR $n bpm below normal", "수면 중 심박수가 평소보다 $n bpm 낮음")
            ReasonKind.HRV_BELOW -> tr("HRV $n% below normal", "HRV가 평소보다 $n% 낮음")
            ReasonKind.HRV_ABOVE -> tr("HRV $n% above normal", "HRV가 평소보다 $n% 높음")
            ReasonKind.ILLNESS_WATCH -> tr("overnight heart rate high for several nights", "며칠 밤째 야간 심박수가 높음")
            ReasonKind.SICK_TAG -> tr("you tagged feeling sick", "아프다고 기록함")
            ReasonKind.DRINKS -> tr("${drinks(a)} logged", "${drinks(a)} 기록됨")
            ReasonKind.SHORT_NIGHT -> tr("${formatHours(a)} short of your need last night", "지난밤 필요 수면보다 ${formatHours(a)} 부족")
            ReasonKind.SLEEP_DEBT -> tr("${formatHours(a)} of sleep debt", "수면 부채 ${formatHours(a)}")
            ReasonKind.HARD_TRAINING -> tr("yesterday’s training was ${one(a)}× your usual", "어제 운동량이 평소의 ${one(a)}배")
            ReasonKind.LATE_MEAL -> tr("$n kcal within 3 h of bed", "잠들기 3시간 이내 $n kcal 섭취")
            ReasonKind.CAFFEINE_AT_BED -> tr("about $n mg of caffeine left at bedtime", "잠자리에 들 때 카페인 약 $n mg 남음")
            ReasonKind.TRAVEL_TAG -> tr("you tagged travel", "여행을 기록함")
            ReasonKind.STRESS_TAG -> tr("you tagged an exam or stress week", "시험이나 스트레스 주간을 기록함")
            ReasonKind.CYCLE -> tr("part of this may be your cycle", "일부는 생리 주기 영향일 수 있음")
        }
    }

    fun reasons(list: List<BodyReason>): String =
        list.joinToString("; ") { reason(it) }.replaceFirstChar { it.uppercase(Locale.getDefault()) }

    fun weekly(p: WeeklyPattern): String = when (p) {
        WeeklyPattern.STEADY -> tr("Steady week", "안정적인 한 주")
        WeeklyPattern.ADAPTING -> tr("Adapting well", "잘 적응하는 중")
        WeeklyPattern.STRAINED -> tr(
            "Strained week: stress, short sleep, illness or a hard block",
            "힘든 한 주: 스트레스, 수면 부족, 질병 또는 고된 훈련",
        )
        WeeklyPattern.POSSIBLE_OVERREACHING -> tr(
            "HRV drifting down under heavy load: check how you feel",
            "높은 부하 속에 HRV가 내려가는 중: 몸 상태를 확인해 보세요",
        )
        WeeklyPattern.MIXED -> tr("Mixed week", "들쭉날쭉한 한 주")
    }

    fun form(f: FormBand): String = when (f) {
        FormBand.FRESH -> tr("Fresh", "개운함")
        FormBand.PRODUCTIVE -> tr("Productive training", "효과적인 훈련")
        FormBand.HEAVY -> tr("Heavy", "무거움")
    }

    /** "You're 3 h 10 m behind", or nothing to catch up. */
    fun debt(hours: Double?): String = when {
        hours == null -> tr("Not enough nights yet", "아직 밤 기록이 부족해요")
        hours < 0.25 -> tr("No sleep debt", "수면 부채 없음")
        else -> tr("${formatHours(hours)} behind", "${formatHours(hours)} 부족")
    }

    /** Sleep Regularity Index in a word; the UK Biobank median was 81. */
    fun regularity(sri: Double?): String = when {
        sri == null -> tr("Regularity needs a week of nights", "규칙성은 일주일치 밤이 필요해요")
        sri >= 81 -> tr("Regular", "규칙적") + " (${sri.roundToInt()})"
        sri >= 60 -> tr("Fairly regular", "대체로 규칙적") + " (${sri.roundToInt()})"
        else -> tr("Irregular", "불규칙") + " (${sri.roundToInt()})"
    }

    val ILLNESS_ALERT: String
        get() = tr(
            "Your overnight heart rate has been above your normal for several nights. " +
                "This often happens with a cold, stress, alcohol or travel.",
            "며칠 밤째 야간 심박수가 평소보다 높아요. 감기, 스트레스, 음주, 여행 중에 흔히 나타나요.",
        )

    val ILLNESS_WATCH: String
        get() = tr(
            "Overnight heart rate has run a little high for a few nights. Pawse is keeping an eye on it.",
            "며칠 밤 동안 야간 심박수가 조금 높았어요. Pawse가 지켜보고 있어요.",
        )

    val DOCTOR: String
        get() = tr(
            "Your sleep debt has stayed high for two weeks, or you said you are having trouble sleeping. " +
                "Talking to a doctor will help more than more tracking; CBT-I is the first-line treatment for insomnia.",
            "수면 부채가 2주째 높거나, 잠들기 어렵다고 기록했어요. 기록을 더 하는 것보다 의사와 상담하는 게 " +
                "더 도움이 돼요. 불면증의 1차 치료법은 CBT-I(불면증 인지행동치료)예요.",
        )

    val FOOTER: String
        get() = tr(
            "Wellness information, not a diagnosis. Pawse doesn’t account for medical conditions or medication.",
            "건강 참고 정보일 뿐 진단이 아니에요. Pawse는 질환이나 복용 중인 약을 고려하지 않아요.",
        )

    val FORECAST_NOTE: String
        get() = tr(
            "A forecast from the two-process model of sleep: it models typical physiology rather than measuring yours.",
            "수면의 2과정 모델로 계산한 예측이에요. 내 몸을 측정한 게 아니라 일반적인 생리를 모델로 삼아요.",
        )

    fun likelyCauses(causes: List<ReasonKind>): String? {
        val named = causes.mapNotNull {
            when (it) {
                ReasonKind.DRINKS -> tr("drinks you logged", "기록한 음주")
                ReasonKind.TRAVEL_TAG -> tr("travel", "여행")
                ReasonKind.STRESS_TAG -> tr("a stress week", "스트레스 주간")
                ReasonKind.SICK_TAG -> tr("feeling sick, as you tagged", "아프다고 기록한 컨디션")
                else -> null
            }
        }
        if (named.isEmpty()) return null
        return tr(
            "Likely linked to " + named.joinToString(" and ") + ".",
            named.joinToString(", ") + "의 영향일 수 있어요.",
        )
    }

    fun tag(t: DayTag): String = when (t) {
        DayTag.SICK -> tr("Sick", "아픔")
        DayTag.TRAVEL -> tr("Travel", "여행")
        DayTag.STRESS -> tr("Exam or stress", "시험·스트레스")
        DayTag.TROUBLE_SLEEPING -> tr("Trouble sleeping", "잠들기 어려움")
        DayTag.OTHER -> tr("Other", "기타")
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

    val DISAGREE: String
        get() = tr(
            "How you feel and what your body shows disagree today. Both are information; neither is the winner.",
            "오늘은 느끼는 컨디션과 몸의 신호가 서로 달라요. 둘 다 참고할 정보일 뿐, 어느 한쪽이 맞는 건 아니에요.",
        )

    /** "After evenings with a drink, your Body signal ran 0.4 SD lower (0.2 to 0.6), seen 11 times." */
    fun insight(i: Insight): String {
        val lead = when (i.predictor) {
            Predictor.TRAINING -> tr("After a typical training day", "평소만큼 운동한 날은")
            Predictor.SLEEP_DEBT -> tr("For each hour of sleep debt", "수면 부채 1시간마다")
            Predictor.ALCOHOL -> tr("For each drink the evening before", "전날 저녁 술 한 잔마다")
            Predictor.CAFFEINE -> tr("For each 100 mg of caffeine left at bedtime", "잠자리에 들 때 남은 카페인 100 mg마다")
            Predictor.LATE_MEAL -> tr("For each 500 kcal within 3 h of bed", "잠들기 3시간 이내 500 kcal마다")
        }
        val (lo, hi) = listOf(abs(i.low), abs(i.high)).sorted()
        return tr(
            "$lead, your Body signal the next morning ran ${one(abs(i.effect))} SD ${if (i.effect < 0) "lower" else "higher"} " +
                "(${one(lo)} to ${one(hi)}), seen on ${i.exposureDays} days.",
            "$lead 다음 날 아침 컨디션 신호가 ${one(abs(i.effect))} SD ${if (i.effect < 0) "낮았어요" else "높았어요"} " +
                "(${one(lo)}~${one(hi)}, ${i.exposureDays}일 관찰).",
        )
    }

    val INSIGHT_NOTE: String
        get() = tr("Associations in your own data, not proof of cause.", "내 데이터 안의 연관성일 뿐, 원인을 증명하지는 않아요.")

    fun loadMethod(m: LoadMethod?): String = when (m) {
        LoadMethod.BANISTER -> tr("Heart rate (Banister TRIMP)", "심박수 (Banister TRIMP)")
        LoadMethod.EDWARDS -> tr("Heart-rate zones (Edwards)", "심박 구간 (Edwards)")
        LoadMethod.RPE -> tr("Effort × minutes", "운동 강도 × 시간(분)")
        null -> tr("Not counted", "집계 안 됨")
    }

    fun provenanceLabel(provenance: Provenance): String = when (provenance) {
        Provenance.PUBLISHED -> tr("Published", "논문 기반")
        Provenance.RECOVERED -> tr("Recovered", "복원값")
        Provenance.INFERRED -> tr("Inferred", "추정값")
        Provenance.OUR_CHOICE -> tr("Our default", "Pawse 기본값")
    }

    fun metricLabel(metric: Metric): String = when (metric) {
        Metric.HRV_RMSSD -> tr("Heart rate variability", "심박 변이도")
        Metric.RESTING_HEART_RATE -> tr("Resting heart rate", "안정 시 심박수")
        Metric.RESPIRATORY_RATE -> tr("Breathing rate", "호흡수")
        Metric.SKIN_TEMPERATURE -> tr("Skin temperature", "피부 온도")
        Metric.SPO2 -> tr("Blood oxygen", "혈중 산소")
        Metric.HEART_RATE -> tr("Heart rate", "심박수")
        Metric.SLEEPING_HEART_RATE -> tr("Sleeping heart rate", "수면 중 심박수")
        Metric.HR_NADIR -> tr("Heart-rate low point", "심박수 최저점")
        Metric.NIGHT_HR_COVERAGE -> tr("Night coverage", "야간 측정 비율")
        Metric.MENSTRUATION -> tr("Period", "생리")
        Metric.BODY_WEIGHT -> tr("Weight", "체중")
        Metric.BODY_FAT -> tr("Body fat", "체지방")
        Metric.LEAN_MASS -> tr("Lean mass", "제지방량")
        Metric.BONE_MASS -> tr("Bone mass", "골량")
        Metric.BODY_WATER -> tr("Body water", "체수분")
        Metric.BODY_HEIGHT -> tr("Height", "키")
        Metric.BASAL_METABOLIC_RATE -> tr("Basal metabolic rate", "기초대사량")
        Metric.ACTIVE_ENERGY -> tr("Active energy", "활동 에너지")
    }

    /** A raw value in its own unit, rounded to the precision the sensor deserves. */
    fun formatRaw(metric: Metric, raw: Double): String = when (metric) {
        Metric.HRV_RMSSD -> "${raw.roundToInt()} ms"
        Metric.RESTING_HEART_RATE, Metric.HEART_RATE, Metric.SLEEPING_HEART_RATE -> "${raw.roundToInt()} bpm"
        Metric.RESPIRATORY_RATE -> String.format(Locale.getDefault(), tr("%.1f br/min", "%.1f회/분"), raw)
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
        val h = total / 60
        val m = total % 60
        return tr(
            "$h h ${String.format(Locale.getDefault(), "%02d", m)}",
            when {
                h == 0 -> "${m}분"
                m == 0 -> "${h}시간"
                else -> "${h}시간 ${m}분"
            },
        )
    }

    fun formatHours(hours: Double): String = formatMinutes(hours * 60.0)

    /** Minutes since midnight of the shown day as a wall clock: "21:30". Past midnight wraps. */
    fun minuteOfDay(minute: Int): String {
        val m = Math.floorMod(minute, 1440)
        return String.format(Locale.getDefault(), "%02d:%02d", m / 60, m % 60)
    }

    fun load(value: Double): String = value.roundToInt().toString()

    fun drinks(n: Double): String {
        val count = if (n < 1.5) 1 else n.roundToInt()
        return tr(if (count == 1) "1 drink" else "$count drinks", "술 ${count}잔")
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
