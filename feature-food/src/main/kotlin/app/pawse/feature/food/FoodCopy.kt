package app.pawse.feature.food

import app.pawse.core.data.food.DayTotals
import app.pawse.core.data.food.FoodLookupFailure
import app.pawse.core.data.food.FoodSource
import app.pawse.core.data.food.Meal
import app.pawse.core.data.food.Nutrients
import app.pawse.core.data.food.ocr.LabelBasis
import app.pawse.core.data.food.ocr.LabelWarning
import app.pawse.scoring.tr
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The food side's voice, in one file, for the same reason the score side has one.
 *
 * The rule that matters here is provenance. A figure typed off a packet by hand, a
 * figure read by OCR and a figure from a public database are not equally
 * trustworthy, and every screen that shows one says which it is. The app never
 * launders a guess into a fact.
 */
object FoodCopy {

    fun meal(meal: Meal): String = when (meal) {
        Meal.BREAKFAST -> tr("Breakfast", "아침")
        Meal.LUNCH -> tr("Lunch", "점심")
        Meal.DINNER -> tr("Dinner", "저녁")
        Meal.SNACK -> tr("Snack", "간식")
    }

    fun source(source: FoodSource): String = when (source) {
        FoodSource.OPEN_FOOD_FACTS -> "Open Food Facts"
        FoodSource.LABEL_OCR -> tr("Read from the label", "라벨에서 읽음")
        FoodSource.MANUAL -> tr("You typed it", "직접 입력")
        FoodSource.HEALTH_CONNECT -> tr("From another app", "다른 앱에서 기록")
    }

    /** One line under a product, saying how much to trust it. */
    fun sourceNote(source: FoodSource, fromCache: Boolean): String = when (source) {
        FoodSource.OPEN_FOOD_FACTS ->
            if (fromCache) {
                tr(
                    "From your saved foods, originally Open Food Facts. Check it still matches the packet.",
                    "저장한 음식에서 가져왔어요(원래 출처는 Open Food Facts). 포장지와 여전히 맞는지 확인하세요.",
                )
            } else {
                tr(
                    "From Open Food Facts, a public database anyone can edit. Worth a glance at the packet.",
                    "누구나 고칠 수 있는 공개 데이터베이스 Open Food Facts에서 가져왔어요. 포장지를 한번 확인해 보세요.",
                )
            }
        FoodSource.LABEL_OCR -> tr(
            "Read off the label by the camera. Check the numbers before you trust the day's total.",
            "카메라로 라벨을 읽었어요. 하루 합계를 믿기 전에 숫자를 확인하세요.",
        )
        FoodSource.MANUAL -> tr("You entered these figures.", "직접 입력한 값이에요.")
        FoodSource.HEALTH_CONNECT -> tr(
            "Logged in another app and read from Health Connect.",
            "다른 앱에서 기록해 헬스 커넥트로 가져왔어요.",
        )
    }

    fun lookupFailure(reason: FoodLookupFailure): String = when (reason) {
        FoodLookupFailure.NOT_FOUND -> tr(
            "No match for that barcode. Point the camera at the nutrition panel instead, " +
                "or type it in — either way it is saved, so the next scan of this product is instant.",
            "이 바코드와 맞는 제품이 없어요. 대신 영양성분표를 비추거나 직접 입력하세요. 어느 쪽이든 저장되니 " +
                "다음에 이 제품을 스캔하면 바로 나와요.",
        )

        FoodLookupFailure.NETWORK_NOT_PERMITTED -> tr(
            "This product is not in your saved foods, and looking it up online is switched off.",
            "저장한 음식에 없는 제품이고, 온라인 검색이 꺼져 있어요.",
        )

        FoodLookupFailure.NETWORK_FAILED -> tr(
            "Could not reach the food database. The nutrition panel on the back works offline.",
            "음식 데이터베이스에 연결하지 못했어요. 뒷면 영양성분표 읽기는 오프라인에서도 돼요.",
        )
    }

    /** The one-time explanation, shown before a single byte leaves the phone. */
    val CONSENT_TITLE: String get() = tr("Look this up online?", "온라인에서 찾아볼까요?")

