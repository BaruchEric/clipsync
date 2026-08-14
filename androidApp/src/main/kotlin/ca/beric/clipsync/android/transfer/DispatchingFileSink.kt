package ca.beric.clipsync.android.transfer

import ca.beric.clipsync.browse.FileBridge
import ca.beric.clipsync.crypto.ClipsyncCrypto
import ca.beric.clipsync.transfer.FileSink
import ca.beric.clipsync.transfer.PendingFile
import java.io.IOException
import java.io.OutputStream

/**
 * Routes an inbound file by whether the sender named a destination. No destination (every
 * share-sheet send, every pre-0.4 peer) keeps the M6 behavior exactly: MediaStore
 * Download/clipsync. A destination goes through the confined [FileBridge], so a browse push
 * cannot write anywhere BrowseEngine would not have let it read.
 */
class DispatchingFileSink(
    private val mediaStore: FileSink,
    private val bridge: FileBridge,
    private val confine: (String) -> String?,
) : FileSink {

    override fun begin(name: String, mime: String, dest: String): PendingFile {
        if (dest.isBlank()) return mediaStore.begin(name, mime)
        val dir = confine(dest) ?: throw IOException("destination rejected: $dest")
        // Unique per receive: create() uses O_EXCL, so a fixed temp name left behind by an
        // interrupted push would block every retry of that filename permanently.
        val temp = "$dir/.clipsync-recv-${ClipsyncCrypto.toHex(ClipsyncCrypto.randomBytes(8))}.part"
        val target = claim(dir, name)
        val out = bridge.create(temp)
        return object : PendingFile {
            override val stream: OutputStream = out

            override fun publish(): String {
                out.close()
                if (!bridge.move(temp, target)) {
                    bridge.delete(temp)
                    throw IOException("could not move received file into $target")
                }
                return target
            }

            override fun discard() {
                runCatching { out.close() }
                bridge.delete(temp)
            }
        }
    }

    /** First free "name", "name (1)", … so a push never silently overwrites. */
    private fun claim(dir: String, name: String): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 0
        while (true) {
            val candidate = if (n == 0) "$dir/$name" else "$dir/$base ($n)$ext"
            if (!bridge.exists(candidate)) return candidate
            n++
        }
    }
}
