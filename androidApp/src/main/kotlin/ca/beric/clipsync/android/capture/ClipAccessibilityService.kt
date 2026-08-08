package ca.beric.clipsync.android.capture

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import ca.beric.clipsync.android.AppGraph
import ca.beric.clipsync.core.LOCAL_DEVICE_ID
import kotlinx.coroutines.launch

/**
 * Clipboard capture. An enabled accessibility service is exempt from the
 * Android 10+ background clipboard-read restriction, which is the entire
 * reason this service exists. It reads NOTHING except the clipboard:
 * no accessibility events are processed and window-content retrieval is
 * disabled in the service config.
 */
class ClipAccessibilityService : AccessibilityService() {

    private var clipboard: ClipboardManager? = null
    private val listener = ClipboardManager.OnPrimaryClipChangedListener { captureClip() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppGraph.init(applicationContext)
        clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.addPrimaryClipChangedListener(listener)
        SyncForegroundService.start(this)
        Log.i(TAG, "onServiceConnected: clipboard listener registered")
    }

    private fun captureClip() {
        val clip = clipboard?.primaryClip
        if (clip == null) {
            Log.w(TAG, "onPrimaryClipChanged fired but primaryClip was null (background read blocked?)")
            return
        }
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this)?.toString()
        Log.i(TAG, "onPrimaryClipChanged fired; text=${text?.take(20)}")
        if (text.isNullOrBlank()) return
        AppGraph.scope.launch {
            AppGraph.repo.record(LOCAL_DEVICE_ID, text, System.currentTimeMillis())
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        Log.i(TAG, "onAccessibilityEvent received: type=${event?.eventType} pkg=${event?.packageName}")
        try {
            val clip = clipboard?.primaryClip
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            Log.i(TAG, "  clipboard read ok: text=${text?.take(20)} (null=${text == null})")
        } catch (t: Throwable) {
            Log.w(TAG, "  clipboard read threw: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        clipboard?.removePrimaryClipChangedListener(listener)
        super.onDestroy()
    }

    private companion object {
        const val TAG = "clipsyncCapture"
    }
}
