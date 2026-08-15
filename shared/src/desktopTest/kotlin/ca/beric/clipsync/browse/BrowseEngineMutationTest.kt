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

    /** Two roots that nest, the way the real seven do: `internal` contains `camera`. */
    private fun nestedEngine(scope: CoroutineScope): BrowseEngine {
        root = Files.createTempDirectory("clipsync-mutate-nested").toFile()
        File(root, "DCIM").mkdirs()
        return BrowseEngine(
            scope = scope,
            bridge = JvmFileBridge(),
            roots = listOf(
                BrowseRoot("internal", "Internal", root.absolutePath),
                BrowseRoot("camera", "Camera", File(root, "DCIM").absolutePath),
            ),
            enabled = { true },
            clock = { 1_700_000_000_000L },
        )
    }

    /**
     * A root TWO levels below another: `internal` contains `DCIM`, which contains the declared
     * `camera` root. The real seven never nest this deeply today — this is the shape that an
     * equality-only [BrowseEngine.isDeclaredRootPath] would silently mis-handle (M9 residual R3).
     */
    private fun deeplyNestedEngine(scope: CoroutineScope): BrowseEngine {
        root = Files.createTempDirectory("clipsync-mutate-deep").toFile()
        File(root, "DCIM/Camera").mkdirs()
        return BrowseEngine(
            scope = scope,
            bridge = JvmFileBridge(),
            roots = listOf(
                BrowseRoot("internal", "Internal", root.absolutePath),
                BrowseRoot("camera", "Camera", File(root, "DCIM/Camera").absolutePath),
            ),
            enabled = { true },
            clock = { 1_700_000_000_000L },
        )
    }

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
    fun deleteRefusesWhenTheTrashIsASymlinkOutOfTheRoot() = runBlocking {
        // Not reachable by the remote peer (the protocol has no symlink-creating op), but the
        // destination of a destructive move must be confined the same way its source is.
        val e = engine(this)
        val outside = Files.createTempDirectory("clipsync-outside-trash").toFile()
        Files.createSymbolicLink(File(root, BrowseEngine.TRASH_DIR).toPath(), outside.toPath())
        File(root, "doc.txt").writeText("keepable")
        val reply = e.onEvent("peer", MirrorEvent.FsDelete("r", listOf("doc.txt"))) as MirrorEvent.FsResult
        assertFalse(reply.ok)
        assertEquals("trash rejected", reply.detail)
        assertTrue(File(root, "doc.txt").exists(), "the file must not have moved")
        assertEquals(0, outside.listFiles()!!.size, "nothing may land outside the root")
    }

    @Test
    fun deleteRefusesAChildRootsOwnDirectoryReachedThroughTheParentRoot() = runBlocking {
        // FsDelete(root="internal", paths=["DCIM"]) must not trash the whole camera root just
        // because DCIM sits inside the internal root's own directory tree.
        val e = nestedEngine(this)
        File(File(root, "DCIM"), "IMG_0001.jpg").writeText("photo")
        val reply = e.onEvent("peer", MirrorEvent.FsDelete("internal", listOf("DCIM"))) as MirrorEvent.FsResult
        assertFalse(reply.ok)
        assertEquals("path rejected", reply.detail)
        assertTrue(File(root, "DCIM").exists(), "the child root's directory must survive")
        assertTrue(File(File(root, "DCIM"), "IMG_0001.jpg").exists())
    }

    @Test
    fun deleteStillWorksOnAnOrdinarySubdirectoryOfTheParentRoot() = runBlocking {
        // Positive control: the new refusal must not overreach and block ordinary deletes.
        val e = nestedEngine(this)
        File(root, "Notes").mkdirs()
        File(File(root, "Notes"), "todo.txt").writeText("buy milk")
        val reply = e.onEvent("peer", MirrorEvent.FsDelete("internal", listOf("Notes"))) as MirrorEvent.FsResult
        assertTrue(reply.ok, reply.detail)
        assertFalse(File(root, "Notes").exists())
    }

    @Test
    fun renameRefusesAChildRootsOwnDirectoryReachedThroughTheParentRoot() = runBlocking {
        val e = nestedEngine(this)
        val reply = e.onEvent("peer", MirrorEvent.FsRename("internal", "DCIM", "DCIM2")) as MirrorEvent.FsResult
        assertFalse(reply.ok)
        assertEquals("path rejected", reply.detail)
        assertTrue(File(root, "DCIM").exists(), "the child root's directory must survive")
    }

    @Test
    fun refusesAncestorOfNestedRoot() = runBlocking {
        // R3, the hole equality left open: `DCIM` is not itself a declared root, so an
        // equality-only check waves it through — and trashing it swallows the `camera` root
        // nested inside, which then renders as a permanently empty folder rather than an error.
        // Delete and rename both have to refuse an ancestor, not just an exact mount point.
        val e = deeplyNestedEngine(this)
        val cameraRoot = File(root, "DCIM/Camera")
        File(cameraRoot, "IMG_0001.jpg").writeText("photo")

        val deleted = e.onEvent("peer", MirrorEvent.FsDelete("internal", listOf("DCIM"))) as MirrorEvent.FsResult
        assertFalse(deleted.ok, "deleting an ancestor of a declared root must be refused")
        assertEquals("path rejected", deleted.detail)

        val renamed = e.onEvent("peer", MirrorEvent.FsRename("internal", "DCIM", "DCIM2")) as MirrorEvent.FsResult
        assertFalse(renamed.ok, "renaming an ancestor of a declared root must be refused")
        assertEquals("path rejected", renamed.detail)

        assertTrue(cameraRoot.exists(), "the nested root's directory must survive")
        assertEquals("photo", File(cameraRoot, "IMG_0001.jpg").readText())
    }

    @Test
    fun nestingFixChangesNothingForTodaysRoots() = runBlocking {
        // The other half of R3: widening a refusal in the security core is only safe if it is
        // behavior-preserving for the roots that actually ship. A path is newly refused only
        // when it is a STRICT ancestor of some root, and with the real one-level shape (six
        // direct children of `internal`) the only such path is `internal` itself — which
        // equality already refused. So every ordinary operation must still behave exactly as
        // it did before the change.
        val e = nestedEngine(this)
        File(root, "Notes/sub").mkdirs()
        File(root, "Notes/sub/todo.txt").writeText("buy milk")

        // An ordinary subdirectory sitting beside a nested root still deletes.
        val ok = e.onEvent("peer", MirrorEvent.FsDelete("internal", listOf("Notes"))) as MirrorEvent.FsResult
        assertTrue(ok.ok, ok.detail)
        assertFalse(File(root, "Notes").exists())

        // The parent root's own directory stays refused (it was, by equality, before the fix).
        val parent = e.onEvent("peer", MirrorEvent.FsDelete("internal", listOf(""))) as MirrorEvent.FsResult
        assertFalse(parent.ok)

        // And a file *inside* the nested root is still deletable through that root — the fix
        // refuses ancestors, never descendants.
        File(root, "DCIM/IMG_0002.jpg").writeText("photo")
        val inner = e.onEvent("peer", MirrorEvent.FsDelete("camera", listOf("IMG_0002.jpg"))) as MirrorEvent.FsResult
        assertTrue(inner.ok, inner.detail)
        assertFalse(File(root, "DCIM/IMG_0002.jpg").exists())
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
