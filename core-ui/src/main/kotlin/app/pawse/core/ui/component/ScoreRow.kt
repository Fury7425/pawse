package app.pawse.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.pawse.core.ui.theme.PawseType

/**
 * A secondary score: name, number, state, and one line saying what it means.
 *
 * The supporting line is not optional. A row that reads "Strain 62" and stops is
 * the twelve-tile dashboard this app exists not to be; the sentence is what turns
 * a reading into something a person can act on or ignore.
 */
@Composable
fun ScoreRow(
    label: String,
    value: String,
    supporting: String,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    bandWord: String? = null,
    bandColor: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = value,
                style = PawseType.MetricValue,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (suffix != null) {
                Spacer(Modifier.width(3.dp))
                Text(
                    text = suffix,
                    style = PawseType.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (bandWord != null && bandColor != null) {
            Spacer(Modifier.height(6.dp))
            BandDot(color = bandColor, label = bandWord)
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = supporting,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
