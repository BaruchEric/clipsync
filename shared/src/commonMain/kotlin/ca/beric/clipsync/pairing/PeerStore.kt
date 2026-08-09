package ca.beric.clipsync.pairing

import ca.beric.clipsync.crypto.ClipsyncCrypto
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

/**
 * One-line pairing summary, shared by both apps so the format cannot drift — `scripts/pairing-test.sh`
 * asserts against it. [via] records HOW the payload arrived ("wire" = a PairRequest over TLS,
 * "file" = the peer-payload.txt bootstrap, "local" = a QR scan or intent on this device); the harness
 * needs that distinction to tell genuine reciprocal pairing from a file-bootstrapped one.
 * The SAS is a public comparison code derived from the per-pair key, not key material.
 */
fun Peer.pairedLogLine(via: String): String =
    "paired via=$via $deviceId ($deviceName) " +
        "SAS=${ClipsyncCrypto.shortAuthString(perPairKey)} endpoints=$addresses"

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
