package ca.beric.clipsync.android.capture

import android.util.Log
import ca.beric.clipsync.sync.ClipboardApplier
import ca.beric.clipsync.transfer.FileSink

/** Writes received text to the Android clipboard via Shizuku (background writes are focus-gated). */
class AndroidClipboardApplier(
    private val shizuku: ShizukuClipboard,
    private val imageSink: FileSink? = null,
    private val onImageSaved: ((name: String) -> Unit)? = null,
) : ClipboardApplier {
    override fun applyText(text: String) {
        val ok = shizuku.setText(text)
        // Distinguishes "sync arrived but the write failed" from "sync never happened".
        Log.i(TAG, "applyText len=${text.length} ok=$ok")
    }

    override fun applyImage(bytes: ByteArray, mime: String) {
        // Putting an image on the Android clipboard would mean a content: URI whose read
        // grant must flow to whatever app pastes — through a shell-uid setPrimaryClip that
        // grant never materializes. The useful behavior is the file-drop one: the image
        // lands in Download/clipsync with a notification, like a received file.
        val sink = imageSink ?: run {
            Log.w(TAG, "applyImage ignored ($mime, ${bytes.size} bytes) - no sink wired")
            return
        }
        val name = "clipboard-${System.currentTimeMillis()}." + if (mime.contains("jpeg")) "jpg" else "png"
        val result = runCatching {
            val pending = sink.begin(name, mime)
            runCatching {
                pending.stream.write(bytes)
                pending.publish()
            }.onFailure { pending.discard() }.getOrThrow()
        }
        Log.i(TAG, "applyImage $mime ${bytes.size}B -> $name ok=${result.isSuccess}")
        if (result.isSuccess) onImageSaved?.invoke(name)
    }

    private companion object {
        const val TAG = "clipsyncApply"
    }
}
