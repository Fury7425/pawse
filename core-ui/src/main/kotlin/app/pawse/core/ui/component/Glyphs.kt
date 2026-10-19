package app.pawse.core.ui.component

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's line icons, as SVG path data on a 24-unit grid.
 *
 * One stroke weight, round caps, no fills: the same drawing the design canvas uses,
 * so a glyph means the same thing on every screen. Each score has one, and it is
 * used wherever that score is named.
 */
object Glyphs {
    const val SLEEP = "M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z"
    const val RECOVERY = "M3 12h4l2-5l4 10l2-5h6"
    const val STRAIN = "M13 3L5 14h6l-1 7l8-11h-6z"
    const val ENERGY = "M5 7h12a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2zM21 11v2M7 10v4M10 10v4"
    const val LOAD = "M5 20v-6M10 20V9M15 20v-8M20 20V5"

    const val TODAY = "M5 10h14a2 2 0 0 1 0 4H5a2 2 0 0 1 0-4zM15 6.5v11"
    const val FOOD = "M3 11h18a9 9 0 0 1-18 0zM8 7.5c0-1.4 1-1.8 1-3.2M12 7.5c0-1.4 1-1.8 1-3.2M16 7.5c0-1.4 1-1.8 1-3.2"
    const val BODY = "M6 3h12a3 3 0 0 1 3 3v12a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V6a3 3 0 0 1 3-3zM8.5 10a3.5 3.5 0 0 1 7 0M12 10l1.6-1.6"
    const val SCAN = "M3 7V5a2 2 0 0 1 2-2h2M17 3h2a2 2 0 0 1 2 2v2M21 17v2a2 2 0 0 1-2 2h-2M7 21H5a2 2 0 0 1-2-2v-2" +
        "M7 8v8M10 8v8M13.5 8v8M17 8v8"

    const val REFRESH = "M20 12a8 8 0 1 1-2.34-5.66M20 4v5h-5"
    const val PERSON = "M8 8a4 4 0 1 0 8 0a4 4 0 1 0-8 0M4 21c0-4 3.6-7 8-7s8 3 8 7"
    const val CHECK = "M5 12.5l4.5 4.5L19 7"
    const val ALERT = "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0M12 8v5M12 16h.01"
    const val CLOCK = "M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18zM12 7v5l3 2"
    const val CHEVRON = "M9 6l6 6l-6 6"
    const val BACK = "M15 6l-6 6l6 6"
    const val CLOSE = "M6 6l12 12M18 6L6 18"
    const val SLIDERS = "M4 7h9M17 7h3M4 17h3M11 17h9M13 7a2 2 0 1 0 4 0a2 2 0 1 0-4 0M7 17a2 2 0 1 0 4 0a2 2 0 1 0-4 0"
    const val PLUS = "M12 5v14M5 12h14"
    const val TRASH = "M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"
    const val PENCIL = "M4 20h4L19 9l-4-4L4 16z"
    const val ARROW = "M5 12h14M13 6l6 6l-6 6"
    const val LOCK = "M7 11h10a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2zM8 11V8a4 4 0 0 1 8 0v3"

    /** In Health Connect: the refresh circle closed by a tick. */
    const val IN_HEALTH_CONNECT = "M20 12a8 8 0 1 1-2.34-5.66M9 12l2 2l4-4"

    /** Where a food's figures came from. */
    const val SOURCE_DATABASE = "M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18zM3 12h18M12 3c2.5 2.7 2.5 15.3 0 18M12 3c-2.5 2.7-2.5 15.3 0 18"
    const val SOURCE_LABEL = "M6 3h12v18H6zM9 7h6M9 11h6M9 15h4"
    const val SOURCE_TYPED = PENCIL
}

@Composable
fun Glyph(
    path: String,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    tint: Color = LocalContentColor.current,
    strokeWidth: Float = 1.75f,
) {
    val vector = remember(path, strokeWidth) { glyphVector(path, strokeWidth) }
    Icon(imageVector = vector, contentDescription = null, modifier = modifier.size(size), tint = tint)
}

private fun glyphVector(path: String, strokeWidth: Float): ImageVector =
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = addPathNodes(path),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        .build()
