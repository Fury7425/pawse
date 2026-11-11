package app.pawse

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.pawse.core.data.profile.NotificationSettings
import app.pawse.core.data.profile.NotifyKind
import app.pawse.core.data.scoring.ScoringSnapshot
import app.pawse.core.data.sync.ScoreSurfaces
import app.pawse.feature.home.ScoreCopy
import app.pawse.scoring.model.IllnessLevel
import app.pawse.scoring.tr
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a finished read shows up outside the app: the widget, and the notifications
 * the user has switched on. With the defaults that is one note a week, plus the
 * overnight heart rate alert. Never a daily score push.
 */
@Singleton
class AppSurfaces @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notifications: NotificationSettings,
    private val model: app.pawse.core.model.PawseModel,
) : ScoreSurfaces {
    override fun onScored(snapshot: ScoringSnapshot, inBackground: Boolean) {
        TodayWidget.save(context, snapshot)
        TodayWidget.refresh(context)
        MovingWidget.update(context)
        // The model's widgets: offered while the model is on, and the summary kept current.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching {
                SummaryWidget.offer(context, model.state.first() is app.pawse.core.model.ModelState.Ready)
                SummaryWidget.refresh(context)
            }
        }
        if (notifications.isOn(NotifyKind.ILLNESS)) Notes.illness(context, snapshot)
        if (notifications.isOn(NotifyKind.WIND_DOWN)) Notes.scheduleWindDown(context, snapshot) else Notes.cancelWindDown(context)
        // Only the nightly read sends the weekly note and the no-new-night note.
        if (inBackground && notifications.isOn(NotifyKind.WEEKLY)) WeeklyNotification.maybePost(context, snapshot)
        if (inBackground && notifications.isOn(NotifyKind.NO_NIGHT)) Notes.noNight(context, snapshot)
    }

    override fun onAccess(readable: Boolean) {
        TodayWidget.setReadable(context, readable)
        TodayWidget.refresh(context)
    }
}

/** The opt-in notes beside the weekly one. Each posts at most once a day. */
object Notes {
    private const val CHANNEL = "pawse_notes"
    private const val ALERT_CHANNEL = "pawse_alert"
    private const val WIND_DOWN_WORK = "wind_down"

    /** The one alert that can come through quiet hours: overnight heart rate high for several nights. */
    fun illness(context: Context, s: ScoringSnapshot) {
        if (s.today.illness.level != IllnessLevel.ALERT) return
        post(
            context,
            channel = ALERT_CHANNEL,
            channelName = tr("Overnight heart rate", "야간 심박수"),
            importance = NotificationManager.IMPORTANCE_DEFAULT,
            id = 2,
            onceKey = "illness_${s.date}",
            title = tr("Overnight heart rate", "야간 심박수"),
            text = ScoreCopy.ILLNESS_ALERT,
            route = ROUTE_EXPLAIN_BODY,
        )
    }

    /** No main sleep for last night by the morning read: the watch app may not have synced. */
    fun noNight(context: Context, s: ScoringSnapshot) {
        if (s.nights[s.date]?.signals?.sleep != null) return
        post(
            context,
            channel = CHANNEL,
            channelName = tr("Notes", "알림"),
            importance = NotificationManager.IMPORTANCE_LOW,
            id = 3,
            onceKey = "no_night_${s.date}",
            title = tr("No new night yet", "새 수면 기록이 아직 없어요"),
            text = tr(
                "Health Connect has nothing for last night. Open your watch app, then Pawse.",
                "헬스 커넥트에 지난밤 기록이 없어요. 워치 앱을 연 다음 Pawse를 열어 주세요.",
            ),
            route = null,
        )
    }

    /** At today's wind-down time from the forecast, if it is still ahead. */
    fun scheduleWindDown(context: Context, s: ScoringSnapshot) {
        val energy = s.energy ?: return
        val now = java.time.ZonedDateTime.now()
        val at = s.date.atStartOfDay(now.zone).plusMinutes(energy.windDownMinute.toLong())
        val delay = java.time.Duration.between(now, at)
        if (delay.isNegative) return
        val request = OneTimeWorkRequestBuilder<WindDownWorker>()
            .setInitialDelay(delay)
            .setInputData(workDataOf(WindDownWorker.BED to ScoreCopy.minuteOfDay(energy.bedtimeMinute)))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WIND_DOWN_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelWindDown(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WIND_DOWN_WORK)
    }

