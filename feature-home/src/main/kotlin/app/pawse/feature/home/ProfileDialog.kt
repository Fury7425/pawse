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
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import app.pawse.core.ui.component.PawseSwitch
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
import androidx.compose.ui.semantics.contentDescription
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
import app.pawse.core.ui.component.rememberHaptic
import app.pawse.core.ui.component.toggleHaptic
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
    /** Null is Automatic. */
    val cardOrder: List<ScoresCard>?,
    val hiddenCards: Set<ScoresCard>,
    /** The rings are swapped for one line until tomorrow morning. */
    val hiddenToday: Boolean = false,
)

class ProfileActions(
    val onShowNumbers: (Boolean) -> Unit,
    val onAppearance: (Appearance) -> Unit,
    val onReduceMotion: (Boolean) -> Unit,
    val onShapes: (Boolean) -> Unit,
    val onNotify: (NotifyKind, Boolean) -> Unit,
    val onSaveProfile: (Int?, BiologicalSex, Double?) -> Unit,
    val onResetModel: () -> Unit,
    val onCardOrder: (List<ScoresCard>?) -> Unit,
    val onHidden: (ScoresCard, Boolean) -> Unit,
    val onHideToday: (Boolean) -> Unit = {},
)

private enum class ProfilePage { MAIN, NOTIFICATIONS, CARDS }

/**
 * Profile: customising lives here, not on the page. Display switches that apply
 * at once, language, notifications, how Pawse calculates, and the few facts
 * Health Connect cannot tell us.
 */
@Composable
fun ProfileDialog(state: ProfileState, actions: ProfileActions, onDismiss: () -> Unit, onOpenCalc: () -> Unit = {}) {
    var page by remember { mutableStateOf(ProfilePage.MAIN) }
    PawseSheet(onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            when (page) {
                ProfilePage.MAIN -> Main(state, actions, onDismiss, onOpen = { page = it }, onOpenCalc = onOpenCalc)
                ProfilePage.NOTIFICATIONS -> Notifications(state.notifications, actions.onNotify, onBack = { page = ProfilePage.MAIN })
                ProfilePage.CARDS -> CardOrder(state.cardOrder, state.hiddenCards, actions, onBack = { page = ProfilePage.MAIN })
            }
        }
    }
}

