package app.pawse.feature.home

import android.Manifest
import android.app.LocaleManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.LocaleList
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.pawse.core.data.profile.Appearance
import app.pawse.core.data.profile.NotifyKind
import app.pawse.core.ui.component.ChoiceRow
import app.pawse.core.ui.component.GhostButton
import app.pawse.core.ui.component.Glyph
import app.pawse.core.ui.component.GlyphButton
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.PawseField
import app.pawse.core.ui.component.PawseSheet
import app.pawse.core.ui.component.PrimaryButton
import app.pawse.core.ui.component.Segmented
import app.pawse.core.ui.component.pressable
import app.pawse.core.ui.theme.PawseTheme
import app.pawse.core.ui.theme.PawseType
import app.pawse.scoring.model.BiologicalSex
import app.pawse.scoring.model.Calibration
import app.pawse.scoring.model.UserProfile
import app.pawse.scoring.tr
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Everything the profile sheet shows and can change. Switches apply at once. */
class ProfileState(
    val profile: UserProfile?,
    val showNumbers: Boolean,
    val appearance: Appearance,
    val reduceMotion: Boolean,
    val shapes: Boolean,
    val notifications: Map<NotifyKind, Boolean>,
    val calibration: Calibration?,
)

class ProfileActions(
    val onShowNumbers: (Boolean) -> Unit,
    val onAppearance: (Appearance) -> Unit,
    val onReduceMotion: (Boolean) -> Unit,
    val onShapes: (Boolean) -> Unit,
    val onNotify: (NotifyKind, Boolean) -> Unit,
    val onSaveProfile: (Int?, BiologicalSex, Double?) -> Unit,
    val onResetModel: () -> Unit,
)

private enum class ProfilePage { MAIN, NOTIFICATIONS, CALCULATES }

/**
 * Profile: customising lives here, not on the page. Display switches that apply
 * at once, language, notifications, how Pawse calculates, and the few facts
 * Health Connect cannot tell us.
 */
@Composable
fun ProfileDialog(state: ProfileState, actions: ProfileActions, onDismiss: () -> Unit) {
    var page by remember { mutableStateOf(ProfilePage.MAIN) }
    PawseSheet(onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            when (page) {
                ProfilePage.MAIN -> Main(state, actions, onDismiss, onOpen = { page = it })
                ProfilePage.NOTIFICATIONS -> Notifications(state.notifications, actions.onNotify, onBack = { page = ProfilePage.MAIN })
                ProfilePage.CALCULATES -> Calculates(onBack = { page = ProfilePage.MAIN })
            }
        }
    }
}

@Composable
private fun Main(state: ProfileState, actions: ProfileActions, onDismiss: () -> Unit, onOpen: (ProfilePage) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tr("Profile", "프로필"), style = PawseType.Heading, modifier = Modifier.weight(1f))
        GlyphButton(Glyphs.Close, tr("Close", "닫기"), onClick = onDismiss)
    }
    Text(tr("Customising lives here, not on the page.", "화면 편집은 여기에서 해요."), style = PawseType.Micro, color = PawseTheme.semantics.faint)

    Column(Modifier.padding(top = 8.dp)) {
        SwitchRow(
            tr("Bedside mode", "침대 모드"),
            tr("True black, dimmer colours, no glow", "완전한 검정, 더 어두운 색, 빛 번짐 없음"),
            state.appearance == Appearance.BEDSIDE,
        ) { actions.onAppearance(if (it) Appearance.BEDSIDE else Appearance.SYSTEM) }
        SwitchRow(
            tr("Light theme", "밝은 테마"),
            tr("Dark is the reference; light is tuned to match", "기본은 어두운 테마예요"),
            state.appearance == Appearance.LIGHT,
        ) { actions.onAppearance(if (it) Appearance.LIGHT else Appearance.DARK) }
        SwitchRow(
            tr("Hide Body's number", "컨디션 숫자 숨기기"),
            tr("Words only on Scores; the detail still shows the arithmetic", "점수 화면에는 말로만, 자세히 보기에는 계산이 그대로 나와요"),
            !state.showNumbers,
        ) { actions.onShowNumbers(!it) }
        SwitchRow(
            tr("Reduce motion", "동작 줄이기"),
            tr("Follows the system too; fades stay", "시스템 설정도 따라요. 페이드는 남아요"),
            state.reduceMotion,
            onChange = actions.onReduceMotion,
        )
        SwitchRow(
            tr("Shapes as well as colour", "색과 함께 모양 표시"),
            tr("▲ above, ▼ below, ● typical", "▲ 높음, ▼ 낮음, ● 평소"),
            state.shapes,
            onChange = actions.onShapes,
        )
    }

    if (Build.VERSION.SDK_INT >= 33) Language()

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 16.dp))
    val on = state.notifications.count { it.value }
    LinkRow(tr("Notifications", "알림"), tr("$on on · one note a week by default", "${on}개 켜짐 · 기본은 일주일에 한 번")) { onOpen(ProfilePage.NOTIFICATIONS) }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    LinkRow(tr("How Pawse calculates", "Pawse 계산 방법"), tr("Every equation, in plain words", "모든 계산식을 쉬운 말로")) { onOpen(ProfilePage.CALCULATES) }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

    You(state.profile, actions.onSaveProfile)
    state.calibration?.let { YourModel(it, actions.onResetModel) }
}

