package app.pawse.feature.food

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pawse.core.data.food.FoodLookupFailure
import app.pawse.core.data.food.ocr.LabelBasis
import app.pawse.core.data.food.ocr.LabelReading
import app.pawse.core.ui.component.Chip
import app.pawse.core.ui.component.EaseInOut
import app.pawse.core.ui.component.GhostButton
import app.pawse.core.ui.component.Glyph
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.PawseCard
import app.pawse.core.ui.component.PawseField
import app.pawse.core.ui.component.PrimaryButton
import app.pawse.core.ui.component.SecondaryButton
import app.pawse.core.ui.component.cardSurface
import app.pawse.core.ui.component.lift
import app.pawse.core.ui.component.pressable
import app.pawse.core.ui.theme.PawseType
import app.pawse.feature.food.camera.BarcodeFrameReader
import app.pawse.feature.food.camera.CameraPreview
import app.pawse.feature.food.camera.LabelFrameReader
import app.pawse.feature.food.camera.hasCamera
import app.pawse.scoring.tr
import kotlin.math.roundToInt

/** Camera chrome is light-on-dark whatever the app theme: it sits on a live picture. */
private val Ink = Color(0xFFEDEFF1)
private val Night = Color(0xFF0E1012)
private val Glass = Color(0xB814171A)
private val GlassLine = Color.White.copy(alpha = 0.07f)

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
    val cameraOn = granted && hasCamera(context)

    Box(modifier = modifier.fillMaxSize().background(Color(0xFF0A0B0C))) {
        if (cameraOn) {
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
            Viewfinder(
                mode = mode,
                sweeping = state is ScanState.Scanning || state is ScanState.LookingUp,
                locked = state is ScanState.Found,
            )
        }

        if (consentRequested) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.56f)))
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(52.dp)
                    .pressable(onClick = onBack)
                    .glass(CircleShape)
                    .semantics { contentDescription = tr("Back", "뒤로") },
                contentAlignment = Alignment.Center,
            ) {
                Glyph(Glyphs.ChevronLeft, color = Ink)
            }
            Spacer(Modifier.weight(1f))
            ModeToggle(mode = mode, onModeChange = viewModel::setMode)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(52.dp))
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 28.dp),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 18.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            )
            when {
                !cameraOn -> CameraUnavailable(
                    hasCamera = hasCamera(context),
                    onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onManualEntry = onManualEntry,
                )

                consentRequested -> Consent(
                    onAllow = viewModel::acceptNetworkLookup,
                    onDecline = viewModel::dismissConsent,
                )

                else -> AnimatedContent(
                    targetState = state,
                    contentKey = { it::class },
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                    label = "sheet",
                ) { current ->
                    ScanStatePanel(
                        state = current,
                        mode = mode,
                        onAdd = onProductChosen,
                        onScanAgain = viewModel::scanAgain,
                        onReadLabel = { viewModel.setMode(ScanMode.LABEL) },
                        onManualEntry = onManualEntry,
                        onEnableNetwork = viewModel::acceptNetworkLookup,
                        onSaveLabel = { name, reading, grams ->
                            viewModel.saveLabelProduct(name, reading, grams, onProductChosen)
                        },
                    )
                }
            }
        }
    }
}

private fun Modifier.glass(shape: Shape): Modifier = this
    .lift(shape)
    .background(Glass, shape)
    .border(1.dp, GlassLine, shape)

/** Barcode or Label, as a glass pill whose light thumb slides between them. */
@Composable
private fun ModeToggle(mode: ScanMode, onModeChange: (ScanMode) -> Unit) {
    val width = 92.dp
    val offset by animateDpAsState(if (mode == ScanMode.BARCODE) 0.dp else width, tween(240, easing = EaseInOut), label = "mode")
    Box(
        Modifier
            .glass(RoundedCornerShape(26.dp))
            .padding(6.dp),
    ) {
        Box(
            Modifier
                .offset(x = offset)
                .size(width = width, height = 40.dp)
                .background(Ink, RoundedCornerShape(20.dp)),
        )
        Row {
            listOf(ScanMode.BARCODE to tr("Barcode", "바코드"), ScanMode.LABEL to tr("Label", "라벨")).forEach { (option, label) ->
                Box(
                    Modifier
                        .size(width = width, height = 40.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                        ) { onModeChange(option) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = PawseType.BodyStrong, color = if (mode == option) Night else Ink)
                }
            }
        }
    }
}

