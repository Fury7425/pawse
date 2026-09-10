package app.pawse.core.data.food.resolver

import app.pawse.core.data.food.FoodResolution

/**
 * One link in the resolver chain.
 *
 * A link says one of four things, and the distinction between the last three is
 * what lets the UI offer the right next step rather than a shrug:
 *
 *  - [ResolverOutcome.Resolved] — here is the product.
 *  - [ResolverOutcome.Miss] — I looked, I do not know this barcode.
 *  - [ResolverOutcome.Skipped] — I did not look, and here is why.
 *  - [ResolverOutcome.Failed] — I tried and could not.
 *
 * "Not found" and "you have not allowed me to ask" are different states, and a
 * user who sees the second should be offered the switch, not a dead end.
 */
interface BarcodeResolver {
    val name: String
    suspend fun resolve(barcode: String): ResolverOutcome
}

sealed interface ResolverOutcome {
    data class Resolved(val resolution: FoodResolution) : ResolverOutcome
    data object Miss : ResolverOutcome
    data class Skipped(val reason: SkipReason) : ResolverOutcome
    data class Failed(val cause: Throwable) : ResolverOutcome

    enum class SkipReason { NETWORK_NOT_PERMITTED, UNUSABLE_BARCODE }
}
