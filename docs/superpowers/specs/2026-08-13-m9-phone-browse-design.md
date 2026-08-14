# M9 — phone file & photo browse from the desktop

**Date:** 2026-08-13 · **Status:** design, approved scope pending Eric's spec review

Closes the last parity row on the LinkMyMac roadmap that isn't a product of its own:
"Photos / contacts / file-manager browse", which that roadmap deferred with *"revisit after
M6/M7"*. M6/M7/M8 are done, so this is next.

Contacts are **not** in scope — resolving names needs `READ_CONTACTS`, which clipsync
deliberately refuses (see M8). This milestone is files and photos only.

## Scope, as decided

| Question | Decision |
|---|---|
| Browse surface | Photo grid **and** file tree |
| Consent | Opt-in toggle on the phone, **off by default** |
| Write access | **Full file manager**: pull, push, delete, rename |
| Android mechanism | Shizuku SHELL-uid file service; MediaStore read-only for thumbnails |
| Phasing | One pass |

Direction is one-way on purpose: the desktop browses the phone. Browsing the Mac from the
phone is not a parity gap (the phone already receives pushed files) and doubles the surface
for no demonstrated want. Revisit only if asked.

## Architecture

Three pieces, one of which is new logic and two of which are adapters:

```
desktop Files tab  ──mirror events──►  BrowseEngine  ──FileBridge──►  Shizuku file service
   (Compose)                          (jvmShared)         │            (SHELL uid, AIDL)
        ▲                                                 └──────────►  MediaStore (read-only)
        └──────────── FileTransferEngine (M6, reused for bytes) ◄──────────────┘
```

- **`BrowseEngine` (jvmShared, new).** All of the actual logic: root resolution, path
  confinement, listing, delete-to-trash, rename, and turning a pull request into a
  `FileTransferEngine.sendFile`. It talks to storage only through a `FileBridge` interface,
  so it is fully testable on the desktop against a temp directory — the Android-specific
  half stays thin enough to verify by hand on-device.
- **`FileBridge` (interface).** `list`, `stat`, `open`, `create`, `move`, `delete`, `mkdirs`.
  Desktop/test implementation is plain `java.io.File`. Android implementation forwards to the
  Shizuku user service. **`open` must be re-invokable** — `FileTransferEngine` reads a source
  twice (hash pass, then stream pass), so each call returns a *fresh* descriptor. That is
  cheap: the shell process does the permission check at `open`, hands back a
  `ParcelFileDescriptor`, and our subsequent reads are ordinary syscalls against the fd, not
  Binder traffic. A pull therefore costs two Binder round trips and zero byte copies.
- **Transport reuse.** Metadata rides the existing sealed `mirror` envelope; file bytes ride
  the existing M6 transfer engine. No new envelope, no `ConnectionManager` change.

### Why the Shizuku service

Scoped storage means an ordinary app cannot list or mutate `/sdcard`. The three ways out are
`MANAGE_EXTERNAL_STORAGE` (a permanent, alarming, app-wide grant), SAF document trees (slow
per-child listing plus per-folder setup on the phone), or the SHELL uid. clipsync already
*requires* Shizuku for clipboard capture — the app is non-functional without it — so routing
file access through a Shizuku user service adds **zero new storage permission** and fails
closed exactly where the app already fails closed.

Implementation: `Shizuku.bindUserService` loads an AIDL service from our own APK into a
shell-uid process; inside it, plain `java.io.File` and `ParcelFileDescriptor` work with
shell's storage visibility. Verified 2026-08-13 against the resolved artifact:
`bindUserService`, `unbindUserService`, and `Shizuku$UserServiceArgs` all live in
`dev.rikka.shizuku:api` 13.1.5, which the app already depends on and which pulls
`dev.rikka.shizuku:aidl` and `:shared` transitively — **no new dependency**.

