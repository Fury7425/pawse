package app.pawse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.pawse.core.data.sync.SyncPreferences
import app.pawse.core.ui.theme.PawseTheme
import app.pawse.feature.food.AddFoodScreen
import app.pawse.feature.food.FoodDestinations
import app.pawse.feature.food.FoodLogScreen
import app.pawse.feature.food.ManualEntryScreen
import app.pawse.feature.food.ScannerScreen
import app.pawse.feature.home.ExplainScreen
import app.pawse.feature.home.HomeDestinations
import app.pawse.feature.home.HomeScreen
import app.pawse.feature.onboarding.OnboardingScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var syncPreferences: SyncPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge and predictive back are platform mechanics, not style
        // choices. This app is native Android; it does not imitate iOS chrome.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            PawseTheme {
                PawseRoot(syncPreferences)
            }
        }
    }
}

@Composable
private fun PawseRoot(preferences: SyncPreferences) {
    val onboarded by preferences.onboardingComplete.collectAsState(initial = false)
    var forceHome by remember { mutableStateOf(false) }

    if (onboarded || forceHome) {
        PawseNavGraph()
    } else {
        OnboardingScreen(onFinished = { forceHome = true })
    }
}

/**
 * The whole app: the number, the arithmetic behind it, and the food log.
 *
 * The graph is deliberately flat. Every explainability screen is one tap from the
 * score it explains and one tap back, because an explanation buried three levels
 * down is an explanation nobody reads. The food side is three screens deep at
 * most, and the scanner returns to the log rather than stacking on itself.
 */
@Composable
private fun PawseNavGraph() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = HomeDestinations.HOME) {
        composable(HomeDestinations.HOME) {
            HomeScreen(
                onOpenExplain = { type -> navController.navigate(HomeDestinations.explain(type)) },
                onOpenFood = { navController.navigate(FoodDestinations.LOG) },
            )
        }

        composable(
            route = HomeDestinations.EXPLAIN,
            arguments = listOf(navArgument(HomeDestinations.EXPLAIN_ARG) { type = NavType.StringType }),
        ) { entry ->
            ExplainScreen(
                type = HomeDestinations.scoreTypeOf(entry.arguments?.getString(HomeDestinations.EXPLAIN_ARG)),
                onBack = { navController.popBackStack() },
            )
        }

        composable(FoodDestinations.LOG) {
            FoodLogScreen(
                onBack = { navController.popBackStack() },
                onScan = { navController.navigate(FoodDestinations.SCAN) },
                onManualEntry = { navController.navigate(FoodDestinations.MANUAL) },
            )
        }

        composable(FoodDestinations.SCAN) {
            ScannerScreen(
                onBack = { navController.popBackStack() },
                onProductChosen = { productId ->
                    // The scanner is replaced rather than stacked under the add
                    // screen: coming back from "added" should land on the log, not
                    // reopen the camera on the thing that was just logged.
                    navController.navigate(FoodDestinations.add(productId)) {
                        popUpTo(FoodDestinations.SCAN) { inclusive = true }
                    }
                },
                onManualEntry = {
                    navController.navigate(FoodDestinations.MANUAL) {
                        popUpTo(FoodDestinations.SCAN) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = FoodDestinations.ADD,
            arguments = listOf(navArgument(FoodDestinations.PRODUCT_ARG) { type = NavType.StringType }),
        ) {
            AddFoodScreen(
                onDone = { navController.popBackStack(FoodDestinations.LOG, inclusive = false) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(FoodDestinations.MANUAL) {
            ManualEntryScreen(
                onDone = { navController.popBackStack(FoodDestinations.LOG, inclusive = false) },
                onBack = { navController.popBackStack() },
            )
        }
    }
}
