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
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import app.pawse.core.data.scoring.ScoringSnapshot
import app.pawse.core.data.sync.ScoreSurfaces
import app.pawse.feature.home.ScoreCopy
import app.pawse.scoring.config.StrainScale
import app.pawse.scoring.model.Band
import app.pawse.scoring.model.ScoreType
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/** Where a finished read shows up outside the app: the widget, and once a day a notification. */
@Singleton
class AppSurfaces @Inject constructor(
    @ApplicationContext private val context: Context,
) : ScoreSurfaces {
    override fun onScored(snapshot: ScoringSnapshot, inBackground: Boolean) {
        TodayWidget.save(context, snapshot)
        TodayWidget.refresh(context)
        // Only the nightly read notifies. A user who tapped refresh is already looking.
        if (inBackground) MorningNotification.maybePost(context, snapshot)
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
 * encrypted database, because the launcher can ask for a redraw at any time and
 * opening SQLCipher for four numbers is not worth a cold start. They are the same
 * four numbers the widget shows on the home screen anyway.
 */
class TodayWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
    }

    companion object {
        private const val PREFS = "pawse_widget"

        fun save(context: Context, s: ScoringSnapshot) {
            val recovery = s.scores[ScoreType.RECOVERY]
            val strain = s.strainDisplayValue
            val target = s.strainTarget?.target?.let { if (s.strainScale == StrainScale.WHOOP_21) it * 21.0 / 100.0 else it }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putString("recovery", recovery?.let { "${it.value}%" } ?: "—")
                putString(
                    "band",
                    recovery?.let { ScoreCopy.bandWord(ScoreType.RECOVERY, it.band) }
                        ?: s.unavailable[ScoreType.RECOVERY]?.let { "Not scored today" }
                        ?: "Open Pawse to read last night",
                )
                putString("bandLevel", recovery?.band?.name)
                putString("sleep", "Sleep  ${s.scores[ScoreType.SLEEP]?.value ?: "—"}")
                putString(
                    "strain",
                    "Strain  " + (strain?.let { ScoreCopy.formatStrain(it, s.strainScale).substringBefore(" /") } ?: "—") +
                        (target?.let { " / ${ScoreCopy.formatStrain(it, s.strainScale).substringBefore(" /")}" } ?: ""),
                )
                putString("energy", "Energy  ${s.scores[ScoreType.ENERGY_BANK]?.value ?: "—"}")
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
                setTextViewText(R.id.widget_recovery, prefs.getString("recovery", "—"))
                setTextViewText(R.id.widget_band, prefs.getString("band", "Open Pawse to read last night"))
                setTextColor(R.id.widget_band_dot, bandColor(prefs.getString("bandLevel", null)))
                setTextViewText(R.id.widget_sleep, prefs.getString("sleep", ""))
                setTextViewText(R.id.widget_strain, prefs.getString("strain", ""))
                setTextViewText(R.id.widget_energy, prefs.getString("energy", ""))
                setTextViewText(R.id.widget_read_at, prefs.getString("readAt", ""))
                setOnClickPendingIntent(R.id.widget_root, openApp(context, route = null, requestCode = 10))
            }
            manager.updateAppWidget(ids, views)
        }

        /** The dark palette's band hues; the dot is always next to the word. */
        private fun bandColor(level: String?): Int = when (level) {
            Band.HIGH.name -> 0xFF5CD6A0.toInt()
            Band.MODERATE.name -> 0xFFF5C451.toInt()
            Band.LOW.name -> 0xFFFF7A6B.toInt()
            else -> 0xFF7E878F.toInt()
        }
    }
}

/**
 * One calm note a day, after the nightly read, and never a nudge to open the app
 * for its own sake. Low importance: no sound, no heads-up.
 */
object MorningNotification {
    private const val CHANNEL = "morning_read"
    private const val ID = 1
    private const val PREFS = "pawse_notify"

    fun maybePost(context: Context, s: ScoringSnapshot) {
        val today = LocalDate.now()
        if (s.date != today) return
        val recovery = s.scores[ScoreType.RECOVERY]
        if (recovery == null && s.scores[ScoreType.SLEEP] == null) return

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("last", null) == today.toString()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_morning), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.channel_morning_description) },
        )

        val target = s.strainTarget?.target?.let { if (s.strainScale == StrainScale.WHOOP_21) it * 21.0 / 100.0 else it }
        val title = ScoreCopy.warmLine(
            hour = LocalTime.now().hour,
            recovery = recovery,
            recoveryMissing = s.unavailable[ScoreType.RECOVERY],
            warmingUp = s.warmup != null,
            strain = s.strainDisplayValue,
            strainTarget = target,
        )
        val body = buildList {
            recovery?.let { add("Recovery ${it.value}% · ${ScoreCopy.recoverySentence(it)}") }
            target?.let { add("Aim for strain around ${ScoreCopy.formatStrain(it, s.strainScale).substringBefore(" /")}.") }
        }.joinToString(" ").ifEmpty { "Last night's Sleep is ready." }

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pawse)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp(context, route = null, requestCode = 20))
            .addAction(0, "See why", openApp(context, route = ROUTE_EXPLAIN_RECOVERY, requestCode = 21))
            .addAction(0, "Log breakfast", openApp(context, route = ROUTE_FOOD, requestCode = 22))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(ID, notification) }
        prefs.edit { putString("last", today.toString()) }
    }
}

const val EXTRA_ROUTE = "app.pawse.route"
const val ROUTE_FOOD = "food"
const val ROUTE_EXPLAIN_RECOVERY = "explain_recovery"

private fun openApp(context: Context, route: String?, requestCode: Int): PendingIntent =
    PendingIntent.getActivity(
        context,
        requestCode,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { route?.let { putExtra(EXTRA_ROUTE, it) } },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
