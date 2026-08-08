# Fork Assessment — CrossPaste (crosspaste-desktop)

**Date:** 2026-08-08
**Assessed:** `github.com/CrossPaste/crosspaste-desktop` (shallow clone, default branch, 2026-08-08) + full CrossPaste GitHub org listing.

## Verdict

**GREENFIELD** — the Android app does not exist in any public repo; there is nothing to fork for our priority-#1 target.

## Findings

### (a) Is the Android app module present and buildable?

**No.** The repo's modules are `core`, `app`, `web` (Chrome extension), `shared`, `shared-ui`, `cli`, `e2e`. Source sets across all of them: `commonMain`, `desktopMain`, `jsMain`, `nativeMain`, `cliNativeMain`, etc. There is **no `androidMain` or `androidApp` source set anywhere**, no `AndroidManifest.xml`, and no `com.android.application`/`com.android.library` plugin or `androidTarget` reference in any Gradle file or the version catalog. The org's other repos are all forks of third-party dependencies (compose-multiplatform, libsignal, selenium, brew, etc.) — no sibling mobile repo, public or otherwise.

The Android app is real but closed-source: the CHANGELOG references "Android version under review on Google Play," a "mobile app promotion guide," and refactors done "to facilitate mobile reuse" — i.e., the public `commonMain` feeds a **private** mobile codebase.

### (b) Is the AccessibilityService clipboard-capture code public?

**No.** `grep -ri AccessibilityService` across the entire repo: **0 matches**. The hard part we care most about is entirely in the closed mobile app.

### (c) Is the Pro entitlement a strippable client-side gate?

**Unanswerable — and therefore no.** There is no entitlement/subscription/purchase code in the public repo because the paywalled surface (mobile) isn't in it. You can't strip a gate from code you don't have.

### (d) Account / licensing server dependency?

Desktop is clean: no login/account/license-key code. The only `crosspaste.com` endpoints are desktop auto-update metadata (`crosspaste.com/api/desktop.json`, `oss.crosspaste.com` release artifacts). Sync itself is LAN-serverless P2P. The mobile app's licensing mechanics are unknown (closed source) — moot given (a).

## Decision rule applied

> FORK only if the Android module builds from public source AND the paywall is a strippable client-side gate.

Condition 1 fails outright (no Android source exists publicly). **GREENFIELD.**

## Salvage value (reference only, not a fork)

The public repo is AGPL-3.0 and contains mature prior art worth reading while building greenfield: `core/.../pairing/v3/` (key schedules, signing-key SHA-256 fingerprints, pairing session store + identity-conflict tests), `app/.../sync/` (SyncResolver, GeneralSyncManager, push/pull services), and WebSocket routing modules with real integration tests. Consult as design reference; do not copy code unless we deliberately accept AGPL provenance tracking for those files (we're AGPL-3.0 anyway, but attribution hygiene applies).
