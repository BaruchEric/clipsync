package ca.beric.clipsync.core

/** Platform clipboard handle. [changeToken] must be cheap; [readText] may be expensive. */
interface ClipboardSource {
    fun changeToken(): Long
    fun readText(): String?
}
