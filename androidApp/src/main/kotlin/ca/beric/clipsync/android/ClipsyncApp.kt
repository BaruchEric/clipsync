package ca.beric.clipsync.android

import android.app.Application
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass

class ClipsyncApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Framework-internal methods (IClipboard.getPrimaryClip) are filtered out of
        // reflection by hidden-API enforcement; exempt them so the Shizuku read works.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
        }
        AppGraph.init(this)
    }
}
