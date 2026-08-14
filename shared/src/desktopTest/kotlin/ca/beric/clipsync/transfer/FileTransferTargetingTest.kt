package ca.beric.clipsync.transfer

import ca.beric.clipsync.crypto.ClipsyncCrypto
import ca.beric.clipsync.protocol.ControlMessage
import ca.beric.clipsync.sync.RemotePeer
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileTransferTargetingTest {

    // A dedicated scope, not the test's own runBlocking scope: FileTransferEngine.init launches
    // a stall watchdog (while (isActive) { delay(...) ... }) that never completes on its own.
    // Passing `this` from inside runBlocking would make that watchdog a child of the runBlocking
    // coroutine, and runBlocking waits for every child before returning — the test would hang
    // forever. Mirrors FileTransferEngineTest's wirePair()/tearDown() pattern.
    private val scope = CoroutineScope(SupervisorJob())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun source(bytes: ByteArray) =
        FileSource("x.bin", bytes.size.toLong(), "application/octet-stream") { ByteArrayInputStream(bytes) }

    @Test
    fun aTargetedSendOffersOnlyToTheNamedPeer() = runBlocking {
        ClipsyncCrypto.ensureInitialized()
        val offeredTo = mutableListOf<String>()
        // offerAckTimeoutMs = 50: the fake peers never ack, so each send fails fast on the
        // offer timeout. We are asserting *who* was offered to, not that bytes moved — with
        // the default 15 s timeout this test would stall for half a minute.
        val engine = FileTransferEngine(
            scope,
            FolderFileSink(Files.createTempDirectory("t").toFile()),
            offerAckTimeoutMs = 50,
        )
        for (id in listOf("A", "B")) {
            engine.addPeer(
                RemotePeer(id, ClipsyncCrypto.randomKey(), send = { msg ->
                    if (msg is ControlMessage.FileOffer) offeredTo += id
                }),
            )
        }
        engine.sendFile(source("hello".encodeToByteArray()), toDeviceId = "B")
        assertEquals(listOf("B"), offeredTo)
    }

    @Test
    fun anUnknownTargetSendsNothingAndReturnsFalse() = runBlocking {
        ClipsyncCrypto.ensureInitialized()
        val engine = FileTransferEngine(scope, FolderFileSink(Files.createTempDirectory("t3").toFile()), offerAckTimeoutMs = 50)
        engine.addPeer(RemotePeer("A", ClipsyncCrypto.randomKey(), send = { error("must not send") }))
        assertEquals(false, engine.sendFile(source("x".encodeToByteArray()), toDeviceId = "ghost"))
    }

    @Test
    fun theDestinationRidesTheOffer() = runBlocking {
        ClipsyncCrypto.ensureInitialized()
        var seen: String? = null
        val engine = FileTransferEngine(scope, FolderFileSink(Files.createTempDirectory("t4").toFile()), offerAckTimeoutMs = 50)
        engine.addPeer(
            RemotePeer("A", ClipsyncCrypto.randomKey(), send = { msg ->
                if (msg is ControlMessage.FileOffer) seen = msg.dest
            }),
        )
        engine.sendFile(source("x".encodeToByteArray()), toDeviceId = "A", dest = "/sdcard/Documents")
        assertEquals("/sdcard/Documents", seen)
    }

    @Test
    fun theDesktopSinkIgnoresAPeerSuppliedDestination() {
        val dir = Files.createTempDirectory("t5").toFile()
        val sink = FolderFileSink(dir)
        val pending = sink.begin("safe.txt", "text/plain", dest = "/etc")
        pending.stream.write("x".encodeToByteArray())
        val where = pending.publish()
        // absolutePath, not canonicalPath: publish() returns the former, and on macOS a temp
        // dir is /var/... absolute but /private/var/... canonical. The subject here is "did
        // dest steer the write", not path normalization.
        assertTrue(where.startsWith(dir.absolutePath), "wrote outside the sink folder: $where")
        assertTrue(File(dir, "safe.txt").exists())
    }
}
