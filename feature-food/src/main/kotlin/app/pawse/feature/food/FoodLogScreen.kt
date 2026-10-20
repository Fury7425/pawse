package app.pawse.feature.food

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pawse.core.data.food.FoodDay
import app.pawse.core.data.food.FoodLogEntry
import app.pawse.core.data.food.FoodSource
import app.pawse.core.data.food.Nutrients
import app.pawse.core.data.health.HealthPermissions
import app.pawse.core.ui.component.EaseOut
import app.pawse.core.ui.component.GhostButton
import app.pawse.core.ui.component.Glyph
import app.pawse.core.ui.component.GlyphButton
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.KeySwatch
import app.pawse.core.ui.component.PawseCard
import app.pawse.core.ui.component.PawseSheet
import app.pawse.core.ui.component.RevealEnter
import app.pawse.core.ui.component.RevealExit
import app.pawse.core.ui.component.SecondaryButton
import app.pawse.core.ui.component.SplitBar
import app.pawse.core.ui.component.UndoToast
import app.pawse.core.ui.component.aura
import app.pawse.core.ui.component.cardSurface
import app.pawse.core.ui.component.pressable
import app.pawse.core.ui.theme.PawseType
import app.pawse.scoring.tr
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The food log: a day, its meals, and what they add up to.
 *
 * The total is never shown alone. Every entry carries where its figures came from,
 * and the line under the total says how many items had no energy figure at all —
 * the same discipline the score screens use, for the same reason. A calorie total
 * that quietly drops the three items it could not price is a worse number than no
 * total.
 *
 * There are no targets here, and no ring to close. Whether 1,900 kcal was the right
 * number for this person on this day is not something this app knows, and inventing
 * a goal would be inventing the one number the user might actually act on.
 */