MediaStore is used **read-only**, for the photo grid's metadata and for
`ContentResolver.loadThumbnail` (API 29+, cache-backed, far cheaper than decoding a 12 MP
JPEG per tile). That costs one runtime permission card: `READ_MEDIA_IMAGES` on API 33+,
`READ_EXTERNAL_STORAGE` (`maxSdkVersion=32`) below. Images only — nothing queries video,
and requesting an unused permission would undercut the same restraint that keeps
`READ_CONTACTS` off this app's surface. Every **mutation** — delete,
rename, and any receive that names a destination directory — goes through the Shizuku
service, never MediaStore. One confined write path to audit, and it sidesteps
`createDeleteRequest`'s system consent dialogs. Plain M6 receives (share-sheet sends, no
`dest`) keep using `MediaStoreFileSink` into `Download/clipsync` unchanged.

## Protocol

New `MirrorEvent` subtypes in the existing sealed hierarchy. Unknown subtypes already decode
to null and drop, so a 0.3.x peer degrades to "no Files tab", never to broken sync.

| Request | Response | Notes |
|---|---|---|
| `FsRoots?` | `FsRoots(list)` | `Root(id, label)` — Internal storage, Download, Documents, DCIM, Pictures, Movies, Music |
| `FsList?(root, path)` | `FsEntries(root, path, entries)` | `FsEntry(name, size, dir, mtimeMs, mime)`, ≤2000 per dir |
| `MediaList?(offset, limit)` | `MediaItems(items)` | `MediaItem(id, name, size, dateMs, mime, w, h)`, newest first |
| `ThumbList?(ids)` | `Thumbs(map)` | MediaStore ids only (the grid); ≤24 per request; 256 px JPEGs, base64 inline, sealed like everything else |
| `FsPull?(root, path)` | a file transfer, or `FsResult` | phone answers by sending bytes over M6 |
| `FsPush?(root, dir)` | `FsResult` | desktop then sends with `FileOffer.dest` set |
| `FsDelete?(root, paths)` | `FsResult(op, ok, detail)` | trash-first, see below |
| `FsRename?(root, path, newName)` | `FsResult(op, ok, detail)` | same-directory rename only |

Two additive changes to existing code:

1. **`FileOffer` gains an optional `dest` field** (default empty). A receiver that understands
   it writes into that directory; an older receiver ignores it and the file lands in the
   default `Download/clipsync`. Additive on the wire — but *not* free in the sink API, which
   is the one place this milestone changes an existing interface: `FileSink.begin(name, mime)`
   becomes `begin(name, mime, dest = "")`. On the phone, a `dest` that is set routes the
   receive through `FileBridge` (confined and validated like every other write) instead of
   `MediaStoreFileSink`; `dest` empty keeps today's M6 behavior exactly. The desktop ignores
   `dest` entirely — pulls always land in `~/Downloads/clipsync`, so a phone can never steer
   where bytes are written on the Mac.
2. **`FileTransferEngine.sendFile` gains a target peer.** It currently fans out to every
   connected peer, which is wrong for a pull — the bytes belong only to the requester.
   `sendFile(source, toDeviceId: String? = null)`, null preserving today's broadcast.

## Security and confinement

Roots nest on purpose (Download sits inside Internal storage), so the same file is reachable
by two root/path pairs. Confinement is evaluated against the root named in *that* request;
no request may reference two roots.

Every path in every request is relative to a declared root. The engine resolves
`root.canonical + "/" + rel`, canonicalizes the result, and rejects anything that does not
still sit under the root's canonical path — which kills `..` traversal, absolute paths, and
symlink escapes in one check. This is the single most important test in the milestone.

The browse toggle is a persisted flag, default off. While it is off, every `Fs*`/`Media*`
event answers `FsResult(ok=false, "browsing disabled")` — the phone does not read storage at
all. Turning it off mid-session does not abort an in-flight transfer; it stops new requests.

