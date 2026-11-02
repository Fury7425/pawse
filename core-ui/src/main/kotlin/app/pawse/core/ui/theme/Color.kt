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
    // Deep graphite with cards lit from above. Hairlines are white at 7% so they
    // catch the light on any layer instead of reading as a grey stroke.
    val DarkBackground = Color(0xFF0A0B0D)
    val DarkSurface = Color(0xFF111317)
    val DarkSurfaceContainer = Color(0xFF15181C)
    /** Raised: controls on cards. Moved apart from the card so it reads on one. */
    val DarkSurfaceContainerHigh = Color(0xFF20252B)
    val DarkOutline = Color(0xFF30363D)
    val DarkOutlineVariant = Color(0x12FFFFFF)
    val DarkOnSurface = Color(0xFFF3F4F6)
    val DarkOnSurfaceVariant = Color(0xFF9DA5AE)
    /** Micro text: sync lines, captions, axis labels. 4.5:1 on the background. */
    val DarkOnSurfaceFaint = Color(0xFF8A939B)
    /** Non-text marks only (meal dots, raw weigh-ins): the old faint, which failed as text. */
    val DarkMark = Color(0xFF6B747C)
    /** The selection fill: a chosen chip, the check-in, a coachmark. */
    val DarkSelection = Color(0xFFE9EBEE)
    val DarkSegmentThumb = Color(0xFF2A3038)
    val DarkToast = Color(0xFF2A3038)
    val DarkSheet = Color(0xFF171A1F)

    // --- Light -----------------------------------------------------------------
    val LightBackground = Color(0xFFF2F3F5)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceContainer = Color(0xFFFFFFFF)
    val LightSurfaceContainerHigh = Color(0xFFE9EBEE)
    val LightOutline = Color(0xFFC6CBD1)
    val LightOutlineVariant = Color(0x1415181B)
    val LightOnSurface = Color(0xFF15181B)
    val LightOnSurfaceVariant = Color(0xFF515A62)
    val LightOnSurfaceFaint = Color(0xFF5F6870)
    val LightMark = Color(0xFF8B949C)
    val LightSelection = Color(0xFF15181B)
    val LightSegmentThumb = Color(0xFFFFFFFF)
    val LightToast = Color(0xFF15181B)
    val LightSheet = Color(0xFFFFFFFF)

    // --- Bedside --------------------------------------------------------------
    // True black and dimmer band hues, for a phone read with the lights off. Same
    // roles as dark, every value pulled down so nothing glares.
    val NightBackground = Color(0xFF000000)
    val NightSurface = Color(0xFF0A0B0C)
    val NightSurfaceContainer = Color(0xFF111315)
    val NightSurfaceContainerHigh = Color(0xFF181B1E)
    val NightOutline = Color(0xFF2A2F34)
    val NightOutlineVariant = Color(0xFF1C2024)
    val NightOnSurface = Color(0xFFD9DCDF)
    val NightOnSurfaceVariant = Color(0xFF8A929A)
    val NightOnSurfaceFaint = Color(0xFF80888F)
    val NightMark = Color(0xFF565E65)
    val NightSelection = Color(0xFFC9CDD1)
    val NightSegmentThumb = Color(0xFF1F2428)
    val NightToast = Color(0xFF181B1E)
    val NightSheet = Color(0xFF0A0B0C)
    val BandLowNight = Color(0xFFE56B5E)
    val BandModerateNight = Color(0xFFDDB04A)
    val BandHighNight = Color(0xFF4FBF8E)
    val NeutralNight = Color(0xFF6E777F)
    val BaselineBandNight = Color(0xFF1F2428)
    val BaselineOuterNight = Color(0xFF121518)

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
    val BaselineBandDark = Color(0xFF2D333B)
    val BaselineBandLight = Color(0xFFCDD3D9)

    /** The track: an empty ring, an empty bar, the +/-3 sigma extent behind the band. */
    val BaselineOuterDark = Color(0xFF1F2329)
    val BaselineOuterLight = Color(0xFFE3E7EB)
}
