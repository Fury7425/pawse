package app.pawse.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Fixed palette. Dynamic color is deliberately off.
 *
 * Material You repaints the palette from the wallpaper. Recovery bands are
 * semantic — red means "do not train today" — and a teal wallpaper must not
 * produce a teal red zone. So the score colours are constants, not derived, and
 * [PawseTheme] never calls dynamicDarkColorScheme.
 *
 * Colour appears in exactly two places in this app: score band state, and
 * direction of deviation from baseline. Neutral grays everywhere else. That
 * restraint, not component styling, is most of what separates a calm app from a
 * cockpit.
 *
 * Dark is the design reference. This app is opened in bed before the lights are
 * on. Light mode exists and is correct, but it is tuned second.
 */
object PawseColors {

    // --- Dark (primary target) -------------------------------------------------
    val DarkBackground = Color(0xFF0E1012)
    val DarkSurface = Color(0xFF14171A)
    val DarkSurfaceContainer = Color(0xFF1A1E22)
    val DarkSurfaceContainerHigh = Color(0xFF22272C)
    val DarkOutline = Color(0xFF343A40)
    val DarkOutlineVariant = Color(0xFF262B30)
    val DarkOnSurface = Color(0xFFEDEFF1)
    val DarkOnSurfaceVariant = Color(0xFF9BA3AB)
    val DarkOnSurfaceFaint = Color(0xFF6B747C)

    // --- Light -----------------------------------------------------------------
    val LightBackground = Color(0xFFFBFBFC)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceContainer = Color(0xFFF2F3F5)
    val LightSurfaceContainerHigh = Color(0xFFE9EBEE)
    val LightOutline = Color(0xFFC6CBD1)
    val LightOutlineVariant = Color(0xFFDFE3E7)
    val LightOnSurface = Color(0xFF15181B)
    val LightOnSurfaceVariant = Color(0xFF515A62)
    val LightOnSurfaceFaint = Color(0xFF79838B)

    // --- Band state (the first of the two places colour is allowed) -------------
    // Bands are 1-33 / 34-66 / 67-100, the split documented identically for Whoop
    // and Bevel. Every one of these is paired with a text label or shape in the UI;
    // band state is never encoded in hue alone.
    val BandLowDark = Color(0xFFFF7A6B)
    val BandModerateDark = Color(0xFFF5C451)
    val BandHighDark = Color(0xFF5CD6A0)

    val BandLowLight = Color(0xFFB3261E)
    val BandModerateLight = Color(0xFF8A5A00)
    val BandHighLight = Color(0xFF14684A)

    // --- Deviation direction (the second) --------------------------------------
    // Used only on the baseline-band row, to mark whether tonight landed above or
    // below the personal baseline. Same hues as band state on purpose: two colour
    // vocabularies in one app is one too many.
    val BetterDark = BandHighDark
    val WorseDark = BandLowDark
    val NeutralDark = Color(0xFF7E878F)

    val BetterLight = BandHighLight
    val WorseLight = BandLowLight
    val NeutralLight = Color(0xFF6C757D)

    /** Fill of the mu +/- sigma band on the baseline row. Chrome, not signal. */
    val BaselineBandDark = Color(0xFF2A3036)
    val BaselineBandLight = Color(0xFFE3E7EB)

    /** The fainter +/-2 sigma extent behind it. */
    val BaselineOuterDark = Color(0xFF1C2126)
    val BaselineOuterLight = Color(0xFFEFF2F5)
}
