package app.pawse.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * One family, three sizes on a score screen, tabular figures everywhere a metric
 * renders.
 *
 * Pretendard is the intended face: de facto Korean UI standard, open-licensed
 * (SIL OFL), and its Latin carries numerals well enough that no second family is
 * needed for the hero digits.
 *
 * ASSUMPTION / one-line swap: the font binaries are not vendored in this repo.
 * Drop PretendardVariable.ttf into core-ui/src/main/res/font/ and change
 * [PawseFontFamily] to the commented line below. Until then the app renders in the
 * system face, which is correct but not the design reference.
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

object PawseType {

    /** The one hero numeral on Home. Never more than one per screen. */
    val Hero = TextStyle(
        fontFamily = PawseFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 84.sp,
        lineHeight = 88.sp,
        letterSpacing = (-3).sp,
        fontFeatureSettings = TABULAR,
        lineHeightStyle = tightLineHeight,
    )

    /** Secondary numerals: a metric's raw value on a baseline row. */
    val MetricValue = TextStyle(
        fontFamily = PawseFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontFeatureSettings = TABULAR,
    )

    /** Signed point contribution: -7, +3. */
    val Delta = TextStyle(
        fontFamily = PawseFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        fontFeatureSettings = TABULAR,
    )

    /** Dense labels. Metric names, units, coverage. */
    val Label = TextStyle(
        fontFamily = PawseFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
    )
}

/**
 * Material 3 typography, retuned to the family. Body and title styles carry
 * tabular figures too, because coverage percentages and dates render in them.
 */
val PawseTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = PawseFontFamily, fontFeatureSettings = TABULAR),
        displayMedium = displayMedium.copy(fontFamily = PawseFontFamily, fontFeatureSettings = TABULAR),
        displaySmall = displaySmall.copy(fontFamily = PawseFontFamily, fontFeatureSettings = TABULAR),
        headlineLarge = headlineLarge.copy(fontFamily = PawseFontFamily),
        headlineMedium = headlineMedium.copy(fontFamily = PawseFontFamily),
        headlineSmall = headlineSmall.copy(fontFamily = PawseFontFamily),
        titleLarge = titleLarge.copy(fontFamily = PawseFontFamily),
        titleMedium = titleMedium.copy(fontFamily = PawseFontFamily),
        titleSmall = titleSmall.copy(fontFamily = PawseFontFamily),
        bodyLarge = bodyLarge.copy(fontFamily = PawseFontFamily, fontFeatureSettings = TABULAR),
        bodyMedium = bodyMedium.copy(fontFamily = PawseFontFamily, fontFeatureSettings = TABULAR),
        bodySmall = bodySmall.copy(fontFamily = PawseFontFamily, fontFeatureSettings = TABULAR),
        labelLarge = labelLarge.copy(fontFamily = PawseFontFamily),
        labelMedium = labelMedium.copy(fontFamily = PawseFontFamily),
        labelSmall = labelSmall.copy(fontFamily = PawseFontFamily),
    )
}
