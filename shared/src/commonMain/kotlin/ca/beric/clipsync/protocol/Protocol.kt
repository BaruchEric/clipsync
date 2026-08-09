@file:OptIn(ExperimentalEncodingApi::class)

package ca.beric.clipsync.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Version stamp carried by every clipboard update; drives last-write-wins. */
@Serializable
data class ClipVersion(
    @SerialName("dev") val deviceId: String,
    @SerialName("ctr") val counter: Long,
    @SerialName("ts") val wallClockMs: Long,
)

/** Metadata for an image sent as chunked binary frames (payload exceeds the inline cap). */
@Serializable
data class ImageMeta(
    @SerialName("mime") val mime: String,
    @SerialName("size") val size: Long,
    @SerialName("sha256") val sha256: String,
)

/**
 * Control-plane messages, exchanged as JSON text frames. Payload bytes referenced
 * here are always the AEAD-sealed ciphertext (encrypted on the source device).
 */
@Serializable
sealed interface ControlMessage {

    /** First message after connect: identifies the sender and protocol version. */
    @Serializable
    @SerialName("hello")
    data class Hello(
        @SerialName("dev") val deviceId: String,
        @SerialName("v") val protocolVersion: Int = 1,
    ) : ControlMessage

    /** Text or small image: the sealed payload rides inline (base64). */
    @Serializable
    @SerialName("clip")
    data class ClipUpdate(
        @SerialName("ver") val version: ClipVersion,
        @SerialName("kind") val kind: String,
        @SerialName("data") val sealedB64: String,
    ) : ControlMessage {
        val sealedBytes: ByteArray get() = Base64.decode(sealedB64)

        companion object {
            fun of(version: ClipVersion, kind: String, sealed: ByteArray) =
                ClipUpdate(version, kind, Base64.encode(sealed))
        }
    }

    /** Large image: announces metadata; the sealed chunks follow as binary frames. */
    @Serializable
    @SerialName("image")
    data class ImageUpdate(
        @SerialName("ver") val version: ClipVersion,
        @SerialName("meta") val meta: ImageMeta,
        @SerialName("chunks") val chunkCount: Int,
    ) : ControlMessage
}

/** Serializes control messages to/from JSON text frames. */
object ControlCodec {
    private val json = Json { classDiscriminator = "t"; ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(message: ControlMessage): String = json.encodeToString(message)

    fun decode(text: String): ControlMessage? =
        runCatching { json.decodeFromString<ControlMessage>(text) }.getOrNull()
}