    internal fun post(
        context: Context,
        channel: String,
        channelName: String,
        importance: Int,
        id: Int,
        onceKey: String,
        title: String,
        text: String,
        route: String?,
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val prefs = context.getSharedPreferences(NotificationSettings.PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(onceKey, false)) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channel, channelName, importance))
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_pawse)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context, route = route, requestCode = 30 + id))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
            .onSuccess { NotificationSettings.record(context, title, text) }
        prefs.edit { putBoolean(onceKey, true) }
    }
}

/** Posts the wind-down note when its delay runs out. Plain worker: nothing to inject. */
class WindDownWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val bed = inputData.getString(BED).orEmpty()
        Notes.post(
            applicationContext,
            channel = "pawse_notes",
            channelName = tr("Notes", "알림"),
            importance = NotificationManager.IMPORTANCE_LOW,
            id = 4,
            onceKey = "wind_down_${LocalDate.now()}",
            title = tr("Time to wind down", "쉬기 시작할 시간이에요"),
            text = tr("Bed by $bed covers what today asked for.", "${bed} 전에 자면 오늘 쓴 만큼 채울 수 있어요."),
            route = null,
        )
        return Result.success()
    }

    companion object {
        const val BED = "bed"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SurfacesModule {
    @Binds
    abstract fun surfaces(impl: AppSurfaces): ScoreSurfaces
}

/**
 * One calm summary a week, after a nightly read, never a daily score push and
 * never a nudge to open the app for its own sake. Low importance: no sound.
 */
object WeeklyNotification {
    private const val CHANNEL = "weekly_summary"
    private const val ID = 1
    private const val EVERY_DAYS = 7L

    fun maybePost(context: Context, s: ScoringSnapshot) {
        val today = LocalDate.now()
        if (s.date != today || s.week.size < 4) return

        val prefs = context.getSharedPreferences(NotificationSettings.PREFS, Context.MODE_PRIVATE)
        val last = prefs.getString("last_weekly", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (last != null && last.plusDays(EVERY_DAYS).isAfter(today)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_weekly), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.channel_weekly_description) },
        )

        val bands = s.week.mapNotNull { it.body?.band }
        val usual = bands.groupingBy { it }.eachCount().maxByOrNull { it.value }
        val body = s.today.weekly?.let { ScoreCopy.weekly(it.pattern) + "." }
            ?: usual?.let {
                val word = ScoreCopy.bandWord(it.key)
                tr(
                    "Body mostly ${word.lowercase()} (${it.value} of ${s.week.size} days).",
                    "컨디션은 대체로 ‘$word’ (${s.week.size}일 중 ${it.value}일).",
                )
            }
        // Hours asleep on average, never a running debt.
        val slept = s.week.mapNotNull { it.sleep.lastNightMinutes }.takeIf { it.isNotEmpty() }?.average()
        val sleep = slept?.let { tr("Sleep: about ${ScoreCopy.formatMinutes(it)} a night.", "수면: 하룻밤 평균 약 ${ScoreCopy.formatMinutes(it)}.") }
        val load = s.today.load.formBand?.let { tr("Load: ", "부하: ") + ScoreCopy.form(it).lowercase() + "." }
        val text = listOfNotNull(body, sleep, load).joinToString(" ")

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pawse)
            .setContentTitle(tr("Your week", "이번 주 요약"))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context, route = null, requestCode = 20))
            .addAction(0, tr("See why", "이유 보기"), openApp(context, route = ROUTE_EXPLAIN_BODY, requestCode = 21))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(ID, notification) }
            .onSuccess { NotificationSettings.record(context, tr("Your week", "이번 주 요약"), text) }
        prefs.edit { putString("last_weekly", today.toString()) }
    }
}

const val EXTRA_ROUTE = "app.pawse.route"
const val ROUTE_FOOD = "food"
const val ROUTE_SCAN = "scan"
const val ROUTE_ADD_FOOD = "add_food"
const val ROUTE_EXPLAIN_BODY = "explain_body"
const val ROUTE_ASK = "ask"

internal fun openApp(context: Context, route: String?, requestCode: Int): PendingIntent =
    PendingIntent.getActivity(
        context,
        requestCode,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { route?.let { putExtra(EXTRA_ROUTE, it) } },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
