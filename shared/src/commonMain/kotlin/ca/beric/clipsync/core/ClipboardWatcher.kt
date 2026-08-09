package ca.beric.clipsync.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

class ClipboardWatcher(
    private val source: ClipboardSource,
    private val pollIntervalMs: Long = 300,
) {
    /** Emits each new clipboard value. Text takes precedence; otherwise an image, if present. */
    fun changes(): Flow<Clip> = flow {
        var lastToken = source.changeToken()
        while (currentCoroutineContext().isActive) {
            delay(pollIntervalMs)
            val token = source.changeToken()
            if (token != lastToken) {
                lastToken = token
                val text = source.readText()
                if (text != null) emit(Clip.Text(text)) else source.readImage()?.let { emit(it) }
            }
        }
    }
}
