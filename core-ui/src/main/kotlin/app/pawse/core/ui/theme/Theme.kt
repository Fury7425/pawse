package app.pawse.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import app.pawse.core.ui.component.LocalReduceMotion
import app.pawse.core.ui.component.LocalShapes
import app.pawse.core.ui.component.systemReduceMotion

/**
 * Semantic colours that are not part of Material's scheme, because Material has no
 * concept of a score band or a deviation direction. Kept in their own local so a
 * call site cannot reach for a band colour by accident.
 */
@Immutable
data class PawseSemantics(
    val bandLow: Color,
    val bandModerate: Color,
    val bandHigh: Color,
    val better: Color,
    val worse: Color,
    val neutral: Color,
    val baselineBand: Color,
    val baselineOuter: Color,
    /** The floating dock and scanner chrome: translucent surface and its hairline. */
    val glass: Color,
    val glassLine: Color,
    /** Light falling on a card's top edge, fading out by two thirds down. Transparent turns it off. */
    val sheen: Color,
    /** A faint cool light from the top of each screen. Dark only. */
    val aura: Color,
    /** Micro text at the 4.5:1 floor: sync lines, captions, axis labels. */
    val faint: Color,
    /** Non-text marks only: meal dots, raw weigh-ins. Never text. */
    val mark: Color,
    /** One selection recipe: a chosen chip, the check-in, a coachmark, and what sits on it. */
    val selection: Color,
    val onSelection: Color,
    val segmentThumb: Color,
    /** The undo toast: raised graphite, not the light CTA fill. */
    val toast: Color,
    val onToast: Color,
    val sheet: Color,
    /** Sleep stages, light to deep in one blue ramp, so no stage reads as good or bad. */
    val stageAwake: Color,
    val stageRem: Color,
    val stageLight: Color,
    val stageDeep: Color,
    /** A night written as asleep only, without stages. */
    val stageAsleep: Color,
)

private val LocalPawseSemantics = staticCompositionLocalOf<PawseSemantics> {
    error("PawseSemantics requested outside PawseTheme")
}

private val DarkSemantics = PawseSemantics(
    bandLow = PawseColors.BandLowDark,
    bandModerate = PawseColors.BandModerateDark,
    bandHigh = PawseColors.BandHighDark,
    better = PawseColors.BetterDark,
    worse = PawseColors.WorseDark,
    neutral = PawseColors.NeutralDark,
    baselineBand = PawseColors.BaselineBandDark,
    baselineOuter = PawseColors.BaselineOuterDark,
    // ponytail: 90% with no blur. The design's 62% needs a backdrop blur (Haze, or
    // RenderEffect on a captured layer on API 31+); drop the alpha when that lands.
    glass = PawseColors.DarkSurface.copy(alpha = 0.9f),
    glassLine = Color.White.copy(alpha = 0.10f),
    sheen = Color.White.copy(alpha = 0.045f),
    aura = Color(0xFF96ACD2).copy(alpha = 0.09f),
    faint = PawseColors.DarkOnSurfaceFaint,
    mark = PawseColors.DarkMark,
    selection = PawseColors.DarkSelection,
    onSelection = PawseColors.DarkBackground,
    segmentThumb = PawseColors.DarkSegmentThumb,
    toast = PawseColors.DarkToast,
    onToast = PawseColors.DarkOnSurface,
    sheet = PawseColors.DarkSheet,
    stageAwake = Color(0xFFE6E8EA),
    stageRem = Color(0xFF9DB9F2),
    stageLight = Color(0xFF5E86DB),
    stageDeep = Color(0xFF3E5FC2),
    stageAsleep = PawseColors.DarkOnSurfaceVariant,
)

private val LightSemantics = PawseSemantics(
    bandLow = PawseColors.BandLowLight,
    bandModerate = PawseColors.BandModerateLight,
    bandHigh = PawseColors.BandHighLight,
    better = PawseColors.BetterLight,
    worse = PawseColors.WorseLight,
    neutral = PawseColors.NeutralLight,
    baselineBand = PawseColors.BaselineBandLight,
    baselineOuter = PawseColors.BaselineOuterLight,
    glass = PawseColors.LightSurface.copy(alpha = 0.92f),
    glassLine = PawseColors.LightOnSurface.copy(alpha = 0.08f),
    sheen = Color.Transparent,
    aura = Color.Transparent,
    faint = PawseColors.LightOnSurfaceFaint,
    mark = PawseColors.LightMark,
    selection = PawseColors.LightSelection,
    onSelection = PawseColors.LightSurface,
    segmentThumb = PawseColors.LightSegmentThumb,
    toast = PawseColors.LightToast,
    onToast = PawseColors.LightSurface,
    sheet = PawseColors.LightSheet,
    stageAwake = Color(0xFF7C858D),
    stageRem = Color(0xFF6690DD),
    stageLight = Color(0xFF3F67C8),
    stageDeep = Color(0xFF1F3A8A),
    stageAsleep = PawseColors.LightOnSurfaceVariant,
)

