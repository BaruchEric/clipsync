package ca.beric.clipsync.transport

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ca.beric.clipsync.core.ClipRepository
import ca.beric.clipsync.crypto.ClipsyncCrypto
import ca.beric.clipsync.db.ClipsyncDb
import ca.beric.clipsync.sync.ClipboardApplier
import ca.beric.clipsync.sync.SyncEngine
import ca.beric.clipsync.transfer.FileSource
import ca.beric.clipsync.transfer.FileTransferEngine
import ca.beric.clipsync.transfer.FolderFileSink
import ca.beric.clipsync.transfer.TransferState
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Full app wiring for files: two [ConnectionManager]s over real pinned TLS, each carrying a
 * [SyncEngine] AND a [FileTransferEngine]. Proves the manager routes file control messages and
 * binary chunk frames to the file engine (and that clipboard sync still works on the same
 * link), with a multi-chunk file arriving byte-identical — the desktop⇄Android path minus
 * the devices.
 */
class FileOverTlsTest {

    private val scope = CoroutineScope(SupervisorJob())
    private var a: ConnectionManager? = null
    private var b: ConnectionManager? = null
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        a?.stop(); b?.stop(); scope.cancel()
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun tempDir(): File =
        Files.createTempDirectory("clipsync-file-tls").toFile().also { tempDirs += it }

    private class RecordingApplier : ClipboardApplier {
        val applied = mutableListOf<String>()
        override fun applyText(text: String) { applied += text }
        override fun applyImage(bytes: ByteArray, mime: String) = Unit
    }

    private fun repo(): ClipRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ClipsyncDb.Schema.create(driver)
        return ClipRepository(ClipsyncDb(driver))
    }

    @Test
    fun fileStreamsOverTlsAndClipboardStillSyncs() = runBlocking {
        ClipsyncCrypto.ensureInitialized()
        val idA = TlsIdentity.generate("A")
        val ka = ClipsyncCrypto.generateKeyPair()
        val kb = ClipsyncCrypto.generateKeyPair()
        val keyA = ClipsyncCrypto.deriveSharedKey(ka.secretKey, ka.publicKey, kb.publicKey)
        val keyB = ClipsyncCrypto.deriveSharedKey(kb.secretKey, kb.publicKey, ka.publicKey)

        val applierB = RecordingApplier()
        val engineA = SyncEngine("A", repo(), RecordingApplier())
        val engineB = SyncEngine("B", repo(), applierB)
        val filesA = FileTransferEngine(scope, FolderFileSink(tempDir()))
        val receiveDir = tempDir()
        val filesB = FileTransferEngine(scope, FolderFileSink(receiveDir))

        a = ConnectionManager("A", idA, engineA, { if (it == "B") keyA else null }, scope, fileEngine = filesA)
            .also { it.startServer(17811, host = "127.0.0.1") }
        b = ConnectionManager(
            "B", TlsIdentity.generate("B"), engineB, { if (it == "A") keyB else null }, scope,
            fileEngine = filesB,
        )

        val payload = ByteArray(1_300_000) { ((it * 131) % 251).toByte() } // 5 chunks

        withTimeout(30_000) {
            scope.launch { b!!.dial("127.0.0.1", 17811, idA.fingerprint) }
            withTimeout(10_000) { while (!a!!.isConnected("B") || !b!!.isConnected("A")) delay(20) }

            // File A → B through the real transport.
            assertTrue(
                filesA.sendFile(
                    FileSource("holiday photo.jpg", payload.size.toLong(), "image/jpeg") {
                        ByteArrayInputStream(payload)
                    },
                ),
            )
            withTimeout(15_000) {
                while (filesB.transfers.value.none { it.status == TransferState.Status.DONE }) delay(20)
            }
            val received = File(receiveDir, "holiday photo.jpg")
            assertTrue(received.exists())
            assertContentEquals(payload, received.readBytes())
            assertEquals(TransferState.Status.DONE, filesA.transfers.value.single().status)

            // The same link still syncs the clipboard after the file traffic.
            engineA.onLocalCapture("still syncing", nowMs = 999)
            withTimeout(5_000) { while (applierB.applied.isEmpty()) delay(20) }
            assertEquals("still syncing", applierB.applied.last())
        }
    }
}
