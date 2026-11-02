package app.pawse.core.model

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.pawse.scoring.tr
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.LocalDate

/**
 * The one file Pawse downloads: Liquid AI's LFM3-1.7B in its 4-bit QAD build, from
 * the model's official Hugging Face repository, pinned to a commit and checked by hash.
 */
internal object ModelFile {
    const val URL =
        "https://huggingface.co/LiquidAI/LFM3-1.7B-GGUF/resolve/bad5729bba61d076fff20d2548e62526665af215/LFM3-1.7B-QAD-Q4_0.gguf"
    const val SHA256 = "43a7d09a0e9cba91b63eb7052488b9564569bddfd65754d2e426465e572a7452"
    private const val NAME = "lfm3-1.7b-qad-q4_0.gguf"

    // Never backed up: a gigabyte that can be downloaded again.
    fun done(context: Context) = File(context.noBackupFilesDir, NAME)
    fun part(context: Context) = File(context.noBackupFilesDir, "$NAME.part")
}

/**
 * Downloads the model under Android's download notification. It resumes from what
 * is already on disk, hashes as it goes, and keeps the file only if the hash matches.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val prefs = ModelPreferences(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val part = ModelFile.part(applicationContext)
        val done = ModelFile.done(applicationContext)
        if (done.exists()) return@withContext Result.success()
        // Android 12 and up refuse a foreground service started from the background.
        // The download then runs in WorkManager's ten-minute turns, resuming each time.
        runCatching { setForeground(foregroundInfo(part.length())) }

        val matches = try {
            fetch(part)
        } catch (e: IOException) {
            return@withContext if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
        // A wrong hash, or the switch went off while the last bytes came in: nothing is kept.
        if (!matches || !prefs.enabled.first() || !part.renameTo(done)) {
            part.delete()
            return@withContext Result.failure()
        }
        prefs.setReady(LocalDate.now())
        Result.success()
    }

    /** Streams the file onto [part], resuming what is there. True when it matches the published hash. */
    private suspend fun fetch(part: File): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        if (part.length() > PawseModel.BYTES) part.delete()
        var have = part.length()
        val buffer = ByteArray(BUFFER)
        if (have > 0) {
            part.inputStream().use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
        }
        if (have < PawseModel.BYTES) {
            val connection = (URL(ModelFile.URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                // The app's name and nothing else: no id, no device, no locale.
                setRequestProperty("User-Agent", "Pawse/0.1.0 (Android)")
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            try {
                val append = when (connection.responseCode) {
                    HttpURLConnection.HTTP_PARTIAL -> true
                    HttpURLConnection.HTTP_OK -> false
                    else -> throw IOException("HTTP ${connection.responseCode}")
                }
                if (!append) {
                    digest.reset()
                    have = 0
                }
                FileOutputStream(part, append).use { out ->
                    connection.inputStream.use { input ->
                        var reported = have
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            have += n
                            if (have - reported >= REPORT_BYTES) {
                                reported = have
                                setProgress(workDataOf(PawseModel.PROGRESS_BYTES to have))
                                runCatching { setForeground(foregroundInfo(have)) }
                            }
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        return part.length() == PawseModel.BYTES && digest.digest().joinToString("") { "%02x".format(it) } == ModelFile.SHA256
    }

    private fun foregroundInfo(bytes: Long): ForegroundInfo {
        val context = applicationContext
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, tr("Model download", "모델 다운로드"), NotificationManager.IMPORTANCE_LOW),
        )
        val cancel = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ModelCancelReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(tr("Downloading Pawse’s model", "Pawse 모델 다운로드 중"))
            .setContentText(PawseModel.downloaded(bytes))
            .setProgress(PROGRESS_MAX, (bytes * PROGRESS_MAX / PawseModel.BYTES).toInt(), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, tr("Cancel", "취소"), cancel)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private companion object {
        const val CHANNEL = "model_download"
        const val NOTIFICATION_ID = 2
        const val MAX_ATTEMPTS = 8
        const val TIMEOUT_MS = 30_000
        const val BUFFER = 1 shl 16
        const val REPORT_BYTES = 2_000_000L
        const val PROGRESS_MAX = 1000
    }
}

/** The notification's Cancel: the same as turning the switch off, so nothing is kept. */
class ModelCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val model = EntryPointAccessors.fromApplication(context, ModelEntryPoint::class.java).model()
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                model.turnOff()
            } finally {
                pending.finish()
            }
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ModelEntryPoint {
    fun model(): PawseModel
}
