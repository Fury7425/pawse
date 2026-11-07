package app.pawse.feature.home

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import app.pawse.core.data.profile.FIRST_WEEK_DONE
import app.pawse.core.data.profile.FIRST_WEEK_NONE
import app.pawse.core.data.profile.FIRST_WEEK_SEEN
import app.pawse.core.ui.component.EaseInOut
import app.pawse.core.ui.component.LocalReduceMotion
import app.pawse.core.ui.component.REDUCED_FADE
import app.pawse.core.ui.component.lift
import app.pawse.core.ui.component.rememberSwap
import app.pawse.core.ui.component.swapIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pawse.core.data.activity.HeartHour
import app.pawse.core.data.activity.MovingSnapshot
import app.pawse.core.data.health.HealthConnectGateway
import app.pawse.core.data.scoring.ScoringSnapshot
import app.pawse.core.data.sync.SyncFailure
import app.pawse.core.data.sync.SyncStatus
import app.pawse.core.ui.component.BandDot
import app.pawse.core.ui.component.EaseOut
import app.pawse.core.ui.component.Glyph
import app.pawse.core.ui.component.Glyphs
import app.pawse.core.ui.component.PawseCard
import app.pawse.core.ui.component.PawseHaptic
import app.pawse.core.ui.component.PawseMotion
import app.pawse.core.ui.component.PrimaryButton
import app.pawse.core.ui.component.RangeTrack
import app.pawse.core.ui.component.RevealEnter
import app.pawse.core.ui.component.RevealExit
import app.pawse.core.ui.component.ScoreRing
import app.pawse.core.ui.component.SecondaryButton
import app.pawse.core.ui.component.SelectChip
import app.pawse.core.ui.component.StatusBarScrim
import app.pawse.core.ui.component.SplitRow
import app.pawse.core.ui.component.WeekDay
import app.pawse.core.ui.component.WeekStrip
import app.pawse.core.ui.component.aura
import app.pawse.core.ui.component.pressable
import app.pawse.core.ui.component.rememberHaptic
import app.pawse.core.ui.theme.PawseFontFamily
import app.pawse.core.ui.theme.PawseTheme
import app.pawse.core.ui.theme.PawseType
import app.pawse.scoring.activity.MovingReading
import app.pawse.scoring.activity.MovingWord
import app.pawse.scoring.attention.Attention
import app.pawse.scoring.attention.Watched
import app.pawse.scoring.attention.WatchedSeries
import app.pawse.scoring.attention.WorthKnowing
import app.pawse.scoring.config.ScoringConfig
import app.pawse.scoring.load.LoadPoints
import app.pawse.scoring.math.Stats
import app.pawse.scoring.model.BodyStatus
import app.pawse.scoring.model.DayReport
import app.pawse.scoring.model.DayTag
import app.pawse.scoring.model.EnergyForecast
import app.pawse.scoring.model.IllnessLevel
import app.pawse.scoring.model.LoadMethod
import app.pawse.scoring.model.ScoreType
import app.pawse.scoring.sleep.Tonight
import app.pawse.scoring.sleep.TonightPlan
import app.pawse.scoring.tr
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as DayStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Scores: the day as three rings under one sentence of guidance, then what is
 * worth knowing about it. One screen, one job: everything below the rings
 * explains them, and each ring opens its own page.
 *
 * Rules against score anxiety are layout rules here: every ring carries its word,
 * Body's number can be hidden, no single night is painted red, and the only red on
 * the screen is an illness alert.
 */
@Composable
fun HomeScreen(
    /** The tapped score and the picked day, as days before the newest. */
    onOpenExplain: (ScoreType, Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Moving today's cards: the Activity detail on the tapped figure. */
    onOpenActivity: (ActivityFigure) -> Unit = {},
    /** Profile's How Pawse calculates, a push of its own. */
    onOpenCalc: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val moving by viewModel.moving.collectAsStateWithLifecycle()
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
    val cardOrder by viewModel.cardOrder.collectAsStateWithLifecycle()
    val hiddenCards by viewModel.hiddenCards.collectAsStateWithLifecycle()
    val coachSeen by viewModel.coachSeen.collectAsStateWithLifecycle()
    val firstWeek by viewModel.firstWeek.collectAsStateWithLifecycle()
    val hiddenToday by viewModel.hiddenToday.collectAsStateWithLifecycle()
    var editingProfile by remember { mutableStateOf(false) }

    Scaffold(modifier = modifier, containerColor = MaterialTheme.colorScheme.background) { padding ->
        when (val current = state) {
            is HomeUiState.Loading -> Loading(Modifier.padding(padding))

            is HomeUiState.Failed -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(tr("Could not work out today", "오늘 값을 계산하지 못했어요"), style = PawseType.Title)
                Spacer(Modifier.height(8.dp))
                Text(current.message, style = PawseType.Body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                PrimaryButton(tr("Try again", "다시 시도"), onClick = viewModel::refresh)
            }

            is HomeUiState.Ready -> Scores(
                snapshot = current.snapshot,
                sync = current.sync,
                accessOff = current.accessOff,
                syncError = error,
                moving = moving,
                cards = cardOrder?.filter { it !in hiddenCards },
                contentPadding = padding,
                onOpenExplain = onOpenExplain,
                onOpenActivity = onOpenActivity,
                onSyncNow = viewModel::syncNow,
                onSettings = { editingProfile = true },
                onDismissError = viewModel::dismissError,
                onCheckIn = viewModel::checkIn,
                onTag = viewModel::toggleTag,
                coach = coachSeen == false,
                onCoachDone = viewModel::dismissCoach,
                firstWeek = firstWeek,
                onFirstWeek = viewModel::setFirstWeek,
                hidden = hiddenToday,
                onShow = { viewModel.setHiddenToday(false) },
            )
        }
    }

    ModelSheets()

    if (editingProfile) {
        val snapshot = (state as? HomeUiState.Ready)?.snapshot
        val reduceMotion by viewModel.reduceMotion.collectAsStateWithLifecycle()
        val shapes by viewModel.shapes.collectAsStateWithLifecycle()
        val notifications by viewModel.notifications.collectAsStateWithLifecycle()
        ProfileDialog(
            state = ProfileState(
                profile = snapshot?.profile,
                showNumbers = snapshot?.showNumbers ?: true,
                appearance = appearance,
                reduceMotion = reduceMotion,
                shapes = shapes,
                notifications = notifications,
                calibration = snapshot?.calibration,
                cardOrder = cardOrder,
                hiddenCards = hiddenCards,
                hiddenToday = hiddenToday,
            ),
            actions = ProfileActions(
                onShowNumbers = viewModel::setShowNumbers,
                onAppearance = viewModel::setAppearance,
                onReduceMotion = viewModel::setReduceMotion,
                onShapes = viewModel::setShapes,
                onNotify = viewModel::setNotify,
                onSaveProfile = { age, sex, maxHr ->
                    viewModel.saveProfile(age, sex, maxHr)
                    editingProfile = false
                },
                onResetModel = viewModel::resetModel,
                onCardOrder = viewModel::setCardOrder,
                onHidden = viewModel::setHidden,
                onHideToday = viewModel::setHiddenToday,
            ),
            onDismiss = { editingProfile = false },
            onOpenCalc = {
                editingProfile = false
                onOpenCalc()
            },
        )
    }
}

/**
 * First install only: Pawse is reading for the first time. Later opens show the last
 * numbers at once. Track-coloured blocks where the title and rings will be, breathing
 * between 55% and 90%; never a spinner, never a shimmer. Still at 70% under reduce motion.
 */
@Composable
private fun Loading(modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val breathe by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.55f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(1200, easing = EaseInOut), RepeatMode.Reverse),
        label = "breathe",
    )
    val block = PawseTheme.semantics.baselineOuter
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 24.dp, top = 16.dp, end = 24.dp)
            .graphicsLayer { alpha = if (reduce) 0.7f else breathe }
            .semantics { contentDescription = tr("Working out your night", "지난밤을 분석하는 중") },
    ) {
        Box(Modifier.fillMaxWidth(0.4f).height(24.dp).background(block, RoundedCornerShape(8.dp)))
        Box(Modifier.padding(top = 10.dp).fillMaxWidth(0.6f).height(12.dp).background(block, RoundedCornerShape(6.dp)))
        Row(
            Modifier.fillMaxWidth().padding(top = 96.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.Bottom,
        ) {
            listOf(84.dp, 112.dp, 84.dp).forEach { Box(Modifier.size(it).background(block, CircleShape)) }
        }
    }
}

