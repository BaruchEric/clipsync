package ca.beric.clipsync.sync

import ca.beric.clipsync.core.ClipRepository
import ca.beric.clipsync.crypto.ClipsyncCrypto
import ca.beric.clipsync.protocol.ChunkFrame
import ca.beric.clipsync.protocol.ClipVersion
import ca.beric.clipsync.protocol.ControlMessage
import ca.beric.clipsync.protocol.ImageMeta
import ca.beric.clipsync.protocol.LwwResolver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Writes a received value into the local system clipboard. Platform-specific. */
interface ClipboardApplier {
    fun applyText(text: String)

    /** Writes a received image (PNG/JPEG bytes) to the clipboard. No-op where unsupported. */
    fun applyImage(bytes: ByteArray, mime: String)
}

/** A connected, paired peer: how to reach it and the key to talk to it. */
class RemotePeer(
    val deviceId: String,
    val perPairKey: ByteArray,
    val send: suspend (ControlMessage) -> Unit,
    val sendChunk: suspend (ByteArray) -> Unit = {},
)

/**
 * The sync brain, independent of transport. Text and images both flow the same way:
 * on local capture the payload is sealed per peer and broadcast; on receipt it is
 * reassembled (images arrive as an [ControlMessage.ImageUpdate] announcement plus
 * binary [ChunkFrame]s), integrity-checked, decrypted, applied, and recorded.
 *
 * Every image goes as chunks — even a one-chunk image — so there is a single receive
 * path; the 1 MiB "inline" spec cap is not implemented as a second branch.
 *
 * Thread safety: all mutable state is guarded by [mutex]; network sends and clipboard
 * writes happen outside the lock so I/O never blocks other engine operations.
 */
