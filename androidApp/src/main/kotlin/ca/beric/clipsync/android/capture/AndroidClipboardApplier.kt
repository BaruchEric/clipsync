package ca.beric.clipsync.android.capture

import ca.beric.clipsync.sync.ClipboardApplier

/** Writes received text to the Android clipboard via Shizuku (background writes are focus-gated). */
class AndroidClipboardApplier(private val shizuku: ShizukuClipboard) : ClipboardApplier {
    override fun applyText(text: String) {
        shizuku.setText(text)
    }
}
