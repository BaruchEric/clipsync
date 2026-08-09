@file:OptIn(ExperimentalUnsignedTypes::class)

package ca.beric.clipsync.crypto

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import com.ionspin.kotlin.crypto.aead.AuthenticatedEncryptionWithAssociatedData
import com.ionspin.kotlin.crypto.box.Box
import com.ionspin.kotlin.crypto.generichash.GenericHash
import com.ionspin.kotlin.crypto.hash.Hash
import com.ionspin.kotlin.crypto.scalarmult.ScalarMultiplication
import com.ionspin.kotlin.crypto.util.LibsodiumRandom

/**
 * clipsync's cryptography, built on libsodium (ionspin multiplatform bindings).
 *
 * - Payload confidentiality/integrity: XChaCha20-Poly1305 AEAD.
 * - Pairing key agreement: X25519, hashed with both public keys into a symmetric
 *   per-pair key that both devices derive identically regardless of role.
 * - Short Authentication String (SAS): a 6-digit code derived from the per-pair
 *   key, shown on both screens for the user to compare (MITM defense).
 */
object ClipsyncCrypto {

    const val KEY_BYTES = 32
    const val NONCE_BYTES = 24
    const val TAG_BYTES = 16
    const val PUBLIC_KEY_BYTES = 32

    @Volatile
    private var initialized = false

    /** Idempotent; must complete before any other call. */
    suspend fun ensureInitialized() {
        if (initialized) return
        if (!LibsodiumInitializer.isInitialized()) LibsodiumInitializer.initialize()
        initialized = true
    }

    fun randomBytes(size: Int): ByteArray = LibsodiumRandom.buf(size).toByteArray()

    fun randomKey(): ByteArray = randomBytes(KEY_BYTES)

    /** SHA-256, used as an image-transfer id and pre-decrypt integrity check. */
    fun sha256(data: ByteArray): ByteArray = Hash.sha256(data.toUByteArray()).toByteArray()

    fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }

    // --- AEAD: sealed = nonce(24) || ciphertext || tag(16) ---

    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        val nonce = randomBytes(NONCE_BYTES)
        val ct = encrypt(key, nonce, plaintext, aad)
        return nonce + ct
    }

    /** Returns plaintext, or null if the nonce/ciphertext/tag fails authentication. */
    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray? {
        if (sealed.size < NONCE_BYTES + TAG_BYTES) return null
        val nonce = sealed.copyOfRange(0, NONCE_BYTES)
        val ct = sealed.copyOfRange(NONCE_BYTES, sealed.size)
        return decrypt(key, nonce, ct, aad)
    }

    // --- AEAD low-level (explicit nonce), used for interop test vectors ---

    fun encrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray =
        AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfEncrypt(
            plaintext.toUByteArray(), aad.toUByteArray(), nonce.toUByteArray(), key.toUByteArray(),
        ).toByteArray()

    /** Returns plaintext, or null on authentication failure. */
    fun decrypt(key: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray, aad: ByteArray): ByteArray? =
        try {
            AuthenticatedEncryptionWithAssociatedData.xChaCha20Poly1305IetfDecrypt(
                ciphertextAndTag.toUByteArray(), aad.toUByteArray(), nonce.toUByteArray(), key.toUByteArray(),
            ).toByteArray()
        } catch (_: Throwable) {
            null
        }

    // --- X25519 key agreement ---

    data class KeyPair(val publicKey: ByteArray, val secretKey: ByteArray)

    fun generateKeyPair(): KeyPair {
        val kp = Box.keypair()
        return KeyPair(kp.publicKey.toByteArray(), kp.secretKey.toByteArray())
    }

    /**
     * Symmetric per-pair key: X25519(mySecret, theirPublic) hashed with both public
     * keys in sorted order. Both devices compute the same value regardless of role.
     */
    fun deriveSharedKey(mySecret: ByteArray, myPublic: ByteArray, theirPublic: ByteArray): ByteArray {
        val dh = ScalarMultiplication.scalarMultiplication(mySecret.toUByteArray(), theirPublic.toUByteArray())
            .toByteArray()
        val (lo, hi) = orderedPair(myPublic, theirPublic)
        return GenericHash.genericHash((dh + lo + hi).toUByteArray(), KEY_BYTES).toByteArray()
    }

    /**
     * 6-digit SAS from the per-pair key. Identical on both devices (same key), so a
     * matching code on both screens rules out a man-in-the-middle.
     */
    fun shortAuthString(perPairKey: ByteArray): String {
        val digest = GenericHash.genericHash(perPairKey.toUByteArray(), 8).toByteArray()
        var acc = 0L
        for (i in 0 until 6) acc = (acc shl 8) or (digest[i].toLong() and 0xFF)
        val code = (acc % 1_000_000).toString()
        return code.padStart(6, '0')
    }

    private fun orderedPair(a: ByteArray, b: ByteArray): Pair<ByteArray, ByteArray> {
        val cmp = compareBytes(a, b)
        return if (cmp <= 0) a to b else b to a
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }
}
