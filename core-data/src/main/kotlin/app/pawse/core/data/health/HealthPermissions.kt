package app.pawse.core.data.health

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import kotlin.reflect.KClass

/**
 * The single source of truth for what this app asks Health Connect for.
 *
 * Health Connect is the only data source. There is no vendor SDK, no account, and
 * no server: whatever the user's watch or ring already wrote, we interpret.
 */
object HealthPermissions {

    /**
     * Record types the scoring engine reads.
     *
     * Note the HRV entry. Health Connect exposes HRV only as
     * [HeartRateVariabilityRmssdRecord] — there is no SDNN record type on Android,
     * so the SDNN/RMSSD switch that Bevel offers on iOS has no equivalent here and
     * is deliberately absent from this app. RMSSD is the sole path, which is also
     * what every recovery-relevant recommendation in the research reports prefers.
     */
    val READ_TYPES: List<KClass<out Record>> = listOf(
        HeartRateVariabilityRmssdRecord::class,
        RestingHeartRateRecord::class,
        HeartRateRecord::class,
        SleepSessionRecord::class,
        RespiratoryRateRecord::class,
        OxygenSaturationRecord::class,
        SkinTemperatureRecord::class,
        ExerciseSessionRecord::class,
        StepsRecord::class,
        ActiveCaloriesBurnedRecord::class,
        TotalCaloriesBurnedRecord::class,
        Vo2MaxRecord::class,
        WeightRecord::class,
        BodyFatRecord::class,
        // The Body tab. Read only when a scale writes them; none feeds a score.
        LeanBodyMassRecord::class,
        BoneMassRecord::class,
        BodyWaterMassRecord::class,
        HeightRecord::class,
        BasalMetabolicRateRecord::class,
        NutritionRecord::class,
    )

    /**
     * Written back only for things the user typed in here: food from the food log,
     * and a weigh-in from the Body tab. Each set is asked for where it is used, so
     * a missing body grant never makes food look blocked, or the reverse.
     */
    val NUTRITION_WRITE: Set<String> = setOf(HealthPermission.getWritePermission(NutritionRecord::class))

    val BODY_WRITE_TYPES: List<KClass<out Record>> = listOf(
        WeightRecord::class,
        BodyFatRecord::class,
        LeanBodyMassRecord::class,
        BodyWaterMassRecord::class,
        BoneMassRecord::class,
    )
    val BODY_WRITE: Set<String> = BODY_WRITE_TYPES.map { HealthPermission.getWritePermission(it) }.toSet()

    val READ: Set<String> = READ_TYPES.map { HealthPermission.getReadPermission(it) }.toSet()
    val WRITE: Set<String> = NUTRITION_WRITE + BODY_WRITE

    /**
     * The capability probe reads 30 days back, which is past the default 30-day
     * history window boundary on some platform versions, so history access is
     * requested alongside the read grants.
     */
    val READ_HISTORY: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY

    /** Nightly recompute fires after the sleep session closes, often in background. */
    val READ_IN_BACKGROUND: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    /** Everything requested at onboarding. Partial grants are expected and handled. */
    val ALL: Set<String> = READ + WRITE + setOf(READ_HISTORY, READ_IN_BACKGROUND)

    /**
     * Without these two nothing meaningful can be computed: no sleep session means
     * no night to attribute a score to, and no HRV means Recovery loses the term
     * that carries most of its weight.
     */
    val CORE_READS: Set<String> = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
    )
}
