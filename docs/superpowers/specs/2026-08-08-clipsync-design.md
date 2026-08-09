# clipsync — Design Spec (MVP)

**Date:** 2026-08-08 · **Status:** Approved (user-locked spec; GREENFIELD verdict accepted)
**Mission:** Open-source, cross-platform shared clipboard. Fully free, no paywalled features, ever. Android true background capture is priority #1; open source #2. License AGPL-3.0.

## Phase 0 outcome

CrossPaste fork rejected — Android app is closed-source (see `FORK-ASSESSMENT.md`). Building greenfield. Reference clone at `~/Arik/dev/_reference/crosspaste-desktop`: read-for-design only; code lifted only for hard-to-reimplement pieces (pairing v3 key schedule class) with a provenance header (repo, path, commit, AGPL-3.0 notice).

## Stack (locked)

- Kotlin Multiplatform + Compose Multiplatform. `shared` core in `commonMain`; `androidApp` module; `desktopApp` (JVM) module. Kotlin 2.x, Gradle version catalogs.
- Ktor client + embedded server. kotlinx.serialization: JSON control/handshake, raw binary payload frames.
- Crypto: libsodium via `com.ionspin.kotlin:multiplatform-crypto-libsodium-bindings` (verify maintenance at M3; fallback Lazysodium behind an expect/actual seam).
- SQLDelight history (cap 100 entries/device). mDNS: NsdManager (Android), JmDNS (JVM).
- Repo `~/Arik/dev/clipsync`, conventional commits, milestone tags.

## Architecture (locked)

- **Topology:** serverless P2P, no accounts/cloud. LAN-first via mDNS `_clipsync._tcp`; WAN via existing Tailscale tailnet — peers persist tailnet 100.x IPs at pairing and direct-dial known peers when mDNS is silent.
- **Transport:** TLS, per-device self-signed certs pinned by SHA-256 fingerprint exchanged at pairing (LocalSend model). Persistent WebSocket, reconnect with backoff.
- **Pairing:** QR (X25519 pubkey + device ID + address hints) → scan → derive per-pair secret → short-auth-string visually confirmed on both screens → persist peer pubkey, cert fingerprint, addresses.
- **Payload crypto:** XChaCha20-Poly1305 AEAD, per-pair key; encrypt before socket. Private keys in Android Keystore / macOS Keychain.
- **Sync:** last-write-wins on (deviceId, monotonicCounter, wallClockMs). Single latest value; no CRDTs.
- **Content:** plain text + PNG/JPEG. 1 MiB inline cap; larger images = metadata frame (type, size, SHA-256) + chunked binary frames on the same TLS socket.

## Android capture (REVISED 2026-08-08 — locked premise overturned by evidence, user-approved pivot)

**Original locked design (AccessibilityService background clipboard read) is impossible on Android 15 AND 16.** Proven on API 35 + API 36 emulators: AOSP `ClipboardService` denies background reads to a normal/accessibility app's UID through every path (change listener, a11y-event-dispatch read, foreground-service poll). The check exempts only the focused app, the default IME, holders of privileged `READ_CLIPBOARD_IN_BACKGROUND`, and the SHELL/ROOT/SYSTEM uids. See `BLOCKER-M2-android-capture.md`.

**Approved replacement — Shizuku as the MVP capture mechanism:**
- Shizuku runs a service as the SHELL uid (2000), which AOSP *does* exempt from the focus check. clipsync reads `getPrimaryClip()` via a `ShizukuBinderWrapper` around the `IClipboard` system service, so the call executes with shell identity → background read succeeds. Verified feasible: Shizuku server starts as shell on the emulator.
- Capture = foreground `dataSync` service that polls the clipboard through Shizuku (~1 s) into `ClipRepository`. No AccessibilityService needed for capture.
- **Fallback (zero-setup, best-effort):** AccessibilityService node-text reading — read the source field's text from the `AccessibilityNodeInfo` on copy/selection events, bypassing the clipboard API. Misses WebView/image/notification copies; reads screen content. Offered when Shizuku is unavailable. Deferred: implement after the M2 Shizuku path proves out.
- User setup: install Shizuku (F-Droid/GitHub, open-source), start it once per boot via wireless debugging or root. Real system-wide background capture — priority #1 preserved; simply not zero-setup.
- minSdk 29, targetSdk latest stable. F-Droid primary, sideload secondary. Honest disclosure: clipsync reads the clipboard (via Shizuku) to sync copies; data goes only to the user's own paired devices, E2E encrypted; no servers.
- Battery: multicast lock only during discovery windows; WS keepalive tuned for Doze.
- Dropped: the v1.1 "Shizuku + READ_LOGS" idea — modern `ClipboardService` no longer logs clip content, so READ_LOGS yields nothing.

## Derived decisions (implied by acceptance criteria, recorded here)

- **Receive = auto-apply:** a synced entry is written into the local system clipboard automatically (M4 acceptance "copy on Mac → paste on Android within 2s" requires it). Requires **echo suppression**: applying a remote clip must not re-capture and re-broadcast it — dedupe on origin (deviceId, counter).
- **Desktop UI:** menubar tray app; popover lists history (latest first, 100 cap). Click entry → copy to local clipboard.
- **macOS watcher:** NSPasteboard has no change notification API → poll `changeCount` (~200–500 ms).

## Deferred design details (fixed in that milestone's plan, not re-litigated)

- M3: SAS derivation (hash over both pubkeys + transcript → short digit/word string), cert generation/storage details, counter persistence.
- M4: chunk size, backpressure, reconnect/backoff parameters.
- M5: connection-status UI shape, F-Droid metadata layout.

## Non-goals (MVP)

iOS; Windows/Linux desktop; cloud relay/FCM; files/HTML/RTF; history search; multi-user; Play Store accommodation.

## Milestones & acceptance gates (stop for user review at each)

1. **M1 Scaffold + macOS watcher** — KMP builds Android+desktop; tray app watches clipboard → SQLDelight history. Accept: copy text on Mac → appears in local history UI.
2. **M2 Android capture** — AccessibilityService + fg service; disclosure/onboarding. Accept: copy anywhere with screen off shortly after → in history; survives 30 min Doze.
3. **M3 Pairing + crypto** — QR + SAS, Keystore/Keychain, AEAD round-trip vs libsodium test vectors. Accept: pair two devices; tampered ciphertext rejected.
4. **M4 LAN sync** — mDNS, TLS pinning, WS, LWW both ways, image chunking. Accept: cross-device paste <2 s on Wi-Fi.
5. **M5 Tailnet + hardening** — known-peer dial over 100.x, backoff, status UI, README/F-Droid metadata, CI. Accept: Android on LTE (Tailscale) ↔ Mac <5 s.

## Testing

Unit-test crypto, protocol framing, LWW ordering. Skip UI tests. On-device steps (accessibility grant, QR scan, Doze) are handed to Eric.

## Working agreements

Short written plan per milestone; commit per coherent unit; tag milestone completions. If a locked decision proves impossible: stop, present blocker + one recommended adjustment.
