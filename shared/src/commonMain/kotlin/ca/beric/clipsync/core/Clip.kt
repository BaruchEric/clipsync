package ca.beric.clipsync.core

/** A captured clipboard value: plain text or a raster image (PNG/JPEG bytes). */
sealed interface Clip {
    data class Text(val text: String) : Clip
    data class Image(val bytes: ByteArray, val mime: String) : Clip
}
