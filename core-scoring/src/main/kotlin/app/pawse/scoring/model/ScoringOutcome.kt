package app.pawse.scoring.model

/**
 * What a scorer returns.
 *
 * There is no third state and no sentinel integer. A scorer either produces a
 * [Score] — which always carries its own coverage and degraded flags — or it
 * refuses and says why. "Refuse rather than guess" is the whole reason
 * [ScoreUnavailable] exists: a number built on two of six inputs is worse than
 * an honest blank, and the UI needs the reason to write the empty state.
 */
sealed interface ScoringOutcome {

    data class Scored(val score: Score) : ScoringOutcome

    data class NotScored(val unavailable: ScoreUnavailable) : ScoringOutcome

    /** Convenience for tests and for call sites that already checked. */
    val scoreOrNull: Score?
        get() = (this as? Scored)?.score
}
