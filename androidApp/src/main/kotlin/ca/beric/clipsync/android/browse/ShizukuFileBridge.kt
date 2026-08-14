package ca.beric.clipsync.android.browse

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import ca.beric.clipsync.browse.FileBridge
import ca.beric.clipsync.browse.JvmFileBridge
import ca.beric.clipsync.protocol.FsEntry
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import rikka.shizuku.Shizuku

/**
 * [FileBridge] backed by [FileBridgeService] running as the SHELL uid. Every call fails
 * closed (empty / false / throw) when the service isn't bound, which is the same posture
 * clipboard capture already takes when Shizuku is stopped.
 */
class ShizukuFileBridge(context: Context) : FileBridge {

    private val appContext = context.applicationContext

    @Volatile
    private var service: IFileBridge? = null

    private val args = Shizuku.UserServiceArgs(
        ComponentName(appContext.packageName, FileBridgeService::class.java.name),
    ).daemon(false).processNameSuffix("filebridge").debuggable(false).version(SERVICE_VERSION)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.let { IFileBridge.Stub.asInterface(it) }
            Log.i(TAG, "file bridge bound=${service != null}")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            Log.i(TAG, "file bridge unbound")
        }
    }

    /** Idempotent; safe to call again after a Shizuku restart. */
    fun bind() {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return
        runCatching { Shizuku.bindUserService(args, connection) }
            .onFailure { Log.w(TAG, "bindUserService failed: ${it.message}") }
    }

    fun isReady(): Boolean = service != null

    // Null when the service isn't bound. Echoing the input path back would hand BrowseEngine
    // an unresolved string to confine against — the same bypass the JVM bridge avoids.
    override fun canonical(path: String): String? = service?.canonical(path)

    override fun list(dir: String): List<FsEntry> =
        service?.list(dir)?.mapNotNull { parseRow(it) } ?: emptyList()

    override fun stat(path: String): FsEntry? = service?.stat(path)?.let { parseRow(it) }

    override fun exists(path: String): Boolean = service?.exists(path) ?: false

    override fun open(path: String): InputStream {
        val fd = service?.open(path) ?: throw IOException("file bridge unavailable")
        return ParcelFileDescriptor.AutoCloseInputStream(fd).buffered()
    }

    override fun create(path: String): OutputStream {
        val fd = service?.create(path) ?: throw IOException("file bridge unavailable")
        return ParcelFileDescriptor.AutoCloseOutputStream(fd).buffered()
    }

    override fun move(from: String, to: String): Boolean = service?.move(from, to) ?: false

    override fun delete(path: String): Boolean = service?.delete(path) ?: false

    override fun mkdirs(path: String): Boolean = service?.mkdirs(path) ?: false

    /** "name\tsize\tdir\tmtime" — the service's wire row. */
    private fun parseRow(row: String): FsEntry? {
        val parts = row.split('\t')
        if (parts.size != 4) return null
        val dir = parts[2].toBoolean()
        return FsEntry(
            name = parts[0],
            size = parts[1].toLongOrNull() ?: 0L,
            dir = dir,
            mtimeMs = parts[3].toLongOrNull() ?: 0L,
            mime = if (dir) "" else JvmFileBridge.guessMime(parts[0]),
        )
    }

    companion object {
        private const val TAG = "clipsyncFiles"
        private const val SERVICE_VERSION = 1
    }
}
