package ca.beric.clipsync.android.capture

import android.util.Log
import ca.beric.clipsync.sync.ClipboardApplier

/** Writes received text to the Android clipboard via Shizuku (background writes are focus-gated). */
class AndroidClipboardApplier(private val shizuku: ShizukuClipboard) : ClipboardApplier {
    override fun applyText(text: String) {
        val ok = shizuku.setText(text)
        // Distinguishes "sync arrived but the write failed" from "sync never happened".
        Log.i(TAG, "applyText len=${text.length} ok=$ok")
    }

    override fun applyImage(bytes: ByteArray, mime: String) {
        // Android clipboard images are content:// URIs backed by a ContentProvider, not raw
        // bytes; writing one through Shizuku's shell-uid binder is a separate problem (URI
        // grants) not yet solved. Received images are dropped on Android for now.
        Log.w(TAG, "applyImage ignored ($mime, ${bytes.size} bytes) - not supported on Android yet")
    }

    private companion object {
        const val TAG = "clipsyncApply"
    }
}
