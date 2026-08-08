package ca.beric.clipsync.core

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real NSPasteboard integration — runs on macOS only (M5 CI uses a macos runner). */
class MacPasteboardTest {

    private fun assumeMac(): Boolean =
        System.getProperty("os.name").lowercase().contains("mac")

    @Test
    fun changeTokenIncrementsWhenClipboardWritten() {
        if (!assumeMac()) return
        val pb = MacPasteboard()
        val before = pb.changeToken()
        assertTrue(before > 0)
        Toolkit.getDefaultToolkit().systemClipboard
            .setContents(StringSelection("clipsync-test-${System.nanoTime()}"), null)
        Thread.sleep(200) // AWT->NSPasteboard write is asynchronous
        assertTrue(pb.changeToken() > before)
    }

    @Test
    fun readTextReturnsWhatWasWritten() {
        if (!assumeMac()) return
        val pb = MacPasteboard()
        val expected = "clipsync-read-${System.nanoTime()}"
        Toolkit.getDefaultToolkit().systemClipboard
            .setContents(StringSelection(expected), null)
        Thread.sleep(200)
        assertEquals(expected, pb.readText())
    }
}