    val CONSENT_BODY: String
        get() = tr(
            "Pawse can ask Open Food Facts, a public food database, what a " +
                "barcode is. The request contains the barcode and nothing else: no account, no device " +
                "identifier, no history, and nothing about your health data — which never leaves this " +
                "phone either way.\n\n" +
                "Each product is fetched once and saved, so the same item resolves instantly and offline " +
                "afterwards. You can turn this off at any time, and reading the nutrition panel with the " +
                "camera works without it.",
            "Pawse는 공개 음식 데이터베이스인 Open Food Facts에 바코드가 무슨 제품인지 물어볼 수 있어요. " +
                "요청에는 바코드만 담겨요. 계정, 기기 식별자, 기록, 건강 데이터는 전혀 포함되지 않고, 건강 " +
                "데이터는 어떤 경우에도 이 휴대폰을 떠나지 않아요.\n\n" +
                "제품마다 한 번만 가져와 저장하므로, 같은 제품은 다음부터 오프라인에서도 바로 나와요. 언제든 끌 수 " +
                "있고, 카메라로 영양성분표를 읽는 기능은 이 설정 없이도 돼요.",
        )

    fun labelBasis(basis: LabelBasis, grams: Double?): String {
        val amount = grams?.let { " (${trim(it)} g)" } ?: ""
        return when (basis) {
            LabelBasis.PER_100G -> tr("The label states these per 100 g.", "라벨에 100 g 기준으로 적혀 있어요.")
            LabelBasis.PER_SERVING -> tr("The label states these per serving$amount.", "라벨에 1회 제공량$amount 기준으로 적혀 있어요.")
            LabelBasis.PER_PACKAGE -> tr("The label states these per package$amount.", "라벨에 1포장$amount 기준으로 적혀 있어요.")
            LabelBasis.UNKNOWN -> tr(
                "The label did not say what these figures are per, so we cannot work it out for you.",
                "라벨에 몇 g 기준인지 나와 있지 않아서 대신 계산할 수 없어요.",
            )
        }
    }

    fun labelWarning(warning: LabelWarning): String = when (warning) {
        LabelWarning.NO_BASIS -> tr("Tell us what the figures are per, below.", "아래에 몇 g 기준인지 알려 주세요.")
        LabelWarning.TOO_FEW_FIELDS -> tr(
            "Only a few figures came through. Move closer or steady the phone.",
            "읽힌 숫자가 몇 개 안 돼요. 더 가까이 대거나 휴대폰을 고정하세요.",
        )
        LabelWarning.ENERGY_DISAGREES_WITH_MACROS -> tr(
            "The energy figure does not match the protein, carbs and fat. One of them was probably misread.",
            "열량이 단백질·탄수화물·지방과 맞지 않아요. 그중 하나를 잘못 읽었을 가능성이 높아요.",
        )
    }

    /** "312 kcal · 12 g protein · 30 g carbs · 14 g fat" — only what is actually known. */
    fun summary(nutrients: Nutrients): String {
        val parts = buildList {
            nutrients.kcal?.let { add("${it.roundToInt()} kcal") }
            nutrients.proteinGrams?.let { add(tr("${trim(it)} g protein", "단백질 ${trim(it)} g")) }
            nutrients.carbsGrams?.let { add(tr("${trim(it)} g carbs", "탄수화물 ${trim(it)} g")) }
            nutrients.fatGrams?.let { add(tr("${trim(it)} g fat", "지방 ${trim(it)} g")) }
        }
        return if (parts.isEmpty()) tr("No figures", "값 없음") else parts.joinToString(" · ")
    }

    fun energy(nutrients: Nutrients): String =
        nutrients.kcal?.let { "${it.roundToInt()} kcal" } ?: "— kcal"

    fun grams(value: Double): String = "${trim(value)} g"

    /**
     * The day's total, with the caveat attached rather than beside it.
     *
     * A total that silently omits three items the user logged is worse than no
     * total, so the count of unknowns is part of the sentence.
     */
    fun totalsNote(totals: DayTotals): String {
        val n = totals.entryCount
        val missing = totals.entriesWithoutEnergy
        return when {
            n == 0 -> tr("Nothing logged yet.", "아직 기록이 없어요.")
            missing == 0 -> tr("$n items, all with figures.", "${n}개 항목, 모두 값이 있어요.")
            missing == 1 -> tr(
                "$n items · 1 has no energy figure, so this total is short.",
                "${n}개 항목 · 1개에 열량 값이 없어서 합계가 실제보다 적어요.",
            )
            else -> tr(
                "$n items · $missing have no energy figure, so this total is short.",
                "${n}개 항목 · ${missing}개에 열량 값이 없어서 합계가 실제보다 적어요.",
            )
        }
    }

    fun trim(value: Double): String =
        if (value % 1.0 == 0.0) {
            value.roundToInt().toString()
        } else {
            String.format(Locale.getDefault(), "%.1f", value)
        }
}
