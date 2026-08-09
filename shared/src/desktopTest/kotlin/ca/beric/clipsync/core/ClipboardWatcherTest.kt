package ca.beric.clipsync.core

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class FakeSource : ClipboardSource {
    var token = 1L
    var text: String? = null
    override fun changeToken(): Long = token
    override fun readText(): String? = text

    fun copy(newText: String?) { token += 1; text = newText }
}

class ClipboardWatcherTest {

    private fun runWatcher(
        source: FakeSource,
        totalMs: Long,
        script: FakeSource.(advanceTo: (Long) -> Unit) -> Unit,
    ): List<Clip> {
        val emitted = mutableListOf<Clip>()
        runTest {
            val watcher = ClipboardWatcher(source, pollIntervalMs = 100)
            val job = launch { watcher.changes().toList(emitted) }
            testScheduler.runCurrent()
            var now = 0L
            source.script { target ->
                testScheduler.advanceTimeBy(target - now)
                testScheduler.runCurrent()
                now = target
            }
            testScheduler.advanceTimeBy(totalMs - now)
            testScheduler.runCurrent()
            job.cancelAndJoin()
        }
        return emitted
    }

    @Test
    fun emitsOnTokenChange() {
        val source = FakeSource()
        val emitted = runWatcher(source, totalMs = 500) { advanceTo ->
            advanceTo(150)
            copy("hello")
        }
        assertEquals(listOf<Clip>(Clip.Text("hello")), emitted)
    }

    @Test
    fun doesNotEmitPreexistingContentOrUnchangedToken() {
        val source = FakeSource().apply { text = "already-there" }
        val emitted = runWatcher(source, totalMs = 500) { }
        assertEquals(emptyList<Clip>(), emitted)
    }

    @Test
    fun skipsNullReadsButEmitsSubsequentText() {
        val source = FakeSource()
        val emitted = runWatcher(source, totalMs = 800) { advanceTo ->
            advanceTo(150)
            copy(null)          // e.g. an image on the clipboard
            advanceTo(350)
            copy("text-after")
        }
        assertEquals(listOf<Clip>(Clip.Text("text-after")), emitted)
    }
}