/** The day whose rings already filled once, so a tab return lands at the value instead of refilling. */
private object FirstDraw {
    var seen: LocalDate? = null
}

private val RINGS = listOf(ScoreType.SLEEP, ScoreType.BODY, ScoreType.LOAD)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Scores(
    snapshot: ScoringSnapshot,
    sync: SyncStatus,
    accessOff: Boolean,
    syncError: String?,
    moving: MovingSnapshot?,
    /** The user's cards in order, or null for Automatic. */
    cards: List<ScoresCard>?,
    contentPadding: PaddingValues,
    onOpenExplain: (ScoreType, Int) -> Unit,
    onOpenActivity: (ActivityFigure) -> Unit,
    onSyncNow: () -> Unit,
    onSettings: () -> Unit,
    onDismissError: () -> Unit,
    onCheckIn: (Int) -> Unit,
    onTag: (DayTag) -> Unit,
    /** "Tap any ring to see why" is still owed. */
    coach: Boolean,
    onCoachDone: () -> Unit,
    /** The seventh night's stage, or null until read. */
    firstWeek: Int?,
    onFirstWeek: (Int) -> Unit,
    /** Hide scores today: the rings and the sentence give way to one line. */
    hidden: Boolean,
    onShow: () -> Unit,
) {
    val context = LocalContext.current
    // The picked day, as days before the newest. Today is 0.
    var back by rememberSaveable { mutableIntStateOf(0) }
    val date = snapshot.date.minusDays(back.toLong())
    val day = if (back == 0) snapshot.today else snapshot.history.lastOrNull { it.date == date }
    val isToday = back == 0
    val evening = isToday && LocalTime.now().hour >= EVENING_HOUR
    val alert = day?.illness?.level == IllnessLevel.ALERT
    // Hidden scores leave no hint of the band behind them either.
    val wash = day?.body?.takeIf { !(hidden && isToday) }?.let { bodyColor(it.band, day.illness.level) }
    val pad = Modifier.padding(horizontal = 24.dp)
    val pull = rememberPullToRefreshState()
    val running = sync is SyncStatus.Running
    val reduce = LocalReduceMotion.current
    val haptic = rememberHaptic()
    // Opened on old numbers while Health Connect is read: the rings wait at 45%.
    val stale = running && System.currentTimeMillis() - snapshot.lastSyncEpochMs > STALE_MS

    // Release now and it reads; backing off says it won't.
    LaunchedEffect(pull, running) {
        if (running) return@LaunchedEffect
        snapshotFlow { pull.distanceFraction >= 1f }.drop(1).collect { past ->
            haptic(if (past) PawseHaptic.Threshold else PawseHaptic.ThresholdOff)
        }
    }

    // The seventh night, once ever: a learning week seen here, then the night the score arrives.
    val learning = isToday && day?.bodyStatus == BodyStatus.WARMING_UP
    LaunchedEffect(learning, firstWeek) {
        if (learning && firstWeek == FIRST_WEEK_NONE) onFirstWeek(FIRST_WEEK_SEEN)
    }
    val celebrate = isToday && firstWeek == FIRST_WEEK_SEEN && day?.body != null
    // The rest of that morning says so.
    var normalSet by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(celebrate) { if (celebrate) normalSet = true }

    // Tap anywhere, or a ring, and the coachmark goes. The room made for it stays
    // for the visit, so the rings don't jump when it leaves.
    var coachOut by remember { mutableStateOf(false) }
    var coachRoom by remember { mutableStateOf(false) }
    LaunchedEffect(coach) { if (coach) coachRoom = true }
    val dismissCoach = {
        if (coach && !coachOut) {
            coachOut = true
            onCoachDone()
        }
    }

    // A thin line under the header stands in for the spinner; the pull is 56 dp.
    Box(Modifier.fillMaxSize().pullToRefresh(isRefreshing = running, state = pull, threshold = PULL_THRESHOLD, onRefresh = onSyncNow)) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .aura()
                .pointerInput(coach) { if (coach) awaitEachGesture { awaitFirstDown(requireUnconsumed = false); dismissCoach() } }
                // The content follows the finger at 0.6, then 0.2 past 100 dp. Still under reduce motion.
                .graphicsLayer {
                    if (reduce) return@graphicsLayer
                    val d = pull.distanceFraction * PULL_THRESHOLD.toPx()
                    val knee = 100.dp.toPx()
                    translationY = if (d <= knee) d * 0.6f else knee * 0.6f + (d - knee) * 0.2f
                },
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding() + 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 140.dp,
            ),
        ) {
            item {
                Column(
                    // Body's colour as a faint wash behind the rings, scrolled away with them.
                    Modifier.drawBehind {
                        if (wash != null) {
                            val c = Offset(size.width / 2f, size.height - 120.dp.toPx())
                            drawCircle(
                                Brush.radialGradient(listOf(wash.copy(alpha = 0.10f), Color.Transparent), c, 260.dp.toPx()),
                                radius = 260.dp.toPx(),
                                center = c,
                            )
                        }
                    },
                ) {
                    Header(
                        title = if (isToday) tr("Today", "오늘") else fullDay(date),
                        sync = statusLine(sync, snapshot.lastSyncEpochMs),
                        onProfile = onSettings,
                        modifier = pad,
                    )
                    PullLine(running = running, fraction = pull.distanceFraction)
                    WeekStrip(
                        days = weekOf(snapshot),
                        selected = 6 - back,
                        onPick = { back = 6 - it },
                        modifier = Modifier.padding(start = 24.dp, top = 4.dp, end = 24.dp),
                    )
                    val hiddenNow = hidden && isToday
                    if (day != null && !hiddenNow) {
                        // The most human line on the screen changes quietly, never by snapping.
                        val guidance = if (normalSet && isToday) ScoreCopy.NORMAL_SET else ScoreCopy.guidance(day.guidance)
                        val guideSwap = rememberSwap(guidance)
                        Text(
                            guidance,
                            style = PawseType.Guidance,
                            modifier = pad.padding(top = 24.dp).heightIn(min = 28.dp).swapIn(guideSwap),
                        )
                    }
                    // Hide scores today: one line until tomorrow morning; the cards below stay.
                    if (hiddenNow) {
                        PawseCard(modifier = pad.padding(top = 24.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    tr("Scores are hidden for today", "오늘은 점수를 숨겼어요"),
                                    style = PawseType.Label,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                SecondaryButton(tr("Show", "보기"), onClick = onShow, height = 40.dp, modifier = Modifier.padding(start = 8.dp).widthIn(min = 80.dp))
                            }
                        }
                    } else Rings(
                        day = day,
                        showNumbers = snapshot.showNumbers,
                        onOpen = {
                            dismissCoach()
                            onOpenExplain(it, back)
                        },
                        today = snapshot.date,
                        dim = stale,
                        coach = coach && !coachOut && isToday && day != null,
                        celebrate = celebrate,
                        onCelebrated = { onFirstWeek(FIRST_WEEK_DONE) },
                        // Room above for the coachmark, as the first week's layout has.
                        modifier = Modifier.padding(top = if (coachRoom && isToday) 72.dp else 24.dp),
                    )
                }
            }

            // Anything Pawse needs from you comes first, as a card with one action.
            if (isToday && alert) {
                item {
                    Banner(
                        title = tr("Overnight heart rate", "야간 심박수"),
                        body = listOfNotNull(ScoreCopy.ILLNESS_ALERT, ScoreCopy.likelyCauses(snapshot.today.illness.likelyCauses)).joinToString(" "),
                        modifier = pad.padding(top = 24.dp),
                        dot = PawseTheme.semantics.bandLow,
                    )
                }
            }
            if (isToday && accessOff) {
                item {
                    Banner(
                        title = tr("Pawse can’t read your data", "데이터를 읽을 수 없어요"),
                        body = tr(
                            "Health Connect access is off, so nothing new can come in. Pawse only reads, and nothing leaves this phone.",
                            "헬스 커넥트 접근이 꺼져 있어서 새 데이터가 들어오지 않아요. Pawse는 읽기만 하고, 어떤 데이터도 휴대폰 밖으로 나가지 않아요.",
                        ),
                        action = tr("Turn access back on", "접근 다시 켜기"),
                        onAction = { runCatching { context.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) } },
                        modifier = pad.padding(top = 24.dp),
                    )
                }
            }
            if (isToday && syncError != null) {
                // One cause, one button; grey, never red.
                item {
                    when ((sync as? SyncStatus.Failed)?.reason) {
                        SyncFailure.READ_FAILED -> Banner(
                            title = tr("Couldn’t read today’s data", "오늘 데이터를 읽지 못했어요"),
                            body = syncError,
                            action = tr("Try again", "다시 시도"),
                            onAction = onSyncNow,
                            modifier = pad.padding(top = 24.dp),
                        )
                        SyncFailure.NEEDS_UPDATE -> Banner(
                            title = tr("Health Connect needs an update", "헬스 커넥트 업데이트가 필요해요"),
                            body = syncError,
                            action = tr("Open the Play Store", "Play 스토어 열기"),
                            onAction = { openHealthConnectInStore(context) },
                            modifier = pad.padding(top = 24.dp),
                        )
                        else -> Banner(title = tr("Nothing new was read", "새로 읽은 게 없어요"), body = syncError, action = tr("Dismiss", "닫기"), onAction = onDismissError, modifier = pad.padding(top = 24.dp))
                    }
                }
            }

            when {
                day == null -> item { NoDay(date, pad.padding(top = 32.dp)) }

                !isToday -> item { ThatDay(day, snapshot.showNumbers, pad.padding(top = 32.dp)) }

                else -> {
                    if (day.bodyStatus == BodyStatus.WARMING_UP) {
                        item { Learning(day.validNights, ScoringConfig.DEFAULT.body.minValidNights, pad.padding(top = 32.dp)) }
                    }
                    val tonight = tonightPlan(snapshot)
                    val energy = snapshot.energy
                    (cards ?: ScoresCard.automatic(evening)).forEach { card ->
                        // Check-in and Moving today stand apart; the cards between sit closer.
                        val top = if (card == ScoresCard.MOVING || card == ScoresCard.CHECK_IN) 32.dp else 16.dp
                        when (card) {
                            ScoresCard.MOVING -> if (moving != null) item { MovingToday(moving, onOpenActivity, pad.padding(top = top)) }
                            ScoresCard.WORTH -> if (day.bodyStatus != BodyStatus.WARMING_UP) item { Box(pad.padding(top = top)) { WorthKnowingCard(snapshot) } }
                            ScoresCard.YOUR_DAY -> if (energy != null) item { Box(pad.padding(top = top)) { YourDay(energy) } }
                            ScoresCard.TONIGHT -> if (tonight != null) item { Box(pad.padding(top = top)) { TonightCard(tonight, snapshot) } }
                            ScoresCard.TODAYS_LOAD -> item { Box(pad.padding(top = top)) { TodaysLoad(day) } }
                            ScoresCard.CHECK_IN -> item { CheckIn(snapshot, onCheckIn, onTag, pad.padding(top = top)) }
                        }
                    }
                    item { MorningSummaryCard(pad.padding(top = 16.dp)) }
                    if (snapshot.today.sleep.suggestDoctor) {
                        item { Banner(title = tr("Worth a word with a doctor", "의사와 상담해 보세요"), body = ScoreCopy.DOCTOR, modifier = pad.padding(top = 24.dp)) }
                    }
                    if (snapshot.needsProfile) {
                        item {
                            Banner(
                                title = tr("Your maximum heart rate", "최대 심박수"),
                                body = tr(
                                    "Workouts are counted by effort until Pawse knows your maximum heart rate. Your age, or a measured maximum, lets it read your heart rate instead.",
                                    "Pawse가 최대 심박수를 알기 전까지는 운동을 체감 강도로 계산해요. 나이나 측정한 최대 심박수를 알려 주면 심박수로 계산할 수 있어요.",
                                ),
                                action = tr("Add it", "입력하기"),
                                onAction = onSettings,
                                modifier = pad.padding(top = 24.dp),
                            )
                        }
                    }
                }
            }
        }
        StatusBarScrim()
    }
}

