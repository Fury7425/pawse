package app.pawse.feature.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pawse.core.data.profile.Appearance
import app.pawse.core.ui.component.Choices
import app.pawse.core.ui.component.FieldLabel
import app.pawse.core.ui.component.PawseSheet
import app.pawse.core.ui.component.PrimaryButton
import app.pawse.core.ui.component.Segmented
import app.pawse.core.ui.component.UnitField
import app.pawse.core.ui.theme.PawseType
import app.pawse.scoring.config.StrainScale
import app.pawse.scoring.model.BiologicalSex
import app.pawse.scoring.model.UserProfile
import kotlin.math.roundToInt

private val Sexes = listOf(BiologicalSex.FEMALE to "Female", BiologicalSex.MALE to "Male", BiologicalSex.UNSPECIFIED to "Not saying")
private val Scales = listOf(StrainScale.BEVEL_100 to "100", StrainScale.WHOOP_21 to "21")
private val Appearances = listOf(
    Appearance.SYSTEM to "System",
    Appearance.DARK to "Dark",
    Appearance.LIGHT to "Light",
    Appearance.BEDSIDE to "Bedside",
)

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
fun ProfileSheet(
    initialProfile: UserProfile?,
    initialScale: StrainScale?,
    initialAppearance: Appearance,
    onDismiss: () -> Unit,
    onSave: (
        ageYears: Int?,
        sex: BiologicalSex,
        measuredMaxHeartRate: Double?,
        scale: StrainScale,
        appearance: Appearance,
    ) -> Unit,
) {
    var age by rememberSaveable { mutableStateOf(initialProfile?.ageYears?.toString().orEmpty()) }
    var maxHr by rememberSaveable { mutableStateOf(initialProfile?.measuredMaxHeartRate?.toInt()?.toString().orEmpty()) }
    var sex by rememberSaveable { mutableStateOf(initialProfile?.biologicalSex ?: BiologicalSex.UNSPECIFIED) }
    var scale by rememberSaveable { mutableStateOf(initialScale ?: StrainScale.BEVEL_100) }
    var appearance by rememberSaveable { mutableStateOf(initialAppearance) }
    val ageYears = age.toIntOrNull()?.takeIf { it in 10..110 }
    val estimated = ageYears?.let { (208 - 0.7 * it).roundToInt() }

    PawseSheet(title = "You and your scales", onDismiss = onDismiss) { close ->
        Note(
            "Strain divides by your heart-rate range, and Health Connect has no record of age or sex. " +
                "Leave either out and Pawse says so wherever it matters.",
            Modifier.padding(top = 6.dp),
            body = true,
        )

        FieldLabel("Age", Modifier.padding(top = 20.dp))
        UnitField(value = age, onValueChange = { new -> age = new.filter { it.isDigit() }.take(3) }, unit = "years")

        FieldLabel("Sex, for the strain curve", Modifier.padding(top = 16.dp))
        Choices(
            options = Sexes.map { it.second },
            selected = Sexes.indexOfFirst { it.first == sex },
            onSelect = { sex = Sexes[it].first },
        )
        Note(
            if (sex == BiologicalSex.UNSPECIFIED) {
                "Strain uses the midpoint of the two published curves and says it's estimated."
            } else {
                "Strain uses Banister's published curve for this."
            },
        )

        FieldLabel("Measured max heart rate · optional", Modifier.padding(top = 16.dp))
        UnitField(
            value = maxHr,
            onValueChange = { new -> maxHr = new.filter { it.isDigit() }.take(3) },
            unit = "bpm",
            placeholder = estimated?.let { "Estimated $it from your age" } ?: "Add your age to estimate it",
        )
        Note(
            if (estimated != null) {
                "Tanaka: 208 − 0.7 × $ageYears = $estimated bpm. A measured maximum beats it."
            } else {
                "Without an age or a measured maximum, workouts can't be scored."
            },
        )

        FieldLabel("Show Strain out of", Modifier.padding(top = 16.dp))
        Segmented(
            options = Scales.map { it.second },
            selected = Scales.indexOfFirst { it.first == scale },
            onSelect = { scale = Scales[it].first },
        )
        Note("Same curve, different ceiling. Switching relabels the number and never rewrites your history.")

        FieldLabel("Appearance", Modifier.padding(top = 16.dp))
        Choices(
            options = Appearances.map { it.second },
            selected = Appearances.indexOfFirst { it.first == appearance },
            onSelect = { appearance = Appearances[it].first },
        )
        Note("Bedside is true black and dimmer, for reading with the lights off.")

        PrimaryButton(
            text = "Save and rescore",
            onClick = {
                close {
                    onSave(ageYears, sex, maxHr.toDoubleOrNull()?.takeIf { it in 100.0..230.0 }, scale, appearance)
                }
            },
            modifier = Modifier
                .padding(top = 20.dp)
                .fillMaxWidth(),
        )
    }
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier.padding(top = 6.dp), body: Boolean = false) {
    Text(
        text = text,
        style = if (body) PawseType.Body else PawseType.Label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
