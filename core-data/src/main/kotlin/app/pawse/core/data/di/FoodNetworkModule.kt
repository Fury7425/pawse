package app.pawse.core.data.di

import app.pawse.core.data.food.net.OpenFoodFactsApi
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * The app's entire network surface.
 *
 * Everything here is behind a `Provider` at the call site, so an install that
 * never consents to a lookup never builds an HTTP stack at all. That is not a
 * micro-optimisation — it is the difference between an app that could make a
 * request and one that has the machinery running and chooses not to.
 *
 * No logging interceptor, in any build type. A request log of what someone eats
 * is exactly the sort of thing this app promises not to keep.
 */
@Module
@InstallIn(SingletonComponent::class)
object FoodNetworkModule {

    /**
     * Open Food Facts asks API users to identify themselves in the User-Agent so
     * they can contact operators of misbehaving clients. It carries the app name
     * and version and nothing about the user — no id, no device, no locale.
     */
    private const val USER_AGENT = "Pawse/0.1.0 (Android; local-first food logging)"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun okHttpClient(): OkHttpClient = OkHttpClient.Builder()
        // Short by design. This runs while someone is standing in a shop holding a
        // tin, and a lookup that has not answered in five seconds should hand back
        // to the label-photograph path rather than spin.
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .build(),
            )
        }
        .build()

    @Provides
    @Singleton
    fun openFoodFactsApi(client: OkHttpClient): OpenFoodFactsApi = Retrofit.Builder()
        .baseUrl(OpenFoodFactsApi.BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(OpenFoodFactsApi::class.java)
}
