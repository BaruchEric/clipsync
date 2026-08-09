package ca.beric.clipsync.sync

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/** Writes received text to the macOS pasteboard via AWT. */
class DesktopClipboardApplier : ClipboardApplier {
    override fun applyText(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}
