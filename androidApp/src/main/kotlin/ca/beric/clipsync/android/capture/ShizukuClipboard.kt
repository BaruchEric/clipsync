package ca.beric.clipsync.android.capture

import android.content.ClipData
import android.content.Context
import android.os.IBinder
import android.util.Log
import java.lang.reflect.Method
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Reads the system clipboard through Shizuku's privileged (SHELL uid) binder.
 *
 * The AOSP ClipboardService focus check exempts the SHELL/ROOT/SYSTEM uids, so a
 * getPrimaryClip() call routed through Shizuku's shell-uid process succeeds even
 * when clipsync is fully backgrounded — which a normal or accessibility-service
 * read cannot do on Android 10+.
 *
 * IClipboard is a hidden system interface and its getPrimaryClip() signature has
 * changed across API levels (added attributionTag, then deviceId), so the method
 * is resolved reflectively and its arguments are filled by parameter type.
 */
class ShizukuClipboard(private val context: Context) {

    /** The package that owns the SHELL uid; must match the calling identity Shizuku uses. */
    private val shellPackage = "com.android.shell"

    private val clipboardInterface: Any? by lazy { buildClipboardInterface() }
    private val getPrimaryClipMethod by lazy { resolveMethod("getPrimaryClip") }
    private val setPrimaryClipMethod by lazy { resolveMethod("setPrimaryClip") }

    fun isReady(): Boolean {
        val ping = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val perm = runCatching { Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)
        return ping && perm
    }

