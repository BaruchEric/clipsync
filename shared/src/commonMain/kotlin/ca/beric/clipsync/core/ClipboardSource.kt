package ca.beric.clipsync.core

/** Platform clipboard handle. [changeToken] must be cheap; the reads may be expensive. */
interface ClipboardSource {
    fun changeToken(): Long
    fun readText(): String?

    /** Current clipboard image, if any. Default: none (platforms that don't support it). */
    fun readImage(): Clip.Image? = null
}
