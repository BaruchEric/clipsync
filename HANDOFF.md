# clipsync — Build Handoff (2026-08-12)

State after Phase 0 → M6 file transfer. Everything below is green; the transport is wired into both apps and demonstrated end to end.

## Done & verified

| Milestone | Status | Evidence |
|---|---|---|
| Phase 0 fork due-diligence | ✅ GREENFIELD | `FORK-ASSESSMENT.md` |
| M1 scaffold + macOS watcher | ✅ tag `m1` | copy on Mac → SQLDelight history (automated probe) |
| M2 Android background capture | ✅ tag `m2` | **Shizuku** path; background copy from another app → history, survives Doze, on Android 16 emulator |
| M3 crypto + pairing + identity | 🟢 **camera-scan gate met on real hardware** (untagged — tag `m3` when Eric confirms) | XChaCha20-Poly1305 vector, X25519+SAS, real Keychain round-trip; **live QR camera pairing Mac↔SM-S921U, SAS 773702 matching on both screens**. See "On-device pairing run" below. |
| M4 LAN sync (transport + engine) | 🟢 **live sync working** (untagged) | Loopback TLS tests + **live Mac↔Android emulator sync, both directions, 447 ms, pinned TLS, E2E-encrypted**. mDNS: desktop half verified live, Android half unverifiable on emulator. |
| M5 hardening | 🟢 built (untagged) | Persisted TLS identity, Android serves (symmetric), backoff dialer, status UI, CI. See DEFERRED-QUESTIONS "M5 hardening — DONE". |
| M6 file transfer | ✅ **VERIFIED on real hardware, both directions** (untagged — tag `m6` when Eric confirms) | Streamed E2E-encrypted files over the paired TLS link, Mac↔SM-S921U on the LAN, sha256-identical each way, incl. the cold-start share path. See "M6 real-S24 run" below. |

**61 shared test cases** (`./gradlew :shared:desktopTest`), 1 skipped (opt-in mDNS smoke test), 0 failures. All three modules build; `:androidApp:assembleDebug` produces an installable APK; `:desktopApp:createDistributable` produces a launchable macOS app image.

## M6 file transfer (2026-08-12) — built + emulator-verified

