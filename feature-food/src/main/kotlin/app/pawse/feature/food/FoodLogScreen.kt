package app.pawse.feature.food

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import app.pawse.core.ui.component.PawseSwitch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pawse.core.data.food.FoodDay
import app.pawse.core.data.food.FoodLogEntry
import app.pawse.core.data.food.FoodSource
import app.pawse.core.data.food.Meal
import app.pawse.core.data.food.Nutrients
import app.pawse.core.data.health.HealthPermissions
import app.pawse.core.ui.component.ChoiceRow
import app.pawse.core.ui.component.EaseOut
import app.pawse.core.ui.component.GhostButton
import app.pawse.core.ui.component.Glyph
import app.pawse.core.ui.component.GlyphButton
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.Mark
import app.pawse.core.ui.component.PawseCard
import app.pawse.core.ui.component.PawseSheet
import app.pawse.core.ui.component.PrimaryButton
import app.pawse.core.ui.component.SecondaryButton
import app.pawse.core.ui.component.SelectChip
import app.pawse.core.ui.component.SplitRow
import app.pawse.core.ui.component.StatusBarScrim
import app.pawse.core.ui.component.UndoToast
import app.pawse.core.ui.component.WeekDay
import app.pawse.core.ui.component.WeekStrip
import app.pawse.core.ui.component.aura
import app.pawse.core.ui.component.pressable
import app.pawse.core.ui.component.LocalReduceMotion
import app.pawse.core.ui.component.PawseHaptic
import app.pawse.core.ui.component.REDUCED_FADE
import app.pawse.core.ui.component.rememberHaptic
import app.pawse.core.ui.component.rememberSwap
import app.pawse.core.ui.component.swapIn
import app.pawse.core.ui.theme.PawseTheme
import app.pawse.core.ui.theme.PawseType
import app.pawse.scoring.tr
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Food: a day, what it adds up to set against what was burned, and its meals.
 *
 * The total is never shown alone. Every entry carries where its figures came from,
 * and the line under the total says how many items had no energy figure at all.
 * There are no targets and no ring to close: eaten against burned stays grey, and
 * Pawse never turns the gap into a goal, a streak or praise.
 */
