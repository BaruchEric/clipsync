package ca.beric.clipsync.browse

import ca.beric.clipsync.protocol.FsRoot
import ca.beric.clipsync.protocol.MirrorEvent
import ca.beric.clipsync.transfer.FileSource
import ca.beric.clipsync.transfer.FileTransferEngine
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
        if (!enabled()) return MirrorEvent.FsResult(op, false, "browsing disabled", reqIdOf(event))
        return when (event) {
            is MirrorEvent.FsQueryRoots -> MirrorEvent.FsRoots(roots.map { FsRoot(it.id, it.label) })
            is MirrorEvent.FsQueryList -> onList(event)
            is MirrorEvent.FsDelete -> onDelete(event)
            is MirrorEvent.FsRename -> onRename(event)
            is MirrorEvent.FsPull -> onPull(fromDeviceId, event)
            is MirrorEvent.FsPush -> onPush(event)
            else -> null // media and thumbnails are answered by the platform layer
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

    /**
     * True when [abs] IS a declared root's own directory — not merely somewhere under it.
     * Roots nest by design (`internal` is /storage/emulated/0; the other six sit inside it),
     * so a request whose *requested* root is a parent can still resolve to a *different*
     * root's mount point — `FsDelete(root = "internal", paths = ["DCIM"])` resolves to the
     * `camera` root's own directory. Checking only the requested root's path leaves every
     * nested root reachable, and swallowable, through its parent.
     *
     * ANCESTORS COUNT TOO. [abs] is refused when it *is* a root and when it merely *contains*
     * one. Equality alone would have encoded an unwritten constraint on [roots] — that no root
     * may sit more than one level below another — which holds for today's seven (six direct
     * children of `internal`) and would silently stop holding the day someone adds, say,
     * `internal/DCIM/Camera`: deleting `DCIM` would swallow a declared root that equality never
     * looks at. The prefix clause removes the constraint instead of documenting it (M9 review
     * residual R3, deferred out of M9 so the security core could run on a device first — it has,
     * 29/0 in the M9 session and clean again in M9.1).
     *
     * Behavior-preserving for the current root set: a path is newly refused only if it is a
     * *strict* ancestor of some root, and the only such path reachable through [resolve] is
     * `internal` itself, which equality already refused. Proven by
     * `refusesAncestorOfNestedRoot` / `nestingFixChangesNothingForTodaysRoots` in
     * `BrowseEngineTest`.
     */
    private fun isDeclaredRootPath(abs: String): Boolean = roots.any {
        val rc = bridge.canonical(it.path) ?: return@any false
        rc == abs || rc.startsWith("$abs/")
    }

    private fun onList(q: MirrorEvent.FsQueryList): MirrorEvent {
        val abs = resolve(q.root, q.path) ?: return MirrorEvent.FsResult("list", false, "path rejected")
        val all = bridge.list(abs)
            .filterNot { it.dir && it.name == TRASH_DIR }
            .sortedWith(
                compareByDescending<ca.beric.clipsync.protocol.FsEntry> { it.dir }
                    .thenBy { it.name.lowercase() }
                    .thenBy { it.name },
            )
        val entries = all.take(MAX_ENTRIES)
        // Sorted before the cap, so what survives is the alphabetical head, and flagged, so
        // the peer can say "there is more" instead of silently rendering a complete-looking
        // 2000 (the M9.1 release-note constraint this field exists to retire).
        return MirrorEvent.FsEntries(q.root, q.path, entries, truncated = all.size > entries.size)
    }

    /**
     * Trash-first delete: each entry is *moved* into <root>/.clipsync-trash, so deleting a
     * directory costs one rename and stays fully reversible. A move that fails changes
     * nothing — we never fall back to copy-then-unlink, which could half-delete a file.
     */
    private fun onDelete(req: MirrorEvent.FsDelete): MirrorEvent {
        val rootPath = resolve(req.root, "")
            ?: return MirrorEvent.FsResult("delete", false, "path rejected", req.reqId)
        // Confine the trash directory itself, exactly as onRename confines its target. A
        // symlinked .clipsync-trash would otherwise be followed by mkdirs/renameTo and land
        // deleted files outside the root — the destination has to be checked, not assumed.
        val trash = confineAbsolute("$rootPath/$TRASH_DIR")
            ?: return MirrorEvent.FsResult("delete", false, "trash rejected", req.reqId)
        val stamp = STAMP.format(Instant.ofEpochMilli(clock()).atZone(ZoneId.systemDefault()))
        var moved = 0
        for (rel in req.paths) {
            val abs = resolve(req.root, rel)
                ?: return MirrorEvent.FsResult("delete", false, "path rejected", req.reqId)
            // isDeclaredRootPath subsumes the abs == rootPath case (the requested root is
            // itself a declared root) as well as every nested root reachable from it.
            if (abs == trash || abs.startsWith("$trash/") || isDeclaredRootPath(abs)) {
                return MirrorEvent.FsResult("delete", false, "path rejected", req.reqId)
            }
            if (!bridge.mkdirs(trash)) {
                return MirrorEvent.FsResult("delete", false, "could not open trash", req.reqId)
            }
            val name = abs.substringAfterLast('/')
            var target = "$trash/$stamp-$name"
            var n = 1
            while (bridge.exists(target)) target = "$trash/$stamp-$n-$name".also { n++ }
            if (!bridge.move(abs, target)) {
                // Name what already moved: a batch that fails partway has genuinely trashed
                // the earlier entries, and a bare ok=false would leave the peer unable to tell.
                return MirrorEvent.FsResult(
                    "delete", false,
                    "moved $moved of ${req.paths.size}; failed on $name", req.reqId,
                )
            }
            moved++
        }
        log("browse delete: $moved to trash")
        return MirrorEvent.FsResult("delete", true, "$moved", req.reqId)
    }

    /** Same-directory rename only. Changing directories is a move, and moves are not in v1. */
    private fun onRename(req: MirrorEvent.FsRename): MirrorEvent {
        val name = req.newName.trim()
        fun refuse(detail: String) = MirrorEvent.FsResult("rename", false, detail, req.reqId)
        if (name.isEmpty() || name == "." || name == ".." || '/' in name || '\\' in name) {
            return refuse("invalid name")
        }
        val abs = resolve(req.root, req.path) ?: return refuse("path rejected")
        // Refuses renaming any declared root's own directory, not just the requested one — the
        // same nesting hazard as onDelete above.
        if (isDeclaredRootPath(abs)) return refuse("path rejected")
        val target = "${abs.substringBeforeLast('/')}/$name"
        if (confineAbsolute(target) == null) return refuse("path rejected")
        if (bridge.exists(target)) return refuse("name already taken")
        if (!bridge.move(abs, target)) return refuse("rename failed")
        log("browse rename: ${abs.substringAfterLast('/')} -> $name")
        return MirrorEvent.FsResult("rename", true, name, req.reqId)
    }

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
        val abs = resolve(req.root, req.dir)
            ?: return MirrorEvent.FsResult("push", false, "path rejected", req.reqId)
        if (!bridge.mkdirs(abs)) return MirrorEvent.FsResult("push", false, "could not create $abs", req.reqId)
        return MirrorEvent.FsResult("push", true, abs, req.reqId)
    }

    /** The request id an FsResult must echo; "" for the ops whose callers don't correlate. */
    private fun reqIdOf(event: MirrorEvent): String = when (event) {
        is MirrorEvent.FsDelete -> event.reqId
        is MirrorEvent.FsRename -> event.reqId
        is MirrorEvent.FsPush -> event.reqId
        else -> ""
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
