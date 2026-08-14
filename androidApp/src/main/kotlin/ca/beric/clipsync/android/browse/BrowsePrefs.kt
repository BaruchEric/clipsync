package ca.beric.clipsync.android.browse

import android.content.Context

/** The M9 consent gate: off until the user turns it on, and it stays on until they don't. */
object BrowsePrefs {
    private const val FILE = "clipsync"
    private const val KEY = "browse_enabled"

    fun enabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, on).apply()
    }
}
