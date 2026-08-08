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
    fun changes(): Flow<String> = flow {
        var lastToken = source.changeToken()
        while (currentCoroutineContext().isActive) {
            delay(pollIntervalMs)
            val token = source.changeToken()
            if (token != lastToken) {
                lastToken = token
                source.readText()?.let { emit(it) }
            }
        }
    }
}
