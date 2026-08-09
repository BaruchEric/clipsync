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

/**
 * Persistent foreground service that hosts the sync engine so the process survives
 * while backgrounded. Capture (via Shizuku), pairing, and the TLS transport are all
 * wired in [AppGraph.startSync]; this service exists to keep that process alive and
 * to show the required persistent notification.
 */
class SyncForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(applicationContext)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "clipsync sync engine", NotificationManager.IMPORTANCE_MIN),
        )
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        Log.i(TAG, "SyncForegroundService started (BUILD_ID=$BUILD_ID)")
        AppGraph.startSync(applicationContext)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("clipsync is running")
            .setContentText("Syncing your clipboard only to your own paired devices.")
            .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val BUILD_ID = "m4-sync-1"
        private const val TAG = "clipsyncFg"
        private const val CHANNEL_ID = "clipsync"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SyncForegroundService::class.java))
        }
    }
}
