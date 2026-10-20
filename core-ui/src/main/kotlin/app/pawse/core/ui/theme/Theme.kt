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
    /** The one-pixel top highlight on cards. Chrome, not signal. */
    val highlight: Color,
    /** Light falling on a card's top edge, fading out by two thirds down. Transparent turns it off. */
    val sheen: Color,
    /** The one deep, soft shadow under a card. Transparent turns it off. */
    val cardShadow: Color,
    /** A faint cool light from the top of each screen. Dark only. */
    val aura: Color,
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
    glass = PawseColors.DarkSurface.copy(alpha = 0.9f),
    glassLine = Color.White.copy(alpha = 0.10f),
    highlight = Color.White.copy(alpha = 0.07f),
    sheen = Color.White.copy(alpha = 0.045f),
    cardShadow = Color.Black.copy(alpha = 0.85f),
    aura = Color(0xFF96ACD2).copy(alpha = 0.09f),
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
    highlight = Color.White.copy(alpha = 0.7f),
    sheen = Color.Transparent,
    cardShadow = PawseColors.LightOnSurface.copy(alpha = 0.16f),
    aura = Color.Transparent,
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
    highlight = Color.White.copy(alpha = 0.03f),
    // Bedside stays flat: no sheen, no shadow, no glow in a dark room.
    sheen = Color.Transparent,
    cardShadow = Color.Transparent,
    aura = Color.Transparent,
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
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalPawseSemantics provides when {
            bedside -> NightSemantics
            darkTheme -> DarkSemantics
            else -> LightSemantics
        },
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