/** Corner brackets where the code or panel should sit, a sweep while looking, a lock when found. */
@Composable
private fun Viewfinder(mode: ScanMode, sweeping: Boolean, locked: Boolean) {
    val barcode = mode == ScanMode.BARCODE
    val frame: Pair<Dp, Dp> = if (barcode) 280.dp to 150.dp else 240.dp to 300.dp
    val sweep by rememberInfiniteTransition(label = "sweep").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse),
        label = "sweep",
    )
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = if (barcode) 168.dp else 100.dp), contentAlignment = Alignment.TopCenter) {
        Canvas(Modifier.size(frame.first, frame.second)) {
            brackets(Offset.Zero, size, 28.dp.toPx(), 16.dp.toPx(), 3.dp.toPx())
            if (sweeping && barcode) {
                val inset = 16.dp.toPx()
                val y = 14.dp.toPx() + sweep * 118.dp.toPx()
                drawLine(Ink.copy(alpha = 0.25f), Offset(inset, y), Offset(size.width - inset, y), 8.dp.toPx())
                drawLine(Ink, Offset(inset, y), Offset(size.width - inset, y), 2.dp.toPx())
            }
            if (locked && barcode) {
                val inner = Size(216.dp.toPx(), 108.dp.toPx())
                brackets(Offset((size.width - inner.width) / 2f, 20.dp.toPx()), inner, 18.dp.toPx(), 8.dp.toPx(), 2.5.dp.toPx())
            }
        }
    }
}

private fun DrawScope.brackets(
    origin: Offset,
    box: Size,
    arm: Float,
    radius: Float,
    stroke: Float,
) {
    val style = Stroke(stroke, cap = StrokeCap.Round)
    val d = radius * 2
    val l = origin.x + stroke / 2
    val t = origin.y + stroke / 2
    val r = origin.x + box.width - stroke / 2
    val b = origin.y + box.height - stroke / 2
    // Each corner: a quarter arc and its two straight arms.
    drawArc(Ink, 180f, 90f, false, Offset(l, t), Size(d, d), style = style)
    drawLine(Ink, Offset(l + radius, t), Offset(l + arm, t), stroke)
    drawLine(Ink, Offset(l, t + radius), Offset(l, t + arm), stroke)
    drawArc(Ink, 270f, 90f, false, Offset(r - d, t), Size(d, d), style = style)
    drawLine(Ink, Offset(r - radius, t), Offset(r - arm, t), stroke)
    drawLine(Ink, Offset(r, t + radius), Offset(r, t + arm), stroke)
    drawArc(Ink, 90f, 90f, false, Offset(l, b - d), Size(d, d), style = style)
    drawLine(Ink, Offset(l + radius, b), Offset(l + arm, b), stroke)
    drawLine(Ink, Offset(l, b - radius), Offset(l, b - arm), stroke)
    drawArc(Ink, 0f, 90f, false, Offset(r - d, b - d), Size(d, d), style = style)
    drawLine(Ink, Offset(r - radius, b), Offset(r - arm, b), stroke)
    drawLine(Ink, Offset(r, b - radius), Offset(r, b - arm), stroke)
}

