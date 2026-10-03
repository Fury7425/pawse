package app.pawse.feature.home

import app.pawse.scoring.model.ScoreType

/**
 * Route names, owned by the feature rather than by the host.
 *
 * The host builds the graph; only this file knows what the arguments mean. An
 * unknown or malformed score type resolves to Recovery rather than crashing —
 * deep links and process death both produce strings this app did not write.
 */
object HomeDestinations {

    const val HOME = "home"

    const val EXPLAIN_ARG = "scoreType"
    const val EXPLAIN = "explain/{$EXPLAIN_ARG}"

    fun explain(type: ScoreType): String = "explain/${type.name}"

    fun scoreTypeOf(raw: String?): ScoreType =
        raw?.let { runCatching { ScoreType.valueOf(it) }.getOrNull() } ?: ScoreType.RECOVERY
}
