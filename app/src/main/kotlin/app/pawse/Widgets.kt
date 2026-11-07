package app.pawse

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import androidx.core.content.edit
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.pawse.core.data.activity.MovingRepository
import app.pawse.core.data.activity.MovingSnapshot
import app.pawse.core.data.food.FoodDay
import app.pawse.core.data.food.FoodRepository
import app.pawse.core.data.scoring.ScoringSnapshot
import app.pawse.feature.home.ScoreCopy
import app.pawse.scoring.activity.MovingWord
import app.pawse.scoring.config.ScoringConfig
import app.pawse.scoring.load.LoadPoints
import app.pawse.scoring.model.BodyBand
import app.pawse.scoring.model.BodyStatus
import app.pawse.scoring.model.IllnessLevel
import app.pawse.scoring.tr
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/*
 * The home and lock-screen widgets. Plain RemoteViews, with the rings drawn into
 * bitmaps: no widget library for a few cards.
 *
 * Figures are kept in small preferences files rather than read from the encrypted
 * database, because the launcher can ask for a redraw at any time. They are the
 * app's numbers, never a widget's own sums, and each widget says how old they are.
 */

/** Scores: the three rings at 4×2, Body at 2×2, and the lock-screen slot where the phone has one. */
class TodayWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        render(context, manager, intArrayOf(id))
    }

    companion object {
        private const val PREFS = "pawse_widget"
        private const val ALERT = "ALERT"

        fun save(context: Context, s: ScoringSnapshot) {
            val today = s.today
            val body = today.body
            val slept = today.sleep.lastNightMinutes
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putLong("readAt", s.lastSyncEpochMs)
                putString("guidance", ScoreCopy.guidance(today.guidance))
                putString("level", if (today.illness.level == IllnessLevel.ALERT) ALERT else body?.band?.name)
                putString("bodyValue", body?.score?.takeIf { s.showNumbers }?.toString())
                putFloat("bodyFraction", body?.let { it.score / 100f } ?: -1f)
                putString(
                    "bodyWord",
                    body?.let { ScoreCopy.bandWord(it.band) }
                        ?: if (today.bodyStatus == BodyStatus.NO_RECENT_NIGHT) tr("No reading", "기록 없음") else tr("Learning", "배우는 중"),
                )
                putFloat("sleptMinutes", slept?.toFloat() ?: -1f)
                putFloat("sleepFraction", slept?.let { (it / (today.sleep.needHours * 60.0)).toFloat() } ?: -1f)
                putFloat("loadPoints", today.load.points.toFloat())
                putFloat("loadFraction", LoadPoints.ringFraction(today.load.points, ScoringConfig.DEFAULT.points))
                // Moving today counts from this morning's wake.
                putString("wakeDate", s.date.toString())
                putLong("wake", s.nights[s.date]?.signals?.sleep?.endEpochMs ?: 0L)
            }
        }

        /** Off when the last read found no Health Connect access: the widget then says so instead of guessing. */
        fun setReadable(context: Context, readable: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("readable", readable) }
        }

        /** Today's wake from the last read, for Moving today; null before one. */
        fun wake(context: Context): Long? {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return prefs.getLong("wake", 0L).takeIf { it > 0 && prefs.getString("wakeDate", null) == LocalDate.now().toString() }
        }

        fun refresh(context: Context) {
            val ids = Widgets.ids(context, TodayWidget::class.java)
            if (ids.isNotEmpty()) render(context, AppWidgetManager.getInstance(context), ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ids.forEach { id -> manager.updateAppWidget(id, views(context, prefs, manager.getAppWidgetOptions(id))) }
        }

        private fun views(context: Context, prefs: SharedPreferences, options: Bundle): RemoteViews {
            val host = options.getInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
            if (host == AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD) return fill(context, prefs, R.layout.widget_lock)
            if (Build.VERSION.SDK_INT >= 31) {
                return RemoteViews(
                    mapOf(
                        SizeF(110f, 110f) to fill(context, prefs, R.layout.widget_body),
                        SizeF(250f, 110f) to fill(context, prefs, R.layout.widget_today),
                    ),
                )
            }
            val wide = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250) >= 250
            return fill(context, prefs, if (wide) R.layout.widget_today else R.layout.widget_body)
        }

        private fun fill(context: Context, prefs: SharedPreferences, layout: Int): RemoteViews {
            val readAt = prefs.getLong("readAt", 0L)
            val readable = prefs.getBoolean("readable", true)
            // No access dims the widget like an old one: it never shows numbers it can't stand behind as current.
            val old = Widgets.stale(readAt) || !readable
            val age = Widgets.age(readAt)
            val grey = context.getColor(R.color.widget_on2)
            val on = context.getColor(if (old) R.color.widget_faint else R.color.widget_on)
            val arc = context.getColor(levelColor(prefs.getString("level", null)))
            val bodyValue = prefs.getString("bodyValue", null)
            val word = prefs.getString("bodyWord", null).orEmpty()
            val slept = prefs.getFloat("sleptMinutes", -1f).takeIf { it >= 0f }
            val sleepValue = slept?.let { String.format(Locale.getDefault(), "%.1f", it / 60f) } ?: "—"
            val points = if (readAt > 0) prefs.getFloat("loadPoints", 0f).roundToInt().toString() else "—"
            val guidance = when {
                !readable -> tr("Pawse can’t read Health Connect. Open Pawse.", "Pawse가 헬스 커넥트를 읽지 못해요. Pawse를 열어 주세요.")
                readAt == 0L -> context.getString(R.string.widget_empty)
                old -> tr("From $age. Open Pawse to update.", "$age 기준이에요. Pawse를 열면 새로 읽어요.")
                else -> prefs.getString("guidance", null).orEmpty()
            }
            val bodyLine = tr("Body", "컨디션") + (bodyValue?.let { " $it" } ?: "") + " · " + word
            val sleepLine = slept?.let { tr("Slept ${ScoreCopy.formatMinutes(it.toDouble())}", "수면 ${ScoreCopy.formatMinutes(it.toDouble())}") }

            return RemoteViews(context.packageName, layout).apply {
                setTextViewText(R.id.widget_read_at, age)
                if (layout == R.layout.widget_today) {
                    setImageViewBitmap(R.id.widget_sleep_ring, Widgets.ring(context, 56, 5f, prefs.getFloat("sleepFraction", -1f), grey, old))
                    setTextViewText(R.id.widget_sleep_value, sleepValue)
                    setImageViewBitmap(R.id.widget_load_ring, Widgets.ring(context, 56, 5f, prefs.getFloat("loadFraction", -1f), grey, old))
                    setTextViewText(R.id.widget_load_value, points)
                    setTextViewText(R.id.widget_guidance, guidance)
                }
                val lock = layout == R.layout.widget_lock
                setImageViewBitmap(
                    R.id.widget_body_ring,
                    Widgets.ring(context, if (lock) 32 else 72, if (lock) 4f else 6f, prefs.getFloat("bodyFraction", -1f), arc, old),
                )
                setTextViewText(R.id.widget_body_value, bodyValue ?: if (readAt == 0L) "—" else "")
                setTextColor(R.id.widget_band_dot, arc)
                setTextViewText(R.id.widget_body_word, word)
                setTextViewText(R.id.widget_lock_title, bodyLine)
                setTextViewText(R.id.widget_lock_sub, listOfNotNull(sleepLine, "Pawse $age".trim()).joinToString(" · "))
                listOf(R.id.widget_sleep_value, R.id.widget_body_value, R.id.widget_load_value, R.id.widget_guidance, R.id.widget_lock_title)
                    .forEach { setTextColor(it, on) }
                setContentDescription(
                    R.id.widget_root,
                    listOfNotNull(sleepLine, bodyLine, tr("Load $points", "부하 $points"), guidance).joinToString(". "),
                )
                setOnClickPendingIntent(R.id.widget_root, openApp(context, route = null, requestCode = 10))
            }
        }

        /** The app's band colours. Red only for an illness alert; the dot always sits by a word. */
        private fun levelColor(level: String?): Int = when (level) {
            ALERT -> R.color.widget_low
            BodyBand.ABOVE_NORMAL.name -> R.color.widget_high
            BodyBand.BELOW_NORMAL.name, BodyBand.STRAINED.name -> R.color.widget_moderate
            else -> R.color.widget_neutral
        }
    }
}

