package ca.beric.clipsync.core

import ca.beric.clipsync.sync.DesktopClipboardApplier
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real NSPasteboard integration — runs on macOS only (M5 CI uses a macos runner). */
class MacPasteboardTest {

    // Real macOS pasteboard + AWT; skipped off-macOS and on headless CI runners.
    private fun assumeMac(): Boolean =
        System.getProperty("os.name").lowercase().contains("mac") && System.getenv("CI") == null

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

    @Test
    fun imageRoundTripsThroughThePasteboard() {
        if (!assumeMac()) return
        // Write a known image to the pasteboard via the applier, then read it back.
        val original = BufferedImage(40, 30, BufferedImage.TYPE_INT_ARGB).apply {
            for (x in 0 until 40) for (y in 0 until 30) setRGB(x, y, 0xFF3366CC.toInt())
        }
        val png = ByteArrayOutputStream().also { ImageIO.write(original, "png", it) }.toByteArray()

        DesktopClipboardApplier().applyImage(png, "image/png")
        Thread.sleep(300) // AWT → NSPasteboard write is asynchronous

        val read = MacPasteboard().readImage()
        assertNotNull(read, "expected an image on the pasteboard")
        val decoded = ImageIO.read(ByteArrayInputStream(read.bytes))
        assertNotNull(decoded, "read bytes should decode as an image")
        assertEquals(40, decoded.width)
        assertEquals(30, decoded.height)
    }
}
