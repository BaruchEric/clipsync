# clipsync — LinkMyMac/LinkMyDroid parity roadmap

**Date:** 2026-08-12 · **Trigger:** Eric: "implement" the LinkMyMac (Play Store
`com.kdg.beam_android`) + LinkMyDroid (App Store id6755784154) pair, per Samuel Nam's video
"How I use Android with Mac (better than iPhone)" (youtu.be/0NOR2ZzJYnQ, 125k views).

## What the reference product is

**LinkMyMac** (Android, free, by BeamDots / Kevin Gnanih) + **LinkMyDroid** (Mac/iPad/Vision,
**$22.99**, macOS 15.5+/iPadOS 17+) link an Android phone to a Mac over the local network —
"No cables. No accounts. No developer mode." Store-listed features: webcam, screen mirroring,
remote control (tap/scroll from macOS), instant file transfer, notification/message sync with
reply (Google Messages / SMS / WhatsApp), photo + contact browsing, clipboard sync, QR pairing,
Wi-Fi or USB. The video's wife-demo beats: **AirDrop-style drag-and-drop files, universal
copy/paste, live Messages, file-manager access, webcam** — and "can it work between phones?
*Not yet.*"

clipsync is the FOSS answer to exactly this category (its README's founding complaint is tools
paywalling clipboard sync — LinkMyDroid is a $22.99 paywall). Parity here means the *cross-device
experience*, not cloning the app.

## Parity table

| LinkMyMac feature | clipsync today | Verdict |
|---|---|---|
| Clipboard sync (text) | ✅ done, E2E-encrypted, verified live | **ahead** (E2E crypto, tailnet, AGPL) |
| Clipboard sync (images) | 🟡 desktop + transport done; Android capture/apply pending | close the Android half |
| File transfer / drag-drop ("AirDrop") | ❌ (spec'd as MVP non-goal, MVP is done) | **→ M6, this session** |
| QR pairing, no accounts, local-only | ✅ done + SAS MITM check (stronger than reference) | ahead |
| Android⇄Android | ✅ architecture already symmetric P2P | free differentiator — reference "not yet" |
| Works off-LAN | ✅ tailnet direct-dial | ahead (reference is LAN-only) |
| Notification mirroring + reply | ✅ | **M7 built + verified 2026-08-12** (mirror + RemoteInput reply, E2E-sealed `mirror` envelope) |
| SMS/Messages from desktop | ✅ | **M8 built 2026-08-12** (threads/read/send from desktop; read path verified on-device; live send = one user tap) |
| Screen mirror + remote control | ❌ | Out for now: **scrcpy** already does this best (FOSS); remote *input* is feasible via Shizuku (`input` through SHELL uid) if ever wanted |
| Webcam | ❌ | Out: whole product on its own (video pipeline + virtual camera driver on macOS) |
| Photos / contacts / file-manager browse | 🟡 files + photos (M9; on-device pending) | **M9 built 2026-08-13** — full browse/pull/push/rename/trash-delete via the Shizuku bridge (no new storage permission for files); the photo grid needs `READ_MEDIA_IMAGES` only. Gate-verified, not yet hardware-verified. Contacts stay out: reading them would need `READ_CONTACTS`, which clipsync has never requested (same restraint as Messages/M8) |

## Priority call (made autonomously — veto welcome)

**M6 = file transfer.** Reasons: (1) it's the video's core "AirDrop — done" moment and the #1
store-listed feature clipsync lacks; (2) the transport already moves chunked sealed binary
(image path) — files are its natural generalization; (3) zero new Android permission surface
(SAF/share-sheet in, MediaStore Downloads out); (4) it's the LocalSend use case folded into an
app that's already persistently paired + E2E-encrypted — i.e. differentiated, not duplicative.

M7 + M8: signed off ("go with both", 2026-08-12) and built the same day — plan in
`docs/superpowers/plans/2026-08-12-m7-m8-notifications-messages.md`. Mirror/webcam stay out; document scrcpy as the companion tool.

Plan for M6: `docs/superpowers/plans/2026-08-12-m6-file-transfer.md`.
