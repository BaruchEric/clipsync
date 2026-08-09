# clipsync — Build Handoff (2026-08-08)

State after Phase 0 → M4 live sync. Everything below is green; the transport is wired into both apps and demonstrated end to end.

## Done & verified

| Milestone | Status | Evidence |
|---|---|---|
| Phase 0 fork due-diligence | ✅ GREENFIELD | `FORK-ASSESSMENT.md` |
| M1 scaffold + macOS watcher | ✅ tag `m1` | copy on Mac → SQLDelight history (automated probe) |
| M2 Android background capture | ✅ tag `m2` | **Shizuku** path; background copy from another app → history, survives Doze, on Android 16 emulator |
| M3 crypto + pairing + identity | 🟡 core done (untagged) | XChaCha20-Poly1305 vector, X25519+SAS, 2-device pairing sim, real Keychain round-trip; **key derivation exercised live** in the sim. QR camera UI still pending. |
| M4 LAN sync (transport + engine) | 🟢 **live sync working** (untagged) | Loopback TLS tests + **live Mac↔Android emulator sync, both directions, 447 ms, pinned TLS, E2E-encrypted**. mDNS auto-discovery still pending. |

**43 shared unit tests green** (`./gradlew :shared:desktopTest --rerun-tasks`). All three modules build; `:androidApp:assembleDebug` produces an installable APK; `:desktopApp:createDistributable` produces a launchable macOS app image.

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
- **Still pending for a "complete" M4:** mDNS auto-discovery (JmDNS/NsdManager, `_clipsync._tcp`) — untestable on the emulator (NAT blocks multicast), needs real Wi-Fi. Image capture/apply wiring (chunk transport is tested but no platform image capture path yet).

### M5
- **TLS identity persistence** (PKCS12 + Keychain/Keystore) — currently ephemeral; required so a restart doesn't break pairing.
- **Android as server** (symmetric P2P) — currently client-only; needs a real persisted cert and `startServer`.
- Known-peer direct dial over persisted 100.x tailnet addresses when mDNS is silent (the `host:port` endpoint list already supports this); reconnect/backoff; connection-status UI; README + F-Droid metadata (`fastlane/metadata`); GitHub Actions (build + `:shared:desktopTest` + `:androidApp:assembleDebug`). LTE+Tailscale sim is Eric's on-device step.

## Sim harness already in place
- Android 16 AVD `clipsync-a16` (API 36, google_apis, arm64); Shizuku installed + authorized for clipsync; `cliptester` helper APK (scratchpad `cliphelper/`, appId `ca.beric.cliptester`) injects clipboard text from a separate uid via `am start -n ca.beric.cliptester/.SetClipActivity --es text "…"`.
- Emulators/AVDs and the CrossPaste reference clone (`~/Arik/dev/_reference/crosspaste-desktop`) persist outside the repo.

## Open items for Eric
See `DEFERRED-QUESTIONS.md` — notably: confirm M2 background capture on your real phone.
