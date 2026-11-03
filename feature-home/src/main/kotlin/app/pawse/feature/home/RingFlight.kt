package app.pawse.feature.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import app.pawse.core.ui.component.EaseInOut
import app.pawse.core.ui.component.EaseOut
import app.pawse.core.ui.component.LocalReduceMotion
import app.pawse.scoring.model.ScoreType
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The tapped ring flies into the detail ring over 360 ms on EaseInOut. The Scores
 * ring never moves: the detail's own ring starts where the tapped one sat, at its
 * size, and grows into place.
 *
 * ponytail: one hand-off slot, not a shared-element API. Scores writes where the
 * ring was on tap; the detail reads it once on arrival and clears it.
 */
internal object RingFlight {
    var origin: Rect? = null
    var type: ScoreType? = null
}

/** Records this ring's place on screen, for the flight that starts when it is tapped. */
internal fun Modifier.flightSource(onBounds: (Rect) -> Unit): Modifier =
    onGloballyPositioned { onBounds(it.boundsInWindow()) }

/** Flies the detail's ring in from where [type]'s ring sat on Scores, once. */
@Composable
internal fun Modifier.flightTarget(type: ScoreType): Modifier {
    val origin = remember { RingFlight.origin.takeIf { RingFlight.type == type } }
    val reduce = LocalReduceMotion.current
    val progress = remember { Animatable(if (origin == null || reduce) 1f else 0f) }
    var target by remember { mutableStateOf<Rect?>(null) }
    LaunchedEffect(Unit) {
        if (origin != null) {
            RingFlight.origin = null
            RingFlight.type = null
        }
        progress.animateTo(1f, tween(FLIGHT_MS, easing = EaseInOut))
    }
    return this
        .onGloballyPositioned { if (target == null) target = it.boundsInWindow() }
        .graphicsLayer {
            val from = origin ?: return@graphicsLayer
            val to = target
            if (to == null) {
                alpha = 0f
                return@graphicsLayer
            }
            val p = progress.value
            val start = from.width / to.width
            scaleX = start + (1f - start) * p
            scaleY = scaleX
            translationX = (from.center.x - to.center.x) * (1f - p)
            translationY = (from.center.y - to.center.y) * (1f - p)
        }
}

private const val FLIGHT_MS = 360

/**
 * The rest of the look-in, around the flight: the header fades in from 100 ms, then
 * four groups rise 8 dp at 220, 260, 300 and 340 ms, each over 160 ms. One set for
 * every page, so a group composed later (scrolled or swiped to) is simply there.
 */
@Stable
internal class LookIn(val flying: Boolean) {
    private val parts = List(DELAYS.size) { Animatable(if (flying) 0f else 1f) }

    fun progress(part: Int): Float = parts[part].value

    suspend fun run() = coroutineScope {
        parts.forEachIndexed { i, a -> launch { a.animateTo(1f, tween(PART_MS, delayMillis = DELAYS[i], easing = EaseOut)) } }
    }

    private companion object {
        val DELAYS = listOf(100, 220, 260, 300, 340)
        const val PART_MS = 160
    }
}

/** Part 0 is the header and only fades; the others also rise into place. */
internal fun Modifier.lookIn(look: LookIn, part: Int): Modifier = graphicsLayer {
    val p = look.progress(part)
    alpha = p
    if (part > 0) translationY = (1f - p) * 8.dp.toPx()
}
