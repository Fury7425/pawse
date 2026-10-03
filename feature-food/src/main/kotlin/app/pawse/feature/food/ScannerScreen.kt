package app.pawse.feature.food

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pawse.core.data.food.FoodLookupFailure
import app.pawse.core.data.food.ocr.LabelBasis
import app.pawse.core.ui.component.Chip
import app.pawse.core.ui.theme.PawseType
import app.pawse.feature.food.camera.BarcodeFrameReader
import app.pawse.feature.food.camera.CameraPreview
import app.pawse.feature.food.camera.LabelFrameReader
import app.pawse.feature.food.camera.hasCamera

/**
 * Point the camera at the barcode, or at the panel.
 *
 * Two modes, one camera, and both of them work with the network switched off —
 * the first because scanned products are saved locally the first time, the second
 * because the nutrition panel is printed on the packet and the text recogniser
 * runs on the phone.
 *
 * The network consent sheet appears at exactly one moment: a barcode we could not
 * answer, while the user is holding the product. That is when the question is
 * useful and when the answer is informed; asking at install time is asking someone
 * to decide about a feature they have not met.
 */
@Composable
fun ScannerScreen(
    onBack: () -> Unit,
    onProductChosen: (Long) -> Unit,
    onManualEntry: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ScannerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val consentRequested by viewModel.consentRequested.collectAsStateWithLifecycle()

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result -> granted = result }

    Box(modifier = modifier.fillMaxSize()) {
        if (granted && hasCamera(context)) {
            val analyzer: ImageAnalysis.Analyzer = remember(mode) {
                when (mode) {
                    ScanMode.BARCODE -> BarcodeFrameReader(viewModel::onBarcode)
                    ScanMode.LABEL -> LabelFrameReader(viewModel::onLabelLines)
                }
            }
            DisposableEffect(analyzer) {
                onDispose { (analyzer as? AutoCloseable)?.close() }
            }
            CameraPreview(analyzer = analyzer, modifier = Modifier.fillMaxSize())
        } else {
            CameraUnavailable(
                hasCamera = hasCamera(context),
                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onManualEntry = onManualEntry,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) {
                TextButton(onClick = onBack) { Text("Back") }
            }
            Spacer(Modifier.weight(1f))
            ModeToggle(mode = mode, onModeChange = viewModel::setMode)
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        ) {
            ScanStatePanel(
                state = state,
                mode = mode,
                onAdd = onProductChosen,
                onScanAgain = viewModel::scanAgain,
                onManualEntry = onManualEntry,
                onEnableNetwork = viewModel::acceptNetworkLookup,
                onSaveLabel = { name, reading, grams ->
                    viewModel.saveLabelProduct(name, reading, grams, onProductChosen)
                },
                modifier = Modifier.padding(20.dp),
            )
        }
    }

    if (consentRequested) {
        AlertDialog(
            onDismissRequest = viewModel::dismissConsent,
            title = { Text(FoodCopy.CONSENT_TITLE) },
            text = {
                Text(
                    text = FoodCopy.CONSENT_BODY,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::acceptNetworkLookup) { Text("Allow lookups") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissConsent) { Text("Keep it offline") }
            },
        )
    }
}

@Composable
private fun ModeToggle(mode: ScanMode, onModeChange: (ScanMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = mode == ScanMode.BARCODE,
            onClick = { onModeChange(ScanMode.BARCODE) },
            label = { Text("Barcode") },
        )
        FilterChip(
            selected = mode == ScanMode.LABEL,
            onClick = { onModeChange(ScanMode.LABEL) },
            label = { Text("Label") },
        )
    }
}