@Composable
fun FoodLogScreen(
    onManualEntry: () -> Unit,
    onOpenBody: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FoodLogViewModel = hiltViewModel(),
) {
    val date by viewModel.date.collectAsStateWithLifecycle()
    val day by viewModel.day.collectAsStateWithLifecycle()
    val week by viewModel.week.collectAsStateWithLifecycle()
    val again by viewModel.again.collectAsStateWithLifecycle()
    val energy by viewModel.energy.collectAsStateWithLifecycle()
    val undo by viewModel.undo.collectAsStateWithLifecycle()
    val networkEnabled by viewModel.networkEnabled.collectAsStateWithLifecycle()
    val mirroring by viewModel.mirroring.collectAsStateWithLifecycle()
    val writeBlocked by viewModel.writeBlocked.collectAsStateWithLifecycle()
    // Health Connect stops showing its sheet after repeated denials, so a second
    // tap goes to its settings instead of to a sheet that never appears.
    var askedForWrite by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val grantLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) {
        askedForWrite = true
        viewModel.onWriteGrantResult()
    }
    val allowWrite: () -> Unit = {
        if (askedForWrite) {
            runCatching { context.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }
            Unit
        } else {
            grantLauncher.launch(HealthPermissions.NUTRITION_WRITE)
        }
    }
    var showSettings by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<Long?>(null) }
    val isToday = date == LocalDate.now()
    val pad = Modifier.padding(horizontal = 24.dp)

    Scaffold(modifier = modifier, containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(Modifier.fillMaxSize().aura()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding() + 12.dp,
                    bottom = padding.calculateBottomPadding() + 140.dp,
                ),
            ) {
                item {
                    Row(pad.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Food", "음식"), style = PawseType.Title, modifier = Modifier.weight(1f))
                        GlyphButton(Glyphs.Sliders, tr("Food settings", "음식 설정"), onClick = { showSettings = true })
                    }
                    WeekStrip(
                        week = week,
                        selected = date,
                        onPick = { viewModel.showDay(it); open = null },
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                    )
                    DayHeader(date = date, modifier = pad.padding(top = 16.dp))
                }

                item {
                    DayTotal(day = day, modifier = pad.padding(top = 8.dp))
                }

                if (mirroring && writeBlocked) {
                    item {
                        WriteBlockedCard(askedBefore = askedForWrite, onClick = allowWrite, modifier = pad.padding(top = 10.dp))
                    }
                }

                if (isToday) {
                    energy?.burnedKcal?.let { burned ->
                        item {
                            BurnedCard(
                                burnedKcal = burned,
                                differenceKcal = energy?.differenceKcal ?: 0.0,
                                onClick = onOpenBody,
                                modifier = pad.padding(top = 10.dp),
                            )
                        }
                    }
                    if (again.isNotEmpty()) {
                        item {
                            Text(
                                text = tr("Again · one tap logs it as a snack", "다시 먹기 · 한 번 누르면 간식으로 기록돼요"),
                                style = PawseType.Label,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = pad.padding(top = 24.dp, bottom = 8.dp),
                            )
                            Row(
                                modifier = Modifier
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                again.forEach { entry -> AgainChip(entry, onClick = { viewModel.logAgain(entry) }) }
                            }
                        }
                    }
                }

                val meals = day?.byMeal.orEmpty()
                if (meals.isEmpty()) {
                    item { EmptyDay(isToday = isToday, date = date, modifier = pad.padding(top = 24.dp)) }
                }

                meals.forEach { (meal, entries) ->
                    item(key = "meal-$meal") {
                        Row(pad.padding(top = 24.dp, bottom = 2.dp)) {
                            Text(FoodCopy.meal(meal), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            Text(
                                "${"%,d".format(entries.sumOf { it.nutrients.kcal ?: 0.0 }.roundToInt())} kcal",
                                style = PawseType.Label,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(entries, key = { it.id }) { entry ->
                        EntryRow(
                            entry = entry,
                            expanded = open == entry.id,
                            onToggle = { open = if (open == entry.id) null else entry.id },
                            onDelete = { open = null; viewModel.delete(entry) },
                            modifier = pad,
                        )
                    }
                }

                item {
                    GhostButton(
                        tr("Add something without a barcode", "바코드 없이 추가하기"),
                        onClick = onManualEntry,
                        glyph = Glyphs.Pencil,
                        modifier = pad.padding(top = 16.dp),
                    )
                }
            }

            // Rises from behind the dock. Compose retargets mid-flight, so a second undo
            // picks up where the first was instead of restarting; it leaves at once.
            AnimatedVisibility(
                visible = undo != null,
                enter = slideInVertically(tween(240, easing = EaseOut)) { it } + fadeIn(tween(240, easing = EaseOut)),
                exit = ExitTransition.None,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 100.dp),
            ) {
                undo?.let { UndoToast(text = it.text, onUndo = it.undo) }
            }
        }
    }

    if (showSettings) {
        FoodSettingsSheet(
            networkEnabled = networkEnabled,
            mirroring = mirroring,
            onNetworkChange = viewModel::setNetworkLookup,
            onMirroringChange = { on ->
                viewModel.setMirroring(on)
                if (on && writeBlocked) allowWrite()
            },
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun DayHeader(date: LocalDate, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(dayTitle(date), style = PawseType.Heading, modifier = Modifier.weight(1f))
        Text(
            text = date.format(DateTimeFormatter.ofPattern(tr("EEE d MMM", "M월 d일 (E)"), Locale.getDefault())),
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun dayTitle(date: LocalDate): String = when (date) {
    LocalDate.now() -> tr("Today", "오늘")
    LocalDate.now().minusDays(1) -> tr("Yesterday", "어제")
    else -> date.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()))
}

/** Seven days, a dot under each one that has anything logged. */
@Composable
private fun WeekStrip(
    week: List<FoodDay>,
    selected: LocalDate,
    onPick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        week.forEach { foodDay ->
            val isSelected = foodDay.date == selected
            val logged = foodDay.entries.isNotEmpty()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(64.dp)
                    .pressable { onPick(foodDay.date) }
                    .then(if (isSelected) Modifier.cardSurface(RoundedCornerShape(12.dp), 12.dp) else Modifier)
                    .padding(top = 8.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = foodDay.date.format(DateTimeFormatter.ofPattern(tr("EEE d MMM", "M월 d일 (E)"), Locale.getDefault())) +
                            if (logged) "" else tr(", nothing logged", ", 기록 없음")
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = foodDay.date.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())),
                    style = PawseType.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(foodDay.date.dayOfMonth.toString(), style = PawseType.Heading)
                Box(
                    Modifier
                        .size(4.dp)
                        .alpha(if (logged) 1f else 0f)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
                )
            }
        }
    }
}

