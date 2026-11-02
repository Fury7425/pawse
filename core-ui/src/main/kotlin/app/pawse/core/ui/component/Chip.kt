package app.pawse.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.pawse.core.ui.theme.PawseType

/**
 * A tag: "Temporary", "Written on this phone", "Lower confidence". Says where words
 * came from or that a figure is provisional.
 *
 * Deliberately not Material's `AssistChip`, which is an interactive affordance
 * with a touch target and a ripple. Nothing here is tappable; these are labels
 * that happen to be enclosed, and dressing a label as a control is how a calm
 * screen turns into a cockpit.
 *
 * Raised fill and secondary text, 22 tall, 6 radius: never a band colour.
 */
@Composable
fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text = text,
        style = PawseType.Micro.copy(letterSpacing = PawseType.Label.letterSpacing),
        color = contentColor,
        maxLines = 1,
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
