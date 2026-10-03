package app.pawse.core.data.scoring

import app.pawse.scoring.model.BiologicalSex
import app.pawse.scoring.model.DayActivity
import app.pawse.scoring.model.Metric
import app.pawse.scoring.model.ScoreUnavailable
import app.pawse.scoring.model.ScoringOutcome
import app.pawse.scoring.model.UserProfile
import app.pawse.scoring.model.WorkoutSample
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Test
import java.time.LocalDate

/**
 * The wiring test.
 *
 * `:core-scoring` already proves the arithmetic against synthetic fixtures. What
 * this proves is the thing only the pipeline can get wrong: that the five scorers
 * are run in the order their dependencies demand, that last night's Sleep Score
 * actually reaches Recovery as an input, that the Energy Bank is chained rather
 * than reseeded, and that a day's strain reaches tomorrow's sleep need.
 */
class ScoringPipelineTest {

    private val start = LocalDate.parse("2026-01-01")
    private val profile = UserProfile(ageYears = 35, biologicalSex = BiologicalSex.MALE)

    private fun bundle(
        date: LocalDate,
        hrv: Double = 55.0,
        restingHeartRate: Double = 50.0,
        asleepMinutes: Double = 465.0,
        workouts: List<WorkoutSample> = emptyList(),
    ): DayBundle {
        val night = NightSummary(
            date = date.toString(),
            hasSession = true,
            hasStages = true,
            inBedMinutes = asleepMinutes + 20.0,
            asleepMinutes = asleepMinutes,
            remMinutes = asleepMinutes * 0.22,
            deepMinutes = asleepMinutes * 0.18,
            wasoMinutes = 12.0,
            awakenings = 2,
            interruptions = 1,
            latencyMinutes = 12.0,
            bedtimeMinutes = 23 * 60.0,
            midpointMinutes = 3 * 60.0,
            napMinutes = 0,
            timezoneShiftMinutes = 0,
            startEpochMs = 0L,
            endEpochMs = 0L,
        )
        return DayBundle(
            date = date,
            values = mapOf(
                Metric.HRV_RMSSD to hrv,
                Metric.RESTING_HEART_RATE to restingHeartRate,
                Metric.RESPIRATORY_RATE to 14.0,
                Metric.TIME_ASLEEP to asleepMinutes,
                Metric.SLEEP_EFFICIENCY to 100.0 * asleepMinutes / (asleepMinutes + 20.0),
                Metric.REM_MINUTES to asleepMinutes * 0.22,
                Metric.DEEP_MINUTES to asleepMinutes * 0.18,
                Metric.SLEEP_LATENCY to 12.0,
                Metric.RESTFULNESS to 85.0,
                Metric.SLEEP_TIMING to 100.0,
            ),
            night = night,
            activity = DayActivity(
                date = date.toString(),
                workouts = workouts,
                wakingHours = 16.0,
            ),
        )
    }

    /**
     * A month of steady nights, with one day optionally altered.
     *
     * The ripple is deterministic and small: without some spread there is no
     * personal sigma, and without a sigma there is no z-score for any term to
     * carry — which is a real state the engine handles by dropping the term, and
     * not the state these tests are about.
     */
    private fun steadyMonth(
        days: Int = 30,
        modifyIndex: Int = days - 1,
        transform: (DayBundle) -> DayBundle = { it },
    ): List<DayBundle> = (0 until days).map { offset ->
        val ripple = if (offset % 2 == 0) 1.5 else -1.5
        val day = bundle(
            date = start.plusDays(offset.toLong()),
            hrv = 55.0 + ripple,
            restingHeartRate = 50.0 + ripple / 3.0,
            // Sleep varies too, so that the Sleep Score has a spread of its own and
            // can be a real weighted term in Recovery rather than a dropped one.
            asleepMinutes = 465.0 + ripple * 20.0,
        )
        if (offset == modifyIndex) transform(day) else day
    }

    @Test
    fun `the first night refuses rather than scoring against no history`() {
        val days = ScoringPipeline().run(listOf(bundle(start)), profile)

        val recovery = days.single().recovery.shouldBeInstanceOf<ScoringOutcome.NotScored>()
        recovery.unavailable.reason shouldBe ScoreUnavailable.Reason.BASELINE_WARMUP
    }

