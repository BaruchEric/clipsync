package ca.beric.clipsync.browse

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmFileBridgeTest {

    private val bridge = JvmFileBridge()

    private fun tempDir(): File = Files.createTempDirectory("clipsync-bridge").toFile()

    @Test
    fun listsFilesAndDirectoriesWithMetadata() {
        val dir = tempDir()
        File(dir, "a.txt").writeText("hello")
        File(dir, "sub").mkdirs()
        val entries = bridge.list(dir.absolutePath).sortedBy { it.name }
        assertEquals(listOf("a.txt", "sub"), entries.map { it.name })
        assertEquals(5L, entries[0].size)
        assertFalse(entries[0].dir)
        assertTrue(entries[1].dir)
    }

    @Test
    fun statReturnsNullForAMissingPath() {
        assertNull(bridge.stat(File(tempDir(), "nope").absolutePath))
    }

    @Test
    fun openIsReInvokableAndReturnsFreshStreams() {
        // FileTransferEngine reads a source twice — hash pass, then stream pass.
        val f = File(tempDir(), "twice.bin").apply { writeText("payload") }
        assertEquals("payload", bridge.open(f.absolutePath).use { it.readBytes().decodeToString() })
        assertEquals("payload", bridge.open(f.absolutePath).use { it.readBytes().decodeToString() })
    }

    @Test
    fun canonicalResolvesSymlinks() {
        val dir = tempDir()
        val outside = File(dir, "outside").apply { mkdirs() }
        val link = File(dir, "link")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertEquals(outside.canonicalPath, bridge.canonical(link.absolutePath))
    }

    @Test
    fun canonicalReturnsNullWhenResolutionFails() {
        // A symlink cycle makes canonicalPath throw (ELOOP). Returning an unresolved path here
        // would be a confinement bypass: BrowseEngine compares canonical paths, and a raw
        // string can start with the root prefix while still containing "..".
        val dir = tempDir()
        val a = File(dir, "a")
        val b = File(dir, "b")
        Files.createSymbolicLink(a.toPath(), b.toPath())
        Files.createSymbolicLink(b.toPath(), a.toPath())
        assertNull(bridge.canonical(File(a, "child.txt").absolutePath))
    }

    @Test
    fun moveRenamesAndCreateWritesThroughMkdirs() {
        val dir = tempDir()
        assertTrue(bridge.mkdirs(File(dir, "deep/er").absolutePath))
        val target = File(dir, "deep/er/new.txt")
        bridge.create(target.absolutePath).use { it.write("x".encodeToByteArray()) }
        assertTrue(target.exists())
        val moved = File(dir, "deep/er/moved.txt")
        assertTrue(bridge.move(target.absolutePath, moved.absolutePath))
        assertFalse(target.exists())
        assertTrue(moved.exists())
    }
}
