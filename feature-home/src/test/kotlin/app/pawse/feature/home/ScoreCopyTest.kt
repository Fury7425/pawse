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

    @Test
    fun `the warm line says what kind of night it was, and the evening says what kind of day`() {
        val high = score(ScoreType.RECOVERY, emptyList()).copy(band = Band.HIGH)
        ScoreCopy.warmLine(7, high, null, false, null, null) shouldBe "Morning. You bounced back well."
        ScoreCopy.warmLine(20, high, null, false, 38.0, 41.0) shouldBe "Evening. A solid day, about on target."
        ScoreCopy.warmLine(20, high, null, false, 6.0, 41.0) shouldBe "Evening. A lighter day than your target."
        ScoreCopy.warmLine(
            7,
            null,
            ScoreUnavailable(ScoreType.RECOVERY, "2026-03-02", ScoreUnavailable.Reason.NO_SLEEP_SESSION, emptyList()),
            false,
            null,
            null,
        ) shouldBe "Morning. No sleep showed up for last night."
        ScoreCopy.warmLine(7, null, null, true, null, null) shouldBe "Morning. Still getting to know your nights."
    }

    @Test
    fun `ranking respects which way is better, and refuses where neither is`() {
        // raw is 50 in the helper.
        ScoreCopy.rankText(contribution(Metric.HRV_RMSSD), listOf(40.0, 45.0, 55.0)) shouldBe
            "Better than 2 of 3 earlier nights"
        ScoreCopy.rankText(contribution(Metric.RESTING_HEART_RATE), listOf(40.0, 45.0, 55.0)) shouldBe
            "Better than 1 of 3 earlier nights"
        ScoreCopy.rankText(contribution(Metric.SKIN_TEMPERATURE), listOf(1.0)) shouldBe null
        ScoreCopy.rankText(contribution(Metric.HRV_RMSSD), emptyList()) shouldBe null
    }

    @Test
    fun `the target says which way the night moved it, on the user's scale`() {
        ScoreCopy.targetNote(41.0, 38.0, StrainScale.BEVEL_100) shouldBe "A good night moved it up from your usual 38"
        ScoreCopy.targetNote(30.0, 38.0, StrainScale.BEVEL_100) shouldContain "moved it down from your usual 38"
        ScoreCopy.targetNote(38.4, 38.0, StrainScale.BEVEL_100) shouldBe "Your usual for a day like this"
        ScoreCopy.targetNote(8.6, 8.0, StrainScale.WHOOP_21) shouldContain "usual 8.0"
        ScoreCopy.strainNumber(8.61, StrainScale.WHOOP_21) shouldBe "8.6"
    }

    @Test
    fun `a sleep term's sub-score is read back from its points, and time asleep is told against the need`() {
        val asleep = contribution(Metric.TIME_ASLEEP).copy(raw = 444.0, weight = 0.35, points = 30.8)
        ScoreCopy.subScore(asleep) shouldBe 88
        ScoreCopy.sleepNote(asleep, needHours = 7.0 + 50.0 / 60.0) shouldBe "26 min short of your need"
        ScoreCopy.sleepNote(contribution(Metric.REM_MINUTES, present = false), 8.0) shouldBe "Not recorded"
        ScoreCopy.subScore(contribution(Metric.REM_MINUTES, present = false)) shouldBe null
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
