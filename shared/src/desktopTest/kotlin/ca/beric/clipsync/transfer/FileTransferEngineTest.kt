package ca.beric.clipsync.transfer

import ca.beric.clipsync.crypto.ClipsyncCrypto
import ca.beric.clipsync.protocol.ChunkFrame
import ca.beric.clipsync.protocol.ControlMessage
import ca.beric.clipsync.sync.RemotePeer
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Engine-level file transfer: two [FileTransferEngine]s wired directly (in-memory delivery,
 * no sockets) — streaming, windowed acks, per-chunk AEAD with positional AAD, whole-file
 * sha256 verification, tamper rejection, timeouts, and name sanitization. The full-transport
 * path (real TLS + [ca.beric.clipsync.transport.ConnectionManager] routing) is proven
 * separately in transport/FileOverTlsTest.
 */
class FileTransferEngineTest {

    private val scope = CoroutineScope(SupervisorJob())
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun tempDir(): File =
        Files.createTempDirectory("clipsync-transfer-test").toFile().also { tempDirs += it }

    /** Two engines delivering to each other synchronously; [mangleChunk] can corrupt frames. */
    private fun wirePair(
        senderTimeouts: Pair<Long, Long> = 15_000L to 60_000L,
        mangleChunk: ((ByteArray) -> ByteArray)? = null,
    ): Triple<FileTransferEngine, FileTransferEngine, File> {
        val receiveDir = tempDir()
        val key = ClipsyncCrypto.randomKey()
        val sender = FileTransferEngine(
            scope, FolderFileSink(tempDir()),
            offerAckTimeoutMs = senderTimeouts.first, stallTimeoutMs = senderTimeouts.second,
        )
        val receiver = FileTransferEngine(scope, FolderFileSink(receiveDir))
        runBlocking {
            sender.addPeer(
                RemotePeer(
                    "B", key,
                    send = { receiver.onRemoteMessage("A", it) },
                    sendChunk = { receiver.onBinaryFrame(mangleChunk?.invoke(it) ?: it) },
                ),
            )
            receiver.addPeer(
                RemotePeer(
                    "A", key,
                    send = { sender.onRemoteMessage("B", it) },
                    sendChunk = { sender.onBinaryFrame(it) },
                ),
            )
        }
        return Triple(sender, receiver, receiveDir)
    }

    private fun source(name: String, bytes: ByteArray, mime: String = "application/octet-stream") =
        FileSource(name, bytes.size.toLong(), mime) { ByteArrayInputStream(bytes) }

    @Test
    fun multiChunkFileArrivesByteIdenticalAndBothSidesReportDone() = runBlocking {
        ClipsyncCrypto.ensureInitialized()
        val (sender, receiver, dir) = wirePair()
        // 20 chunks: exercises the in-flight window (16) and the ack cadence (8).
        val payload = ByteArray(5 * 1024 * 1024 + 123) { ((it * 31 + 7) % 256).toByte() }

        withTimeout(30_000) { assertTrue(sender.sendFile(source("report final.pdf", payload, "application/pdf"))) }

        val received = File(dir, "report final.pdf")
        assertTrue(received.exists(), "expected ${received.absolutePath}")
        assertContentEquals(payload, received.readBytes())
        assertEquals(TransferState.Status.DONE, sender.transfers.value.single().status)
        val incoming = receiver.transfers.value.single()
        assertEquals(TransferState.Status.DONE, incoming.status)
        assertEquals(received.absolutePath, incoming.detail)
        assertEquals(payload.size.toLong(), incoming.transferredBytes)
        // No stray temp files left behind.
        assertEquals(listOf(received.name), dir.listFiles()!!.map { it.name })
    }

    @Test
    fun emptyFileTransfers() = runBlocking {
        ClipsyncCrypto.ensureInitialized()
        val (sender, _, dir) = wirePair()
        withTimeout(10_000) { assertTrue(sender.sendFile(source("empty.bin", ByteArray(0)))) }
        val received = File(dir, "empty.bin")
        assertTrue(received.exists())
        assertEquals(0L, received.length())
        assertEquals(TransferState.Status.DONE, sender.transfers.value.single().status)
    }

    @Test
    fun tamperedChunkFailsTransferAndPublishesNothing() = runBlocking {
        ClipsyncCrypto.ensureInitialized()
        val (sender, receiver, dir) = wirePair(
            mangleChunk = { frame ->
                val decoded = ChunkFrame.decode(frame)!!
                if (decoded.index == 1) {
                    frame.copyOf().also { it[ChunkFrame.HEADER_BYTES + 5] = (it[ChunkFrame.HEADER_BYTES + 5].toInt() xor 1).toByte() }
                } else {
                    frame
                }
            },
        )
        val payload = ByteArray(FileTransferEngine.CHUNK_BYTES * 3) { (it % 256).toByte() }

        withTimeout(30_000) { sender.sendFile(source("video.mp4", payload, "video/mp4")) }

        val incoming = receiver.transfers.value.single()
        assertEquals(TransferState.Status.FAILED, incoming.status)
        assertEquals("chunk failed authentication", incoming.detail)
        assertEquals(TransferState.Status.FAILED, sender.transfers.value.single().status)
        assertTrue(dir.listFiles()!!.isEmpty(), "partial data must be discarded: ${dir.listFiles()!!.toList()}")
    }

