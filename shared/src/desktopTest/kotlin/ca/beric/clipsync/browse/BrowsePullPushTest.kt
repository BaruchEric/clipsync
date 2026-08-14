package ca.beric.clipsync.browse

import ca.beric.clipsync.crypto.ClipsyncCrypto
import ca.beric.clipsync.protocol.ControlMessage
import ca.beric.clipsync.protocol.MirrorEvent
import ca.beric.clipsync.sync.RemotePeer
import ca.beric.clipsync.transfer.FileTransferEngine
import ca.beric.clipsync.transfer.FolderFileSink
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowsePullPushTest {

    private lateinit var root: File

    /** Completed by whichever fake peer is offered a file — no sleeping, no flakes. */
    private val firstOffer = CompletableDeferred<Pair<String, ControlMessage.FileOffer>>()

    // A dedicated scope, NOT the test's own runBlocking scope. FileTransferEngine.init launches
    // a stall watchdog (`while (isActive) { delay(...) }`) that never completes on its own, and
    // BrowseEngine.onPull launches into this scope too. Passing `this` from inside runBlocking
    // makes those children of the runBlocking coroutine, which waits for every child before
    // returning — the test would hang forever. Mirrors FileTransferEngineTest's pattern.
    private val scope = CoroutineScope(SupervisorJob())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private suspend fun engine(): BrowseEngine {
        ClipsyncCrypto.ensureInitialized() // libsodium; sealing a chunk fails without it
        root = Files.createTempDirectory("clipsync-pull").toFile()
        val transfers = FileTransferEngine(
            scope,
            FolderFileSink(Files.createTempDirectory("clipsync-sink").toFile()),
            offerAckTimeoutMs = 50,
        )
        for (id in listOf("mac", "other")) {
            transfers.addPeer(
                RemotePeer(id, ClipsyncCrypto.randomKey(), send = { msg ->
                    if (msg is ControlMessage.FileOffer) firstOffer.complete(id to msg)
                }),
            )
        }
        return BrowseEngine(
            scope = scope,
            bridge = JvmFileBridge(),
            roots = listOf(BrowseRoot("r", "Root", root.absolutePath)),
            enabled = { true },
            clock = { 1_700_000_000_000L },
        ).also { it.transfers = transfers }
    }

    @Test
    fun pullOffersTheFileToTheRequesterOnly() = runBlocking {
        val e = engine()
        File(root, "photo.jpg").writeText("jpegbytes")
        assertEquals(null, e.onEvent("mac", MirrorEvent.FsPull("r", "photo.jpg")))
        // sendFile is launched into the engine's scope; await the offer instead of sleeping.
        val (peer, offer) = withTimeout(5_000) { firstOffer.await() }
        assertEquals("mac", peer)
        assertEquals("photo.jpg", offer.name)
        assertEquals(9L, offer.size)
        assertEquals("", offer.dest, "a pull must never steer where the Mac writes")
    }

    @Test
    fun pullRefusesADirectory() = runBlocking {
        val e = engine()
        File(root, "album").mkdirs()
        val reply = e.onEvent("mac", MirrorEvent.FsPull("r", "album")) as MirrorEvent.FsResult
        assertFalse(reply.ok)
        assertEquals("not a file", reply.detail)
    }

    @Test
    fun pullRefusesAPathOutsideTheRoot() = runBlocking {
        val e = engine()
        val reply = e.onEvent("mac", MirrorEvent.FsPull("r", "../secret")) as MirrorEvent.FsResult
        assertFalse(reply.ok)
        assertEquals("path rejected", reply.detail)
    }

    @Test
    fun pullOfAMissingFileFails() = runBlocking {
        val e = engine()
        val reply = e.onEvent("mac", MirrorEvent.FsPull("r", "ghost.bin")) as MirrorEvent.FsResult
        assertFalse(reply.ok)
        assertEquals("not found", reply.detail)
    }

    @Test
    fun pushAnswersWithTheAbsoluteDestinationAndCreatesIt() = runBlocking {
        val e = engine()
        val reply = e.onEvent("mac", MirrorEvent.FsPush("r", "inbox")) as MirrorEvent.FsResult
        assertTrue(reply.ok, reply.detail)
        assertEquals(File(root, "inbox").canonicalPath, reply.detail)
        assertTrue(File(root, "inbox").isDirectory)
    }

    @Test
    fun pushRefusesADirectoryOutsideTheRoot() = runBlocking {
        val e = engine()
        val reply = e.onEvent("mac", MirrorEvent.FsPush("r", "../elsewhere")) as MirrorEvent.FsResult
        assertFalse(reply.ok)
        assertEquals("path rejected", reply.detail)
    }

    @Test
    fun confineAbsoluteRejectsASiblingSharingTheRootsNamePrefix() {
        // confineAbsolute() carries the same startsWith("$rootCanon/") construct resolve() does,
        // and it is what guards an inbound push destination. Task 3 covered the separator bug
        // for resolve() only; this covers it for the write path, where getting it wrong means
        // a received file lands outside the browse root entirely.
        val parent = Files.createTempDirectory("clipsync-confine").toFile()
        val theRoot = File(parent, "browse").apply { mkdirs() }
        File(parent, "browse-evil").apply { mkdirs() }
        val e = BrowseEngine(
            scope = CoroutineScope(EmptyCoroutineContext),
            bridge = JvmFileBridge(),
            roots = listOf(BrowseRoot("r", "Root", theRoot.absolutePath)),
            enabled = { true },
            clock = { 1_700_000_000_000L },
        )
        assertNull(e.confineAbsolute(File(parent, "browse-evil").canonicalPath))
        // Positive control, so the null above is confinement and not a blanket refusal.
        assertEquals(theRoot.canonicalPath, e.confineAbsolute(theRoot.canonicalPath))
    }
}
