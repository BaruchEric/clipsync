package ca.beric.clipsync.android.browse

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.util.Size
import ca.beric.clipsync.protocol.MediaItem
import java.io.ByteArrayOutputStream

/**
 * Read-only MediaStore access for the desktop's photo grid (M9). Thumbnails come from
 * ContentResolver.loadThumbnail, which is cache-backed — decoding a 12 MP JPEG per tile
 * would be far slower. Mutations never come through here: they go through the Shizuku
 * bridge, so there is one confined write path.
 */
class MediaIndex(context: Context) {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    fun hasPermission(): Boolean =
        PERMISSIONS.all { appContext.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Newest-first images. Absent permission answers empty rather than throwing. */
    fun items(offset: Int, limit: Int): List<MediaItem> {
        if (!hasPermission()) return emptyList()
        val out = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
        )
        val order = "${MediaStore.Images.Media.DATE_MODIFIED} DESC LIMIT ${limit.coerceIn(1, 200)} OFFSET ${offset.coerceAtLeast(0)}"
        runCatching {
            resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, order)?.use { c ->
                while (c.moveToNext()) {
                    out += MediaItem(
                        id = c.getLong(0),
                        name = c.getString(1).orEmpty(),
                        size = c.getLong(2),
                        dateMs = c.getLong(3) * 1000L, // DATE_MODIFIED is seconds
                        mime = c.getString(4).orEmpty(),
                        width = c.getInt(5),
                        height = c.getInt(6),
                    )
                }
            }
        }.onFailure { Log.w(TAG, "media query failed: ${it.message}") }
        return out
    }

    /** MediaStore id → base64 JPEG, ≤[MAX_THUMBS] per call. Ids that fail are omitted. */
    fun thumbs(ids: List<Long>): Map<Long, String> {
        if (!hasPermission()) return emptyMap()
        val out = LinkedHashMap<Long, String>()
        for (id in ids.take(MAX_THUMBS)) {
            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.buildUpon().appendPath(id.toString()).build()
            runCatching {
                val bmp = resolver.loadThumbnail(uri, Size(THUMB_PX, THUMB_PX), null)
                val bytes = ByteArrayOutputStream().use { buf ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, buf)
                    buf.toByteArray()
                }
                out[id] = Base64.encodeToString(bytes, Base64.NO_WRAP)
            }.onFailure { Log.w(TAG, "thumb $id failed: ${it.message}") }
        }
        return out
    }

    companion object {
        private const val TAG = "clipsyncMedia"
        private const val THUMB_PX = 256
        private const val THUMB_QUALITY = 80

        /** One envelope's worth of tiles. */
        const val MAX_THUMBS = 24

        val PERMISSIONS: Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Images only. Requiring READ_MEDIA_VIDEO as well would make a user who grants
                // Photos but denies Videos see an empty grid, for a query that never runs.
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
    }
}
