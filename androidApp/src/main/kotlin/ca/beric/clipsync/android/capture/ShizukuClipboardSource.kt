package ca.beric.clipsync.android.capture

import ca.beric.clipsync.core.ClipboardSource

/**
 * Adapts [ShizukuClipboard] to [ClipboardSource] so capture runs through the shared,
 * change-gated [ca.beric.clipsync.core.ClipboardWatcher]. Android exposes no clipboard
 * change-count to the shell binder, so the token is derived from the content itself
 * (length + hash) — that is what stops the poll loop from re-broadcasting an unchanged
 * clipboard every tick.
 *
 * When Shizuku is not ready the token is a constant sentinel, so no read is emitted
 * until capture is actually available.
 */
class ShizukuClipboardSource(private val shizuku: ShizukuClipboard) : ClipboardSource {

    override fun changeToken(): Long {
        if (!shizuku.isReady()) return NOT_READY
        val text = shizuku.readText() ?: return EMPTY
        // length in the high bits, hash in the low bits — collisions on real text are negligible.
        return (text.length.toLong() shl 32) xor (text.hashCode().toLong() and 0xFFFFFFFFL)
    }

    override fun readText(): String? = shizuku.readText()

    private companion object {
        const val NOT_READY = Long.MIN_VALUE
        const val EMPTY = Long.MIN_VALUE + 1
    }
}
