package app.pawse.scoring.strain

import app.pawse.scoring.config.StrainConfig
import app.pawse.scoring.model.BiologicalSex
import app.pawse.scoring.model.LoadResult
import app.pawse.scoring.model.Provenance
import kotlin.math.exp

/**
 * Training-impulse models.
 *
 * These are the genuinely published equations under the whole load side of every
 * app in the reports. claude.md §10 calls Banister "the grandparent of most app
 * load scores", and it is the one place in this engine where the coefficients are
 * verbatim from a paper and must not be tuned.
 */
object Trimp {

    /**
     * Banister 1991:
     *   TRIMP = D x x x (a x e^(b x))
     *   x = fraction of heart-rate reserve
     *   men   a = 0.64, b = 1.92
     *   women a = 0.86, b = 1.67
     *
     * The exponential models the blood-lactate rise against %HRR, which is what
     * stops three easy hours from outweighing forty hard minutes.
     *
     * **The unspecified-sex case.** Banister published two curves and no third.
     * Health Connect has no sex record type, so "we do not know" is the common
     * state, and there are only bad options: pick one sex's curve for everybody
     * (wrong for half the users, silently), or take the midpoint (wrong for
     * everybody, but visibly and symmetrically). We take the midpoint and tag it
     * [Provenance.OUR_CHOICE], so the explainability sheet says the number is an
     * average of two published curves rather than either of them. The two curves
     * diverge by about 18% at x = 0.4 and narrow to under 5% at x = 1.0, so the
     * cost of not knowing is real at easy intensities and small at hard ones. It
     * is stated rather than hidden, and one settings tap removes it.
     */
    fun banister(
        durationMinutes: Double,
        reserveFraction: Double,
        sex: BiologicalSex,
        config: StrainConfig,
    ): LoadResult {
        val x = reserveFraction.coerceIn(0.0, 1.0)
        fun curve(a: Double, b: Double) = durationMinutes * x * (a * exp(b * x))

        return when (sex) {
            BiologicalSex.MALE -> LoadResult(
                load = curve(config.banisterMaleA, config.banisterMaleB),
                provenance = Provenance.PUBLISHED,
                citation = "Banister 1991 TRIMP, male coefficients " +
                    "${config.banisterMaleA} x e^(${config.banisterMaleB}x) (claude.md §10).",
            )

            BiologicalSex.FEMALE -> LoadResult(
                load = curve(config.banisterFemaleA, config.banisterFemaleB),
                provenance = Provenance.PUBLISHED,
                citation = "Banister 1991 TRIMP, female coefficients " +
                    "${config.banisterFemaleA} x e^(${config.banisterFemaleB}x) (claude.md §10).",
            )

            BiologicalSex.UNSPECIFIED -> LoadResult(
                load = (
                    curve(config.banisterMaleA, config.banisterMaleB) +
                        curve(config.banisterFemaleA, config.banisterFemaleB)
                    ) / 2.0,
                provenance = Provenance.OUR_CHOICE,
                citation = "Midpoint of Banister's two published sex-specific curves, " +
                    "because you have not told us which to use. Enter it in settings " +
                    "to get the published coefficients instead.",
            )
        }
    }

    /**
     * Edwards' five-zone summed TRIMP: minutes in each %HRmax band times the
     * band's multiplier, 1 through 5. PUBLISHED (claude.md §10).
     *
     * Sex-neutral, which makes it the better model whenever a writer app gave us a
     * heart-rate series to bin. It needs more data than Banister and is therefore
     * the less available of the two, not the less preferred.
     */
    fun edwards(zoneMinutes: Map<Int, Double>, config: StrainConfig): LoadResult {
        val load = zoneMinutes.entries.sumOf { (zone, minutes) ->
            (config.edwardsZoneWeights[zone] ?: 0.0) * minutes.coerceAtLeast(0.0)
        }
        return LoadResult(
            load = load,
            provenance = Provenance.PUBLISHED,
            citation = "Edwards five-zone TRIMP, multipliers " +
                config.edwardsZoneWeights.entries.sortedBy { it.key }
                    .joinToString("/") { it.value.toInt().toString() } +
                " by %HRmax band (claude.md §10). Sex-neutral.",
        )
    }

    /**
     * Saturating transform onto the canonical 0-100 strain scale:
     *   Strain = 100 x (1 - e^(-L / k))
     *
     * Written down identically by grok.txt §11 and gemini.txt for this whole
     * family of apps, with Whoop at S_max 21 and Bevel at 100. We compute on 100
     * always and convert only for display, because the scale is a display toggle
     * and history should not change shape when a user flips it.
     *
     * k is [Provenance.OUR_CHOICE] and is the single most consequential tunable
     * number in the app: it alone decides what "a hard day" reads as. No vendor
     * publishes it.
     *
     * Non-additivity across sessions falls out of the curve. There is no special
     * case anywhere for two workouts in one day, and adding one would be a bug.
     */
    fun saturate(totalLoad: Double, config: StrainConfig): Double =
        CANONICAL_MAX * (1.0 - exp(-totalLoad.coerceAtLeast(0.0) / config.saturationK))

    /** Inverse of [saturate]. Used to turn a target strain back into a target load. */
    fun loadForStrain(strain: Double, config: StrainConfig): Double {
        val fraction = (strain / CANONICAL_MAX).coerceIn(0.0, 0.999999)
        return -config.saturationK * kotlin.math.ln(1.0 - fraction)
    }

    /** The canonical internal scale. Display conversion lives in the scorer. */
    const val CANONICAL_MAX: Double = 100.0
}
