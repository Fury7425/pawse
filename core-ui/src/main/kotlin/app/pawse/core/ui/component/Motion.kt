package app.pawse.core.ui.component

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** The canvas's durations in ms, in one place so motion stays one family. */
object PawseMotion {
    const val PRESS_DOWN = 90
    const val PRESS_UP = 160
    const val SWAP = 200
    const val REVEAL = 220
    const val SHEET = 240
    const val PUSH = 300
    /** Rings fill and charts draw left to right, first draw only. */
    const val FIRST_DRAW = 700
    /** A changed value glides to its new place. */
    const val VALUE = 400
}

/**
 * True when movement should give way to plain fades: the system's Remove
 * animations is on, or Pawse's own switch is, since Android's is all-or-nothing.
 * [app.pawse.core.ui.theme.PawseTheme] provides it.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * Shapes as well as colour: ▲ above, ▼ below, ■ illness, ● typical. Android has no
 * Differentiate Without Colour, so Pawse offers its own.
 */
val LocalShapes = staticCompositionLocalOf { false }

/** The system's Remove animations: an animator duration scale of 0. */
@Composable
fun systemReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}
