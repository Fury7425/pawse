package app.pawse.core.ui.component

import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

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

/**
 * The canvas's haptics map. A haptic confirms something saved, a threshold crossed,
 * a page or tick snapped or a scan resolved; plain taps stay silent. Everything goes
 * through [View.performHapticFeedback], so the system's touch-feedback setting wins.
 */
enum class PawseHaptic {
    /** Saved or recognised: check-in, Again, plate logged, barcode found, label parsed, seventh night. */
    Confirm,
    /** A gentle no: barcode not found, a rail edge. */
    Reject,
    /** A discrete step landed: the detail pager, a rail week. */
    Tick,
    /** Fine steps under the finger: chart scrub, a whole kilogram on the ruler. */
    FineTick,
    /** A day stepped with ‹ ›, a chart tap or a swap. */
    Step,
    /** Release now and it happens: pull, sheet drag, predictive back. */
    Threshold,
    /** Backed off one of those. */
    ThresholdOff,
    ToggleOn,
    ToggleOff,
    /** The weigh-in ruler crossing 0.1 kg. */
    Ruler,
    /** A day pinned by holding it. */
    LongPress,
}

fun View.haptic(kind: PawseHaptic) {
    val api34 = Build.VERSION.SDK_INT >= 34
    val api30 = Build.VERSION.SDK_INT >= 30
    val constant = when (kind) {
        PawseHaptic.Confirm -> if (api30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        PawseHaptic.Reject -> if (api30) HapticFeedbackConstants.REJECT else return
        PawseHaptic.Tick -> if (api34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
        PawseHaptic.FineTick -> if (api34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK
        PawseHaptic.Step -> HapticFeedbackConstants.CLOCK_TICK
        PawseHaptic.Threshold -> if (api34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CONTEXT_CLICK
        PawseHaptic.ThresholdOff -> if (api34) HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE else return
        PawseHaptic.ToggleOn -> if (api34) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.CONTEXT_CLICK
        PawseHaptic.ToggleOff -> if (api34) HapticFeedbackConstants.TOGGLE_OFF else HapticFeedbackConstants.CONTEXT_CLICK
        PawseHaptic.Ruler -> HapticFeedbackConstants.TEXT_HANDLE_MOVE
        PawseHaptic.LongPress -> HapticFeedbackConstants.LONG_PRESS
    }
    performHapticFeedback(constant)
}

/** [haptic] on this screen's view. */
@Composable
fun rememberHaptic(): (PawseHaptic) -> Unit {
    val view = LocalView.current
    return remember(view) { { kind -> view.haptic(kind) } }
}

/** A switch's haptic: on or off by where it lands. */
fun toggleHaptic(on: Boolean): PawseHaptic = if (on) PawseHaptic.ToggleOn else PawseHaptic.ToggleOff
