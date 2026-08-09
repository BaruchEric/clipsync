package ca.beric.clipsync.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ca.beric.clipsync.core.ClipRepository
import ca.beric.clipsync.crypto.ClipsyncCrypto
import ca.beric.clipsync.db.ClipsyncDb
import ca.beric.clipsync.transport.ClipClient
import ca.beric.clipsync.transport.ClipServer
import ca.beric.clipsync.transport.PeerLink
import ca.beric.clipsync.transport.TlsIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end sync over the real TLS transport: two [SyncEngine]s, one per side,
 * exchange an encrypted clipboard value. Proves a copy on A reaches B's clipboard
 * and that applying it on B does not echo back to A.
 */
class LoopbackEngineTest {

    private var server: ClipServer? = null
    private val keepAlive = CompletableDeferred<Unit>()

    @AfterTest
    fun tearDown() {
        keepAlive.complete(Unit)
        server?.stop()
    }

    private class RecordingApplier : ClipboardApplier {
        val applied = mutableListOf<String>()
        override fun applyText(text: String) { applied += text }
    }

    private fun repo(): ClipRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ClipsyncDb.Schema.create(driver)
        return ClipRepository(ClipsyncDb(driver))
    }

    @Test
    fun copyOnAReachesBWithNoEcho() = runBlocking {
        ClipsyncCrypto.ensureInitialized()
        val identity = TlsIdentity.generate("A")
        val ka = ClipsyncCrypto.generateKeyPair()
        val kb = ClipsyncCrypto.generateKeyPair()
        val keyA = ClipsyncCrypto.deriveSharedKey(ka.secretKey, ka.publicKey, kb.publicKey)
        val keyB = ClipsyncCrypto.deriveSharedKey(kb.secretKey, kb.publicKey, ka.publicKey)

        val applierA = RecordingApplier()
        val applierB = RecordingApplier()
        val engineA = SyncEngine("A", repo(), applierA)
        val engineB = SyncEngine("B", repo(), applierB)

        withTimeout(15_000) {
            val serverReady = CompletableDeferred<PeerLink>()
            server = ClipServer(identity) { link -> serverReady.complete(link); keepAlive.await() }
                .also { it.start(17791, host = "127.0.0.1") }
            val clientLink = ClipClient.connect("127.0.0.1", 17791, identity.fingerprint)
            val serverLink = serverReady.await()

            // route inbound control messages into each engine (cancelled before the block returns
            // so structured concurrency doesn't wait on these forever-running collectors)
            val collectors = listOf(
                launch { serverLink.control.collect { engineA.onRemoteMessage("B", it) } },
                launch { clientLink.control.collect { engineB.onRemoteMessage("A", it) } },
            )

            engineA.addPeer(RemotePeer("B", keyA) { serverLink.send(it) })
            engineB.addPeer(RemotePeer("A", keyB) { clientLink.send(it) })

            // A copies → B should apply it
            engineA.onLocalCapture("hello from A", nowMs = 100)
            withTimeout(5_000) { while (applierB.applied.isEmpty()) delay(20) }
            assertEquals("hello from A", applierB.applied.last())

            // B's watcher would now see the applied text; that capture must be suppressed (no echo)
            engineB.onLocalCapture("hello from A", nowMs = 101)
            delay(300)
            assertTrue(applierA.applied.isEmpty(), "A should not receive an echo of its own value")

            // A genuinely copies something new → still flows
            engineA.onLocalCapture("second", nowMs = 200)
            withTimeout(5_000) { while (applierB.applied.size < 2) delay(20) }
            assertEquals("second", applierB.applied.last())

            collectors.forEach { it.cancel() }
        }
    }
}
