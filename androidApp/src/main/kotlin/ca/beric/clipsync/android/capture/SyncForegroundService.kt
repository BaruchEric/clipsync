package ca.beric.clipsync.android.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import ca.beric.clipsync.android.AppGraph
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the process alive with a persistent notification. From M4 this
 * service hosts the sync engine (mDNS + WebSocket connections).
 *
 * DIAGNOSTIC (M2): also polls the clipboard every 2s and logs whether the
 * read succeeded, to determine empirically whether a foreground service can
 * read the clipboard while the app is backgrounded on Android 15.
 */
class SyncForegroundService : Service() {

    private var pollJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(applicationContext)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "clipsync sync engine", NotificationManager.IMPORTANCE_MIN),
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("clipsync is running")
            .setContentText("Syncing your clipboard only to your own paired devices.")
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        Log.i(TAG, "SyncForegroundService started (BUILD_ID=$BUILD_ID)")
        startPolling()
    }

    private fun startPolling() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        pollJob = AppGraph.scope.launch {
            while (isActive) {
                delay(2000)
                try {
                    val clip = clipboard.primaryClip
                    val text = clip?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)?.coerceToText(this@SyncForegroundService)?.toString()
                    Log.i(TAG, "poll read: text=${text?.take(24)} (null=${text == null})")
                } catch (t: Throwable) {
                    Log.w(TAG, "poll read threw: ${t.javaClass.simpleName}: ${t.message}")
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
        const val BUILD_ID = "m2-diag-1"
        private const val TAG = "clipsyncFg"
        private const val CHANNEL_ID = "clipsync"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SyncForegroundService::class.java))
        }
    }
}
