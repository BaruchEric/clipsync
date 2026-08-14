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
