package app.pawse

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import app.pawse.core.data.scoring.ScoringSnapshot
import app.pawse.core.data.sync.ScoreSurfaces
import app.pawse.feature.home.ScoreCopy
import app.pawse.scoring.model.BodyBand
import app.pawse.scoring.model.IllnessLevel
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Where a finished read shows up outside the app: the widget, and once a week a summary. */
@Singleton
class AppSurfaces @Inject constructor(
    @ApplicationContext private val context: Context,
) : ScoreSurfaces {
    override fun onScored(snapshot: ScoringSnapshot, inBackground: Boolean) {
        TodayWidget.save(context, snapshot)
        TodayWidget.refresh(context)
        // Only the nightly read notifies, and only weekly: no daily score push.
        if (inBackground) WeeklyNotification.maybePost(context, snapshot)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SurfacesModule {
    @Binds
    abstract fun surfaces(impl: AppSurfaces): ScoreSurfaces
}

/**
 * Home-screen widget. Plain RemoteViews: no extra library for one card.
 *
 * The figures are kept in a small preferences file rather than read from the
 * encrypted database, because the launcher can ask for a redraw at any time.
 */
class TodayWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
    }

    companion object {
        private const val PREFS = "pawse_widget"

        fun save(context: Context, s: ScoringSnapshot) {
            val today = s.today
            val body = today.body
            val load = today.load
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                val word = body?.let { ScoreCopy.bandWord(it.band) } ?: "Settling"
                val number = body?.score?.takeIf { s.showNumbers }
                // Hidden numbers: the word takes the big line and the guidance stands alone under it.
                putString("score", number?.toString() ?: word)
                putBoolean("scoreIsNumber", number != null)
                putString("body", if (number != null) "$word · ${ScoreCopy.guidance(today.guidance)}" else ScoreCopy.guidance(today.guidance))
                putString(
                    "level",
                    if (today.illness.level == IllnessLevel.ALERT) ALERT else body?.band?.name,
                )
                putString("sleep", "Sleep  " + ScoreCopy.debt(today.sleep.debtHours))
                val low = load.rangeLow
                val high = load.rangeHigh
                putString(
                    "load",
                    "Load  " + when {
                        load.rest -> "rest"
                        low != null && high != null -> "${ScoreCopy.load(low)}–${ScoreCopy.load(high)}"
                        else -> "—"
                    },
                )
                putString(
                    "energy",
                    s.energy?.let { "Focus  ${ScoreCopy.minuteOfDay(it.focusStart)}–${ScoreCopy.minuteOfDay(it.focusEnd)}" } ?: "",
                )
                putString("readAt", if (s.lastSyncEpochMs > 0) ScoreCopy.clock(s.lastSyncEpochMs) else "")
            }
        }

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TodayWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val views = RemoteViews(context.packageName, R.layout.widget_today).apply {
                setTextViewText(R.id.widget_score, prefs.getString("score", "—"))
                setTextViewTextSize(
                    R.id.widget_score,
                    TypedValue.COMPLEX_UNIT_SP,
                    if (prefs.getBoolean("scoreIsNumber", true)) 44f else 24f,
                )
                setTextViewText(R.id.widget_body, prefs.getString("body", "Open Pawse to read last night"))
                setTextColor(R.id.widget_band_dot, dotColor(prefs.getString("level", null)))
                setTextViewText(R.id.widget_sleep, prefs.getString("sleep", ""))
                setTextViewText(R.id.widget_load, prefs.getString("load", ""))
                setTextViewText(R.id.widget_energy, prefs.getString("energy", ""))
                setTextViewText(R.id.widget_read_at, prefs.getString("readAt", ""))
                setOnClickPendingIntent(R.id.widget_root, openApp(context, route = null, requestCode = 10))
            }
            manager.updateAppWidget(ids, views)
        }

        private const val ALERT = "ALERT"

        /** The dark palette's hues. Red only for an illness alert; the dot always sits by a word. */
        private fun dotColor(level: String?): Int = when (level) {
            ALERT -> 0xFFFF7A6B.toInt()
            BodyBand.ABOVE_NORMAL.name -> 0xFF5CD6A0.toInt()
            BodyBand.BELOW_NORMAL.name, BodyBand.STRAINED.name -> 0xFFF5C451.toInt()
            else -> 0xFF7E878F.toInt()
        }
    }
}

/**
 * One calm summary a week, after a nightly read, never a daily score push and
 * never a nudge to open the app for its own sake. Low importance: no sound.
 */
object WeeklyNotification {
    private const val CHANNEL = "weekly_summary"
    private const val ID = 1
    private const val PREFS = "pawse_notify"
    private const val EVERY_DAYS = 7L

    fun maybePost(context: Context, s: ScoringSnapshot) {
        val today = LocalDate.now()
        if (s.date != today || s.week.size < 4) return

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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
            ?: usual?.let { "Body mostly ${ScoreCopy.bandWord(it.key).lowercase()} (${it.value} of ${s.week.size} days)." }
        val sleep = "Sleep: ${ScoreCopy.debt(s.today.sleep.debtHours).lowercase()}."
        val load = s.today.load.formBand?.let { "Load: ${ScoreCopy.form(it).lowercase()}." }
        val text = listOfNotNull(body, sleep, load).joinToString(" ")

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pawse)
            .setContentTitle("Your week")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context, route = null, requestCode = 20))
            .addAction(0, "See why", openApp(context, route = ROUTE_EXPLAIN_BODY, requestCode = 21))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(ID, notification) }
        prefs.edit { putString("last_weekly", today.toString()) }
    }
}

const val EXTRA_ROUTE = "app.pawse.route"
const val ROUTE_FOOD = "food"
const val ROUTE_EXPLAIN_BODY = "explain_body"

private fun openApp(context: Context, route: String?, requestCode: Int): PendingIntent =
    PendingIntent.getActivity(
        context,
        requestCode,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { route?.let { putExtra(EXTRA_ROUTE, it) } },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
