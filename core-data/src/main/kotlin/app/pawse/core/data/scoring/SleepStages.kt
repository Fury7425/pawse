package app.pawse.core.data.scoring

import androidx.health.connect.client.records.SleepSessionRecord

/**
 * Health Connect's stage integers, reduced to the four states the engine reasons
 * about.
 *
 * Two of Health Connect's eight values need a decision rather than a mapping:
 *
 *  - `AWAKE_IN_BED` is awake, but it is the only stage that tells us the user was
 *    lying there trying. That is exactly what sleep latency is, so it is kept
 *    distinct from plain `AWAKE` right up to the point where latency is measured.
 *  - `SLEEPING` is "asleep, stage unknown". Several writer apps emit nothing else.
 *    It counts towards time asleep and towards nothing else, which is why a night
 *    made only of `SLEEPING` blocks scores under Apple's stage-free profile.
 */
enum class SleepStageKind {
    AWAKE,
    AWAKE_IN_BED,
    OUT_OF_BED,
    LIGHT,
    DEEP,
    REM,
    ASLEEP_UNSPECIFIED,
    UNKNOWN,
    ;

    val isAsleep: Boolean
        get() = this == LIGHT || this == DEEP || this == REM || this == ASLEEP_UNSPECIFIED

    val isAwake: Boolean
        get() = this == AWAKE || this == AWAKE_IN_BED || this == OUT_OF_BED

    /**
     * Whether this stage carries staging information at all. A session whose only
     * blocks are [ASLEEP_UNSPECIFIED] or [UNKNOWN] has an envelope, not stages, and
     * must not select the Oura-recovered profile.
     */
    val isStaged: Boolean
        get() = this == LIGHT || this == DEEP || this == REM
}

object SleepStages {

    fun of(healthConnectStage: Int): SleepStageKind = when (healthConnectStage) {
        SleepSessionRecord.STAGE_TYPE_AWAKE -> SleepStageKind.AWAKE
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> SleepStageKind.AWAKE_IN_BED
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> SleepStageKind.OUT_OF_BED
        SleepSessionRecord.STAGE_TYPE_LIGHT -> SleepStageKind.LIGHT
        SleepSessionRecord.STAGE_TYPE_DEEP -> SleepStageKind.DEEP
        SleepSessionRecord.STAGE_TYPE_REM -> SleepStageKind.REM
        SleepSessionRecord.STAGE_TYPE_SLEEPING -> SleepStageKind.ASLEEP_UNSPECIFIED
        else -> SleepStageKind.UNKNOWN
    }
}