**Delete is a move, not an unlink.** Deleted entries — files and whole directories alike —
are moved to `<root>/.clipsync-trash/<yyyyMMdd-HHmmss>-<name>`. A recursive directory delete
therefore costs one rename and stays fully reversible. Trash is never auto-purged in v1; the
desktop shows its size in the Files tab footer so it can't grow unnoticed. `.clipsync-trash`
is omitted from `FsEntries` listings, so it never reads as an ordinary folder to browse into.
If the rename
fails (a cross-filesystem move, e.g. SD card), the operation **fails and changes nothing** —
it does not fall back to copy-then-unlink.

The desktop confirms destructive actions in a dialog naming the exact count and the first few
names. Rename is same-directory only: a rename that changes directories is a move, and moves
are not in v1.

Threat model, stated plainly: a paired peer with browse enabled can read, write, and
trash the phone's shared storage. That is a genuine escalation over M6's "receive a pushed
file". The mitigations are the default-off toggle, root confinement, and reversible deletes;
the residual risk is accepted deliberately, as with M6's auto-accept.

## Android

- **`FileBridgeService`** — AIDL, bound via `Shizuku.bindUserService`, running as SHELL.
  Methods mirror `FileBridge`; bytes move as `ParcelFileDescriptor` so nothing is buffered
  whole. Rebinds on Shizuku restart; every call fails cleanly when the binder is dead.
- **`MediaIndex`** — MediaStore queries for the photo grid and `loadThumbnail` for tiles.
  Read-only. Absent permission answers empty, it never throws.
- **After every mutation**, `MediaScannerConnection.scanFile` on the affected paths so the
  phone's own gallery reflects the change.
- **UI** — one more opt-in card in the existing stack (Shizuku / notifications / SMS):
  "Let a paired Mac browse my files", off until tapped, with the media permission request
  folded into the same tap.

## Desktop

A **Files** tab beside Activity · Notifications · Messages:

- Root dropdown + breadcrumb; a list/grid toggle (grid = the photo view, backed by
  `MediaList?`/`ThumbList?`).
- Double-click or **Download** pulls into `~/Downloads/clipsync` through the existing
  transfer rows, so progress display is free.
- Dropping files onto the pane pushes them into the current directory.
- Right-click → Delete (confirm dialog) / Rename (inline field).
- Empty states that name the actual cause: not paired, peer offline, browsing not enabled on
  the phone, permission not granted. The phone distinguishes the last two for you — a refused
  request answers `FsResult(ok = false)` whose `detail` is "browsing disabled" or "photo
  permission not granted" — so the tab displays that detail rather than inferring a reason
  from an empty list, which cannot tell "denied" apart from "you have no photos".

## Testing

- **`BrowseEngine` unit tests (desktop, real logic):** confinement against `..`, absolute
  paths, and a symlink pointing outside the root; listing shape and cap; delete-to-trash
  including a directory; trash name collision; rename rejecting a path separator; a failed
  cross-filesystem move leaving the source intact; every op answering `ok=false` while the
  toggle is off.
- **Protocol round trips** for each new event, plus the "unknown subtype drops" guarantee
  against a 0.3.x decoder.
- **Transfer targeting:** a pull with two peers connected delivers to the requester only.
- **`FileOffer.dest`:** honored by a new receiver, ignored safely by an old one, and ignored
  unconditionally by the desktop — a peer-supplied `dest` must never steer a write on the Mac,
  including one containing `..`.
- **On-device harness** (`~/.clipsync/mirror-cmd.txt`, the M7/M8 idiom): `fs-roots`,
  `fs-list <root> <path>`, `fs-pull <root> <path>`, `fs-push <root> <dir> <localfile>`,
  `fs-delete`, `fs-rename`, each asserting from both apps' logs. Destructive assertions run
  only against a scratch directory the harness creates.

## Out of scope

Contacts and name resolution; browsing the Mac from the phone; moves across directories;
trash auto-purge; thumbnail caching across sessions; app-private storage
(`/data/data/...`) even though the shell uid could reach it; video playback or streaming
preview; multi-select drag *out* to Finder.

## Version

0.4.0 / versionCode 5 when the milestone lands verified.
