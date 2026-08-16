package ca.beric.clipsync.identity

import ca.beric.clipsync.crypto.ClipsyncCrypto
import ca.beric.clipsync.db.ClipsyncDb

/**
 * The device's stable identity: a persistent X25519 keypair and a random device
 * id, created once and reused. The private key lives in [SecretStore]; the public
 * key and id live in the database. If either half is missing, a fresh identity is
 * generated (and overwrites any orphaned half).
 */
class DeviceIdentity(
    private val db: ClipsyncDb,
    private val secretStore: SecretStore,
    private val secretAlias: String = "device-identity-secret",
) {
    data class Identity(
        val deviceId: String,
        val deviceName: String,
        val keyPair: ClipsyncCrypto.KeyPair,
    )

    suspend fun getOrCreate(defaultName: String): Identity {
        ClipsyncCrypto.ensureInitialized()
        val row = db.identityQueries.get().executeAsOneOrNull()
        val secret = secretStore.get(secretAlias)
        if (row != null && secret != null) {
            return Identity(row.device_id, row.device_name, ClipsyncCrypto.KeyPair(row.public_key, secret))
        }
        val keyPair = ClipsyncCrypto.generateKeyPair()
        val deviceId = ClipsyncCrypto.toHex(ClipsyncCrypto.randomBytes(8))
        secretStore.put(secretAlias, keyPair.secretKey)
        db.identityQueries.put(deviceId, defaultName, keyPair.publicKey)
        return Identity(deviceId, defaultName, keyPair)
    }
}