Triggered by Eric: "implement" the LinkMyMac/LinkMyDroid pair (the $22.99 Android⇄Mac app from
Samuel Nam's video). Parity analysis + roadmap: `docs/superpowers/specs/2026-08-12-linkmymac-parity-roadmap.md`;
design: `docs/superpowers/plans/2026-08-12-m6-file-transfer.md`. Files was the clear next
milestone (the video's "AirDrop — done" beat); notifications (M7) and messages (M8) are
spec'd as candidates but **not** built — each needs a permission-surface sign-off first.

What exists now:

- **Shared engine** (`transfer/FileTransferEngine`, jvmShared): streamed transfers over the
  existing links — 256 KiB chunks sealed per-chunk (AAD = transfer id ‖ index), disk-backed
  receive through a `FileSink` seam, whole-file sha256 verified before publish, windowed acks
  (≤4 MiB in flight), 15 s offer timeout / 60 s stall watchdog, ≤4 GiB, name sanitization
  (a malicious paired peer cannot path-traverse). New control messages FileOffer/FileAck/
  FileError are additive — an old peer silently drops them and the sender times out with a
  "peer up to date?" failure, no crash.
- **Desktop**: drop files on the window or "Send a file…" (native dialog); receives to
  `~/Downloads/clipsync`; live transfer rows in the window. Also: Parallels `vnic*`/`bridge*`
  interfaces are now **excluded** from advertised addresses and tailnet CGNAT addresses sort
  last (closes the dead-endpoint reconnect latency flagged in the 2026-08-08 run — the live
  payload now advertises `["192.168.1.32","100.72.29.68"]`, no Parallels 10.x).
- **Android**: share-sheet target (`ACTION_SEND`/`SEND_MULTIPLE`, any mime) streams straight
  from the content Uri (exact size required; unknown-length streams skipped with a log);
  receives into MediaStore `Download/clipsync` (IS_PENDING until integrity passes) + a
  tap-to-open notification; transfer rows on the main screen. **Sends wait up to 10 s for a
  peer link** — a share usually cold-starts the process, and the unwaited send lost the race
  against the dialer every time (caught live in the emulator run below).
- **Harness hooks** (peer-payload.txt idiom): desktop polls `~/.clipsync/send-file.txt` (write
  an absolute path → sends it, logs `send-file start path=… size=…`); Android accepts
  `--es send_file_path <app-readable path>` (logs `clipsyncShare: send-from-intent … ok=…`).

### M6 emulator run (2026-08-12) — VERIFIED

Desktop app ran against an **isolated home** (`JAVA_TOOL_OPTIONS=-Duser.home=<tmp>`), so
Eric's real DB/identity/pairings were untouched; emulator `clipsync-a16`, app data cleared.
Paired via payload exchange (Mac ← logcat payload via peer-payload.txt, via=file; Android ←
intent extra with addresses rewritten `["10.0.2.2"]`, via=local; **SAS 364696 in both logs**).
Then, over the real pinned-TLS link:

- **Mac→Android**: 700,000-byte random file via the send-file.txt hook → landed as
  `/sdcard/Download/clipsync/testfile-mac-to-android.bin`, **sha256 identical**
  (`8008085c…2ee661`), `clipsync-files` notification channel live.
- **Android→Mac**: 650,000-byte random file via the send_file_path hook → landed in
  `~/Downloads/clipsync/up.bin` (isolated home), **sha256 identical** (`bbf0213a…33ede4`),
  ~4 s after a cold start (includes the wait-for-peer fix doing its job).

Notes: the first Android→Mac attempt failed `ok=false` — that failure is what produced the
wait-for-peer fix, then passed on retry. Also learned: `am start` extras are **dropped** when
the same activity is already top (`Intent.filterEquals` ignores extras) — harness runs must
`am force-stop` first, exactly as `pairing-test.sh run` already does.

### M6 real-S24 run (2026-08-12, later that day) — VERIFIED

Eric enabled wireless debugging; `adb-wifi.sh connect` found the phone at a fresh port
(192.168.1.45:45129, key trust intact). New APK installed over the old one (pairing
survived — the 2026-08-08 peer rows carried straight through), real desktop app relaunched
on the new build (identity `614186691d70d0e1`, same as the original pairing). All over the
real LAN, real pinned TLS, link auto-established (mDNS vs. stored-endpoint dial not
instrumented — attribution still open):

- **Mac→S24**: 1.5 MB via the send-file.txt hook → `/sdcard/Download/clipsync/`, ~4 s,
  **sha256 identical** (`99bc79b7…87301a`).
- **S24→Mac** (link up): 1.2 MB via the send_file_path hook → `~/Downloads/clipsync/`, ~2 s,
  **sha256 identical** (`8ab66684…43f946`).
- **S24→Mac, cold-start worst case** (force-stop → start-with-send): first attempt FAILED —
  the phone's stored peer row still lists the Mac's two dead Parallels endpoints *first*
  (from the 2026-08-08 pairing; the desktop filter only fixes newly generated payloads), and
  each burned OkHttp's default 10 s connect timeout, blowing the 10 s peer wait. Fixed with a
  **3 s dial connectTimeout** (Transport) + **20 s share wait** (AppGraph); re-ran the same
  worst case: **arrived ~8 s after cold start**, sha256 identical. Root cause + the real fix
  (refresh stored endpoints on contact) logged in DEFERRED-QUESTIONS.

Harness gotcha, twice-earned: `am start` **drops extras** when the same activity is already
top (`Intent.filterEquals` ignores extras) — either `am force-stop` first or vary the data
URI (`-d clipsync://send/N`) to force delivery.

Also done in that session: **Shizuku server started on the S24 over adb** (the app's
`start.sh` isn't visible to shell under scoped storage on Android 16 — exec the starter lib
directly: `pm path moe.shizuku.privileged.api` → `…/lib/arm64/libshizuku.so`; survives until
reboot).

### Live clipboard on real hardware (2026-08-12 16:12) — VERIFIED

Eric granted Shizuku (16:12:32) and re-scanned the Mac's QR. Both history DBs (metadata
checked from both sides) tell the same story:

- **Phone→Mac**: the phone's first Shizuku clipboard read captured a `local` text row at
  16:12:32; the Mac's DB has the same clip at 16:12:32 attributed to `633db2f59f4d9afa`
  (the S24) — Shizuku read → capture → seal → TLS → applied on the Mac.
- **Mac→phone**: Eric's 16:13:12 Mac copy appears on the phone attributed to
  `614186691d70d0e1`. Mac-copied **images** are recorded on the phone (16:03, 16:06) but not
  applied — Android image apply is the known open item, working as documented.
- **Re-scan refreshed the phone's stored endpoints** to the clean list
  (`[192.168.1.32:47653, 100.72.29.68:47653]`, Parallels gone), SAS 773702 matching the
  original pairing (key continuity). Observed edge, harmless here: with the link already up,
  the armed reciprocal `PairRequest` isn't sent until the *next* new link — so a re-scan
  refreshes the *scanner's* stored endpoints immediately but the peer's only at reconnect
  (the documented `pendingReciprocalPair` single-flag design).

Still open on M2, deliberately: the strict gate is a copy from another app with clipsync
**backgrounded** (and surviving Doze). Normal phone use will prove it in the field — if
copies keep appearing on the Mac today, it's closed.

### Follow-up hardening, same day (all live-verified on the S24)

- **Self-healing endpoints**: every Hello now carries the sender's current dial endpoints;
  a receiver holding the peer's key persists them (`PeerStore.updateAddresses`). Live: Mac
  tracks the phone at `192.168.1.45:47653`; phone holds the clean Mac list. Cold-start
  share is down to **~4 s** (was ~8 s, was ∞ before the dial-timeout fix).
- **Reciprocal pairing is per-device-id** (was one global flag): the payload goes out when
  the scanned peer's Hello arrives, or **immediately over an existing link** — a re-scan
  refreshes the peer without waiting for a reconnect. Closes the documented multi-peer
  half-pair edge.
- **mDNS on real Wi-Fi — VERIFIED both directions**, after fixing a real bug the new
  attribution log exposed: the phone "discovered" the Mac at **127.0.0.1** and dialed
  itself. Cause: JmDNS was created with `InetAddress.getLocalHost()`, which is loopback on
  stock macOS — it both advertised 127.0.0.1 and couldn't see LAN multicast. Now bound to
  the first real LAN IPv4. Evidence: Mac log `mDNS discovered <S24> at 192.168.1.45:47653;
  dialing`; `dns-sd -Z _clipsync._tcp` shows both adverts with real SRV targets
  (`192-168-1-32.local.`, `Android_1MX8PLN1.local.`). A pre-fix loopback record can linger
  in Android's mDNS cache until TTL; harmless.
- **CI verified green** on its first real run (the workflow had never executed).
  `versionName 0.2.0`, suite at 66 tests.

### LTE session (2026-08-12 evening) — blocked on phone Tailscale, and a gap found + fixed

Eric switched the S24 to LTE and copied a photo. Nothing arrived, for two stacked reasons:

1. **The phone's Tailscale is offline ("last seen 3 days ago")** — on LTE there is no path
   to the Mac at all until Eric opens Tailscale on the phone and connects (an expired node
   key needs a fresh sign-in, not a toggle). The m5 LTE gate stays open pending that.
2. **Replay gap (now fixed):** even with a path, the copy predated the link — and clipsync
   had no replay: capture broadcasts only to currently-connected peers, so an offline copy
   was marooned forever. The engine now keeps the newest local capture in memory and
   **replays it to each peer that registers**; both sides replay, receiver LWW keeps the
   newest (SyncEngineReplayTest, 3 cases). The Mac runs the replay build; **the phone still
   needs this APK** — install on next Wi-Fi/USB contact. Until then, a copy made while
   linked syncs as always; the marooned photo can just be re-copied once the tailnet is up.

## On-device pairing run (2026-08-08) — VERIFIED

Harness: `scripts/pairing-test.sh` (`preflight | reset | run | verify | evidence | sync | logs | stop`).
Run against a physical **SM-S921U, Android 16** on the LAN, paired with the Mac desktop app.

What the run established, all green:

- Camera scan accepted the payload (`clipsyncScan: scan-pair ok=true`). — *asserted by `verify`*
- Both sides derived the same key: **SAS 773702**, logged on both *and* shown on both screens
  ("SM-S921U: 773702" on the Mac, "Mac: 773702" on the phone). — *asserted by `verify`*
- Reciprocal pairing over the wire worked — the camera-less desktop got the phone's key. — *asserted by
  `verify`, which now requires the desktop's `via=wire` log line specifically; the byte-identical
  `via=file` line from the `peer-payload.txt` poller no longer satisfies it.*
- Peer row present in both DBs; TLS link established. — *asserted by `verify`*
- Bonus: Mac→phone text sync arrived intact on the real phone. — *`sync`; it now also asserts the
  applier's own `applyText … ok=true`, because the history row is written before the clipboard write
  and so passes even when the write fails.*
- QR **decodes to byte-identical** content to the app's own payload (248 B) — checked by decoding a
  screenshot of the window, so a scan failure could never be blamed on the QR. **This one was a manual
  step during the run, not something `verify` re-checks** — there is no QR decode in the harness.

**Re-certifying needs a fresh `run`.** `verify` now demands the desktop's `via=wire` pairing line, and
the stored logs from this run predate that marker — replaying `verify` against them reports "desktop
never paired over the wire". That is the assertion getting stricter, not evidence the run was fake
(`peer-payload.txt` was absent throughout). The originals are kept at
`build/pairing-test/archive-2026-08-08-m3/`, since `run` truncates both logs unconditionally.

Also proven here for the first time (the emulator could not): **`serving=true` on real Android hardware**
— Netty binds on-device, so the phone advertises real addresses and P2P is genuinely symmetric.

Two honest caveats:

- The phone reached the Mac over the **tailnet** (`100.82.0.66 → 100.72.29.68`), not the LAN. It dials
  the payload's address list in order, and the Mac advertises Parallels virtual interfaces
  (`10.37.129.2`, `10.211.55.2`) ahead of the real LAN address. Worth filtering those out in
  `localAddresses()`: this is not cosmetic — `PeerDialer` backs off 2s→60s per peer, so two dead
  endpoints ahead of the live one add real latency to every *reconnect*, not just the first connect.
  They also masked the LAN path in this run.
- Shizuku is installed on the phone but **not started**, so clipboard *capture* on the phone
  (and therefore the phone→Mac direction) is still unverified on real hardware.

To reproduce: `./scripts/pairing-test.sh preflight` → `reset` → `run` → scan → `verify`.

## Live sim — how to reproduce
1. Fresh DBs (schema changed since M2): `rm -f ~/Library/Application\ Support/clipsync/history.db*` and `adb shell run-as ca.beric.clipsync rm -f databases/clipsync.db databases/clipsync.db-journal`.
2. Desktop: launch `desktopApp/build/compose/binaries/main/app/clipsync.app/Contents/MacOS/clipsync`. It writes `~/.clipsync/my-payload.txt` and serves `:47653`.
3. Android: `adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk`; launch `ca.beric.clipsync/ca.beric.clipsync.android.MainActivity`; grant Shizuku.
4. Exchange payloads: feed Mac's payload (addresses rewritten to `["10.0.2.2"]`) to Android via `am start … --es pairing_payload_b64 <base64>`; copy Android's payload (from logcat `clipsync-payload`) into `~/.clipsync/peer-payload.txt`.
5. `echo hi | pbcopy` → appears on Android; set Android clipboard → `pbpaste` shows it.

## Key architectural facts (read before continuing)

- **Android capture is Shizuku, not AccessibilityService.** The locked accessibility design is impossible on Android 15/16 (proven — `BLOCKER-M2-android-capture.md`). Capture = foreground `dataSync` service polling `IClipboard.getPrimaryClip` through Shizuku's SHELL-uid binder, with `HiddenApiBypass` to un-hide the reflective method. Requires the user to run Shizuku.
- **Crypto** is `ClipsyncCrypto` (ionspin libsodium, commonMain): `seal`/`open` (XChaCha20-Poly1305, nonce-prepended), `generateKeyPair`/`deriveSharedKey` (X25519 → symmetric per-pair key), `shortAuthString` (6-digit SAS). libsodium tests are desktop-only (native lib absent in Android host-JVM unit tests).
- **Pairing** is `PairingPayload` (QR content) + `PeerStore` (SQLDelight) + `DeviceIdentity` (keypair; secret in `SecretStore` = Keychain/Keystore, public+id in DB). The handshake is proven in `PairingProtocolTest` — no QR UI yet.
- **Protocol** is `ControlCodec` (JSON: Hello/ClipUpdate/ImageUpdate), `ChunkFrame` (binary image chunks), `LwwResolver` (per-device counter dedup + wallclock/deviceId order). These are the pieces the transport moves over the wire.
- **Consumer seam:** capture and sync both flow through `ClipRepository` (record/observe/latest, 100-cap, dedup). Wire the sync engine to it.

## What's left

### M3 finish (before tagging `m3`)
1. **TLS identity** — per-device self-signed cert + keypair; SHA-256 fingerprint of the DER cert. Put the fingerprint in `PairingPayload.certFingerprint`. JVM + Android both have BouncyCastle available (`bcpkix-jdk18on` on desktop; Android has `androidx`/platform BC). Suggest `expect class TlsIdentity { fun certPem(): String; fun privateKey(): …; fun fingerprint(): String }`, actuals per platform, cert cached alongside the identity.
2. **QR UI** — desktop: render `PairingPayload.encode()` to a QR bitmap in the tray window (ZXing `com.google.zxing:core` works on JVM). Android: camera scan (`com.journeyapps:zxing-android-embedded:4.3.0`) → `PairingPayload.decode`. Then SAS confirm screen on both. **On-device verification needed** (camera + real QR).

### M4 transport — DONE
- **Ktor Netty TLS server + OkHttp client**, WebSocket, cert pinning by SHA-256 fingerprint (`transport/Transport.kt`, `TlsIdentity`).
- **`ConnectionManager`** (`transport/`): symmetric handshake (`Hello` → look up per-pair key → register peer or close unknown link), idempotent `dialPeer` over `host:port` endpoints with re-dial, client-only nodes skip the server (nullable `tlsIdentity`).
- **`SyncEngine`** (`sync/`, commonMain): capture→seal→broadcast, receive→LWW→decrypt→record→apply, echo suppression, `Mutex`-guarded for the multi-coroutine (poll vs. connection) access. Records **before** applying so history attributes remote clips to the origin device, not `local`.
- **Appliers**: desktop AWT pasteboard; Android Shizuku `setPrimaryClip`.
- **Wiring**: desktop `Main.kt` and Android `AppGraph.startSync` build identity + engine + manager; capture flows through the shared change-gated `ClipboardWatcher` (Android via `ShizukuClipboardSource`).
- **Still pending for a "complete" M4:** mDNS auto-discovery (JmDNS/NsdManager, `_clipsync._tcp`) — untestable on the emulator (NAT blocks multicast), needs real Wi-Fi. (Image sync is now wired for desktop + transport; Android image capture is the remaining piece — see below.)

### M5 — DONE (built)
- **TLS identity persistence** ✅ `TlsIdentityStore` (PKCS12 + Keychain/Keystore); fingerprint stable across restart.
- **Android as server** ✅ symmetric P2P; Netty binds on-device (`serving=true`), advertises real LAN addresses.
- **Backoff dialer** ✅ `PeerDialer` (2s→60s per-peer); dials stored `host:port` endpoints (tailnet direct-dial when mDNS silent).
- **Connection-status UI** ✅ tray tooltip/title + Android connected-peer count.
- **CI** ✅ GitHub Actions (desktop tests on macOS, Android APK on Ubuntu) — unverified until first push.
- **README + AGPL LICENSE + F-Droid fastlane metadata** ✅.

### Genuinely remaining (needs Eric / a device)
- ~~QR pairing UI camera scan~~ — **DONE on a real phone.** See "On-device pairing run" above.
- **Real-phone verification** — still open: M2 background capture on a physical phone (needs Shizuku *started*, not just installed), M4/mDNS cross-device discovery on real Wi-Fi, and the phone→Mac clipboard direction. The LAN dial path is also still unexercised: the phone reached the Mac over the **tailnet** first, so LAN/mDNS remains unproven.
- ~~Android image capture/apply~~ — **DONE 2026-08-12, verified live on the S24 both
  directions.** Capture: the change token now hashes a URI clip's URI (an image-only
  clipboard used to collapse to the EMPTY sentinel), bytes are read via this app's resolver
  or, failing that, a SHELL-uid `content read` through Shizuku, and only accepted if they
  sniff as PNG/JPEG. Live: a URI clip set on the phone landed on the Mac pasteboard in ~3 s
  («class PNGf» in `clipboard info`, history row attributed to the S24). Apply: a clipboard
  image received from a peer saves to `Download/clipsync` with a notification (the
  LinkMyMac-style behavior) — putting it on the Android clipboard proper is still out, since
  a shell-uid `setPrimaryClip` can't make the URI read grant flow to whichever app pastes.
  Caveats: a Gallery "copy photo" by hand is the remaining human confirmation (same clip
  shape as the verified vehicle); provider URIs that even shell can't read are dropped with
  a log.
