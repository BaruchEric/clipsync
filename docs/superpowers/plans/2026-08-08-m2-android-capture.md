# M2 — Android Capture Plan

**Goal:** system-wide clipboard capture on Android via AccessibilityService + foreground service; honest disclosure onboarding; survives screen-off and Doze. Acceptance simulated on emulator (AVD `clipsync-test`, android-35 google_apis) since no physical phone is attached; Eric spot-checks on real hardware later.

**Mechanism:** an enabled AccessibilityService exempts the app from Android 10+ background clipboard-read restrictions. We do NOT parse accessibility events — the service registers `ClipboardManager.OnPrimaryClipChangedListener` in `onServiceConnected` and reads `primaryClip` text on change. A `dataSync` foreground service (persistent notification) keeps the process alive; it will host the sync engine from M4.

## Tasks

1. **App graph + Application class** — `ClipsyncApp` initializes a singleton graph (db → `ClipRepository`); manifest registers it.
2. **ClipAccessibilityService** — config XML (minimal event mask, generic feedback), listener registration, text capture → `repo.record(LOCAL_DEVICE_ID, …)`, starts the foreground service. Non-text clips ignored (images: M4).
3. **SyncForegroundService** — notification channel, `START_STICKY`, fgs type `dataSync`; permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
4. **Onboarding UI in MainActivity** — status card per grant (accessibility, notifications, battery exemption) with honest disclosure copy shown BEFORE deep-linking to Settings: what is read (clipboard content on change only), what is not (screen content, keystrokes, analytics), where data goes (only the user's own paired devices, E2E encrypted; no servers).
5. **Emulator simulation (acceptance)** — create+boot `clipsync-test`; install debug APK; grant notification perm via `pm grant`; enable service via `settings put secure enabled_accessibility_services …`; trigger copies from the shell uid (`cmd clipboard set-text`, fallback: tiny helper APK with a different appId); verify rows by pulling the DB with `run-as`; repeat with screen off and after `dumpsys deviceidle force-idle` (Doze proxy — real 30-min Doze is Eric's on-device check), then `unforce` and confirm capture still works.

No new unit tests (capture is glue; the agreed test surface is crypto/framing/LWW). Commit per task; no tag until sim passes → tag `m2`.
