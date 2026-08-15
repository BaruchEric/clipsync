# Deferred questions / decisions for Eric

Running autonomously through M3→M5 per "continue non-stop, defer questions till I'm back."
Decisions I made without you are logged here with my reasoning, so you can veto any.

## Open questions (need your input eventually)

- **M2 real-hardware confirmation — nearly closed (2026-08-12 16:12).** Shizuku granted; the phone's first Shizuku clipboard read captured and synced to the Mac, and a Mac copy landed on the phone (both DBs agree, correct attribution). Last sliver: a copy from another app while clipsync is **backgrounded** / after Doze — normal daily use settles it. Restart Shizuku after each reboot (HANDOFF has the adb one-liner).
- ~~M4 real-Wi-Fi confirmation~~ — **DONE 2026-08-12**: mDNS verified on real Wi-Fi after fixing a JmDNS loopback-bind bug (the Mac advertised 127.0.0.1 because `InetAddress.getLocalHost()` resolves to loopback on stock macOS; now bound to the first real LAN IPv4). Evidence: Mac log "mDNS discovered <S24> at 192.168.1.45:47653; dialing"; dns-sd shows both adverts with real SRV targets. A stale pre-fix loopback record may linger in Android's mDNS cache until TTL — harmless, three other connect paths cover it.
- ~~M6 real-hardware confirmation~~ — **DONE 2026-08-12**: verified Mac↔SM-S921U both directions on the real LAN, sha256-identical, including the cold-start share path. See HANDOFF "M6 real-S24 run". Tags m3/m4/m6 pushed.
- ~~m5 LTE gate~~ — **MET 2026-08-12 17:31, tagged.** Phone on LTE + Tailscale → Gallery photo on the Mac pasteboard in ~3 s (gate: <5 s); endpoint refresh carried the network switch; Mac→phone exercised by the link-up replay. ~~Only mechanical step left: install the current APK on the S24 at next Wi-Fi/USB contact.~~ **Delivered 2026-08-12 18:20 — by clipsync itself** (wireless debugging turned itself off on the network switch, so no adb): `Download/clipsync/clipsync-0.2.1.apk`, 55 MB in ~15 s. Left for you, deliberately, because it's a consent dialog: **tap the APK → Update → open clipsync once** (Shizuku grant survives the update). Then delete the APKs in that folder. If you'd rather I do it end-to-end: toggle Wireless debugging on and say so.
- ~~M7/M8 scope sign-off~~ — **signed off ("go with both") and built 2026-08-12.** M7 verified end-to-end (mirror + reply); M8 read path verified, live radio send left as your one tap (Messages tab → text yourself), observer push proves itself on the first real text. Note: I granted the phone-side access via adb under that sign-off — notification listener (`cmd notification allow_listener ca.beric.clipsync/…NotifMirrorService`) and SMS (`pm grant … READ_SMS/SEND_SMS`); revoke anytime with `disallow_listener` / `pm revoke`, or the app's cards re-request interactively.
- **M9 real-hardware confirmation — not started.** Phone file & photo browse is built and
  gate-verified only (2026-08-13); your S24 was unreachable this session (no adb device, no
  `_adb-tls-connect` advert, Tailscale node offline). Needs: unlock the phone, start Shizuku,
  turn on "Let a paired Mac browse my files" and grant the photo permission when asked, then
  the on-device checklist in the M9 task brief (roots/list/pull/push/delete/rename over adb,
  the disabled-toggle refusal, the photo-permission-denied grid state, four Files-tab
  screenshots). `m9` tags once that runs — see the "M9 phone file & photo browse" section
  below for the decisions made along the way.
  **As of 2026-08-14 that checklist is automated**: `scripts/m9-test.sh preflight` tells you
  what is missing, `run` drives and asserts items 1-7 over adb, and `ui` prints the four
  Files-tab states that need your eyes. It deliberately will not turn on the browse card or
  grant the photo permission — those two taps are yours, and a harness that flipped them would
  be testing consent it forged. Everything else in that session is one command.