    @Test
    fun `last night's sleep score reaches Recovery as a weighted input`() {
        val days = ScoringPipeline().run(steadyMonth(), profile)
        val last = days.last()

        last.sleep.scoreOrNull.shouldNotBeNull()
        val recovery = last.recovery.scoreOrNull.shouldNotBeNull()

        val sleepTerm = recovery.contributions.single { it.metric == Metric.SLEEP_SCORE }
        sleepTerm.present shouldBe true
        sleepTerm.raw!!.toInt() shouldBe last.sleep.scoreOrNull!!.value
    }

    @Test
    fun `a night well below your own baseline scores low`() {
        // gemini.txt's worked example: two drinks late, nocturnal RMSSD down about
        // 30% and resting heart rate up about 8 bpm.
        val days = ScoringPipeline().run(
            steadyMonth { night ->
                night.copy(
                    values = night.values + mapOf(
                        Metric.HRV_RMSSD to 38.0,
                        Metric.RESTING_HEART_RATE to 58.0,
                    ),
                )
            },
            profile,
        )

        val recovery = days.last().recovery.scoreOrNull.shouldNotBeNull()
        recovery.value shouldBeLessThan 34
    }

    @Test
    fun `a night at your own baseline sits in the middle of your own scale`() {
        val days = ScoringPipeline().run(steadyMonth(), profile)
        val recovery = days.last().recovery.scoreOrNull.shouldNotBeNull()

        // z0 = 0 maps "exactly your usual" to 50, deliberately not to Whoop's
        // population average of 58.
        recovery.value shouldBeGreaterThan 30
        recovery.value shouldBeLessThan 70
    }

    @Test
    fun `the energy bank is chained, not reseeded every morning`() {
        val days = ScoringPipeline().run(steadyMonth(), profile)

        val first = days.first().energyBank.scoreOrNull.shouldNotBeNull()
        val last = days.last().energyBank.scoreOrNull.shouldNotBeNull()

        // Day one has no yesterday, so it is seeded and says so.
        first.warmingUp shouldBe true
        last.warmingUp shouldBe false
    }

    @Test
    fun `yesterday's strain raises tonight's sleep need`() {
        val hardSession = WorkoutSample(
            id = "ride",
            durationMinutes = 75.0,
            meanHeartRate = 150.0,
            maxHeartRate = 175.0,
            title = "Ride",
        )

        // The ride goes on the second-to-last day, because a night is scored on the
        // morning it ends and the strain that earned it belongs to the day before.
        val rideDay = 28
        val rested = ScoringPipeline().run(steadyMonth(), profile)
        val trained = ScoringPipeline().run(
            steadyMonth(modifyIndex = rideDay) { day ->
                day.copy(activity = day.activity.copy(workouts = listOf(hardSession)))
            },
            profile,
        )

        val restedStrain = rested[rideDay].strain.scoreOrNull.shouldNotBeNull()
        val trainedStrain = trained[rideDay].strain.scoreOrNull.shouldNotBeNull()
        trainedStrain.value shouldBeGreaterThan restedStrain.value

        // Whoop's published logistic: a harder day asks for more sleep, up to about
        // 1.7 extra hours at maximal strain.
        val restedNeed = rested.last().sleepNeed.strainAdderHours
        val trainedNeed = trained.last().sleepNeed.strainAdderHours
        (trainedNeed > restedNeed) shouldBe true
    }

    @Test
    fun `the load ratio waits for a month of history before dividing by it`() {
        val short = ScoringPipeline().run(steadyMonth(days = 6), profile)
        val long = ScoringPipeline().run(steadyMonth(days = 30), profile)

        val refused = short.last().loadRatio.shouldBeInstanceOf<ScoringOutcome.NotScored>()
        refused.unavailable.reason shouldBe ScoreUnavailable.Reason.BASELINE_WARMUP

        // Thirty identical days: this week is exactly the month behind it.
        long.last().loadRatio.scoreOrNull.shouldNotBeNull().value shouldBe 100
    }
}
