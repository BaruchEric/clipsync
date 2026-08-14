package ca.beric.clipsync.transfer

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Bytes to send. [open] must be re-invokable: the engine reads the file twice (once to hash
 * for the offer, once to stream), so it needs a fresh stream each time — File and
 * ContentResolver sources both satisfy that.
 */
class FileSource(
    val name: String,
    val size: Long,
    val mime: String,
    val open: () -> InputStream,
)

/** Platform destination for received files (desktop: a folder; Android: MediaStore Downloads). */
interface FileSink {
    /**
     * Begins a pending receive under [name] (already sanitized by the engine). [dest] is the
     * sender's requested absolute directory (M9 push) — implementations that cannot or must
     * not honor it ignore it. The desktop always ignores it: a peer never steers a write here.
     */
    fun begin(name: String, mime: String, dest: String = ""): PendingFile
}

/** An in-progress received file: stream bytes in, then publish or discard. */
interface PendingFile {
    val stream: OutputStream

    /** Closes the stream and makes the completed file visible; returns its user-facing location. */
    fun publish(): String

    /** Deletes anything written so far; safe to call at any point after a failure. */
    fun discard()
}

/** Live status of one transfer, for the UIs. */
data class TransferState(
    val id: String,
    val peerDeviceId: String,
    val name: String,
    val sizeBytes: Long,
    val outbound: Boolean,
    val transferredBytes: Long = 0,
    val status: Status = Status.ACTIVE,
    /** Final location when DONE; failure reason when FAILED. */
    val detail: String? = null,
) {
    enum class Status { ACTIVE, DONE, FAILED }
}

/**
 * Receives files into a plain directory: bytes stream to a hidden ".part" temp file that is
 * renamed into place only after the engine's integrity check passes. Used by the desktop app
 * (~/Downloads/clipsync) and by tests.
 */
class FolderFileSink(private val dir: File) : FileSink {

    override fun begin(name: String, mime: String, dest: String): PendingFile {
        dir.mkdirs()
        val temp = File.createTempFile(".clipsync-recv-", ".part", dir)
        val out = temp.outputStream().buffered()
        return object : PendingFile {
            override val stream: OutputStream = out

            override fun publish(): String {
                out.close()
                val target = claimTarget(name)
                if (!temp.renameTo(target)) {
                    temp.delete()
                    target.delete()
                    throw IOException("could not move received file into $target")
                }
                return target.absolutePath
            }

            override fun discard() {
                runCatching { out.close() }
                temp.delete()
            }
        }
    }

    /** First free name — "name.ext", then "name (1).ext", … — claimed via createNewFile. */
    private fun claimTarget(name: String): File {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 0
        while (true) {
            val candidate = File(dir, if (n == 0) name else "$base ($n)$ext")
            if (candidate.createNewFile()) return candidate
            n++
        }
    }
}