/** The day's kcal, the caveat attached to it, and the protein / carbs / fat split. */
@Composable
private fun DayTotal(day: FoodDay?, modifier: Modifier = Modifier) {
    val totals = day?.totals
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(totals?.kcalRounded?.let { "%,d".format(it) } ?: "—", style = PawseType.Hero)
            Spacer(Modifier.width(8.dp))
            Text(
                "kcal",
                style = PawseType.Heading.copy(fontWeight = PawseType.Body.fontWeight),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = totals?.let { FoodCopy.totalsNote(it) } ?: tr("Nothing logged yet.", "아직 기록이 없어요."),
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val n = totals?.totals
        if (n != null && (n.proteinGrams != null || n.carbsGrams != null || n.fatGrams != null)) {
            Spacer(Modifier.height(16.dp))
            MacroCard(n)
        }
    }
}

@Composable
private fun MacroCard(n: Nutrients) {
    val p = n.proteinGrams ?: 0.0
    val c = n.carbsGrams ?: 0.0
    val f = n.fatGrams ?: 0.0
    val energy = p * 4 + c * 4 + f * 9
    val ps = if (energy > 0) (p * 4 / energy * 100).roundToInt() else 0
    val cs = if (energy > 0) (c * 4 / energy * 100).roundToInt() else 0
    val fs = if (energy > 0) 100 - ps - cs else 0
    val on = MaterialTheme.colorScheme.onSurface
    val onv = MaterialTheme.colorScheme.onSurfaceVariant
    val ol = MaterialTheme.colorScheme.outline
    PawseCard(padding = PaddingValues(vertical = 14.dp)) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            listOf(tr("Protein", "단백질") to p, tr("Carbs", "탄수화물") to c, tr("Fat", "지방") to f).forEachIndexed { i, (name, grams) ->
                if (i > 0) VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.fillMaxHeight())
                Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    Text("${grams.roundToInt()} g", style = PawseType.MetricValue)
                    Spacer(Modifier.height(2.dp))
                    Text(name, style = PawseType.Label, color = onv)
                }
            }
        }
        if (energy > 0) {
            Column(
                Modifier
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = tr(
                            "Share of energy: protein $ps%, carbs $cs%, fat $fs%",
                            "열량 비율: 단백질 $ps%, 탄수화물 $cs%, 지방 $fs%",
                        )
                    },
            ) {
                SplitBar(listOf(ps.toFloat() to on, cs.toFloat() to onv, fs.toFloat() to ol))
                Spacer(Modifier.height(8.dp))
                Row {
                    KeySwatch(on, tr("$ps% of energy", "열량의 $ps%"), Modifier.weight(1f))
                    KeySwatch(onv, "$cs%", Modifier.weight(1f).padding(start = 16.dp))
                    KeySwatch(ol, "$fs%", Modifier.weight(1f).padding(start = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun BurnedCard(
    burnedKcal: Double,
    differenceKcal: Double,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PawseCard(modifier = modifier, onClick = onClick, padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Burned so far", "지금까지 소모"), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = "${"%,d".format(burnedKcal.roundToInt())} kcal · " + when {
                        differenceKcal >= 0.5 -> {
                            val more = "%,d".format(differenceKcal.roundToInt())
                            tr("$more more than eaten", "섭취보다 $more 많음")
                        }
                        differenceKcal <= -0.5 -> {
                            val less = "%,d".format((-differenceKcal).roundToInt())
                            tr("$less less than eaten", "섭취보다 $less 적음")
                        }
                        else -> tr("the same as eaten", "섭취와 같음")
                    },
                    style = PawseType.BodyStrong,
                )
            }
            Glyph(Glyphs.ChevronRight, size = 18.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun WriteBlockedCard(askedBefore: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PawseCard(modifier = modifier, onClick = onClick, padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Not reaching Health Connect", "헬스 커넥트에 저장되지 않아요"), style = PawseType.BodyStrong)
                Text(
                    text = if (askedBefore) {
                        tr(
                            "Still not allowed. Open Health Connect and let Pawse write nutrition.",
                            "아직 허용되지 않았어요. 헬스 커넥트를 열고 Pawse의 영양 정보 기록을 허용하세요.",
                        )
                    } else {
                        tr(
                            "Health Connect hasn't allowed Pawse to save food. Tap to allow it.",
                            "헬스 커넥트에서 Pawse의 음식 저장을 허용하지 않았어요. 눌러서 허용하세요.",
                        )
                    },
                    style = PawseType.Label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Glyph(Glyphs.ChevronRight, size = 18.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AgainChip(entry: FoodLogEntry, onClick: () -> Unit) {
    Row(
        Modifier
            .height(44.dp)
            .pressable(onClick = onClick)
            .cardSurface(RoundedCornerShape(12.dp), 12.dp)
            .padding(start = 10.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(Glyphs.Plus, size = 16.dp, strokeWidth = 2f, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(entry.name, style = PawseType.BodyStrong, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Text(FoodCopy.energy(entry.nutrients), style = PawseType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun EmptyDay(isToday: Boolean, date: LocalDate, modifier: Modifier = Modifier) {
    PawseCard(modifier = modifier, padding = PaddingValues(20.dp)) {
        Box(
            Modifier
                .size(width = 72.dp, height = 40.dp)
                .background(MaterialTheme.colorScheme.background, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(7) {
                    Box(Modifier.size(width = 4.dp, height = 4.dp).background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            if (isToday) tr("Nothing logged yet", "아직 기록이 없어요") else tr("Nothing logged on ${dayTitle(date)}", "${dayTitle(date)}에는 기록이 없어요"),
            style = PawseType.Heading,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (isToday) {
                tr(
                    "Scan a barcode, photograph a nutrition panel, or type it in. " +
                        "Everything you scan is saved on this phone, so the second time is instant.",
                    "바코드를 스캔하거나, 영양성분표를 찍거나, 직접 입력하세요. 스캔한 건 모두 이 휴대폰에 " +
                        "저장되니 두 번째부터는 바로 나와요.",
                )
            } else {
                tr("A gap is a true record too. Add what you remember, or leave it.", "빈 날도 사실 그대로의 기록이에요. 기억나는 걸 추가하거나 그대로 두세요.")
            },
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun sourceGlyph(source: FoodSource): String = when (source) {
    FoodSource.OPEN_FOOD_FACTS -> Glyphs.Globe
    FoodSource.LABEL_OCR -> Glyphs.Panel
    FoodSource.MANUAL -> Glyphs.Pencil
}

@Composable
private fun EntryRow(
    entry: FoodLogEntry,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val inHc = entry.healthConnectId != null
    val onv = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .cardSurface(RoundedCornerShape(12.dp), 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Glyph(sourceGlyph(entry.source), size = 18.dp, color = onv)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = PawseType.Body)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = listOfNotNull(entry.brand, FoodCopy.grams(entry.quantityGrams)).joinToString(" · "),
                        style = PawseType.Label,
                        color = onv,
                    )
                    if (inHc) {
                        Spacer(Modifier.width(6.dp))
                        Glyph(
                            Glyphs.Synced,
                            size = 13.dp,
                            strokeWidth = 2f,
                            color = onv,
                            modifier = Modifier.semantics { contentDescription = tr("In Health Connect", "헬스 커넥트에 있음") },
                        )
                    }
                }
            }
            Text(
                text = FoodCopy.energy(entry.nutrients),
                style = PawseType.MetricValue,
                color = if (entry.nutrients.kcal == null) onv else MaterialTheme.colorScheme.onSurface,
            )
        }

        AnimatedVisibility(expanded, enter = RevealEnter, exit = RevealExit) {
            Column(Modifier.padding(start = 52.dp, bottom = 16.dp)) {
                Text(
                    FoodCopy.source(entry.source) +
                        if (inHc) tr(" · in Health Connect", " · 헬스 커넥트에 있음") else tr(" · only on this phone", " · 이 휴대폰에만 있음"),
                    style = PawseType.Label,
                )
                Spacer(Modifier.height(2.dp))
                Text(FoodCopy.summary(entry.nutrients), style = PawseType.Label, color = onv)
                Spacer(Modifier.height(12.dp))
                SecondaryButton(
                    text = if (inHc) tr("Remove · also from Health Connect", "삭제 · 헬스 커넥트에서도") else tr("Remove", "삭제"),
                    onClick = onDelete,
                    height = 44.dp,
                    radius = 12.dp,
                    glyph = Glyphs.Trash,
                    textStyle = PawseType.BodyStrong,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Two switches that apply at once. No Save button, nothing to forget. */
@Composable
private fun FoodSettingsSheet(
    networkEnabled: Boolean,
    mirroring: Boolean,
    onNetworkChange: (Boolean) -> Unit,
    onMirroringChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    PawseSheet(onDismiss = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Food settings", "음식 설정"), style = PawseType.Title, modifier = Modifier.weight(1f))
            GlyphButton(Glyphs.Close, tr("Close", "닫기"), onClick = onDismiss)
        }
        SettingRow(
            title = tr("Look barcodes up online", "바코드 온라인 검색"),
            body = tr(
                "Sends the barcode to Open Food Facts and nothing else. Off by default. " +
                    "Reading the label with the camera works either way.",
                "Open Food Facts에 바코드만 보내요. 기본값은 꺼짐이에요. 카메라로 라벨을 읽는 기능은 " +
                    "어느 쪽이든 쓸 수 있어요.",
            ),
            now = if (networkEnabled) {
                tr("Unknown barcodes are looked up once, then saved", "모르는 바코드는 한 번 찾아본 뒤 저장해요")
            } else {
                tr("Unknown barcodes ask you first", "모르는 바코드는 먼저 물어봐요")
            },
            checked = networkEnabled,
            onCheckedChange = onNetworkChange,
        )
        SettingRow(
            title = tr("Copy food into Health Connect", "헬스 커넥트에 음식 복사"),
            body = tr("Removing an entry here removes it there too.", "여기서 기록을 지우면 헬스 커넥트에서도 지워져요."),
            now = if (mirroring) {
                tr("New entries are copied as you log them", "새 기록은 입력할 때마다 복사해요")
            } else {
                tr("Entries stay only on this phone", "기록은 이 휴대폰에만 남아요")
            },
            checked = mirroring,
            onCheckedChange = onMirroringChange,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            tr(
                "Your health data never leaves this phone, with either switch on or off.",
                "어떤 스위치를 켜거나 꺼도 건강 데이터는 이 휴대폰을 떠나지 않아요.",
            ),
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingRow(
    title: String,
    body: String,
    now: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column {
        Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(title, style = PawseType.Heading)
                Spacer(Modifier.height(4.dp))
                Text(body, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Glyph(Glyphs.Arrow, size = 14.dp, strokeWidth = 2f, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(now, style = PawseType.Label)
                }
            }
            Spacer(Modifier.width(16.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.onSurface,
                    checkedThumbColor = MaterialTheme.colorScheme.background,
                    checkedBorderColor = MaterialTheme.colorScheme.onSurface,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                ),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
