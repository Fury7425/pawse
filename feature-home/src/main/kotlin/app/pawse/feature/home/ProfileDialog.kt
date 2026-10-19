package app.pawse.feature.home

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
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
import app.pawse.core.ui.component.GlyphButton
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.PawseField
import app.pawse.core.ui.component.PawseSheet
import app.pawse.core.ui.component.PrimaryButton
import app.pawse.core.ui.component.Segmented
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
    initialAppearance: Appearance,
    onDismiss: () -> Unit,
    onSave: (
        ageYears: Int?,
        sex: BiologicalSex,
        measuredMaxHeartRate: Double?,
        scale: StrainScale?,
        appearance: Appearance,
    ) -> Unit,
) {
    var appearance by remember { mutableStateOf(initialAppearance) }
    var age by remember { mutableStateOf(initialProfile?.ageYears?.toString().orEmpty()) }
    var maxHr by remember {
        mutableStateOf(initialProfile?.measuredMaxHeartRate?.toInt()?.toString().orEmpty())
    }
    var sex by remember { mutableStateOf(initialProfile?.biologicalSex ?: BiologicalSex.UNSPECIFIED) }
    var scale by remember { mutableStateOf(initialScale ?: StrainScale.BEVEL_100) }
    val estimate = age.toIntOrNull()?.takeIf { it in 10..110 }?.let { (208 - 0.7 * it).toInt() }

    PawseSheet(onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("You and your scales", style = PawseType.Title, modifier = Modifier.weight(1f))
                GlyphButton(Glyphs.Close, "Close", onClick = onDismiss)
            }
            Spacer(Modifier.height(6.dp))
            Caption(
                "Strain divides by your heart-rate range, and Health Connect has no record of age or sex. " +
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
            Caption("Sex, for the strain curve")
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
            Spacer(Modifier.height(6.dp))
            Caption(
                if (sex == BiologicalSex.UNSPECIFIED) {
                    "Pawse uses the midpoint of the two published curves and labels it as its own choice."
                } else {
                    "Banister published TRIMP as two curves; this picks the matching one."
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
            Spacer(Modifier.height(6.dp))
            Caption(
                "If you have a real figure from a test, it replaces the age estimate. " +
                    "A true maximum can sit twenty beats either side of the formula.",
            )

            Spacer(Modifier.height(16.dp))
            Caption("Show Strain out of")
            Spacer(Modifier.height(6.dp))
            Segmented(
                options = listOf(StrainScale.BEVEL_100, StrainScale.WHOOP_21),
                selected = scale,
                onSelect = { scale = it },
                label = { if (it == StrainScale.BEVEL_100) "100" else "21" },
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
            Spacer(Modifier.height(6.dp))
            Caption("Bedside is true black and dimmer, for reading before the lights are on.")

            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                text = "Save and rescore",
                onClick = {
                    onSave(
                        age.toIntOrNull()?.takeIf { it in 10..110 },
                        sex,
                        maxHr.toDoubleOrNull()?.takeIf { it in 100.0..230.0 },
                        scale,
                        appearance,
                    )
                },
            )
        }
    }
}

@Composable
private fun Caption(text: String, body: Boolean = false) {
    Text(
        text,
        style = if (body) PawseType.Body else PawseType.Label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
