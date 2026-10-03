package app.pawse.core.data.scoring

import app.pawse.core.data.db.ScoreEntity
import app.pawse.scoring.model.Band
import app.pawse.scoring.model.Contribution
import app.pawse.scoring.model.Score
import app.pawse.scoring.model.ScoreType
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * [Score] to [ScoreEntity] and back.
 *
 * The contributions travel as JSON in a single column rather than as a child
 * table, because they are read as a unit, written as a unit, and never queried
 * across. The explainability screen deserialises exactly one row.
 *
 * `ignoreUnknownKeys` is on for the read path so that a row written by an older
 * build with a field this build no longer has still opens. A stored score is
 * history; failing to render it because the shape moved would be the same sin as
 * rewriting it.
 */
object ScoreMapper {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val contributionsSerializer = ListSerializer(Contribution.serializer())

    fun toEntity(score: Score, computedAtEpochMs: Long): ScoreEntity = ScoreEntity(
        type = score.type.name,
        localDate = score.date,
        value = score.value,
        band = score.band.name,
        dataCoverage = score.dataCoverage,
        degraded = score.degraded,
        warmingUp = score.warmingUp,
        scoringVersion = score.scoringVersion,
        configHash = score.configHash,
        contributionsJson = json.encodeToString(contributionsSerializer, score.contributions),
        computedAtEpochMs = computedAtEpochMs,
    )

    fun toScore(entity: ScoreEntity): Score = Score(
        type = ScoreType.valueOf(entity.type),
        date = entity.localDate,
        value = entity.value,
        band = Band.valueOf(entity.band),
        contributions = runCatching {
            json.decodeFromString(contributionsSerializer, entity.contributionsJson)
        }.getOrDefault(emptyList()),
        dataCoverage = entity.dataCoverage,
        degraded = entity.degraded,
        warmingUp = entity.warmingUp,
        scoringVersion = entity.scoringVersion,
        configHash = entity.configHash,
    )
}
