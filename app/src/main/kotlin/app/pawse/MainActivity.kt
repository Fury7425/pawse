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
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
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
import app.pawse.core.ui.component.EaseDrawer
import app.pawse.core.ui.component.EaseInOut
import app.pawse.core.ui.component.EaseOut
import app.pawse.core.ui.component.Glyph
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.ctaBrush
import app.pawse.core.ui.component.lift
import app.pawse.core.ui.component.pressable
import app.pawse.core.ui.theme.PawseTheme
import app.pawse.core.ui.theme.PawseType
import app.pawse.feature.food.AddFoodScreen
import app.pawse.feature.food.FoodDestinations
import app.pawse.feature.food.FoodLogScreen
import app.pawse.feature.food.ManualEntryScreen
import app.pawse.feature.food.ScannerScreen
import app.pawse.feature.home.BodyScreen
import app.pawse.feature.home.ExplainScreen
import app.pawse.feature.home.HomeDestinations
import app.pawse.feature.home.HomeScreen
import app.pawse.feature.home.WeighInScreen
import app.pawse.feature.onboarding.OnboardingScreen
import app.pawse.scoring.model.ScoreType
import app.pawse.scoring.tr
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

/** Screens opened on top of a tab. They slide in; the tabs themselves never move. */
private val PUSHED = setOf(
    HomeDestinations.EXPLAIN,
    HomeDestinations.WEIGH_IN,
    FoodDestinations.SCAN,
    FoodDestinations.ADD,
    FoodDestinations.MANUAL,
)

/** A pushed screen: 20 dp in from the right with a fade, 300 ms on the drawer curve. */
private val pushIn = fadeIn(tween(300, easing = EaseDrawer)) +
    slideInHorizontally(tween(300, easing = EaseDrawer)) { it / 18 }

/** The camera grows out of the Scan button, so it is clear where it came from. */
private val scanIn = fadeIn(tween(280, easing = EaseOut)) +
    scaleIn(tween(280, easing = EaseOut), initialScale = 0.94f, transformOrigin = TransformOrigin(0.88f, 0.96f))

/** Keeps the screen underneath in place while a pushed one slides over it. */
private val holdUnder = fadeOut(tween(300), targetAlpha = 1f)

/**
 * The whole app: Scores, Food and Body as three tabs, the scanner one tap from any
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
            ROUTE_EXPLAIN_BODY -> navController.navigate(HomeDestinations.explain(ScoreType.BODY))
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Tabs swap at once, because they are switched many times a day. Back is
        // instant too: leaving a screen should never wait on an animation.
        NavHost(
            navController = navController,
            startDestination = HomeDestinations.HOME,
            enterTransition = { EnterTransition.None },
            exitTransition = { if (targetState.destination.route in PUSHED) holdUnder else ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            composable(HomeDestinations.HOME) {
                HomeScreen(onOpenExplain = { type -> navController.navigate(HomeDestinations.explain(type)) })
            }

            composable(
                route = HomeDestinations.EXPLAIN,
                arguments = listOf(navArgument(HomeDestinations.EXPLAIN_ARG) { type = NavType.StringType }),
                enterTransition = { pushIn },
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

            composable(BODY) { BodyScreen(onAddWeighIn = { navController.navigate(HomeDestinations.WEIGH_IN) }) }

            composable(HomeDestinations.WEIGH_IN, enterTransition = { pushIn }) {
                WeighInScreen(
                    onDone = { navController.popBackStack() },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(FoodDestinations.SCAN, enterTransition = { scanIn }) {
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
                enterTransition = { pushIn },
            ) {
                AddFoodScreen(
                    onDone = { navController.switchTab(FoodDestinations.LOG) },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(FoodDestinations.MANUAL, enterTransition = { pushIn }) {
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

/** A getter so the labels follow a language change made while the app runs. */
private val TABS: List<Triple<String, String, String>>
    get() = listOf(
        Triple(HomeDestinations.HOME, tr("Scores", "점수"), Glyphs.Today),
        Triple(FoodDestinations.LOG, tr("Food", "음식"), Glyphs.Food),
        Triple(BODY, tr("Body", "신체"), Glyphs.Body),
    )

/**
 * Three tabs in a glass pill whose highlight slides between them, and the scanner
 * beside them, always one tap away. Content scrolls under a fade into the page.
 */
@Composable
private fun Dock(
    current: String?,
    onTab: (String) -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = MaterialTheme.colorScheme.background
    val semantics = PawseTheme.semantics
    Box(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to background.copy(alpha = 0f), 0.7f to background)),
    ) {
        Row(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 48.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .height(64.dp)
                    .lift(RoundedCornerShape(32.dp))
                    .background(semantics.glass, RoundedCornerShape(32.dp))
                    .border(1.dp, semantics.glassLine, RoundedCornerShape(32.dp))
                    .padding(6.dp),
            ) {
                val segment = maxWidth / TABS.size
                val index = TABS.indexOfFirst { it.first == current }.coerceAtLeast(0)
                val offset by animateDpAsState(segment * index, tween(280, easing = EaseInOut), label = "dock")
                Box(
                    Modifier
                        .offset(x = offset)
                        .width(segment)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(26.dp)),
                )
                Row(Modifier.fillMaxSize()) {
                    TABS.forEach { (route, label, glyph) ->
                        val selected = current == route
                        val color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .pressable(role = Role.Tab) { onTab(route) }
                                .semantics { contentDescription = if (selected) label + tr(", current tab", ", 현재 탭") else label },
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Glyph(glyph, color = color)
                            Spacer(Modifier.width(6.dp))
                            Text(label, style = PawseType.BodyStrong, color = color)
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .pressable(onClick = onScan)
                    .lift(CircleShape)
                    .background(ctaBrush(), CircleShape)
                    .semantics { contentDescription = tr("Scan food", "음식 스캔") },
                contentAlignment = Alignment.Center,
            ) {
                Glyph(Glyphs.Scan, size = 22.dp, color = MaterialTheme.colorScheme.background)
            }
        }
    }
}
