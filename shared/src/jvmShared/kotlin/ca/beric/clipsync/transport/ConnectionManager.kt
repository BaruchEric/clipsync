package ca.beric.clipsync.transport

import ca.beric.clipsync.protocol.ControlMessage
import ca.beric.clipsync.sync.RemotePeer
import ca.beric.clipsync.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages TLS connections to paired peers and wires each one into the [engine].
 * clipsync is symmetric P2P: a device with a [tlsIdentity] runs a server for peers
 * that dial in, and any device also dials out to known peers. Either way, the first
 * message on a link is a [ControlMessage.Hello] naming the peer; we look up its
 * per-pair key and, if known, register it with the engine and route its subsequent
 * messages there. An unknown peer's link is closed so the dialer can retry later.
 *
 * [tlsIdentity] may be null for a client-only node (it dials but never serves); in
 * that case [startServer] is a no-op. The dial path presents no client certificate,
 * so a client-only node needs no cert of its own.
 */
class ConnectionManager(
    private val localDeviceId: String,
    private val tlsIdentity: TlsIdentity?,
    private val engine: SyncEngine,
    private val perPairKeyFor: (peerDeviceId: String) -> ByteArray?,
    private val scope: CoroutineScope,
) {
    private val server = tlsIdentity?.let { id -> ClipServer(id) { link -> handleLink(link) } }

    /** Device ids with an in-flight or open outbound (dialed) link, to avoid re-dialing. */
    private val dialing = ConcurrentHashMap.newKeySet<String>()

    fun startServer(port: Int, host: String = "0.0.0.0") { server?.start(port, host) }

    fun stop() { server?.stop() }

    /** True if an outbound dial to [deviceId] is currently in flight or connected. */
    fun isDialing(deviceId: String): Boolean = dialing.contains(deviceId)

    /** One-shot dial (used by tests): connect to [host]:[port] and handle in the background. */
    suspend fun dial(host: String, port: Int, fingerprint: String) {
        val link = ClipClient.connect(host, port, fingerprint)
        scope.launch { handleLink(link) }
    }

    /**
     * Idempotent dial-and-hold for a known peer. No-ops if a link to [deviceId] is
     * already in flight or open. Tries [endpoints] ("host:port") in order and, on the
     * first that connects, suspends until the link closes; then frees the slot so a
     * scheduler can re-dial. [fingerprint] is pinned against the server's certificate.
     */
    suspend fun dialPeer(deviceId: String, endpoints: List<String>, fingerprint: String) {
        if (!dialing.add(deviceId)) return
        try {
            for (endpoint in endpoints) {
                val (host, port) = parseEndpoint(endpoint) ?: continue
                val link = runCatching { ClipClient.connect(host, port, fingerprint) }.getOrNull() ?: continue
                handleLink(link) // suspends until the socket closes
                break
            }
        } finally {
            dialing.remove(deviceId)
        }
    }

    private suspend fun handleLink(link: PeerLink) {
        link.send(ControlMessage.Hello(localDeviceId))
        var peerId: String? = null
        try {
            link.control.collect { message ->
                when (message) {
                    is ControlMessage.Hello -> {
                        val key = perPairKeyFor(message.deviceId)
                        if (key != null) {
                            peerId = message.deviceId
                            engine.addPeer(RemotePeer(message.deviceId, key) { link.send(it) })
                        } else {
                            // Not paired (yet): don't hold a zombie link — let the peer retry.
                            link.close()
                        }
                    }
                    else -> peerId?.let { engine.onRemoteMessage(it, message) }
                }
            }
        } finally {
            peerId?.let { engine.removePeer(it) }
        }
    }

    private fun parseEndpoint(endpoint: String): Pair<String, Int>? {
        val idx = endpoint.lastIndexOf(':')
        if (idx <= 0 || idx == endpoint.length - 1) return null
        val host = endpoint.substring(0, idx)
        val port = endpoint.substring(idx + 1).toIntOrNull() ?: return null
        return host to port
    }
}
