# clipsync — Build Handoff (2026-08-08)

State after an autonomous run through Phase 0 → M4-core. Everything below is committed and green.

## Done & verified

| Milestone | Status | Evidence |
|---|---|---|
| Phase 0 fork due-diligence | ✅ GREENFIELD | `FORK-ASSESSMENT.md` |
| M1 scaffold + macOS watcher | ✅ tag `m1` | copy on Mac → SQLDelight history (automated probe) |
| M2 Android background capture | ✅ tag `m2` | **Shizuku** path; background copy from another app → history, survives Doze, on Android 16 emulator |
| M3 crypto + pairing + identity | 🟡 core done (untagged) | 21→ tests: XChaCha20-Poly1305 vector, X25519+SAS, 2-device pairing sim, real Keychain round-trip |
| M4 wire protocol + LWW | 🟡 core done (untagged) | 14 tests: control codec, chunk frame, LWW ordering |

**35 shared unit tests green** (`./gradlew :shared:desktopTest --rerun-tasks`). All three modules build; `:androidApp:assembleDebug` produces an installable APK.

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

### M4 transport (the big chunk)
1. **Ktor embedded server + client**, WebSocket, TLS using the M3 cert. Pin the peer by comparing its presented cert's SHA-256 to the stored `Peer.certFingerprint` (custom `X509TrustManager` / Ktor `HttpsRedirect` off; verify in the WS upgrade). LocalSend does exactly this pattern — reference `~/Arik/dev/_reference/crosspaste-desktop` `net/` for prior art (AGPL, read-only).
2. **Sync engine** in `shared` (or per-platform with a common core): on local capture → `ClipRepository` → build `ClipVersion` (deviceId, incrementing counter, now) → `seal` with each peer's `perPairKey` → send `ClipUpdate` (or `ImageUpdate` + `ChunkFrame`s if >1 MiB). On receive → `LwwResolver.accept` → `open` → if accepted, write to local clipboard AND `recordLocal` to suppress the echo. Echo suppression is critical: applying a remote clip must not rebroadcast (dedup on origin deviceId+counter; `LwwResolver.recordLocal` + `ClipRepository` consecutive-dup guard cover it).
3. **Apply-to-clipboard**: desktop writes NSPasteboard (AWT `Toolkit.systemClipboard`); Android writes via Shizuku (`IClipboard.setPrimaryClip` through the same wrapped binder — background *write* is also focus-gated, so route it through Shizuku like the read).
4. **mDNS**: JmDNS (JVM), NsdManager (Android), advertise/discover `_clipsync._tcp`.
5. **Loopback integration test** (device-independent, do this first): two in-process endpoints over localhost TLS, pair them, seal→send→receive→open→LWW, assert the text/image round-trips and a tampered frame is rejected. Then the Mac↔emulator Wi-Fi sim.

### M5
Known-peer direct dial over persisted 100.x tailnet addresses when mDNS is silent; reconnect/backoff; connection-status UI; README + F-Droid metadata (`fastlane/metadata`); GitHub Actions (build + `:shared:desktopTest` + `:androidApp:assembleDebug`). LTE+Tailscale sim is Eric's on-device step.

## Sim harness already in place
- Android 16 AVD `clipsync-a16` (API 36, google_apis, arm64); Shizuku installed + authorized for clipsync; `cliptester` helper APK (scratchpad `cliphelper/`, appId `ca.beric.cliptester`) injects clipboard text from a separate uid via `am start -n ca.beric.cliptester/.SetClipActivity --es text "…"`.
- Emulators/AVDs and the CrossPaste reference clone (`~/Arik/dev/_reference/crosspaste-desktop`) persist outside the repo.

## Open items for Eric
See `DEFERRED-QUESTIONS.md` — notably: confirm M2 background capture on your real phone.
