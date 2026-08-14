package ca.beric.clipsync.android.transfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import ca.beric.clipsync.transfer.FileSink
import ca.beric.clipsync.transfer.PendingFile
import java.io.IOException
import java.io.OutputStream

/**
 * Receives files into MediaStore Downloads under Download/clipsync — visible in the Files
 * app with no storage permission on minSdk 29. The row stays IS_PENDING until the engine's
 * integrity check passes, so a failed transfer never leaves a half-file visible; MediaStore
 * uniquifies colliding names itself.
 */
class MediaStoreFileSink(context: Context) : FileSink {

    private val resolver = context.applicationContext.contentResolver

    override fun begin(name: String, mime: String, dest: String): PendingFile {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, if ('/' in mime) mime else "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/clipsync")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore insert failed for $name")
        val out = resolver.openOutputStream(uri)
            ?: run {
                resolver.delete(uri, null, null)
                throw IOException("MediaStore stream unavailable for $name")
            }
        val buffered = out.buffered()
        return object : PendingFile {
            override val stream: OutputStream = buffered

            override fun publish(): String {
                buffered.close()
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
                return "Download/clipsync/$name"
            }

            override fun discard() {
                runCatching { buffered.close() }
                runCatching { resolver.delete(uri, null, null) }
            }
        }
    }
}
