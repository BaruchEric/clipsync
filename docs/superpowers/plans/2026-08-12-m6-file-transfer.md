# M6 — File transfer (AirDrop-style, over the existing paired TLS links)

**Date:** 2026-08-12 · **Spec:** `../specs/2026-08-12-linkmymac-parity-roadmap.md`
**Goal:** send arbitrary files Mac⇄Android between already-paired devices, E2E-encrypted, over
the existing pinned-TLS WebSocket. Desktop: drag-and-drop / picker; Android: share-sheet in,
Downloads out. No new pairing, no new ports, no schema change.

## Why not reuse the image path as-is

`onLocalImageCapture` seals the *whole* payload as one AEAD blob in memory, and the receiver
buffers every chunk before concat+open (16 MiB cap). Files are GB-scale → must **stream**:
per-chunk AEAD, disk-backed receive, bounded in-flight window. The image path stays untouched.

## Protocol (additive; old peers silently drop unknown `"t"` values — sender surfaces that as a
timeout, see below)

- `FileOffer` `"file"`: `id` (32 random bytes, hex), `name`, `size`, `mime`, `sha256`
  (plaintext, hex), `chunkCount`.
- `FileAck` `"file-ack"`: `id`, `received` (chunk count). `received=0` = accept/start signal;
  sent every ACK_EVERY chunks; `received=chunkCount` = success signal.
- `FileError` `"file-err"`: `id`, `reason` — either side aborts; receiver deletes partial data.
- Chunks ride the existing binary `ChunkFrame` (`id32 | index | total | sealedChunk`), same
  frame type as images — ids can't collide (32 random bytes vs sha256), and both engines ignore
  unknown ids.

Crypto: each chunk sealed with the per-pair key, **AAD = id ‖ u32(index)** — binds a chunk to
its transfer and position (no cross-transfer splicing / reorder). Whole-file `sha256` verified
by the receiver before the file is published. Chunks are sent and required **in order** (single
ordered WS stream), so receive = append.

Flow control (transport buffering must not balloon on a GB file): CHUNK = 256 KiB, receiver
acks every 8 chunks, sender keeps ≤ 16 chunks (4 MiB) unacked. Timeouts: 15 s offer→first ack
("peer app has no file support / is away"), 60 s progress stall → abort + cleanup.

Bounds: size ≤ 4 GiB, ≤ 4 concurrent inbound transfers, name sanitized (basename only,
`/\:*?"<>|` and leading dots stripped, ≤ 200 chars) — a malicious paired peer must not be able
to path-traverse. Collisions get " (n)".

## Code shape

- `shared/jvmShared/.../transfer/FileTransferEngine.kt` (both apps are JVM — java.io +
  MessageDigest are fine here, same as the transport):
  - `sendFile(source: FileSource)` → offer + windowed sealed chunks to **all connected peers**
    (peer picker is a later nicety; the video's setup is 1 phone + 1 Mac).
  - `onRemoteMessage` / `onBinaryFrame` mirror SyncEngine's surface.
  - `FileSource(name,size,mime,open(): InputStream)` — desktop: File; Android: ContentResolver.
  - `FileSink` (platform): desktop temp-file → rename into `~/Downloads/clipsync/`; Android
    MediaStore Downloads (`Download/clipsync`, IS_PENDING → publish), no storage permission
    on minSdk 29.
  - `transfers: StateFlow<List<TransferState>>` for both UIs (active/done/failed, bytes moved).
- `ConnectionManager`: register peers with both engines; route file control messages + binary
  frames to the file engine too.
- Desktop `Main.kt`: AWT `DropTarget` on the window (`javaFileListFlavor`) + "Send a file…"
  (`java.awt.FileDialog`); transfers section in the window; receive dir `~/Downloads/clipsync`.
- Android: manifest `ACTION_SEND`/`ACTION_SEND_MULTIPLE` (`*/*`) on MainActivity → stream
  straight from the granted content URI; receive → MediaStore + a "Received <name>" notification
  (existing channel infra).

## Tests (desktopTest, no devices)

1. Codec round-trip for the three new messages; unknown-type decode still returns null.
2. Loopback over real pinned TLS: 1 MiB file (multi-chunk, exercises window+acks) arrives
   byte-identical, sha verified, states reach Done both sides.
3. Tampered chunk → AEAD fails → FileError, partial file cleaned up, nothing published.
4. Offer to a peer that never acks → sender fails at the (injectable) timeout.
5. Name sanitization: `../../etc/passwd`, absolute paths, weird chars.

## Acceptance

- All gates green: `:shared:desktopTest`, `:desktopApp:createDistributable`,
  `:androidApp:assembleDebug`.
- On-device (S24): Mac→phone file lands in Downloads/clipsync with notification; phone
  share-sheet→Mac lands in ~/Downloads/clipsync. (Blocked on adb reachability at write time —
  phone must be reconnected; harness step documented in HANDOFF.)

## Non-goals (M6)

Folder/multi-GB batching UX, resume of interrupted transfers, peer picker, receive-side
accept prompt (auto-accept from *paired* peers is the LocalSend-adjacent call for v1 — pairing
is the consent boundary; revisit if it feels too permissive), Android→Android UI affordance
(engine supports it; no share target on desktop needed).
