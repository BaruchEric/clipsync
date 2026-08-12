package ca.beric.clipsync.transport

import ca.beric.clipsync.protocol.ControlMessage
import ca.beric.clipsync.sync.RemotePeer
import ca.beric.clipsync.sync.SyncEngine
import ca.beric.clipsync.transfer.FileTransferEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    /** This device's pairing payload JSON, offered to a peer during reciprocal pairing. */
    private val myPayload: () -> String? = { null },
    /** Persists a peer from a received payload and returns its device id (or null to reject). */
    private val pairingSink: ((payloadJson: String) -> String?)? = null,
    /** When set, peers are also registered here and file messages/chunks are routed to it. */
    private val fileEngine: FileTransferEngine? = null,
    /** This device's current dial endpoints ("host:port"), carried in every Hello it sends. */
    private val myEndpoints: () -> List<String> = { emptyList() },
    /** Persists a registered peer's refreshed endpoints (from its Hello), so addresses can't rot. */
    private val endpointSink: ((deviceId: String, endpoints: List<String>) -> Unit)? = null,
) {
    /** Peers (by device id) owed our payload after a QR scan, so the camera-less side pairs too. */
    private val pendingReciprocal = ConcurrentHashMap.newKeySet<String>()

    /** Live links by registered peer id, for messages outside the engines (reciprocal pairing). */
    private val links = ConcurrentHashMap<String, PeerLink>()

    /**
     * Call after scanning [peerDeviceId]'s QR: sends our payload back over the existing link
     * right away (a re-scan of an already-connected peer refreshes it immediately), or arms
     * it for when that peer's Hello arrives on the next link.
     */
    fun offerReciprocalPairing(peerDeviceId: String) {
        val link = links[peerDeviceId]
        if (link != null) {
            scope.launch { myPayload()?.let { link.send(ControlMessage.PairRequest(it)) } }
        } else {
            pendingReciprocal.add(peerDeviceId)
        }
    }
    private val server = tlsIdentity?.let { id -> ClipServer(id) { link -> handleLink(link) } }

    /** Device ids with an in-flight or open outbound (dialed) link, to avoid re-dialing. */
    private val dialing = ConcurrentHashMap.newKeySet<String>()

    /** Device ids with an active link (inbound or outbound) — dedups links per peer. */
    private val connected = ConcurrentHashMap.newKeySet<String>()

    private val _connectedPeers = MutableStateFlow<Set<String>>(emptySet())

    /** Live set of connected peer device ids, for status UI. */
    val connectedPeers: StateFlow<Set<String>> = _connectedPeers

    fun startServer(port: Int, host: String = "0.0.0.0") { server?.start(port, host) }

    fun stop() { server?.stop() }

    /** True if an outbound dial to [deviceId] is currently in flight. */
    fun isDialing(deviceId: String): Boolean = dialing.contains(deviceId)

    /** True if a live link to [deviceId] exists (either direction). */
    fun isConnected(deviceId: String): Boolean = connected.contains(deviceId)

    /** One-shot dial (used by tests): connect to [host]:[port] and handle in the background. */
    suspend fun dial(host: String, port: Int, fingerprint: String) {
        val link = ClipClient.connect(host, port, fingerprint)
        scope.launch { handleLink(link) }
    }

    /**
     * Idempotent dial-and-hold for a known peer. No-ops if a dial to [deviceId] is already
     * in flight. Tries [endpoints] ("host:port") in order; on the first that connects,
     * suspends until the link closes and returns true. Returns false if nothing connected.
     * [fingerprint] is pinned against the server's certificate.
     */
    suspend fun dialPeer(deviceId: String, endpoints: List<String>, fingerprint: String): Boolean {
        if (!dialing.add(deviceId)) return false
        try {
            for (endpoint in endpoints) {
                val (host, port) = parseEndpoint(endpoint) ?: continue
                val link = runCatching { ClipClient.connect(host, port, fingerprint) }.getOrNull() ?: continue
                // Report whether a peer actually registered (Hello accepted), NOT merely that
                // the socket opened — else an unknown-peer/glare close would reset the backoff.
                return runCatching { handleLink(link) }.getOrDefault(false)
            }
            return false
        } finally {
            dialing.remove(deviceId)
        }
    }

    /** Returns true if a peer was registered on this link (a valid Hello / pairing was accepted). */
    private suspend fun handleLink(link: PeerLink): Boolean {
        link.send(ControlMessage.Hello(localDeviceId, endpoints = myEndpoints()))
        // Image and file chunks arrive as binary frames; each engine claims frames by its own
        // transfer ids and ignores the rest, so routing to both is safe.
        val binaryPump = scope.launch {
            link.binary.collect {
                engine.onBinaryFrame(it)
                fileEngine?.onBinaryFrame(it)
            }
        }
        var peerId: String? = null

        // Registers the peer once its per-pair key is known. Either a Hello (peer already
        // paired) or a PairRequest (peer just paired us over the wire) can trigger it.
        suspend fun register(id: String) {
            if (peerId != null) return
            val key = perPairKeyFor(id) ?: return // unknown peer: wait (a PairRequest may follow)
            if (!connected.add(id)) { link.close(); return } // already linked: drop the duplicate
            peerId = id
            val remote = RemotePeer(id, key, send = { link.send(it) }, sendChunk = { link.sendChunk(it) })
            engine.addPeer(remote)
            fileEngine?.addPeer(remote)
            links[id] = link
            _connectedPeers.value = connected.toSet()
            if (pendingReciprocal.remove(id)) myPayload()?.let { link.send(ControlMessage.PairRequest(it)) }
        }

        try {
            link.control.collect { message ->
                when (message) {
                    is ControlMessage.Hello -> {
                        register(message.deviceId)
                        // Refresh stored addresses only for a peer that actually registered
                        // (we hold its per-pair key); see the Hello doc for the trust caveat.
                        if (peerId == message.deviceId && message.endpoints.isNotEmpty()) {
                            endpointSink?.invoke(message.deviceId, message.endpoints)
                        }
                    }
                    is ControlMessage.PairRequest -> pairingSink?.invoke(message.payload)?.let { register(it) }
                    is ControlMessage.FileOffer, is ControlMessage.FileAck, is ControlMessage.FileError ->
                        peerId?.let { fileEngine?.onRemoteMessage(it, message) }
                    else -> peerId?.let { engine.onRemoteMessage(it, message) }
                }
            }
        } finally {
            binaryPump.cancel()
            peerId?.let {
                connected.remove(it)
                links.remove(it)
                _connectedPeers.value = connected.toSet()
                engine.removePeer(it)
                fileEngine?.removePeer(it)
            }
        }
        return peerId != null
    }

    private fun parseEndpoint(endpoint: String): Pair<String, Int>? {
        val idx = endpoint.lastIndexOf(':')
        if (idx <= 0 || idx == endpoint.length - 1) return null
        val host = endpoint.substring(0, idx)
        val port = endpoint.substring(idx + 1).toIntOrNull() ?: return null
        return host to port
    }
}
