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

    /**
     * First message after connect: identifies the sender and protocol version.
     * [endpoints] ("host:port") are the sender's current dial addresses; a receiver that
     * knows this peer refreshes its stored row so old addresses can't rot (a v1 peer omits
     * the field and a v1 receiver ignores it). Note the Hello itself is not authenticated —
     * a poisoned endpoint list can only send future dials to dead ends (dials still pin the
     * TLS fingerprint), the same availability-not-confidentiality posture as the
     * no-client-auth TLS server.
     */
    @Serializable
    @SerialName("hello")
    data class Hello(
        @SerialName("dev") val deviceId: String,
        @SerialName("v") val protocolVersion: Int = 1,
        @SerialName("ep") val endpoints: List<String> = emptyList(),
    ) : ControlMessage

    /**
     * Reciprocal pairing: the sender's [ca.beric.clipsync.pairing.PairingPayload] JSON,
     * sent by a device that just scanned the peer's QR so the peer (which has no camera)
     * can derive the same per-pair key. Only sent while a reciprocal pairing is pending.
     */
    @Serializable
    @SerialName("pair")
    data class PairRequest(
        @SerialName("p") val payload: String,
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

    /**
     * Offers a file transfer. [id] (random, hex) keys the binary [ChunkFrame]s that follow;
     * [sha256] is the plaintext file hash the receiver verifies before publishing. Chunks are
     * sent only after the receiver's accepting [FileAck] (received = 0), and unlike images are
     * sealed per chunk (AAD = id ‖ index) so neither side ever holds the whole file in memory.
     */
    @Serializable
    @SerialName("file")
    data class FileOffer(
        @SerialName("id") val id: String,
        @SerialName("name") val name: String,
        @SerialName("size") val size: Long,
        @SerialName("mime") val mime: String,
        @SerialName("sha256") val sha256: String,
        @SerialName("chunks") val chunkCount: Int,
    ) : ControlMessage

    /**
     * Receiver → sender progress for one transfer: [received] chunks so far. 0 accepts the
     * offer and starts the stream; reaching the offer's chunkCount confirms completion. The
     * sender keeps a bounded window of unacked chunks, so acks are also flow control.
     */
    @Serializable
    @SerialName("file-ack")
    data class FileAck(
        @SerialName("id") val id: String,
        @SerialName("n") val received: Int,
    ) : ControlMessage

    /** Either side aborts a transfer; the receiver discards any partially written data. */
    @Serializable
    @SerialName("file-err")
    data class FileError(
        @SerialName("id") val id: String,
        @SerialName("reason") val reason: String,
    ) : ControlMessage

    /**
     * Envelope for notification-mirroring and messages traffic: [sealedB64] is the per-pair
     * sealed JSON of a [MirrorEvent], so notification text and SMS bodies are E2E-encrypted
     * exactly like clip payloads. Pre-0.3 peers drop the unknown type; the features degrade
     * to absent, never break clipboard/file sync.
     */
    @Serializable
    @SerialName("mirror")
    data class Mirror(
        @SerialName("data") val sealedB64: String,
    ) : ControlMessage {
        val sealedBytes: ByteArray get() = Base64.decode(sealedB64)

        companion object {
            fun of(sealed: ByteArray) = Mirror(Base64.encode(sealed))
        }
    }
}

/** One phone notification, or one messages request/response, inside a sealed [ControlMessage.Mirror]. */
@Serializable
sealed interface MirrorEvent {

    @Serializable
    @SerialName("notif")
    data class NotifPosted(
        @SerialName("key") val key: String,
        @SerialName("app") val app: String,
        @SerialName("title") val title: String,
        @SerialName("text") val text: String,
        @SerialName("when") val whenMs: Long,
        @SerialName("reply") val canReply: Boolean = false,
    ) : MirrorEvent

    @Serializable
    @SerialName("notif-reply")
    data class NotifReply(
        @SerialName("key") val key: String,
        @SerialName("text") val text: String,
    ) : MirrorEvent

    @Serializable
    @SerialName("sms-threads?")
    data object SmsQueryThreads : MirrorEvent

    @Serializable
    @SerialName("sms-threads")
    data class SmsThreads(
        @SerialName("list") val threads: List<SmsThread>,
    ) : MirrorEvent

    @Serializable
    @SerialName("sms-thread?")
    data class SmsQueryThread(
        @SerialName("id") val threadId: Long,
    ) : MirrorEvent

    @Serializable
    @SerialName("sms-msgs")
    data class SmsMessages(
        @SerialName("id") val threadId: Long,
        @SerialName("list") val messages: List<SmsMessage>,
    ) : MirrorEvent

    @Serializable
    @SerialName("sms-send")
    data class SmsSend(
        @SerialName("to") val to: String,
        @SerialName("body") val body: String,
    ) : MirrorEvent

    @Serializable
    @SerialName("sms-sent")
    data class SmsSent(
        @SerialName("ok") val ok: Boolean,
        @SerialName("to") val to: String,
    ) : MirrorEvent
}

@Serializable
data class SmsThread(
    @SerialName("id") val threadId: Long,
    @SerialName("addr") val address: String,
    @SerialName("snip") val snippet: String,
    @SerialName("date") val dateMs: Long,
    @SerialName("n") val count: Int,
)

@Serializable
data class SmsMessage(
    @SerialName("addr") val address: String,
    @SerialName("body") val body: String,
    @SerialName("date") val dateMs: Long,
    @SerialName("out") val outbound: Boolean,
)

/** Serializes mirror events; unknown subtypes (newer peers) decode to null and are dropped. */
object MirrorCodec {
    private val json = Json { classDiscriminator = "t"; ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(event: MirrorEvent): String = json.encodeToString<MirrorEvent>(event)

    fun decode(text: String): MirrorEvent? =
        runCatching { json.decodeFromString<MirrorEvent>(text) }.getOrNull()
}

/** Serializes control messages to/from JSON text frames. */
object ControlCodec {
    private val json = Json { classDiscriminator = "t"; ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(message: ControlMessage): String = json.encodeToString(message)

    fun decode(text: String): ControlMessage? =
        runCatching { json.decodeFromString<ControlMessage>(text) }.getOrNull()
}
