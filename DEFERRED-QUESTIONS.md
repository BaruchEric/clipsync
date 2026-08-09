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
- **Connection "glare" is possible but self-heals.** Both sides dial and the manager dedups links; if two links complete near-simultaneously both can drop, and the backoff dialer reconnects within ~2s. Rare on a LAN; acceptable for MVP. A deterministic tiebreaker (keep the lower-id dialer's link) is the fix if it ever bites.
- **TLS server does no client-auth.** A dialing peer presents no certificate; peer identity rests entirely on the `Hello` device id + the per-pair key (a wrong key fails the AEAD open). This is fine for an E2E-encrypted app but is the kind of thing a security review will flag — documented deliberately.
- **NsdManager uses the deprecated `resolveService`/`getHost`** (deprecated API 34+) for minSdk-29 simplicity; migrate to the callback API later.

## Autonomous decisions (FYI — veto if wrong)

- **M3 crypto lib:** ionspin libsodium verified working on JVM — kept it, no Lazysodium fallback needed.
- **M3 SAS format:** 6-digit numeric code derived from the per-pair key (blake2b). Simple to compare on both screens.
- **M3 per-pair key:** X25519 DH hashed with both public keys (sorted) via blake2b → symmetric key both devices derive identically. Stored in the peer table (app-private DB); the long-term X25519 *secret* goes in Keychain/Keystore, never the DB.
- **M3 QR UI (camera scan on Android, QR render on desktop):** NOT yet built — the pairing *protocol* is proven end-to-end in simulation (PairingProtocolTest), but the on-screen QR + camera capture is device/UI work still pending. Will need your on-device verification once built.
- **M3 not tagged yet** — protocol/crypto/identity core is done and tested; QR UI + TLS cert generation (part of M4 transport) remain before tagging m3.
- **libsodium tests run desktop-only:** ionspin can't load native libsodium in the Android host-JVM unit test, so crypto tests live in desktopTest. The crypto code itself is commonMain and runs on-device fine.
- **M4 LWW order:** wall-clock time primary, deviceId lexicographic tie-break (deterministic convergence), per-device monotonic counter for dedup/echo-suppression. Clock-skew across devices is a known LWW limitation — acceptable for MVP; revisit if it bites.
- **M4 transport — BUILT and wired into both apps.** TLS+WebSocket transport (`ConnectionManager`), the `SyncEngine`, and platform clipboard appliers now carry clips end to end; loopback tests + the live emulator sim both pass. Remaining M4 nicety: mDNS auto-discovery (below).
- **Android is client-only for the sim.** The phone dials the desktop and does not run a server yet, so it presents no TLS certificate (the dial path does no client auth) and its pairing payload carries a placeholder fingerprint (`android-client-no-cert`). **M5 needs the phone to also serve** (symmetric P2P) — at which point it needs a real, persisted TLS identity. Noted here so it isn't forgotten.
- **TLS identity is currently ephemeral (regenerated per process run).** Fine for a single sim run (the payload is written after the cert is generated, in the same run), but a Mac restart changes the fingerprint and breaks a previously-paired peer. **TLS identity persistence** (PKCS12 + Keychain/Keystore) is required before M5 and before real pairing is durable.
- **Peer dial endpoints are stored as `host:port` strings** in the existing `Peer.addresses` column (no schema change), so a peer row carries everything needed to redial. Emulator NAT: Android dials `10.0.2.2` (host loopback); the sim rewrites the Mac payload's addresses to `["10.0.2.2"]` before importing.
- **Pairing bootstrap for the sim** is real key derivation minus the camera: payloads exchanged out-of-band (Mac writes `~/.clipsync/my-payload.txt`; peer payload imported via `~/.clipsync/peer-payload.txt` on Mac and `am start … --es pairing_payload_b64 <base64>` on Android). QR render + camera scan (M3 UI) still pending.
- **Where I stopped:** M1+M2 tagged; M3 crypto/pairing/identity + M4 transport/sync all built, tested, and demonstrated live. `m3`/`m4` not yet tagged (QR camera UI still pending for a "complete" M3; mDNS + TLS persistence pending for a "complete" M4). M5 (tailnet dial, backoff, status UI, README/F-Droid, CI) remains.
