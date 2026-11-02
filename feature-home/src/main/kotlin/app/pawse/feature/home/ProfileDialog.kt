package app.pawse.feature.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import app.pawse.core.model.Engine
import app.pawse.core.model.ModelState
import app.pawse.core.model.PawseModel
import app.pawse.core.ui.component.ChoiceRow
import app.pawse.core.ui.component.GhostButton
import app.pawse.core.ui.component.GlyphButton
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.PawseField
import app.pawse.core.ui.component.PawseSheet
import app.pawse.core.ui.component.PrimaryButton
import app.pawse.core.ui.component.Segmented
import app.pawse.core.ui.component.SplitBar
import app.pawse.core.ui.theme.PawseType
import app.pawse.scoring.model.BiologicalSex
import app.pawse.scoring.model.Calibration
import app.pawse.scoring.model.UserProfile
import app.pawse.scoring.tr
import java.time.LocalDate
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
    model: ModelSettings,
    onModelSwitch: (Boolean) -> Unit,
    onModelDownload: (mobileData: Boolean) -> Unit,
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
                Text(tr("You and your settings", "내 정보와 설정"), style = PawseType.Title, modifier = Modifier.weight(1f))
                GlyphButton(Glyphs.Close, tr("Close", "닫기"), onClick = onDismiss)
            }
            Spacer(Modifier.height(6.dp))
            Caption(
                tr(
                    "Training load divides by your heart-rate range, and Health Connect has no record of age or sex. " +
                        "Leave either out and Pawse says so wherever it matters.",
                    "훈련 부하는 심박수 범위로 나눠 계산하는데, 헬스 커넥트에는 나이나 성별 기록이 없어요. " +
                        "입력하지 않으면 Pawse가 관련된 곳마다 그렇다고 알려 드려요.",
                ),
                body = true,
            )

            Spacer(Modifier.height(20.dp))
            Caption(tr("Age", "나이"))
            Spacer(Modifier.height(6.dp))
            PawseField(
                value = age,
                onValueChange = { new -> age = new.filter { it.isDigit() }.take(3) },
                suffix = tr("years", "세"),
                numeric = true,
            )

            Spacer(Modifier.height(16.dp))
            Caption(tr("Sex, for the training-load curve", "성별 (훈련 부하 곡선용)"))
            Spacer(Modifier.height(6.dp))
            ChoiceRow(
                options = listOf(BiologicalSex.FEMALE, BiologicalSex.MALE, BiologicalSex.UNSPECIFIED),
                selected = sex,
                onSelect = { sex = it },
                label = {
                    when (it) {
                        BiologicalSex.MALE -> tr("Male", "남성")
                        BiologicalSex.FEMALE -> tr("Female", "여성")
                        BiologicalSex.UNSPECIFIED -> tr("Rather not say", "밝히지 않음")
                    }
                },
            )
            Spacer(Modifier.height(6.dp))
            Caption(
                if (sex == BiologicalSex.UNSPECIFIED) {
                    tr(
                        "Pawse uses the midpoint of the two published curves and labels it as its own choice.",
                        "Pawse는 공개된 두 곡선의 중간값을 쓰고, Pawse가 정한 값이라고 표시해요.",
                    )
                } else {
                    tr(
                        "Banister published TRIMP as two curves; this picks the matching one.",
                        "Banister는 TRIMP를 두 곡선으로 발표했어요. 이 선택으로 맞는 곡선을 골라요.",
                    )
                },
            )

            Spacer(Modifier.height(16.dp))
            Caption(tr("Measured max heart rate · optional", "측정한 최대 심박수 · 선택"))
            Spacer(Modifier.height(6.dp))
            PawseField(
                value = maxHr,
                onValueChange = { new -> maxHr = new.filter { it.isDigit() }.take(3) },
                suffix = "bpm",
                placeholder = estimate?.let { tr("Estimated $it from your age", "나이로 추정한 값 $it") },
                numeric = true,
                textStyle = PawseType.Body,
            )
            Spacer(Modifier.height(6.dp))
            Caption(
                tr(
                    "If you have a real figure from a test, it replaces the age estimate. " +
                        "A true maximum can sit twenty beats either side of the formula.",
                    "검사로 잰 실제 값이 있으면 나이 추정값 대신 써요. 실제 최대 심박수는 공식과 " +
                        "20회 정도 차이 날 수 있어요.",
                ),
            )

            Spacer(Modifier.height(16.dp))
            Caption(tr("Numbers on Today", "오늘 화면의 숫자"))
            Spacer(Modifier.height(6.dp))
            Segmented(
                options = listOf(true, false),
                selected = showNumbers,
                onSelect = { showNumbers = it },
                label = { if (it) tr("Show", "보기") else tr("Words only", "말로만") },
            )
            Spacer(Modifier.height(6.dp))
            Caption(
                tr(
                    "Words only hides Body's number, the Sleep score and form. The explain screens still show the arithmetic.",
                    "‘말로만’을 고르면 컨디션 숫자, 수면 점수, 폼을 숨겨요. 설명 화면에는 계산 과정이 그대로 나와요.",
                ),
            )

            Spacer(Modifier.height(16.dp))
            Caption(tr("Appearance", "화면 모드"))
            Spacer(Modifier.height(6.dp))
            Segmented(
                options = Appearance.entries,
                selected = appearance,
                onSelect = { appearance = it },
                label = {
                    when (it) {
                        Appearance.SYSTEM -> tr("Phone", "휴대폰")
                        Appearance.DARK -> tr("Dark", "어둡게")
                        Appearance.LIGHT -> tr("Light", "밝게")
                        Appearance.BEDSIDE -> tr("Bedside", "침대맡")
                    }
                },
            )
            Spacer(Modifier.height(6.dp))
            Caption(
                tr(
                    "Bedside is true black and dimmer, for reading before the lights are on.",
                    "침대맡 모드는 완전한 검정에 더 어두워서, 불을 켜기 전에 보기 좋아요.",
                ),
            )

            Spacer(Modifier.height(24.dp))
            ModelSection(model, onModelSwitch, onModelDownload)

            calibration?.let { c ->
                Spacer(Modifier.height(24.dp))
                Text(tr("Your model", "내 모델"), style = PawseType.Heading)
                Spacer(Modifier.height(6.dp))
                Caption(
                    c.bodyMatch?.let {
                        val r = String.format(Locale.getDefault(), "%.2f", it)
                        val weak = kotlin.math.abs(it) < 0.1
                        tr(
                            "Body matched how you felt: r = $r over ${c.checkIns} mornings." + if (weak) " Weigh the score lightly." else "",
                            "아침 ${c.checkIns}번 동안 컨디션과 느낀 기분의 일치도: r = $r." + if (weak) " 점수는 가볍게 참고하세요." else "",
                        )
                    } ?: tr(
                        "Check in a few mornings and Pawse will show how well Body tracks how you feel.",
                        "몇 번 아침에 기분을 기록하면, 컨디션이 내 기분을 얼마나 잘 따라가는지 보여 드려요.",
                    ),
                    body = true,
                )
                Spacer(Modifier.height(6.dp))
                Caption(
                    tr(
                        "HRV's share of Body: ${(c.hrvWeight * 100).toInt()}%. After 30 check-ins it adapts to you, slowly.",
                        "컨디션 내 HRV 비중: ${(c.hrvWeight * 100).toInt()}%. 기분 기록이 30번 쌓이면 천천히 나에게 맞춰져요.",
                    ),
                )
                c.changes.takeLast(MAX_CHANGES).reversed().forEach { change ->
                    Caption(
                        "${change.date.format(DateTimeFormatter.ofPattern(tr("d MMM", "M월 d일"), Locale.getDefault()))} · " +
                            "${change.what}: ${String.format(Locale.getDefault(), "%.2f", change.from)} → " +
                            String.format(Locale.getDefault(), "%.2f", change.to),
                    )
                }
                GhostButton(tr("Reset to defaults", "기본값으로 되돌리기"), onClick = onResetModel)
            }

            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                text = tr("Save", "저장"),
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

