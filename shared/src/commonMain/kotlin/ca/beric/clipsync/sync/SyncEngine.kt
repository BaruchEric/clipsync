package ca.beric.clipsync.sync

import ca.beric.clipsync.core.ClipRepository
import ca.beric.clipsync.crypto.ClipsyncCrypto
import ca.beric.clipsync.protocol.ClipVersion
import ca.beric.clipsync.protocol.ControlMessage
import ca.beric.clipsync.protocol.LwwResolver

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
 * decrypts, writes the value to the local clipboard, and records history.
 *
 * Echo suppression: applying a remote value re-triggers local capture; the applied
 * text is remembered once so it is not immediately rebroadcast, and the per-device
 * LWW counter rejects any that slips through.
 */
class SyncEngine(
    private val deviceId: String,
    private val repository: ClipRepository,
    private val applier: ClipboardApplier,
) {
    private val lww = LwwResolver()
    private val peers = mutableMapOf<String, RemotePeer>()
    private var counter = 0L
    private var suppressedEcho: String? = null

    fun addPeer(peer: RemotePeer) {
        peers[peer.deviceId] = peer
    }

    fun removePeer(deviceId: String) {
        peers.remove(deviceId)
    }

    /** Called when this device captures a new local clipboard value. */
    suspend fun onLocalCapture(text: String, nowMs: Long) {
        if (text == suppressedEcho) {
            suppressedEcho = null
            return
        }
        val version = ClipVersion(deviceId, ++counter, nowMs)
        lww.recordLocal(version)
        for (peer in peers.values) {
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
        val peer = peers[fromDeviceId] ?: return
        if (!lww.accept(update.version)) return
        val plain = ClipsyncCrypto.open(peer.perPairKey, update.sealedBytes) ?: return
        val text = plain.decodeToString()
        suppressedEcho = text
        applier.applyText(text)
        repository.record(update.version.deviceId, text, update.version.wallClockMs)
    }
}
