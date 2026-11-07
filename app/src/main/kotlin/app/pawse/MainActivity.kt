package app.pawse

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.animate
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.compose.dropUnlessResumed
import app.pawse.core.ui.component.REDUCED_FADE
import kotlin.coroutines.cancellation.CancellationException
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalDensity
import app.pawse.core.ui.component.LocalReduceMotion
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
import androidx.compose.ui.draw.clip
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.haze
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
import app.pawse.core.data.sync.SyncCoordinator
import app.pawse.core.data.sync.SyncPreferences
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import app.pawse.feature.home.ActivityScreen
import app.pawse.feature.home.BodyScreen
import app.pawse.feature.home.CalcScreen
import app.pawse.feature.home.CalcSection
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
    @Inject lateinit var syncCoordinator: SyncCoordinator

    /**
     * Every return to the app, not only a cold start, reads Health Connect when the
     * last read is stale: a phone left with Pawse in the background overnight
     * otherwise opens on yesterday's numbers.
     */
    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            if (syncPreferences.onboardingComplete.first()) syncCoordinator.runIfStale()
        }
    }

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
            val reduceMotion by profilePreferences.reduceMotion.collectAsState(initial = false)
            val shapes by profilePreferences.shapes.collectAsState(initial = false)
            PawseTheme(darkTheme = dark, bedside = appearance == Appearance.BEDSIDE, reduceMotion = reduceMotion, shapes = shapes) {
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
            // The notification grant is asked when a notification is first switched on, never at launch.
            PawseNavGraph(route)
        } else {
            OnboardingScreen(onFinished = { forceHome = true })
        }
    }
}

private const val BODY = "body"

