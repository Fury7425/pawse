package app.pawse.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * One family, light numerals, medium headings, tabular figures everywhere a metric
 * renders. Sizes and tracking follow the design canvas exactly.
 *
 * Pretendard is the intended face: de facto Korean UI standard, open-licensed
 * (SIL OFL), and its Latin carries numerals well enough that no second family is
 * needed for the hero digits.
 *
 * ASSUMPTION / one-line swap: the font binaries are not vendored in this repo.
 * Drop PretendardVariable.ttf into core-ui/src/main/res/font/ and change
 * [PawseFontFamily] to the commented line below. Until then the app renders in the
 * system face, whose Light and Medium weights carry the same hierarchy.
 */
val PawseFontFamily: FontFamily = FontFamily.Default
// val PawseFontFamily: FontFamily = FontFamily(Font(R.font.pretendard_variable))

/**
 * Tabular figures. Without this, a Recovery score ticking 68 -> 71 shifts width and
 * the hero number visibly jitters. Applied to every numeric style below, not left
 * to call sites to remember.
 */
private const val TABULAR = "tnum"

private val tightLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.Both,
)

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = PawseFontFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
    fontFeatureSettings = TABULAR,
)

/**
 * Big figures sit at 250 so they read light and exact. Pretendard Variable renders
 * it as drawn; a static system face snaps to its nearest weight, Light.
 */
private val NumeralWeight = FontWeight(250)

object PawseType {

    /** The one hero numeral on a screen: a day's kcal, a weight. */
    val Hero = style(84, 88, NumeralWeight, -4.0).copy(lineHeightStyle = tightLineHeight)

    /** Numerals inside a large ring, a portion's kcal, a BMR. */
    val Numeral = style(36, 44, NumeralWeight, -1.44)

    /** Numerals on an "Also today" card. */
    val NumeralSmall = style(32, 40, NumeralWeight, -1.2)

    /** Numerals inside a small ring. */
    val RingSmall = style(26, 30, NumeralWeight, -1.04)

    /** Onboarding's one big heading. */
    val Display = style(32, 40, FontWeight.Medium, -0.8)

    /** Today's warm line: larger and lighter than a title, so it reads as a sentence. */
    val Guidance = style(28, 34, FontWeight.Normal, -0.6)

    /** Screen titles. */
    val Title = style(24, 32, FontWeight.Medium, -0.48)

    /** Section heads, dates, product names. */
    val Heading = style(16, 24, FontWeight.Medium)

    val Body = style(14, 20, FontWeight.Normal)

    /** Button and tab labels. */
    val BodyStrong = style(14, 20, FontWeight.Medium)

    /** Secondary numerals: a metric's raw value on a baseline row. */
    val MetricValue = style(17, 22, FontWeight.Medium)

    /** Signed point contribution: -7, +3. */
    val Delta = style(14, 18, FontWeight.SemiBold)

    /** Dense labels. Metric names, units, coverage. */
    val Label = style(13, 18, FontWeight.Normal)
}

/**
 * Material 3 typography, retuned to the canvas so every Material component that
 * reaches for a role lands on the same scale as the hand-built ones.
 */
val PawseTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = PawseFontFamily, fontWeight = NumeralWeight, fontFeatureSettings = TABULAR),
        displayMedium = displayMedium.copy(fontFamily = PawseFontFamily, fontWeight = NumeralWeight, fontFeatureSettings = TABULAR),
        displaySmall = PawseType.Numeral,
        headlineLarge = PawseType.Display,
        headlineMedium = PawseType.Title,
        headlineSmall = PawseType.Title,
        titleLarge = PawseType.Title,
        titleMedium = PawseType.Heading,
        titleSmall = PawseType.BodyStrong,
        bodyLarge = style(16, 24, FontWeight.Normal),
        bodyMedium = PawseType.Body,
        bodySmall = PawseType.Label,
        labelLarge = PawseType.BodyStrong,
        labelMedium = PawseType.Label,
        labelSmall = PawseType.Label,
    )
}
