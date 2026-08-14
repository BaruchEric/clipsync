package ca.beric.clipsync.browse

import ca.beric.clipsync.protocol.MirrorEvent
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowseEngineMutationTest {

    private lateinit var root: File

    private fun engine(scope: CoroutineScope, bridge: FileBridge = JvmFileBridge()): BrowseEngine {
        root = Files.createTempDirectory("clipsync-mutate").toFile()
        return BrowseEngine(
            scope = scope,
            bridge = bridge,
            roots = listOf(BrowseRoot("r", "Root", root.absolutePath)),
            enabled = { true },
            clock = { 1_700_000_000_000L },
        )
    }

    private fun trash() = File(root, BrowseEngine.TRASH_DIR)

    @Test
    fun deleteMovesToTrashRatherThanUnlinking() = runBlocking {
        val e = engine(this)
        File(root, "doc.txt").writeText("keepable")
        val reply = e.onEvent("peer", MirrorEvent.FsDelete("r", listOf("doc.txt"))) as MirrorEvent.FsResult
        assertTrue(reply.ok, reply.detail)
        assertFalse(File(root, "doc.txt").exists())
        val trashed = trash().listFiles()!!.single()
        assertTrue(trashed.name.endsWith("-doc.txt"), "got ${trashed.name}")
        assertEquals("keepable", trashed.readText())
    }

    @Test
    fun deletingADirectoryIsOneReversibleMove() = runBlocking {
        val e = engine(this)
        File(root, "album/inner").mkdirs()
        File(root, "album/inner/pic.jpg").writeText("bytes")
        val reply = e.onEvent("peer", MirrorEvent.FsDelete("r", listOf("album"))) as MirrorEvent.FsResult
        assertTrue(reply.ok, reply.detail)
        assertFalse(File(root, "album").exists())
        val trashed = trash().listFiles()!!.single()
        assertEquals("bytes", File(trashed, "inner/pic.jpg").readText())
    }

    @Test
    fun aSecondDeleteOfTheSameNameDoesNotClobberTheFirst() = runBlocking {
        val e = engine(this)
        File(root, "dup.txt").writeText("first")
        e.onEvent("peer", MirrorEvent.FsDelete("r", listOf("dup.txt")))
        File(root, "dup.txt").writeText("second")
        val reply = e.onEvent("peer", MirrorEvent.FsDelete("r", listOf("dup.txt"))) as MirrorEvent.FsResult
        assertTrue(reply.ok, reply.detail)
        assertEquals(2, trash().listFiles()!!.size)
        assertEquals(setOf("first", "second"), trash().listFiles()!!.map { it.readText() }.toSet())
    }

    @Test
    fun deleteRefusesAPathOutsideTheRoot() = runBlocking {
        val e = engine(this)
        val reply = e.onEvent("peer", MirrorEvent.FsDelete("r", listOf("../escape"))) as MirrorEvent.FsResult
        assertFalse(reply.ok)
        assertEquals("path rejected", reply.detail)
    }

    @Test
    fun theTrashIsHiddenFromListingsButStillAddressable() = runBlocking {
        // A stated decision, not an accident: the trash is omitted from FsEntries so it does
        // not read as an ordinary folder, but resolve() still accepts its path — a restore
        // flow needs to be able to name it. Pin both halves so neither drifts silently.
        val e = engine(this)
        trash().mkdirs()
        File(trash(), "old.txt").writeText("archived")
        val listing = e.onEvent("peer", MirrorEvent.FsQueryList("r", "")) as MirrorEvent.FsEntries
        assertTrue(listing.entries.none { it.name == BrowseEngine.TRASH_DIR })
        assertEquals(trash().canonicalPath, e.resolve("r", BrowseEngine.TRASH_DIR))
    }

    @Test
    fun deleteRefusesToTrashTheTrash() = runBlocking {
        val e = engine(this)
        trash().mkdirs()
        File(trash(), "old.txt").writeText("archived")
        val reply = e.onEvent("peer", MirrorEvent.FsDelete("r", listOf(BrowseEngine.TRASH_DIR))) as MirrorEvent.FsResult
        assertFalse(reply.ok)
        assertTrue(File(trash(), "old.txt").exists())
    }

    @Test
    fun renameWorksWithinTheDirectory() = runBlocking {
        val e = engine(this)
        File(root, "old.txt").writeText("x")
        val reply = e.onEvent("peer", MirrorEvent.FsRename("r", "old.txt", "new.txt")) as MirrorEvent.FsResult
        assertTrue(reply.ok, reply.detail)
        assertTrue(File(root, "new.txt").exists())
        assertFalse(File(root, "old.txt").exists())
    }

    @Test
    fun renameRefusesAPathSeparatorBecauseAMoveIsNotARename() = runBlocking {
        val e = engine(this)
        File(root, "old.txt").writeText("x")
        for (bad in listOf("sub/new.txt", "../new.txt", "..", ".", "")) {
            val reply = e.onEvent("peer", MirrorEvent.FsRename("r", "old.txt", bad)) as MirrorEvent.FsResult
            assertFalse(reply.ok, "expected refusal for '$bad'")
        }
        assertTrue(File(root, "old.txt").exists())
    }

    @Test
    fun renameRefusesToOverwriteAnExistingFile() = runBlocking {
        val e = engine(this)
        File(root, "old.txt").writeText("source")
        File(root, "taken.txt").writeText("victim")
        val reply = e.onEvent("peer", MirrorEvent.FsRename("r", "old.txt", "taken.txt")) as MirrorEvent.FsResult
        assertFalse(reply.ok)
        assertEquals("victim", File(root, "taken.txt").readText())
        assertTrue(File(root, "old.txt").exists())
    }

    @Test
    fun aFailedMoveLeavesTheSourceIntactAndReportsFailure() = runBlocking {
        // Cross-filesystem moves fail on real devices; we must never fall back to copy+unlink.
        val refusing = object : FileBridge by JvmFileBridge() {
            override fun move(from: String, to: String) = false
        }
        val e = engine(this, refusing)
        File(root, "doc.txt").writeText("intact")
        val reply = e.onEvent("peer", MirrorEvent.FsDelete("r", listOf("doc.txt"))) as MirrorEvent.FsResult
        assertFalse(reply.ok)
        assertEquals("intact", File(root, "doc.txt").readText())
    }
}
