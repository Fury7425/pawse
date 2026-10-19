package app.pawse.feature.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pawse.core.data.profile.Appearance
import app.pawse.core.ui.component.ChoiceRow
import app.pawse.core.ui.component.GhostButton
import app.pawse.core.ui.component.GlyphButton
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.PawseField
import app.pawse.core.ui.component.PawseSheet
import app.pawse.core.ui.component.PrimaryButton
import app.pawse.core.ui.component.Segmented
import app.pawse.core.ui.theme.PawseType
import app.pawse.scoring.model.BiologicalSex
import app.pawse.scoring.model.Calibration
import app.pawse.scoring.model.UserProfile
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The only things this app asks a user to type, plus how the model has learned.
 *
 * Age and sex feed two published equations, Tanaka's HRmax and Banister's TRIMP;
 * Health Connect has a record type for neither. Below them: whether Body tracks
 * how you feel, every change the model made with its date, and one button back to
 * the defaults.
 */
@Composable
fun ProfileDialog(
    initialProfile: UserProfile?,
    initialShowNumbers: Boolean,
    initialAppearance: Appearance,
    calibration: Calibration?,
    onResetModel: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (
        ageYears: Int?,
        sex: BiologicalSex,
        measuredMaxHeartRate: Double?,
        showNumbers: Boolean,
        appearance: Appearance,
    ) -> Unit,
) {
    var appearance by remember { mutableStateOf(initialAppearance) }
    var age by remember { mutableStateOf(initialProfile?.ageYears?.toString().orEmpty()) }
    var maxHr by remember { mutableStateOf(initialProfile?.measuredMaxHeartRate?.toInt()?.toString().orEmpty()) }
    var sex by remember { mutableStateOf(initialProfile?.biologicalSex ?: BiologicalSex.UNSPECIFIED) }
    var showNumbers by remember { mutableStateOf(initialShowNumbers) }
    val estimate = age.toIntOrNull()?.takeIf { it in 10..110 }?.let { (208 - 0.7 * it).toInt() }

    PawseSheet(onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("You and your settings", style = PawseType.Title, modifier = Modifier.weight(1f))
                GlyphButton(Glyphs.Close, "Close", onClick = onDismiss)
            }
            Spacer(Modifier.height(6.dp))
            Caption(
                "Training load divides by your heart-rate range, and Health Connect has no record of age or sex. " +
                    "Leave either out and Pawse says so wherever it matters.",
                body = true,
            )

            Spacer(Modifier.height(20.dp))
            Caption("Age")
            Spacer(Modifier.height(6.dp))
            PawseField(
                value = age,
                onValueChange = { new -> age = new.filter { it.isDigit() }.take(3) },
                suffix = "years",
                numeric = true,
            )

            Spacer(Modifier.height(16.dp))
            Caption("Sex, for the training-load curve")
            Spacer(Modifier.height(6.dp))
            ChoiceRow(
                options = listOf(BiologicalSex.FEMALE, BiologicalSex.MALE, BiologicalSex.UNSPECIFIED),
                selected = sex,
                onSelect = { sex = it },
                label = {
                    when (it) {
                        BiologicalSex.MALE -> "Male"
                        BiologicalSex.FEMALE -> "Female"
                        BiologicalSex.UNSPECIFIED -> "Rather not say"
                    }
                },
            )

            Spacer(Modifier.height(16.dp))
            Caption("Measured max heart rate · optional")
            Spacer(Modifier.height(6.dp))
            PawseField(
                value = maxHr,
                onValueChange = { new -> maxHr = new.filter { it.isDigit() }.take(3) },
                suffix = "bpm",
                placeholder = estimate?.let { "Estimated $it from your age" },
                numeric = true,
                textStyle = PawseType.Body,
            )

            Spacer(Modifier.height(16.dp))
            Caption("Numbers on the cards")
            Spacer(Modifier.height(6.dp))
            Segmented(
                options = listOf(true, false),
                selected = showNumbers,
                onSelect = { showNumbers = it },
                label = { if (it) "Show" else "Words only" },
            )

            Spacer(Modifier.height(16.dp))
            Caption("Appearance")
            Spacer(Modifier.height(6.dp))
            Segmented(
                options = Appearance.entries,
                selected = appearance,
                onSelect = { appearance = it },
                label = {
                    when (it) {
                        Appearance.SYSTEM -> "Phone"
                        Appearance.DARK -> "Dark"
                        Appearance.LIGHT -> "Light"
                        Appearance.BEDSIDE -> "Bedside"
                    }
                },
            )

            calibration?.let { c ->
                Spacer(Modifier.height(24.dp))
                Text("Your model", style = PawseType.Heading)
                Spacer(Modifier.height(6.dp))
                Caption(
                    c.bodyMatch?.let {
                        "Body matched how you felt: r = ${String.format(Locale.getDefault(), "%.2f", it)} " +
                            "over ${c.checkIns} mornings." + if (kotlin.math.abs(it) < 0.1) " Weigh the score lightly." else ""
                    } ?: "Check in a few mornings and Pawse will show how well Body tracks how you feel.",
                    body = true,
                )
                Spacer(Modifier.height(6.dp))
                Caption(
                    "HRV's share of Body: ${(c.hrvWeight * 100).toInt()}%. " +
                        "After 30 check-ins it adapts to you, slowly.",
                )
                c.changes.takeLast(MAX_CHANGES).reversed().forEach { change ->
                    Caption(
                        "${change.date.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))} · " +
                            "${change.what}: ${String.format(Locale.getDefault(), "%.2f", change.from)} → " +
                            String.format(Locale.getDefault(), "%.2f", change.to),
                    )
                }
                GhostButton("Reset to defaults", onClick = onResetModel)
            }

            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                text = "Save",
                onClick = {
                    onSave(
                        age.toIntOrNull()?.takeIf { it in 10..110 },
                        sex,
                        maxHr.toDoubleOrNull()?.takeIf { it in 100.0..230.0 },
                        showNumbers,
                        appearance,
                    )
                },
            )
        }
    }
}

private const val MAX_CHANGES = 6

@Composable
private fun Caption(text: String, body: Boolean = false) {
    Text(
        text,
        style = if (body) PawseType.Body else PawseType.Label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
