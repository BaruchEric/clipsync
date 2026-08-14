package ca.beric.clipsync.browse

import ca.beric.clipsync.protocol.FsRoot
import ca.beric.clipsync.protocol.MirrorEvent
import ca.beric.clipsync.transfer.FileTransferEngine
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
            is MirrorEvent.FsDelete -> onDelete(event)
            is MirrorEvent.FsRename -> onRename(event)
            else -> null // transfers arrive in Task 6
        }
    }

    /**
     * Absolute canonical path for [rel] under [rootId], or null when it escapes the root.
     * A rooted path ("/etc/passwd") is refused outright rather than quietly re-rooted under
     * the browse root — a client that sends one has a bug, and an error names it.
     */
    fun resolve(rootId: String, rel: String): String? {
        if (!enabled()) return null
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
        if (!enabled()) return null
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
            .sortedWith(
                compareByDescending<ca.beric.clipsync.protocol.FsEntry> { it.dir }
                    .thenBy { it.name.lowercase() }
                    .thenBy { it.name },
            )
            .take(MAX_ENTRIES)
        return MirrorEvent.FsEntries(q.root, q.path, entries)
    }

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

        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