/** The app's language, on Android 13 and up where each app can have its own. */
@RequiresApi(33)
@Composable
private fun Language() {
    val context = LocalContext.current
    val manager = context.getSystemService(LocaleManager::class.java)
    var korean by remember { mutableStateOf(Locale.getDefault().language == "ko") }
    Text(tr("Language", "언어"), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
    Segmented(
        options = listOf(false, true),
        selected = korean,
        onSelect = {
            korean = it
            manager.applicationLocales = LocaleList.forLanguageTags(if (it) "ko" else "en")
        },
        label = { if (it) "한국어" else "English" },
        height = 32.dp,
    )
}

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val s = PawseTheme.semantics
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .pressable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = PawseType.BodyStrong, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(sub, style = PawseType.Micro, color = s.faint)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = s.selection,
                checkedThumbColor = s.onSelection,
                checkedBorderColor = Color.Transparent,
                uncheckedTrackColor = s.baselineBand,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun LinkRow(title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .pressable(onClick = onClick)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = PawseType.BodyStrong)
            Text(sub, style = PawseType.Micro, color = PawseTheme.semantics.faint)
        }
        Glyph(Glyphs.ChevronRight, size = 16.dp, color = PawseTheme.semantics.faint)
    }
}

@Composable
private fun PageHead(title: String, sub: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlyphButton(Glyphs.ChevronLeft, tr("Back to profile", "프로필로 돌아가기"), onClick = onBack, modifier = Modifier.padding(end = 4.dp))
        Text(title, style = PawseType.Heading)
    }
    Text(sub, style = PawseType.Micro, color = PawseTheme.semantics.faint)
}

/**
 * Six kinds, each saying when it arrives. The grant is asked the first time one is
 * switched on, never at launch. Quiet hours follow bedtime and wake; only the
 * overnight heart rate alert passes them.
 */
@Composable
private fun Notifications(state: Map<NotifyKind, Boolean>, onNotify: (NotifyKind, Boolean) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<NotifyKind?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pending?.let { if (granted) onNotify(it, true) }
        pending = null
    }
    fun flip(kind: NotifyKind, on: Boolean) {
        val needsGrant = on && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsGrant) {
            pending = kind
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onNotify(kind, on)
        }
    }
    PageHead(tr("Notifications", "알림"), tr("With the defaults Pawse sends one note a week.", "기본 설정에서는 일주일에 한 번만 보내요."), onBack)
    Column(Modifier.padding(top = 8.dp)) {
        SwitchRow(
            tr("Weekly note", "주간 노트"),
            tr("Once a week, after the morning read. What changed, in two lines.", "일주일에 한 번, 아침 기록을 읽은 뒤. 바뀐 점을 두 줄로."),
            state[NotifyKind.WEEKLY] == true,
        ) { flip(NotifyKind.WEEKLY, it) }
        SwitchRow(
            tr("Overnight heart rate", "야간 심박수"),
            tr("Only when it stays high for several nights. The one alert that can wake you.", "며칠 밤 계속 높을 때만. 잠을 깨울 수 있는 유일한 알림이에요."),
            state[NotifyKind.ILLNESS] == true,
        ) { flip(NotifyKind.ILLNESS, it) }
        SwitchRow(
            tr("Morning summary ready", "아침 요약 준비됨"),
            tr("Needs the model on this phone", "휴대폰 속 모델이 필요해요"),
            checked = false,
            enabled = false,
        ) {}
        SwitchRow(
            tr("Wind-down", "쉬기 시작"),
            tr("At your wind-down time from the forecast.", "예측한 쉬기 시작 시간에."),
            state[NotifyKind.WIND_DOWN] == true,
        ) { flip(NotifyKind.WIND_DOWN, it) }
        SwitchRow(
            tr("No new night", "새 수면 기록 없음"),
            tr("In the morning, if your watch hasn't synced last night.", "워치가 지난밤 기록을 동기화하지 않았으면 아침에."),
            state[NotifyKind.NO_NIGHT] == true,
        ) { flip(NotifyKind.NO_NIGHT, it) }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 8.dp))
    Column(Modifier.padding(vertical = 12.dp)) {
        Text(tr("Quiet hours", "방해 금지 시간"), style = PawseType.BodyStrong)
        Text(
            tr("From your usual bedtime to wake. Only the overnight heart rate alert comes through.", "평소 취침부터 기상까지. 야간 심박수 알림만 울려요."),
            style = PawseType.Micro,
            color = PawseTheme.semantics.faint,
        )
    }
}

