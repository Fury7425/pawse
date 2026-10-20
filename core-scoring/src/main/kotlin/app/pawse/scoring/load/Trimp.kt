package app.pawse.scoring.load

import app.pawse.scoring.config.LoadConfig
import app.pawse.scoring.model.BiologicalSex
import app.pawse.scoring.model.LoadResult
import app.pawse.scoring.model.Provenance
import app.pawse.scoring.tr
import kotlin.math.exp

/**
 * Training-impulse models: the genuinely published equations under every load
 * score. Banister's coefficients are verbatim from the paper and are not tuned.
 */
object Trimp {

    /**
     * Banister 1991: TRIMP = D x HRr x a e^(b HRr), HRr = fraction of heart-rate
     * reserve; men a = 0.64, b = 1.92; women a = 0.86, b = 1.67.
     *
     * An unspecified sex takes the midpoint of the two published curves and says
     * so, rather than scoring everyone with one sex's curve.
     */
    fun banister(
        durationMinutes: Double,
        reserveFraction: Double,
        sex: BiologicalSex,
        config: LoadConfig,
    ): LoadResult {
        val x = reserveFraction.coerceIn(0.0, 1.0)
        fun curve(a: Double, b: Double) = durationMinutes * x * (a * exp(b * x))

        return when (sex) {
            BiologicalSex.MALE -> LoadResult(
                load = curve(config.banisterMaleA, config.banisterMaleB),
                provenance = Provenance.PUBLISHED,
                citation = "Banister 1991 TRIMP, ${config.banisterMaleA} x e^(${config.banisterMaleB}x).",
            )

            BiologicalSex.FEMALE -> LoadResult(
                load = curve(config.banisterFemaleA, config.banisterFemaleB),
                provenance = Provenance.PUBLISHED,
                citation = "Banister 1991 TRIMP, ${config.banisterFemaleA} x e^(${config.banisterFemaleB}x).",
            )

            BiologicalSex.UNSPECIFIED -> LoadResult(
                load = (
                    curve(config.banisterMaleA, config.banisterMaleB) +
                        curve(config.banisterFemaleA, config.banisterFemaleB)
                    ) / 2.0,
                provenance = Provenance.OUR_CHOICE,
                citation = tr(
                    "Midpoint of Banister's two published curves, because you have not " +
                        "said which to use. Add it in settings for the published coefficients.",
                    "어느 곡선을 쓸지 정하지 않아서 Banister가 발표한 두 곡선의 중간값을 썼어요. " +
                        "설정에서 성별을 입력하면 논문의 계수를 그대로 써요.",
                ),
            )
        }
    }

    /** Edwards' five-zone summed TRIMP: minutes per %HRmax band times 1..5. Published. */
    fun edwards(zoneMinutes: Map<Int, Double>, config: LoadConfig): LoadResult {
        val load = zoneMinutes.entries.sumOf { (zone, minutes) ->
            (config.edwardsZoneWeights[zone] ?: 0.0) * minutes.coerceAtLeast(0.0)
        }
        return LoadResult(
            load = load,
            provenance = Provenance.PUBLISHED,
            citation = tr("Edwards five-zone TRIMP by %HRmax band. Sex-neutral.", "%HRmax 구간별 Edwards 5구간 TRIMP. 성별과 무관해요."),
        )
    }
}
