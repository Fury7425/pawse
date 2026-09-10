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
 * Two destinations: the number, and the arithmetic behind it.
 *
 * The graph is deliberately flat. Every explainability screen is one tap from the
 * score it explains and one tap back, because an explanation buried three levels
 * down is an explanation nobody reads.
 */
@Composable
private fun PawseNavGraph() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = HomeDestinations.HOME) {
        composable(HomeDestinations.HOME) {
            HomeScreen(
                onOpenExplain = { type -> navController.navigate(HomeDestinations.explain(type)) },
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
    }
}
