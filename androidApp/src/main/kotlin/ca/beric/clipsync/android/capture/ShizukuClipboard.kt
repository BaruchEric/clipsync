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
    }
}
