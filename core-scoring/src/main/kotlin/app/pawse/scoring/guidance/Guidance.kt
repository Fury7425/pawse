package app.pawse.scoring.guidance

import app.pawse.scoring.config.LoadConfig
import app.pawse.scoring.model.BodyBand
import app.pawse.scoring.model.BodyScore
import app.pawse.scoring.model.Guidance
import app.pawse.scoring.model.IllnessLevel

/**
 * The one place Body, Sleep and Load are combined: one sentence of guidance.
 *
 * | Body                | Illness | Form        | Guidance                                   |
 * |---------------------|---------|-------------|--------------------------------------------|
 * | any                 | alert   | any         | Take it easy today and watch for symptoms  |
 * | Strained 2+ nights  | none    | any         | An easy or rest day will pay off           |
 * | Below normal        | none    | any         | Keep it light; tonight's sleep is your lever|
 * | Typical             | none    | below -30%  | Body's fine but fatigue is building        |
 * | Typical or above    | none    | -30% or up  | Good day for hard training or study        |
 *
 * A single Strained night reads as Below normal: no alarm for one night.
 */
object GuidanceRules {

    fun pick(body: BodyScore?, strainedRun: Int, illness: IllnessLevel, form: Double?, config: LoadConfig): Guidance = when {
        illness == IllnessLevel.ALERT -> Guidance.WATCH_SYMPTOMS
        body == null -> Guidance.SETTLING
        body.band == BodyBand.STRAINED && strainedRun >= 2 -> Guidance.REST_DAY
        body.band == BodyBand.STRAINED || body.band == BodyBand.BELOW_NORMAL -> Guidance.KEEP_LIGHT
        form != null && form < config.heavyBelow -> Guidance.GO_MODERATE
        else -> Guidance.GOOD_DAY
    }
}
