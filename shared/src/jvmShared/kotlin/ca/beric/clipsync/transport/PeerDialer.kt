package ca.beric.clipsync.transport

import ca.beric.clipsync.pairing.PeerStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps outbound links to every paired peer alive. Each tick it dials any known peer
 * that has dial endpoints and is neither connected nor already being dialed. A failed
 * dial backs that peer off exponentially (up to [maxDelayMs]) so a peer that is simply
 * offline — the common case on a phone off-network — does not cause a wakeup every tick;
 * a successful connection resets its backoff.
 *
 * Discovery (mDNS/tailnet) and inbound links compose with this: [ConnectionManager]
 * dedups links per peer, so whichever side connects first wins and the dialer's attempt
 * to the same peer simply no-ops.
 */
class PeerDialer(
    private val manager: ConnectionManager,
    private val peerStore: PeerStore,
    private val scope: CoroutineScope,
    private val tickMs: Long = 2_000,
    private val baseDelayMs: Long = 2_000,
    private val maxDelayMs: Long = 60_000,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val nextAttemptAt = ConcurrentHashMap<String, Long>()
    private val currentDelay = ConcurrentHashMap<String, Long>()

    fun start(): Job = scope.launch {
        while (isActive) {
            for (peer in peerStore.all()) {
                if (peer.addresses.isEmpty()) continue
                if (manager.isConnected(peer.deviceId) || manager.isDialing(peer.deviceId)) continue
                if (nowMs() < (nextAttemptAt[peer.deviceId] ?: 0L)) continue
                launch { attempt(peer.deviceId, peer.addresses, peer.certFingerprint) }
            }
            delay(tickMs)
        }
    }

    private suspend fun attempt(deviceId: String, endpoints: List<String>, fingerprint: String) {
        val connected = manager.dialPeer(deviceId, endpoints, fingerprint)
        if (connected) {
            // dialPeer returns when the (previously live) link closed — retry promptly.
            currentDelay.remove(deviceId)
            nextAttemptAt.remove(deviceId)
        } else {
            val next = ((currentDelay[deviceId] ?: baseDelayMs) * 2).coerceAtMost(maxDelayMs)
            currentDelay[deviceId] = next
            nextAttemptAt[deviceId] = nowMs() + next
        }
    }
}
