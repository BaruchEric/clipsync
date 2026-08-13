package ca.beric.clipsync.android.capture

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import ca.beric.clipsync.android.AppGraph
import ca.beric.clipsync.protocol.MirrorEvent

/**
 * Mirrors phone notifications to paired desktops (M7). Dormant until the user grants
 * notification access in Settings — the rest of the app never depends on it.
 *
 * Own-package notifications are skipped so the persistent sync notification and
 * "file received" posts can't loop back — except the [TEST_TAG] one, which is this
 * feature's own harness hook (a synthetic RemoteInput notification the reply path can
 * be verified against without touching a real conversation).
 */
class NotifMirrorService : NotificationListenerService() {

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (sbn.packageName == packageName && sbn.tag != TEST_TAG) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (
            extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            )?.toString().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        val app = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        val replyAction = n.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
        if (replyAction != null) {
            synchronized(replyActions) {
                replyActions[sbn.key] = replyAction
                while (replyActions.size > MAX_REPLY_ACTIONS) {
                    replyActions.remove(replyActions.keys.first())
                }
            }
        }
        AppGraph.mirrorNotification(
            MirrorEvent.NotifPosted(sbn.key, app, title, text, sbn.postTime, replyAction != null),
        )
    }

    companion object {
        const val TEST_TAG = "clipsync-mirror-test"
        private const val TAG = "clipsyncNotif"
        private const val MAX_REPLY_ACTIONS = 64

        /** Keyed by notification key; service-lifetime, bounded. */
        private val replyActions = LinkedHashMap<String, Notification.Action>()

        /** Sends [text] through the RemoteInput of the notification posted under [key]. */
        fun sendReply(context: Context, key: String, text: String): Boolean {
            val action = synchronized(replyActions) { replyActions[key] } ?: return false
            val remoteInputs = action.remoteInputs ?: return false
            return runCatching {
                val fillIn = Intent()
                val results = Bundle()
                remoteInputs.forEach { results.putCharSequence(it.resultKey, text) }
                RemoteInput.addResultsToIntent(remoteInputs, fillIn, results)
                action.actionIntent.send(context, 0, fillIn)
                true
            }.onFailure { Log.w(TAG, "reply via RemoteInput failed: ${it.message}") }.getOrDefault(false)
        }
    }
}
