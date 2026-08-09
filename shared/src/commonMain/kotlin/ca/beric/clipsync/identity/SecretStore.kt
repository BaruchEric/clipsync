package ca.beric.clipsync.identity

/**
 * Stores small secrets (the device's long-term X25519 private key) in the
 * platform secure store: macOS Keychain on desktop, Android Keystore-backed
 * storage on Android. Keys never touch the SQLDelight database.
 */
expect class SecretStore {
    fun put(alias: String, secret: ByteArray)
    fun get(alias: String): ByteArray?
    fun delete(alias: String)
}