/**
 * The optional on-device model: off by default, explained before it is turned on,
 * and asked for again, with its size, before anything is downloaded.
 */
@Composable
private fun ModelSection(model: ModelSettings, onSwitch: (Boolean) -> Unit, onDownload: (mobileData: Boolean) -> Unit) {
    val state = model.state
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tr("On-device model", "휴대폰 속 모델"), style = PawseType.Heading, modifier = Modifier.weight(1f))
        Switch(
            checked = state != ModelState.Off,
            onCheckedChange = onSwitch,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.background,
                checkedTrackColor = colors.onSurface,
                uncheckedThumbColor = colors.onSurfaceVariant,
                uncheckedTrackColor = colors.surfaceContainerHigh,
                uncheckedBorderColor = colors.outline,
            ),
        )
    }
    Spacer(Modifier.height(6.dp))
    Caption(
        tr(
            "Writes a short summary each morning and answers questions about your numbers. About 1 GB, runs only " +
                "on this phone, and never works out a number itself. It can be wrong, and it is not medical advice.",
            "매일 아침 짧은 요약을 쓰고 내 수치에 관한 질문에 답해요. 약 1GB이고, 이 휴대폰에서만 동작하며, 숫자를 " +
                "스스로 계산하지 않아요. 틀릴 수 있고, 의학적 조언이 아니에요.",
        ),
    )
    when (state) {
        ModelState.Off -> if (model.unsupported) {
            Spacer(Modifier.height(6.dp))
            Caption(
                tr(
                    "This phone can’t run it: it needs a processor from about 2018 on and 3 GB of memory, or Gemini Nano.",
                    "이 휴대폰에서는 쓸 수 없어요. 2018년 무렵 이후의 프로세서와 3GB 메모리, 또는 Gemini Nano가 필요해요.",
                ),
            )
        }
        ModelState.NotDownloaded -> DownloadAsk(model.room, onDownload, onNotNow = { onSwitch(false) })
        ModelState.Failed -> {
            Spacer(Modifier.height(6.dp))
            Caption(tr("The last download didn’t finish or didn’t check out. Nothing was kept.", "지난 다운로드가 끝나지 않았거나 확인에 실패했어요. 남은 건 없어요."))
            DownloadAsk(model.room, onDownload, onNotNow = { onSwitch(false) })
        }
        is ModelState.Downloading -> {
            val done = (state.bytes.toFloat() / PawseModel.BYTES).coerceIn(0f, 1f)
            Spacer(Modifier.height(12.dp))
            SplitBar(listOf(done to colors.onSurface, (1f - done) to colors.outline), height = 8.dp)
            Spacer(Modifier.height(8.dp))
            Text(
                PawseModel.downloaded(state.bytes) + if (state.waiting) tr(" · waiting for the network", " · 네트워크 기다리는 중") else "",
                style = PawseType.Label,
            )
            Spacer(Modifier.height(4.dp))
            Caption(
                tr(
                    "Keeps going in the background under Android’s download notification. Cancel keeps nothing.",
                    "안드로이드 다운로드 알림과 함께 백그라운드에서 계속돼요. 취소하면 아무것도 남지 않아요.",
                ),
            )
            GhostButton(tr("Cancel", "취소"), onClick = { onSwitch(false) })
        }
        is ModelState.Ready -> {
            Spacer(Modifier.height(6.dp))
            Caption(
                if (state.engine == Engine.NANO) {
                    tr("Using Gemini Nano, already on this phone. Nothing was downloaded.", "이 휴대폰에 이미 있는 Gemini Nano를 써요. 다운로드한 건 없어요.")
                } else {
                    tr(
                        "LFM3-1.7B by Liquid AI, under the LFM Open License v1.0. Kept only on this phone.",
                        "Liquid AI의 LFM3-1.7B, LFM Open License v1.0. 이 휴대폰에만 있어요.",
                    )
                },
            )
            if (state.since == LocalDate.now()) {
                Caption(tr("Ask Pawse works now. The first summary comes tomorrow morning.", "이제 Pawse에게 물어볼 수 있어요. 첫 요약은 내일 아침에 나와요."))
            }
        }
    }
    if (model.dropped > 0 && state != ModelState.Off) {
        Spacer(Modifier.height(6.dp))
        Caption(tr("Sentences the number check has left out so far: ${model.dropped}", "지금까지 숫자 확인에서 뺀 문장: ${model.dropped}개"))
    }
}