## M9 phone file & photo browse — decisions & known limitations (2026-08-13)

Built and gate-verified (122 shared tests, both the Android APK and the desktop app image
build clean); the on-device session is still open, so `m9` is untagged and none of this has
run against your phone yet. Full write-up: `HANDOFF.md`'s M9 session section.

Decisions made without you (rationale in the spec/plan; veto any):

- **Full write access, not read-only pull.** The spec's open question was pull-only (a safer,
  LocalSend-style default) vs. full browse+push+delete+rename (LinkMyMac parity, matching what
  you asked to "implement"). Your call, made 2026-08-13: full write access — a paired Mac can
  push new files to the phone and rename/trash existing ones, not just copy files off. The
  desktop still never honors a peer-supplied absolute write path; every push resolves inside
  the destination root the *phone* names and confines, the same as every other path.
- **Trash-first delete, no auto-purge, no restore UI.** Deleting moves the entry into
  `<root>/.clipsync-trash` instead of unlinking it — cheap insurance against a wrong click
  hitting the wrong file (a real risk mid-build: the milestone's own stale-listing bug, see
  HANDOFF, made this concretely useful). Nothing purges the trash directory automatically, and
  there is no UI anywhere to browse or restore what's in it — a deleted file's only way back is
  manual, through the phone's own file manager. The consent card deliberately does not claim
  trashed items "can be restored" — an earlier draft did, and a reviewer caught it as promising
  a feature that doesn't exist.
- **Images only for the photo permission — `READ_MEDIA_IMAGES`, not `READ_MEDIA_VIDEO`.** The
  manifest briefly declared both; the grid never queries video, so that grant was dropped
  before it shipped (Task 8) rather than requesting access the app doesn't use. Consistent with
  the project's existing restraint on `READ_CONTACTS` for Messages.
- **Consent copy discloses the write access explicitly.** The on-phone toggle's ON-state text
  names "add new ones, rename, and trash," not just "copy them off" — an earlier draft
  undersold the grant (read as pull-only), caught in Task 10's review as a material omission on
  a consent surface, not a style note.

Known limitations, not fixed in this milestone:

- A `list` refusal for a directory the user has since navigated away from can misname the
  *currently displayed* directory as the one that was refused — `FsResult` carries only
  `op`/`ok`/`detail`, nothing to correlate a reply against the request that produced it. Needs
  a protocol change (tag `FsResult` with the root/path or a request id). Produces a wrong
  message, not data loss; needs fast navigation to trigger.
- The desktop broadcasts browse requests to every connected peer rather than targeting one — a
  second paired phone would answer the same request. Not reachable with your current
  one-phone setup.
