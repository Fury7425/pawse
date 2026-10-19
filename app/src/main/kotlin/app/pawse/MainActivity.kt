package app.pawse

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.pawse.core.data.profile.Appearance
import app.pawse.core.data.profile.UserProfilePreferences
import app.pawse.core.data.sync.SyncPreferences
import app.pawse.core.ui.theme.PawseTheme
import app.pawse.feature.food.AddFoodScreen
import app.pawse.feature.food.FoodDestinations
import app.pawse.feature.food.FoodLogScreen
import app.pawse.feature.food.ManualEntryScreen
import app.pawse.feature.food.ScannerScreen
import app.pawse.feature.home.BodyScreen
import app.pawse.feature.home.ExplainScreen
import app.pawse.feature.home.HomeDestinations
import app.pawse.feature.home.HomeScreen
import app.pawse.feature.onboarding.OnboardingScreen
import app.pawse.scoring.model.ScoreType
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var syncPreferences: SyncPreferences
    @Inject lateinit var profilePreferences: UserProfilePreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge and predictive back are platform mechanics, not style
        // choices. This app is native Android; it does not imitate iOS chrome.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val route = intent?.getStringExtra(EXTRA_ROUTE)

        setContent {
            val appearance by profilePreferences.appearance.collectAsState(initial = Appearance.SYSTEM)
            val dark = when (appearance) {
                Appearance.SYSTEM -> isSystemInDarkTheme()
                Appearance.DARK, Appearance.BEDSIDE -> true
                Appearance.LIGHT -> false
            }
            // Status-bar icons follow the app's own palette, not the phone's, so a
            // light Pawse on a dark phone does not lose its clock.
            DisposableEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            PawseTheme(darkTheme = dark, bedside = appearance == Appearance.BEDSIDE) {
                PawseRoot(syncPreferences, route)
            }
        }
    }
}

@Composable
private fun PawseRoot(preferences: SyncPreferences, route: String?) {
    val onboarded by preferences.onboardingComplete.collectAsState(initial = false)
    var forceHome by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        if (onboarded || forceHome) {
            AskForNotificationsOnce()
            PawseNavGraph(route)
        } else {
            OnboardingScreen(onFinished = { forceHome = true })
        }
    }
}

/**
 * The morning note needs the notification grant on Android 13 and up. Asked once,
 * after onboarding, and never again: declining is a fine answer.
 */
@Composable
private fun AskForNotificationsOnce() {
    if (Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("pawse_notify", Context.MODE_PRIVATE)
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted && !prefs.getBoolean("asked", false)) {
            prefs.edit { putBoolean("asked", true) }
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

private const val BODY = "body"

/**
 * The whole app: Today, Food and Body as three tabs, the scanner one tap from any
 * of them, and every explanation one tap from the score it explains.
 */
@Composable
private fun PawseNavGraph(initialRoute: String?) {
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    val showDock = current in setOf(HomeDestinations.HOME, FoodDestinations.LOG, BODY)

    LaunchedEffect(initialRoute) {
        when (initialRoute) {
            ROUTE_FOOD -> navController.switchTab(FoodDestinations.LOG)
            ROUTE_EXPLAIN_RECOVERY -> navController.navigate(HomeDestinations.explain(ScoreType.RECOVERY))
        }
    }

    Box(Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = HomeDestinations.HOME) {
            composable(HomeDestinations.HOME) {
                HomeScreen(onOpenExplain = { type -> navController.navigate(HomeDestinations.explain(type)) })
            }

            composable(
                route = HomeDestinations.EXPLAIN,
                arguments = listOf(navArgument(HomeDestinations.EXPLAIN_ARG) { type = NavType.StringType }),
            ) { backStackEntry ->
                ExplainScreen(
                    type = HomeDestinations.scoreTypeOf(backStackEntry.arguments?.getString(HomeDestinations.EXPLAIN_ARG)),
                    onBack = { navController.popBackStack() },
                )
            }

            composable(FoodDestinations.LOG) {
                FoodLogScreen(
                    onManualEntry = { navController.navigate(FoodDestinations.MANUAL) },
                    onOpenBody = { navController.switchTab(BODY) },
                )
            }

            composable(BODY) { BodyScreen() }

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
                    onDone = { navController.switchTab(FoodDestinations.LOG) },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(FoodDestinations.MANUAL) {
                ManualEntryScreen(
                    onDone = { navController.switchTab(FoodDestinations.LOG) },
                    onBack = { navController.popBackStack() },
                )
            }
        }

        if (showDock) {
            Dock(
                current = current,
                onTab = navController::switchTab,
                onScan = { navController.navigate(FoodDestinations.SCAN) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** Tabs keep their own scroll and state; tapping the one you are on does nothing. */
private fun NavController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Three tabs in a pill, and the scanner beside them, always one tap away. */
@Composable
private fun Dock(
    current: String?,
    onTab: (String) -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            shadowElevation = 8.dp,
            modifier = Modifier
                .weight(1f)
                .height(64.dp),
        ) {
            Row(Modifier.padding(6.dp)) {
                listOf(HomeDestinations.HOME to "Today", FoodDestinations.LOG to "Food", BODY to "Body")
                    .forEach { (route, label) ->
                        val selected = current == route
                        Surface(
                            onClick = { onTab(route) },
                            shape = RoundedCornerShape(26.dp),
                            color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else androidx.compose.ui.graphics.Color.Transparent,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .semantics { contentDescription = if (selected) "$label, current tab" else label },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
            }
        }
        Surface(
            onClick = onScan,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.onSurface,
            contentColor = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
            modifier = Modifier
                .size(64.dp)
                .semantics { contentDescription = "Scan food" },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("Scan", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
