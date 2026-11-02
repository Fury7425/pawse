package app.pawse.feature.home

import app.pawse.scoring.model.ScoreType

/**
 * Route names, owned by the feature rather than by the host.
 *
 * The host builds the graph; only this file knows what the arguments mean. An
 * unknown or malformed score type resolves to Body rather than crashing —
 * deep links and process death both produce strings this app did not write.
 */
object HomeDestinations {

    const val HOME = "home"

    const val WEIGH_IN = "weigh-in"

    const val EXPLAIN_ARG = "scoreType"

    /** The day the Scores strip had picked, as days before the newest. The detail opens on it. */
    const val EXPLAIN_BACK_ARG = "back"
    const val EXPLAIN = "explain/{$EXPLAIN_ARG}?$EXPLAIN_BACK_ARG={$EXPLAIN_BACK_ARG}"

    fun explain(type: ScoreType, back: Int = 0): String = "explain/${type.name}?$EXPLAIN_BACK_ARG=$back"

    fun scoreTypeOf(raw: String?): ScoreType =
        raw?.let { runCatching { ScoreType.valueOf(it) }.getOrNull() } ?: ScoreType.BODY
}