- Nothing re-binds the Shizuku file service if `FileBridgeService` dies on its own while
  Shizuku itself keeps running (it's a killable, non-daemon process) — browsing stays dead
  until the clipsync app is relaunched. Distinct from the Shizuku-*restarts* case, which Task 9
  already covers (`Shizuku.addBinderReceivedListener`).
- Carried from M6, found during M9's review, not an M9 regression: `FileTransferEngine`'s
  transfer-state list is a plain `MutableStateFlow` read-modify-write with no `.update{}` — two
  concurrent transfers on different threads can silently drop a state update, which can make
  transfer rows look frozen in both UIs. One-line fix (`_transfers.update { ... }`); parked for
  the branch's final review rather than opened in this task.

## M4 live sync — DONE (2026-08-08)

Full bidirectional Mac↔Android clipboard sync demonstrated on the Android 16 emulator:
- **Mac → Android:** `pbcopy` on Mac → appears on Android clipboard via Shizuku `setPrimaryClip`. **447 ms** (gate: <2 s).
- **Android → Mac:** clipboard set from another uid (cliptester) → Android captures via Shizuku → appears on the macOS pasteboard (`pbpaste`).
- Over **real pinned TLS** (cert fingerprint from the pairing payload), payloads **E2E-encrypted** with the X25519-derived per-pair XChaCha20-Poly1305 key. History attribution correct on both sides (remote clips stored under the origin deviceId, not `local`), echo suppression held, no rebroadcast storm.

## M5 hardening — DONE (2026-08-08)

Built and (where possible) verified on the emulator:
- **Persistent TLS identity** (`TlsIdentityStore`): PKCS12 file + password in Keychain/Keystore. Fingerprint now stable across restart (verified: re-pair after restart, sync intact).
- **Android now serves** (symmetric P2P): Netty binds on the phone (verified `serving=true` on Android 16, no crash), advertises real LAN addresses.
- **PeerDialer** with per-peer exponential backoff (2s→60s) so an offline peer doesn't wake the phone every tick; dials stored `host:port` endpoints (covers tailnet direct-dial when mDNS is silent).
- **Connection-status UI**: desktop tray tooltip/title + Android "N peer(s) connected".
- **CI** (GitHub Actions): desktop tests on macOS, Android APK on Ubuntu.

### New open items / decisions to know

- **CI is unverified until first push.** GitHub Actions can't run locally; the workflow is correct-by-construction and the test suite passes in a simulated `CI=true` run, but the macOS/Ubuntu runners haven't executed it. Watch the first run.
- **mDNS: fully verified on real Wi-Fi (2026-08-12).** Both halves live: the Mac discovers and dials the phone's NsdManager advert at its real address, and the phone browses the Mac's JmDNS advert. Required fixing JmDNS to bind a real LAN interface instead of `InetAddress.getLocalHost()` (loopback on macOS — the loopback-only bind is also why the desktop half "worked" in the local opt-in test but was invisible on a real LAN).
- **Per-peer link dedup is implemented but not exercised.** The manager closes a duplicate link when a peer is already connected. On the emulator only one side can reach the other (Mac can't dial the emulator), so the dedup path never ran there — it's implemented, not verified. Related: connection "glare" (both sides' links completing near-simultaneously and both dropping) is possible; the backoff dialer reconnects within ~2s. Rare on a LAN; a deterministic tiebreaker (keep the lower-id dialer's link) is the fix if it ever bites.
- **TLS server does no client-auth.** A dialing peer presents no certificate; peer identity rests entirely on the `Hello` device id + the per-pair key (a wrong key fails the AEAD open). This is fine for an E2E-encrypted app but is the kind of thing a security review will flag — documented deliberately.
- **NsdManager uses the deprecated `resolveService`/`getHost`** (deprecated API 34+) for minSdk-29 simplicity; migrate to the callback API later.

## Image sync — DONE, both directions (2026-08-08; Android half closed 2026-08-12)

- **What's verified (each piece, not the whole composition):** (a) the engine+transport moves a 200 KB image A→B byte-identical over real TLS — announce → chunks → reassemble → integrity-check → decrypt → apply — between two in-process engines (test); (b) the real macOS pasteboard captures and applies an image within one process (test). **End-to-end Mac↔Mac is not tested** — two desktop instances can't run on one machine (they'd contend for port 47653 and the same DB), so the pieces compose by inspection, not by a test. **Mac→Android would sync but not apply** (see below).
- **Single path (spec deviation, intentional):** every image is sent as `ImageUpdate` + chunks, even a one-chunk image. The 1 MiB "inline in the control frame" cap from the spec is not implemented as a second branch — one receive path is simpler and gets exercised by every image. Chunk size 64 KiB; images over **16 MiB are rejected** (DoS bound).
- **Transfer id / integrity:** one value — `sha256(sealed bytes)` — is both the frame id and `ImageMeta.sha256`, so the receiver verifies integrity *before* decrypting. Half-received transfers are bounded (oversize/duplicate/bad-index frames drop the transfer) and evicted when the peer disconnects.
- ~~Android image capture/apply~~ — **BUILT + live-verified 2026-08-12** (see HANDOFF). Decisions: capture reads URI-clip bytes via own resolver → SHELL-uid `content read` fallback, gated by PNG/JPEG magic-byte sniffing (declared mimes lie; `content read` prints errors to stdout); apply saves received images to `Download/clipsync` + notification instead of the clipboard, because a shell-uid `setPrimaryClip` cannot make URI read grants flow to paste targets — revisit only if a grant path materializes. The old token gap is fixed (`clipSignature`: text hash / URI hash, disjoint ranges). Open slivers: hand-verify one Gallery "copy photo"; URIs unreadable even by shell drop with a log.

## M6 file transfer — DONE, emulator-verified (2026-08-12)

Decisions made without you (rationale in the plan doc; veto any):

- **Files went ahead of notifications/messages.** The MVP spec listed files as a non-goal, but your "implement LinkMyMac" instruction re-opened scope, and files is that product's headline beat, reuses the transport, and adds zero Android permissions. Notifications/SMS wait for your sign-off (permission surface).
- **Auto-accept from paired peers** (the LocalSend-adjacent call): pairing is the consent boundary; there is no receive-side prompt in v1. Bounds: ≤4 GiB, ≤4 concurrent inbound, sanitized names, IS_PENDING/temp-file until the sha256 verifies. Add an accept prompt later if this feels too permissive.
- **Send targets all connected peers** (your setup is 1 phone + 1 Mac); a peer picker is a later nicety.
- **Per-chunk AEAD instead of the image path's whole-blob seal** — files must stream (GB-scale); the image path is untouched. AAD binds transfer id + chunk index, so chunks can't be spliced across transfers or reordered.
- **Exact-size offers only:** Android share sources without a resolvable size (rare) are skipped with a log, because the offer carries the chunk count up front. Revisit with a chunked-EOF protocol if it ever bites.
- **Share-sheet URI grant caveat:** a huge send relies on the content-Uri read grant staying valid while the transfer runs; if the user swipes the task away mid-GB-transfer, the stream can die (transfer fails cleanly, receiver discards). Copy-to-cache-first was rejected (doubles I/O and disk for the common case).
- **Harness hooks are product code:** `~/.clipsync/send-file.txt` (desktop) and `--es send_file_path` (Android) mirror the peer-payload.txt bootstrap so on-device runs stay assertable. They only read files the app could already read.
- **Stored peer endpoints go stale — root cause found on the S24 (2026-08-12).** The phone's peer row still carries the Mac's 2026-08-08 address list with the two dead Parallels endpoints *first*; the desktop-side filter only fixes newly generated payloads. Each dead endpoint burned OkHttp's default 10 s connect timeout, so post-cold-start reconnects exceeded the share wait. Mitigated now (3 s dial timeout + 20 s share wait); the real fix is refreshing a peer's stored addresses whenever a link is established (the Hello could carry the current list) or on re-pair. Re-pairing the phone also clears it today.
- **Emulator peer hygiene:** the emulator run used an isolated desktop home (`-Duser.home`), so no test peer rows or files touched your real desktop state.

## Autonomous decisions (FYI — veto if wrong)

- **M3 crypto lib:** ionspin libsodium verified working on JVM — kept it, no Lazysodium fallback needed.
- **M3 SAS format:** 6-digit numeric code derived from the per-pair key (blake2b). Simple to compare on both screens.
- **M3 per-pair key:** X25519 DH hashed with both public keys (sorted) via blake2b → symmetric key both devices derive identically. Stored in the peer table (app-private DB); the long-term X25519 *secret* goes in Keychain/Keystore, never the DB.
- **M3 QR pairing UI — BUILT (camera scan needs your phone).** Desktop renders its pairing payload as a scannable QR; Android has a "Scan to pair" button (ZXing camera — FOSS, no ML Kit/Play Services, so the F-Droid build is fine). Because the desktop has no camera, the scanner sends its payload back over the wire (a `PairRequest`, gated behind a one-shot flag), so the scanned peer derives the same key. Verified device-independently: the reverse-channel loopback test passes, the QR encode/decode round-trips the real 248-char payload byte-identical, and both UIs render without crash. **The literal camera scan is unverified until a physical phone** — bundle with the real-phone session.
- **QR render is not visually verified.** The payload encodes/decodes correctly (round-trip test) and the window composes without error, but I could not screenshot the actual pixels (screen-capture is blocked for this session). A 180.dp render of a 320px QR matrix is exactly where a code can become too small for a camera to read — the phone session will reveal this immediately; bump the render size if it won't scan.
- **`pendingReciprocalPair` is a single global flag (multi-peer edge case).** It is set on QR scan and cleared when the *first* peer registers. With one peer (your immediate Mac↔phone test) this is fine. But if the phone already has another paired peer that reconnects between the scan and the new peer's link, the flag clears early, the new peer never gets the `PairRequest`, and pairing half-completes (phone has desktop, desktop lacks phone) — the only symptom is "sync doesn't work." Fix when it matters: make it a per-peer pending set keyed on the scanned device id. Documented so a half-pair points here, not at the transport.
- **SAS is advisory, not gating.** Both screens show the 6-digit short-auth-string with copy: "these codes must match; if they don't, remove the peer." Security really rests on QR possession (only a device with the desktop's cert fingerprint can complete the TLS handshake); the SAS is a visual MITM double-check. Gating sync on an explicit SAS confirm is a possible hardening.
- **M3 tag still pending on-device** — everything is built and tested to the limit of the emulator; `m3` completes when the camera scan is confirmed on your phone (same session as M2/mDNS).
- **libsodium tests run desktop-only:** ionspin can't load native libsodium in the Android host-JVM unit test, so crypto tests live in desktopTest. The crypto code itself is commonMain and runs on-device fine.
- **M4 LWW order:** wall-clock time primary, deviceId lexicographic tie-break (deterministic convergence), per-device monotonic counter for dedup/echo-suppression. Clock-skew across devices is a known LWW limitation — acceptable for MVP; revisit if it bites.
- **M4 transport — BUILT and wired into both apps.** TLS+WebSocket transport (`ConnectionManager`), the `SyncEngine`, and platform clipboard appliers now carry clips end to end; loopback tests + the live emulator sim both pass. Remaining M4 nicety: mDNS auto-discovery (below).
- **Android is client-only for the sim.** The phone dials the desktop and does not run a server yet, so it presents no TLS certificate (the dial path does no client auth) and its pairing payload carries a placeholder fingerprint (`android-client-no-cert`). **M5 needs the phone to also serve** (symmetric P2P) — at which point it needs a real, persisted TLS identity. Noted here so it isn't forgotten.
- **TLS identity is currently ephemeral (regenerated per process run).** Fine for a single sim run (the payload is written after the cert is generated, in the same run), but a Mac restart changes the fingerprint and breaks a previously-paired peer. **TLS identity persistence** (PKCS12 + Keychain/Keystore) is required before M5 and before real pairing is durable.
- **Peer dial endpoints are stored as `host:port` strings** in the existing `Peer.addresses` column (no schema change), so a peer row carries everything needed to redial. Emulator NAT: Android dials `10.0.2.2` (host loopback); the sim rewrites the Mac payload's addresses to `["10.0.2.2"]` before importing.
- **Pairing bootstrap for the sim** is real key derivation minus the camera: payloads exchanged out-of-band (Mac writes `~/.clipsync/my-payload.txt`; peer payload imported via `~/.clipsync/peer-payload.txt` on Mac and `am start … --es pairing_payload_b64 <base64>` on Android). QR render + camera scan (M3 UI) still pending.
- **Where I stopped:** M1+M2 tagged; M3 crypto/pairing/identity + M4 transport/sync all built, tested, and demonstrated live. `m3`/`m4` not yet tagged (QR camera UI still pending for a "complete" M3; mDNS + TLS persistence pending for a "complete" M4). M5 (tailnet dial, backoff, status UI, README/F-Droid, CI) remains.