    private fun readClip(): ClipData? {
        val iface = clipboardInterface ?: return null
        val method = getPrimaryClipMethod ?: return null
        return try {
            val args = buildArgs(method.parameterTypes)
            method.invoke(iface, *args) as? ClipData
        } catch (t: Throwable) {
            Log.w(TAG, "getPrimaryClip via Shizuku failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /**
     * Returns the current clipboard text, or null (not text / unavailable / read failed).
     * A URI-only clip (an image copy) is NOT text: coerceToText would stringify the URI,
     * which is how an image copy masquerades as a text clip — hence the explicit gate.
     */
    fun readText(): String? {
        val clip = readClip() ?: return null
        if (clip.itemCount == 0) return null
        val item = clip.getItemAt(0)
        item.text?.let { return it.toString() }
        if (item.uri != null) return null
        return runCatching { item.coerceToText(context)?.toString() }.getOrNull()
    }

    /**
     * Cheap-ish change signature of the current clip: text hash for text clips, URI hash for
     * URI (image) clips, null when empty/unavailable. Android exposes no clipboard change
     * count to the shell binder, so the poller diffs this instead.
     */
    fun clipSignature(): Long? {
        val clip = readClip() ?: return null
        if (clip.itemCount == 0) return null
        val item = clip.getItemAt(0)
        item.text?.toString()?.let { text ->
            return (text.length.toLong() shl 32) xor (text.hashCode().toLong() and 0xFFFFFFFFL)
        }
        item.uri?.toString()?.let { uri ->
            return URI_TOKEN_BIT or (uri.hashCode().toLong() and 0xFFFFFFFFL)
        }
        return null
    }

    /**
     * Reads the current clipboard image (a content: URI item) as raw bytes + mime.
     * Tries this app's own resolver first (covers URIs we can already read), then falls back
     * to a SHELL-uid "content read" through Shizuku, which sidesteps the URI grant the copy
     * source never gave us. Bytes are trusted only if they sniff as PNG/JPEG — "content read"
     * happily prints error text to stdout, and declared mimes lie.
     */
    fun readImage(): Pair<ByteArray, String>? {
        val clip = readClip() ?: return null
        if (clip.itemCount == 0) return null
        val item = clip.getItemAt(0)
        if (item.text != null) return null
        val uri = item.uri ?: return null
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { limitedRead(it) }
        }.getOrNull() ?: shellReadUri(uri)
        if (bytes == null) {
            Log.w(TAG, "image clip $uri: unreadable via resolver and shell")
            return null
        }
        val mime = sniffImageMime(bytes)
        if (mime == null) {
            Log.w(TAG, "image clip $uri: ${bytes.size}B did not sniff as PNG/JPEG; dropped")
            return null
        }
        return bytes to mime
    }

    /** Harness hook: puts [uri] on the clipboard as an image clip (via the shell-uid binder). */
    fun setImageUri(uri: android.net.Uri): Boolean {
        val iface = clipboardInterface ?: return false
        val method = setPrimaryClipMethod ?: return false
        return try {
            val clip = ClipData("clipsync", arrayOf("image/png"), ClipData.Item(uri))
            method.invoke(iface, *buildSetArgs(method.parameterTypes, clip))
            true
        } catch (t: Throwable) {
            Log.w(TAG, "setImageUri via Shizuku failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /** Runs "content read" as the SHELL uid via Shizuku's (non-SDK) newProcess. */
    private fun shellReadUri(uri: android.net.Uri): ByteArray? = try {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
        )
        m.isAccessible = true
        val quoted = "'" + uri.toString().replace("'", "'\\''") + "'"
        val proc = m.invoke(null, arrayOf("sh", "-c", "content read --uri $quoted"), null, null) as Process
        val bytes = proc.inputStream.use { limitedRead(it) }
        proc.waitFor()
        bytes?.takeIf { it.isNotEmpty() }
    } catch (t: Throwable) {
        Log.w(TAG, "shell content read failed: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    /** Reads at most [MAX_IMAGE_BYTES]; returns null if the stream exceeds it (engine cap). */
    private fun limitedRead(input: java.io.InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_IMAGE_BYTES) return null
        }
        return out.toByteArray()
    }

    private fun sniffImageMime(b: ByteArray): String? = when {
        b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() &&
            b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> "image/png"
        b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
        else -> null
    }

    /** Writes [text] to the system clipboard via Shizuku (background writes are focus-gated too). */
    fun setText(text: String): Boolean {
        val iface = clipboardInterface ?: return false
        val method = setPrimaryClipMethod ?: return false
        return try {
            val clip = ClipData.newPlainText("clipsync", text)
            val args = buildSetArgs(method.parameterTypes, clip)
            method.invoke(iface, *args)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "setPrimaryClip via Shizuku failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    private fun buildClipboardInterface(): Any? = try {
        val raw: IBinder = SystemServiceHelper.getSystemService("clipboard")
        val wrapped = ShizukuBinderWrapper(raw)
        val stub = Class.forName("android.content.IClipboard\$Stub")
        stub.getMethod("asInterface", IBinder::class.java).invoke(null, wrapped)
    } catch (t: Throwable) {
        Log.w(TAG, "could not build IClipboard interface: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    private fun resolveMethod(name: String): Method? {
        val iface = clipboardInterface ?: return null
        return iface.javaClass.methods
            .filter { it.name == name }
            .maxByOrNull { it.parameterTypes.size }
            ?.also { Log.i(TAG, "resolved $name(${it.parameterTypes.joinToString { p -> p.simpleName }})") }
    }

    /**
     * Fill arguments by declared parameter type:
     *  - first String  → calling package (shell)
     *  - later String  → attributionTag (null)
     *  - int           → first is userId (0), any second int is deviceId (0 = DEVICE_ID_DEFAULT)
     */
    private fun buildArgs(paramTypes: Array<Class<*>>): Array<Any?> {
        var stringSeen = 0
        return Array(paramTypes.size) { i ->
            when (paramTypes[i]) {
                String::class.java -> if (stringSeen++ == 0) shellPackage else null
                Int::class.javaPrimitiveType, Integer::class.java -> 0
                else -> null
            }
        }
    }

    /** Like [buildArgs] but injects the [clip] for the ClipData parameter of setPrimaryClip. */
    private fun buildSetArgs(paramTypes: Array<Class<*>>, clip: ClipData): Array<Any?> {
        var stringSeen = 0
        return Array(paramTypes.size) { i ->
            when {
                paramTypes[i] == ClipData::class.java -> clip
                paramTypes[i] == String::class.java -> if (stringSeen++ == 0) shellPackage else null
                paramTypes[i] == Int::class.javaPrimitiveType || paramTypes[i] == Integer::class.java -> 0
                else -> null
            }
        }
    }

    companion object {
        private const val TAG = "clipsyncShizuku"
        private const val MAX_IMAGE_BYTES = 16 * 1024 * 1024 // matches the engine's image cap
        private const val URI_TOKEN_BIT = 0x4000_0000_0000_0000L // disjoint from text tokens
    }
}