/**
 * Moving today at 4×1. Read again after each sync and every 30 minutes, in the
 * background, and only while the widget is on a home screen.
 */
class MovingWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
        // Scheduling work can itself make the launcher ask for an update, so a read
        // in the last quarter hour is enough: that breaks the loop.
        val tried = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("triedAt", 0L)
        if (System.currentTimeMillis() - tried > RETRY_MS) update(context)
    }

    companion object {
        private const val PREFS = "pawse_widget_moving"
        private const val WORK = "moving_widget"
        private const val RETRY_MS = 15 * 60 * 1000L

        fun update(context: Context) {
            if (Widgets.ids(context, MovingWidget::class.java).isEmpty()) return
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<MovingWidgetWorker>().build())
        }

        /** A failed read keeps the last numbers; a read with nothing written clears them. */
        fun save(context: Context, m: MovingSnapshot?, failed: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putLong("triedAt", System.currentTimeMillis())
                if (failed) return@edit
                if (m == null) {
                    putLong("asOf", 0L)
                    return@edit
                }
                putLong("asOf", m.asOfEpochMs)
                putString("steps", NumberFormat.getIntegerInstance().format(m.steps.today.roundToInt()))
                putString("stepsWord", tr("steps", "걸음") + " · " + word(m.steps.word))
                putString("kcal", m.energy.today.roundToInt().toString())
                putString("kcalWord", "kcal · " + word(m.energy.word))
                val wakeHour = Instant.ofEpochMilli(m.wakeEpochMs).atZone(ZoneId.systemDefault()).hour
                val bpm = m.heart.filter { it.hour >= wakeHour }.map { it.mean }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
                putString("bpm", bpm?.toString() ?: "—")
                putString(
                    "bpmWord",
                    if (m.wakeKnown) tr("bpm since ${ScoreCopy.clock(m.wakeEpochMs)}", "${ScoreCopy.clock(m.wakeEpochMs)} 이후 bpm") else tr("bpm today", "오늘 bpm"),
                )
            }
        }

        fun refresh(context: Context) {
            val ids = Widgets.ids(context, MovingWidget::class.java)
            if (ids.isNotEmpty()) render(context, AppWidgetManager.getInstance(context), ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val asOf = prefs.getLong("asOf", 0L)
            // Yesterday's totals are not today's: past midnight the widget waits for a read.
            val today = asOf > 0 && Instant.ofEpochMilli(asOf).atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now()
            fun value(key: String) = if (today) prefs.getString(key, null) ?: "—" else "—"
            fun label(key: String, fallback: String) = if (today) prefs.getString(key, null) ?: fallback else fallback
            val faint = context.getColor(R.color.widget_faint)
            val views = RemoteViews(context.packageName, R.layout.widget_moving).apply {
                setTextViewText(R.id.widget_read_at, if (today) ScoreCopy.clock(asOf) else "")
                setTextViewText(R.id.widget_steps, value("steps"))
                setTextViewText(R.id.widget_steps_word, label("stepsWord", tr("steps", "걸음")))
                setTextViewText(R.id.widget_kcal, value("kcal"))
                setTextViewText(R.id.widget_kcal_word, label("kcalWord", "kcal"))
                setTextViewText(R.id.widget_bpm, value("bpm"))
                setTextViewText(R.id.widget_bpm_word, label("bpmWord", "bpm"))
                if (Widgets.stale(asOf)) listOf(R.id.widget_steps, R.id.widget_kcal, R.id.widget_bpm).forEach { setTextColor(it, faint) }
                setOnClickPendingIntent(R.id.widget_root, openApp(context, route = null, requestCode = 11))
            }
            manager.updateAppWidget(ids, views)
        }

        private fun word(w: MovingWord): String = when (w) {
            MovingWord.BELOW -> tr("below usual", "평소보다 적음")
            MovingWord.ABOUT -> tr("usual", "평소")
            MovingWord.ABOVE -> tr("above usual", "평소보다 많음")
            MovingWord.LEARNING -> tr("learning", "배우는 중")
        }
    }
}

