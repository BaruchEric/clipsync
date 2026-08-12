# clipsync

Open-source, cross-platform **shared clipboard and file drop**. Copy on one device, paste
on another; share a file from the phone and it lands on the Mac (and vice versa).
Serverless peer-to-peer over your LAN (and your Tailscale tailnet), end-to-end encrypted,
with **no accounts, no cloud, and no paywalled features — ever.**

**License: AGPL-3.0.** Free software, and it stays that way.

## Why

Every good clipboard-sync tool eventually paywalls the thing you actually need — usually
Android background sync or encryption. clipsync exists so that never happens: the whole
feature set is open source and free, and Android background capture is the priority, not
an upsell.

## How it works

- **Serverless P2P.** No backend, no account. Devices pair directly and talk to each other.
- **Discovery.** mDNS (`_clipsync._tcp`) on the LAN; known peers are also dialed directly by
  their stored addresses, so it works across a Tailscale tailnet where multicast can't reach.
- **Transport.** Persistent TLS WebSocket. Each device has a self-signed certificate; peers
  pin each other by SHA-256 fingerprint exchanged at pairing (the LocalSend model).
- **Pairing.** A pairing payload (X25519 public key + certificate fingerprint + address hints)
  is exchanged; both sides derive the same per-pair key. A short auth string lets you confirm
  no man-in-the-middle.
- **Encryption.** Clipboard payloads are sealed with XChaCha20-Poly1305 under the per-pair key
  *before* they touch the socket. Long-term secrets live in the macOS Keychain / Android Keystore.
- **Sync model.** Last-write-wins on `(deviceId, monotonicCounter, wallClockMs)`. Local history
  is capped at 100 entries. Text and PNG/JPEG images (large images are chunked).
- **File transfer.** Any file streams between paired devices over the same encrypted link:
  256 KiB chunks, each sealed with the per-pair key (AAD binds chunk to transfer + position),
  whole-file SHA-256 verified before the file is published. Desktop: drag-and-drop onto the
  clipsync window or "Send a file…", received files in `~/Downloads/clipsync`. Android: share
  to clipsync from any app; received files in `Download/clipsync` with a notification.

## Android background capture

Android 10+ blocks background clipboard reads for everything but the focused app or active IME —
AccessibilityService cannot read the clipboard in the background on Android 15/16 (verified).
clipsync uses **[Shizuku](https://shizuku.rikka.app/)**: a foreground service reads/writes the
clipboard through Shizuku's shell-uid binder, which is exempt from the focus check. You run
Shizuku (via wireless debugging or root) and grant clipsync access once.

Distribution target is **F-Droid** (and sideloaded APKs) — not Google Play.

## Build & run

Requires JDK 17. Uses the Gradle wrapper.

```bash
# Shared unit tests (crypto, protocol, LWW, TLS transport loopback, …)
./gradlew :shared:desktopTest

# Desktop app (macOS menu-bar tray)
./gradlew :desktopApp:createDistributable
open desktopApp/build/compose/binaries/main/app/clipsync.app

# Android debug APK
./gradlew :androidApp:assembleDebug
# -> androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

## Pairing (current state)

The pairing key exchange and the full transport are implemented and tested, and the QR-camera scan
has been verified on real hardware (Mac ↔ SM-S921U, matching SAS on both screens). The desktop shows
a QR; the phone scans it and sends its own payload back over the wire so the camera-less side gets
the matching key. Pairing by exchanging the payload out-of-band (each side prints/logs its own) still
works as a headless fallback. `scripts/pairing-test.sh` drives and asserts the whole on-device run
(`preflight | reset | run | verify | evidence | sync | logs | stop`). See `HANDOFF.md` for details.

## Project layout

- `shared/` — Kotlin Multiplatform core (commonMain) + JVM transport (`jvmShared`, shared by
  desktop and Android): crypto, pairing, protocol, LWW, TLS transport, sync engine, discovery.
- `desktopApp/` — Compose Multiplatform desktop tray app (macOS).
- `androidApp/` — Android app: Shizuku capture, foreground service, Compose UI.

## Status

M1 (scaffold + macOS watcher) and M2 (Android background capture via Shizuku) are complete.
M3 (crypto + pairing) and M4 (LAN sync: TLS transport, sync engine, mDNS) are implemented and
proven with a live Mac↔Android sync. M5 hardening (persisted TLS identity, symmetric serving,
backoff, status UI, CI) is built. M6 (file transfer) is implemented and verified live
Mac↔Android-emulator in both directions; real-phone confirmation pending. The wider roadmap
(vs. LinkMyMac/LinkMyDroid: notifications, messages) is in
`docs/superpowers/specs/2026-08-12-linkmymac-parity-roadmap.md`. See `HANDOFF.md`.

## License

Copyright (C) clipsync contributors.

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU Affero General Public License as published by the Free Software Foundation, either version 3
of the License, or (at your option) any later version. See [LICENSE](LICENSE).
