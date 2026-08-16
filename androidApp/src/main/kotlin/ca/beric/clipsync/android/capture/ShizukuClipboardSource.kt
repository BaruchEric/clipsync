package ca.beric.clipsync.android.capture

import ca.beric.clipsync.core.ClipboardSource

/**
 * Adapts [ShizukuClipboard] to [ClipboardSource] so capture runs through the shared,
 * change-gated [ca.beric.clipsync.core.ClipboardWatcher]. Android exposes no clipboard
 * change-count to the shell binder, so the token is derived from the content itself
 * (a 64-bit content hash) — that is what stops the poll loop from re-broadcasting an
 * unchanged clipboard every tick.
 *
 * When Shizuku is not ready the token is a constant sentinel, so no read is emitted
 * until capture is actually available.
 */
class ShizukuClipboardSource(private val shizuku: ShizukuClipboard) : ClipboardSource {

    override fun changeToken(): Long {
        if (!shizuku.isReady()) return NOT_READY
        // Text clips hash their content; URI (image) clips hash the URI — an image-only
        // clipboard used to collapse to the EMPTY sentinel and image copies went unseen.
        return shizuku.clipSignature() ?: EMPTY
    }

    override fun readText(): String? = shizuku.readText()

    override fun readImage(): ca.beric.clipsync.core.Clip.Image? =
        shizuku.readImage()?.let { (bytes, mime) -> ca.beric.clipsync.core.Clip.Image(bytes, mime) }

    private companion object {
        const val NOT_READY = Long.MIN_VALUE
        const val EMPTY = Long.MIN_VALUE + 1
    }
}