/** Reads Moving today for its widget. Today's wake comes from the last read, so nothing is rescored. */
@HiltWorker
class MovingWidgetWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val moving: MovingRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val now = ZonedDateTime.now()
        val wakes = TodayWidget.wake(applicationContext)?.let { mapOf(now.toLocalDate() to it) }.orEmpty()
        val read = runCatching { moving.read(now, wakes) }
        MovingWidget.save(applicationContext, read.getOrNull(), failed = read.isFailure)
        MovingWidget.refresh(applicationContext)
        return Result.success()
    }
}

/** Food at 2×2: what was logged today, with Scan and +. */
class FoodWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
    }

    override fun onEnabled(context: Context) {
        follow(context) { EntryPointAccessors.fromApplication(context, FoodEntryPoint::class.java).food() }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface FoodEntryPoint {
        fun food(): FoodRepository
    }

    companion object {
        private const val PREFS = "pawse_widget_food"

        @Volatile private var following = false

        /**
         * Keeps the widget on today's log for as long as the process lives. Food is
         * only logged while it does, so nothing is ever polled.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun follow(context: Context, food: () -> FoodRepository) {
            if (following || Widgets.ids(context, FoodWidget::class.java).isEmpty()) return
            following = true
            val app = context.applicationContext
            val days = flow {
                while (true) {
                    val today = LocalDate.now()
                    emit(today)
                    delay(Duration.between(LocalDateTime.now(), today.plusDays(1).atStartOfDay()).toMillis() + 1_000)
                }
            }
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                days.flatMapLatest { food().observeDay(it) }.collect { day ->
                    save(app, day)
                    refresh(app)
                }
            }
        }

        private fun save(context: Context, day: FoodDay) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putString("date", day.date.toString())
                putInt("count", day.entries.size)
                putString("last", day.entries.maxByOrNull { it.eatenAtEpochMs }?.name)
            }
        }

        private fun refresh(context: Context) {
            val ids = Widgets.ids(context, FoodWidget::class.java)
            if (ids.isNotEmpty()) render(context, AppWidgetManager.getInstance(context), ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val today = prefs.getString("date", null) == LocalDate.now().toString()
            val count = if (today) prefs.getInt("count", 0) else 0
            val last = if (today) prefs.getString("last", null) else null
            val views = RemoteViews(context.packageName, R.layout.widget_food).apply {
                setTextViewText(
                    R.id.widget_food_count,
                    when (count) {
                        0 -> context.getString(R.string.widget_food_none)
                        1 -> tr("1 thing logged", "1개 기록함")
                        else -> tr("$count things logged", "${count}개 기록함")
                    },
                )
                setTextViewText(R.id.widget_food_last, last?.let { tr("last: $it", "마지막: $it") } ?: "")
                setOnClickPendingIntent(R.id.widget_root, openApp(context, route = ROUTE_FOOD, requestCode = 12))
                setOnClickPendingIntent(R.id.widget_scan, openApp(context, route = ROUTE_SCAN, requestCode = 13))
                setOnClickPendingIntent(R.id.widget_add, openApp(context, route = ROUTE_ADD_FOOD, requestCode = 14))
            }
            manager.updateAppWidget(ids, views)
        }
    }
}

internal object Widgets {
    /** Widgets can't animate, so after this long they dim and say how old they are. */
    private const val STALE_MS = 12 * 60 * 60 * 1000L

    fun ids(context: Context, provider: Class<*>): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, provider))

    fun stale(at: Long): Boolean = at > 0 && System.currentTimeMillis() - at > STALE_MS

    /** "07:12" today, "yesterday 22:40", or the date. Empty before the first read. */
    fun age(at: Long): String {
        if (at <= 0) return ""
        val day = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()
        val clock = ScoreCopy.clock(at)
        return when (day) {
            LocalDate.now() -> clock
            LocalDate.now().minusDays(1) -> tr("yesterday $clock", "어제 $clock")
            else -> day.format(DateTimeFormatter.ofPattern(tr("d MMM", "M월 d일"), Locale.getDefault()))
        }
    }

    /** A ring as a bitmap: the track, then the arc from twelve o'clock. A negative fraction draws the track alone. */
    fun ring(context: Context, sizeDp: Int, strokeDp: Float, fraction: Float, color: Int, dim: Boolean): Bitmap {
        val density = context.resources.displayMetrics.density
        val px = (sizeDp * density).roundToInt()
        val stroke = strokeDp * density
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }
        val box = RectF(stroke / 2, stroke / 2, px - stroke / 2, px - stroke / 2)
        paint.color = context.getColor(R.color.widget_track)
        canvas.drawArc(box, 0f, 360f, false, paint)
        if (fraction > 0f) {
            paint.color = color
            if (dim) paint.alpha = 140
            canvas.drawArc(box, -90f, 360f * fraction.coerceAtMost(1f), false, paint)
        }
        return bitmap
    }
}