@Composable
fun FoodLogScreen(
    onManualEntry: () -> Unit,
    onOpenBody: () -> Unit,
    modifier: Modifier = Modifier,
    onScan: () -> Unit = onManualEntry,
    viewModel: FoodLogViewModel = hiltViewModel(),
) {
    val date by viewModel.date.collectAsStateWithLifecycle()
    val day by viewModel.day.collectAsStateWithLifecycle()
    val week by viewModel.week.collectAsStateWithLifecycle()
    val again by viewModel.again.collectAsStateWithLifecycle()
    val burned by viewModel.burned.collectAsStateWithLifecycle()
    val undo by viewModel.undo.collectAsStateWithLifecycle()
    val networkEnabled by viewModel.networkEnabled.collectAsStateWithLifecycle()
    val mirroring by viewModel.mirroring.collectAsStateWithLifecycle()
    val writeBlocked by viewModel.writeBlocked.collectAsStateWithLifecycle()
    val incomplete by viewModel.incomplete.collectAsStateWithLifecycle()
    val partly = date.toString() in incomplete
    // Health Connect stops showing its sheet after repeated denials, so a second
    // tap goes to its settings instead of to a sheet that never appears.
    var askedForWrite by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val grantLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
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
    var editing by remember { mutableStateOf<FoodLogEntry?>(null) }
    val haptic = rememberHaptic()
    val isToday = date == LocalDate.now()
    val pad = Modifier.padding(horizontal = 24.dp)
    val totals = day?.totals
    val has = (totals?.entryCount ?: 0) > 0

    Scaffold(modifier = modifier, containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(Modifier.fillMaxSize().aura()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 140.dp),
            ) {
                item {
                    Row(pad.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Food", "음식"), style = PawseType.Title, modifier = Modifier.weight(1f))
                        GlyphButton(Glyphs.Search, tr("Search foods", "음식 검색"), onClick = onScan)
                        GlyphButton(Glyphs.Sliders, tr("Food settings", "음식 설정"), onClick = { showSettings = true })
                    }
                    WeekStrip(
                        days = week.map { weekDay(it) },
                        selected = week.indexOfFirst { it.date == date },
                        onPick = { i -> week.getOrNull(i)?.let { viewModel.showDay(it.date) } },
                        modifier = Modifier.padding(start = 24.dp, top = 4.dp, end = 24.dp),
                    )
                    SplitRow(pad.padding(top = 24.dp)) {
                        Text(dayTitle(date), style = PawseType.Heading, modifier = Modifier.weight(1f))
                        Text(tr("dot = something logged", "점 = 기록 있음"), style = PawseType.Micro, color = PawseTheme.semantics.faint)
                    }
                }

                if (!has) {
                    item { EmptyDay(isToday, date, onManualEntry, pad.padding(top = 12.dp)) }
                } else {
                    item { Hero(day!!, if (isToday) burned else null, partly, pad.padding(top = 4.dp)) }
                    if (isToday && burned != null) item { EatenBurned(totals!!.totals.kcal ?: 0.0, burned!!, pad.padding(top = 24.dp)) }
                    item { Macros(totals!!.totals, week, date, pad.padding(top = 16.dp)) }
                }

                if (mirroring && writeBlocked) {
                    item { WriteBlockedCard(askedBefore = askedForWrite, onClick = allowWrite, modifier = pad.padding(top = 16.dp)) }
                }

                if (isToday && again.isNotEmpty()) {
                    item {
                        Text(
                            tr("Again · one tap adds your usual portion", "다시 · 한 번 누르면 평소 양이 추가돼요"),
                            style = PawseType.Label,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = pad.padding(top = 32.dp, bottom = 8.dp),
                        )
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            again.forEach { entry ->
                                AgainChip(entry, onClick = {
                                    haptic(PawseHaptic.Confirm)
                                    viewModel.logAgain(entry)
                                })
                            }
                        }
                    }
                }

                day?.byMeal.orEmpty().forEach { (meal, entries) ->
                    item(key = "meal-$meal") {
                        SplitRow(pad.padding(top = 24.dp, bottom = 4.dp)) {
                            Text(FoodCopy.meal(meal), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            Text(kcalText(entries.sumOf { it.nutrients.kcal ?: 0.0 }), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(entries, key = { it.id }) { entry ->
                        // A new row fades in at full height, a removed one fades out in 120 ms; nothing below slides.
                        EntryRow(
                            entry,
                            onClick = { editing = entry },
                            modifier = pad.animateItem(fadeInSpec = tween(220, easing = EaseOut), placementSpec = null, fadeOutSpec = tween(120, easing = EaseOut)),
                        )
                    }
                }

                if (has) {
                    item {
                        Column(pad.padding(top = 16.dp)) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            SplitRow(Modifier.padding(top = 12.dp)) {
                                Text(tr("Day", "하루"), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                Text("${totals?.kcalRounded?.let { "%,d".format(it) } ?: "—"} kcal", style = PawseType.MetricValue)
                            }
                            // A day not fully logged says so, and the weekly weight check leaves it out.
                            SelectChip(
                                tr("Partly logged", "일부만 기록함"),
                                selected = partly,
                                onClick = { viewModel.setIncomplete(date, !partly) },
                                modifier = Modifier.padding(top = 12.dp),
                            )
                            GhostButton(tr("Add manually", "직접 추가"), onClick = onManualEntry, glyph = Glyphs.Plus, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }

            // Rises from behind the dock in 240 ms and leaves the same way in 160; a fade only under reduce motion.
            val still = LocalReduceMotion.current
            AnimatedVisibility(
                visible = undo != null,
                enter = if (still) {
                    fadeIn(tween(REDUCED_FADE))
                } else {
                    slideInVertically(tween(240, easing = EaseOut)) { it } + fadeIn(tween(240, easing = EaseOut))
                },
                exit = if (still) {
                    fadeOut(tween(REDUCED_FADE))
                } else {
                    slideOutVertically(tween(160, easing = EaseOut)) { it } + fadeOut(tween(160))
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 100.dp),
            ) {
                undo?.let { UndoToast(text = it.text, onUndo = it.undo, onHold = viewModel::holdUndo) }
            }
            StatusBarScrim()
        }
    }

    editing?.let { entry ->
        EditSheet(
            entry = entry,
            onDismiss = { editing = null },
            onSave = { factor, meal ->
                editing = null
                viewModel.edit(entry, factor, meal)
            },
            onDelete = {
                editing = null
                viewModel.delete(entry)
            },
        )
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
private fun weekDay(d: FoodDay): WeekDay {
    val logged = d.entries.isNotEmpty()
    return WeekDay(
        name = d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
        number = d.date.dayOfMonth.toString(),
        mark = if (logged) PawseTheme.semantics.neutral else null,
        hollow = !logged,
        description = d.date.format(DateTimeFormatter.ofPattern(tr("EEEE d MMMM", "M월 d일 EEEE"), Locale.getDefault())) +
            if (logged) "" else tr(", nothing logged", ", 기록 없음"),
    )
}

private fun dayTitle(date: LocalDate): String = when (date) {
    LocalDate.now() -> tr("Today", "오늘")
    LocalDate.now().minusDays(1) -> tr("Yesterday", "어제")
    else -> date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
}

private fun kcalText(kcal: Double): String = "%,d".format(kcal.roundToInt())

/** Shown figures round to 10 kcal; projected ones carry "~". */
private fun tens(kcal: Double): String = "%,d".format((kcal / 10).roundToInt() * 10)

/** The day's kcal at 64, the comparison right under it, and where the figures came from. */
@Composable
private fun Hero(day: FoodDay, burned: Burned?, partly: Boolean, modifier: Modifier = Modifier) {
    val totals = day.totals
    val eaten = totals.totals.kcal ?: 0.0
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            // A changed total swaps in place, so old and new never read as two numbers.
            Text(totals.kcalRounded?.let { "%,d".format(it) } ?: "—", style = PawseType.Hero, modifier = Modifier.swapIn(rememberSwap(totals.kcalRounded)))
            Text("kcal", style = PawseType.Heading.copy(fontWeight = PawseType.Body.fontWeight), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, bottom = 10.dp))
        }
        if (partly) Text(tr("Partly logged", "일부만 기록함"), style = PawseType.BodyStrong)
        burned?.takeIf { !partly }?.let {
            val gap = it.soFar - eaten
            Text(
                if (gap >= 0) {
                    tr("${tens(gap)} under what you have burned so far", "지금까지 소모한 것보다 ${tens(gap)} 적게")
                } else {
                    tr("${tens(-gap)} over what you have burned so far", "지금까지 소모한 것보다 ${tens(-gap)} 많이")
                },
                style = PawseType.BodyStrong,
            )
        }
        Text(FoodCopy.totalsNote(totals), style = PawseType.Micro, color = PawseTheme.semantics.faint, modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * Eaten against burned on one bar: burned so far filled, burned by bedtime as the
 * band behind it, and a tick where eaten sits. Grey: a gap is not a target.
 */
@Composable
private fun EatenBurned(eaten: Double, burned: Burned, modifier: Modifier = Modifier) {
    val s = PawseTheme.semantics
    val on = MaterialTheme.colorScheme.onSurface
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val card = MaterialTheme.colorScheme.surfaceContainer
    val top = maxOf(burned.byBedtime ?: burned.soFar, eaten, 1.0) * 1.15
    PawseCard(modifier = modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(18.dp)
                .clearAndSetSemantics {
                    contentDescription = tr("Eaten ${tens(eaten)}, burned so far ${tens(burned.soFar)}", "섭취 ${tens(eaten)}, 지금까지 소모 ${tens(burned.soFar)}")
                },
        ) {
            val bar = 10.dp.toPx()
            val y = (size.height - bar) / 2f
            val r = CornerRadius(bar / 2f)
            fun w(v: Double) = (v / top).toFloat().coerceIn(0f, 1f) * size.width
            drawRoundRect(s.baselineOuter, Offset(0f, y), Size(size.width, bar), r)
            burned.byBedtime?.let { drawRoundRect(s.baselineBand, Offset(0f, y), Size(w(it), bar), r) }
            drawRoundRect(grey, Offset(0f, y), Size(w(burned.soFar), bar), r)
            val x = w(eaten)
            drawRoundRect(card, Offset(x - 2.5.dp.toPx(), 0f), Size(5.dp.toPx(), size.height), CornerRadius(2.dp.toPx()))
            drawRoundRect(on, Offset(x - 1.5.dp.toPx(), 1.dp.toPx()), Size(3.dp.toPx(), size.height - 2.dp.toPx()), CornerRadius(2.dp.toPx()))
        }
        LegendRow(Modifier.padding(top = 12.dp), key = { Box(Modifier.padding(horizontal = 2.5.dp).size(3.dp, 12.dp).background(on, RoundedCornerShape(2.dp))) }, label = tr("Eaten", "섭취")) {
            Text(kcalText(eaten), style = PawseType.MetricValue)
        }
        LegendRow(Modifier.padding(top = 4.dp), key = { Mark(grey) }, label = tr("Burned so far", "지금까지 소모")) {
            Text(tens(burned.soFar), style = PawseType.MetricValue)
        }
        burned.byBedtime?.let {
            LegendRow(Modifier.padding(top = 4.dp), key = { Mark(s.baselineBand) }, label = tr("Burned by bedtime", "취침까지 소모 예상")) {
                Text("~${tens(it)}", style = PawseType.Label, color = grey)
            }
        }
    }
}

@Composable
private fun LegendRow(modifier: Modifier, key: @Composable () -> Unit, label: String, value: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        key()
        Text(label, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp).weight(1f))
        value()
    }
}

/** Protein, carbs and fat with their 7-day averages, and the energy split in words. No dividers. */
@Composable
private fun Macros(n: Nutrients, week: List<FoodDay>, date: LocalDate, modifier: Modifier = Modifier) {
    val p = n.proteinGrams ?: 0.0
    val c = n.carbsGrams ?: 0.0
    val f = n.fatGrams ?: 0.0
    val energy = p * 4 + c * 4 + f * 9
    val logged = week.filter { it.entries.isNotEmpty() && it.date != date }
    fun avg(get: (Nutrients) -> Double?) = logged.mapNotNull { get(it.totals.totals) }.takeIf { it.isNotEmpty() }?.average()
    val on = MaterialTheme.colorScheme.onSurface
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val mark = PawseTheme.semantics.mark
    PawseCard(modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            listOf(
                Triple(tr("Protein", "단백질"), p, avg { it.proteinGrams }),
                Triple(tr("Carbs", "탄수화물"), c, avg { it.carbsGrams }),
                Triple(tr("Fat", "지방"), f, avg { it.fatGrams }),
            ).forEach { (name, grams, average) ->
                Column(Modifier.weight(1f)) {
                    Text("${grams.roundToInt()} g", style = PawseType.MetricValue)
                    Text(name, style = PawseType.Label, color = grey)
                    average?.let { Text(tr("avg ${it.roundToInt()}", "평균 ${it.roundToInt()}"), style = PawseType.Micro, color = PawseTheme.semantics.faint) }
                }
            }
        }
        if (energy > 0) {
            val ps = (p * 4 / energy * 100).roundToInt()
            val cs = (c * 4 / energy * 100).roundToInt()
            val fs = 100 - ps - cs
            Row(Modifier.padding(top = 16.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                listOf(ps to on, cs to grey, fs to mark).filter { it.first > 0 }.forEach { (share, color) ->
                    Box(Modifier.weight(share.toFloat()).height(6.dp).background(color))
                }
            }
            Text(
                tr("Protein $ps% · carbs $cs% · fat $fs% of energy", "열량 중 단백질 $ps% · 탄수화물 $cs% · 지방 $fs%"),
                style = PawseType.Micro,
                color = PawseTheme.semantics.faint,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun WriteBlockedCard(askedBefore: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PawseCard(modifier = modifier) {
        Text(tr("Not reaching Health Connect", "헬스 커넥트에 저장되지 않아요"), style = PawseType.Heading)
        Text(
            if (askedBefore) {
                tr("Still not allowed. Open Health Connect and let Pawse write nutrition.", "아직 허용되지 않았어요. 헬스 커넥트를 열고 Pawse의 영양 정보 기록을 허용하세요.")
            } else {
                tr("Health Connect hasn't allowed Pawse to save food.", "헬스 커넥트에서 Pawse의 음식 저장을 허용하지 않았어요.")
            },
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        SecondaryButton(tr("Allow it", "허용하기"), onClick = onClick, textStyle = PawseType.BodyStrong, modifier = Modifier.padding(top = 16.dp).fillMaxWidth())
    }
}

/** One tap logs the usual portion again: a 40-tall chip with its kcal in the faint tone. */
@Composable
private fun AgainChip(entry: FoodLogEntry, onClick: () -> Unit) {
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .pressable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = tr("Add ${entry.name} again, ${FoodCopy.energy(entry.nutrients)}", "${entry.name} 다시 추가, ${FoodCopy.energy(entry.nutrients)}")
                role = Role.Button
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .height(40.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
                .padding(start = 12.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(Glyphs.Plus, size = 16.dp, strokeWidth = 2f, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(6.dp))
            Text(entry.name, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            Text(FoodCopy.energy(entry.nutrients), style = PawseType.Label, color = PawseTheme.semantics.faint, maxLines = 1)
        }
    }
}

@Composable
private fun EmptyDay(isToday: Boolean, date: LocalDate, onManualEntry: () -> Unit, modifier: Modifier = Modifier) {
    PawseCard(modifier = modifier) {
        Text(
            if (isToday) tr("Nothing logged yet", "아직 기록이 없어요") else tr("Nothing logged on ${dayTitle(date)}", "${dayTitle(date)}에는 기록이 없어요"),
            style = PawseType.Heading,
        )
        Text(
            if (isToday) {
                tr(
                    "Scan a barcode, photograph a nutrition panel, or type it in. Everything you scan is saved on this phone, so the second time is instant.",
                    "바코드를 스캔하거나, 영양성분표를 찍거나, 직접 입력하세요. 스캔한 건 모두 이 휴대폰에 저장되니 두 번째부터는 바로 나와요.",
                )
            } else {
                tr("Gaps are fine. Pawse does not count streaks.", "빈 날도 괜찮아요. Pawse는 연속 기록을 세지 않아요.")
            },
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (isToday) SecondaryButton(tr("Add manually", "직접 추가"), onClick = onManualEntry, textStyle = PawseType.BodyStrong, modifier = Modifier.padding(top = 16.dp).fillMaxWidth())
    }
}

/** A row: name and kcal, then portion, source and protein. No row lines; it opens the edit sheet. */
@Composable
private fun EntryRow(entry: FoodLogEntry, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val meta = listOfNotNull(
        // Imported entries carry no portion size: 0 g means "not stated".
        FoodCopy.grams(entry.quantityGrams).takeIf { entry.quantityGrams > 0 },
        FoodCopy.source(entry.source),
        entry.nutrients.proteinGrams?.let { "P ${it.roundToInt()} g" },
    ).joinToString(" · ")
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .pressable(onClick = onClick)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .padding(vertical = 8.dp),
    ) {
        SplitRow {
            Text(entry.name, style = PawseType.BodyStrong, modifier = Modifier.weight(1f), maxLines = 1)
            Text(
                FoodCopy.energy(entry.nutrients),
                style = PawseType.Body,
                color = if (entry.nutrients.kcal == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(meta, style = PawseType.Micro, color = PawseTheme.semantics.faint)
    }
}

private val PORTIONS = listOf(0.5, 1.0, 1.5, 2.0)

/** Portion presets and grams, meal, then Save or Delete. Delete is grey and undoable, never red. */
@Composable
private fun EditSheet(entry: FoodLogEntry, onDismiss: () -> Unit, onSave: (Double, Meal) -> Unit, onDelete: () -> Unit) {
    var factor by remember { mutableDoubleStateOf(1.0) }
    var meal by remember { mutableStateOf(entry.meal) }
    val fromHc = entry.source == FoodSource.HEALTH_CONNECT
    PawseSheet(onDismiss = onDismiss) {
        Text(entry.name, style = PawseType.Heading)
        val per100 = entry.nutrients.kcal?.takeIf { entry.quantityGrams > 0 }?.let { it / entry.quantityGrams * 100 }
        Text(
            listOfNotNull(FoodCopy.source(entry.source), per100?.let { tr("per 100 g: ${it.roundToInt()} kcal", "100g당 ${it.roundToInt()}kcal") }).joinToString(" · "),
            style = PawseType.Micro,
            color = PawseTheme.semantics.faint,
        )
        if (fromHc) {
            Text(
                tr("Logged in another app. Edit or remove it there.", "다른 앱에서 기록했어요. 그 앱에서 수정하거나 삭제하세요."),
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
            )
            return@PawseSheet
        }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PORTIONS.forEach { p ->
                SelectChip(portionLabel(p), selected = abs(factor - p) < 1e-9, onClick = { factor = p })
            }
        }
        val grams = entry.quantityGrams * factor
        val kcal = entry.nutrients.kcal?.times(factor)
        Text(
            listOfNotNull(FoodCopy.grams(grams).takeIf { entry.quantityGrams > 0 }, kcal?.let { "${it.roundToInt()} kcal" }).joinToString(" · "),
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(tr("Meal", "식사"), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        ChoiceRow(Meal.entries, meal, onSelect = { meal = it }, label = FoodCopy::meal)
        PrimaryButton(
            if (kcal != null && (factor != 1.0 || meal != entry.meal)) tr("Save · ${kcal.roundToInt()} kcal", "저장 · ${kcal.roundToInt()}kcal") else tr("Save", "저장"),
            onClick = { onSave(factor, meal) },
            modifier = Modifier.padding(top = 24.dp),
        )
        GhostButton(tr("Delete", "삭제"), onClick = onDelete, glyph = Glyphs.Trash, modifier = Modifier.padding(top = 8.dp))
    }
}

private fun portionLabel(p: Double): String = when (p) {
    0.5 -> "½×"
    1.0 -> "1×"
    1.5 -> "1½×"
    else -> "${p.roundToInt()}×"
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
                "Sends the barcode to Open Food Facts and nothing else. Off by default. Reading the label with the camera works either way.",
                "Open Food Facts에 바코드만 보내요. 기본값은 꺼짐이에요. 카메라로 라벨을 읽는 기능은 어느 쪽이든 쓸 수 있어요.",
            ),
            checked = networkEnabled,
            onCheckedChange = onNetworkChange,
        )
        SettingRow(
            title = tr("Copy food into Health Connect", "헬스 커넥트에 음식 복사"),
            body = tr("Removing an entry here removes it there too.", "여기서 기록을 지우면 헬스 커넥트에서도 지워져요."),
            checked = mirroring,
            onCheckedChange = onMirroringChange,
        )
        Text(
            tr("Your health data never leaves this phone, with either switch on or off.", "어떤 스위치를 켜거나 꺼도 건강 데이터는 이 휴대폰을 떠나지 않아요."),
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
private fun SettingRow(title: String, body: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val s = PawseTheme.semantics
    Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(title, style = PawseType.Heading)
            Text(body, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.width(16.dp))
        PawseSwitch(checked = checked, onChange = onCheckedChange)
    }
}
