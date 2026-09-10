package app.pawse.core.data.food

import app.pawse.core.data.food.resolver.BarcodeResolver
import app.pawse.core.data.food.resolver.FoodResolverChain
import app.pawse.core.data.food.resolver.ResolverOutcome
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BarcodesTest {

    @Test
    fun `a real EAN-13 check digit is accepted`() {
        // 8801043032117 — the check digit agrees with the other twelve.
        Barcodes.isPlausible("8801043032117") shouldBe true
    }

    @Test
    fun `one misread digit is rejected rather than looked up`() {
        // A camera that read twelve digits right and one wrong produces a perfectly
        // plausible number belonging to a different product. Better to refuse.
        Barcodes.isPlausible("8801043032118") shouldBe false
    }

    @Test
    fun `too short, too long, and not a number`() {
        Barcodes.isPlausible("12345") shouldBe false
        Barcodes.isPlausible("123456789012345678") shouldBe false
        Barcodes.isPlausible("abcdefgh") shouldBe false
    }

    @Test
    fun `whitespace and stray characters are stripped before checking`() {
        Barcodes.normalise(" 880 1043-032117 ") shouldBe "8801043032117"
        Barcodes.isPlausible(" 880 1043-032117 ") shouldBe true
    }
}

class FoodResolverChainTest {

    private val product = FoodProduct(
        id = 1,
        barcode = "8801043032117",
        name = "Test noodles",
        brand = null,
        per100g = Nutrients(kcal = 450.0),
        servingGrams = 120.0,
        servingLabel = null,
        source = FoodSource.OPEN_FOOD_FACTS,
        sourceDetail = null,
        fetchedAtEpochMs = 0L,
    )

    private fun resolver(outcome: ResolverOutcome, onCalled: () -> Unit = {}) = object : BarcodeResolver {
        override val name = "fake"
        override suspend fun resolve(barcode: String): ResolverOutcome {
            onCalled()
            return outcome
        }
    }

    @Test
    fun `the first link that knows the barcode wins and the rest are never asked`() = runTest {
        var networkAsked = false
        val chain = FoodResolverChain.of(
            resolver(ResolverOutcome.Resolved(FoodResolution(product, fromCache = true))),
            resolver(ResolverOutcome.Miss) { networkAsked = true },
        )

        val result = chain.resolve("8801043032117").shouldBeInstanceOf<FoodLookupResult.Found>()

        result.resolution.fromCache shouldBe true
        // The cache answering means no request is made at all. That is the property
        // that makes the network step genuinely optional rather than merely disableable.
        networkAsked shouldBe false
    }

    @Test
    fun `a skipped network step is reported as a choice, not as a miss`() = runTest {
        val chain = FoodResolverChain.of(
            resolver(ResolverOutcome.Miss),
            resolver(ResolverOutcome.Skipped(ResolverOutcome.SkipReason.NETWORK_NOT_PERMITTED)),
        )

        val result = chain.resolve("8801043032117").shouldBeInstanceOf<FoodLookupResult.NotFound>()

        // "We did not ask" and "we asked and it does not exist" lead to different
        // next steps, so they are different answers.
        result.reason shouldBe FoodLookupFailure.NETWORK_NOT_PERMITTED
    }

    @Test
    fun `a failed request is distinguished from an unknown product`() = runTest {
        val chain = FoodResolverChain.of(
            resolver(ResolverOutcome.Miss),
            resolver(ResolverOutcome.Failed(java.io.IOException("offline"))),
        )

        val result = chain.resolve("8801043032117").shouldBeInstanceOf<FoodLookupResult.NotFound>()
        result.reason shouldBe FoodLookupFailure.NETWORK_FAILED
    }

    @Test
    fun `every link missing is simply not found`() = runTest {
        val chain = FoodResolverChain.of(resolver(ResolverOutcome.Miss), resolver(ResolverOutcome.Miss))
        val result = chain.resolve("8801043032117").shouldBeInstanceOf<FoodLookupResult.NotFound>()
        result.reason shouldBe FoodLookupFailure.NOT_FOUND
    }

    @Test
    fun `an implausible barcode never reaches a resolver`() = runTest {
        var asked = false
        val chain = FoodResolverChain.of(
            resolver(ResolverOutcome.Resolved(FoodResolution(product, fromCache = true))) { asked = true },
        )

        chain.resolve("8801043032118").shouldBeInstanceOf<FoodLookupResult.NotFound>()
        asked shouldBe false
    }
}
