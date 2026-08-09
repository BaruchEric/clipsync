package ca.beric.clipsync.android.capture

import android.content.ClipData
import android.content.Context
import android.os.IBinder
import android.util.Log
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
    private val getPrimaryClipMethod by lazy { resolveGetPrimaryClip() }

    fun isReady(): Boolean {
        val ping = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val perm = runCatching { Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)
        return ping && perm
    }

    /** Returns the current clipboard text, or null (not text / unavailable / read failed). */
    fun readText(): String? {
        val iface = clipboardInterface ?: return null
        val method = getPrimaryClipMethod ?: return null
        return try {
            val args = buildArgs(method.parameterTypes)
            val clip = method.invoke(iface, *args) as? ClipData ?: return null
            if (clip.itemCount == 0) return null
            clip.getItemAt(0).coerceToText(context)?.toString()
        } catch (t: Throwable) {
            Log.w(TAG, "getPrimaryClip via Shizuku failed: ${t.javaClass.simpleName}: ${t.message}")
            null
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

    private fun resolveGetPrimaryClip(): java.lang.reflect.Method? {
        val iface = clipboardInterface ?: return null
        // Pick the getPrimaryClip overload whose first parameter is the calling package (String).
        return iface.javaClass.methods
            .filter { it.name == "getPrimaryClip" }
            .maxByOrNull { it.parameterTypes.size }
            ?.also { Log.i(TAG, "resolved getPrimaryClip(${it.parameterTypes.joinToString { p -> p.simpleName }})") }
    }

    /**
     * Fill arguments by declared parameter type:
     *  - first String  → calling package (shell)
     *  - later String  → attributionTag (null)
     *  - int           → first is userId (0), any second int is deviceId (0 = DEVICE_ID_DEFAULT)
     */
    private fun buildArgs(paramTypes: Array<Class<*>>): Array<Any?> {
        var stringSeen = 0
        var intSeen = 0
        return Array(paramTypes.size) { i ->
            when (paramTypes[i]) {
                String::class.java -> if (stringSeen++ == 0) shellPackage else null
                Int::class.javaPrimitiveType, Integer::class.java -> { intSeen++; 0 }
                else -> null
            }
        }
    }

    companion object {
        private const val TAG = "clipsyncShizuku"
    }
}
