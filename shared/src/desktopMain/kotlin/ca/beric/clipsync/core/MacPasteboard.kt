package ca.beric.clipsync.core

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

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
}
