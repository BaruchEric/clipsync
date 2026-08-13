package ca.beric.clipsync.android.capture

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Sink for the reply-test notification: logs the RemoteInput length, never the content. */
class TestReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(RESULT_KEY)?.toString()
        Log.i("clipsyncNotif", "test-reply received len=${text?.length ?: -1}")
    }

    companion object {
        const val RESULT_KEY = "reply"
    }
}
