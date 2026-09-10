package app.pawse.core.ui.component

import androidx.compose.foundation.layout.Arrangement
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
 * The one hero numeral, and everything that qualifies it.
 *
 * The rule this component exists to enforce: a number is never shown on its own.
 * [coverage] sits under it every time, because a Recovery of 71 built from four
 * inputs and a Recovery of 71 built from two are different claims, and the app
 * that prints them identically is lying by omission.
 *
 * There is one hero per screen. If a second number ever wants this treatment, the
 * screen has the wrong shape.
 */
@Composable
fun ScoreHero(
    label: String,
    value: String,
    supporting: String,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    bandWord: String? = null,
    bandColor: Color? = null,
    coverage: String? = null,
    flags: List<String> = emptyList(),
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = PawseType.Hero,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (suffix != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = suffix,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
            }
        }

        if (bandWord != null && bandColor != null) {
            Spacer(Modifier.height(4.dp))
            BandDot(color = bandColor, label = bandWord)
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = supporting,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        if (coverage != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = coverage,
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (flags.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                flags.forEach { Chip(text = it) }
            }
        }
    }
}