@Composable
private fun ScanStatePanel(
    state: ScanState,
    mode: ScanMode,
    onAdd: (Long) -> Unit,
    onScanAgain: () -> Unit,
    onManualEntry: () -> Unit,
    onEnableNetwork: () -> Unit,
    onSaveLabel: (String, app.pawse.core.data.food.ocr.LabelReading, Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        when (state) {
            is ScanState.Scanning -> Text(
                text = when (mode) {
                    ScanMode.BARCODE -> "Point at the barcode."
                    ScanMode.LABEL -> "Point at the nutrition panel. Hold steady; it reads as you go."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            is ScanState.LookingUp -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.padding(horizontal = 8.dp))
                Text("Looking up ${state.barcode}", style = MaterialTheme.typography.bodyMedium)
            }

            is ScanState.Found -> {
                Text(state.product.name, style = MaterialTheme.typography.titleMedium)
                state.product.brand?.let {
                    Text(it, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = FoodCopy.summary(state.product.per100g) + " per 100 g",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Chip(text = FoodCopy.source(state.product.source))
                Spacer(Modifier.height(6.dp))
                Text(
                    text = FoodCopy.sourceNote(state.product.source, state.fromCache),
                    style = PawseType.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onAdd(state.product.id) }) { Text("Add to log") }
                    TextButton(onClick = onScanAgain) { Text("Scan another") }
                }
            }

            is ScanState.NotFound -> {
                Text("Not in your foods", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = FoodCopy.lookupFailure(state.reason),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.reason == FoodLookupFailure.NETWORK_NOT_PERMITTED) {
                        Button(onClick = onEnableNetwork) { Text("Look it up online") }
                    }
                    TextButton(onClick = onScanAgain) { Text("Try again") }
                    TextButton(onClick = onManualEntry) { Text("Type it in") }
                }
            }

            is ScanState.LabelRead -> LabelConfirmation(
                reading = state.reading,
                onSave = onSaveLabel,
                onScanAgain = onScanAgain,
            )
        }
    }
}

/**
 * The parsed panel, for the user to check before it becomes a food.
 *
 * The basis field is the important one. When the label stated what its figures are
 * per, it is filled in and the user can move on; when it did not, this is where
 * they say, because the alternative is assuming 100 g and being wrong by a factor
 * of two on a 45 g packet.
 */
@Composable
private fun LabelConfirmation(
    reading: app.pawse.core.data.food.ocr.LabelReading,
    onSave: (String, app.pawse.core.data.food.ocr.LabelReading, Double) -> Unit,
    onScanAgain: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var basisText by remember(reading.basisGrams) {
        mutableStateOf(reading.basisGrams?.let { FoodCopy.trim(it) } ?: "")
    }
    val basisGrams = basisText.replace(',', '.').toDoubleOrNull()

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Read from the label", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = FoodCopy.summary(reading.nutrients),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = FoodCopy.labelBasis(reading.basis, reading.basisGrams),
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        reading.warnings.forEach { warning ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = FoodCopy.labelWarning(warning),
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("What is it?") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (reading.basis == LabelBasis.UNKNOWN || reading.basisGrams == null) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = basisText,
                onValueChange = { basisText = it.filter { char -> char.isDigit() || char == '.' } },
                label = { Text("These figures are per how many grams?") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { basisGrams?.let { onSave(name, reading, it) } },
                enabled = basisGrams != null && basisGrams > 0.0,
            ) { Text("Save and add") }
            TextButton(onClick = onScanAgain) { Text("Read again") }
        }
    }
}

@Composable
private fun CameraUnavailable(
    hasCamera: Boolean,
    onRequest: () -> Unit,
    onManualEntry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
    ) {
        Text(
            text = if (hasCamera) "Camera access" else "No camera on this phone",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (hasCamera) {
                "The camera reads barcodes and nutrition panels on this phone. No photo is " +
                    "saved and no frame leaves the device."
            } else {
                "Barcode scanning needs a camera, but you can still type food in by hand."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (hasCamera) Button(onClick = onRequest) { Text("Allow camera") }
            TextButton(onClick = onManualEntry) { Text("Type it in") }
        }
    }
}
