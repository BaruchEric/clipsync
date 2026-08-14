package ca.beric.clipsync.transfer

import ca.beric.clipsync.crypto.ClipsyncCrypto
import ca.beric.clipsync.protocol.ChunkFrame
import ca.beric.clipsync.protocol.ControlMessage
import ca.beric.clipsync.sync.RemotePeer
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Streams whole files between paired peers over the existing links, without ever holding a
 * file in memory: each 256 KiB chunk is sealed individually (AAD binds it to the transfer id
 * and its position) and the receiver appends plaintext to a [FileSink]-provided stream as
 * chunks arrive. The offered whole-file sha256 is verified before the file is published.
 *
 * Flow control is the receiver's [ControlMessage.FileAck] cadence: the sender keeps at most
 * [WINDOW_CHUNKS] unacked chunks in flight, so transport buffers stay bounded on GB files.
 * Chunks travel strictly in order (one ordered WebSocket stream), so receive = append.
 *
 * Mirrors [ca.beric.clipsync.sync.SyncEngine]'s surface: the [ConnectionManager] registers
 * peers with both engines and routes file control messages / binary frames here. Binary
 * frames whose id is unknown are ignored (they may be image chunks, and vice versa).
 */
class FileTransferEngine(
    scope: CoroutineScope,
    private val sink: FileSink,
    private val offerAckTimeoutMs: Long = 15_000,
    private val stallTimeoutMs: Long = 60_000,
    private val log: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private val peers = mutableMapOf<String, RemotePeer>()
    private val outgoing = mutableMapOf<String, Outgoing>()
    private val incoming = mutableMapOf<String, Incoming>()

    private val _transfers = MutableStateFlow<List<TransferState>>(emptyList())

    /** Recent transfers, most recently updated first, for the UIs. */
    val transfers: StateFlow<List<TransferState>> = _transfers

    /** Invoked once per successfully received file: (name, published location). */
    var onFileReceived: ((name: String, location: String) -> Unit)? = null

    private class TransferFailed(message: String) : Exception(message)

    private class Outgoing(val peer: RemotePeer) {
        /** Latest cumulative acked chunk count (conflated: only the newest value matters). */
        val acks = Channel<Int>(Channel.CONFLATED)

        @Volatile
        var abortReason: String? = null
    }

    private class Incoming(
        val peer: RemotePeer,
        val idBytes: ByteArray,
        val name: String,
        val size: Long,
        val sha256Hex: String,
        val total: Int,
        val pending: PendingFile,
    ) {
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
        var received = 0
        var receivedBytes = 0L

        @Volatile
        var lastProgressMs = System.currentTimeMillis()
    }

    init {
        // Watchdog: an inbound transfer whose sender went quiet is failed and its partial
        // data discarded, so a dead peer can't pin temp storage forever.
        scope.launch {
            while (isActive) {
                delay(maxOf(stallTimeoutMs / 4, 50))
                val now = System.currentTimeMillis()
                val stalled = mutex.withLock {
                    incoming.filterValues { now - it.lastProgressMs > stallTimeoutMs }.keys.toList()
                }
                for (id in stalled) failIncoming(id, "transfer stalled", notifyPeer = true)
            }
        }
    }

    suspend fun addPeer(peer: RemotePeer) = mutex.withLock { peers[peer.deviceId] = peer }

    suspend fun removePeer(deviceId: String) {
        val (outs, ins) = mutex.withLock {
            peers.remove(deviceId)
            val outs = outgoing.filterValues { it.peer.deviceId == deviceId }.values.toList()
            val ins = incoming.filterValues { it.peer.deviceId == deviceId }.keys.toList()
            outs to ins
        }
        outs.forEach {
            it.abortReason = "peer disconnected"
            it.acks.close(TransferFailed("peer disconnected"))
        }
        ins.forEach { failIncoming(it, "peer disconnected", notifyPeer = false) }
    }

    // --- Sending ---

    /**
     * Offers [source] to [toDeviceId], or to every connected peer when null, and streams it.
     * Returns false (doing nothing) when no such peer is connected. [dest] asks the receiver
     * to write into that absolute directory; empty means "your default".
     */
    suspend fun sendFile(source: FileSource, toDeviceId: String? = null, dest: String = ""): Boolean {
        val targets = mutex.withLock {
            if (toDeviceId == null) peers.values.toList() else listOfNotNull(peers[toDeviceId])
        }
        if (targets.isEmpty()) return false
        coroutineScope { targets.forEach { peer -> launch { sendToPeer(source, peer, dest) } } }
        return true
    }

    private suspend fun sendToPeer(source: FileSource, peer: RemotePeer, dest: String) {
        val idBytes = ClipsyncCrypto.randomBytes(TRANSFER_ID_BYTES)
        val id = ClipsyncCrypto.toHex(idBytes)
        val chunkCount = chunkCountFor(source.size)
        val state = Outgoing(peer)
        mutex.withLock { outgoing[id] = state }
        upsertState(TransferState(id, peer.deviceId, source.name, source.size, outbound = true))
        try {
            // Hash first: the offer must carry the whole-file sha256 the receiver verifies,
            // so sending costs one extra sequential read pass before streaming starts.
            val sha = source.open().use { streamSha256(it) }
            peer.send(ControlMessage.FileOffer(id, source.name, source.size, source.mime, sha, chunkCount, dest))
            var acked = withTimeout(offerAckTimeoutMs) { state.acks.receive() }
            source.open().use { input ->
                val buf = ByteArray(CHUNK_BYTES)
                var index = 0
                var sent = 0L
                while (true) {
                    val n = readFully(input, buf)
                    if (n <= 0) break
                    state.abortReason?.let { throw TransferFailed(it) }
                    while (index - acked >= WINDOW_CHUNKS) {
                        acked = withTimeout(stallTimeoutMs) { state.acks.receive() }
                    }
                    if (index >= chunkCount) throw TransferFailed("file grew while sending")
                    val chunk = if (n == buf.size) buf.copyOf() else buf.copyOf(n)
                    val sealed = ClipsyncCrypto.seal(peer.perPairKey, chunk, aad(idBytes, index))
                    peer.sendChunk(ChunkFrame.encode(idBytes, index, chunkCount, sealed))
                    index++
                    sent += n
                    if (index % ACK_EVERY == 0 || index == chunkCount) {
                        upsertState(TransferState(id, peer.deviceId, source.name, source.size, true, sent))
                    }
                }
                if (index != chunkCount) throw TransferFailed("file shrank while sending")
            }
            while (acked < chunkCount) {
                acked = withTimeout(stallTimeoutMs) { state.acks.receive() }
            }
            upsertState(
                TransferState(
                    id, peer.deviceId, source.name, source.size, true, source.size,
                    TransferState.Status.DONE,
                ),
            )
        } catch (t: Throwable) {
            val reason = when (t) {
                is TimeoutCancellationException ->
                    "peer did not respond — is clipsync on the other device up to date and running?"
                is CancellationException -> throw t
                else -> t.message ?: "send failed"
            }
            runCatching { peer.send(ControlMessage.FileError(id, reason)) }
            upsertState(
                TransferState(
                    id, peer.deviceId, source.name, source.size, true,
                    status = TransferState.Status.FAILED, detail = reason,
                ),
            )
        } finally {
            // NonCancellable: this cleanup must run even when the scope was cancelled mid-send.
            withContext(NonCancellable) { mutex.withLock { outgoing.remove(id) } }
        }
    }

    // --- Receiving ---

    /** Called for file-related control messages arriving from a registered peer. */
    suspend fun onRemoteMessage(fromDeviceId: String, message: ControlMessage) {
        when (message) {
            is ControlMessage.FileOffer -> onOffer(fromDeviceId, message)
            is ControlMessage.FileAck ->
                mutex.withLock { outgoing[message.id] }
                    ?.takeIf { it.peer.deviceId == fromDeviceId }
                    ?.acks?.trySend(message.received)
            is ControlMessage.FileError -> {
                mutex.withLock { outgoing[message.id] }
                    ?.takeIf { it.peer.deviceId == fromDeviceId }
                    ?.let {
                        it.abortReason = "peer: ${message.reason}"
                        it.acks.close(TransferFailed("peer: ${message.reason}"))
                    }
                failIncoming(message.id, "peer: ${message.reason}", notifyPeer = false, fromDeviceId = fromDeviceId)
            }
            else -> Unit
        }
    }

    private suspend fun onOffer(fromDeviceId: String, offer: ControlMessage.FileOffer) {
        val peer = mutex.withLock { peers[fromDeviceId] } ?: return
        val reject = when {
            offer.size < 0 || offer.size > MAX_FILE_BYTES -> "size out of bounds"
            offer.chunkCount != chunkCountFor(offer.size) -> "chunk count mismatch"
            offer.id.length != TRANSFER_ID_BYTES * 2 || !isHex(offer.id) -> "bad transfer id"
            else -> null
        }
        if (reject != null) {
            runCatching { peer.send(ControlMessage.FileError(offer.id, reject)) }
            return
        }
        val name = sanitizeName(offer.name)
        val pending = runCatching { sink.begin(name, offer.mime, offer.dest) }.getOrElse {
            runCatching { peer.send(ControlMessage.FileError(offer.id, "receiver storage error")) }
            return
        }
        val inc = Incoming(peer, hexToBytes(offer.id), name, offer.size, offer.sha256, offer.chunkCount, pending)
        val accepted = mutex.withLock {
            if (incoming.size >= MAX_CONCURRENT_INBOUND || incoming.containsKey(offer.id)) {
                false
            } else {
                incoming[offer.id] = inc
                true
            }
        }
        if (!accepted) {
            pending.discard()
            runCatching { peer.send(ControlMessage.FileError(offer.id, "receiver busy")) }
            return
        }
        upsertState(TransferState(offer.id, fromDeviceId, name, offer.size, outbound = false))
        runCatching { peer.send(ControlMessage.FileAck(offer.id, 0)) }
        if (offer.chunkCount == 0) finishIncoming(offer.id) // empty file: no chunks will follow
    }

    /** Called for every inbound binary frame; frames whose id isn't a file transfer are ignored. */
    suspend fun onBinaryFrame(frame: ByteArray) {
        val decoded = ChunkFrame.decode(frame) ?: return
        val id = ClipsyncCrypto.toHex(decoded.sha256) // the frame's 32-byte id field: our transfer id
        val inc = mutex.withLock { incoming[id] } ?: return
        val fail = when {
            decoded.total != inc.total -> "chunk total mismatch"
            decoded.index != inc.received -> "out-of-order chunk"
            decoded.payload.size > CHUNK_BYTES + SEAL_OVERHEAD_SLACK -> "oversize chunk"
            else -> null
        }
        if (fail != null) {
            failIncoming(id, fail, notifyPeer = true)
            return
        }
        val plain = ClipsyncCrypto.open(inc.peer.perPairKey, decoded.payload, aad(inc.idBytes, decoded.index))
        if (plain == null) {
            failIncoming(id, "chunk failed authentication", notifyPeer = true)
            return
        }
        val writeError = runCatching { inc.pending.stream.write(plain) }.exceptionOrNull()
        if (writeError != null) {
            failIncoming(id, "write failed: ${writeError.message}", notifyPeer = true)
            return
        }
        inc.digest.update(plain)
        inc.received++
        inc.receivedBytes += plain.size
        inc.lastProgressMs = System.currentTimeMillis()
        if (inc.receivedBytes > inc.size) {
            failIncoming(id, "more bytes than announced", notifyPeer = true)
            return
        }
        if (inc.received % ACK_EVERY == 0 && inc.received < inc.total) {
            upsertState(TransferState(id, inc.peer.deviceId, inc.name, inc.size, false, inc.receivedBytes))
            runCatching { inc.peer.send(ControlMessage.FileAck(id, inc.received)) }
        }
        if (inc.received == inc.total) finishIncoming(id)
    }

    private suspend fun finishIncoming(id: String) {
        val inc = mutex.withLock { incoming.remove(id) } ?: return
        val digestHex = ClipsyncCrypto.toHex(inc.digest.digest())
        if (inc.receivedBytes != inc.size || !digestHex.equals(inc.sha256Hex, ignoreCase = true)) {
            inc.pending.discard()
            runCatching { inc.peer.send(ControlMessage.FileError(id, "integrity check failed")) }
            upsertState(
                TransferState(
                    id, inc.peer.deviceId, inc.name, inc.size, false, inc.receivedBytes,
                    TransferState.Status.FAILED, "integrity check failed",
                ),
            )
            return
        }
        val location = runCatching { inc.pending.publish() }.getOrElse {
            inc.pending.discard()
            runCatching { inc.peer.send(ControlMessage.FileError(id, "receiver storage error")) }
            upsertState(
                TransferState(
                    id, inc.peer.deviceId, inc.name, inc.size, false, inc.receivedBytes,
                    TransferState.Status.FAILED, "could not save: ${it.message}",
                ),
            )
            return
        }
        runCatching { inc.peer.send(ControlMessage.FileAck(id, inc.total)) }
        upsertState(
            TransferState(
                id, inc.peer.deviceId, inc.name, inc.size, false, inc.size,
                TransferState.Status.DONE, location,
            ),
        )
        onFileReceived?.invoke(inc.name, location)
    }

    private suspend fun failIncoming(
        id: String,
        reason: String,
        notifyPeer: Boolean,
        fromDeviceId: String? = null,
    ) {
        val inc = mutex.withLock {
            val candidate = incoming[id] ?: return
            if (fromDeviceId != null && candidate.peer.deviceId != fromDeviceId) return
            incoming.remove(id)
        } ?: return
        inc.pending.discard()
        if (notifyPeer) runCatching { inc.peer.send(ControlMessage.FileError(id, reason)) }
        upsertState(
            TransferState(
                id, inc.peer.deviceId, inc.name, inc.size, false, inc.receivedBytes,
                TransferState.Status.FAILED, reason,
            ),
        )
    }

    // --- Helpers ---

    private fun upsertState(state: TransferState) {
        _transfers.value = (listOf(state) + _transfers.value.filterNot { it.id == state.id }).take(MAX_STATES)
        if (state.status != TransferState.Status.ACTIVE) {
            val dir = if (state.outbound) "send" else "recv"
            val tail = state.detail?.let { " — $it" } ?: ""
            log("file $dir ${state.status.name.lowercase()}: ${state.name} (${state.sizeBytes} B) peer=${state.peerDeviceId}$tail")
        }
    }

    /** AAD binding a chunk to its transfer and position: id (32 bytes) ‖ index (u32 BE). */
    private fun aad(idBytes: ByteArray, index: Int): ByteArray {
        val out = idBytes.copyOf(idBytes.size + 4)
        out[idBytes.size] = (index ushr 24).toByte()
        out[idBytes.size + 1] = (index ushr 16).toByte()
        out[idBytes.size + 2] = (index ushr 8).toByte()
        out[idBytes.size + 3] = index.toByte()
        return out
    }

    private fun streamSha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(CHUNK_BYTES)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
        return ClipsyncCrypto.toHex(digest.digest())
    }

    /** Fills [buf] completely unless the stream ends first; returns bytes read (0 at EOF). */
    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    private fun isHex(s: String) = s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    companion object {
        const val CHUNK_BYTES = 256 * 1024
        const val WINDOW_CHUNKS = 16 // ≤ 4 MiB sealed bytes unacked in flight
        const val ACK_EVERY = 8 // receiver acks every 2 MiB
        const val MAX_FILE_BYTES = 4L * 1024 * 1024 * 1024
        const val MAX_CONCURRENT_INBOUND = 4
        const val TRANSFER_ID_BYTES = 32
        const val SEAL_OVERHEAD_SLACK = 64
        private const val MAX_STATES = 20

        fun chunkCountFor(size: Long): Int = ((size + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt()

        /**
         * A received name must not be able to escape the receive folder or hide itself:
         * basename only, path/control characters replaced, no leading dots, bounded length.
         */
        fun sanitizeName(raw: String): String {
            val base = raw.substringAfterLast('/').substringAfterLast('\\')
            val cleaned = buildString {
                for (c in base) append(if (c.code < 0x20 || c in "\\/:*?\"<>|") '_' else c)
            }.trimStart('.', ' ').trim().take(MAX_NAME_CHARS)
            return cleaned.ifBlank { "file" }
        }

        private const val MAX_NAME_CHARS = 200
    }
}
