package app.pawse.feature.home

import app.pawse.scoring.config.StrainScale
import app.pawse.scoring.model.Band
import app.pawse.scoring.model.Contribution
import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.Provenance
import app.pawse.scoring.model.Score
import app.pawse.scoring.model.ScoreType
import app.pawse.scoring.model.ScoreUnavailable
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test

/**
 * The app's voice is a feature, so it gets tests.
 *
 * Two of these are rules rather than preferences: strain must not borrow
 * Recovery's vocabulary, and nothing anywhere may imply a diagnosis.
 */
class ScoreCopyTest {

    private fun contribution(
        metric: Metric,
        present: Boolean = true,
        anomalyFlag: Boolean = false,
    ) = Contribution(
        metric = metric,
        raw = if (present) 50.0 else null,
        baselineMean = if (present) 48.0 else null,
        baselineSd = if (present) 4.0 else null,
        baselineWindowDays = 60,
        baselineSampleCount = 30,
        z = if (present) 0.5 else null,
        weight = 0.6,
        nominalWeight = 0.6,
        points = 3.0,
        provenance = Provenance.OUR_CHOICE,
        citation = "Our default.",
        present = present,
        anomalyFlag = anomalyFlag,
    )

    private fun score(type: ScoreType, contributions: List<Contribution>) = Score(
        type = type,
        date = "2026-03-02",
        value = 62,
        band = Band.MODERATE,
        contributions = contributions,
        dataCoverage = 0.86f,
        degraded = false,
        warmingUp = false,
        scoringVersion = "1.0.0",
        configHash = "abc",
    )

    @Test
    fun `strain does not borrow Recovery's vocabulary`() {
        // Its bands are magnitude, not verdict: a hard day is a lot of load, which
        // is neither good news nor bad.
        ScoreCopy.bandWord(ScoreType.STRAIN, Band.HIGH) shouldBe "Hard"
        ScoreCopy.bandWord(ScoreType.RECOVERY, Band.HIGH) shouldBe "High"
        ScoreCopy.bandWord(ScoreType.STRAIN, Band.HIGH) shouldNotBe
            ScoreCopy.bandWord(ScoreType.RECOVERY, Band.HIGH)
    }

    @Test
    fun `the load ratio's good state is its middle, not its top`() {
        ScoreCopy.bandWord(ScoreType.LOAD_RATIO, Band.HIGH) shouldBe "Steady"
        ScoreCopy.bandWord(ScoreType.LOAD_RATIO, Band.LOW) shouldBe "Ramping fast"
    }

    @Test
    fun `coverage counts inputs and weight, and ignores the flags`() {
        val built = score(
            ScoreType.RECOVERY,
            listOf(
                contribution(Metric.HRV_RMSSD),
                contribution(Metric.RESTING_HEART_RATE),
                contribution(Metric.RESPIRATORY_RATE, present = false),
                contribution(Metric.SKIN_TEMPERATURE, anomalyFlag = true),
            ),
        )

        ScoreCopy.coverageLine(built) shouldBe "2 of 3 inputs · 86% of the weight"
    }

    @Test
    fun `an excursion is described as unusual, never as illness`() {
        val built = score(
            ScoreType.RECOVERY,
            listOf(
                contribution(Metric.HRV_RMSSD),
                contribution(Metric.SKIN_TEMPERATURE, anomalyFlag = true).copy(points = -6.0),
            ),
        )

        val sentence = ScoreCopy.sentence(
            type = ScoreType.RECOVERY,
            score = built,
            snapshot = snapshot(),
        )

        sentence shouldContain "outside your usual range"
        sentence shouldNotContain "illness"
        sentence shouldNotContain "sick"
        sentence shouldNotContain "fever"
    }

    @Test
    fun `every refusal explains itself`() {
        ScoreUnavailable.Reason.entries.forEach { reason ->
            val sentence = ScoreCopy.unavailableSentence(
                type = ScoreType.RECOVERY,
                unavailable = ScoreUnavailable(
                    type = ScoreType.RECOVERY,
                    date = "2026-03-02",
                    reason = reason,
                    missing = listOf(Metric.HRV_RMSSD),
                ),
            )
            sentence.isNotBlank() shouldBe true
        }
    }

    @Test
    fun `durations read as hours and minutes, not as decimals`() {
        ScoreCopy.formatMinutes(465.0) shouldBe "7 h 45"
        ScoreCopy.formatMinutes(60.0) shouldBe "1 h 00"
        ScoreCopy.formatHours(8.5) shouldBe "8 h 30"
    }

    @Test
    fun `the same strain is one number on two scales`() {
        ScoreCopy.formatStrain(48.0, StrainScale.BEVEL_100) shouldBe "48"
        ScoreCopy.formatStrain(10.1, StrainScale.WHOOP_21) shouldBe "10.1 / 21"
    }

    private fun snapshot() = HomeSnapshot(
        date = java.time.LocalDate.parse("2026-03-02"),
        scores = emptyMap(),
        unavailable = emptyMap(),
        sleepNeedHours = 8.0,
        sleepNeedBaselineHours = 8.0,
        sleepNeedStrainAdderHours = 0.0,
        sleepNeedDebtAdderHours = 0.0,
        sleepNeedNapCreditHours = 0.0,
        strainDisplay = null,
        strainTargetDisplay = null,
        strainTypicalDisplay = null,
        strainScale = StrainScale.BEVEL_100,
        sessionLoads = emptyList(),
        profile = app.pawse.scoring.model.UserProfile(),
        needsProfile = true,
        nightsInWindow = 30,
        lastSyncEpochMs = 0L,
    )
}
