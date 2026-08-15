package ca.beric.clipsync.browse

import ca.beric.clipsync.protocol.MirrorEvent
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowseEngineTest {

    private lateinit var root: File
    private var enabled = true

    private fun engine(scope: CoroutineScope): BrowseEngine {
        root = Files.createTempDirectory("clipsync-browse").toFile()
        return BrowseEngine(
            scope = scope,
            bridge = JvmFileBridge(),
            roots = listOf(BrowseRoot("r", "Root", root.absolutePath)),
            enabled = { enabled },
            clock = { 1_700_000_000_000L },
        )
    }

    @Test
    fun listsADirectoryAndHidesTheTrash() = runBlocking {
        val e = engine(this)
        File(root, "a.txt").writeText("hi")
        File(root, BrowseEngine.TRASH_DIR).mkdirs()
        val reply = e.onEvent("peer", MirrorEvent.FsQueryList("r", "")) as MirrorEvent.FsEntries
        assertEquals(listOf("a.txt"), reply.entries.map { it.name })
        assertFalse(reply.truncated, "a listing under the cap must not claim truncation")
    }

    @Test
    fun rootsAreAnnounced() = runBlocking {
        val e = engine(this)
        val reply = e.onEvent("peer", MirrorEvent.FsQueryRoots) as MirrorEvent.FsRoots
        assertEquals(listOf("r" to "Root"), reply.roots.map { it.id to it.label })
    }

    @Test
    fun dotDotTraversalIsRejected() = runBlocking {
        val e = engine(this)
        assertNull(e.resolve("r", "../.."))
        assertNull(e.resolve("r", "sub/../../etc"))
        // Positive control: a real child still resolves, so the nulls above are confinement,
        // not a blanket failure.
        File(root, "sub").mkdirs()
        assertEquals(File(root, "sub").canonicalPath, e.resolve("r", "sub"))
    }

    @Test
    fun anAbsolutePathIsRejected() = runBlocking {
        val e = engine(this)
        assertNull(e.resolve("r", "/etc/passwd"))
    }

    @Test
    fun aSymlinkPointingOutsideTheRootIsRejected() = runBlocking {
        val e = engine(this)
        val outside = Files.createTempDirectory("clipsync-outside").toFile()
        File(outside, "secret.txt").writeText("nope")
        Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath())
        assertNull(e.resolve("r", "escape/secret.txt"))
    }

    @Test
    fun anUnknownRootIsRejected() = runBlocking {
        val e = engine(this)
        assertNull(e.resolve("nope", ""))
    }

    @Test
    fun theRootItselfResolves() = runBlocking {
        val e = engine(this)
        assertEquals(root.canonicalPath, e.resolve("r", ""))
    }

    @Test
    fun listingIsCappedAndSaysSo() = runBlocking {
        val e = engine(this)
        repeat(BrowseEngine.MAX_ENTRIES + 25) { File(root, "f$it").writeText("x") }
        val reply = e.onEvent("peer", MirrorEvent.FsQueryList("r", "")) as MirrorEvent.FsEntries
        assertEquals(BrowseEngine.MAX_ENTRIES, reply.entries.size)
        assertTrue(reply.truncated, "a capped listing must be flagged, not complete-looking")
        // Sorted before the cap: the survivors are the alphabetical head, so which 2000 you
        // see is deterministic rather than directory-iteration order.
        assertEquals(reply.entries, reply.entries.sortedBy { it.name.lowercase() })
    }

    @Test
    fun everyRequestIsRefusedWhileBrowsingIsDisabled() = runBlocking {
        val e = engine(this)
        File(root, "a.txt").writeText("hi")
        enabled = false
        val events = listOf(
            MirrorEvent.FsQueryRoots,
            MirrorEvent.FsQueryList("r", ""),
            MirrorEvent.FsDelete("r", listOf("a.txt")),
            MirrorEvent.FsRename("r", "a.txt", "b.txt"),
            MirrorEvent.FsPull("r", "a.txt"),
            MirrorEvent.FsPush("r", ""),
        )
        for (event in events) {
            val reply = e.onEvent("peer", event)
            assertTrue(reply is MirrorEvent.FsResult && !reply.ok, "expected refusal for $event, got $reply")
            assertEquals("browsing disabled", (reply as MirrorEvent.FsResult).detail)
        }
        assertTrue(File(root, "a.txt").exists(), "a refused request must not touch storage")
        enabled = true
    }

    @Test
    fun anUnrelatedMirrorEventIsIgnored() = runBlocking {
        val e = engine(this)
        assertNull(e.onEvent("peer", MirrorEvent.SmsQueryThreads))
    }

    @Test
    fun aSiblingSharingTheRootsNamePrefixIsRejected() {
        // The classic startsWith bug: root /x/browse must not admit /x/browse-evil.
        // Without the trailing separator in the prefix check, this test is what fails.
        val parent = Files.createTempDirectory("clipsync-sibling").toFile()
        val theRoot = File(parent, "browse").apply { mkdirs() }
        val evil = File(parent, "browse-evil").apply { mkdirs() }
        File(evil, "loot.txt").writeText("secret")
        File(theRoot, "ok.txt").writeText("fine")
        val e = BrowseEngine(
            scope = this@BrowseEngineTest.let { CoroutineScope(EmptyCoroutineContext) },
            bridge = JvmFileBridge(),
            roots = listOf(BrowseRoot("r", "Root", theRoot.absolutePath)),
            enabled = { true },
            clock = { 1_700_000_000_000L },
        )
        assertNull(e.resolve("r", "../browse-evil/loot.txt"))
        // Positive control: a real child still resolves, so the null above is confinement,
        // not a blanket failure.
        assertEquals(File(theRoot, "ok.txt").canonicalPath, e.resolve("r", "ok.txt"))
    }

    @Test
    fun aRootThatCannotBeCanonicalizedRejectsEverything() {
        // Guards the `bridge.canonical(root.path) ?: return null` line specifically: delete
        // that elvis and substitute root.path, and this is the test that catches it.
        val denying = object : FileBridge by JvmFileBridge() {
            override fun canonical(path: String): String? = null
        }
        val e = BrowseEngine(
            scope = CoroutineScope(EmptyCoroutineContext),
            bridge = denying,
            roots = listOf(BrowseRoot("r", "Root", "/tmp")),
            enabled = { true },
            clock = { 1_700_000_000_000L },
        )
        assertNull(e.resolve("r", ""))
        assertNull(e.resolve("r", "anything"))
        assertNull(e.confineAbsolute("/tmp/anything"))
    }
}
