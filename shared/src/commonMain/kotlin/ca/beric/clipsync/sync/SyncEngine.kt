package ca.beric.clipsync.sync

import ca.beric.clipsync.core.ClipRepository
import ca.beric.clipsync.crypto.ClipsyncCrypto
import ca.beric.clipsync.protocol.ClipVersion
import ca.beric.clipsync.protocol.ControlMessage
import ca.beric.clipsync.protocol.LwwResolver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Writes a received value into the local system clipboard. Platform-specific. */
interface ClipboardApplier {
    fun applyText(text: String)
}

/** A connected, paired peer: how to reach it and the key to talk to it. */
class RemotePeer(
    val deviceId: String,
    val perPairKey: ByteArray,
    val send: suspend (ControlMessage) -> Unit,
)

/**
 * The sync brain, independent of transport. On a local copy it stamps a version,
 * seals the payload per peer, and broadcasts. On a remote update it applies LWW,
 * decrypts, records history, then writes the value to the local clipboard.
 *
 * Echo suppression: applying a remote value re-triggers local capture; the applied
 * text is remembered once so it is not immediately rebroadcast, and the per-device
 * LWW counter rejects any that slips through.
 *
 * Thread safety: capture runs on the poll coroutine while peers are added/removed on
 * connection coroutines (Netty/OkHttp threads). All mutable state ([peers], [counter],
 * [suppressedEcho], [lww]) is guarded by [mutex]; network sends happen outside the lock
 * against a snapshot so I/O never blocks other engine operations.
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

    suspend fun addPeer(peer: RemotePeer) = mutex.withLock {
        peers[peer.deviceId] = peer
    }

    suspend fun removePeer(deviceId: String) = mutex.withLock {
        peers.remove(deviceId)
        Unit
    }

    /** Called when this device captures a new local clipboard value. */
    suspend fun onLocalCapture(text: String, nowMs: Long) {
        val version: ClipVersion
        val targets: List<RemotePeer>
        mutex.withLock {
            if (text == suppressedEcho) {
                suppressedEcho = null
                return
            }
            version = ClipVersion(deviceId, ++counter, nowMs)
            lww.recordLocal(version)
            targets = peers.values.toList()
        }
        for (peer in targets) {
            val sealed = ClipsyncCrypto.seal(peer.perPairKey, text.encodeToByteArray())
            peer.send(ControlMessage.ClipUpdate.of(version, "text", sealed))
        }
    }

    /** Called for each control message arriving from [fromDeviceId]. */
    suspend fun onRemoteMessage(fromDeviceId: String, message: ControlMessage) {
        when (message) {
            is ControlMessage.ClipUpdate -> applyRemoteClip(fromDeviceId, message)
            is ControlMessage.Hello, is ControlMessage.ImageUpdate -> Unit // image path handled elsewhere
        }
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
        // Record before applying: applying re-triggers local capture, which could
        // otherwise record the value as LOCAL_DEVICE_ID first and mis-attribute history.
        repository.record(update.version.deviceId, text, update.version.wallClockMs)
        applier.applyText(text)
    }
}
