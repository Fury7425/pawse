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
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalPawseSemantics provides if (darkTheme) DarkSemantics else LightSemantics,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = PawseTypography,
            content = content,
        )
    }
}

object PawseTheme {
    val semantics: PawseSemantics
        @Composable @ReadOnlyComposable get() = LocalPawseSemantics.current
}
