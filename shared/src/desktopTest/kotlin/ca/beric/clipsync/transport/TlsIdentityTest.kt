package ca.beric.clipsync.transport

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TlsIdentityTest {

    @Test
    fun fingerprintIsSha256OfDerEncoding() {
        val id = TlsIdentity.generate("mac-1")
        val expected = MessageDigest.getInstance("SHA-256").digest(id.certificate.encoded)
            .joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
        assertEquals(expected, id.fingerprint)
        assertEquals(64, id.fingerprint.length) // 32 bytes hex
    }

    @Test
    fun distinctIdentitiesHaveDistinctFingerprints() {
        assertNotEquals(TlsIdentity.generate().fingerprint, TlsIdentity.generate().fingerprint)
    }

    @Test
    fun keyStoreHoldsTheCertAndKey() {
        val id = TlsIdentity.generate()
        val ks = id.keyStore(password = "pw".toCharArray())
        assertTrue(ks.containsAlias("clipsync"))
        assertEquals(id.fingerprint, TlsIdentity.sha256Hex(ks.getCertificate("clipsync").encoded))
    }
}
