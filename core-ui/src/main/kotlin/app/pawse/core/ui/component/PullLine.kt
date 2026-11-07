package app.pawse.core.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp

/**
 * The 2 dp line: it follows a pull ([fraction]), then runs while something is read.
 * Pawse's only loading mark; never a spinner.
 */
@Composable
fun PullLine(running: Boolean, fraction: Float = 0f, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val reduce = LocalReduceMotion.current
    val loop = rememberInfiniteTransition(label = "pull")
    // A 40% segment crosses on the in-out curve, 1400 ms a pass.
    val sweep by loop.animateFloat(
        initialValue = -0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = EaseInOut), RepeatMode.Restart),
        label = "sweep",
    )
    // Reduce motion: the whole line pulses its opacity instead.
    val pulse by loop.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    Canvas(modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp))) {
        when {
            running && reduce -> drawRect(ink.copy(alpha = pulse))
            running -> drawRect(ink, Offset(sweep * size.width, 0f), Size(size.width * 0.4f, size.height))
            fraction > 0f -> drawRect(ink, Offset.Zero, Size(size.width * fraction.coerceIn(0f, 1f), size.height))
        }
    }
}
