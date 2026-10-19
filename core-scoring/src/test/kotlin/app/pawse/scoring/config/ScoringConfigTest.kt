package app.pawse.scoring.config

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import kotlin.math.abs

class ScoringConfigTest {

    @Test
    fun `banister coefficients are the published sex-specific pair`() {
        val l = ScoringConfig.DEFAULT.load
        l.banisterMaleA shouldBe 0.64
        l.banisterMaleB shouldBe 1.92
        l.banisterFemaleA shouldBe 0.86
        l.banisterFemaleB shouldBe 1.67
    }

    @Test
    fun `the optional sleep score weights form a complete unit`() {
        val s = ScoringConfig.DEFAULT.sleep
        check(abs(s.scoreDurationWeight + s.scoreRegularityWeight + s.scoreContinuityWeight - 1.0) < 1e-9)
    }

    @Test
    fun `the default HRV weight sits inside the calibration clip`() {
        val c = ScoringConfig.DEFAULT
        check(c.body.hrvWeight in c.learning.wMin..c.learning.wMax)
    }

    @Test
    fun `config hash is stable across round trips and changes when a default changes`() {
        val a = ScoringConfig.DEFAULT
        ScoringConfig.fromJson(ScoringConfig.toJson(a)).configHash shouldBe a.configHash
        a.copy(body = a.body.copy(hrvWeight = 0.55)).configHash shouldNotBe a.configHash
    }

    @Test
    fun `baseline window is constrained to the range the reports cover`() {
        BaselineConfig(windowDays = 60)
        runCatching { BaselineConfig(windowDays = 7) }.isFailure shouldBe true
        runCatching { BaselineConfig(windowDays = 120) }.isFailure shouldBe true
    }
}
