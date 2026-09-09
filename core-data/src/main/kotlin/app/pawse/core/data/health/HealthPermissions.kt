package app.pawse.core.data.health

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
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
        NutritionRecord::class,
    )

    /** Only nutrition is ever written back, and only from the food log. */
    val WRITE_TYPES: List<KClass<out Record>> = listOf(NutritionRecord::class)

    val READ: Set<String> = READ_TYPES.map { HealthPermission.getReadPermission(it) }.toSet()
    val WRITE: Set<String> = WRITE_TYPES.map { HealthPermission.getWritePermission(it) }.toSet()

    /**
     * The capability probe reads 30 days back, which is past the default 30-day
     * history window boundary on some platform versions, so history access is
     * requested alongside the read grants.
     */
    const val READ_HISTORY: String = HealthConnectClient.PERMISSION_READ_HEALTH_DATA_HISTORY

    /** Nightly recompute fires after the sleep session closes, often in background. */
    const val READ_IN_BACKGROUND: String = HealthConnectClient.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

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
