package ca.beric.clipsync.pairing

import ca.beric.clipsync.db.ClipsyncDb

data class Peer(
    val deviceId: String,
    val deviceName: String,
    val publicKey: ByteArray,
    val certFingerprint: String,
    val perPairKey: ByteArray,
    val addresses: List<String>,
    val pairedAtMs: Long,
)

/** Persists paired peers (their keys, pinned cert fingerprint, and last-known addresses). */
class PeerStore(private val db: ClipsyncDb) {

    private val queries get() = db.peerQueries

    fun save(peer: Peer) {
        queries.upsert(
            peer.deviceId,
            peer.deviceName,
            peer.publicKey,
            peer.certFingerprint,
            peer.perPairKey,
            peer.addresses.joinToString(","),
            peer.pairedAtMs,
        )
    }

    fun all(): List<Peer> = queries.selectAll(::toPeer).executeAsList()

    fun get(deviceId: String): Peer? = queries.getById(deviceId, ::toPeer).executeAsOneOrNull()

    /** Refresh a peer's address hints (e.g. a new tailnet IP seen at connect time). */
    fun updateAddresses(deviceId: String, addresses: List<String>) {
        queries.updateAddresses(addresses.joinToString(","), deviceId)
    }

    fun remove(deviceId: String) = queries.delete(deviceId)

    @Suppress("LongParameterList")
    private fun toPeer(
        deviceId: String,
        deviceName: String,
        publicKey: ByteArray,
        certFingerprint: String,
        perPairKey: ByteArray,
        addresses: String,
        pairedAtMs: Long,
    ) = Peer(
        deviceId = deviceId,
        deviceName = deviceName,
        publicKey = publicKey,
        certFingerprint = certFingerprint,
        perPairKey = perPairKey,
        addresses = if (addresses.isEmpty()) emptyList() else addresses.split(","),
        pairedAtMs = pairedAtMs,
    )
}
