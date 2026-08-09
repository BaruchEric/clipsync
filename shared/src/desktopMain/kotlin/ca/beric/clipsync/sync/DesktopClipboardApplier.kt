package ca.beric.clipsync.sync

import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** Writes received values to the macOS pasteboard via AWT (text and images). */
class DesktopClipboardApplier : ClipboardApplier {
    override fun applyText(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    override fun applyImage(bytes: ByteArray, mime: String) {
        val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return
        val transferable = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
            override fun getTransferData(flavor: DataFlavor): Image =
                if (flavor == DataFlavor.imageFlavor) image else throw UnsupportedFlavorException(flavor)
        }
        Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null)
    }
}
