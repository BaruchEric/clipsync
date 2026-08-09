package ca.beric.clipsync.transport

import ca.beric.clipsync.protocol.ControlMessage
import ca.beric.clipsync.sync.RemotePeer
import ca.beric.clipsync.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Manages TLS connections to paired peers and wires each one into the [engine].
 * clipsync is symmetric P2P: this device runs a server for peers that dial in and
 * also dials out to known peers. Either way, the first message on a link is a
 * [ControlMessage.Hello] that names the peer; we look up its per-pair key and, if
 * known, register it with the engine and route its subsequent messages there.
 */
class ConnectionManager(
    private val localDeviceId: String,
    private val tlsIdentity: TlsIdentity,
    private val engine: SyncEngine,
    private val perPairKeyFor: (peerDeviceId: String) -> ByteArray?,
    private val scope: CoroutineScope,
) {
    private val server = ClipServer(tlsIdentity) { link -> handleLink(link) }

    fun startServer(port: Int, host: String = "0.0.0.0") = server.start(port, host)

    fun stop() = server.stop()

    /** Dial a known peer at [host]:[port], pinning [fingerprint]. */
    suspend fun dial(host: String, port: Int, fingerprint: String) {
        val link = ClipClient.connect(host, port, fingerprint)
        scope.launch { handleLink(link) }
    }

    private suspend fun handleLink(link: PeerLink) {
        link.send(ControlMessage.Hello(localDeviceId))
        var peerId: String? = null
        link.control.collect { message ->
            when (message) {
                is ControlMessage.Hello -> {
                    val key = perPairKeyFor(message.deviceId)
                    if (key != null) {
                        peerId = message.deviceId
                        engine.addPeer(RemotePeer(message.deviceId, key) { link.send(it) })
                    }
                }
                else -> peerId?.let { engine.onRemoteMessage(it, message) }
            }
        }
        peerId?.let { engine.removePeer(it) }
    }
}
