package app.pawse

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.pawse.core.data.food.FoodRepository
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PawseApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    // Lazy: the encrypted database is only touched when a Food widget is on a home screen.
    @Inject lateinit var food: Lazy<FoodRepository>

    override fun onCreate() {
        super.onCreate()
        FoodWidget.follow(this) { food.get() }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