class SyncEngine(
    private val deviceId: String,
    private val repository: ClipRepository,
    private val applier: ClipboardApplier,
) {
    private val lww = LwwResolver()
    private val peers = mutableMapOf<String, RemotePeer>()
    private val mutex = Mutex()
    private var counter = 0L
    private var suppressedEcho: String? = null
    private var suppressedImageHash: String? = null

    /** The newest locally captured value, replayed to each newly connected peer. */
    private sealed interface LastLocal {
        val version: ClipVersion
    }

    private class LastText(val text: String, override val version: ClipVersion) : LastLocal
    private class LastImage(val bytes: ByteArray, val mime: String, override val version: ClipVersion) : LastLocal

    private var lastLocal: LastLocal? = null

    /** A partially-received image transfer, keyed by its sealed-bytes hash. */
    private class Incoming(
        val fromDeviceId: String,
        val mime: String,
        val version: ClipVersion,
        val total: Int,
        val sizeBytes: Long,
    ) {
        val chunks = arrayOfNulls<ByteArray>(total)
        var received = 0
        var accumulated = 0
    }

    private val pendingImages = mutableMapOf<String, Incoming>()

    suspend fun addPeer(peer: RemotePeer) {
        val replay = mutex.withLock {
            peers[peer.deviceId] = peer
            lastLocal
        }
        // Replay the newest local value to the peer that just (re)connected: a copy made
        // while the link was down (network switch, LTE) would otherwise never sync — there
        // is no queue. Both sides replay on connect; the receiver's LWW keeps the newest,
        // so this converges instead of ping-ponging.
        when (replay) {
            is LastText -> {
                val sealed = ClipsyncCrypto.seal(peer.perPairKey, replay.text.encodeToByteArray())
                peer.send(ControlMessage.ClipUpdate.of(replay.version, "text", sealed))
            }
            is LastImage -> sendImageTo(peer, replay.bytes, replay.mime, replay.version)
            null -> Unit
        }
    }

    suspend fun removePeer(deviceId: String) = mutex.withLock {
        peers.remove(deviceId)
        // Evict any half-received transfers from this peer so a mid-transfer disconnect
        // does not leak buffers.
        pendingImages.entries.removeAll { it.value.fromDeviceId == deviceId }
    }

    // --- Local capture ---

    /**
     * Called when this device captures a new local clipboard text value. Returns false if the
     * value was an echo of a just-applied remote value (so the caller does not record it locally).
     */
    suspend fun onLocalCapture(text: String, nowMs: Long): Boolean {
        val version: ClipVersion
        val targets: List<RemotePeer>
        mutex.withLock {
            if (text == suppressedEcho) {
                suppressedEcho = null
                return false
            }
            version = ClipVersion(deviceId, ++counter, nowMs)
            lww.recordLocal(version)
            lastLocal = LastText(text, version)
            targets = peers.values.toList()
        }
        for (peer in targets) {
            val sealed = ClipsyncCrypto.seal(peer.perPairKey, text.encodeToByteArray())
            peer.send(ControlMessage.ClipUpdate.of(version, "text", sealed))
        }
        return true
    }

    /**
     * Called when this device captures a new local clipboard image. Returns false if the image
     * was an echo of a just-applied remote image (so the caller does not record it locally).
     */
    suspend fun onLocalImageCapture(bytes: ByteArray, mime: String, nowMs: Long): Boolean {
        val imageHash = ClipsyncCrypto.toHex(ClipsyncCrypto.sha256(bytes))
        val version: ClipVersion
        val targets: List<RemotePeer>
        mutex.withLock {
            if (imageHash == suppressedImageHash) {
                suppressedImageHash = null
                return false
            }
            version = ClipVersion(deviceId, ++counter, nowMs)
            lww.recordLocal(version)
            lastLocal = LastImage(bytes, mime, version)
            targets = peers.values.toList()
        }
        for (peer in targets) sendImageTo(peer, bytes, mime, version)
        return true
    }

    /** Seals and streams one image to one peer: announcement plus sealed chunks. */
    private suspend fun sendImageTo(peer: RemotePeer, bytes: ByteArray, mime: String, version: ClipVersion) {
        val sealed = ClipsyncCrypto.seal(peer.perPairKey, bytes)
        val transferSha = ClipsyncCrypto.sha256(sealed) // ties the frames to the announcement
        val transferShaHex = ClipsyncCrypto.toHex(transferSha)
        val chunks = sealed.chunkedBy(CHUNK_BYTES)
        peer.send(ControlMessage.ImageUpdate(version, ImageMeta(mime, bytes.size.toLong(), transferShaHex), chunks.size))
        chunks.forEachIndexed { i, chunk ->
            peer.sendChunk(ChunkFrame.encode(transferSha, i, chunks.size, chunk))
        }
    }

    // --- Remote receive ---

    /** Called for each control message arriving from [fromDeviceId]. */
    suspend fun onRemoteMessage(fromDeviceId: String, message: ControlMessage) {
        when (message) {
            is ControlMessage.ClipUpdate -> applyRemoteClip(fromDeviceId, message)
            is ControlMessage.ImageUpdate -> beginImageTransfer(fromDeviceId, message)
            is ControlMessage.Hello, is ControlMessage.PairRequest,
            is ControlMessage.FileOffer, is ControlMessage.FileAck, is ControlMessage.FileError,
            is ControlMessage.Mirror,
            -> Unit // handled by the transport / FileTransferEngine / MirrorEngine
        }
    }

    /** Called for each inbound binary frame (one image chunk). */
    suspend fun onBinaryFrame(frame: ByteArray) {
        val decoded = ChunkFrame.decode(frame) ?: return
        val shaHex = ClipsyncCrypto.toHex(decoded.sha256)
        val ready: Triple<ByteArray, String, ClipVersion> = mutex.withLock {
            val t = pendingImages[shaHex] ?: return
            if (decoded.total != t.total || decoded.index < 0 || decoded.index >= t.total) {
                pendingImages.remove(shaHex)
                return
            }
            if (t.chunks[decoded.index] == null) {
                t.chunks[decoded.index] = decoded.payload
                t.received++
                t.accumulated += decoded.payload.size
            }
            if (t.accumulated > t.sizeBytes + SEAL_SLACK_BYTES) { // oversize / abusive transfer
                pendingImages.remove(shaHex)
                return
            }
            if (t.received < t.total) return
            pendingImages.remove(shaHex)
            val sealed = t.chunks.requireNoNulls().concat()
            if (ClipsyncCrypto.toHex(ClipsyncCrypto.sha256(sealed)) != shaHex) return // corrupt/truncated
            if (!lww.accept(t.version)) return // stale relative to what we already have
            val key = peers[t.fromDeviceId]?.perPairKey ?: return
            val plain = ClipsyncCrypto.open(key, sealed) ?: return // auth failure
            suppressedImageHash = ClipsyncCrypto.toHex(ClipsyncCrypto.sha256(plain))
            Triple(plain, t.mime, t.version)
        }
        // Record before applying so applying (which re-triggers local capture) can't
        // mis-attribute the image to LOCAL_DEVICE_ID first.
        repository.recordImage(ready.third.deviceId, ready.second, ready.first.size, ready.third.wallClockMs)
        applier.applyImage(ready.first, ready.second)
    }

    private suspend fun beginImageTransfer(fromDeviceId: String, update: ControlMessage.ImageUpdate) = mutex.withLock {
        if (update.chunkCount <= 0 || update.meta.size <= 0 || update.meta.size > MAX_IMAGE_BYTES) return
        if (peers[fromDeviceId] == null) return
        pendingImages[update.meta.sha256] =
            Incoming(fromDeviceId, update.meta.mime, update.version, update.chunkCount, update.meta.size)
    }

    private suspend fun applyRemoteClip(fromDeviceId: String, update: ControlMessage.ClipUpdate) {
        val text = mutex.withLock {
            val peer = peers[fromDeviceId] ?: return
            if (!lww.accept(update.version)) return
            val plain = ClipsyncCrypto.open(peer.perPairKey, update.sealedBytes) ?: return
            val decoded = plain.decodeToString()
            suppressedEcho = decoded
            decoded
        }
        repository.record(update.version.deviceId, text, update.version.wallClockMs)
        applier.applyText(text)
    }

    private fun ByteArray.chunkedBy(size: Int): List<ByteArray> =
        (indices step size).map { copyOfRange(it, minOf(it + size, this.size)) }

    private fun Array<ByteArray>.concat(): ByteArray {
        val out = ByteArray(sumOf { it.size })
        var offset = 0
        for (part in this) { part.copyInto(out, offset); offset += part.size }
        return out
    }

    private companion object {
        const val CHUNK_BYTES = 64 * 1024
        const val MAX_IMAGE_BYTES = 16L * 1024 * 1024
        const val SEAL_SLACK_BYTES = 1024 // AEAD overhead (nonce+tag) + margin over announced plaintext size
    }
}
