package ca.beric.clipsync.browse

import ca.beric.clipsync.protocol.FsEntry
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URLConnection
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

/**
 * The only way [BrowseEngine] touches storage. Paths are absolute and already confined by the
 * engine — a bridge does no policy of its own. Implementations: [JvmFileBridge] (desktop and
 * tests, plain java.io) and the Android Shizuku SHELL-uid service.
 *
 * [open] MUST be re-invokable: FileTransferEngine reads a source twice (hash, then stream).
 */
interface FileBridge {
    /**
     * Fully resolved path — symlinks and `..` collapsed — or **null when resolution failed**.
     * Null is load-bearing: [BrowseEngine] confines by comparing canonical paths, and an
     * unresolved path is not safely comparable (a raw string can still start with the root
     * prefix while containing `..`). Callers must treat null as deny, never as fall-through.
     */
    fun canonical(path: String): String?
    fun list(dir: String): List<FsEntry>
    fun stat(path: String): FsEntry?
    fun exists(path: String): Boolean
    fun open(path: String): InputStream

    /**
     * Opens [path] for writing a new file. Must refuse to create through a symlink and must
     * refuse to overwrite an existing file: [path] doesn't exist at the moment the caller
     * confines it, but a symlink planted in the window between that check and this call would
     * otherwise be followed, redirecting the write anywhere the process can write.
     */
    fun create(path: String): OutputStream
    fun move(from: String, to: String): Boolean
    fun delete(path: String): Boolean
    fun mkdirs(path: String): Boolean
}

/** Plain java.io implementation: the desktop's own filesystem, and every unit test. */
class JvmFileBridge : FileBridge {

    override fun canonical(path: String): String? =
        runCatching { File(path).canonicalPath }.getOrNull()

    override fun list(dir: String): List<FsEntry> =
        File(dir).listFiles()?.map { it.toEntry() } ?: emptyList()

    override fun stat(path: String): FsEntry? = File(path).takeIf { it.exists() }?.toEntry()

    override fun exists(path: String): Boolean = File(path).exists()

    override fun open(path: String): InputStream = File(path).inputStream().buffered()

    override fun create(path: String): OutputStream =
        Files.newOutputStream(
            File(path).toPath(),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).buffered()

    override fun move(from: String, to: String): Boolean =
        runCatching { File(from).renameTo(File(to)) }.getOrDefault(false)

    override fun delete(path: String): Boolean = runCatching { File(path).deleteRecursively() }.getOrDefault(false)

    override fun mkdirs(path: String): Boolean = File(path).let { it.isDirectory || it.mkdirs() }

    private fun File.toEntry() = FsEntry(
        name = name,
        size = if (isDirectory) 0L else length(),
        dir = isDirectory,
        mime = if (isDirectory) "" else guessMime(name),
    )

    companion object {
        /** Best-effort, extension-based. A wrong mime never breaks a transfer; bytes are bytes. */
        fun guessMime(name: String): String =
            URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
    }
}
