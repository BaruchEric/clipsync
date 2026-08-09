package ca.beric.clipsync.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ClipsyncCryptoTest {

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "").replace("\n", "")
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    @Test
    fun sealOpenRoundTrips() = runTest {
        ClipsyncCrypto.ensureInitialized()
        val key = ClipsyncCrypto.randomKey()
        val message = "hello clipsync 📋".encodeToByteArray()
        val sealed = ClipsyncCrypto.seal(key, message)
        val opened = ClipsyncCrypto.open(key, sealed)
        assertContentEquals(message, opened)
    }

    @Test
    fun tamperedCiphertextIsRejected() = runTest {
        ClipsyncCrypto.ensureInitialized()
        val key = ClipsyncCrypto.randomKey()
        val sealed = ClipsyncCrypto.seal(key, "secret".encodeToByteArray())
        // flip a bit in the ciphertext body (past the 24-byte nonce)
        val tampered = sealed.copyOf().also { it[30] = (it[30].toInt() xor 0x01).toByte() }
        assertNull(ClipsyncCrypto.open(key, tampered))
    }

    @Test
    fun wrongKeyIsRejected() = runTest {
        ClipsyncCrypto.ensureInitialized()
        val sealed = ClipsyncCrypto.seal(ClipsyncCrypto.randomKey(), "secret".encodeToByteArray())
        assertNull(ClipsyncCrypto.open(ClipsyncCrypto.randomKey(), sealed))
    }

    /** XChaCha20-Poly1305-IETF test vector from draft-arciszewski-xchacha-03 A.3.1. */
    @Test
    fun matchesXChaCha20Poly1305DraftVector() = runTest {
        ClipsyncCrypto.ensureInitialized()
        val key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val nonce = hex("404142434445464748494a4b4c4d4e4f5051525354555657")
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val plaintext = ("Ladies and Gentlemen of the class of '99: If I could offer you only " +
            "one tip for the future, sunscreen would be it.").encodeToByteArray()
        val expectedCt = hex(
            "bd6d179d3e83d43b9576579493c0e939572a1700252bfaccbed2902c21396cbb731c7f1b0b4a" +
                "a6440bf3a82f4eda7e39ae64c6708c54c216cb96b72e1213b4522f8c9ba40db5d945b11b69b98" +
                "2c1bb9e3f3fac2bc369488f76b2383565d3fff921f9664c97637da9768812f615c68b13b52e",
        )
        val tag = hex("c0875924c1c7987947deafd8780acf49")
        val expected = expectedCt + tag

        val ct = ClipsyncCrypto.encrypt(key, nonce, plaintext, aad)
        assertContentEquals(expected, ct)

        val decrypted = ClipsyncCrypto.decrypt(key, nonce, expected, aad)
        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun bothDevicesDeriveSameSharedKeyAndSas() = runTest {
        ClipsyncCrypto.ensureInitialized()
        val a = ClipsyncCrypto.generateKeyPair()
        val b = ClipsyncCrypto.generateKeyPair()

        val keyOnA = ClipsyncCrypto.deriveSharedKey(a.secretKey, a.publicKey, b.publicKey)
        val keyOnB = ClipsyncCrypto.deriveSharedKey(b.secretKey, b.publicKey, a.publicKey)
        assertContentEquals(keyOnA, keyOnB)

        val sasA = ClipsyncCrypto.shortAuthString(keyOnA)
        val sasB = ClipsyncCrypto.shortAuthString(keyOnB)
        assertEquals(sasA, sasB)
        assertEquals(6, sasA.length)

        // a payload sealed by A opens on B with the derived key
        val sealed = ClipsyncCrypto.seal(keyOnA, "cross-device".encodeToByteArray())
        assertContentEquals("cross-device".encodeToByteArray(), ClipsyncCrypto.open(keyOnB, sealed))
    }

    @Test
    fun differentPairsDeriveDifferentKeys() = runTest {
        ClipsyncCrypto.ensureInitialized()
        val a = ClipsyncCrypto.generateKeyPair()
        val b = ClipsyncCrypto.generateKeyPair()
        val c = ClipsyncCrypto.generateKeyPair()
        val ab = ClipsyncCrypto.deriveSharedKey(a.secretKey, a.publicKey, b.publicKey)
        val ac = ClipsyncCrypto.deriveSharedKey(a.secretKey, a.publicKey, c.publicKey)
        assertNotNull(ab)
        assert(!ab.contentEquals(ac))
    }
}
