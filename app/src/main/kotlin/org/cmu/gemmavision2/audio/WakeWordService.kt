package org.cmu.gemmavision2.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import ai.picovoice.porcupine.PorcupineManager
import org.cmu.gemmavision2.BuildConfig
import org.cmu.gemmavision2.R
import java.io.File

/**
 * Always-on wake-word service via Porcupine ("Hey Gemma").
 *
 * Runs as a foreground microphone service so Android keeps the mic open
 * even with the screen off. The custom keyword model lives in
 * assets/hey_gemma.ppn. Porcupine requires a filesystem path so we copy
 * the .ppn to filesDir on first launch.
 *
 * If [BuildConfig.PORCUPINE_ACCESS_KEY] is blank (developer hasn't set up
 * Picovoice yet) or any init step throws, we stop the service immediately
 * rather than leaving a permanent broken foreground notification.
 */
class WakeWordService : Service() {

    private var porcupine: PorcupineManager? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()

        if (BuildConfig.PORCUPINE_ACCESS_KEY.isBlank()) {
            Log.w(TAG, "PORCUPINE_ACCESS_KEY blank; wake-word disabled. Use gamepad/button.")
            stopSelfCleanly()
            return
        }
        try {
            val keywordPath = ensureKeywordFile().absolutePath
            porcupine = PorcupineManager.Builder()
                .setAccessKey(BuildConfig.PORCUPINE_ACCESS_KEY)
                .setKeywordPath(keywordPath)
                .setSensitivity(0.6f)
                .build(applicationContext) { _ ->
                    val broadcast = Intent(ACTION_WAKE_WORD_FIRED).setPackage(packageName)
                    sendBroadcast(broadcast)
                }
            porcupine?.start()
            Log.i(TAG, "Porcupine started")
        } catch (t: Throwable) {
            Log.e(TAG, "Porcupine init failed; wake-word disabled", t)
            stopSelfCleanly()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        try {
            porcupine?.stop()
            porcupine?.delete()
        } catch (_: Throwable) {}
        porcupine = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        ensureChannel()
        val notif = buildNotification()
        // 3-arg startForeground requires API 29+; on API 28 we use the 2-arg form.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun stopSelfCleanly() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.wake_service_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        // Notification.Builder(Context, channelId) is API 26+; we already
        // require channels above. For API < 26 the channelId is ignored.
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.wake_service_notification_title))
            .setContentText(getString(R.string.wake_service_notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    /**
     * Copy the bundled keyword file out of assets so PorcupineManager has
     * a real filesystem path. Returns the destination File.
     */
    private fun ensureKeywordFile(): File {
        val dest = File(filesDir, KEYWORD_ASSET)
        if (dest.exists() && dest.length() > 0) return dest
        val tmp = File(filesDir, "$KEYWORD_ASSET.tmp")
        assets.open(KEYWORD_ASSET).use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        if (!tmp.renameTo(dest)) {
            throw IllegalStateException("Failed to rename ${tmp.absolutePath} -> ${dest.absolutePath}")
        }
        return dest
    }

    companion object {
        const val ACTION_WAKE_WORD_FIRED = "org.cmu.gemmavision2.WAKE_WORD_FIRED"

        private const val TAG = "WakeWordService"
        private const val NOTIF_ID = 42
        private const val CHANNEL_ID = "gemmavision_wake"
        private const val KEYWORD_ASSET = "hey_gemma.ppn"
    }
}
