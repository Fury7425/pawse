package app.pawse.core.data.food.resolver

import app.pawse.core.data.db.FoodProductDao
import app.pawse.core.data.food.Barcodes
import app.pawse.core.data.food.FoodLookupFailure
import app.pawse.core.data.food.FoodLookupResult
import app.pawse.core.data.food.FoodPreferences
import app.pawse.core.data.food.FoodProductMapper
import app.pawse.core.data.food.FoodResolution
import app.pawse.core.data.food.net.OpenFoodFactsApi
import app.pawse.core.data.food.net.OpenFoodFactsMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * The local library, and the first thing asked every time.
 *
 * A cache hit is what makes the rest of the chain optional. The foods a person
 * actually eats are a short, repetitive list, so after the first scan of each one
 * the shop aisle works offline, instantly, and without another word to anyone.
 */
@Singleton
class CachedProductResolver @Inject constructor(
    private val dao: FoodProductDao,
) : BarcodeResolver {

    override val name: String = "Your scanned foods"

    override suspend fun resolve(barcode: String): ResolverOutcome = withContext(Dispatchers.IO) {
        val cached = dao.byBarcode(barcode) ?: return@withContext ResolverOutcome.Miss
        ResolverOutcome.Resolved(
            FoodResolution(product = FoodProductMapper.toProduct(cached), fromCache = true),
        )
    }
}

/**
 * Open Food Facts, asked only with permission.
 *
 * The consent check is inside the resolver rather than at the call site on
 * purpose: there is exactly one place in this codebase that can make a network
 * request, and it reads the switch itself. A future screen that forgets to check
 * still cannot cause a lookup the user did not agree to.
 *
 * A successful lookup is written to the cache immediately, so the same product is
 * never fetched twice.
 */
@Singleton
class OpenFoodFactsResolver @Inject constructor(
    private val api: Provider<OpenFoodFactsApi>,
    private val preferences: FoodPreferences,
    private val dao: FoodProductDao,
) : BarcodeResolver {

    override val name: String = "Open Food Facts"

    override suspend fun resolve(barcode: String): ResolverOutcome = withContext(Dispatchers.IO) {
        if (!preferences.isNetworkLookupEnabled()) {
            return@withContext ResolverOutcome.Skipped(ResolverOutcome.SkipReason.NETWORK_NOT_PERMITTED)
        }

        try {
            val response = api.get().product(barcode)
            val product = OpenFoodFactsMapper.toProduct(
                response = response,
                barcode = barcode,
                fetchedAtEpochMs = System.currentTimeMillis(),
            )
            if (product == null) {
                ResolverOutcome.Miss
            } else {
                val id = dao.upsert(FoodProductMapper.toEntity(product))
                ResolverOutcome.Resolved(FoodResolution(product = product.copy(id = id), fromCache = false))
            }
        } catch (cancellation: CancellationException) {
            // The user walked away from the scanner. Not a lookup failure.
            throw cancellation
        } catch (failure: Exception) {
            ResolverOutcome.Failed(failure)
        }
    }
}

/**
 * The chain, in order: what you already have, then what the world knows.
 *
 * Order is the design. Cache first means the common case never touches the
 * network; network last means it is skippable without breaking anything above it.
 * Photographing the label and typing it in are not links in this chain — they
 * cannot answer "what is barcode 8801234567890", they are what the user does when
 * the chain comes back empty, and the failure reason is what tells the UI to offer
 * them.
 */
@Singleton
class FoodResolverChain private constructor(
    private val resolvers: List<BarcodeResolver>,
) {

    @Inject
    constructor(
        cached: CachedProductResolver,
        openFoodFacts: OpenFoodFactsResolver,
    ) : this(listOf(cached, openFoodFacts))

    suspend fun resolve(rawBarcode: String): FoodLookupResult {
        val barcode = Barcodes.normalise(rawBarcode)
        if (!Barcodes.isPlausible(barcode)) {
            // A check digit that does not agree means the camera misread a digit,
            // and a misread barcode is a different product, not this one.
            return FoodLookupResult.NotFound(barcode, FoodLookupFailure.NOT_FOUND)
        }

        var skippedForConsent = false
        var failure: Throwable? = null

        for (resolver in resolvers) {
            when (val outcome = resolver.resolve(barcode)) {
                is ResolverOutcome.Resolved -> return FoodLookupResult.Found(outcome.resolution)
                is ResolverOutcome.Miss -> Unit
                is ResolverOutcome.Skipped ->
                    if (outcome.reason == ResolverOutcome.SkipReason.NETWORK_NOT_PERMITTED) {
                        skippedForConsent = true
                    }
                is ResolverOutcome.Failed -> failure = outcome.cause
            }
        }

        val reason = when {
            skippedForConsent -> FoodLookupFailure.NETWORK_NOT_PERMITTED
            failure != null -> FoodLookupFailure.NETWORK_FAILED
            else -> FoodLookupFailure.NOT_FOUND
        }
        return FoodLookupResult.NotFound(barcode, reason)
    }

    internal companion object {
        /** Chain over arbitrary links, so the ordering rules can be tested on fakes. */
        internal fun of(vararg resolvers: BarcodeResolver): FoodResolverChain =
            FoodResolverChain(resolvers.toList())
    }
}
