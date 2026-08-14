# M9 — Phone File & Photo Browse Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Browse the phone's photos and files from the desktop app, and pull, push, rename, or trash them — over the existing paired, E2E-encrypted link.

**Architecture:** All logic lives in a new `BrowseEngine` in `jvmShared`, which reaches storage only through a `FileBridge` interface — so the whole thing is unit-tested on the desktop against a temp directory, and the Android half is a thin adapter over a Shizuku SHELL-uid AIDL service. Metadata rides the existing sealed `mirror` envelope; file bytes ride the existing M6 `FileTransferEngine`.

**Tech Stack:** Kotlin Multiplatform, kotlinx.serialization, Compose Multiplatform (desktop + Android), Shizuku 13.1.5 (`bindUserService`), MediaStore (read-only), JUnit via `kotlin.test`.

**Spec:** `docs/superpowers/specs/2026-08-13-m9-phone-browse-design.md`

## Global Constraints

- JDK 17. Gradle wrapper only. Build with `./gradlew`, never a system gradle.
- Android: `minSdk = 29`, `targetSdk = 36`, `compileSdk = 36`. Distribution is F-Droid + sideload — **never** Google Play, so SMS/storage permissions are acceptable.
- **No new Gradle dependencies.** `Shizuku.bindUserService` / `Shizuku$UserServiceArgs` are in `dev.rikka.shizuku:api` 13.1.5, already declared in `androidApp/build.gradle.kts`.
- Shared test command: `./gradlew :shared:desktopTest`. Suite is at **75 tests, 1 skipped, 0 failures** before this milestone — it must never go down.
- **Every task verifies `./gradlew :shared:desktopTest :androidApp:assembleDebug`, both.** Learned the hard way here: adding subtypes to the sealed `MirrorEvent` broke an exhaustive `when` in `AppGraph`, and four consecutive tasks passed a green shared suite over an Android app that would not compile. A green `:shared:desktopTest` says nothing about the consumers of a shared type.
- libsodium (`ClipsyncCrypto`) native lib does not load in Android host-JVM unit tests. Any test touching crypto lives in `shared/src/desktopTest/`.
- **A fresh worktree needs `local.properties` first**, or `:androidApp` dies during configuration: `echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties`
- Wire compatibility is non-negotiable: a 0.3.x peer must keep full clipboard/file sync against a 0.4.0 peer. New `MirrorEvent` subtypes already decode to null and drop; new `FileOffer` fields must have defaults.
- The desktop **never** honors a peer-supplied destination path. `dest` steers writes on the phone only.
- Version bumps to `0.4.0` / `versionCode = 5` in the final task, not before.
- **Never enable `sun.io.useCanonCaches` or `sun.io.useCanonPrefixCache`** in this project's JVM args. Confinement rests on `File.canonicalPath` being authoritative; those caches are off by default on JDK 17, and turning them on would let a stale symlink mapping be compared against.
- **Writes must not follow symlinks on the final component.** A push destination does not exist at resolve time, so it cannot be a symlink then — but `File(path).outputStream()` follows one planted in the check-to-use window. Any code that creates a received file opens it with `Files.newOutputStream(path, CREATE_NEW, WRITE, LinkOption.NOFOLLOW_LINKS)`. `renameTo` (the trash move) does not follow its final component and needs no change.

---

### Task 1: Protocol — browse events and `FileOffer.dest`

**Files:**
- Modify: `shared/src/commonMain/kotlin/ca/beric/clipsync/protocol/Protocol.kt`
- Test: `shared/src/commonTest/kotlin/ca/beric/clipsync/protocol/ProtocolTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `FsRoot`, `FsEntry`, `MediaItem` data classes; `MirrorEvent.FsQueryRoots`, `.FsRoots`, `.FsQueryList`, `.FsEntries`, `.MediaQuery`, `.MediaItems`, `.ThumbQuery`, `.Thumbs`, `.FsPull`, `.FsPush`, `.FsDelete`, `.FsRename`, `.FsResult`; `ControlMessage.FileOffer.dest: String`.

- [ ] **Step 1: Write the failing tests**

Append to `shared/src/commonTest/kotlin/ca/beric/clipsync/protocol/ProtocolTest.kt`:

```kotlin
@Test
fun browseEventsRoundTrip() {
    val events = listOf<MirrorEvent>(
        MirrorEvent.FsQueryRoots,
        MirrorEvent.FsRoots(listOf(FsRoot("dl", "Download"))),
        MirrorEvent.FsQueryList("dl", "sub/dir"),
        MirrorEvent.FsEntries("dl", "sub/dir", listOf(FsEntry("a.txt", 12L, false, 99L, "text/plain"))),
        MirrorEvent.MediaQuery(0, 60),
        MirrorEvent.MediaItems(listOf(MediaItem(7L, "IMG.jpg", 900L, 5L, "image/jpeg", 4000, 3000))),
        MirrorEvent.ThumbQuery(listOf(7L, 8L)),
        MirrorEvent.Thumbs(mapOf(7L to "QUJD")),
        MirrorEvent.FsPull("dl", "a.txt"),
        MirrorEvent.FsPush("dl", "sub"),
        MirrorEvent.FsDelete("dl", listOf("a.txt", "b.txt")),
        MirrorEvent.FsRename("dl", "a.txt", "b.txt"),
        MirrorEvent.FsResult("pull", false, "not found"),
    )
    for (event in events) {
        assertEquals(event, MirrorCodec.decode(MirrorCodec.encode(event)), "round trip failed for $event")
    }
}

@Test
fun fileOfferDestDefaultsToEmptyAndSurvivesAnOldEncoder() {
    // An old (0.3.x) peer emits a FileOffer with no "dest" key at all.
    val legacy = """{"t":"file","id":"ab","name":"x.bin","size":1,"mime":"application/octet-stream","sha256":"cd","chunks":1}"""
    val decoded = ControlCodec.decode(legacy) as ControlMessage.FileOffer
    assertEquals("", decoded.dest)
}

