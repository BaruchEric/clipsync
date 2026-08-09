package ca.beric.clipsync.core

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * NSPasteboard changeCount via the Objective-C runtime (cheap per-tick check);
 * content is read through AWT, which bridges to the same pasteboard.
 */
class MacPasteboard : ClipboardSource {

    private interface ObjCRuntime : Library {
        fun objc_getClass(name: String): Pointer
        fun sel_registerName(name: String): Pointer
        fun objc_msgSend(receiver: Pointer, selector: Pointer): Long
    }

    private val objc: ObjCRuntime = Native.load("objc", ObjCRuntime::class.java)
    private val nsPasteboardClass = objc.objc_getClass("NSPasteboard")
    private val generalPasteboardSel = objc.sel_registerName("generalPasteboard")
    private val changeCountSel = objc.sel_registerName("changeCount")

    override fun changeToken(): Long {
        val pasteboard = Pointer(objc.objc_msgSend(nsPasteboardClass, generalPasteboardSel))
        return objc.objc_msgSend(pasteboard, changeCountSel)
    }

    override fun readText(): String? =
        runCatching {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                clipboard.getData(DataFlavor.stringFlavor) as? String
            } else null
        }.getOrNull()

    override fun readImage(): Clip.Image? =
        runCatching {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) return null
            val image = clipboard.getData(DataFlavor.imageFlavor) as? Image ?: return null
            val buffered = image as? BufferedImage ?: image.toBuffered()
            val out = ByteArrayOutputStream()
            ImageIO.write(buffered, "png", out)
            Clip.Image(out.toByteArray(), "image/png")
        }.getOrNull()

    private fun Image.toBuffered(): BufferedImage {
        val w = getWidth(null).coerceAtLeast(1)
        val h = getHeight(null).coerceAtLeast(1)
        val buffered = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = buffered.createGraphics()
        g.drawImage(this, 0, 0, null)
        g.dispose()
        return buffered
    }
}