@Composable
private fun ScanStatePanel(
    state: ScanState,
    mode: ScanMode,
    onAdd: (Long) -> Unit,
    onScanAgain: () -> Unit,
    onReadLabel: () -> Unit,
    onManualEntry: () -> Unit,
    onEnableNetwork: () -> Unit,
    onSaveLabel: (String, LabelReading, Double) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        when (state) {
            is ScanState.Scanning -> {
                Text(
                    text = when (mode) {
                        ScanMode.BARCODE -> tr("Point at the barcode.", "바코드를 비춰 주세요.")
                        ScanMode.LABEL -> tr(
                            "Point at the nutrition panel. Hold steady; it reads as you go.",
                            "영양성분표를 비춰 주세요. 움직이지 않으면 바로 읽어요.",
                        )
                    },
                    style = PawseType.Heading.copy(fontWeight = PawseType.Body.fontWeight),
                )
                Spacer(Modifier.height(6.dp))
                Muted(tr("Reads on this phone. No photo is saved and no frame leaves the device.", "이 휴대폰에서 읽어요. 사진은 저장되지 않고 어떤 화면도 기기 밖으로 나가지 않아요."))
                Spacer(Modifier.height(12.dp))
                GhostButton(tr("Type it in instead", "대신 직접 입력"), onClick = onManualEntry, glyph = Glyphs.Pencil)
            }

            is ScanState.LookingUp -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(12.dp))
                Text(tr("Looking up ${state.barcode}", "${state.barcode} 찾는 중"), style = PawseType.Body)
            }

            is ScanState.Found -> {
                val product = state.product
                Text(product.name, style = PawseType.Heading)
                product.brand?.let { Muted(it) }
                Spacer(Modifier.height(16.dp))
                PawseCard(padding = PaddingValues(vertical = 12.dp)) {
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        val n = product.per100g
                        listOf(
                            (n.kcal?.roundToInt()?.toString() ?: "—") to "kcal",
                            (n.proteinGrams?.let(FoodCopy::grams) ?: "—") to tr("Protein", "단백질"),
                            (n.carbsGrams?.let(FoodCopy::grams) ?: "—") to tr("Carbs", "탄수화물"),
                            (n.fatGrams?.let(FoodCopy::grams) ?: "—") to tr("Fat", "지방"),
                        ).forEachIndexed { i, (value, name) ->
                            if (i > 0) VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.fillMaxHeight())
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(value, style = PawseType.MetricValue, maxLines = 1)
                                Muted(name)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Muted(tr("Per 100 g", "100 g당"))
                Spacer(Modifier.height(14.dp))
                Chip(FoodCopy.source(product.source))
                Spacer(Modifier.height(6.dp))
                Muted(FoodCopy.sourceNote(product.source, state.fromCache))
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(tr("Choose how much", "양 정하기"), onClick = { onAdd(product.id) }, modifier = Modifier.weight(1f))
                    SecondaryButton(tr("Scan another", "다른 것 스캔"), onClick = onScanAgain, modifier = Modifier.weight(1f))
                }
            }

            is ScanState.NotFound -> {
                val offline = state.reason == FoodLookupFailure.NETWORK_FAILED
                if (offline) {
                    Box(
                        Modifier
                            .size(width = 72.dp, height = 40.dp)
                            .cardSurface(RoundedCornerShape(12.dp), 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            repeat(2) {
                                Box(Modifier.size(width = 18.dp, height = 4.dp).background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    if (offline) tr("Couldn’t reach the food database", "음식 데이터베이스에 연결하지 못했어요") else tr("Not in your foods", "저장한 음식에 없어요"),
                    style = PawseType.Heading,
                )
                Spacer(Modifier.height(6.dp))
                Text(FoodCopy.lookupFailure(state.reason), style = PawseType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
                PrimaryButton(tr("Read the label", "라벨 읽기"), onClick = onReadLabel)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.reason == FoodLookupFailure.NETWORK_NOT_PERMITTED) {
                        SecondaryButton(tr("Look it up online", "온라인에서 찾기"), onClick = onEnableNetwork, modifier = Modifier.weight(1f))
                    } else {
                        SecondaryButton(tr("Try again", "다시 시도"), onClick = onScanAgain, modifier = Modifier.weight(1f))
                    }
                    SecondaryButton(tr("Type it in", "직접 입력"), onClick = onManualEntry, modifier = Modifier.weight(1f))
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

/** The one-time question, asked while the product is in hand. */
@Composable
private fun Consent(onAllow: () -> Unit, onDecline: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Box(Modifier.size(48.dp).cardSurface(), contentAlignment = Alignment.Center) {
            Glyph(Glyphs.Shield, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(14.dp))
        Text(FoodCopy.CONSENT_TITLE, style = PawseType.Title)
        Spacer(Modifier.height(8.dp))
        Text(
            tr(
                "Pawse can ask Open Food Facts, a public food database, what a barcode is.",
                "Pawse는 공개 음식 데이터베이스인 Open Food Facts에 바코드가 무슨 제품인지 물어볼 수 있어요.",
            ),
            style = PawseType.Body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        PawseCard(padding = PaddingValues(0.dp)) {
            ConsentLine(Glyphs.Check, tr("Sent: ", "보내는 것: "), tr("the barcode, and nothing else", "바코드뿐, 다른 건 없어요"))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ConsentLine(Glyphs.Close, tr("Never sent: ", "절대 보내지 않는 것: "), tr("account, device identifier, history, health data", "계정, 기기 식별자, 기록, 건강 데이터"))
        }
        Spacer(Modifier.height(12.dp))
        Muted(
            tr(
                "Each product is fetched once and saved, so it resolves instantly and offline afterwards. " +
                    "Reading the nutrition panel works without this.",
                "제품마다 한 번만 가져와 저장하므로 다음부터는 오프라인에서도 바로 나와요. " +
                    "영양성분표 읽기는 이 설정 없이도 돼요.",
            ),
        )
        Spacer(Modifier.height(20.dp))
        PrimaryButton(tr("Allow lookups", "검색 허용"), onClick = onAllow)
        Spacer(Modifier.height(8.dp))
        SecondaryButton(tr("Keep it offline", "오프라인으로 두기"), onClick = onDecline, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ConsentLine(glyph: String, lead: String, rest: String) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Glyph(glyph, size = 18.dp, modifier = Modifier.padding(top = 1.dp))
        Spacer(Modifier.width(12.dp))
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        Text(
            buildAnnotatedString {
                append(lead)
                withStyle(SpanStyle(color = muted)) { append(rest) }
            },
            style = PawseType.Body,
        )
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
    reading: LabelReading,
    onSave: (String, LabelReading, Double) -> Unit,
    onScanAgain: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var basisText by remember(reading.basisGrams) {
        mutableStateOf(reading.basisGrams?.let { FoodCopy.trim(it) } ?: "")
    }
    val basisGrams = basisText.replace(',', '.').toDoubleOrNull()

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(tr("Read from the label", "라벨에서 읽은 값"), style = PawseType.Heading)
        Spacer(Modifier.height(8.dp))
        Text(FoodCopy.summary(reading.nutrients), style = PawseType.Body)
        Spacer(Modifier.height(6.dp))
        Muted(FoodCopy.labelBasis(reading.basis, reading.basisGrams))
        reading.warnings.forEach { warning ->
            Spacer(Modifier.height(6.dp))
            Muted(FoodCopy.labelWarning(warning))
        }

        Spacer(Modifier.height(16.dp))
        Muted(tr("What is it?", "무엇인가요?"))
        Spacer(Modifier.height(6.dp))
        PawseField(value = name, onValueChange = { name = it })

        if (reading.basis == LabelBasis.UNKNOWN || reading.basisGrams == null) {
            Spacer(Modifier.height(12.dp))
            Muted(tr("These figures are per how many grams?", "이 값은 몇 g 기준인가요?"))
            Spacer(Modifier.height(6.dp))
            PawseField(
                value = basisText,
                onValueChange = { basisText = it.filter { char -> char.isDigit() || char == '.' } },
                suffix = "g",
                numeric = true,
            )
        }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(
                tr("Save and add", "저장하고 추가"),
                onClick = { basisGrams?.let { onSave(name, reading, it) } },
                enabled = basisGrams != null && basisGrams > 0.0,
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(tr("Read again", "다시 읽기"), onClick = onScanAgain, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun CameraUnavailable(
    hasCamera: Boolean,
    onRequest: () -> Unit,
    onManualEntry: () -> Unit,
) {
    Column {
        Text(if (hasCamera) tr("Camera access", "카메라 접근") else tr("No camera on this phone", "이 휴대폰에는 카메라가 없어요"), style = PawseType.Heading)
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (hasCamera) {
                tr(
                    "The camera reads barcodes and nutrition panels on this phone. No photo is " +
                        "saved and no frame leaves the device.",
                    "카메라로 이 휴대폰에서 바코드와 영양성분표를 읽어요. 사진은 저장되지 않고 어떤 화면도 " +
                        "기기 밖으로 나가지 않아요.",
                )
            } else {
                tr(
                    "Barcode scanning needs a camera, but you can still type food in by hand.",
                    "바코드 스캔에는 카메라가 필요하지만, 음식은 직접 입력할 수 있어요.",
                )
            },
            style = PawseType.Body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        if (hasCamera) {
            PrimaryButton(tr("Allow camera", "카메라 허용"), onClick = onRequest)
            Spacer(Modifier.height(8.dp))
        }
        SecondaryButton(tr("Type it in", "직접 입력"), onClick = onManualEntry, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Muted(text: String) {
    Text(text, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