- **LTE + Tailscale sim** — Eric's on-device step.

## Harnesses already in place
- **`scripts/pairing-test.sh`** — the on-device harness described above. Prefers the phone's LAN adb
  transport (the phone shows up 3× alongside an emulator) but falls back to tailnet/USB rather than
  refusing to run, and asserts the SAS from *both apps' own logs* rather than re-deriving the hash
  outside the app. `run` force-stops the phone app so this run's log actually contains this run's
  startup lines, and refuses to start behind an already-running desktop whose stdout it cannot read.
  `evidence` screenshots both screens by CGWindowID (region capture silently grabs whatever occludes
  the tray window — it did, twice).
  Stale-state handling is a *warning*, not a gate: `preflight` flags leftover peer rows and a leftover
  `peer-payload.txt` with `→`, and only `reset` actually clears them. What stops a stale pass is
  narrower and load-bearing — `run` truncates both logs, and `verify` only accepts the desktop's
  `via=wire` pairing line. `verify` does *not* check how old the run was.
- Android 16 AVD `clipsync-a16` (API 36, google_apis, arm64); Shizuku installed + authorized for clipsync; `cliptester` helper APK (scratchpad `cliphelper/`, appId `ca.beric.cliptester`) injects clipboard text from a separate uid via `am start -n ca.beric.cliptester/.SetClipActivity --es text "…"`.
- Emulators/AVDs and the CrossPaste reference clone (`~/Arik/dev/_reference/crosspaste-desktop`) persist outside the repo.

## Open items for Eric
See `DEFERRED-QUESTIONS.md` — notably: confirm M2 background capture on your real phone.