private val NightSemantics = PawseSemantics(
    bandLow = PawseColors.BandLowNight,
    bandModerate = PawseColors.BandModerateNight,
    bandHigh = PawseColors.BandHighNight,
    better = PawseColors.BandHighNight,
    worse = PawseColors.BandLowNight,
    neutral = PawseColors.NeutralNight,
    baselineBand = PawseColors.BaselineBandNight,
    baselineOuter = PawseColors.BaselineOuterNight,
    glass = PawseColors.NightSurface.copy(alpha = 0.92f),
    glassLine = Color.White.copy(alpha = 0.06f),
    // Bedside stays flat: no sheen, no glow in a dark room.
    sheen = Color.Transparent,
    aura = Color.Transparent,
    faint = PawseColors.NightOnSurfaceFaint,
    mark = PawseColors.NightMark,
    selection = PawseColors.NightSelection,
    onSelection = PawseColors.NightBackground,
    segmentThumb = PawseColors.NightSegmentThumb,
    toast = PawseColors.NightToast,
    onToast = PawseColors.NightOnSurface,
    sheet = PawseColors.NightSheet,
    stageAwake = Color(0xFFBFC4C9),
    stageRem = Color(0xFF8AA6D9),
    stageLight = Color(0xFF5579C4),
    stageDeep = Color(0xFF3A5AB0),
    stageAsleep = PawseColors.NightOnSurfaceVariant,
)

private val NightScheme = darkColorScheme(
    background = PawseColors.NightBackground,
    onBackground = PawseColors.NightOnSurface,
    surface = PawseColors.NightSurface,
    onSurface = PawseColors.NightOnSurface,
    surfaceVariant = PawseColors.NightSurfaceContainer,
    onSurfaceVariant = PawseColors.NightOnSurfaceVariant,
    surfaceContainer = PawseColors.NightSurfaceContainer,
    surfaceContainerHigh = PawseColors.NightSurfaceContainerHigh,
    outline = PawseColors.NightOutline,
    outlineVariant = PawseColors.NightOutlineVariant,
    primary = PawseColors.NightOnSurface,
    onPrimary = PawseColors.NightBackground,
    secondary = PawseColors.NightOnSurfaceVariant,
    onSecondary = PawseColors.NightBackground,
)

private val DarkScheme = darkColorScheme(
    background = PawseColors.DarkBackground,
    onBackground = PawseColors.DarkOnSurface,
    surface = PawseColors.DarkSurface,
    onSurface = PawseColors.DarkOnSurface,
    surfaceVariant = PawseColors.DarkSurfaceContainer,
    onSurfaceVariant = PawseColors.DarkOnSurfaceVariant,
    surfaceContainer = PawseColors.DarkSurfaceContainer,
    surfaceContainerHigh = PawseColors.DarkSurfaceContainerHigh,
    outline = PawseColors.DarkOutline,
    outlineVariant = PawseColors.DarkOutlineVariant,
    // Primary is a near-neutral on purpose. Nothing in the chrome competes with
    // the two places colour is allowed to mean something.
    primary = PawseColors.DarkOnSurface,
    onPrimary = PawseColors.DarkBackground,
    secondary = PawseColors.DarkOnSurfaceVariant,
    onSecondary = PawseColors.DarkBackground,
)

private val LightScheme = lightColorScheme(
    background = PawseColors.LightBackground,
    onBackground = PawseColors.LightOnSurface,
    surface = PawseColors.LightSurface,
    onSurface = PawseColors.LightOnSurface,
    surfaceVariant = PawseColors.LightSurfaceContainer,
    onSurfaceVariant = PawseColors.LightOnSurfaceVariant,
    surfaceContainer = PawseColors.LightSurfaceContainer,
    surfaceContainerHigh = PawseColors.LightSurfaceContainerHigh,
    outline = PawseColors.LightOutline,
    outlineVariant = PawseColors.LightOutlineVariant,
    primary = PawseColors.LightOnSurface,
    onPrimary = PawseColors.LightBackground,
    secondary = PawseColors.LightOnSurfaceVariant,
    onSecondary = PawseColors.LightBackground,
)

/**
 * No dynamicColor parameter exists on this function, and that is the point.
 * There is no supported path to wallpaper-derived score colours.
 */
@Composable
fun PawseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    bedside: Boolean = false,
    /** Pawse's own reduce-motion switch, on top of the system's. */
    reduceMotion: Boolean = false,
    /** Shapes as well as colour on band dots. */
    shapes: Boolean = false,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalPawseSemantics provides when {
            bedside -> NightSemantics
            darkTheme -> DarkSemantics
            else -> LightSemantics
        },
        LocalReduceMotion provides (reduceMotion || systemReduceMotion()),
        LocalShapes provides shapes,
    ) {
        MaterialTheme(
            colorScheme = when {
                bedside -> NightScheme
                darkTheme -> DarkScheme
                else -> LightScheme
            },
            typography = PawseTypography,
            content = content,
        )
    }
}

object PawseTheme {
    val semantics: PawseSemantics
        @Composable @ReadOnlyComposable get() = LocalPawseSemantics.current
}
