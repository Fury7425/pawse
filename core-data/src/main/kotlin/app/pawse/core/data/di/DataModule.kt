package app.pawse.core.data.di

import android.content.Context
import androidx.work.WorkManager
import app.pawse.core.data.db.ExerciseDao
import app.pawse.core.data.db.MetricSampleDao
import app.pawse.core.data.db.PawseDatabase
import app.pawse.core.data.db.ScoreDao
import app.pawse.core.data.db.SleepDao
import app.pawse.core.data.security.DatabaseKeyStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun database(
        @ApplicationContext context: Context,
        keyStore: DatabaseKeyStore,
    ): PawseDatabase = PawseDatabase.build(context, keyStore.passphrase())

    @Provides fun metricSampleDao(db: PawseDatabase): MetricSampleDao = db.metricSampleDao()
    @Provides fun sleepDao(db: PawseDatabase): SleepDao = db.sleepDao()
    @Provides fun exerciseDao(db: PawseDatabase): ExerciseDao = db.exerciseDao()
    @Provides fun scoreDao(db: PawseDatabase): ScoreDao = db.scoreDao()

    @Provides
    @Singleton
    fun workManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}
