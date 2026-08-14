package ca.beric.clipsync.android.browse

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Runs inside a SHELL-uid process started by Shizuku, so plain java.io sees shared storage
 * that a scoped-storage app cannot. It carries no policy: paths arrive already confined by
 * BrowseEngine. Shizuku requires a no-arg or Context constructor.
 */
class FileBridgeService : IFileBridge.Stub() {

    override fun list(dir: String): Array<String> =
        File(dir).listFiles()?.map { it.row() }?.toTypedArray() ?: emptyArray()

    override fun stat(path: String): String? = File(path).takeIf { it.exists() }?.row()

    override fun exists(path: String): Boolean = File(path).exists()

    override fun canonical(path: String): String? =
        runCatching { File(path).canonicalPath }.getOrNull()

    override fun open(path: String): ParcelFileDescriptor? = runCatching {
        ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
    }.getOrNull()

    /**
     * O_CREAT|O_EXCL|O_NOFOLLOW in one syscall. A symlink planted at the destination between
     * confinement and write would otherwise redirect a received file — and this write runs as
     * the SHELL uid, so the blast radius is the whole filesystem. It must be one atomic open:
     * creating the file and then reopening it by path leaves the same race in a smaller window.
     * O_EXCL also makes "already exists" an error instead of a silent overwrite.
     */
    override fun create(path: String): ParcelFileDescriptor? = runCatching {
        File(path).parentFile?.mkdirs()
        val fd = Os.open(
            path,
            OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW,
            DEFAULT_FILE_MODE,
        )
        // try/finally, not .also{}: if dup() itself throws (EMFILE/ENFILE), .also never runs
        // and the raw fd leaks.
        try {
            ParcelFileDescriptor.dup(fd)
        } finally {
            Os.close(fd)
        }
    }.getOrNull()

    override fun move(from: String, to: String): Boolean =
        runCatching { File(from).renameTo(File(to)) }.getOrDefault(false)

    override fun delete(path: String): Boolean = runCatching { File(path).deleteRecursively() }.getOrDefault(false)

    override fun mkdirs(path: String): Boolean = File(path).let { it.isDirectory || it.mkdirs() }

    /** size ‖ dir ‖ mtime ‖ name — name last so a tab inside it cannot shift the other fields. */
    private fun File.row(): String =
        listOf(if (isDirectory) 0L else length(), isDirectory, lastModified(), name).joinToString("\t")

    private companion object {
        /** rw-rw---- : the shell uid writes, the media scanner's group reads. */
        const val DEFAULT_FILE_MODE = 432 // 0660
    }
}
