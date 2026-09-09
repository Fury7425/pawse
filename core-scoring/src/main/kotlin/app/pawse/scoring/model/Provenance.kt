package app.pawse.scoring.model

/**
 * Where a scoring coefficient came from.
 *
 * Ground rule 1 (build spec): every research report converges on the same point —
 * vendors publish *which* signals feed a score and *which direction* each pushes,
 * but never the weight table or the scale mapping. So no invented weight may ever
 * be presented as a vendor formula. Every parameter carries one of these tags and
 * the tag is surfaced in the UI next to the number it produced.
 */
enum class Provenance {
    /**
     * Published in a white paper, patent, or peer-reviewed paper, verbatim.
     * Examples shipped in this app:
     *  - Banister TRIMP sex-specific coefficients (Banister 1991; claude.md §10)
     *  - Uth-Sorensen-Overgaard-Pedersen VO2max = 15 x HRmax/HRrest
     *    (Eur J Appl Physiol 2004; 91:111-115; claude.md §10)
     *  - Apple Sleep Score 50/30/20 point split (grok.txt §4; claude.md §3)
     *  - Whoop Sleep Need logistic f1(i) = 1.7 / (1 + e^((17-i)/3.5))
     *    (patent US 9,538,923; claude.md §2)
     */
    PUBLISHED,

    /**
     * Recovered by regression or firmware analysis against the vendor's own
     * exported sub-scores, reproducing the official number at high R^2.
     * Shipped example: Oura Sleep Score contributor weights 35/15/10x5, which
     * reconstruct the official score at R^2 ~= 0.99 (grok.txt §8; claude.md §6).
     */
    RECOVERED,

    /**
     * Not published, but constrained by vendor statements about relative
     * importance or by independent variance analysis. Example: HRV explaining
     * ~56-60% of Whoop Recovery daily variance (gemini.txt, "Comparative
     * Analysis of Recovery Score Formulations").
     */
    INFERRED,

    /**
     * Our own default. Chosen inside a range the reports support, but not
     * attributable to any vendor. Every Recovery weight this app ships is
     * OUR_CHOICE — see [app.pawse.scoring.config.ScoringConfig].
     */
    OUR_CHOICE,
}

/**
 * Provenance plus the report and section it is traceable to.
 * [citation] is rendered verbatim in the explainability sheet, so keep it
 * short enough to read on a phone and specific enough to find again.
 */
data class Sourced(
    val provenance: Provenance,
    val citation: String,
)
