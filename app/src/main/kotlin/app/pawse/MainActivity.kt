package app.pawse

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.pawse.core.ui.component.Glyph
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.PawseMotion
import app.pawse.core.ui.component.press
import app.pawse.core.ui.theme.PawseType
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
                val clear = android.graphics.Color.TRANSPARENT
                val style = if (dark) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear)
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
        // Screens fade through quickly; the dock's pill is what moves.
        NavHost(
            navController = navController,
            startDestination = HomeDestinations.HOME,
            enterTransition = { fadeIn(tween(180)) },
            exitTransition = { fadeOut(tween(180)) },
            popEnterTransition = { fadeIn(tween(180)) },
            popExitTransition = { fadeOut(tween(180)) },
        ) {
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

private val Tabs = listOf(
    Triple(HomeDestinations.HOME, "Today", Glyphs.TODAY),
    Triple(FoodDestinations.LOG, "Food", Glyphs.FOOD),
    Triple(BODY, "Body", Glyphs.BODY),
)

/**
 * Three tabs on a sheet of glass, and the scanner beside them, always one tap
 * away. The picked tab is a raised graphite pill that slides under its icon and
 * word; the dock never takes a band colour.
 */
@Composable
private fun Dock(
    current: String?,
    onTab: (String) -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = MaterialTheme.colorScheme.background
    val dark = background.luminance() < 0.5f
    val pill = RoundedCornerShape(32.dp)
    val selected = Tabs.indexOfFirst { it.first == current }.coerceAtLeast(0)
    val haptics = LocalHapticFeedback.current
    val lift = Color.Black.copy(alpha = if (dark) 0.5f else 0.12f)

    Box(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.6f to background)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 48.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .height(64.dp)
                    .shadow(16.dp, pill, ambientColor = lift, spotColor = lift)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = if (dark) 0.72f else 0.78f), pill)
                    .border(1.dp, (if (dark) Color.White else Color.Black).copy(alpha = 0.07f), pill)
                    .padding(6.dp),
            ) {
                val width = maxWidth / Tabs.size
                val x by animateDpAsState(
                    targetValue = width * selected,
                    animationSpec = tween(280, easing = PawseMotion.EaseInOut),
                    label = "dock",
                )
                Box(
                    Modifier
                        .offset(x = x)
                        .width(width)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(26.dp)),
                )
                Row(Modifier.fillMaxSize()) {
                    Tabs.forEachIndexed { index, (route, label, glyph) ->
                        val on = index == selected
                        val tint = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(26.dp))
                                .selectable(selected = on, role = Role.Tab) {
                                    if (!on) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onTab(route)
                                },
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Glyph(glyph, size = 20.dp, tint = tint)
                            Spacer(Modifier.width(6.dp))
                            Text(label, style = PawseType.Action, color = tint)
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .shadow(16.dp, CircleShape, ambientColor = lift, spotColor = lift)
                    .clip(CircleShape)
                    .press(onScan)
                    .background(MaterialTheme.colorScheme.onSurface)
                    .semantics { contentDescription = "Scan food" },
                contentAlignment = Alignment.Center,
            ) {
                Glyph(Glyphs.SCAN, size = 22.dp, tint = background)
            }
        }
    }
}
