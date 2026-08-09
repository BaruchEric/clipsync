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

    private companion object {
        const val TAG = "clipsyncApply"
    }
}
