# Deferred questions / decisions for Eric

Running autonomously through M3→M5 per "continue non-stop, defer questions till I'm back."
Decisions I made without you are logged here with my reasoning, so you can veto any.

## Open questions (need your input eventually)

- **M2 real-hardware confirmation.** Emulator proves background capture works via Shizuku on Android 16. Confirm on your actual phone when convenient (install clipsync + Shizuku, start Shizuku, background clipsync, copy in another app → should appear in history).
- **M4 real-Wi-Fi confirmation (bundle with the above).** Live sync is proven Mac↔emulator over TLS, but the emulator's user-mode NAT **cannot** carry mDNS multicast — so discovery could only be exercised by direct-dial to `10.0.2.2`. mDNS auto-discovery needs your physical phone + Mac on the same Wi-Fi. Same device session as the M2 check.

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
- **mDNS: desktop half verified live, Android half not.** JmDNS advertise+discover round-trips on the Mac (opt-in test `-Dclipsync.mdns.test=1`). `NsdDiscovery` compiles and is wired but is **unverifiable on the emulator** (NAT blocks multicast) — folds into the real-phone session above.
- **Per-peer link dedup is implemented but not exercised.** The manager closes a duplicate link when a peer is already connected. On the emulator only one side can reach the other (Mac can't dial the emulator), so the dedup path never ran there — it's implemented, not verified. Related: connection "glare" (both sides' links completing near-simultaneously and both dropping) is possible; the backoff dialer reconnects within ~2s. Rare on a LAN; a deterministic tiebreaker (keep the lower-id dialer's link) is the fix if it ever bites.
- **TLS server does no client-auth.** A dialing peer presents no certificate; peer identity rests entirely on the `Hello` device id + the per-pair key (a wrong key fails the AEAD open). This is fine for an E2E-encrypted app but is the kind of thing a security review will flag — documented deliberately.
- **NsdManager uses the deprecated `resolveService`/`getHost`** (deprecated API 34+) for minSdk-29 simplicity; migrate to the callback API later.

## Image sync — DONE for desktop + transport; Android pending (2026-08-08)

- **What's verified (each piece, not the whole composition):** (a) the engine+transport moves a 200 KB image A→B byte-identical over real TLS — announce → chunks → reassemble → integrity-check → decrypt → apply — between two in-process engines (test); (b) the real macOS pasteboard captures and applies an image within one process (test). **End-to-end Mac↔Mac is not tested** — two desktop instances can't run on one machine (they'd contend for port 47653 and the same DB), so the pieces compose by inspection, not by a test. **Mac→Android would sync but not apply** (see below).
- **Single path (spec deviation, intentional):** every image is sent as `ImageUpdate` + chunks, even a one-chunk image. The 1 MiB "inline in the control frame" cap from the spec is not implemented as a second branch — one receive path is simpler and gets exercised by every image. Chunk size 64 KiB; images over **16 MiB are rejected** (DoS bound).
- **Transfer id / integrity:** one value — `sha256(sealed bytes)` — is both the frame id and `ImageMeta.sha256`, so the receiver verifies integrity *before* decrypting. Half-received transfers are bounded (oversize/duplicate/bad-index frames drop the transfer) and evicted when the peer disconnects.
- **Android image capture/apply is NOT implemented.** Reading/writing a clipboard image on Android means a `content://` URI backed by a ContentProvider, and doing that through Shizuku's shell-uid binder is a separate permission problem (URI grants) — not just more code. For now Android never captures images, and an image received from a peer is dropped with a log (`applyImage ignored … not supported on Android yet`). This is the one piece of image sync that still needs real Android work; flagged so "images sync" isn't read as "images sync to the phone."
- **Android image-capture token gap (for when it's built):** `ShizukuClipboardSource.changeToken()` hashes the text, so an image-only clipboard reads as the EMPTY sentinel and two different image copies look unchanged. Whoever builds Android image capture must fold the clip's URI/description into the token.

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
