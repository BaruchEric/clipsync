package ca.beric.clipsync.transport

import ca.beric.clipsync.identity.SecretStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Persistence must keep the certificate fingerprint identical, or pinning breaks on restart. */
class TlsIdentityStoreTest {

    private val file = File.createTempFile("clipsync-tls", ".p12").also { it.delete() }
    private val alias = "tls-test-${System.nanoTime()}"
    private val secretStore = SecretStore()

    @AfterTest
    fun cleanup() {
        secretStore.delete(alias)
        file.delete()
    }

    @Test
    fun fingerprintStableAcrossReload() {
        val first = TlsIdentityStore(file, secretStore, alias).loadOrCreate("test")
        // A fresh store over the same file + password alias must load, not regenerate.
        val second = TlsIdentityStore(file, secretStore, alias).loadOrCreate("test")
        assertEquals(first.fingerprint, second.fingerprint)
        assertEquals(first.certificate.encoded.toList(), second.certificate.encoded.toList())
    }

    @Test
    fun freshFileYieldsNewIdentity() {
        val a = TlsIdentityStore(file, secretStore, alias).loadOrCreate("test")
        file.delete()
        secretStore.delete(alias)
        val b = TlsIdentityStore(file, secretStore, alias).loadOrCreate("test")
        assertNotEquals(a.fingerprint, b.fingerprint)
    }
}