@Test
fun fileOfferDestRoundTrips() {
    val offer = ControlMessage.FileOffer("ab", "x.bin", 1L, "text/plain", "cd", 1, "/sdcard/Documents")
    assertEquals(offer, ControlCodec.decode(ControlCodec.encode(offer)))
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :shared:desktopTest --tests '*ProtocolTest*'`
Expected: FAIL — `Unresolved reference: FsRoot`, `FsQueryRoots`, and `dest`.

- [ ] **Step 3: Add the payload types**

In `Protocol.kt`, below the existing `SmsMessage` data class:

```kotlin
/** One browsable storage root on the phone. [id] is opaque; [label] is what the desktop shows. */
@Serializable
data class FsRoot(
    @SerialName("id") val id: String,
    @SerialName("label") val label: String,
)

/** One directory entry. [mime] is best-effort, "" for directories. */
@Serializable
data class FsEntry(
    @SerialName("name") val name: String,
    @SerialName("size") val size: Long,
    @SerialName("dir") val dir: Boolean,
    @SerialName("mtime") val mtimeMs: Long,
    @SerialName("mime") val mime: String = "",
)

/** One MediaStore image/video, for the photo grid. [id] keys a later thumbnail request. */
@Serializable
data class MediaItem(
    @SerialName("id") val id: Long,
    @SerialName("name") val name: String,
    @SerialName("size") val size: Long,
    @SerialName("date") val dateMs: Long,
    @SerialName("mime") val mime: String,
    @SerialName("w") val width: Int = 0,
    @SerialName("h") val height: Int = 0,
)
```

- [ ] **Step 4: Add the `MirrorEvent` subtypes**

Inside the `MirrorEvent` sealed interface, after `SmsSent`:

```kotlin
    @Serializable
    @SerialName("fs-roots?")
    data object FsQueryRoots : MirrorEvent

    @Serializable
    @SerialName("fs-roots")
    data class FsRoots(@SerialName("list") val roots: List<FsRoot>) : MirrorEvent

    /** [path] is relative to [root]; "" is the root itself. */
    @Serializable
    @SerialName("fs-list?")
    data class FsQueryList(
        @SerialName("root") val root: String,
        @SerialName("path") val path: String = "",
    ) : MirrorEvent

    @Serializable
    @SerialName("fs-entries")
    data class FsEntries(
        @SerialName("root") val root: String,
        @SerialName("path") val path: String,
        @SerialName("list") val entries: List<FsEntry>,
    ) : MirrorEvent

    @Serializable
    @SerialName("media?")
    data class MediaQuery(
        @SerialName("off") val offset: Int = 0,
        @SerialName("lim") val limit: Int = 60,
    ) : MirrorEvent

    @Serializable
    @SerialName("media")
    data class MediaItems(@SerialName("list") val items: List<MediaItem>) : MirrorEvent

    @Serializable
    @SerialName("thumbs?")
    data class ThumbQuery(@SerialName("ids") val ids: List<Long>) : MirrorEvent

    /** MediaStore id → base64 JPEG thumbnail. Sealed like every other mirror body. */
    @Serializable
    @SerialName("thumbs")
    data class Thumbs(@SerialName("map") val jpegB64: Map<Long, String>) : MirrorEvent

    /** Desktop asks the phone to send one file; the answer is a file transfer, or an FsResult. */
    @Serializable
    @SerialName("fs-pull?")
    data class FsPull(
        @SerialName("root") val root: String,
        @SerialName("path") val path: String,
    ) : MirrorEvent

    /** Desktop asks where to write; FsResult.detail carries the absolute directory on success. */
    @Serializable
    @SerialName("fs-push?")
    data class FsPush(
        @SerialName("root") val root: String,
        @SerialName("dir") val dir: String = "",
    ) : MirrorEvent

    @Serializable
    @SerialName("fs-delete?")
    data class FsDelete(
        @SerialName("root") val root: String,
        @SerialName("paths") val paths: List<String>,
    ) : MirrorEvent

    @Serializable
    @SerialName("fs-rename?")
    data class FsRename(
        @SerialName("root") val root: String,
        @SerialName("path") val path: String,
        @SerialName("to") val newName: String,
    ) : MirrorEvent

    /** Generic answer for the mutating ops. [op] echoes "pull"/"push"/"delete"/"rename". */
    @Serializable
    @SerialName("fs-result")
    data class FsResult(
        @SerialName("op") val op: String,
        @SerialName("ok") val ok: Boolean,
        @SerialName("detail") val detail: String = "",
    ) : MirrorEvent
```

- [ ] **Step 5: Add `dest` to `FileOffer`**

In `ControlMessage.FileOffer`, add a final parameter and extend its KDoc:

```kotlin
    @Serializable
    @SerialName("file")
    data class FileOffer(
        @SerialName("id") val id: String,
        @SerialName("name") val name: String,
        @SerialName("size") val size: Long,
        @SerialName("mime") val mime: String,
        @SerialName("sha256") val sha256: String,
        @SerialName("chunks") val chunkCount: Int,
        /**
         * Absolute directory the receiver should write into (M9 push). Empty means "your
         * default", which is what every pre-0.4 sender emits and what every pre-0.4 receiver
         * assumes. Only the phone honors it; the desktop always writes to ~/Downloads/clipsync.
         */
        @SerialName("dest") val dest: String = "",
    ) : ControlMessage
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :shared:desktopTest --tests '*ProtocolTest*'`
Expected: PASS.

- [ ] **Step 7: Run the whole suite — nothing else may break**

Run: `./gradlew :shared:desktopTest`
Expected: PASS, count is 75 + 3 = 78, 1 skipped.

- [ ] **Step 8: Commit**

```bash
git add shared/src/commonMain/kotlin/ca/beric/clipsync/protocol/Protocol.kt shared/src/commonTest/kotlin/ca/beric/clipsync/protocol/ProtocolTest.kt
git commit -m "feat(protocol): browse events and an optional FileOffer destination"
```

---

### Task 2: `FileBridge` interface and the JVM implementation

**Files:**
- Create: `shared/src/jvmShared/kotlin/ca/beric/clipsync/browse/FileBridge.kt`
- Test: `shared/src/desktopTest/kotlin/ca/beric/clipsync/browse/JvmFileBridgeTest.kt`

**Interfaces:**
- Consumes: `FsEntry` (Task 1).
- Produces: `interface FileBridge` with `canonical(path: String): String`, `list(dir: String): List<FsEntry>`, `stat(path: String): FsEntry?`, `exists(path: String): Boolean`, `open(path: String): InputStream`, `create(path: String): OutputStream`, `move(from: String, to: String): Boolean`, `delete(path: String): Boolean`, `mkdirs(path: String): Boolean`; `class JvmFileBridge : FileBridge`.

- [ ] **Step 1: Write the failing test**

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :shared:desktopTest --tests '*JvmFileBridgeTest*'`
Expected: FAIL — `Unresolved reference: JvmFileBridge`.

- [ ] **Step 3: Write the implementation**

```kotlin
package ca.beric.clipsync.browse

import ca.beric.clipsync.protocol.FsEntry
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URLConnection

/**
 * The only way [BrowseEngine] touches storage. Paths are absolute and already confined by the
 * engine — a bridge does no policy of its own. Implementations: [JvmFileBridge] (desktop and
 * tests, plain java.io) and the Android Shizuku SHELL-uid service.
 *
 * [open] MUST be re-invokable: FileTransferEngine reads a source twice (hash, then stream).
 */
interface FileBridge {
    /**
     * Fully resolved path — symlinks and `..` collapsed — or **null when resolution failed**.
     * Null is load-bearing: [BrowseEngine] confines by comparing canonical paths, and an
     * unresolved path is not safely comparable (a raw string can still start with the root
     * prefix while containing `..`). Callers must treat null as deny, never as fall-through.
     */
    fun canonical(path: String): String?
    fun list(dir: String): List<FsEntry>
    fun stat(path: String): FsEntry?
    fun exists(path: String): Boolean
    fun open(path: String): InputStream
    fun create(path: String): OutputStream
    fun move(from: String, to: String): Boolean
    fun delete(path: String): Boolean
    fun mkdirs(path: String): Boolean
}

/** Plain java.io implementation: the desktop's own filesystem, and every unit test. */
class JvmFileBridge : FileBridge {

    override fun canonical(path: String): String? =
        runCatching { File(path).canonicalPath }.getOrNull()

    override fun list(dir: String): List<FsEntry> =
        File(dir).listFiles()?.map { it.toEntry() } ?: emptyList()

    override fun stat(path: String): FsEntry? = File(path).takeIf { it.exists() }?.toEntry()

    override fun exists(path: String): Boolean = File(path).exists()

    override fun open(path: String): InputStream = File(path).inputStream().buffered()

    override fun create(path: String): OutputStream = File(path).outputStream().buffered()

    override fun move(from: String, to: String): Boolean =
        runCatching { File(from).renameTo(File(to)) }.getOrDefault(false)

    override fun delete(path: String): Boolean = runCatching { File(path).deleteRecursively() }.getOrDefault(false)

    override fun mkdirs(path: String): Boolean = File(path).let { it.isDirectory || it.mkdirs() }

    private fun File.toEntry() = FsEntry(
        name = name,
        size = if (isDirectory) 0L else length(),
        dir = isDirectory,
        mtimeMs = lastModified(),
        mime = if (isDirectory) "" else guessMime(name),
    )

    companion object {
        /** Best-effort, extension-based. A wrong mime never breaks a transfer; bytes are bytes. */
        fun guessMime(name: String): String =
            URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :shared:desktopTest --tests '*JvmFileBridgeTest*'`
Expected: PASS, 6 tests. (Task 6 later adds a seventh to this file.)

- [ ] **Step 5: Commit**

```bash
git add shared/src/jvmShared/kotlin/ca/beric/clipsync/browse/FileBridge.kt shared/src/desktopTest/kotlin/ca/beric/clipsync/browse/JvmFileBridgeTest.kt
git commit -m "feat(browse): FileBridge seam with a plain java.io implementation"
```

---

### Task 3: `BrowseEngine` — roots, path confinement, listing

This is the security core of the milestone. Confinement is the single most important behavior in M9: everything else assumes it holds.

**Files:**
- Create: `shared/src/jvmShared/kotlin/ca/beric/clipsync/browse/BrowseEngine.kt`
- Test: `shared/src/desktopTest/kotlin/ca/beric/clipsync/browse/BrowseEngineTest.kt`

**Interfaces:**
- Consumes: `FileBridge`, `JvmFileBridge` (Task 2); `MirrorEvent.*` (Task 1).
- Produces: `data class BrowseRoot(val id: String, val label: String, val path: String)`; `class BrowseEngine(scope, bridge, roots, enabled, clock, log)` with the settable property `var transfers: FileTransferEngine?`, plus `suspend fun onEvent(from: String, event: MirrorEvent): MirrorEvent?`, `fun resolve(rootId: String, rel: String): String?`, `fun confineAbsolute(abs: String): String?`; `BrowseEngine.TRASH_DIR`, `BrowseEngine.MAX_ENTRIES`.
- **Note for Tasks 4, 6, and 9:** `transfers` is deliberately *not* a constructor parameter — see its KDoc below. Author every later task against that shape; nothing needs to change it again.

- [ ] **Step 1: Write the failing tests**

```kotlin
package ca.beric.clipsync.browse

import ca.beric.clipsync.protocol.MirrorEvent
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun listingIsCapped() = runBlocking {
        val e = engine(this)
        repeat(BrowseEngine.MAX_ENTRIES + 25) { File(root, "f$it").writeText("x") }
        val reply = e.onEvent("peer", MirrorEvent.FsQueryList("r", "")) as MirrorEvent.FsEntries
        assertEquals(BrowseEngine.MAX_ENTRIES, reply.entries.size)
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
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :shared:desktopTest --tests '*BrowseEngineTest*'`
Expected: FAIL — `Unresolved reference: BrowseEngine`.

- [ ] **Step 3: Write the implementation**

```kotlin
package ca.beric.clipsync.browse

import ca.beric.clipsync.protocol.FsRoot
import ca.beric.clipsync.protocol.MirrorEvent
import ca.beric.clipsync.transfer.FileTransferEngine
import kotlinx.coroutines.CoroutineScope

/** One browsable location on this device. [path] is absolute; [id] is what the wire carries. */
data class BrowseRoot(val id: String, val label: String, val path: String)

/**
 * Serves file and photo browsing to a paired peer (M9). Every request names a root and a path
 * relative to it; [resolve] canonicalizes the join and refuses anything that no longer sits
 * under that root, which kills "..", absolute paths, and symlink escapes in one check.
 *
 * Storage is reached only through [FileBridge], so this whole class is exercised on the
 * desktop against a temp directory — the Android side is a thin adapter over the same seam.
 *
 * Nothing here runs while [enabled] returns false: a disabled engine never reads storage.
 */
class BrowseEngine(
    private val scope: CoroutineScope,
    private val bridge: FileBridge,
    private val roots: List<BrowseRoot>,
    private val enabled: () -> Boolean,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val log: (String) -> Unit = {},
) {

    /**
     * Set once at wiring time. It is a property rather than a constructor parameter because
     * the Android graph is cyclic: the transfer engine needs a sink that confines paths
     * through this engine, and this engine needs the transfer engine to answer a pull. A pull
     * arriving before this is set answers "transfers unavailable".
     */
    var transfers: FileTransferEngine? = null

    /** Answers one browse request, or null when the event isn't ours / the answer is bytes. */
    suspend fun onEvent(fromDeviceId: String, event: MirrorEvent): MirrorEvent? {
        val op = opNameOf(event) ?: return null
        if (!enabled()) return MirrorEvent.FsResult(op, false, "browsing disabled")
        return when (event) {
            is MirrorEvent.FsQueryRoots -> MirrorEvent.FsRoots(roots.map { FsRoot(it.id, it.label) })
            is MirrorEvent.FsQueryList -> onList(event)
            else -> null // mutations and transfers arrive in Tasks 4 and 6
        }
    }

    /**
     * Absolute canonical path for [rel] under [rootId], or null when it escapes the root.
     * A rooted path ("/etc/passwd") is refused outright rather than quietly re-rooted under
     * the browse root — a client that sends one has a bug, and an error names it.
     */
    fun resolve(rootId: String, rel: String): String? {
        val root = roots.firstOrNull { it.id == rootId } ?: return null
        val trimmed = rel.trim()
        if (trimmed.startsWith("/") || trimmed.startsWith("\\")) return null
        // A null from canonical() means resolution failed — deny. Never fall through to an
        // unresolved path: it can start with the root prefix and still contain "..".
        val rootCanon = bridge.canonical(root.path) ?: return null
        val joined = if (trimmed.isEmpty()) rootCanon else "$rootCanon/$trimmed"
        val canon = bridge.canonical(joined) ?: return null
        return if (canon == rootCanon || canon.startsWith("$rootCanon/")) canon else null
    }

    /** Confines an already-absolute path (used for an inbound push destination). */
    fun confineAbsolute(abs: String): String? {
        val canon = bridge.canonical(abs) ?: return null
        return roots.firstOrNull { root ->
            val rootCanon = bridge.canonical(root.path) ?: return@firstOrNull false
            canon == rootCanon || canon.startsWith("$rootCanon/")
        }?.let { canon }
    }

    private fun onList(q: MirrorEvent.FsQueryList): MirrorEvent {
        val abs = resolve(q.root, q.path) ?: return MirrorEvent.FsResult("list", false, "path rejected")
        val entries = bridge.list(abs)
            .filterNot { it.dir && it.name == TRASH_DIR }
            .sortedWith(compareByDescending<ca.beric.clipsync.protocol.FsEntry> { it.dir }.thenBy { it.name.lowercase() })
            .take(MAX_ENTRIES)
        return MirrorEvent.FsEntries(q.root, q.path, entries)
    }

    /** The op label for an FsResult, or null when this event isn't a browse request at all. */
    private fun opNameOf(event: MirrorEvent): String? = when (event) {
        is MirrorEvent.FsQueryRoots -> "roots"
        is MirrorEvent.FsQueryList -> "list"
        is MirrorEvent.MediaQuery -> "media"
        is MirrorEvent.ThumbQuery -> "thumbs"
        is MirrorEvent.FsPull -> "pull"
        is MirrorEvent.FsPush -> "push"
        is MirrorEvent.FsDelete -> "delete"
        is MirrorEvent.FsRename -> "rename"
        else -> null
    }

    companion object {
        /** Deleted entries move here rather than being unlinked, so a delete is reversible. */
        const val TRASH_DIR = ".clipsync-trash"

        /** A directory listing is bounded: one envelope, one screenful of scrolling. */
        const val MAX_ENTRIES = 2000
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :shared:desktopTest --tests '*BrowseEngineTest*'`
Expected: PASS, 10 tests.

- [ ] **Step 5: Commit**

```bash
git add shared/src/jvmShared/kotlin/ca/beric/clipsync/browse/BrowseEngine.kt shared/src/desktopTest/kotlin/ca/beric/clipsync/browse/BrowseEngineTest.kt
git commit -m "feat(browse): BrowseEngine with root confinement and directory listing"
```

---

### Task 4: `BrowseEngine` — delete to trash, and rename

**Files:**
- Modify: `shared/src/jvmShared/kotlin/ca/beric/clipsync/browse/BrowseEngine.kt`
- Test: `shared/src/desktopTest/kotlin/ca/beric/clipsync/browse/BrowseEngineMutationTest.kt`

**Interfaces:**
- Consumes: `BrowseEngine`, `BrowseRoot` (Task 3).
- Produces: `MirrorEvent.FsDelete` and `.FsRename` handling, both answering `MirrorEvent.FsResult`.

- [ ] **Step 1: Write the failing tests**

```kotlin
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :shared:desktopTest --tests '*BrowseEngineMutationTest*'`
Expected: FAIL — the `when` in `onEvent` still returns null for `FsDelete`/`FsRename`, so the casts to `FsResult` throw `NullPointerException`.

- [ ] **Step 3: Handle the mutations**

In `BrowseEngine.onEvent`, replace the `else -> null` branch:

```kotlin
            is MirrorEvent.FsDelete -> onDelete(event)
            is MirrorEvent.FsRename -> onRename(event)
            else -> null // transfers arrive in Task 6
```

Add the implementations, plus the imports `java.time.Instant`, `java.time.ZoneId`, `java.time.format.DateTimeFormatter`:

```kotlin
    /**
     * Trash-first delete: each entry is *moved* into <root>/.clipsync-trash, so deleting a
     * directory costs one rename and stays fully reversible. A move that fails changes
     * nothing — we never fall back to copy-then-unlink, which could half-delete a file.
     */
    private fun onDelete(req: MirrorEvent.FsDelete): MirrorEvent {
        val rootPath = resolve(req.root, "") ?: return MirrorEvent.FsResult("delete", false, "path rejected")
        // Confine the trash directory itself, exactly as onRename confines its target. A
        // symlinked .clipsync-trash would otherwise be followed by mkdirs/renameTo and land
        // deleted files outside the root — the destination has to be checked, not assumed.
        val trash = confineAbsolute("$rootPath/$TRASH_DIR")
            ?: return MirrorEvent.FsResult("delete", false, "trash rejected")
        val stamp = STAMP.format(Instant.ofEpochMilli(clock()).atZone(ZoneId.systemDefault()))
        var moved = 0
        for (rel in req.paths) {
            val abs = resolve(req.root, rel)
                ?: return MirrorEvent.FsResult("delete", false, "path rejected")
            if (abs == rootPath || abs == trash || abs.startsWith("$trash/")) {
                return MirrorEvent.FsResult("delete", false, "path rejected")
            }
            if (!bridge.mkdirs(trash)) return MirrorEvent.FsResult("delete", false, "could not open trash")
            val name = abs.substringAfterLast('/')
            var target = "$trash/$stamp-$name"
            var n = 1
            while (bridge.exists(target)) target = "$trash/$stamp-$n-$name".also { n++ }
            if (!bridge.move(abs, target)) {
                // Name what already moved: a batch that fails partway has genuinely trashed
                // the earlier entries, and a bare ok=false would leave the peer unable to tell.
                return MirrorEvent.FsResult(
                    "delete", false,
                    "moved $moved of ${req.paths.size}; failed on $name",
                )
            }
            moved++
        }
        log("browse delete: $moved to trash")
        return MirrorEvent.FsResult("delete", true, "$moved")
    }

    /** Same-directory rename only. Changing directories is a move, and moves are not in v1. */
    private fun onRename(req: MirrorEvent.FsRename): MirrorEvent {
        val name = req.newName.trim()
        if (name.isEmpty() || name == "." || name == ".." || '/' in name || '\\' in name) {
            return MirrorEvent.FsResult("rename", false, "invalid name")
        }
        val abs = resolve(req.root, req.path) ?: return MirrorEvent.FsResult("rename", false, "path rejected")
        if (abs == resolve(req.root, "")) return MirrorEvent.FsResult("rename", false, "path rejected")
        val target = "${abs.substringBeforeLast('/')}/$name"
        if (confineAbsolute(target) == null) return MirrorEvent.FsResult("rename", false, "path rejected")
        if (bridge.exists(target)) return MirrorEvent.FsResult("rename", false, "name already taken")
        if (!bridge.move(abs, target)) return MirrorEvent.FsResult("rename", false, "rename failed")
        log("browse rename: ${abs.substringAfterLast('/')} -> $name")
        return MirrorEvent.FsResult("rename", true, name)
    }
```

And in the companion object:

```kotlin
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :shared:desktopTest --tests '*BrowseEngineMutationTest*'`
Expected: PASS, 9 tests.

- [ ] **Step 5: Run the whole suite**

Run: `./gradlew :shared:desktopTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add shared/src/jvmShared/kotlin/ca/beric/clipsync/browse/BrowseEngine.kt shared/src/desktopTest/kotlin/ca/beric/clipsync/browse/BrowseEngineMutationTest.kt
git commit -m "feat(browse): reversible trash-first delete and same-directory rename"
```

---

### Task 5: Transfer engine — targeted sends and a destination-aware sink

**Files:**
- Modify: `shared/src/jvmShared/kotlin/ca/beric/clipsync/transfer/FileTransferEngine.kt:124-142`, `:232`
- Modify: `shared/src/jvmShared/kotlin/ca/beric/clipsync/transfer/FileTransfer.kt` (the `FileSink` interface and `FolderFileSink`)
- Modify: `androidApp/src/main/kotlin/ca/beric/clipsync/android/transfer/MediaStoreFileSink.kt:23` (the override must widen with the interface)
- Test: `shared/src/desktopTest/kotlin/ca/beric/clipsync/transfer/FileTransferTargetingTest.kt`

**Interfaces:**
- Consumes: `ControlMessage.FileOffer.dest` (Task 1).
- Produces: `suspend fun FileTransferEngine.sendFile(source: FileSource, toDeviceId: String? = null, dest: String = ""): Boolean`; `fun FileSink.begin(name: String, mime: String, dest: String = ""): PendingFile`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package ca.beric.clipsync.transfer

import ca.beric.clipsync.crypto.ClipsyncCrypto
import ca.beric.clipsync.protocol.ControlMessage
import ca.beric.clipsync.sync.RemotePeer
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileTransferTargetingTest {

    private fun source(bytes: ByteArray) =
        FileSource("x.bin", bytes.size.toLong(), "application/octet-stream") { ByteArrayInputStream(bytes) }

    @Test
    fun aTargetedSendOffersOnlyToTheNamedPeer() = runBlocking {
        val offeredTo = mutableListOf<String>()
        // offerAckTimeoutMs = 50: the fake peers never ack, so each send fails fast on the
        // offer timeout. We are asserting *who* was offered to, not that bytes moved — with
        // the default 15 s timeout this test would stall for half a minute.
        val engine = FileTransferEngine(
            this,
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
        val engine = FileTransferEngine(this, FolderFileSink(Files.createTempDirectory("t3").toFile()), offerAckTimeoutMs = 50)
        engine.addPeer(RemotePeer("A", ClipsyncCrypto.randomKey(), send = { error("must not send") }))
        assertEquals(false, engine.sendFile(source("x".encodeToByteArray()), toDeviceId = "ghost"))
    }

    @Test
    fun theDestinationRidesTheOffer() = runBlocking {
        var seen: String? = null
        val engine = FileTransferEngine(this, FolderFileSink(Files.createTempDirectory("t4").toFile()), offerAckTimeoutMs = 50)
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :shared:desktopTest --tests '*FileTransferTargetingTest*'`
Expected: FAIL — `No value passed for parameter` / `Cannot find a parameter with this name: toDeviceId`.

- [ ] **Step 3: Add the destination parameter to the sink**

In `FileTransfer.kt`, change the `FileSink` interface and `FolderFileSink.begin`:

```kotlin
/** Platform destination for received files (desktop: a folder; Android: MediaStore Downloads). */
interface FileSink {
    /**
     * Begins a pending receive under [name] (already sanitized by the engine). [dest] is the
     * sender's requested absolute directory (M9 push) — implementations that cannot or must
     * not honor it ignore it. The desktop always ignores it: a peer never steers a write here.
     */
    fun begin(name: String, mime: String, dest: String = ""): PendingFile
}
```

```kotlin
    override fun begin(name: String, mime: String, dest: String): PendingFile {
```

(the body is unchanged — `dest` is deliberately unused here.)

**Every existing implementor must widen too, or `:androidApp` stops compiling.** In
`androidApp/src/main/kotlin/ca/beric/clipsync/android/transfer/MediaStoreFileSink.kt`, change
the override the same way:

```kotlin
    override fun begin(name: String, mime: String, dest: String): PendingFile {
```

Its body is unchanged as well — routing on `dest` is Task 9's `DispatchingFileSink`, not this
class. Callers that pass two arguments keep compiling: the default lives on the interface.

- [ ] **Step 4: Add targeting and `dest` to the engine**

In `FileTransferEngine.kt`, replace `sendFile` and thread the destination through:

```kotlin
    /**
     * Offers [source] to [toDeviceId], or to every connected peer when null, and streams it.
     * Returns false (doing nothing) when no such peer is connected. [dest] asks the receiver
     * to write into that absolute directory; empty means "your default".
     */
    suspend fun sendFile(source: FileSource, toDeviceId: String? = null, dest: String = ""): Boolean {
        val targets = mutex.withLock {
            if (toDeviceId == null) peers.values.toList() else listOfNotNull(peers[toDeviceId])
        }
        if (targets.isEmpty()) return false
        coroutineScope { targets.forEach { peer -> launch { sendToPeer(source, peer, dest) } } }
        return true
    }

    private suspend fun sendToPeer(source: FileSource, peer: RemotePeer, dest: String) {
```

and the offer itself:

```kotlin
            peer.send(ControlMessage.FileOffer(id, source.name, source.size, source.mime, sha, chunkCount, dest))
```

and the receive side at `onOffer`:

```kotlin
        val pending = runCatching { sink.begin(name, offer.mime, offer.dest) }.getOrElse {
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :shared:desktopTest --tests '*FileTransferTargetingTest*'`
Expected: PASS, 4 tests.

- [ ] **Step 6: Verify no existing caller broke — including Android**

Run: `./gradlew :shared:desktopTest :androidApp:assembleDebug`
Expected: both PASS. The new parameters have defaults, so *call sites* like `sendFile(source)` and `begin(name, mime)` compile untouched — but an `override` must match the widened signature exactly, which is why `MediaStoreFileSink` changed in Step 3. Building `:androidApp` here is what proves no implementor was missed; `:shared:desktopTest` alone would not catch it.

- [ ] **Step 7: Commit**

```bash
git add shared/src/jvmShared/kotlin/ca/beric/clipsync/transfer/ shared/src/desktopTest/kotlin/ca/beric/clipsync/transfer/FileTransferTargetingTest.kt
git commit -m "feat(transfer): target a single peer and carry an optional destination"
```

---

### Task 6: `BrowseEngine` — pull and push

**Files:**
- Modify: `shared/src/jvmShared/kotlin/ca/beric/clipsync/browse/BrowseEngine.kt`
- Test: `shared/src/desktopTest/kotlin/ca/beric/clipsync/browse/BrowsePullPushTest.kt`

**Interfaces:**
- Consumes: `sendFile(source, toDeviceId, dest)` (Task 5); `BrowseEngine` (Tasks 3–4).
- Produces: `MirrorEvent.FsPull` → a targeted file transfer; `MirrorEvent.FsPush` → `FsResult(ok = true, detail = <absolute dir>)`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package ca.beric.clipsync.browse

import ca.beric.clipsync.crypto.ClipsyncCrypto
import ca.beric.clipsync.protocol.ControlMessage
import ca.beric.clipsync.protocol.MirrorEvent
import ca.beric.clipsync.sync.RemotePeer
import ca.beric.clipsync.transfer.FileTransferEngine
import ca.beric.clipsync.transfer.FolderFileSink
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.EmptyCoroutineContext
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :shared:desktopTest --tests '*BrowsePullPushTest*'`
Expected: FAIL — `FsPull`/`FsPush` still fall into the `else -> null` branch, so the casts throw.

- [ ] **Step 3: Implement pull and push**

Replace the `else -> null` branch in `onEvent` with:

```kotlin
            is MirrorEvent.FsPull -> onPull(fromDeviceId, event)
            is MirrorEvent.FsPush -> onPush(event)
            else -> null // media and thumbnails are answered by the platform layer
```

Add, with `import ca.beric.clipsync.transfer.FileSource` and `import kotlinx.coroutines.launch`:

```kotlin
    /**
     * Streams one file to the requester over the M6 engine. The answer is bytes, not an
     * event, so this returns null on success. Note [FileSource.open] is invoked twice by the
     * transfer engine (hash pass, then stream pass) — [FileBridge.open] must give a fresh
     * stream each time.
     */
    private fun onPull(toDeviceId: String, req: MirrorEvent.FsPull): MirrorEvent? {
        val engine = transfers ?: return MirrorEvent.FsResult("pull", false, "transfers unavailable")
        val abs = resolve(req.root, req.path) ?: return MirrorEvent.FsResult("pull", false, "path rejected")
        val stat = bridge.stat(abs) ?: return MirrorEvent.FsResult("pull", false, "not found")
        if (stat.dir) return MirrorEvent.FsResult("pull", false, "not a file")
        val source = FileSource(stat.name, stat.size, stat.mime) { bridge.open(abs) }
        scope.launch {
            if (!engine.sendFile(source, toDeviceId = toDeviceId)) log("browse pull: peer $toDeviceId gone")
        }
        log("browse pull: ${stat.name} (${stat.size} B) -> $toDeviceId")
        return null
    }

    /** Confirms (and creates) a destination directory; the peer then sends with dest set. */
    private fun onPush(req: MirrorEvent.FsPush): MirrorEvent {
        val abs = resolve(req.root, req.dir) ?: return MirrorEvent.FsResult("push", false, "path rejected")
        if (!bridge.mkdirs(abs)) return MirrorEvent.FsResult("push", false, "could not create $abs")
        return MirrorEvent.FsResult("push", true, abs)
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :shared:desktopTest --tests '*BrowsePullPushTest*'`
Expected: PASS, 7 tests.

- [ ] **Step 5: Harden `create()` against a symlinked final component**

A push destination does not exist when `resolve()` checks it, so it cannot be a symlink *then* — but `File(path).outputStream()` follows one planted between the check and the write, redirecting a received file anywhere the process can write. Task 6 is where push becomes reachable, so it is where this closes. In `shared/src/jvmShared/kotlin/ca/beric/clipsync/browse/FileBridge.kt`, change `JvmFileBridge.create` (and document the contract on the interface method):

```kotlin
    override fun create(path: String): OutputStream =
        Files.newOutputStream(
            File(path).toPath(),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).buffered()
```

with `import java.nio.file.Files`, `java.nio.file.LinkOption`, `java.nio.file.StandardOpenOption`. **`CREATE_NEW` is the option that actually carries the guarantee** — POSIX specifies that `O_CREAT|O_EXCL` fails with `EEXIST` when the final component is a symlink, dangling or not (verified on this machine: `O_CREAT|O_EXCL` refused, plain `O_CREAT` followed the link). `NOFOLLOW_LINKS` is defense-in-depth for opens that are not exclusive-create. Keep both, and do not expect the covering test to distinguish them. Add the covering test:

```kotlin
    @Test
    fun createRefusesToWriteThroughASymlink() {
        val dir = Files.createTempDirectory("clipsync-nofollow").toFile()
        val outside = File(Files.createTempDirectory("clipsync-target").toFile(), "victim.txt")
        outside.writeText("original")
        val planted = File(dir, "incoming.bin")
        Files.createSymbolicLink(planted.toPath(), outside.toPath())
        assertFailsWith<java.io.IOException> { JvmFileBridge().create(planted.absolutePath) }
        assertEquals("original", outside.readText(), "a planted symlink redirected the write")
    }
```

(`import kotlin.test.assertFailsWith` in `JvmFileBridgeTest.kt`.) Note `FolderFileSink` writes through `File.createTempFile` + `renameTo`, which is already safe; this change is for the `FileBridge` write path that Task 9's `DispatchingFileSink` uses.

- [ ] **Step 6: Run the whole suite**

Run: `./gradlew :shared:desktopTest`
Expected: PASS. Total should now be 75 + 3 + 7 + 12 + 10 + 4 + 8 = 119, 1 skipped (Tasks 2 and 3 grew by their fix rounds; Task 4 gained the trash-addressability test).

- [ ] **Step 7: Commit**

```bash
git add shared/src/jvmShared/kotlin/ca/beric/clipsync/browse/ shared/src/desktopTest/kotlin/ca/beric/clipsync/browse/
git commit -m "feat(browse): pull a file to the requester, confirm a push destination"
```

---

### Task 7: Android — the Shizuku SHELL-uid file service

No unit test is possible here (it needs a device and a running Shizuku). The task's deliverable is "the app builds and the service binds"; real verification is the on-device run in Task 12.

**Files:**
- Create: `androidApp/src/main/aidl/ca/beric/clipsync/android/browse/IFileBridge.aidl`
- Create: `androidApp/src/main/kotlin/ca/beric/clipsync/android/browse/FileBridgeService.kt`
- Create: `androidApp/src/main/kotlin/ca/beric/clipsync/android/browse/ShizukuFileBridge.kt`
- Modify: `androidApp/build.gradle.kts` (enable the AIDL build feature)

**Interfaces:**
- Consumes: `FileBridge` (Task 2).
- Produces: `class ShizukuFileBridge(context: Context) : FileBridge` with `fun bind()` and `fun isReady(): Boolean`.

- [ ] **Step 1: Enable AIDL**

In `androidApp/build.gradle.kts`, extend the existing `buildFeatures` block:

```kotlin
    buildFeatures {
        compose = true
        aidl = true
    }
```

- [ ] **Step 2: Declare the interface**

`androidApp/src/main/aidl/ca/beric/clipsync/android/browse/IFileBridge.aidl`:

```aidl
package ca.beric.clipsync.android.browse;

interface IFileBridge {
    /**
     * Tab-separated "size\tdir\tmtime\tname" rows; empty array for a missing directory.
     * The name is LAST on purpose: it is the only user-controlled field, and a tab inside it
     * must not shift the others. Readers split with limit=4 so such a name survives intact.
     */
    String[] list(String dir);
    /** One "size\tdir\tmtime\tname" row, or null when the path does not exist. */
    String stat(String path);
    boolean exists(String path);
    String canonical(String path);
    ParcelFileDescriptor open(String path);
    ParcelFileDescriptor create(String path);
    boolean move(String from, String to);
    boolean delete(String path);
    boolean mkdirs(String path);
}
```

- [ ] **Step 3: Implement the service**

`FileBridgeService.kt`:

```kotlin
package ca.beric.clipsync.android.browse

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Runs inside a SHELL-uid process started by Shizuku, so plain java.io sees shared storage
 * that a scoped-storage app cannot. It carries no policy: paths arrive already confined by
 * BrowseEngine. Shizuku requires a no-arg or Context constructor.
 */
class FileBridgeService : IFileBridge.Stub() {

    override fun list(dir: String): Array<String> =
        File(dir).listFiles()?.map { it.row() }?.toTypedArray() ?: emptyArray()

    override fun stat(path: String): String? = File(path).takeIf { it.exists() }?.row()

    override fun exists(path: String): Boolean = File(path).exists()

    override fun canonical(path: String): String? =
        runCatching { File(path).canonicalPath }.getOrNull()

    override fun open(path: String): ParcelFileDescriptor? = runCatching {
        ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
    }.getOrNull()

    /**
     * O_CREAT|O_EXCL|O_NOFOLLOW in one syscall. A symlink planted at the destination between
     * confinement and write would otherwise redirect a received file — and this write runs as
     * the SHELL uid, so the blast radius is the whole filesystem. It must be one atomic open:
     * creating the file and then reopening it by path leaves the same race in a smaller window.
     * O_EXCL also makes "already exists" an error instead of a silent overwrite.
     */
    override fun create(path: String): ParcelFileDescriptor? = runCatching {
        File(path).parentFile?.mkdirs()
        val fd = Os.open(
            path,
            OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW,
            DEFAULT_FILE_MODE,
        )
        // try/finally, not .also{}: if dup() itself throws (EMFILE/ENFILE), .also never runs
        // and the raw fd leaks.
        try {
            ParcelFileDescriptor.dup(fd)
        } finally {
            Os.close(fd)
        }
    }.getOrNull()

    override fun move(from: String, to: String): Boolean =
        runCatching { File(from).renameTo(File(to)) }.getOrDefault(false)

    override fun delete(path: String): Boolean = runCatching { File(path).deleteRecursively() }.getOrDefault(false)

    override fun mkdirs(path: String): Boolean = File(path).let { it.isDirectory || it.mkdirs() }

    /** size ‖ dir ‖ mtime ‖ name — name last so a tab inside it cannot shift the other fields. */
    private fun File.row(): String =
        listOf(if (isDirectory) 0L else length(), isDirectory, lastModified(), name).joinToString("\t")

    private companion object {
        /** rw-rw---- : the shell uid writes, the media scanner's group reads. */
        const val DEFAULT_FILE_MODE = 432 // 0660
    }
}
```

- [ ] **Step 4: Implement the client adapter**

`ShizukuFileBridge.kt`:

```kotlin
package ca.beric.clipsync.android.browse

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import ca.beric.clipsync.browse.FileBridge
import ca.beric.clipsync.browse.JvmFileBridge
import ca.beric.clipsync.protocol.FsEntry
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import rikka.shizuku.Shizuku

/**
 * [FileBridge] backed by [FileBridgeService] running as the SHELL uid. Every call fails
 * closed (empty / false / throw) when the service isn't bound, which is the same posture
 * clipboard capture already takes when Shizuku is stopped.
 */
class ShizukuFileBridge(context: Context) : FileBridge {

    private val appContext = context.applicationContext

    @Volatile
    private var service: IFileBridge? = null

    private val args = Shizuku.UserServiceArgs(
        ComponentName(appContext.packageName, FileBridgeService::class.java.name),
    ).daemon(false).processNameSuffix("filebridge").debuggable(false).version(SERVICE_VERSION)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.let { IFileBridge.Stub.asInterface(it) }
            Log.i(TAG, "file bridge bound=${service != null}")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            Log.i(TAG, "file bridge unbound")
        }
    }

    /** Idempotent; safe to call again after a Shizuku restart. */
    fun bind() {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return
        runCatching { Shizuku.bindUserService(args, connection) }
            .onFailure { Log.w(TAG, "bindUserService failed: ${it.message}") }
    }

    /** A bound service whose binder has already died is not ready — ping, don't just null-check. */
    fun isReady(): Boolean = service != null && runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    // Null when the service isn't bound. Echoing the input path back would hand BrowseEngine
    // an unresolved string to confine against — the same bypass the JVM bridge avoids.
    override fun canonical(path: String): String? = service?.canonical(path)

    override fun list(dir: String): List<FsEntry> =
        service?.list(dir)?.mapNotNull { parseRow(it) } ?: emptyList()

    override fun stat(path: String): FsEntry? = service?.stat(path)?.let { parseRow(it) }

    override fun exists(path: String): Boolean = service?.exists(path) ?: false

    override fun open(path: String): InputStream {
        val fd = service?.open(path) ?: throw IOException("file bridge unavailable")
        return ParcelFileDescriptor.AutoCloseInputStream(fd).buffered()
    }

    override fun create(path: String): OutputStream {
        val fd = service?.create(path) ?: throw IOException("file bridge unavailable")
        return ParcelFileDescriptor.AutoCloseOutputStream(fd).buffered()
    }

    override fun move(from: String, to: String): Boolean = service?.move(from, to) ?: false

    override fun delete(path: String): Boolean = service?.delete(path) ?: false

    override fun mkdirs(path: String): Boolean = service?.mkdirs(path) ?: false

    /**
     * "size\tdir\tmtime\tname" — the service's wire row. limit = 4 keeps a tab-containing
     * filename intact in the final field instead of splitting it into a fifth part, which
     * would drop the entry from the listing while the file itself stayed on disk.
     */
    private fun parseRow(row: String): FsEntry? {
        val parts = row.split('\t', limit = 4)
        if (parts.size != 4) return null
        val dir = parts[1].toBoolean()
        val name = parts[3]
        return FsEntry(
            name = name,
            size = parts[0].toLongOrNull() ?: 0L,
            dir = dir,
            mtimeMs = parts[2].toLongOrNull() ?: 0L,
            mime = if (dir) "" else JvmFileBridge.guessMime(name),
        )
    }

    companion object {
        private const val TAG = "clipsyncFiles"
        private const val SERVICE_VERSION = 1
    }
}
```

- [ ] **Step 5: Verify the app compiles**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL. (If it fails with "SDK location not found", write `local.properties` — see Global Constraints.)

- [ ] **Step 6: Commit**

```bash
git add androidApp/build.gradle.kts androidApp/src/main/aidl androidApp/src/main/kotlin/ca/beric/clipsync/android/browse
git commit -m "feat(android): SHELL-uid file bridge over a Shizuku user service"
```

---

### Task 8: Android — MediaStore photo index and thumbnails

**Files:**
- Create: `androidApp/src/main/kotlin/ca/beric/clipsync/android/browse/MediaIndex.kt`
- Modify: `androidApp/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `MediaItem` (Task 1).
- Produces: `class MediaIndex(context: Context)` with `fun hasPermission(): Boolean`, `fun items(offset: Int, limit: Int): List<MediaItem>`, `fun thumbs(ids: List<Long>): Map<Long, String>`; `MediaIndex.PERMISSIONS: Array<String>`, `MediaIndex.MAX_THUMBS`.

- [ ] **Step 1: Declare the permissions**

In `androidApp/src/main/AndroidManifest.xml`, beside the existing `<uses-permission>` entries:

```xml
    <!-- M9 photo grid: read-only MediaStore metadata + system thumbnails. Images only —
         nothing here queries video, and this app does not request access it does not use. -->
    <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
    <uses-permission
        android:name="android.permission.READ_EXTERNAL_STORAGE"
        android:maxSdkVersion="32" />
```

- [ ] **Step 2: Write the index**

```kotlin
package ca.beric.clipsync.android.browse

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.util.Size
import ca.beric.clipsync.protocol.MediaItem
import java.io.ByteArrayOutputStream

/**
 * Read-only MediaStore access for the desktop's photo grid (M9). Thumbnails come from
 * ContentResolver.loadThumbnail, which is cache-backed — decoding a 12 MP JPEG per tile
 * would be far slower. Mutations never come through here: they go through the Shizuku
 * bridge, so there is one confined write path.
 */
class MediaIndex(context: Context) {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    fun hasPermission(): Boolean =
        PERMISSIONS.all { appContext.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Newest-first images. Absent permission answers empty rather than throwing. */
    fun items(offset: Int, limit: Int): List<MediaItem> {
        if (!hasPermission()) return emptyList()
        val out = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
        )
        val order = "${MediaStore.Images.Media.DATE_MODIFIED} DESC LIMIT ${limit.coerceIn(1, 200)} OFFSET ${offset.coerceAtLeast(0)}"
        runCatching {
            resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, order)?.use { c ->
                while (c.moveToNext()) {
                    out += MediaItem(
                        id = c.getLong(0),
                        name = c.getString(1).orEmpty(),
                        size = c.getLong(2),
                        dateMs = c.getLong(3) * 1000L, // DATE_MODIFIED is seconds
                        mime = c.getString(4).orEmpty(),
                        width = c.getInt(5),
                        height = c.getInt(6),
                    )
                }
            }
        }.onFailure { Log.w(TAG, "media query failed: ${it.message}") }
        return out
    }

    /** MediaStore id → base64 JPEG, ≤[MAX_THUMBS] per call. Ids that fail are omitted. */
    fun thumbs(ids: List<Long>): Map<Long, String> {
        if (!hasPermission()) return emptyMap()
        val out = LinkedHashMap<Long, String>()
        for (id in ids.take(MAX_THUMBS)) {
            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.buildUpon().appendPath(id.toString()).build()
            runCatching {
                val bmp = resolver.loadThumbnail(uri, Size(THUMB_PX, THUMB_PX), null)
                val bytes = ByteArrayOutputStream().use { buf ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, buf)
                    buf.toByteArray()
                }
                out[id] = Base64.encodeToString(bytes, Base64.NO_WRAP)
            }.onFailure { Log.w(TAG, "thumb $id failed: ${it.message}") }
        }
        return out
    }

    companion object {
        private const val TAG = "clipsyncMedia"
        private const val THUMB_PX = 256
        private const val THUMB_QUALITY = 80

        /** One envelope's worth of tiles. */
        const val MAX_THUMBS = 24

        val PERMISSIONS: Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Images only. Requiring READ_MEDIA_VIDEO as well would make a user who grants
                // Photos but denies Videos see an empty grid, for a query that never runs.
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
    }
}
```

- [ ] **Step 3: Verify the app compiles**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add androidApp/src/main/AndroidManifest.xml androidApp/src/main/kotlin/ca/beric/clipsync/android/browse/MediaIndex.kt
git commit -m "feat(android): read-only MediaStore index and cached thumbnails"
```

---

### Task 9: Android — wire browse into `AppGraph`, behind an off-by-default toggle

**Files:**
- Modify: `androidApp/src/main/kotlin/ca/beric/clipsync/android/AppGraph.kt:99-180` (fields + `startSync`), `:434-462` (`handleMirrorEvent`)
- Create: `androidApp/src/main/kotlin/ca/beric/clipsync/android/browse/BrowsePrefs.kt`
- Create: `androidApp/src/main/kotlin/ca/beric/clipsync/android/transfer/DispatchingFileSink.kt`

**Interfaces:**
- Consumes: `BrowseEngine`, `BrowseRoot` (Tasks 3–6); `ShizukuFileBridge` (Task 7); `MediaIndex` (Task 8); `FileSink` (Task 5).
- Produces: `object BrowsePrefs` with `fun enabled(context: Context): Boolean` / `fun setEnabled(context: Context, on: Boolean)`; `class DispatchingFileSink(mediaStore: FileSink, bridge: FileBridge, confine: (String) -> String?) : FileSink`; `AppGraph.browseEngine`.

- [ ] **Step 1: The persisted toggle**

```kotlin
package ca.beric.clipsync.android.browse

import android.content.Context

/** The M9 consent gate: off until the user turns it on, and it stays on until they don't. */
object BrowsePrefs {
    private const val FILE = "clipsync"
    private const val KEY = "browse_enabled"

    fun enabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, on).apply()
    }
}
```

- [ ] **Step 2: The destination-aware sink**

```kotlin
package ca.beric.clipsync.android.transfer

import ca.beric.clipsync.browse.FileBridge
import ca.beric.clipsync.transfer.FileSink
import ca.beric.clipsync.transfer.PendingFile
import java.io.IOException
import java.io.OutputStream

/**
 * Routes an inbound file by whether the sender named a destination. No destination (every
 * share-sheet send, every pre-0.4 peer) keeps the M6 behavior exactly: MediaStore
 * Download/clipsync. A destination goes through the confined [FileBridge], so a browse push
 * cannot write anywhere BrowseEngine would not have let it read.
 */
class DispatchingFileSink(
    private val mediaStore: FileSink,
    private val bridge: FileBridge,
    private val confine: (String) -> String?,
) : FileSink {

    override fun begin(name: String, mime: String, dest: String): PendingFile {
        if (dest.isBlank()) return mediaStore.begin(name, mime)
        val dir = confine(dest) ?: throw IOException("destination rejected: $dest")
        // Unique per receive: create() uses O_EXCL, so a fixed temp name left behind by an
        // interrupted push would block every retry of that filename permanently.
        val temp = "$dir/.clipsync-recv-${ClipsyncCrypto.toHex(ClipsyncCrypto.randomBytes(8))}.part"
        val target = claim(dir, name)
        val out = bridge.create(temp)
        return object : PendingFile {
            override val stream: OutputStream = out

            override fun publish(): String {
                out.close()
                if (!bridge.move(temp, target)) {
                    bridge.delete(temp)
                    throw IOException("could not move received file into $target")
                }
                return target
            }

            override fun discard() {
                runCatching { out.close() }
                bridge.delete(temp)
            }
        }
    }

    /** First free "name", "name (1)", … so a push never silently overwrites. */
    private fun claim(dir: String, name: String): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 0
        while (true) {
            val candidate = if (n == 0) "$dir/$name" else "$dir/$base ($n)$ext"
            if (!bridge.exists(candidate)) return candidate
            n++
        }
    }
}
```

- [ ] **Step 3: Build the engine in `startSync`**

In `AppGraph.kt`, add fields beside `private var mirrorEngine: MirrorEngine? = null`:

```kotlin
    private var browseEngine: BrowseEngine? = null
    private var fileBridge: ShizukuFileBridge? = null
    private var mediaIndex: MediaIndex? = null
```

Then, inside `startSync`, replace the `files` construction (currently `FileTransferEngine(scope, MediaStoreFileSink(appContext), …)`) with:

```kotlin
            val bridge = ShizukuFileBridge(appContext).also { it.bind(); fileBridge = it }
            // Shizuku's user service dies whenever Shizuku restarts, which happens on every
            // phone reboot. bind() at startup alone would leave browsing silently dead until
            // the app is relaunched, so re-bind whenever Shizuku's binder comes back.
            Shizuku.addBinderReceivedListener {
                Log.i(TAG, "shizuku binder received; rebinding file bridge")
                fileBridge?.bind()
            }
            val media = MediaIndex(appContext).also { mediaIndex = it }
            val roots = listOf(
                BrowseRoot("internal", "Internal storage", Environment.getExternalStorageDirectory().absolutePath),
                BrowseRoot("download", "Download", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath),
                BrowseRoot("documents", "Documents", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath),
                BrowseRoot("dcim", "Camera", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).absolutePath),
                BrowseRoot("pictures", "Pictures", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath),
                BrowseRoot("movies", "Movies", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).absolutePath),
                BrowseRoot("music", "Music", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).absolutePath),
            )
            val browse = BrowseEngine(
                scope = scope,
                bridge = bridge,
                roots = roots,
                enabled = { BrowsePrefs.enabled(appContext) },
                log = { Log.i(TAG, it) },
            )
            browseEngine = browse
            val files = FileTransferEngine(
                scope,
                DispatchingFileSink(MediaStoreFileSink(appContext), bridge) { browse.confineAbsolute(it) },
                log = { Log.i(TAG, it) },
            )
```

`BrowseEngine` needs the transfer engine, which needs the sink, which needs the engine. That cycle is why `transfers` is already a settable property rather than a constructor parameter (Task 3) — nothing about the class changes here. Just close the loop after `files` is built:

```kotlin
            browse.transfers = files
```

Imports to add: `android.os.Environment`, `ca.beric.clipsync.android.browse.BrowsePrefs`, `.MediaIndex`, `.ShizukuFileBridge`, `ca.beric.clipsync.android.transfer.DispatchingFileSink`, `ca.beric.clipsync.browse.BrowseEngine`, `ca.beric.clipsync.browse.BrowseRoot`.

- [ ] **Step 4: Route the events**

In `handleMirrorEvent`, add before the terminal `is MirrorEvent.NotifPosted, …` branch:

```kotlin
            is MirrorEvent.MediaQuery -> scope.launch {
                val reply = if (!BrowsePrefs.enabled(context)) {
                    MirrorEvent.FsResult("media", false, "browsing disabled")
                } else {
                    MirrorEvent.MediaItems(mediaIndex?.items(event.offset, event.limit).orEmpty())
                }
                mirrorEngine?.send(from, reply)
            }
            is MirrorEvent.ThumbQuery -> scope.launch {
                val reply = if (!BrowsePrefs.enabled(context)) {
                    MirrorEvent.FsResult("thumbs", false, "browsing disabled")
                } else {
                    MirrorEvent.Thumbs(mediaIndex?.thumbs(event.ids).orEmpty())
                }
                mirrorEngine?.send(from, reply)
            }
            is MirrorEvent.FsQueryRoots, is MirrorEvent.FsQueryList, is MirrorEvent.FsPull,
            is MirrorEvent.FsPush, is MirrorEvent.FsDelete, is MirrorEvent.FsRename -> scope.launch {
                browseEngine?.onEvent(from, event)?.let { mirrorEngine?.send(from, it) }
            }
```

and extend the final ignore branch to include the desktop-bound replies so the `when` stays exhaustive:

```kotlin
            is MirrorEvent.NotifPosted, is MirrorEvent.SmsThreads,
            is MirrorEvent.SmsMessages, is MirrorEvent.SmsSent,
            is MirrorEvent.FsRoots, is MirrorEvent.FsEntries, is MirrorEvent.MediaItems,
            is MirrorEvent.Thumbs, is MirrorEvent.FsResult,
```

- [ ] **Step 5: Verify it compiles and the shared suite still passes**

Run: `./gradlew :shared:desktopTest :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL, tests PASS.

- [ ] **Step 6: Commit**

```bash
git add androidApp/src/main/kotlin/ca/beric/clipsync/android
git commit -m "feat(android): serve browse requests behind an off-by-default toggle"
```

---

### Task 10: Android — the opt-in card

**Files:**
- Modify: `androidApp/src/main/kotlin/ca/beric/clipsync/android/MainActivity.kt` (beside the existing Shizuku / notification / SMS cards)

**Interfaces:**
- Consumes: `BrowsePrefs` (Task 9), `MediaIndex.PERMISSIONS` (Task 8).
- Produces: no new API — a UI card only.

- [ ] **Step 1: Add the card**

Following the existing setup-card composables in `MainActivity.kt`, add:

```kotlin
/**
 * M9 consent: file browsing is off until this is switched on. Turning it on also asks for the
 * read-only media permission the photo grid needs — one tap, both grants.
 */
@Composable
private fun BrowseCard(activity: ComponentActivity) {
    var on by remember { mutableStateOf(BrowsePrefs.enabled(activity)) }
    val askMedia = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    SetupCard(
        title = "Let a paired Mac browse my files",
        body = if (on) {
            "On. A paired Mac can browse, copy, rename, and trash files on this phone. " +
                "Deleted items move to .clipsync-trash and can be restored."
        } else {
            "Off. Turn this on to browse this phone's photos and files from the desktop app."
        },
        actionLabel = if (on) "Turn off" else "Turn on",
        onAction = {
            val next = !on
            BrowsePrefs.setEnabled(activity, next)
            on = next
            if (next) askMedia.launch(MediaIndex.PERMISSIONS)
        },
    )
}
```

Call `BrowseCard(activity)` in the setup-card column alongside the existing cards. If `SetupCard`'s parameter names differ in this file, match the existing call sites rather than the names above — the surrounding cards are the reference.

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add androidApp/src/main/kotlin/ca/beric/clipsync/android/MainActivity.kt
git commit -m "feat(android): opt-in card for letting a paired Mac browse files"
```

---

### Task 11: Desktop — the Files tab

**Files:**
- Modify: `desktopApp/src/main/kotlin/ca/beric/clipsync/desktop/Main.kt` (the `Boot` class, the tab row, and a new `FilesScreen`)

**Interfaces:**
- Consumes: every `MirrorEvent` from Task 1; `boot.mirror.send` (existing).
- Produces: `FilesScreen(boot: Boot)`; `Boot.fsRoots`, `Boot.fsEntries`, `Boot.mediaItems`, `Boot.thumbs`, `Boot.fsResults` state flows.

**Known single-peer assumption, deliberate:** `FilesScreen` broadcasts (`send(null, …)`) rather than targeting a peer, matching how the existing Messages tab already queries. With Eric's one phone this is identical to targeting. With two paired phones both would answer and the last reply would silently win the pane. The fix when it matters is a peer picker in the header plus threading the device id through every `send` here — same shape as the M6 "send targets all connected peers" decision, and deferred for the same reason.

- [ ] **Step 1: Hold the incoming state**

In `Main.kt`, beside `smsThreads` / `smsMessages`, add:

```kotlin
        val fsRoots = MutableStateFlow(emptyList<FsRoot>())
        val fsEntries = MutableStateFlow<MirrorEvent.FsEntries?>(null)
        val mediaItems = MutableStateFlow(emptyList<MediaItem>())
        val thumbs = MutableStateFlow(emptyMap<Long, String>())
        val fsResults = MutableSharedFlow<MirrorEvent.FsResult>(extraBufferCapacity = 16)
```

and in the `MirrorEngine` `onEvent` lambda, beside the existing `SmsThreads` branch:

```kotlin
                    is MirrorEvent.FsRoots -> fsRoots.value = event.roots
                    is MirrorEvent.FsEntries -> fsEntries.value = event
                    is MirrorEvent.MediaItems -> mediaItems.value = event.items
                    is MirrorEvent.Thumbs -> thumbs.value = thumbs.value + event.jpegB64
                    is MirrorEvent.FsResult -> {
                        println("clipsync: fs ${event.op} ok=${event.ok} ${event.detail}")
                        fsResults.tryEmit(event)
                    }
```

Add all five to the `Boot` constructor and to its construction site alongside `smsThreads, smsMessages`.

- [ ] **Step 2: Add the tab and the screen**

Add "Files" to the existing tab row (beside Activity · Notifications · Messages), and:

```kotlin
/**
 * Browse the phone (M9). Two views over one protocol: a folder tree and a photo grid.
 * Destructive actions confirm first, and the phone moves deletions to a trash folder, so a
 * mis-click costs a restore rather than a photo.
 */
@Composable
private fun FilesScreen(boot: Boot) {
    val scope = rememberCoroutineScope()
    val roots by boot.fsRoots.collectAsState()
    val listing by boot.fsEntries.collectAsState()
    val photos by boot.mediaItems.collectAsState()
    val thumbs by boot.thumbs.collectAsState()
    var root by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var grid by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<List<String>>(emptyList()) }
    var renaming by remember { mutableStateOf<String?>(null) }

    fun list(r: String, p: String) {
        root = r
        path = p
        scope.launch { boot.mirror.send(null, MirrorEvent.FsQueryList(r, p)) }
    }

    LaunchedEffect(Unit) { boot.mirror.send(null, MirrorEvent.FsQueryRoots) }
    LaunchedEffect(roots) { if (root.isEmpty() && roots.isNotEmpty()) list(roots.first().id, "") }
    LaunchedEffect(grid) {
        if (grid) boot.mirror.send(null, MirrorEvent.MediaQuery(0, 60))
    }
    LaunchedEffect(photos) {
        val missing = photos.map { it.id }.filterNot { thumbs.containsKey(it) }.take(24)
        if (missing.isNotEmpty()) boot.mirror.send(null, MirrorEvent.ThumbQuery(missing))
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            roots.forEach { r ->
                TextButton(onClick = { list(r.id, "") }) {
                    Text(r.label, fontWeight = if (r.id == root) FontWeight.Bold else FontWeight.Normal)
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { grid = !grid }) { Text(if (grid) "Files" else "Photos") }
        }
        if (roots.isEmpty()) {
            Text(
                "No storage offered yet.\nOn the phone, turn on \"Let a paired Mac browse my files\".",
                Modifier.padding(top = 24.dp),
            )
            return@Column
        }
        if (grid) {
            LazyVerticalGrid(columns = GridCells.Adaptive(120.dp), modifier = Modifier.weight(1f)) {
                items(photos, key = { it.id }) { item ->
                    Column(Modifier.padding(4.dp)) {
                        thumbs[item.id]?.let { b64 ->
                            Image(
                                bitmap = org.jetbrains.skia.Image.makeFromEncoded(
                                    java.util.Base64.getDecoder().decode(b64),
                                ).toComposeImageBitmap(),
                                contentDescription = item.name,
                                modifier = Modifier.size(112.dp),
                            )
                        } ?: Box(Modifier.size(112.dp))
                        Text(item.name, maxLines = 1, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        } else {
            if (path.isNotEmpty()) {
                TextButton(onClick = { list(root, path.substringBeforeLast('/', "")) }) { Text("← ${path.ifEmpty { "/" }}") }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(listing?.entries.orEmpty(), key = { it.name }) { entry ->
                    val child = if (path.isEmpty()) entry.name else "$path/${entry.name}"
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(if (entry.dir) "📁" else "📄", Modifier.padding(end = 6.dp))
                        Text(
                            entry.name,
                            Modifier.weight(1f).clickable {
                                if (entry.dir) list(root, child)
                                else scope.launch { boot.mirror.send(null, MirrorEvent.FsPull(root, child)) }
                            },
                        )
                        if (!entry.dir) Text("${entry.size / 1024} KB", Modifier.padding(end = 8.dp))
                        TextButton(onClick = { renaming = child }) { Text("Rename") }
                        TextButton(onClick = { confirm = listOf(child) }) { Text("Delete") }
                    }
                }
            }
        }
    }

    if (confirm.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { confirm = emptyList() },
            title = { Text("Move ${confirm.size} item(s) to the phone's trash?") },
            text = { Text(confirm.joinToString("\n").take(400) + "\n\nRecoverable from .clipsync-trash on the phone.") },
            confirmButton = {
                TextButton(onClick = {
                    val paths = confirm
                    confirm = emptyList()
                    scope.launch {
                        boot.mirror.send(null, MirrorEvent.FsDelete(root, paths))
                        boot.mirror.send(null, MirrorEvent.FsQueryList(root, path))
                    }
                }) { Text("Move to trash") }
            },
            dismissButton = { TextButton(onClick = { confirm = emptyList() }) { Text("Cancel") } },
        )
    }

    renaming?.let { target ->
        var name by remember(target) { mutableStateOf(target.substringAfterLast('/')) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    renaming = null
                    scope.launch {
                        boot.mirror.send(null, MirrorEvent.FsRename(root, target, name))
                        boot.mirror.send(null, MirrorEvent.FsQueryList(root, path))
                    }
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}
```

Add the imports Compose needs (`LazyVerticalGrid`, `GridCells`, `AlertDialog`, `Image`, `toComposeImageBitmap`, `clickable`, `FontWeight`) following the file's existing import style.

- [ ] **Step 3: Verify the desktop app builds and launches**

Run: `./gradlew :desktopApp:createDistributable`
Expected: BUILD SUCCESSFUL.

Run: `open desktopApp/build/compose/binaries/main/app/clipsync.app`
Expected: the window opens with a fourth tab, "Files", showing the not-enabled empty state.

- [ ] **Step 4: Commit**

```bash
git add desktopApp/src/main/kotlin/ca/beric/clipsync/desktop/Main.kt
git commit -m "feat(desktop): Files tab with a folder tree, photo grid, and confirmed deletes"
```

---

### Task 12: Harness hooks, docs, and the version bump

**Files:**
- Modify: `desktopApp/src/main/kotlin/ca/beric/clipsync/desktop/Main.kt:675-695` (`watchMirrorCmd`)
- Modify: `androidApp/build.gradle.kts:17-18`
- Modify: `README.md`, `HANDOFF.md`, `DEFERRED-QUESTIONS.md`
- Modify: `docs/superpowers/specs/2026-08-12-linkmymac-parity-roadmap.md` (flip the browse row)

- [ ] **Step 1: Extend the harness verbs**

In `watchMirrorCmd`'s `when (parts[0])`, add:

```kotlin
                    "fs-roots" -> MirrorEvent.FsQueryRoots
                    "fs-list" -> if (parts.size >= 2) MirrorEvent.FsQueryList(parts[1], parts.getOrElse(2) { "" }) else null
                    "fs-pull" -> if (parts.size == 3) MirrorEvent.FsPull(parts[1], parts[2]) else null
                    "fs-push" -> if (parts.size == 3) MirrorEvent.FsPush(parts[1], parts[2]) else null
                    "fs-delete" -> if (parts.size == 3) MirrorEvent.FsDelete(parts[1], listOf(parts[2])) else null
                    "fs-rename" -> if (parts.size == 4) MirrorEvent.FsRename(parts[1], parts[2], parts[3]) else null
                    "media" -> MirrorEvent.MediaQuery(0, 20)
```

- [ ] **Step 2: Bump the version**

In `androidApp/build.gradle.kts`:

```kotlin
        versionCode = 5
        versionName = "0.4.0"
```

- [ ] **Step 3: Run every gate**

Run: `./gradlew :shared:desktopTest :androidApp:assembleDebug :desktopApp:createDistributable`
Expected: all three succeed; shared suite green.

- [ ] **Step 4: On-device verification (needs the phone and Shizuku running)**

Do this in one session with the phone unlocked (clipboard reads are keyguard-gated; file ops are not, but the harness needs adb):

```bash
# Phone: turn on the browse card, grant media when asked.
adb shell am force-stop ca.beric.clipsync
adb shell am start -n ca.beric.clipsync/ca.beric.clipsync.android.MainActivity
adb logcat -c

echo "fs-roots"  > ~/.clipsync/mirror-cmd.txt   # expect: fs roots ok, 7 roots
echo "fs-list download" > ~/.clipsync/mirror-cmd.txt
echo "fs-pull download clipsync-0.3.0.apk" > ~/.clipsync/mirror-cmd.txt
```

Assert, in order:
1. Desktop log prints the roots and a listing of `Download`.
2. A pull lands in `~/Downloads/clipsync` and `shasum -a 256` matches `adb shell sha256sum /sdcard/Download/<name>`.
3. `fs-push download scratch` answers `ok=true` with an absolute path, then a desktop drag-drop into that folder lands there (`adb shell ls /sdcard/Download/scratch`).
4. `fs-delete download scratch/<file>` moves it into `/sdcard/Download/.clipsync-trash/` — verify with `adb shell ls -la /sdcard/Download/.clipsync-trash/`, and that the bytes are intact.
5. `fs-rename download scratch/a.txt b.txt` renames in place.
6. Turn the phone card **off**, re-run `fs-list download`, and assert the desktop logs `ok=false browsing disabled` and logcat shows **no** storage read.
7. `media` returns items and the Photos grid renders thumbnails.

Use a scratch directory for every destructive assertion — never a real photo.

- [ ] **Step 5: Update the docs**

- `README.md`: a "Phone file & photo browse" bullet in the how-it-works list (opt-in, trash-first delete, Shizuku-backed, no new storage permission); bump the Status paragraph to M9.
- `HANDOFF.md`: a new `| M9 phone browse |` row in the Done & verified table with the evidence from Step 4, and a session section recording anything learned the hard way on-device.
- `DEFERRED-QUESTIONS.md`: record the two decisions made inside this milestone — full write access chosen over read-only pull (Eric's call, 2026-08-13), and trash-first delete with no auto-purge.
- The parity roadmap: change the "Photos / contacts / file-manager browse" row's verdict to note files+photos shipped in M9 and contacts still deliberately out.

- [ ] **Step 6: Commit and tag**

```bash
git add -A
git commit -m "feat: M9 phone file and photo browse — 0.4.0"
git tag m9
```

Do **not** push. Pushing `main` deploys; that is Eric's call.

---

## Self-Review

**Spec coverage:** Roots/confinement → Task 3. Trash-first delete, rename → Task 4. Pull/push and the `dest` + targeting changes → Tasks 5–6. Shizuku user service → Task 7. MediaStore + thumbnails + permissions → Task 8. Consent toggle, event routing, dest-aware sink → Tasks 9–10. Desktop Files tab with confirm dialog → Task 11. Harness verbs, version, docs → Task 12. The spec's "trash size in the footer" is the one cosmetic item not given its own step; fold it into Task 11 if it's wanted, or drop it — it does not gate the milestone.

**Type consistency:** `FsEntry(name, size, dir, mtimeMs, mime)` is produced by `JvmFileBridge.toEntry`, `ShizukuFileBridge.parseRow`, and consumed by `BrowseEngine.onList` and `FilesScreen` — same field order throughout. `sendFile(source, toDeviceId, dest)` and `FileSink.begin(name, mime, dest)` keep their defaults, so every existing call site compiles untouched. `BrowseEngine.transfers` is a settable property, not a constructor parameter, to break the engine↔sink cycle (Task 9 Step 3 also patches the Task 6 test accordingly).

**Known risk carried forward:** `Shizuku.bindUserService` cannot be exercised without a device — Task 7 ships on "it compiles", and Task 12 Step 4 is where it is actually proven. If binding fails on-device, the fallback is the `newProcess` shell path already used by `ShizukuClipboard.shellReadUri`.