    @Test
    fun offerToSilentPeerFailsAtTheTimeout() = runBlocking {
        ClipsyncCrypto.ensureInitialized()
        val sender = FileTransferEngine(scope, FolderFileSink(tempDir()), offerAckTimeoutMs = 300)
        sender.addPeer(RemotePeer("mute", ClipsyncCrypto.randomKey(), send = {}, sendChunk = {}))

        withTimeout(10_000) { assertTrue(sender.sendFile(source("doc.txt", ByteArray(10)))) }

        val state = sender.transfers.value.single()
        assertEquals(TransferState.Status.FAILED, state.status)
        assertTrue(state.detail!!.contains("did not respond"), state.detail)
    }

    @Test
    fun sendWithNoPeersReturnsFalse() = runBlocking {
        ClipsyncCrypto.ensureInitialized()
        val engine = FileTransferEngine(scope, FolderFileSink(tempDir()))
        assertFalse(engine.sendFile(source("x.txt", ByteArray(5))))
        assertTrue(engine.transfers.value.isEmpty())
    }

    @Test
    fun receivedFileNameCannotEscapeTheSinkFolder() = runBlocking {
        ClipsyncCrypto.ensureInitialized()
        val (sender, _, dir) = wirePair()
        withTimeout(10_000) { sender.sendFile(source("../../etc/passwd", "boo".encodeToByteArray())) }
        // The receiver's sanitized name keeps only the basename.
        val received = File(dir, "passwd")
        assertTrue(received.exists(), "expected sanitized ${received.absolutePath}, dir has ${dir.listFiles()!!.toList()}")
        assertEquals("boo", received.readText())
    }

    @Test
    fun sanitizeNameStripsTraversalAndControlCharacters() {
        assertEquals("passwd", FileTransferEngine.sanitizeName("../../etc/passwd"))
        assertEquals("evil.exe", FileTransferEngine.sanitizeName("C:\\Windows\\evil.exe"))
        assertEquals("file", FileTransferEngine.sanitizeName(".."))
        assertEquals("file", FileTransferEngine.sanitizeName(""))
        assertEquals("hidden", FileTransferEngine.sanitizeName("...hidden"))
        assertEquals("a_b_c", FileTransferEngine.sanitizeName("a:b*c"))
        assertEquals("tab_name", FileTransferEngine.sanitizeName("tab\tname"))
        assertEquals(200, FileTransferEngine.sanitizeName("x".repeat(500)).length)
    }

    @Test
    fun duplicateNamesGetNumberedSuffixes() = runBlocking {
        ClipsyncCrypto.ensureInitialized()
        val (sender, _, dir) = wirePair()
        withTimeout(10_000) {
            sender.sendFile(source("notes.txt", "one".encodeToByteArray()))
            sender.sendFile(source("notes.txt", "two".encodeToByteArray()))
        }
        assertEquals("one", File(dir, "notes.txt").readText())
        assertEquals("two", File(dir, "notes (1).txt").readText())
    }

    @Test
    fun disconnectMidTransferDiscardsPartialData() = runBlocking {
        ClipsyncCrypto.ensureInitialized()
        val receiveDir = tempDir()
        val key = ClipsyncCrypto.randomKey()
        val receiver = FileTransferEngine(scope, FolderFileSink(receiveDir))
        // Feed the receiver an accepted offer plus one valid chunk, then drop the peer.
        val idBytes = ClipsyncCrypto.randomBytes(32)
        val id = ClipsyncCrypto.toHex(idBytes)
        val chunk = ByteArray(1000) { 1 }
        val full = ByteArray(FileTransferEngine.CHUNK_BYTES + 1000) { 1 }
        receiver.addPeer(RemotePeer("gone", key, send = {}, sendChunk = {}))
        receiver.onRemoteMessage(
            "gone",
            ControlMessage.FileOffer(
                id, "half.bin", full.size.toLong(), "application/octet-stream",
                ClipsyncCrypto.toHex(ClipsyncCrypto.sha256(full)), 2,
            ),
        )
        val first = full.copyOfRange(0, FileTransferEngine.CHUNK_BYTES)
        receiver.onBinaryFrame(
            ChunkFrame.encode(idBytes, 0, 2, ClipsyncCrypto.seal(key, first, aad(idBytes, 0))),
        )
        assertEquals(TransferState.Status.ACTIVE, receiver.transfers.value.single().status)

        receiver.removePeer("gone")

        withTimeout(5_000) {
            while (receiver.transfers.value.single().status != TransferState.Status.FAILED) delay(20)
        }
        assertTrue(receiveDir.listFiles()!!.isEmpty(), "partial data must be gone: ${receiveDir.listFiles()!!.toList()}")
        // A late chunk for the dead transfer is ignored, not an error.
        receiver.onBinaryFrame(
            ChunkFrame.encode(idBytes, 1, 2, ClipsyncCrypto.seal(key, chunk, aad(idBytes, 1))),
        )
    }

    private fun aad(idBytes: ByteArray, index: Int): ByteArray {
        val out = idBytes.copyOf(idBytes.size + 4)
        out[idBytes.size] = (index ushr 24).toByte()
        out[idBytes.size + 1] = (index ushr 16).toByte()
        out[idBytes.size + 2] = (index ushr 8).toByte()
        out[idBytes.size + 3] = index.toByte()
        return out
    }
}
