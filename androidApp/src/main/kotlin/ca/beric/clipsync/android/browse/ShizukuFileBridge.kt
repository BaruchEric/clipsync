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
 * closed (empty / false / throw) when the service isn't bound, and equally when a bound
 * call throws — the binder can die between a call and the disconnect callback landing,
 * which is routine (Shizuku restarts on every phone reboot). Same posture clipboard
 * capture already takes when Shizuku is stopped.
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

    /**
     * Idempotent; safe to call again after a Shizuku restart.
     *
     * **Unbinds before it binds**, and that ordering is the fix for a real leak measured on the
     * S24 (0.4.1, 2026-08-15): `bindUserService` does *not* reuse a helper whose client process
     * has died, so every app restart stood up a new SHELL-uid process beside the old one —
     * 2 → 3 → 4 across two force-stop/start cycles, unbounded, each orphan a privileged process
     * with whole-filesystem reach and no client. `.daemon(false)` cannot prevent it: a killed
     * process never gets to run an unbind. The reap therefore has to happen on the way *in*,
     * from the new process.
     *
     * That this works cross-process is not an assumption — it is what the API actually does
     * (verified against the `dev.rikka.shizuku:api:13.1.5` bytecode, since it decides whether
     * this fix is real or inert). With `remove = true`, `unbindUserService` calls
     * `IShizukuService.removeUserService(null, args.forRemove(true))`: the connection it sends
     * is literally **null**, and the Bundle carries only the component, the tag, and the remove
     * flag. So the server identifies the doomed service by **ComponentName**, which is stable
     * across processes — not by the [ServiceConnection] instance, which is per-process and
     * would make a new process unable to reap an old one. (The client-side connection cache is
     * keyed the same way, `tag ?: componentName.className`.) Note the remove Bundle carries no
     * version code, so this reaps whatever instance exists for the component, which is what a
     * leak-sweep wants.
     *
     * Both calls are wrapped: with no previous instance the unbind is a no-op, and its failure
     * must never stop the bind that follows.
     */
    fun bind() {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return
        runCatching { Shizuku.unbindUserService(args, connection, /* remove = */ true) }
        runCatching { Shizuku.bindUserService(args, connection) }
            .onFailure { Log.w(TAG, "bindUserService failed: ${it.message}") }
    }

    /**
     * Stops the helper when consent is withdrawn. Not a security boundary — [BrowseEngine]
     * already refuses every request while the toggle is off, before any filesystem call — but
     * it makes the release notes' claim literally true ("the phone does not even spawn its
     * privileged helper process") for the turn-it-back-off direction too, rather than leaving a
     * shell-uid process alive to answer nobody.
     */
    fun unbind() {
        runCatching { Shizuku.unbindUserService(args, connection, /* remove = */ true) }
            .onFailure { Log.w(TAG, "unbindUserService failed: ${it.message}") }
        service = null
    }

    /** A bound service whose binder has already died is not ready — ping, don't just null-check. */
    fun isReady(): Boolean = service != null && runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    // Null when the service isn't bound, or when the call throws. Echoing the input path back
    // would hand BrowseEngine an unresolved string to confine against — the same bypass the
    // JVM bridge avoids.
    override fun canonical(path: String): String? = call { service?.canonical(path) }

    override fun list(dir: String): List<FsEntry> =
        call { service?.list(dir)?.mapNotNull { parseRow(it) } } ?: emptyList()

    override fun stat(path: String): FsEntry? = call { service?.stat(path)?.let { parseRow(it) } }

    override fun exists(path: String): Boolean = call { service?.exists(path) } ?: false

    override fun open(path: String): InputStream {
        val fd = call { service?.open(path) } ?: throw IOException("file bridge unavailable")
        return ParcelFileDescriptor.AutoCloseInputStream(fd).buffered()
    }

    override fun create(path: String): OutputStream {
        val fd = call { service?.create(path) } ?: throw IOException("file bridge unavailable")
        return ParcelFileDescriptor.AutoCloseOutputStream(fd).buffered()
    }

    override fun move(from: String, to: String): Boolean = call { service?.move(from, to) } ?: false

    override fun delete(path: String): Boolean = call { service?.delete(path) } ?: false

    override fun mkdirs(path: String): Boolean = call { service?.mkdirs(path) } ?: false

    /**
     * Every AIDL method is declared `throws RemoteException`, and DeadObjectException extends
     * it — the service dies whenever Shizuku restarts, which happens on every phone reboot.
     * A throw here would escape onto the transport reader coroutine and could drop the peer
     * link, so a dead binder must degrade to the same answer an unbound one gives.
     */
    private fun <T> call(body: () -> T?): T? = runCatching { body() }
        .onFailure { Log.w(TAG, "file bridge call failed: ${it.javaClass.simpleName}: ${it.message}") }
        .getOrNull()

    /**
     * "size\tdir\tmtime\tname" — the service's wire row. limit = 4 keeps a tab-containing
     * filename intact in the final field instead of splitting it into a fifth part, which
     * would drop the entry from the listing while the file itself stayed on disk.
     */
    private fun parseRow(row: String): FsEntry? {
        val parts = row.split('\t', limit = 4)
        if (parts.size != 4) return null
        val dir = parts[1].toBoolean()
        val name = parts[3]
        return FsEntry(
            name = name,
            size = parts[0].toLongOrNull() ?: 0L,
            dir = dir,
            mtimeMs = parts[2].toLongOrNull() ?: 0L,
            mime = if (dir) "" else JvmFileBridge.guessMime(name),
        )
    }

    companion object {
        private const val TAG = "clipsyncFiles"
        private const val SERVICE_VERSION = 1
    }
}
