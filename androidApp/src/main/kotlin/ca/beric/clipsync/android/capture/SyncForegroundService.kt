package ca.beric.clipsync.android.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import ca.beric.clipsync.android.AppGraph
import ca.beric.clipsync.core.LOCAL_DEVICE_ID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Hosts clipboard capture (via Shizuku) and, from M4, the sync engine. Runs as a
 * persistent foreground service so the process survives while backgrounded.
 *
 * Capture polls the clipboard through [ShizukuClipboard] (~1 s). Shizuku's
 * shell-uid identity is what makes the background read succeed on Android 10+.
 */
class SyncForegroundService : Service() {

    private var pollJob: Job? = null
    private lateinit var shizukuClipboard: ShizukuClipboard

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(applicationContext)
        shizukuClipboard = ShizukuClipboard(applicationContext)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "clipsync sync engine", NotificationManager.IMPORTANCE_MIN),
        )
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        Log.i(TAG, "SyncForegroundService started (BUILD_ID=$BUILD_ID)")
        startPolling()
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("clipsync is running")
            .setContentText("Syncing your clipboard only to your own paired devices.")
            .build()

    private fun startPolling() {
        pollJob = AppGraph.scope.launch {
            var wasReady = false
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                val ready = shizukuClipboard.isReady()
                if (ready != wasReady) {
                    Log.i(TAG, "Shizuku capture ${if (ready) "active" else "unavailable"}")
                    wasReady = ready
                }
                if (!ready) continue
                val text = shizukuClipboard.readText()
                if (!text.isNullOrBlank()) {
                    AppGraph.repo.record(LOCAL_DEVICE_ID, text, System.currentTimeMillis())
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pollJob?.cancel()
        super.onDestroy()
    }

    companion object {
        const val BUILD_ID = "m2-shizuku-2"
        private const val TAG = "clipsyncFg"
        private const val CHANNEL_ID = "clipsync"
        private const val NOTIFICATION_ID = 1
        private const val POLL_INTERVAL_MS = 1000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SyncForegroundService::class.java))
        }
    }
}
