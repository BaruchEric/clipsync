# BLOCKER — M2 Android background clipboard capture

**Date:** 2026-08-08 · **Status:** locked design proven impossible as specified → stop-and-report per working agreement.
**Tested on:** Android 15 (API 35) AND Android 16 (API 36.1, the project's `targetSdk`) Google-APIs emulators, arm64. **Identical denial on both.** Real-hardware confirmation still owed by Eric.

## Android 16 confirmation (2026-08-08)

Re-ran the decisive test on a fresh Android 16 emulator (`release=16, sdk=36`). Same result across all paths — foreground-service poll `null`, a11y event-path read `null`, and the same AOSP line:

```
E ClipboardService: Denying clipboard access to ca.beric.clipsync,
  application is not in focus nor is it a system service for user 0
```

The restriction is not version-specific to Android 15; it is the standing AOSP model on the current target.

## The locked premise that failed

> "An enabled AccessibilityService exempts the app from the Android 10+ background clipboard-read restriction."

**This is false on Android 15.** Background clipboard *read* is denied to our app's UID through every path, regardless of the AccessibilityService or a running foreground service.

## Evidence (all with clipsync fully backgrounded, a separate app `cliptester` performing the copy)

| Path tested | Result |
|---|---|
| `OnPrimaryClipChangedListener` (a11y service) | Callback does **not fire** when backgrounded (foreground-gated) |
| Read inside `onAccessibilityEvent` (throttle fixed to 0, `typeAllMask`) | Event **fires** globally, but `primaryClip` read returns **null** |
| Poll `getPrimaryClip()` from the foreground `dataSync` service every 2 s | Every read returns **null** |
| `pm grant READ_CLIPBOARD_IN_BACKGROUND` | **Throws** — permission is `signature|privileged`, not `development`; not grantable to a sideloaded app via adb/Shizuku-pm-grant |

AOSP ground-truth log line, emitted on every background read attempt:

```
E ClipboardService: Denying clipboard access to ca.beric.clipsync,
  application is not in focus nor is it a system service for user 0
```

The check exempts only: the focused app, the default IME, holders of the privileged `READ_CLIPBOARD_IN_BACKGROUND`, and UIDs `SHELL`/`ROOT`/`SYSTEM`. A normal foreground/accessibility app is none of these.

Corollary finding: the v1.1 **READ_LOGS** premise is also dead here — modern `ClipboardService` does **not** log clip *content* (only the denial errors), so there is nothing to scrape from logs.

## What DOES capture on the emulator (works, but only foreground)

When clipsync is the focused app, capture is correct end-to-end: the listener fires and rows land in SQLDelight (verified rows e.g. `testB-…`). The M1 pipeline underneath (repo, dedup, cap) is sound. The wall is strictly *background* read.

## Options for true background capture on unrooted Android 12+

1. **Shizuku (recommended).** Shizuku exposes a binder running as the SHELL uid (2000), which the AOSP clipboard check exempts. clipsync reads `getPrimaryClip()` *through* Shizuku's privileged process. Reliable, captures all clipboard content, no root. Cost: user installs Shizuku and starts it once per boot via wireless-debugging (or root). This is real system-wide capture — it matches the project's #1 priority; it just isn't zero-setup.
2. **Accessibility node-text reading (best-effort, zero-setup).** Set `canRetrieveWindowContent=true`, watch for text-selection/copy events, and read the *source field's* text from the `AccessibilityNodeInfo` — bypassing the clipboard API entirely. Captures text copied from readable text fields; misses WebView/canvas/image copies, notification copies, and apps that suppress accessibility. Reads screen content, so the honest-disclosure copy must widen materially.
3. **Become the IME.** The default keyboard may read the clipboard. High friction (users must switch keyboards) and narrow; not recommended.

## Recommended adjustment (one)

**Promote Shizuku from v1.1 to the MVP capture mechanism (M2), with the AccessibilityService demoted to a best-effort zero-setup fallback (option 2).** Capture becomes: try Shizuku (full, reliable); if unavailable, offer accessibility node-text best-effort with honest disclosure. Everything downstream (M3 crypto, M4 sync, M5 tailnet) is unchanged — they consume `ClipRepository`, which is capture-agnostic.

This is a genuine change to the permission/UX model and the honest-disclosure copy, so it needs your sign-off before I proceed — I'm not silently substituting.