/** The download, asked for with its size, where it comes from, and on which network. */
@Composable
private fun DownloadAsk(room: Boolean, onDownload: (mobileData: Boolean) -> Unit, onNotNow: () -> Unit) {
    var mobileData by remember { mutableStateOf(false) }
    val size = PawseModel.megabytes(PawseModel.BYTES)
    Spacer(Modifier.height(12.dp))
    Caption(
        tr(
            "Pawse will download LFM3-1.7B, $size MB, from Liquid AI’s page on Hugging Face. The request carries " +
                "nothing about you, and the file is checked before it is used.",
            "Pawse가 Hugging Face의 Liquid AI 페이지에서 LFM3-1.7B(${size}MB)를 내려받아요. 요청에는 나에 관한 " +
                "정보가 담기지 않고, 파일은 쓰기 전에 확인해요.",
        ),
        body = true,
    )
    Spacer(Modifier.height(10.dp))
    Segmented(
        options = listOf(false, true),
        selected = mobileData,
        onSelect = { mobileData = it },
        label = { if (it) tr("Wi-Fi or mobile data", "Wi-Fi 또는 모바일 데이터") else tr("Wi-Fi only", "Wi-Fi만") },
    )
    Spacer(Modifier.height(12.dp))
    if (room) {
        PrimaryButton(tr("Download $size MB", "${size}MB 다운로드"), onClick = { onDownload(mobileData) })
    } else {
        Caption(tr("Needs about 1.3 GB free on this phone first.", "먼저 이 휴대폰에 약 1.3GB의 여유 공간이 필요해요."))
    }
    GhostButton(tr("Not now", "나중에"), onClick = onNotNow)
}

@Composable
private fun Caption(text: String, body: Boolean = false) {
    Text(
        text,
        style = if (body) PawseType.Body else PawseType.Label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