@Composable
private fun Main(state: ProfileState, actions: ProfileActions, onDismiss: () -> Unit, onOpen: (ProfilePage) -> Unit, onOpenCalc: () -> Unit) {
    // As on the canvas: the title and one line under it; the sheet closes by its handle or the scrim.
    Text(tr("Profile", "프로필"), style = PawseType.Heading)
    Text(tr("Customising lives here, not on the page.", "화면 편집은 여기에서 해요."), style = PawseType.Micro, color = PawseTheme.semantics.faint, modifier = Modifier.padding(top = 4.dp))

    Column(Modifier.padding(top = 16.dp)) {
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
            tr("Hide scores today", "오늘 점수 숨기기"),
            tr("Comes back tomorrow morning", "내일 아침 다시 보여요"),
            state.hiddenToday,
            onChange = actions.onHideToday,
        )
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

    // Card order and Language as the canvas has them: two segmented switches in place.
    Text(tr("Card order", "카드 순서"), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
    Segmented(
        options = listOf(false, true),
        selected = state.cardOrder != null,
        onSelect = { mine -> actions.onCardOrder(if (mine) ScoresCard.complete(ScoresCard.automatic(evening = false)) else null) },
        label = { if (it) tr("My order", "직접 정렬") else tr("Automatic", "자동") },
    )
    if (state.cardOrder != null) {
        LinkRow(tr("Arrange the cards", "카드 배치"), tr("Their order and which show", "순서와 표시할 카드")) { onOpen(ProfilePage.CARDS) }
    }

    if (Build.VERSION.SDK_INT >= 33) Language()

    val on = state.notifications.count { it.value }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 16.dp))
    LinkRow(tr("Notifications", "알림"), tr("$on on · one note a week by default", "${on}개 켜짐 · 기본은 일주일에 한 번")) { onOpen(ProfilePage.NOTIFICATIONS) }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    LinkRow(tr("How Pawse calculates", "Pawse 계산 방법"), tr("Every equation, with today’s numbers", "모든 계산식과 오늘의 숫자"), onClick = onOpenCalc)
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

    You(state.profile, actions.onSaveProfile)
    Spacer(Modifier.height(24.dp))
    ModelSettingsSection()
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
    )
}

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val s = PawseTheme.semantics
    // Settings are rare, so they can carry a haptic.
    val haptic = rememberHaptic()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .pressable(enabled = enabled, role = Role.Switch) {
                haptic(toggleHaptic(!checked))
                onChange(!checked)
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = PawseType.BodyStrong, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(sub, style = PawseType.Micro, color = s.faint)
        }
        Spacer(Modifier.width(12.dp))
        PawseSwitch(checked = checked, enabled = enabled)
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
    Text(tr("What Pawse can send", "Pawse가 보낼 수 있는 것"), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 20.dp))
    Column(Modifier.padding(top = 4.dp)) {
        SwitchRow(
            tr("Weekly note", "주간 노트"),
            tr("Sunday 20:00. What changed this week, in two lines.", "일요일 20:00. 이번 주에 바뀐 점을 두 줄로."),
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
            tr("At 10:00 if your watch hasn’t synced last night.", "워치가 지난밤 기록을 동기화하지 않았으면 10:00에."),
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
    Text(
        tr(
            "With the defaults Pawse sends one note a week. The illness alert comes only when your overnight heart rate stays high.",
            "기본 설정에서는 일주일에 한 번만 보내요. 질병 알림은 야간 심박수가 계속 높을 때만 와요.",
        ),
        style = PawseType.Label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(top = 4.dp)
            .fillMaxWidth()
            .background(PawseTheme.semantics.baselineOuter, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

/**
 * The cards under Scores' rings. Automatic follows the time of day; My order keeps
 * the user's order at every hour, with any card hidden.
 */
@Composable
private fun CardOrder(order: List<ScoresCard>?, hidden: Set<ScoresCard>, actions: ProfileActions, onBack: () -> Unit) {
    PageHead(tr("Card order", "카드 순서"), tr("What sits under the rings on Scores", "점수 화면의 링 아래 카드"), onBack)
    Segmented(
        options = listOf(false, true),
        selected = order != null,
        onSelect = { mine -> actions.onCardOrder(if (mine) ScoresCard.complete(ScoresCard.automatic(evening = false)) else null) },
        label = { if (it) tr("My order", "내 순서") else tr("Automatic", "자동") },
        modifier = Modifier.padding(top = 16.dp),
    )
    if (order == null) {
        Text(
            tr(
                "Mornings lead with what's worth knowing and your day. From 6 pm, tonight and today's load.",
                "아침에는 알아둘 점과 오늘의 흐름이 먼저 나와요. 저녁 6시부터는 오늘 밤과 오늘의 부하가 나와요.",
            ),
            style = PawseType.Micro,
            color = PawseTheme.semantics.faint,
            modifier = Modifier.padding(top = 12.dp),
        )
        return
    }
    Column(Modifier.padding(top = 8.dp)) {
        order.forEachIndexed { i, card ->
            fun move(by: Int) = actions.onCardOrder(order.toMutableList().apply { add(i + by, removeAt(i)) })
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                if (i > 0) GlyphButton(Glyphs.ChevronUp, tr("Move ${card.label} up", "${card.label} 위로"), onClick = { move(-1) }) else Spacer(Modifier.width(48.dp))
                if (i < order.lastIndex) GlyphButton(Glyphs.ChevronDown, tr("Move ${card.label} down", "${card.label} 아래로"), onClick = { move(1) }) else Spacer(Modifier.width(48.dp))
                Text(
                    card.label,
                    style = PawseType.BodyStrong,
                    color = if (card in hidden) PawseTheme.semantics.faint else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                PawseSwitch(
                    checked = card !in hidden,
                    onChange = { actions.onHidden(card, !it) },
                    modifier = Modifier.semantics { contentDescription = tr("Show ${card.label}", "${card.label} 표시") },
                )
            }
        }
    }
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
