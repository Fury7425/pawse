package app.pawse.core.data.scoring

import app.pawse.scoring.config.ScoringConfig
import app.pawse.scoring.model.Band
import app.pawse.scoring.model.Contribution
import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.Provenance
import app.pawse.scoring.model.Score
import app.pawse.scoring.model.ScoreType
import io.kotest.matchers.shouldBe
import org.junit.Test

class ScoreMapperTest {

    private val score = Score(
        type = ScoreType.RECOVERY,
        date = "2026-03-02",
        value = 68,
        band = Band.HIGH,
        contributions = listOf(
            Contribution(
                metric = Metric.HRV_RMSSD,
                raw = 61.5,
                baselineMean = 55.2,
                baselineSd = 6.1,
                baselineWindowDays = 60,
                baselineSampleCount = 43,
                z = 1.03,
                weight = 0.645,
                nominalWeight = 0.60,
                points = 7.4,
                provenance = Provenance.OUR_CHOICE,
                citation = "Our default. Range 0.5-0.8 supported by grok.txt §11.",
                present = true,
            ),
            Contribution(
                metric = Metric.SKIN_TEMPERATURE,
                raw = null,
                baselineMean = null,
                baselineSd = null,
                baselineWindowDays = 60,
                baselineSampleCount = 0,
                z = null,
                weight = 0.0,
                nominalWeight = 0.0,
                points = 0.0,
                provenance = Provenance.OUR_CHOICE,
                citation = "Bounded flag, not a continuous term.",
                present = false,
                anomalyFlag = true,
            ),
        ),
        dataCoverage = 0.93f,
        degraded = false,
        warmingUp = false,
        scoringVersion = ScoringConfig.SCORING_VERSION,
        configHash = ScoringConfig.DEFAULT.configHash,
    )

    @Test
    fun `a score survives the round trip through storage`() {
        val restored = ScoreMapper.toScore(ScoreMapper.toEntity(score, computedAtEpochMs = 1_772_000_000_000L))

        restored shouldBe score
    }

    @Test
    fun `the version and weight hash travel with the row`() {
        val entity = ScoreMapper.toEntity(score, computedAtEpochMs = 1L)

        // These two are half the score table's primary key: retuning a weight writes
        // a new row rather than rewriting an old one.
        entity.scoringVersion shouldBe ScoringConfig.SCORING_VERSION
        entity.configHash shouldBe ScoringConfig.DEFAULT.configHash
    }

    @Test
    fun `an unreadable contributions blob degrades to an empty list, not a crash`() {
        val entity = ScoreMapper.toEntity(score, computedAtEpochMs = 1L).copy(contributionsJson = "{ not json")

        // A stored score is history. Failing to render it because the shape moved
        // would be the same sin as rewriting it.
        ScoreMapper.toScore(entity).contributions shouldBe emptyList()
    }
}