private const val EVENING_HOUR = 18

/** Health Connect's own Play Store page, the way its docs open it for an update. */
private fun openHealthConnectInStore(context: android.content.Context) {
    val uri = "market://details?id=${HealthConnectGateway.HEALTH_CONNECT_PACKAGE}&url=healthconnect%3A%2F%2Fonboarding"
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri))
                .setPackage("com.android.vending")
                .putExtra("overlay", true)
                .putExtra("callerId", context.packageName),
        )
    }
}

/** Older than this, the numbers on screen wait dimmed while a read runs. */
private const val STALE_MS = 30 * 60 * 1000L

private val PULL_THRESHOLD = 56.dp

// ---------------------------------------------------------------------------
// Header, pull line and week
// ---------------------------------------------------------------------------

/** "Today" with the sync line under it, and the profile button. Customising lives behind it. */
@Composable
private fun Header(title: String, sync: String, onProfile: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = PawseType.Title, modifier = Modifier.swapIn(rememberSwap(title)))
            Text(
                sync,
                style = PawseType.Micro,
                color = PawseTheme.semantics.faint,
                modifier = Modifier
                    .swapIn(rememberSwap(sync))
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Box(
            Modifier
                .size(48.dp)
                .pressable(onClick = onProfile)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                .clearAndSetSemantics {
                    contentDescription = tr("Profile and settings", "프로필 및 설정")
                    role = Role.Button
                },
            contentAlignment = Alignment.Center,
        ) {
            Glyph(Glyphs.Person, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/**
 * The 2 dp line under the header: it follows a pull, then runs while Health
 * Connect is read. Never a spinner.
 */
@Composable
private fun PullLine(running: Boolean, fraction: Float) {
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val reduce = LocalReduceMotion.current
    val loop = rememberInfiniteTransition(label = "pull")
    // A 40% segment crosses on the in-out curve, 1400 ms a pass.
    val sweep by loop.animateFloat(
        initialValue = -0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = EaseInOut), RepeatMode.Restart),
        label = "sweep",
    )
    // Reduce motion: the whole line pulses its opacity instead.
    val pulse by loop.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    Canvas(Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(2.dp).clip(RoundedCornerShape(1.dp))) {
        when {
            running && reduce -> drawRect(ink.copy(alpha = pulse))
            running -> drawRect(ink, Offset(sweep * size.width, 0f), Size(size.width * 0.4f, size.height))
            fraction > 0f -> drawRect(ink, Offset.Zero, Size(size.width * fraction.coerceIn(0f, 1f), size.height))
        }
    }
}

private fun statusLine(sync: SyncStatus, lastSyncEpochMs: Long): String = when (sync) {
    is SyncStatus.Running -> tr("Checking Health Connect…", "헬스 커넥트 확인 중…")
    is SyncStatus.Failed -> when (sync.reason) {
        SyncFailure.READ_FAILED -> tr("Couldn’t reach Health Connect · ${ScoreCopy.clock(sync.atEpochMs)}", "헬스 커넥트에 연결하지 못함 · ${ScoreCopy.clock(sync.atEpochMs)}")
        SyncFailure.ACCESS_OFF -> tr("No access to Health Connect", "헬스 커넥트 접근 권한 없음")
        SyncFailure.UNAVAILABLE -> tr("Health Connect isn’t available", "헬스 커넥트를 사용할 수 없어요")
        SyncFailure.NEEDS_UPDATE -> tr("Health Connect is out of date", "헬스 커넥트가 오래된 버전이에요")
    }
    else -> if (lastSyncEpochMs > 0) {
        tr("Updated ${ScoreCopy.clock(lastSyncEpochMs)} from Health Connect", "헬스 커넥트에서 ${ScoreCopy.clock(lastSyncEpochMs)} 업데이트")
    } else {
        tr("Not read from Health Connect yet", "아직 헬스 커넥트에서 읽지 않았어요")
    }
}

/** The seven days ending today. The dot is that day's Body band; hollow means no score. */
@Composable
private fun weekOf(snapshot: ScoringSnapshot): List<WeekDay> {
    val byDate = snapshot.history.associateBy { it.date }
    return (6 downTo 0).map { back ->
        val d = snapshot.date.minusDays(back.toLong())
        val report = if (back == 0) snapshot.today else byDate[d]
        val body = report?.body
        val word = when {
            report == null -> tr("before Pawse", "Pawse 사용 전")
            body == null -> tr("no score", "점수 없음")
            else -> ScoreCopy.bandWord(body.band)
        }
        WeekDay(
            name = d.dayOfWeek.getDisplayName(DayStyle.SHORT, Locale.getDefault()),
            number = d.dayOfMonth.toString(),
            mark = body?.let { bodyColor(it.band, report.illness.level) },
            hollow = report != null && body == null,
            description = "${fullDay(d)}, $word",
            enabled = report != null,
        )
    }
}

private fun fullDay(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern(tr("EEEE d MMMM", "M월 d일 EEEE"), Locale.getDefault()))

// ---------------------------------------------------------------------------
// Rings
// ---------------------------------------------------------------------------

/** What one ring shows. A null [value] draws the score's glyph instead: Body with numbers hidden. */
private class RingFace(
    val fraction: Float?,
    val arc: Color,
    val glow: Boolean,
    val value: String?,
    val unit: String,
    val dot: Color,
    val hollow: Boolean,
    val word: String,
    val target: Float? = null,
    val range: ClosedFloatingPointRange<Float>? = null,
    /** The figure behind [value], so the first fill can count up to it. */
    val count: Double? = null,
    val decimals: Int = 0,
    /** First week: nights learned of seven, drawn as segments. */
    val segments: Int? = null,
)

/**
 * Body is its score on its band colour. Sleep is last night against your need,
 * the need a tick at the top; Load is today's points on a 16-point track with
 * today's range drawn on it. Both are real units, so both stay grey.
 */
@Composable
private fun ringFace(type: ScoreType, day: DayReport?, showNumbers: Boolean): RingFace {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val neutral = PawseTheme.semantics.neutral
    if (day == null) return RingFace(null, grey, false, "—", "", neutral, true, tr("No reading", "기록 없음"))
    return when (type) {
        ScoreType.BODY -> {
            val body = day.body
            val color = bodyColor(body?.band, day.illness.level)
            // The first week: seven segments fill a night at a time, and the numeral counts them.
            if (body == null && day.bodyStatus == BodyStatus.WARMING_UP) {
                val nights = day.validNights.coerceIn(0, 7)
                return RingFace(
                    fraction = null,
                    arc = grey,
                    glow = false,
                    value = "$nights/7",
                    unit = "",
                    dot = neutral,
                    hollow = true,
                    word = tr("Learning", "배우는 중"),
                    segments = nights,
                )
            }
            RingFace(
                fraction = body?.let { it.score / 100f },
                arc = color,
                glow = body != null,
                value = when {
                    body == null -> "—"
                    showNumbers -> body.score.toString()
                    else -> null
                },
                unit = "",
                dot = color,
                hollow = body == null,
                word = body?.let { ScoreCopy.bandWord(it.band) }
                    ?: if (day.bodyStatus == BodyStatus.NO_RECENT_NIGHT) tr("No reading", "기록 없음") else tr("Learning", "배우는 중"),
                count = body?.score?.toDouble()?.takeIf { showNumbers },
            )
        }

        ScoreType.SLEEP -> {
            val sleep = day.sleep
            val slept = sleep.lastNightMinutes
            val need = sleep.needHours * 60.0
            RingFace(
                fraction = slept?.let { (it / need).toFloat() },
                arc = grey,
                glow = false,
                value = slept?.let { String.format(Locale.getDefault(), "%.1f", it / 60.0) } ?: "—",
                unit = if (slept != null) tr("h", "시간") else "",
                dot = neutral,
                hollow = slept == null,
                word = when {
                    slept == null -> tr("No night", "기록 없음")
                    need - slept <= 3.0 -> tr("Enough", "충분")
                    need - slept <= 50.0 -> tr("Just short", "조금 부족")
                    else -> tr("Short", "부족")
                },
                target = if (slept != null) 1f else null,
                count = slept?.let { it / 60.0 },
                decimals = 1,
            )
        }

        ScoreType.LOAD -> {
            val load = day.load
            val range = load.pointsRange
            val points = ScoringConfig.DEFAULT.points
            val shown = load.points.roundToInt()
            RingFace(
                fraction = LoadPoints.ringFraction(load.points, points),
                arc = grey,
                glow = false,
                value = shown.toString(),
                unit = "",
                dot = neutral,
                hollow = false,
                word = when {
                    range == null -> tr("$shown · no range yet", "$shown · 아직 범위 없음")
                    range.rest -> tr("Rest · $shown of 0–${range.high}", "휴식 · 0–${range.high} 중 $shown")
                    else -> tr("$shown of ${range.low}–${range.high}", "${range.low}–${range.high} 중 $shown")
                },
                range = range?.let { it.low.toFloat() / points.ringPoints..it.high.toFloat() / points.ringPoints },
                count = shown.toDouble(),
            )
        }
    }
}

/** Sleep, Body a size up, and Load. Each opens its page; the chevron says so. */
@Composable
private fun Rings(
    day: DayReport?,
    showNumbers: Boolean,
    onOpen: (ScoreType) -> Unit,
    today: LocalDate,
    /** Old numbers waiting on a read: 45%, back to full in 200 ms. */
    dim: Boolean,
    coach: Boolean,
    /** The seventh night has landed and hasn't been shown. */
    celebrate: Boolean,
    onCelebrated: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The day's first fill counts up once; a tab return lands at the value.
    val skipFirstDraw = remember { FirstDraw.seen == today }
    SideEffect { FirstDraw.seen = today }
    val alpha by animateFloatAsState(if (dim) 0.45f else 1f, tween(200), label = "dim")
    // The coachmark waits for the rings to fill, then a 200 ms rest.
    var coachIn by remember { mutableStateOf(false) }
    LaunchedEffect(coach) {
        if (coach) {
            delay(if (skipFirstDraw) 200L else PawseMotion.FIRST_DRAW + 200L)
            coachIn = true
        } else {
            coachIn = false
        }
    }
    Box(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).graphicsLayer { this.alpha = alpha },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            RINGS.forEach { type ->
                val big = type == ScoreType.BODY
                RingTile(
                    type = type,
                    face = ringFace(type, day, showNumbers),
                    big = big,
                    onClick = { onOpen(type) },
                    skipFirstDraw = skipFirstDraw,
                    celebrate = big && celebrate,
                    onCelebrated = onCelebrated,
                    modifier = Modifier.weight(if (big) 1.4f else 1f),
                )
            }
        }
        Coachmark(coachIn, Modifier.align(Alignment.TopCenter).offset(y = (-58).dp))
    }
}

