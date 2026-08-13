package ca.beric.clipsync.android.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import ca.beric.clipsync.protocol.SmsMessage
import ca.beric.clipsync.protocol.SmsThread

/**
 * Read + send SMS for the desktop Messages view (M8). Address-only on purpose: contact
 * names would need READ_CONTACTS, which stays off the permission surface. Threads are
 * derived from the newest [SCAN_ROWS] rows of the provider, so counts are "recent
 * messages", not all-time totals. MMS/RCS are out of scope (documented).
 */
class SmsBridge(private val context: Context) {

    fun hasPermissions(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /** Newest-first conversation summaries derived from the latest [SCAN_ROWS] messages. */
    fun threads(limit: Int = 30): List<SmsThread> {
        val byThread = LinkedHashMap<Long, MutableList<Triple<String, String, Long>>>()
        query(Telephony.Sms.CONTENT_URI, "date DESC LIMIT $SCAN_ROWS") { c ->
            val threadId = c.getLong(0)
            byThread.getOrPut(threadId) { mutableListOf() } +=
                Triple(c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getLong(3))
        }
        return byThread.entries.take(limit).map { (id, msgs) ->
            val newest = msgs.first()
            SmsThread(id, newest.first, newest.second.take(80), newest.third, msgs.size)
        }
    }

    /** The last [limit] messages of one thread, oldest first. */
    fun messages(threadId: Long, limit: Int = 50): List<SmsMessage> {
        val out = mutableListOf<SmsMessage>()
        query(Telephony.Sms.CONTENT_URI, "date DESC LIMIT $limit", "thread_id = ?", arrayOf(threadId.toString())) { c ->
            out += SmsMessage(
                address = c.getString(1).orEmpty(),
                body = c.getString(2).orEmpty(),
                dateMs = c.getLong(3),
                outbound = c.getInt(4) != Telephony.Sms.MESSAGE_TYPE_INBOX,
            )
        }
        return out.asReversed()
    }

    /** Sends one SMS (multipart when needed). True means handed to the radio, not delivered. */
    fun send(to: String, body: String): Boolean = runCatching {
        val sms = context.getSystemService(SmsManager::class.java)
        val parts = sms.divideMessage(body)
        if (parts.size > 1) {
            sms.sendMultipartTextMessage(to, null, parts, null, null)
        } else {
            sms.sendTextMessage(to, null, body, null, null)
        }
        true
    }.onFailure { Log.w(TAG, "sms send failed: ${it.javaClass.simpleName}: ${it.message}") }
        .getOrDefault(false)

    /**
     * Fires [onChanged] on every SMS provider change; caller debounces. Registered on
     * content://mms-sms — SmsProvider posts its notifyChange there, not on content://sms
     * (verified on Android 16: an sms-table insert never pinged a content://sms observer) —
     * plus content://sms for providers that do notify it.
     */
    fun observe(onChanged: () -> Unit) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                Log.i(TAG, "provider change")
                onChanged()
            }
        }
        context.contentResolver.registerContentObserver(Telephony.MmsSms.CONTENT_URI, true, observer)
        context.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
    }

    private inline fun query(
        uri: Uri,
        sortOrder: String,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        onRow: (android.database.Cursor) -> Unit,
    ) {
        runCatching {
            context.contentResolver.query(uri, PROJECTION, selection, selectionArgs, sortOrder)?.use { c ->
                while (c.moveToNext()) onRow(c)
            }
        }.onFailure { Log.w(TAG, "sms query failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "clipsyncSms"
        private const val SCAN_ROWS = 500
        private val PROJECTION = arrayOf(
            Telephony.Sms.THREAD_ID, // 0
            Telephony.Sms.ADDRESS, // 1
            Telephony.Sms.BODY, // 2
            Telephony.Sms.DATE, // 3
            Telephony.Sms.TYPE, // 4
        )
    }
}