/** Screens opened on top of a tab. They slide in; the tabs themselves never move. */
private val PUSHED = setOf(
    HomeDestinations.EXPLAIN,
    HomeDestinations.ACTIVITY,
    HomeDestinations.CALC,
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

/** Reduce motion: anything that travels arrives in place with a 150 ms fade. */
private val arriveIn = fadeIn(tween(REDUCED_FADE, easing = EaseOut))

/**
 * Predictive back on a pushed screen: while the edge swipe decides, the screen
 * shrinks to 92% toward its right edge, slides 24 dp and rounds to 32 dp, so the
 * screen below shows where back goes. The commit itself stays a cut; a cancel
 * settles back in 200 ms. Reduce motion keeps the commit only.
 */
@Composable
private fun PredictiveBack(onBack: () -> Unit, content: @Composable () -> Unit) {
    val reduce = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler { events ->
        try {
            events.collect { progress = it.progress }
            progress = 0f
            onBack()
        } catch (cancelled: CancellationException) {
            val from = progress
            scope.launch { animate(from, 0f, animationSpec = tween(200, easing = EaseOut)) { v, _ -> progress = v } }
            throw cancelled
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val p = if (reduce) 0f else progress
                if (p == 0f) return@graphicsLayer
                scaleX = 1f - 0.08f * p
                scaleY = scaleX
                translationX = 24.dp.toPx() * p
                transformOrigin = TransformOrigin(1f, 0.5f)
                shape = RoundedCornerShape(32.dp * p)
                clip = true
            },
    ) { content() }
}

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
    val reduce = LocalReduceMotion.current
    val push = if (reduce) arriveIn else pushIn
    val scan = if (reduce) arriveIn else scanIn

    LaunchedEffect(initialRoute) {
        when (initialRoute) {
            ROUTE_FOOD -> navController.switchTab(FoodDestinations.LOG)
            // The widget's Scan and +: over the food log, so back lands there.
            ROUTE_SCAN -> {
                navController.switchTab(FoodDestinations.LOG)
                navController.navigate(FoodDestinations.SCAN)
            }
            ROUTE_ADD_FOOD -> {
                navController.switchTab(FoodDestinations.LOG)
                navController.navigate(FoodDestinations.MANUAL)
            }
            ROUTE_EXPLAIN_BODY -> navController.navigate(HomeDestinations.explain(ScoreType.BODY))
        }
    }

    // The dock's glass blurs whatever scrolls under it.
    val haze = remember { HazeState() }
    Box(Modifier.fillMaxSize()) {
        // Tabs swap at once, because they are switched many times a day. Back is
        // instant too: leaving a screen should never wait on an animation.
        NavHost(
            navController = navController,
            startDestination = HomeDestinations.HOME,
            modifier = Modifier.haze(haze),
            enterTransition = { EnterTransition.None },
            exitTransition = { if (targetState.destination.route in PUSHED) holdUnder else ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            composable(HomeDestinations.HOME) {
                HomeScreen(
                    onOpenExplain = { type, back -> navController.navigate(HomeDestinations.explain(type, back)) },
                    onOpenActivity = { navController.navigate(HomeDestinations.activity(it)) },
                    onOpenCalc = { navController.navigate(HomeDestinations.calc()) },
                )
            }

            composable(
                route = HomeDestinations.CALC,
                arguments = listOf(navArgument(HomeDestinations.CALC_ARG) { type = NavType.StringType; defaultValue = "" }),
                enterTransition = { push },
            ) { backStackEntry ->
                val pop = popOnce(navController)
                PredictiveBack(pop) {
                    CalcScreen(section = HomeDestinations.calcSectionOf(backStackEntry.arguments?.getString(HomeDestinations.CALC_ARG)), onBack = pop)
                }
            }

            composable(
                route = HomeDestinations.ACTIVITY,
                arguments = listOf(navArgument(HomeDestinations.ACTIVITY_ARG) { type = NavType.StringType }),
                enterTransition = { push },
            ) { backStackEntry ->
                val pop = popOnce(navController)
                PredictiveBack(pop) {
                    ActivityScreen(
                        initial = HomeDestinations.activityFigureOf(backStackEntry.arguments?.getString(HomeDestinations.ACTIVITY_ARG)),
                        onBack = pop,
                        onOpenCalc = { navController.navigate(HomeDestinations.calc(CalcSection.USUAL)) },
                        onOpenScore = { navController.navigate(HomeDestinations.explain(it)) },
                    )
                }
            }

            composable(
                route = HomeDestinations.EXPLAIN,
                arguments = listOf(
                    navArgument(HomeDestinations.EXPLAIN_ARG) { type = NavType.StringType },
                    navArgument(HomeDestinations.EXPLAIN_BACK_ARG) { type = NavType.IntType; defaultValue = 0 },
                ),
                // The look-in: the layer only fades, so the ring's flight lands where the ring is measured.
                enterTransition = { if (reduce) arriveIn else fadeIn(tween(200, easing = EaseOut)) },
            ) { backStackEntry ->
                val pop = popOnce(navController)
                PredictiveBack(pop) {
                    ExplainScreen(
                        type = HomeDestinations.scoreTypeOf(backStackEntry.arguments?.getString(HomeDestinations.EXPLAIN_ARG)),
                        initialBack = backStackEntry.arguments?.getInt(HomeDestinations.EXPLAIN_BACK_ARG) ?: 0,
                        onBack = pop,
                        onOpenCalc = { navController.navigate(HomeDestinations.calc(it)) },
                    )
                }
            }

            composable(FoodDestinations.LOG) {
                FoodLogScreen(
                    onManualEntry = { navController.navigate(FoodDestinations.MANUAL) },
                    onOpenBody = { navController.switchTab(BODY) },
                    onScan = { navController.navigate(FoodDestinations.SCAN) },
                )
            }

            composable(BODY) { BodyScreen(onAddWeighIn = { navController.navigate(HomeDestinations.WEIGH_IN) }) }

            composable(HomeDestinations.WEIGH_IN, enterTransition = { push }) {
                val pop = popOnce(navController)
                PredictiveBack(pop) {
                    WeighInScreen(onDone = pop, onBack = pop)
                }
            }

            composable(FoodDestinations.SCAN, enterTransition = { scan }) {
                ScannerScreen(
                    onBack = popOnce(navController),
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
                    onLogged = {
                        navController.popBackStack()
                        navController.switchTab(FoodDestinations.LOG)
                    },
                )
            }

            composable(
                route = FoodDestinations.ADD,
                arguments = listOf(navArgument(FoodDestinations.PRODUCT_ARG) { type = NavType.StringType }),
                enterTransition = { push },
            ) {
                val pop = popOnce(navController)
                PredictiveBack(pop) {
                    AddFoodScreen(
                        onDone = { navController.switchTab(FoodDestinations.LOG) },
                        onBack = pop,
                    )
                }
            }

            composable(FoodDestinations.MANUAL, enterTransition = { push }) {
                val pop = popOnce(navController)
                PredictiveBack(pop) {
                    ManualEntryScreen(
                        onDone = { navController.switchTab(FoodDestinations.LOG) },
                        onBack = pop,
                    )
                }
            }
        }

        if (showDock) {
            Dock(
                current = current,
                onTab = navController::switchTab,
                onScan = { navController.navigate(FoodDestinations.SCAN) },
                haze = haze,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * Back from a pushed screen, once. A second tap while the pop is under way would
 * otherwise pop again and take the tab underneath with it.
 */
@Composable
private fun popOnce(nav: NavController): () -> Unit = dropUnlessResumed { nav.popBackStack() }

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
    haze: HazeState,
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
                    .clip(RoundedCornerShape(32.dp))
                    // Real glass: the page behind blurred 24 dp under the design's 62% tint.
                    .hazeChild(haze, HazeStyle(backgroundColor = background, tints = listOf(HazeTint(semantics.glass)), blurRadius = 24.dp, noiseFactor = 0f))
                    .border(1.dp, semantics.glassLine, RoundedCornerShape(32.dp))
                    .padding(6.dp),
            ) {
                val segment = maxWidth / TABS.size
                val index = TABS.indexOfFirst { it.first == current }.coerceAtLeast(0)
                val reduce = LocalReduceMotion.current
                val offset by animateDpAsState(segment * index, if (reduce) snap() else tween(280, easing = EaseInOut), label = "dock")
                // At very large text the labels drop and the icons carry the tabs.
                val iconsOnly = LocalDensity.current.fontScale >= 1.6f
                Box(
                    Modifier
                        .offset(x = offset)
                        .width(segment)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f), RoundedCornerShape(26.dp)),
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
                            if (!iconsOnly) {
                                Spacer(Modifier.width(5.dp))
                                Text(label, style = PawseType.BodyStrong, color = color, maxLines = 1)
                            }
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