/** "Tap any ring to see why", in the selection fill, pointing down at the rings. Once ever. */
@Composable
private fun Coachmark(visible: Boolean, modifier: Modifier = Modifier) {
    val s = PawseTheme.semantics
    val reduce = LocalReduceMotion.current
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (reduce) {
            fadeIn(tween(REDUCED_FADE))
        } else {
            fadeIn(tween(200, easing = EaseOut)) +
                scaleIn(tween(200, easing = EaseOut), initialScale = 0.96f, transformOrigin = TransformOrigin(0.5f, 1f))
        },
        exit = fadeOut(tween(150, easing = EaseOut)),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(
                tr("Tap any ring to see why", "링을 누르면 이유가 보여요"),
                style = PawseType.Label.copy(fontWeight = FontWeight.Medium),
                color = s.onSelection,
                modifier = Modifier
                    .lift(RoundedCornerShape(12.dp))
                    .background(s.selection, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
            Canvas(Modifier.size(12.dp, 6.dp)) {
                drawPath(Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2f, size.height); close() }, s.selection)
            }
        }
    }
}

@Composable
private fun RingTile(
    type: ScoreType,
    face: RingFace,
    big: Boolean,
    onClick: () -> Unit,
    skipFirstDraw: Boolean,
    celebrate: Boolean,
    onCelebrated: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduce = LocalReduceMotion.current
    val haptic = rememberHaptic()
    // The numeral counts up with the first fill, reading the same 700 ms curve as the arc.
    val count = remember { Animatable(if (skipFirstDraw || reduce) 1f else 0f) }
    LaunchedEffect(Unit) { count.animateTo(1f, tween(PawseMotion.FIRST_DRAW, easing = EaseOut)) }
    // The seventh night: the segments grow into one ring (600 ms), then the score fades in over it (300 ms).
    val join = remember { Animatable(0f) }
    val reveal = remember { Animatable(0f) }
    var joined by remember { mutableStateOf(false) }
    LaunchedEffect(celebrate) {
        if (!celebrate) return@LaunchedEffect
        if (!reduce) join.animateTo(1f, tween(600, easing = EaseInOut))
        haptic(PawseHaptic.Confirm)
        reveal.animateTo(1f, tween(if (reduce) 200 else 300))
        joined = true
        onCelebrated()
    }
    val value = when {
        celebrate && !joined -> "7/7"
        face.count != null && count.value < 1f -> {
            val v = face.count * count.value
            if (face.decimals == 0) v.roundToInt().toString() else String.format(Locale.getDefault(), "%.${face.decimals}f", v)
        }
        else -> face.value
    }
    val shown = face.value?.let { " $it${face.unit}" } ?: ""
    val description = "${ScoreCopy.name(type)}$shown, ${face.word}. " + tr("Opens details.", "자세히 보기.")
    // Where this ring sits, for the flight into the detail. Not state: nothing redraws from it.
    val bounds = remember { arrayOfNulls<androidx.compose.ui.geometry.Rect>(1) }
    // Keyed on the settled figure, never the count-up, so only a real change swaps.
    val numeralSwap = rememberSwap(face.value to (celebrate && !joined))
    val wordSwap = rememberSwap(face.word)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .pressable {
                    RingFlight.origin = bounds[0]
                    RingFlight.type = type
                    onClick()
                }
                .clearAndSetSemantics {
                    contentDescription = description
                    role = Role.Button
                }
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ScoreRing(
                fraction = face.fraction,
                color = face.arc,
                size = if (big) 112.dp else 84.dp,
                // The glow is dark theme's only: light and bedside have no aura, so no glow either.
                glow = face.glow && PawseTheme.semantics.aura.alpha > 0f,
                target = face.target,
                range = face.range,
                stroke = 7.2f,
                segments = if (celebrate && !joined) 7 else face.segments,
                join = join.value,
                reveal = reveal.value,
                skipFirstDraw = skipFirstDraw,
                modifier = Modifier.flightSource { bounds[0] = it },
            ) {
                if (value == null) {
                    Glyph(scoreGlyph(type), size = 28.dp, color = PawseTheme.semantics.mark)
                } else {
                    Row(Modifier.swapIn(numeralSwap), verticalAlignment = Alignment.Bottom) {
                        // The first week's "4/7" sits a size down so it never reads as a score.
                        Text(value, style = if (big && face.segments == null && !celebrate) PawseType.Numeral else PawseType.RingSmall)
                        if (face.unit.isNotEmpty()) {
                            Text(face.unit, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 1.dp, bottom = if (big) 8.dp else 3.dp))
                        }
                    }
                }
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(ScoreCopy.name(type), style = PawseType.BodyStrong)
                Spacer(Modifier.width(2.dp))
                Glyph(Glyphs.ChevronRight, size = 16.dp, color = PawseTheme.semantics.faint)
            }
        }
        BandDot(color = face.dot, label = face.word, hollow = face.hollow, modifier = Modifier.swapIn(wordSwap))
    }
}

