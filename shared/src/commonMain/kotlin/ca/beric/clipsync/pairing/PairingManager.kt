package ca.beric.clipsync.pairing

import ca.beric.clipsync.crypto.ClipsyncCrypto
import ca.beric.clipsync.identity.DeviceIdentity

/**
 * Turns a scanned/received [PairingPayload] into a persisted [Peer], and produces
 * this device's own payload. The per-pair key is derived here from our long-term
 * X25519 secret and the peer's public key (both sides derive the same key), so a
 * paired peer can be reached later with only what [PeerStore] holds.
 *
 * The peer's dial endpoints are stored as "host:port" strings in [Peer.addresses];
 * [certFingerprint] is what the TLS client pins when dialing that peer.
 */
class PairingManager(
    private val identity: DeviceIdentity.Identity,
    private val peerStore: PeerStore,
) {
    /** This device's pairing payload, for the peer to scan/import. */
    fun myPayload(certFingerprint: String, addresses: List<String>, port: Int): String =
        PairingPayload.of(
            deviceId = identity.deviceId,
            deviceName = identity.deviceName,
            publicKey = identity.keyPair.publicKey,
            certFingerprint = certFingerprint,
            addresses = addresses,
            port = port,
        ).encode()

    /** Parse a peer payload, derive the per-pair key, persist the peer. Null on bad input. */
    fun pair(peerPayloadText: String, nowMs: Long): Peer? {
        val payload = PairingPayload.decode(peerPayloadText) ?: return null
        val perPairKey = ClipsyncCrypto.deriveSharedKey(
            identity.keyPair.secretKey,
            identity.keyPair.publicKey,
            payload.publicKey,
        )
        val endpoints = payload.addresses.map { "$it:${payload.port}" }
        val peer = Peer(
            deviceId = payload.deviceId,
            deviceName = payload.deviceName,
            publicKey = payload.publicKey,
            certFingerprint = payload.certFingerprint,
            perPairKey = perPairKey,
            addresses = endpoints,
            pairedAtMs = nowMs,
        )
        peerStore.save(peer)
        return peer
    }
}
