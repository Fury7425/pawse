package app.pawse.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
 * A small qualifier: "Still settling", "Our default", "Lower confidence".
 *
 * Deliberately not Material's `AssistChip`, which is an interactive affordance
 * with a touch target and a ripple. Nothing here is tappable; these are labels
 * that happen to be enclosed, and dressing a label as a control is how a calm
 * screen turns into a cockpit.
 *
 * Chrome only — outline and surface, never a band colour. Colour in this app means
 * score state or deviation direction, and a provenance tag is neither.
 */
@Composable
fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text = text,
        style = PawseType.Label,
        color = contentColor,
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(6.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
