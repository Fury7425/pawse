package app.pawse.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        MetricSampleEntity::class,
        SleepSessionEntity::class,
        SleepStageEntity::class,
        ExerciseSessionEntity::class,
        ScoreEntity::class,
        FoodProductEntity::class,
        FoodLogEntryEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class PawseDatabase : RoomDatabase() {
    abstract fun metricSampleDao(): MetricSampleDao
    abstract fun sleepDao(): SleepDao
    abstract fun exerciseDao(): ExerciseDao
    abstract fun scoreDao(): ScoreDao
    abstract fun foodProductDao(): FoodProductDao
    abstract fun foodLogDao(): FoodLogDao

    companion object {
        const val NAME = "pawse.db"

        /**
         * Encrypted at rest via SQLCipher. Local-first is not a slogan here: there
         * is no account and no server, so the on-device file is the only copy, and
         * it holds a continuous record of the user's sleep and heart rate.
         *
         * The passphrase comes from [app.pawse.core.data.security.DatabaseKeyStore],
         * which generates it once and keeps it in the Android keystore.
         */
        fun build(context: Context, passphrase: ByteArray): PawseDatabase {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context, PawseDatabase::class.java, NAME)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                // No fallbackToDestructiveMigration, deliberately. This file is the
                // only copy of the user's history; a failed migration must announce
                // itself, not quietly empty the baselines every score rests on.
                .addMigrations(*Migrations.ALL)
                .build()
        }
    }
}
