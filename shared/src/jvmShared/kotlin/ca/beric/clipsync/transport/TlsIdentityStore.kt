package ca.beric.clipsync.transport

import ca.beric.clipsync.identity.SecretStore
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Persists the device's [TlsIdentity] so its certificate fingerprint is stable across
 * restarts — otherwise a restart would change the fingerprint and break every peer
 * that pinned the old one.
 *
 * The certificate + private key live in a PKCS12 file (a blob too large to put on a
 * command line, so the desktop Keychain's argv-based store holds only the *password*).
 * The random keystore password is kept in [secretStore] (Keychain / Android Keystore);
 * the file itself is inert without it.
 */
class TlsIdentityStore(
    private val keystoreFile: File,
    private val secretStore: SecretStore,
    private val passwordAlias: String = "tls-keystore-password",
) {
    fun loadOrCreate(commonName: String): TlsIdentity {
        load()?.let { return it }
        val identity = TlsIdentity.generate(commonName)
        save(identity)
        return identity
    }

    private fun load(): TlsIdentity? {
        if (!keystoreFile.exists()) return null
        val password = secretStore.get(passwordAlias)?.let { passwordChars(it) } ?: return null
        return runCatching {
            val ks = KeyStore.getInstance("PKCS12")
            keystoreFile.inputStream().use { ks.load(it, password) }
            val cert = ks.getCertificate(ALIAS) as X509Certificate
            val key = ks.getKey(ALIAS, password) as PrivateKey
            TlsIdentity(cert, key)
        }.getOrNull()
    }

    private fun save(identity: TlsIdentity) {
        val secret = secretStore.get(passwordAlias) ?: randomSecret().also { secretStore.put(passwordAlias, it) }
        val password = passwordChars(secret)
        keystoreFile.parentFile?.mkdirs()
        identity.keyStore(ALIAS, password).store(keystoreFile.outputStream(), password)
    }

    private fun randomSecret(): ByteArray = ByteArray(SECRET_BYTES).also { SecureRandom().nextBytes(it) }

    /** Derive a stable char[] password from the stored secret bytes (both save and load agree). */
    private fun passwordChars(secret: ByteArray): CharArray =
        Base64.getEncoder().encodeToString(secret).toCharArray()

    private companion object {
        const val ALIAS = "clipsync"
        const val SECRET_BYTES = 32
    }
}
