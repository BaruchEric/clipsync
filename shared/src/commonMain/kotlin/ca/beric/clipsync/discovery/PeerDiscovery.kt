package ca.beric.clipsync.discovery

/** A clipsync instance found on the local network via mDNS. */
data class DiscoveredService(
    val deviceId: String,
    val host: String,
    val port: Int,
)

/**
 * LAN peer discovery over mDNS (service type `_clipsync._tcp`). An instance advertises
 * this device and browses for others; every resolved peer (excluding self) is delivered
 * to the callback passed to [start].
 *
 * Discovery only supplies where a peer is right now (host:port + its device id). It is
 * never trusted for the certificate fingerprint — the caller pins the fingerprint stored
 * at pairing time and dials only peers it has actually paired with.
 *
 * Implementations: JmDNS (desktop), NsdManager (Android).
 */
interface PeerDiscovery {
    fun start(deviceId: String, port: Int, onPeer: (DiscoveredService) -> Unit)
    fun stop()
}
