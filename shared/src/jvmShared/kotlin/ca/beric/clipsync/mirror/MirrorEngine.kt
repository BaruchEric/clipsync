package ca.beric.clipsync.mirror

import ca.beric.clipsync.crypto.ClipsyncCrypto
import ca.beric.clipsync.protocol.ControlMessage
import ca.beric.clipsync.protocol.MirrorCodec
import ca.beric.clipsync.protocol.MirrorEvent
import ca.beric.clipsync.sync.RemotePeer
import java.util.concurrent.ConcurrentHashMap

/**
 * Carries [MirrorEvent]s (phone notifications, messages) between paired peers. Events are
 * sealed with the per-pair key inside a [ControlMessage.Mirror] envelope, so their content is
 * E2E-encrypted like clip payloads. What each side *does* with an event stays in the apps:
 * [onEvent] is invoked on the transport reader coroutine and must not block.
 */
class MirrorEngine(
    private val onEvent: (fromDeviceId: String, event: MirrorEvent) -> Unit,
    private val log: (String) -> Unit = {},
) {
    private val peers = ConcurrentHashMap<String, RemotePeer>()

    fun addPeer(peer: RemotePeer) {
        peers[peer.deviceId] = peer
    }

    fun removePeer(deviceId: String) {
        peers.remove(deviceId)
    }

    /** Seals and sends [event] to [peerDeviceId], or to every peer when null. False if none reachable. */
    suspend fun send(peerDeviceId: String?, event: MirrorEvent): Boolean {
        val targets = if (peerDeviceId == null) peers.values.toList() else listOfNotNull(peers[peerDeviceId])
        if (targets.isEmpty()) return false
        val plain = MirrorCodec.encode(event).encodeToByteArray()
        var any = false
        for (peer in targets) {
            runCatching {
                val sealed = ClipsyncCrypto.seal(peer.perPairKey, plain)
                peer.send(ControlMessage.Mirror.of(sealed))
                any = true
            }.onFailure { log("mirror send to ${peer.deviceId} failed: ${it.message}") }
        }
        return any
    }

    fun onRemoteMessage(fromDeviceId: String, message: ControlMessage.Mirror) {
        val peer = peers[fromDeviceId] ?: return
        val sealed = runCatching { message.sealedBytes }.getOrNull() ?: return
        val plain = ClipsyncCrypto.open(peer.perPairKey, sealed) ?: return // auth failure: drop
        val event = MirrorCodec.decode(plain.decodeToString()) ?: return // unknown subtype: drop
        onEvent(fromDeviceId, event)
    }
}