// ---------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------

/** A card that needs something from you: a title, a sentence, at most one action. */
@Composable
private fun Banner(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
    dot: Color? = null,
) {
    PawseCard(modifier = modifier) {
        if (dot != null) BandDot(color = dot, label = title) else Text(title, style = PawseType.Heading)
        Text(body, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        if (action != null) SecondaryButton(action, onClick = onAction, textStyle = PawseType.BodyStrong, modifier = Modifier.padding(top = 16.dp).fillMaxWidth())
    }
}

/** The first week: seven segments, one per night, and how far Body has got. */
@Composable
private fun Learning(nights: Int, needed: Int, modifier: Modifier = Modifier) {
    PawseCard(modifier = modifier) {
        Text(tr("Learning your normal", "평소 상태를 알아가는 중"), style = PawseType.Heading)
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val done = MaterialTheme.colorScheme.onSurfaceVariant
            val left = PawseTheme.semantics.baselineBand
            repeat(needed) { i ->
                Box(Modifier.weight(1f).height(6.dp).drawBehind { drawRoundRect(if (i < nights) done else left, cornerRadius = CornerRadius(3.dp.toPx())) })
            }
        }
        val leftNights = (needed - nights).coerceAtLeast(0)
        Text(
            tr(
                "Body needs $needed nights with your watch on. $leftNights to go. Its first scores are marked lower confidence and firm up over the next few weeks.",
                "컨디션 점수에는 워치를 찬 밤 ${needed}일이 필요해요. ${leftNights}일 남았어요. 첫 점수는 신뢰도 낮음으로 표시되고 몇 주에 걸쳐 정확해져요.",
            ),
            style = PawseType.Label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** No report for a picked day: Pawse had no reading for it. */
@Composable
private fun NoDay(date: LocalDate, modifier: Modifier = Modifier) {
    PawseCard(modifier = modifier) {
        Text(fullDay(date), style = PawseType.Heading)
        Text(tr("No reading for this day.", "이날은 기록이 없어요."), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
}

/** A past day picked on the strip: its three figures in a row each. */
@Composable
private fun ThatDay(day: DayReport, showNumbers: Boolean, modifier: Modifier = Modifier) {
    PawseCard(modifier = modifier) {
        SplitRow {
            Text(tr("That day", "그날"), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(fullDay(day.date), style = PawseType.Micro, color = PawseTheme.semantics.faint)
        }
        val slept = day.sleep.lastNightMinutes
        FigureRow(tr("Sleep", "수면"), Modifier.padding(top = 12.dp)) {
            Text(slept?.let { String.format(Locale.getDefault(), "%.1f h", it / 60.0) } ?: "—", style = PawseType.MetricValue)
        }
        FigureRow(tr("Body", "컨디션"), Modifier.padding(top = 8.dp)) {
            val body = day.body
            BandDot(
                color = bodyColor(body?.band, day.illness.level),
                label = body?.let { (if (showNumbers) "${it.score} · " else "") + ScoreCopy.bandWord(it.band) } ?: tr("No score", "점수 없음"),
                hollow = body == null,
            )
        }
        FigureRow(tr("Load", "부하"), Modifier.padding(top = 8.dp)) {
            Text(pointsOf(day), style = PawseType.MetricValue)
        }
    }
}

@Composable
private fun FigureRow(label: String, modifier: Modifier = Modifier, value: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = PawseType.BodyStrong, modifier = Modifier.weight(1f))
        value()
    }
}

private fun pointsOf(day: DayReport): String {
    val shown = day.load.points.roundToInt()
    val r = day.load.pointsRange ?: return shown.toString()
    return tr("$shown of ${r.low}–${r.high}", "${r.low}–${r.high} 중 $shown")
}

// ---------------------------------------------------------------------------
// Moving today
// ---------------------------------------------------------------------------

/**
 * Steps and active energy against your usual by this time since waking, and heart
 * rate since you woke. Health Connect's own totals, read when Scores opens; Pawse
 * keeps none of it. No goal, no score, grey only.
 */
@Composable
private fun MovingToday(m: MovingSnapshot, onOpen: (ActivityFigure) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        SplitRow {
            Text(tr("Moving today", "오늘의 움직임"), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(
                tr("Midnight to ${ScoreCopy.clock(m.asOfEpochMs)}", "자정부터 ${ScoreCopy.clock(m.asOfEpochMs)}까지"),
                style = PawseType.Micro,
                color = PawseTheme.semantics.faint,
            )
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Each card opens the Activity detail on its own figure.
            MovingCard(tr("Steps", "걸음 수"), m.steps, unit = "", per = "", onClick = { onOpen(ActivityFigure.STEPS) }, modifier = Modifier.weight(1f))
            MovingCard(tr("Active energy", "활동 에너지"), m.energy, unit = "kcal", per = " kcal", onClick = { onOpen(ActivityFigure.ENERGY) }, modifier = Modifier.weight(1f))
        }
        if (m.heart.isNotEmpty()) HeartToday(m, onClick = { onOpen(ActivityFigure.HEART) }, modifier = Modifier.padding(top = 12.dp))
        Text(
            tr("Health Connect’s own totals, read when Scores opens. Pawse keeps none of it.", "헬스 커넥트의 합계를 점수 화면을 열 때 읽어요. Pawse는 저장하지 않아요."),
            style = PawseType.Micro,
            color = PawseTheme.semantics.faint,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun MovingCard(label: String, r: MovingReading, unit: String, per: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PawseCard(modifier = modifier, onClick = onClick, padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Glyph(Glyphs.ChevronRight, size = 14.dp, color = PawseTheme.semantics.faint)
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text(String.format(Locale.getDefault(), "%,d", r.today.roundToInt()), style = MovingNumber)
            if (unit.isNotEmpty()) Text(unit, style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp, bottom = 5.dp))
        }
        BandDot(color = PawseTheme.semantics.neutral, label = movingWord(r.word))
        val low = r.low
        val high = r.high
        val top = (high ?: r.today).coerceAtLeast(r.today / 1.25).coerceAtLeast(1.0) * 1.25
        RangeTrack(
            band = if (low != null && high != null) (low / top).toFloat()..(high / top).toFloat() else null,
            fill = (r.today / top).toFloat(),
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
        Text(
            if (low != null && high != null) {
                val a = String.format(Locale.getDefault(), "%,d", low.roundToInt())
                val b = String.format(Locale.getDefault(), "%,d", high.roundToInt())
                tr("Usually $a–$b$per by now", "이 시간 평소 $a–$b$per")
            } else {
                tr("${r.days} days so far", "지금까지 ${r.days}일")
            },
            style = PawseType.Micro,
            color = PawseTheme.semantics.faint,
        )
    }
}

private val MovingNumber = PawseType.Numeral.copy(fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.7).sp)

internal fun movingWord(w: MovingWord): String = when (w) {
    MovingWord.BELOW -> tr("Below usual by now", "이 시간 평소보다 적음")
    MovingWord.ABOUT -> tr("About usual by now", "이 시간 평소 수준")
    MovingWord.ABOVE -> tr("Above usual by now", "이 시간 평소보다 많음")
    MovingWord.LEARNING -> tr("Learning your usual", "평소를 배우는 중")
}

/** Heart rate since waking, hour by hour: lowest to highest as a bar, the mean as a dot. Shown, never scored. */
@Composable
private fun HeartToday(m: MovingSnapshot, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val zone = java.time.ZoneId.systemDefault()
    val wake = java.time.Instant.ofEpochMilli(m.wakeEpochMs).atZone(zone).toLocalTime()
    val awake = m.heart.filter { it.hour >= wake.hour }
    val shown = awake.ifEmpty { m.heart }
    val mean = shown.map { it.mean.toDouble() }.average().roundToInt()
    val low = shown.minOf { it.low }
    val peak = shown.maxBy { it.high }
    val now = m.heart.last()
    PawseCard(modifier = modifier, onClick = onClick) {
        SplitRow {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Heart rate today", "오늘의 심박수"), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Glyph(Glyphs.ChevronRight, size = 14.dp, color = PawseTheme.semantics.faint)
            }
            if (m.wakeKnown) {
                Text(tr("since you woke at ${ScoreCopy.clock(m.wakeEpochMs)}", "${ScoreCopy.clock(m.wakeEpochMs)} 기상 이후"), style = PawseType.Micro, color = PawseTheme.semantics.faint)
            }
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$mean", style = MovingNumber)
            Text(tr("bpm average", "bpm 평균"), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        }
        Text(
            tr("Now ${now.mean} · lowest $low · highest ${peak.high} at ${"%02d".format(peak.hour)}:00", "지금 ${now.mean} · 최저 $low · 최고 ${peak.high} (${peak.hour}시)"),
            style = PawseType.Micro,
            color = PawseTheme.semantics.faint,
        )
        HeartChart(m.heart, wake.hour + wake.minute / 60f, Modifier.padding(top = 12.dp).fillMaxWidth().height(104.dp))
        Text(
            tr("Shown, never scored. Workouts count under Load.", "보여주기만 하고 점수에는 넣지 않아요. 운동은 부하에 반영돼요."),
            style = PawseType.Micro,
            color = PawseTheme.semantics.faint,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun HeartChart(hours: List<HeartHour>, wakeHour: Float, modifier: Modifier = Modifier) {
    val s = PawseTheme.semantics
    val track = s.baselineOuter
    val band = s.baselineBand
    val bars = s.mark
    val dot = MaterialTheme.colorScheme.onSurface
    val faint = s.faint
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val axis = TextStyle(fontFamily = PawseFontFamily, fontSize = 11.sp, color = faint)
    val asleep = tr("Asleep", "수면")
    val nowHour = LocalTime.now().let { it.hour + it.minute / 60f }
    Canvas(
        modifier.semantics {
            contentDescription = tr("Heart rate by hour since waking.", "기상 이후 시간별 심박수.")
        },
    ) {
        val chartW = size.width * 282f / 310f
        val chartH = 84.dp.toPx()
        fun x(h: Float) = h / 24f * chartW
        fun y(bpm: Long) = chartH - (bpm - 40f) / 140f * chartH
        drawRoundRect(track, Offset.Zero, Size(chartW, chartH), CornerRadius(6.dp.toPx()))
        drawRoundRect(band, Offset.Zero, Size(x(wakeHour), chartH), CornerRadius(6.dp.toPx()))
        if (wakeHour >= 3f) {
            val t = measurer.measure(asleep, axis.copy(color = grey))
            drawText(t, topLeft = Offset(x(wakeHour) / 2f - t.size.width / 2f, chartH / 2f - t.size.height / 2f))
        }
        hours.forEach { h ->
            val cx = x(h.hour + 0.5f)
            drawLine(bars, Offset(cx, y(h.high)), Offset(cx, y(h.low)), 6.dp.toPx(), StrokeCap.Round)
            drawCircle(dot, 2.5.dp.toPx(), Offset(cx, y(h.mean)))
        }
        val nx = x(nowHour)
        drawLine(grey, Offset(nx, 0f), Offset(nx, chartH), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
        axisLabel(measurer, "160", axis, Offset(chartW + 6.dp.toPx(), y(160) - 6.dp.toPx()))
        axisLabel(measurer, "80", axis, Offset(chartW + 6.dp.toPx(), y(80) - 6.dp.toPx()))
        listOf(0, 6, 12, 18, 24).forEach { h ->
            val t = measurer.measure("%02d".format(h), axis)
            val left = when (h) {
                0 -> 0f
                24 -> x(24f) - t.size.width
                else -> x(h.toFloat()) - t.size.width / 2f
            }
            drawText(t, topLeft = Offset(left, chartH + 4.dp.toPx()))
        }
    }
}

private fun DrawScope.axisLabel(measurer: androidx.compose.ui.text.TextMeasurer, text: String, style: TextStyle, at: Offset) {
    drawText(measurer.measure(text, style), topLeft = at)
}

// ---------------------------------------------------------------------------
// Worth knowing and your day (mornings)
// ---------------------------------------------------------------------------

private class WatchedRow(val label: String, val unit: String, val higherIsBetter: Boolean?, val decimals: Int)

private val WATCHED = mapOf(
    Watched.HRV to WatchedRow("hrv", "ms", true, 0),
    Watched.SLEEPING_HR to WatchedRow("shr", "bpm", false, 0),
    Watched.BREATHING to WatchedRow("br", "/min", null, 1),
    Watched.TEMPERATURE to WatchedRow("temp", "°C", null, 2),
    Watched.SPO2 to WatchedRow("spo2", "%", null, 0),
)

private fun watchedName(w: Watched): String = when (w) {
    Watched.HRV -> tr("Heart rate variability", "심박변이도")
    Watched.SLEEPING_HR -> tr("Sleeping heart rate", "수면 중 심박수")
    Watched.BREATHING -> tr("Breathing rate", "호흡수")
    Watched.TEMPERATURE -> tr("Skin temperature", "피부 온도")
    Watched.SPO2 -> tr("Blood oxygen", "혈중 산소")
}

/** This morning's signals against the 60 nights ending a week ago, the window Body's baseline uses. */
private fun watchedSeries(snapshot: ScoringSnapshot): List<WatchedSeries> {
    val d = snapshot.date
    fun value(w: Watched, date: LocalDate): Double? {
        val n = snapshot.nights[date]?.signals ?: return null
        return when (w) {
            Watched.HRV -> n.lnHrv?.value?.let(::exp)
            Watched.SLEEPING_HR -> n.sleepingHr?.value
            Watched.BREATHING -> n.breathingRate
            Watched.TEMPERATURE -> n.skinTempDelta
            Watched.SPO2 -> n.spo2
        }
    }
    val window = (7L..66L).map { d.minusDays(it) }
    return Watched.entries.map { w -> WatchedSeries(w, value(w, d), value(w, d.minusDays(1)), window.mapNotNull { value(w, it) }) }
}

/**
 * Only what sits well outside your usual: HRV and sleeping heart rate past 1.5
 * robust SDs (or 1 two mornings running), unscored vitals past 2. At most two.
 */
@Composable
private fun WorthKnowingCard(snapshot: ScoringSnapshot) {
    val series = watchedSeries(snapshot)
    val items = Attention.worthKnowing(series, ScoringConfig.DEFAULT.attention)
    val measured = series.count { it.today != null && it.usualNights.size >= 2 }
    PawseCard {
        SplitRow {
            Text(tr("Worth knowing", "알아둘 점"), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (measured > 0) {
                Text(tr("${items.size} of $measured signals", "신호 ${measured}개 중 ${items.size}개"), style = PawseType.Label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (items.isEmpty()) {
            Text(tr("All within your usual.", "모두 평소 범위예요."), style = PawseType.BodyStrong, modifier = Modifier.padding(top = 12.dp))
        }
        items.forEach { WorthRow(it, series.first { s -> s.signal == it.signal }.usualNights.size) }
        // The scored signal that stayed inside its usual still gets its sentence.
        val quiet = listOf(Watched.HRV, Watched.SLEEPING_HR).filter { w -> items.none { it.signal == w } && series.first { it.signal == w }.today != null }
        if (items.isNotEmpty() && quiet.isNotEmpty()) {
            Text(
                quiet.joinToString(" ") { tr("${watchedName(it)} is in your usual range.", "${watchedName(it)}는 평소 범위예요.") },
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun WorthRow(w: WorthKnowing, nights: Int) {
    val row = WATCHED.getValue(w.signal)
    val s = PawseTheme.semantics
    val better = row.higherIsBetter?.let { if (it) w.z > 0 else w.z < 0 }
    val color = when {
        better == null || abs(w.z) <= 1.0 -> s.neutral
        better -> s.bandHigh
        else -> s.bandModerate
    }
    val pct = if (w.median != 0.0) ((w.value - w.median) / abs(w.median) * 100).roundToInt() else 0
    fun fmt(v: Double) = String.format(Locale.getDefault(), "%.${row.decimals}f", v)
    SplitRow(Modifier.padding(top = 12.dp)) {
        Text(watchedName(w.signal), style = PawseType.BodyStrong, modifier = Modifier.weight(1f))
        Text("${fmt(w.value)} ${row.unit}", style = PawseType.MetricValue)
        if (row.unit != "°C") Text((if (pct >= 0) "+" else "−") + "${abs(pct)}%", style = PawseType.Delta, color = color)
    }
    RangeTrack(
        band = (1f / 3f)..(2f / 3f),
        marker = ((w.z.coerceIn(-3.0, 3.0) + 3.0) / 6.0).toFloat(),
        markerColor = color,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
    Text(
        tr("Usually ${fmt(w.median)} ± ${fmt(w.spread)} ${row.unit} · $nights nights", "평소 ${fmt(w.median)} ± ${fmt(w.spread)} ${row.unit} · ${nights}일 밤"),
        style = PawseType.Micro,
        color = PawseTheme.semantics.faint,
    )
}

/**
 * The energy forecast: solid up to now, dashed after, with the focus window, the
 * dip and bed labelled inside the chart.
 */
@Composable
private fun YourDay(energy: EnergyForecast) {
    val s = PawseTheme.semantics
    val on = MaterialTheme.colorScheme.onSurface
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val stroke = MaterialTheme.colorScheme.outline
    val card = MaterialTheme.colorScheme.surfaceContainer
    val measurer = rememberTextMeasurer()
    val label = TextStyle(fontFamily = PawseFontFamily, fontSize = 12.sp, color = grey, textAlign = TextAlign.Center)
    val axis = TextStyle(fontFamily = PawseFontFamily, fontSize = 12.sp, color = s.faint)
    val now = LocalTime.now().let { it.hour * 60 + it.minute }
    val words = Triple(tr("Focus", "집중"), tr("Dip", "처짐"), tr("Bed", "취침"))
    PawseCard {
        SplitRow {
            Text(tr("Your day", "오늘의 흐름"), style = PawseType.Label, color = grey, modifier = Modifier.weight(1f))
            Text(tr("forecast", "예측"), style = PawseType.Micro, color = s.faint)
        }
        val points = energy.points
        Canvas(
            Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
                .height(124.dp)
                .semantics {
                    contentDescription = tr(
                        "Energy forecast. Best focus ${ScoreCopy.minuteOfDay(energy.focusStart)} to ${ScoreCopy.minuteOfDay(energy.focusEnd)}, dip around ${ScoreCopy.minuteOfDay(energy.dipMinute)}, bed ${ScoreCopy.minuteOfDay(energy.bedtimeMinute)}.",
                        "에너지 예측. ${ScoreCopy.minuteOfDay(energy.focusStart)}부터 ${ScoreCopy.minuteOfDay(energy.focusEnd)}까지 집중하기 좋고, ${ScoreCopy.minuteOfDay(energy.dipMinute)}쯤 처지며, 취침 ${ScoreCopy.minuteOfDay(energy.bedtimeMinute)}.",
                    )
                },
        ) {
            if (points.isEmpty()) return@Canvas
            val top = 18.dp.toPx()
            val bottom = 80.dp.toPx()
            val from = 360f
            val to = 1560f
            fun x(m: Int) = ((m - from) / (to - from)).coerceIn(0f, 1f) * size.width
            val lo = points.minOf { it.alertness }
            val span = (points.maxOf { it.alertness } - lo).takeIf { it > 1e-9 } ?: 1.0
            fun y(a: Double) = bottom - 4.dp.toPx() - ((a - lo) / span).toFloat() * (bottom - top - 8.dp.toPx())
            drawRoundRect(s.baselineOuter, Offset(x(energy.focusStart), top), Size(x(energy.focusEnd) - x(energy.focusStart), bottom - top), CornerRadius(6.dp.toPx()))
            val bed = x(energy.bedtimeMinute)
            drawLine(stroke, Offset(bed, top), Offset(bed, bottom), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx())))
            listOf(words.first to (energy.focusStart + energy.focusEnd) / 2, words.second to energy.dipMinute, words.third to energy.bedtimeMinute).forEach { (w, m) ->
                val t = measurer.measure(w, label)
                drawText(t, topLeft = Offset((x(m) - t.size.width / 2f).coerceIn(0f, size.width - t.size.width), 0f))
            }
            val path = Path()
            points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.minute), y(p.alertness)) else path.lineTo(x(p.minute), y(p.alertness)) }
            val nx = x(now)
            clipRect(left = nx, right = size.width) {
                drawPath(path, grey, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()))))
            }
            clipRect(right = nx) { drawPath(path, on, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round)) }
            drawLine(on, Offset(nx, top), Offset(nx, bottom), 1.5.dp.toPx())
            points.minByOrNull { abs(it.minute - now) }?.let {
                drawCircle(card, 6.dp.toPx(), Offset(nx, y(it.alertness)))
                drawCircle(on, 4.dp.toPx(), Offset(nx, y(it.alertness)))
            }
            listOf(360, 720, 1080, 1440).forEach { m ->
                val t = measurer.measure("%02d".format((m / 60) % 24).let { if (m == 1440) "24" else it }, axis)
                drawText(t, topLeft = Offset(x(m).coerceAtMost(size.width - t.size.width), size.height - t.size.height))
            }
        }
        Text(tr("Solid is so far, dashed is the forecast.", "실선은 지금까지, 점선은 예측이에요."), style = PawseType.Micro, color = s.faint, modifier = Modifier.padding(top = 8.dp))
    }
}

// ---------------------------------------------------------------------------
// Tonight and today's load (evenings)
// ---------------------------------------------------------------------------

/**
 * Tonight's need from v2's base need plus today's load and illness, and the
 * bedtime and wind-down it sets from your usual wake and time to fall asleep.
 */
private fun tonightPlan(snapshot: ScoringSnapshot): TonightPlan? {
    val today = snapshot.today
    val recent = snapshot.nights.toSortedMap().values.toList().takeLast(14)
    val wakes = recent.mapNotNull { it.signals.sleep?.wakeMinute }
    val wake = Stats.circularMeanMinutes(wakes) ?: return null
    val config = ScoringConfig.DEFAULT.tonight
    val typical = Stats.median(snapshot.history.takeLast(config.typicalPointsDays).map { it.load.points }) ?: 0.0
    return Tonight.plan(
        baseNeedMinutes = today.sleep.needHours * 60.0,
        todayPoints = today.load.points,
        typicalPoints = typical,
        illness = today.illness.level != IllnessLevel.NONE,
        napMinutes = snapshot.nights[snapshot.date]?.sleep?.napMinutes?.toDouble() ?: 0.0,
        usualWakeMinute = wake,
        latencyMinutes = Tonight.latency(recent.mapNotNull { it.sleep.latencyMinutes }, config),
        config = config,
    )
}

@Composable
private fun TonightCard(plan: TonightPlan, snapshot: ScoringSnapshot) {
    val s = PawseTheme.semantics
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val base = snapshot.today.sleep.needHours * 60.0
    val bed = plan.bedMinute.roundToInt()
    val wake = (plan.bedMinute + plan.needMinutes).roundToInt()
    PawseCard {
        SplitRow {
            Text(tr("Tonight", "오늘 밤"), style = PawseType.Label, color = grey, modifier = Modifier.weight(1f))
            Text(tr("need ${ScoreCopy.formatMinutes(plan.needMinutes)}", "필요 ${ScoreCopy.formatMinutes(plan.needMinutes)}"), style = PawseType.Label, color = grey)
        }
        SplitRow(Modifier.padding(top = 4.dp)) {
            Text(ScoreCopy.minuteOfDay(bed), style = PawseType.Numeral, modifier = Modifier.weight(1f))
            Text(tr("wind down ${ScoreCopy.minuteOfDay(plan.windDownMinute.roundToInt())}", "${ScoreCopy.minuteOfDay(plan.windDownMinute.roundToInt())}부터 쉬기"), style = PawseType.Label, color = grey, modifier = Modifier.padding(bottom = 6.dp))
        }
        val now = LocalTime.now().let { it.hour * 60 + it.minute }
        val span = ((wake - now + 1440) % 1440).coerceAtLeast(1)
        val bedAt = (((bed - now + 1440) % 1440).toFloat() / span).coerceIn(0f, 1f)
        val on = MaterialTheme.colorScheme.onSurface
        Canvas(Modifier.padding(top = 8.dp).fillMaxWidth().height(22.dp)) {
            val bar = 6.dp.toPx()
            val r = CornerRadius(bar / 2f)
            drawRoundRect(s.baselineOuter, Offset(0f, 7.dp.toPx()), Size(size.width, bar), r)
            drawRoundRect(s.baselineBand, Offset(bedAt * size.width, 7.dp.toPx()), Size((1f - bedAt) * size.width, bar), r)
            drawCircle(on, 3.dp.toPx(), Offset(3.dp.toPx(), 10.dp.toPx()))
            drawRoundRect(on, Offset(bedAt * size.width - 1.dp.toPx(), 2.dp.toPx()), Size(2.dp.toPx(), 16.dp.toPx()), CornerRadius(1.dp.toPx()))
        }
        Row(Modifier.fillMaxWidth()) {
            Text(tr("now", "지금"), style = PawseType.Micro, color = s.faint, modifier = Modifier.weight(1f))
            Text(ScoreCopy.minuteOfDay(wake), style = PawseType.Micro, color = s.faint)
        }
        val bump = (plan.needMinutes - base).roundToInt()
        val caffeine = snapshot.energy?.caffeineAtBedMg ?: 0.0
        val notes = listOfNotNull(
            when {
                bump > 0 -> tr("Need is up $bump min after today.", "오늘 하루로 필요한 잠이 ${bump}분 늘었어요.")
                bump < 0 -> tr("Need is down ${-bump} min after today’s nap.", "오늘 낮잠으로 필요한 잠이 ${-bump}분 줄었어요.")
                else -> null
            },
            if (caffeine >= 10.0) {
                tr("About ${caffeine.roundToInt()} mg of caffeine will be left at ${ScoreCopy.minuteOfDay(bed)}.", "${ScoreCopy.minuteOfDay(bed)}에는 카페인이 약 ${caffeine.roundToInt()}mg 남아 있어요.")
            } else {
                null
            },
        )
        if (notes.isNotEmpty()) Text(notes.joinToString(" "), style = PawseType.Label, color = grey, modifier = Modifier.padding(top = 8.dp))
    }
}

/** Today's points against the range, and each session's share of them. */
@Composable
private fun TodaysLoad(day: DayReport) {
    val load = day.load
    val ring = ScoringConfig.DEFAULT.points.ringPoints.toFloat()
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    PawseCard {
        SplitRow {
            Text(tr("Today’s load", "오늘의 부하"), style = PawseType.Label, color = grey, modifier = Modifier.weight(1f))
            Text(pointsOf(day), style = PawseType.Label, color = grey)
        }
        RangeTrack(
            band = load.pointsRange?.let { it.low / ring..it.high / ring },
            fill = (load.points / ring).toFloat(),
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
        val sessions = load.sessions.filter { it.load > 0.0 }
        if (sessions.isEmpty()) {
            Text(tr("No sessions yet today.", "오늘은 아직 활동이 없어요."), style = PawseType.Label, color = grey, modifier = Modifier.padding(top = 4.dp))
        }
        sessions.forEach { session ->
            Column(Modifier.padding(vertical = 8.dp)) {
                FigureRow(session.title ?: tr("Workout", "운동")) {
                    Text(
                        (if (load.pointsReference > 0.0) LoadPoints.points(session.load, load.pointsReference, ScoringConfig.DEFAULT.points) else 0.0).roundToInt().toString(),
                        style = PawseType.MetricValue,
                    )
                }
                Text(
                    listOf(
                        "${session.durationMinutes.roundToInt()}${tr(" min", "분")}",
                        when (session.method) {
                            LoadMethod.BANISTER, LoadMethod.EDWARDS -> tr("from heart rate", "심박수 기준")
                            LoadMethod.RPE, null -> tr("from effort", "체감 강도 기준")
                        },
                        writer(session.source),
                    ).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = PawseType.Micro,
                    color = PawseTheme.semantics.faint,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Check-in
// ---------------------------------------------------------------------------

/**
 * How do you feel, 1 to 5, with Rough and Great at the ends and nothing
 * preselected. It never changes today's reading; after saving it is set beside Body.
 */
@Composable
private fun CheckIn(snapshot: ScoringSnapshot, onCheckIn: (Int) -> Unit, onTag: (DayTag) -> Unit, modifier: Modifier = Modifier) {
    val s = PawseTheme.semantics
    val feeling = snapshot.checkIn
    val haptic = rememberHaptic()
    PawseCard(modifier = modifier) {
        Text(tr("How do you feel?", "오늘 컨디션은 어때요?"), style = PawseType.Heading)
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..5).forEach { n ->
                val on = feeling == n
                // The choice inverts in 160 ms: the one selection recipe.
                val fill by animateColorAsState(
                    if (on) s.selection else MaterialTheme.colorScheme.surfaceContainerHigh,
                    tween(PawseMotion.PRESS_UP, easing = EaseOut),
                    label = "checkIn",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(48.dp)
                        .pressable(role = Role.RadioButton) {
                            // Saving confirms; tapping the same answer clears it, silently.
                            if (!on) haptic(PawseHaptic.Confirm)
                            onCheckIn(n)
                        }
                        .background(fill, CircleShape)
                        .clearAndSetSemantics {
                            contentDescription = when (n) {
                                1 -> "1, " + tr("Rough", "힘듦")
                                5 -> "5, " + tr("Great", "아주 좋음")
                                else -> "$n"
                            }
                            selected = on
                            role = Role.RadioButton
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$n", style = PawseType.BodyStrong, color = if (on) s.onSelection else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(tr("Rough", "힘듦"), style = PawseType.Micro, color = s.faint, modifier = Modifier.weight(1f))
            Text(tr("Great", "아주 좋음"), style = PawseType.Micro, color = s.faint)
        }
        val body = snapshot.today.body
        AnimatedVisibility(feeling != null && body != null, enter = RevealEnter, exit = RevealExit) {
            if (feeling == null || body == null) return@AnimatedVisibility
            val agree = !ScoreCopy.disagrees(feeling, body.band)
            Text(
                if (snapshot.showNumbers) {
                    tr("You said $feeling and Body said ${body.score}.", "${feeling}라고 했고 컨디션은 ${body.score}이에요.")
                } else {
                    tr("You said $feeling and Body said ${ScoreCopy.bandWord(body.band).lowercase()}.", "${feeling}라고 했고 컨디션은 ${ScoreCopy.bandWord(body.band)}이에요.")
                } + " " + if (agree) tr("They agree.", "서로 맞아요.") else ScoreCopy.DISAGREE,
                style = PawseType.Label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DayTag.entries.filter { it != DayTag.OTHER }.forEach { tag ->
                SelectChip(ScoreCopy.tag(tag), selected = tag in snapshot.tags, onClick = { onTag(tag) })
            }
        }
    }
}

internal fun scoreGlyph(type: ScoreType): String = when (type) {
    ScoreType.BODY -> Glyphs.Recovery
    ScoreType.SLEEP -> Glyphs.Sleep
    ScoreType.LOAD -> Glyphs.Load
}
