package ca.beric.clipsync.transport

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ca.beric.clipsync.core.ClipRepository
import ca.beric.clipsync.crypto.ClipsyncCrypto
import ca.beric.clipsync.db.ClipsyncDb
import ca.beric.clipsync.sync.ClipboardApplier
import ca.beric.clipsync.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Proves the full app wiring — two ConnectionManagers over real TLS syncing a clip. */
class ConnectionManagerTest {

    private val scope = CoroutineScope(SupervisorJob())
    private var a: ConnectionManager? = null
    private var b: ConnectionManager? = null

    @AfterTest
    fun tearDown() {
        a?.stop(); b?.stop(); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
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
    fun serverAndDialerSyncOverTls() = runBlocking {
        ClipsyncCrypto.ensureInitialized()
        val idA = TlsIdentity.generate("A")
        val ka = ClipsyncCrypto.generateKeyPair()
        val kb = ClipsyncCrypto.generateKeyPair()
        val keyA = ClipsyncCrypto.deriveSharedKey(ka.secretKey, ka.publicKey, kb.publicKey)
        val keyB = ClipsyncCrypto.deriveSharedKey(kb.secretKey, kb.publicKey, ka.publicKey)

        val applierB = RecordingApplier()
        val engineA = SyncEngine("A", repo(), RecordingApplier())
        val engineB = SyncEngine("B", repo(), applierB)

        a = ConnectionManager("A", idA, engineA, { if (it == "B") keyA else null }, scope)
            .also { it.startServer(17795, host = "127.0.0.1") }
        b = ConnectionManager("B", TlsIdentity.generate("B"), engineB, { if (it == "A") keyB else null }, scope)

        withTimeout(15_000) {
            b!!.dial("127.0.0.1", 17795, idA.fingerprint)
            // give the Hello handshake a moment to register peers both ways
            delay(500)
            engineA.onLocalCapture("synced!", nowMs = 100)
            withTimeout(5_000) { while (applierB.applied.isEmpty()) delay(20) }
            assertEquals("synced!", applierB.applied.last())
        }
    }
}
