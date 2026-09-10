package app.pawse.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import app.pawse.core.ui.theme.PawseType
import app.pawse.scoring.config.StrainScale
import app.pawse.scoring.model.BiologicalSex
import app.pawse.scoring.model.UserProfile

/**
 * The only thing this app ever asks a user to type.
 *
 * Age and biological sex are not profile decoration: they are inputs to two
 * published equations. Tanaka's `HRmax = 208 - 0.7 x age` is what anchors
 * heart-rate reserve, and Banister published TRIMP as two sex-specific curves.
 * Health Connect has a record type for neither.
 *
 * Sex is optional and stays optional. The engine's `UNSPECIFIED` case takes the
 * midpoint of Banister's two curves and tags the result as our choice rather than
 * silently scoring everyone as male — the two diverge by about 18% at 40% of
 * heart-rate reserve and by under 5% at maximum, so the cost of not knowing is
 * real at easy intensities and small at hard ones. That is a fair price for not
 * demanding the answer.
 */
@Composable
fun ProfileDialog(
    initialProfile: UserProfile?,
    initialScale: StrainScale?,
    onDismiss: () -> Unit,
    onSave: (ageYears: Int?, sex: BiologicalSex, measuredMaxHeartRate: Double?, scale: StrainScale?) -> Unit,
) {
    var age by remember { mutableStateOf(initialProfile?.ageYears?.toString().orEmpty()) }
    var maxHr by remember {
        mutableStateOf(initialProfile?.measuredMaxHeartRate?.toInt()?.toString().orEmpty())
    }
    var sex by remember { mutableStateOf(initialProfile?.biologicalSex ?: BiologicalSex.UNSPECIFIED) }
    var scale by remember { mutableStateOf(initialScale ?: StrainScale.BEVEL_100) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        age.toIntOrNull()?.takeIf { it in 10..110 },
                        sex,
                        maxHr.toDoubleOrNull()?.takeIf { it in 100.0..230.0 },
                        scale,
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("You, and your scales") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Your watch cannot tell us these, and the load model divides by them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = age,
                    onValueChange = { new -> age = new.filter { it.isDigit() }.take(3) },
                    label = { Text("Age") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = maxHr,
                    onValueChange = { new -> maxHr = new.filter { it.isDigit() }.take(3) },
                    label = { Text("Measured maximum heart rate (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "If you have a real figure from a test, it replaces the age estimate. " +
                        "A true maximum can sit twenty beats either side of the formula.",
                    style = PawseType.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(16.dp))
                Text("Biological sex", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column {
                    BiologicalSex.entries.forEach { option ->
                        Choice(
                            label = when (option) {
                                BiologicalSex.MALE -> "Male"
                                BiologicalSex.FEMALE -> "Female"
                                BiologicalSex.UNSPECIFIED -> "Rather not say"
                            },
                            selected = sex == option,
                            onSelect = { sex = option },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("Strain scale", style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column {
                    StrainScale.entries.forEach { option ->
                        Choice(
                            label = when (option) {
                                StrainScale.BEVEL_100 -> "0 to 100"
                                StrainScale.WHOOP_21 -> "0 to 21"
                            },
                            selected = scale == option,
                            onSelect = { scale = option },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Same curve, different ceiling. Switching relabels the number and never " +
                        "rewrites your history.",
                    style = PawseType.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