/** One calm page: each number's equation, what goes in, and where the data comes from. */
@Composable
private fun Calculates(onBack: () -> Unit) {
    PageHead(tr("How Pawse calculates", "Pawse 계산 방법"), tr("Every number, in plain words", "모든 숫자를 쉬운 말로"), onBack)
    listOf(
        tr("Body", "컨디션") to tr(
            "Last night's HRV and sleeping heart rate, each smoothed by a filter that trusts a night as much as its quality allows, then set against your normal: the median of the 60 nights ending a week ago. The two are combined without counting the same nervous system twice, and Body = 100 × Φ(z), so your normal reads 50. Typical is 31–69. The likely range is the filter's own uncertainty.",
            "지난밤 HRV와 수면 중 심박수를 밤의 품질만큼만 믿는 필터로 다듬은 뒤, 일주일 전에 끝나는 60일 밤의 중앙값인 내 평소와 비교해요. 같은 신경계를 두 번 세지 않게 둘을 합치고, 컨디션 = 100 × Φ(z)라서 평소는 50이에요. 평소 범위는 31–69예요. 예상 범위는 필터 자체의 불확실성이에요.",
        ),
        tr("Sleep", "수면") to tr(
            "Hours asleep against your need. Need is learned from nights off and rises a little after a hard day: 4 minutes a load point above your usual day, at most 30. Stages are shown, never scored. The optional Sleep score weighs duration 45, regularity 35 and continuity 20.",
            "필요한 잠에 대한 실제 수면 시간이에요. 필요한 잠은 쉬는 날 밤에서 배우고, 힘든 날 뒤에는 평소보다 부하 1점마다 4분, 최대 30분 늘어요. 수면 단계는 보여주기만 하고 점수에 넣지 않아요. 선택 항목인 수면 점수는 길이 45, 규칙성 35, 연속성 20으로 계산해요.",
        ),
        tr("Load", "부하") to tr(
            "Each session's TRIMP from heart rate (Banister), scaled so your typical active day is 10 points. Today's range is r to r + 5 with r = round(5 + Body ÷ 20). With the illness alert, or Body strained two mornings running, it drops to 0–5. Fitness and fatigue are 42- and 7-day averages; form is the gap between them.",
            "운동마다 심박수로 TRIMP(Banister)를 구하고, 평소 활동한 날이 10점이 되게 맞춰요. 오늘의 범위는 r = round(5 + 컨디션 ÷ 20)일 때 r에서 r + 5까지예요. 질병 알림이 있거나 이틀 연속 컨디션이 크게 낮으면 0–5로 내려가요. 체력과 피로는 42일과 7일 평균이고, 폼은 그 차이예요.",
        ),
        tr("Your usual and ≈", "평소 범위와 ≈") to tr(
            "Your usual is the median ± 1.4826 × MAD of the last 60 days, so one sick week can't widen it. A change is named only when it is bigger than half the usual day-to-day change; otherwise it reads about the same.",
            "평소 범위는 최근 60일의 중앙값 ± 1.4826 × MAD라서, 아픈 한 주가 범위를 넓히지 못해요. 변화는 평소 하루 사이 변화의 절반보다 클 때만 말하고, 아니면 거의 같다고 해요.",
        ),
    ).forEach { (title, body) ->
        Text(title, style = PawseType.BodyStrong, modifier = Modifier.padding(top = 20.dp))
        Text(body, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
    Text(
        tr("Every default is a reasoned starting point, not a validated value.", "모든 기본값은 근거 있는 출발점이지, 검증된 값은 아니에요."),
        style = PawseType.Micro,
        color = PawseTheme.semantics.faint,
        modifier = Modifier.padding(top = 20.dp),
    )
}

/**
 * The facts Health Connect has no record for. Age and sex feed two published
 * equations, Tanaka's HRmax and Banister's TRIMP.
 */
@Composable
private fun You(profile: UserProfile?, onSave: (Int?, BiologicalSex, Double?) -> Unit) {
    var age by remember { mutableStateOf(profile?.ageYears?.toString().orEmpty()) }
    var maxHr by remember { mutableStateOf(profile?.measuredMaxHeartRate?.toInt()?.toString().orEmpty()) }
    var sex by remember { mutableStateOf(profile?.biologicalSex ?: BiologicalSex.UNSPECIFIED) }
    val estimate = age.toIntOrNull()?.takeIf { it in 10..110 }?.let { (208 - 0.7 * it).toInt() }
    val grey = MaterialTheme.colorScheme.onSurfaceVariant

    Text(tr("You", "나"), style = PawseType.Heading, modifier = Modifier.padding(top = 24.dp))
    Text(
        tr(
            "Training load divides by your heart-rate range, and Health Connect has no record of age or sex. Leave either out and Pawse says so wherever it matters.",
            "훈련 부하는 심박수 범위로 나눠 계산하는데, 헬스 커넥트에는 나이나 성별 기록이 없어요. 입력하지 않으면 Pawse가 관련된 곳마다 그렇다고 알려 드려요.",
        ),
        style = PawseType.Label,
        color = grey,
        modifier = Modifier.padding(top = 4.dp),
    )
    Text(tr("Age", "나이"), style = PawseType.Label, color = grey, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
    PawseField(value = age, onValueChange = { new -> age = new.filter { it.isDigit() }.take(3) }, suffix = tr("years", "세"), numeric = true, height = 48.dp)
    Text(tr("Sex, for the training-load curve", "성별 (훈련 부하 곡선용)"), style = PawseType.Label, color = grey, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
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
    Text(tr("Measured max heart rate · optional", "측정한 최대 심박수 · 선택"), style = PawseType.Label, color = grey, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
    PawseField(
        value = maxHr,
        onValueChange = { new -> maxHr = new.filter { it.isDigit() }.take(3) },
        suffix = "bpm",
        placeholder = estimate?.let { tr("Estimated $it from your age", "나이로 추정한 값 $it") },
        numeric = true,
        height = 48.dp,
        textStyle = PawseType.Body,
    )
    PrimaryButton(
        text = tr("Save", "저장"),
        onClick = { onSave(age.toIntOrNull()?.takeIf { it in 10..110 }, sex, maxHr.toDoubleOrNull()?.takeIf { it in 100.0..230.0 }) },
        modifier = Modifier.padding(top = 20.dp),
    )
}

/** Whether Body tracks how you feel, each change the model made, and one way back to the defaults. */
@Composable
private fun YourModel(c: Calibration, onReset: () -> Unit) {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    Text(tr("Your model", "내 모델"), style = PawseType.Heading, modifier = Modifier.padding(top = 28.dp))
    Text(
        c.bodyMatch?.let {
            val r = String.format(Locale.getDefault(), "%.2f", it)
            tr("Body matched how you felt: r = $r over ${c.checkIns} mornings.", "아침 ${c.checkIns}번 동안 컨디션과 느낀 기분의 일치도: r = $r.")
        } ?: tr(
            "Check in a few mornings and Pawse will show how well Body tracks how you feel.",
            "몇 번 아침에 기분을 기록하면, 컨디션이 내 기분을 얼마나 잘 따라가는지 보여 드려요.",
        ),
        style = PawseType.Label,
        color = grey,
        modifier = Modifier.padding(top = 4.dp),
    )
    Text(
        tr("HRV's share of Body: ${(c.hrvWeight * 100).toInt()}%. After 30 check-ins it adapts to you, slowly.", "컨디션 내 HRV 비중: ${(c.hrvWeight * 100).toInt()}%. 기분 기록이 30번 쌓이면 천천히 나에게 맞춰져요."),
        style = PawseType.Micro,
        color = PawseTheme.semantics.faint,
        modifier = Modifier.padding(top = 4.dp),
    )
    c.changes.takeLast(MAX_CHANGES).reversed().forEach { change ->
        Text(
            "${change.date.format(DateTimeFormatter.ofPattern(tr("d MMM", "M월 d일"), Locale.getDefault()))} · ${change.what}: " +
                "${String.format(Locale.getDefault(), "%.2f", change.from)} → ${String.format(Locale.getDefault(), "%.2f", change.to)}",
            style = PawseType.Micro,
            color = PawseTheme.semantics.faint,
        )
    }
    GhostButton(tr("Reset to defaults", "기본값으로 되돌리기"), onClick = onReset)
}

private const val MAX_CHANGES = 6
