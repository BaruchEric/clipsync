# clipsync — Build Handoff (2026-08-15)

State after Phase 0 → M9. Everything below is verified on real hardware (Mac ↔ SM-S921U),
M9 included as of 2026-08-14 (see its session section below). Sessions are logged
newest-first below the table.

## Done & verified

| Milestone | Status | Evidence |
|---|---|---|
| Phase 0 fork due-diligence | ✅ GREENFIELD | `FORK-ASSESSMENT.md` |
| M1 scaffold + macOS watcher | ✅ tag `m1` | copy on Mac → SQLDelight history (automated probe) |
| M2 Android background capture | ✅ tag `m2` | Shizuku path on the real S24; keyguard boundary documented (reads need the phone unlocked; applies work locked) |
| M3 crypto + pairing + identity | ✅ tag `m3` | live QR camera pairing Mac↔SM-S921U, SAS matching on both screens |
| M4 LAN sync + mDNS | ✅ tag `m4` | live both-direction sync on the real LAN; JmDNS loopback-bind bug fixed |
| M5 tailnet + hardening | ✅ tag `m5` | Gallery photo on LTE → Mac pasteboard in ~3 s over Tailscale, E2E-encrypted; endpoint refresh across the network switch |
| M6 file transfer | ✅ tag `m6` | sha256-identical both directions incl. cold-start share; 55 MB APK self-delivery at scale |
| Replay-on-connect | ✅ | offline phone clip → Mac ~2 s after link-up (lock-state-keyed harness) |
| GUI status pass | ✅ | status-first screens both apps, verified by on-device screenshots |
| M7 notification mirroring + reply | ✅ tag `m7` | phone notification → Mac ~2 s; desktop reply landed back through RemoteInput (ok=true, len asserted) |
| M8 messages | ✅ tag `m8` — live send verified 2026-08-15 | radio send ok=true + provider sent row; loopback text (own number, Verizon) came back as an inbox row; observer fired unprompted (2× `sms push sent=true` → 2× unprompted `sms threads: 30` on the desktop); incoming notification mirrored with reply=true — see the session below |
| M9 phone browse | 🟢 verified on-device 2026-08-14; tag `m9` after commit | `m9-test.sh run` 29/0 in a single pass (roots, list, pull/push sha256, trash-first delete, rename, consent-gate refusal, 20 media items) + all four Files-tab `ui` states; one real bug (MediaStore paging) found on-device and fixed; 3 UI findings recorded — see the session below |
| M9.1 Files-tab follow-ups | ✅ verified on-device 2026-08-15 (0.4.1/vc6) | truncation flag live (2005-file folder → "2000 … truncated", 49-entry folder unflagged); offline state, reconnect auto-refetch, refusal-over-stale-grid, thumb recovery, and scrollable chips all screenshot-verified; `m9-test.sh run` re-ran clean (26/26 driven); peer picker built, needs a second phone to see — session below |

**125 shared test cases** (`./gradlew :shared:desktopTest`), 1 skipped (opt-in mDNS smoke test), 0 failures. All three modules build; `:androidApp:assembleDebug` produces an installable APK (0.4.2/vc7 — its Android-side change is **not** device-verified, see the 0.4.2 session below); `:desktopApp:createDistributable` produces a launchable macOS app image.

**Fresh clone / new worktree: write `local.properties` first.** It is gitignored, so it never
propagates — without it `:androidApp` dies at dependency resolution with "SDK location not found"
(the other two gates never get to run under `--continue`, since it fails during configuration):

```
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
```

## 0.4.2 session (2026-08-15, later) — deferred ledger, and a leak the device showed

Unattended session against a **finished tree**: the parity roadmap's last row closed in M9.1,
git was clean, everything through `m9.1` tagged and pushed. So this session deliberately did
**not** open an M10 — it worked only items the project's own docs had already recorded as owed,
and stopped. Branch `m10-deferred-ledger`, **not pushed, not tagged** (see "For Eric" below).

**The find: clipsync leaks a privileged helper process on every restart.** Noticed as an
oddity — two `ca.beric.clipsync:filebridge` processes with different ages (20:32 vs 20:09)
while the app process was only 20:10 old, so the older one had outlived a previous app. A
force-stop/start experiment made it unambiguous:

```
baseline    fb: 17513(21:21) 18023(20:58)
force-stop  fb: 17513 18023                 <- survive the app's death
start       fb: 17513 18023 20599           <- +1
force-stop  fb: 17513 18023 20599
start       fb: 17513 18023 20599 20712     <- +1
```

One orphaned SHELL-uid process per restart, unbounded, each with whole-filesystem reach and no
client. The baseline already had two before this session touched anything, so it leaks in
ordinary use, not just under a harness. Root cause: `unbindUserService` is called **nowhere in
the tree** — `bindUserService` does not reuse a helper whose client process has died, and
`.daemon(false)` cannot help because a killed process never runs an unbind. Fix: unbind
(`remove = true`) on the way *in*, from the new process, keyed on the same service args; plus a
real teardown when consent is withdrawn. Evidence kept at `build/m10-findings/` (gitignored).

**Also fixed, both fully verified here:**
- **R3** (`isDeclaredRootPath` equality → ancestor check). The recorded precondition — "don't
  widen refusals in the security core before it has run on a device" — was met by M9's 29/0 and
  M9.1. Verifiable without the phone, which is the point often missed about this class:
  `BrowseEngine` is jvmShared and runs in `desktopTest` against a real temp dir. Suite 123 → 125,
  0 failures. The refusal test was checked against a reverted implementation and **fails there**,
  so it isn't a false pass; the behavior-preservation test passes under both implementations by
  design, which is what makes it a control.
- **`pairing-test.sh:87`** — the last unguarded `pgrep | head` under `pipefail`, recorded in the
  M9.1 prep session as "same latent early-exit, not fixed here". Proven both ways: the old shape
  exits 1 and never reaches the next line when no desktop is running; the guarded one continues
  with an empty value.

Gates: all three green (`:shared:desktopTest` 125/0/1, `:androidApp:assembleDebug`,
`:desktopApp:createDistributable`), plus `m9-test.sh selftest` 0 failed with stdin closed and
`bash -n` on both harnesses. 0.4.2 / vc7.

**A dead branch caught in review, worth recording:** the first cut of the consent-off teardown
added an `else fileBridge?.unbind()` to `ensureFileBridgeBound` — but its only call site sits
inside `if (next)` in `MainActivity`, so the else could never run. The call moved outside the
`if`. Wiring a new branch is not the same as reaching it.

**Deliberately NOT done** (available, but not owed — and unverifiable while the phone is away):
- **`FsResult` request correlation.** A refusal can misname the currently-displayed directory.
  Real, but it's a wire-format change to a security-adjacent surface, its symptom is a wrong
  string under fast navigation (no data loss), and there's no device to test it against.
- **The file-bridge self-death re-bind** (health-check ping or death recipient). Related to the
  leak but a distinct case; needs a device to provoke.
- **Connection-glare tiebreaker, NsdManager `resolveService` migration, trash purge/restore UI,
  peer picker live check** — the first two speculative, the third a feature, the fourth needs a
  second paired phone.

### For Eric

1. **Nothing is pushed and nothing is tagged.** `main` is the deployed branch and the tags in
   this project mean "verified on real hardware" — 0.4.2's Android half isn't, so applying one
   unattended would devalue what m1–m9 mean. Branch `m10-deferred-ledger` is ready to merge when
   you've seen it.
2. **Verify the leak fix on the phone** (the one thing this session couldn't finish): install
   0.4.2, then `adb shell ps -A | grep filebridge` around a couple of `am force-stop` /
   `am start` cycles. The count should stay at one instead of climbing.
3. Your phone may still carry ~4 orphaned filebridge processes from the experiment above. They
   are harmless and a reboot clears them (Shizuku stops on reboot regardless).

## M9.1 session (2026-08-15) — the three findings fixed, VERIFIED on-device

Unattended session (Eric: "continue unattended till all done"). Scope: the M9 session's
three UI findings, the two 0.4.0 release-note constraints (silent 2000 cap, two-phone
targeting), and what verification turned up along the way. 0.4.1/vc6.

**Shipped** (details in RELEASE-NOTES 0.4.1): the `fs-entries` `trunc` protocol field with
its desktop line — *"Showing the first N entries…"*; scrollable root chips with the
Photos toggle pinned; a real offline state plus reconnect auto-refetch keyed on the peer
(retires switch-tabs-to-retry); refusal banners over stale grid *and* stale tree;
`requestedThumbs` reset on media replies so thumbs recover after a permission re-grant;
a peer picker that renders only with ≥2 phones connected.

**Two bugs only a device could show, both found and fixed in-session:**
- **The truncation flag was a no-op on Android.** `FileBridgeService.list()` capped at
  exactly 2000 *before* the Binder hop (transaction-buffer protection), so `BrowseEngine`
  never saw more rows than the wire cap and `truncated` stayed false — a 2005-file folder
  listed as an unflagged 2000. The bridge now returns 2001 (one past the wire cap); the
  engine still forwards 2000. The desktop gates said green the whole time — 123 unit
  tests can't see a cap that lives on the far side of a Binder call.
- **Thumbs stayed blank after a permission recovery.** The ask-once memory
  (`requestedThumbs`) had no reset: ids refused under a revoked `READ_MEDIA_IMAGES` were
  never re-asked after the re-grant, until the tab was closed and reopened. It now clears
  on each media reply (bounded: media replies don't generate themselves).

**Verified live** (evidence: `build/m91-test/`, gitignored — desktop.log, win-*.png):
2005-file scratch → `fs entries … 2000 (0 dirs, 2000 files) truncated`, 49-entry folder
unflagged; chips scrolled under a pinned Photos at 540 pt (cliclick + Quartz wheel
events); "No phone connected." replacing stale chips when the phone dropped; reconnect
auto-refetching roots + open folder + grid with no tab switch; the refusal banner over a
populated stale grid; thumbs re-loading after re-grant (all 60 drained, so the backfill
loop got a live pass too). Then `m9-test.sh run` re-ran against both new builds: every
driven check passed (roots, list, pull/push sha256, trash-first delete, rename, media);
the consent-OFF step waits for a human by design and was skipped, not faked — that path
is untouched and was verified in the M9 session.

**Also:** `m9-test.sh` preflight no longer pins the phone to 0.4.0 — it derives the
expected version from `androidApp/build.gradle.kts` (the pin failed the first correct
0.4.1 install). Session facts: Shizuku was down again (starts over adb by exec'ing
`libshizuku.so`, as recorded); `pm revoke` force-stops the app, which played the
disconnect scenario for free; the S24's keyguard dismissed with a plain swipe this
session (no PIN gate reached). One oddity, not chased: with the cursor parked over the
chip row, a burst of synthetic horizontal wheel events ended with the chip under the
cursor activating — Skiko/Compose event synthesis, not app logic; a real trackpad
doesn't do it.

**Not device-verifiable here:** the peer picker needs a second paired phone; it's built,
compiled, and review-argued (falls back on disconnect, renders only at ≥2).

## M8 live send (2026-08-15) — VERIFIED, gate met

The one deliberately-human step from the M8 row — a real radio send — ran driven end to end
(harness `sms-send`, not the tab; same path up to the SmsManager call). Text to Eric's own
number (+1 818…, discovered via `service call iphonesubinfo`, codes 16/20 on this A16 build):

- **Send**: desktop `mirror-cmd sms-send sent=true` → phone `sms send ok=true len=38` →
  provider row type=2. Desktop ack `sms send ok=true` came back over the wire.
- **Real round-trip**: the text left over the Verizon radio and returned — provider row
  type=1 (inbox), same 38-char body, ~300 ms after the sent row.
- **The observer proved itself** (the part shell-uid inserts could never exercise —
  non-default-app write protection silently no-ops them): SmsProvider change notifications
  on content://mms-sms fired for the sent and the inbox row; phone logged `sms push
  sent=true` twice; the desktop received two **unprompted** `sms threads: 30` refreshes.
- **Bonus, M7 exercised organically**: the incoming text's Messages notification mirrored
  to the desktop as `mirror notif from Messages (38 chars, reply=true)`.

Session facts worth keeping: the phone app was a cached process held alive only by the
NotifMirrorService binding — the sync service was down and both sides sat discovered-but-
undialed until `am force-stop` + `am start` (the activity starts fine behind the keyguard;
SMS send needs no unlock). Link-up was proven first with a read-only `sms-threads` probe
(30 conversations, ~1 s) before anything was sent. Evidence: `build/m8-live-send/`
(gitignored) — results.txt, desktop.log. Phone: SM-S921U on 0.4.0/vc5, desktop identity
614186691d70d0e1.

## M9 on-device session (2026-08-14) — VERIFIED, 29/0

The pending session from the M9 row ran on the real S24 (0.4.0/vc5; Shizuku restarted over
adb by exec'ing `libshizuku.so`, the trick recorded in the M8 session). `m9-test.sh run`:
**29 passed, 0 failed** in one pass — 7 roots by id, listings, pull and push sha256-identical
both directions, trash-first delete with bytes intact, in-place rename, and the consent gate:
card OFF refused with the exact Files-tab reason ('browsing disabled') and nothing leaked;
card back ON, media answered 20 items. `ui` then walked all four Files-tab states. Evidence:
`build/m9-test/` (gitignored) — results.txt, desktop.log, logcat.log, ui-8/9/10/11 pngs.

**One real app bug, findable only on-device, fixed and re-verified in-session:**
`MediaIndex` paged with `LIMIT`/`OFFSET` inside the sortOrder string. MediaProvider on R+
parses sortOrder strictly and rejects it ("Invalid token LIMIT"), and the `runCatching`
swallowed that into an empty list — "0 items", indistinguishable from an empty gallery.
Paging now goes through the Bundle query args on R+ (the string suffix stays for Q, where
the args don't exist). Verified after: 20 items, thumbnails rendering past the 24th tile,
so the backfill loop is live too.

Two harness bugs, found by the first pass rather than by reading:
- The trash assertion looked for the bare filename; `BrowseEngine` stamps trash entries
  `yyyyMMdd-HHmmss-<name>` on purpose (collision-proof). The engine was right.
- Scratch setup wasn't idempotent: run 1's `b.txt` made run 2's rename collide — which
  incidentally exercised the 'name already taken' refusal on a device. Setup now wipes first.

Three UI findings, **recorded rather than fixed** (the same order-of-work argument as R3):
- **The root-chip row neither wraps nor scrolls.** At the default window width 7 chips
  overflow: 'Movies' renders letter-by-letter vertically, and 'Music' plus the Photos
  button are pushed out of view — the photo grid is unreachable until the window is widened.
- **Disconnect leaves stale roots and an indefinite 'Loading…'.** The no-roots message only
  renders when `roots` is empty, and a disconnect doesn't clear the cached chips (R10's
  family: the tab has no offline/retry state of its own).
- **A refusal that arrives while stale data is on screen is invisible.** With photos already
  listed, revoking the permission produced the correct `fs media ok=false photo permission
  not granted` (logged, plumbing verified end-to-end) — but the grid kept the stale 20 names
  with silently blank thumbs. The message only shows on an empty grid; a fresh desktop
  showed it verbatim.

Also verified live: the stale-listing guard — x→back→y driven in ~300 ms (cliclick), the
settled listing matched the breadcrumb — and the delete dialog's exact trash-first wording.

## M9.1 prep (2026-08-14) — the on-device session is now one command

Phone still unreachable (no adb device), so this session did everything the device does not
gate. Branch `m9-1-harness-and-notes`, three gates green (122/0/1), nothing pushed, `m9` still
untagged.

**`scripts/m9-test.sh`** turns Task 12 Step 4 from a checklist into a driver:
`preflight | selftest | run | ui | verify | evidence | logs | stop | clean`. `run` executes
items 1-7 over adb with real assertions (7 roots by id, a listing that names the file, sha256
both directions on pull and push, delete lands in `.clipsync-trash` with bytes intact, rename
in place, and the disabled-toggle refusal); `ui` prints the four never-seen Files-tab states
with their exact expected strings. It will **not** turn on the browse card or grant the photo
permission — those are consent, and a harness that flips them tests a toggle it forged.

**A gap this found before the device did: three of the seven harness verbs were unobservable.**
`fs-roots`, `fs-list` and `media` answer into StateFlows that only the Files tab reads, and
clipsync is a menu-bar app whose window does not exist until the status item is clicked — so an
adb-driven session could see a query was *sent* and never what came back. Only `FsResult`
logged. The desktop now logs roots, listings (with a bounded, control-char-sanitized name
sample), media counts and thumbnail batches. Without this the harness could drive M9 but not
assert it.

Six bugs found by running the thing, and by review, rather than by reading it:

- **`x && var=…` as a bare statement is fatal under `set -e`** when `x` is false. Three sites;
  preflight died silently right after "desktop app image built" with no error at all.
- **`pgrep | head` returns 1 under `pipefail`** when nothing matches, so `live="$(desktop_pid)"`
  aborted the script in the ordinary case of no desktop running. The same construct is at
  `scripts/pairing-test.sh:87` unguarded — same latent early-exit, not fixed here.
- **The first verb after startup is silently lost.** `clipsync: identity` prints before
  `watchMirrorCmd`'s poller is running, so the verb is written, never read, then overwritten by
  the next one — the step times out for a reason having nothing to do with the phone. Caught by
  a dry run against a real desktop on an isolated home: `fs-roots` vanished while the four verbs
  after it logged fine. Fixed at both ends — `start_desktop` pings the watcher with a
  deliberately unrecognized verb and waits for the rejection, and `send()` rewrites (safe: a
  file still on disk was provably not consumed).
- **`read -r _` exits the script under `set -e`.** It returns 1 at EOF, and stdin is not a TTY
  when this is driven from an agent session, `nohup` or cron — which is the likeliest way it
  runs at all. It would have died at the two consent prompts, *after* the destructive steps,
  with no summary and the scratch dir and trash populated. Replaced with `prompt_until`, which
  polls the real consent flag instead of waiting for a keypress; that also deletes the "you said
  you did it but the flag disagrees" branch, since it waits for the state itself.
- **`wait_for_peer` grepped for a link-up line that does not exist.** Neither `Main.kt` nor
  `ConnectionManager` prints anything on connect — `connectedPeers` is a StateFlow read only by
  the status UI — so it always burned its timeout and fell through with a misleading message. It
  now polls with `fs-roots` and reads the answer the R1 change made observable: "sent=true" or
  "no connected peer". The fix and the thing it depends on came from the same change.
- **A leftover `logcat` corrupts the next run's evidence.** It holds an fd on the old inode while
  `>` truncates, interleaving two runs into one sparse file. Now killed before the new one
  starts, plus an `EXIT` trap so an early return cannot leak it. The trap deliberately does *not*
  stop the desktop — `ui` is the next step and needs the window.

`m9-test.sh selftest` pins all of these plus the false-pass case (an `expect()` that matches a
line from an earlier step reports a pass nobody performed) and a guard that fails if a bare
`read` ever comes back. It needs no phone and no desktop, and passes with stdin closed.

Also closed: **R1** — harness verbs now address one peer, matching the Files tab, and uniformly
across all browse verbs rather than only the destructive ones (a listing from one phone and a
delete addressed to another is worse than broadcasting both; the Files tab's own comment makes
that argument). Verified at runtime, not just compiled: with no peer, `fs-list`/`media`/
`fs-delete` now log "no connected peer" while `sms-threads` still broadcasts as before. **R3**
was deliberately *documented rather than fixed* — `isDeclaredRootPath`'s equality check encodes
an unwritten constraint (no root nested more than one level below another), now stated in the
KDoc with the exact one-clause fix, because widening refusals in the security core before that
core has run on a device once is the wrong order.

`RELEASE-NOTES.md` (new) carries the 0.4.0 draft and the three constraints the ledger required a
release note for: the silent 2000-entry cap, switch-tabs-to-retry, and the two-phone targeting.
Docs truth-up: the suite is **122**, not the 119 recorded in three places before the final fix
wave, and the parity roadmap's "Android image capture/apply pending" row was stale since
2026-08-12 (`DEFERRED-QUESTIONS.md` had carried the correction all along).

Still device-gated, unchanged: everything in the M9 row below.

## M9 phone file & photo browse (2026-08-13) — built + gate-verified; on-device run PENDING

Triggered by the parity roadmap's last open row ("Photos / contacts / file-manager browse").
Spec: `docs/superpowers/specs/2026-08-13-m9-phone-browse-design.md`; plan:
`docs/superpowers/plans/2026-08-13-m9-phone-browse.md`; 12-task SDD ledger:
`.superpowers/sdd/2026-08-13-m9-phone-browse/progress.md`.

What exists now: a `BrowseEngine` (jvmShared) confining every path to one of 7 phone roots
(internal, Download, Documents, Camera, Pictures, Movies, Music) by canonical-path prefix
check; trash-first delete (`<root>/.clipsync-trash`, no auto-purge, no restore UI — items are
moved, not erased); rename, pull, push; a MediaStore-backed photo index (images only, paged,
thumbnails on demand); a Shizuku SHELL-uid user-service file bridge on the phone (adds no new
storage permission — Android background capture already needed Shizuku); a `READ_MEDIA_IMAGES`
grant for the photo grid (`READ_MEDIA_VIDEO` was requested then dropped — the grid never
queried it); an off-by-default phone consent card that gates every read and write; and a
desktop Files tab (tree + Photos grid, delete confirm dialog, harness verbs in
`watchMirrorCmd`: `fs-roots`/`fs-list`/`fs-pull`/`fs-push`/`fs-delete`/`fs-rename`/`media`).
`versionName 0.4.0` / `versionCode 5`. Suite 75 → 119, then 122 after the final review's fix wave.

**Nothing in the Android half has executed on a device.** All 12 tasks gated on
`:shared:desktopTest` + `:androidApp:assembleDebug` + `:desktopApp:createDistributable`
only — Shizuku user services, MediaStore, and the desktop Compose UI all need either a device
or a clicked tray window that no automated run in this milestone ever reached (clipsync is a
menu-bar app; its window doesn't exist until the status item is clicked — confirmed by a
`System Events` probe during Task 11 that found window count 0 and was abandoned rather than
faked). The on-device session in the task brief (roots/list/pull/push/delete/rename over adb,
the disabled-toggle refusal, the photo-permission-denied grid check, and four desktop
screenshots) is deliberately **not done this session** — Eric's S24 was unreachable (no adb
device, no `_adb-tls-connect` mDNS advert, its Tailscale node offline >1 day). The `m9` tag is
withheld until that session runs; every other tag in this project (`m1`–`m7`) means "verified
on real hardware," and `m8` was left untagged for the same reason pending one live SMS send.

Four things learned the hard way, all from code review rather than a device, since none of
this could run on a device yet:

- **`O_CREAT|O_EXCL` is what refuses a symlinked final component, not `O_NOFOLLOW`.** A
  mutation test that dropped `NOFOLLOW_LINKS` alone still passed — `CREATE_NEW` (which maps to
  `O_CREAT|O_EXCL`) already fails with `EEXIST` the instant the final path component exists,
  symlink or not, verified directly against POSIX semantics with a syscall probe rather than
  trusted on faith. Both flags are kept — `CREATE_NEW` carries the real guarantee,
  `NOFOLLOW_LINKS` is defense-in-depth for non-exclusive opens — but the covering test cannot
  tell them apart, and the plan now says so instead of implying it can.
- **Adding subtypes to a sealed `MirrorEvent` silently breaks a consumer in another module.**
  Task 1 added 13 new subtypes; `AppGraph.handleMirrorEvent`'s exhaustive `when` didn't compile
  against them, and `:androidApp:assembleDebug` was broken from Task 1 through Task 4 — four
  tasks in a row shipped on a green `:shared:desktopTest` alone. Every task from Task 5 on was
  required to gate on all three build targets, and the fix for a sealed-type change is to name
  every new subtype explicitly (even in a throwaway ignore branch) rather than `else -> Unit`,
  which is exactly what let the break through undetected the first time.
- **`FileTransferEngine(this, ...)` constructed inside a test's own `runBlocking` deadlocks on
  the engine's stall watchdog.** The watchdog coroutine becomes a child of the calling
  `runBlocking` scope, so `runBlocking` waits on its own child forever (one run hung 3m28s
  before being killed). Fix is a separate `CoroutineScope(SupervisorJob())` plus an
  `@AfterTest` cancel, matching the pre-existing `FileTransferEngineTest` pattern — needed
  twice, in both Task 5 and Task 6, once the same construction shape showed up again.
- **`StateFlow` conflates an unchanged value, which silently defeats a payload-keyed
  `LaunchedEffect`.** `FsEntries` is a data class, so re-listing a directory whose contents
  haven't changed produces a value `==` the cached one — `MutableStateFlow` never re-emits it,
  so an effect keyed on the listing itself never re-fires. This sat directly on the milestone's
  own QA path (disable browsing, observe the refusal, re-enable, revisit the same folder) and
  was only caught because a re-reviewer walked that exact sequence through the code by hand.
  Fixed by keying on a reply counter (`fsEpoch`/`mediaEpoch`) instead of the payload shape —
  the identical bug shape independently caused "permission granted, zero photos" to render as
  "permission still denied" until fixed the same way.

Known limitations, not fixed in this milestone — see `DEFERRED-QUESTIONS.md` for the full
list and the milestone's other autonomous decisions (full write access vs. read-only pull,
trash semantics, images-only permission, consent copy).

## M6 file transfer (2026-08-12) — built + emulator-verified

Triggered by Eric: "implement" the LinkMyMac/LinkMyDroid pair (the $22.99 Android⇄Mac app from
Samuel Nam's video). Parity analysis + roadmap: `docs/superpowers/specs/2026-08-12-linkmymac-parity-roadmap.md`;
design: `docs/superpowers/plans/2026-08-12-m6-file-transfer.md`. Files was the clear next
milestone (the video's "AirDrop — done" beat); notifications (M7) and messages (M8) are
spec'd as candidates but **not** built — each needs a permission-surface sign-off first.

What exists now:

- **Shared engine** (`transfer/FileTransferEngine`, jvmShared): streamed transfers over the
  existing links — 256 KiB chunks sealed per-chunk (AAD = transfer id ‖ index), disk-backed
  receive through a `FileSink` seam, whole-file sha256 verified before publish, windowed acks
  (≤4 MiB in flight), 15 s offer timeout / 60 s stall watchdog, ≤4 GiB, name sanitization
  (a malicious paired peer cannot path-traverse). New control messages FileOffer/FileAck/
  FileError are additive — an old peer silently drops them and the sender times out with a
  "peer up to date?" failure, no crash.
- **Desktop**: drop files on the window or "Send a file…" (native dialog); receives to
  `~/Downloads/clipsync`; live transfer rows in the window. Also: Parallels `vnic*`/`bridge*`
  interfaces are now **excluded** from advertised addresses and tailnet CGNAT addresses sort
  last (closes the dead-endpoint reconnect latency flagged in the 2026-08-08 run — the live
  payload now advertises `["192.168.1.32","100.72.29.68"]`, no Parallels 10.x).
- **Android**: share-sheet target (`ACTION_SEND`/`SEND_MULTIPLE`, any mime) streams straight
  from the content Uri (exact size required; unknown-length streams skipped with a log);
  receives into MediaStore `Download/clipsync` (IS_PENDING until integrity passes) + a
  tap-to-open notification; transfer rows on the main screen. **Sends wait up to 10 s for a
  peer link** — a share usually cold-starts the process, and the unwaited send lost the race
  against the dialer every time (caught live in the emulator run below).
- **Harness hooks** (peer-payload.txt idiom): desktop polls `~/.clipsync/send-file.txt` (write
  an absolute path → sends it, logs `send-file start path=… size=…`); Android accepts
  `--es send_file_path <app-readable path>` (logs `clipsyncShare: send-from-intent … ok=…`).

### M6 emulator run (2026-08-12) — VERIFIED

Desktop app ran against an **isolated home** (`JAVA_TOOL_OPTIONS=-Duser.home=<tmp>`), so
Eric's real DB/identity/pairings were untouched; emulator `clipsync-a16`, app data cleared.
Paired via payload exchange (Mac ← logcat payload via peer-payload.txt, via=file; Android ←
intent extra with addresses rewritten `["10.0.2.2"]`, via=local; **SAS 364696 in both logs**).
Then, over the real pinned-TLS link:

- **Mac→Android**: 700,000-byte random file via the send-file.txt hook → landed as
  `/sdcard/Download/clipsync/testfile-mac-to-android.bin`, **sha256 identical**
  (`8008085c…2ee661`), `clipsync-files` notification channel live.
- **Android→Mac**: 650,000-byte random file via the send_file_path hook → landed in
  `~/Downloads/clipsync/up.bin` (isolated home), **sha256 identical** (`bbf0213a…33ede4`),
  ~4 s after a cold start (includes the wait-for-peer fix doing its job).

Notes: the first Android→Mac attempt failed `ok=false` — that failure is what produced the
wait-for-peer fix, then passed on retry. Also learned: `am start` extras are **dropped** when
the same activity is already top (`Intent.filterEquals` ignores extras) — harness runs must
`am force-stop` first, exactly as `pairing-test.sh run` already does.

### M6 real-S24 run (2026-08-12, later that day) — VERIFIED

Eric enabled wireless debugging; `adb-wifi.sh connect` found the phone at a fresh port
(192.168.1.45:45129, key trust intact). New APK installed over the old one (pairing
survived — the 2026-08-08 peer rows carried straight through), real desktop app relaunched
on the new build (identity `614186691d70d0e1`, same as the original pairing). All over the
real LAN, real pinned TLS, link auto-established (mDNS vs. stored-endpoint dial not
instrumented — attribution still open):

- **Mac→S24**: 1.5 MB via the send-file.txt hook → `/sdcard/Download/clipsync/`, ~4 s,
  **sha256 identical** (`99bc79b7…87301a`).
- **S24→Mac** (link up): 1.2 MB via the send_file_path hook → `~/Downloads/clipsync/`, ~2 s,
  **sha256 identical** (`8ab66684…43f946`).
- **S24→Mac, cold-start worst case** (force-stop → start-with-send): first attempt FAILED —
  the phone's stored peer row still lists the Mac's two dead Parallels endpoints *first*
  (from the 2026-08-08 pairing; the desktop filter only fixes newly generated payloads), and
  each burned OkHttp's default 10 s connect timeout, blowing the 10 s peer wait. Fixed with a
  **3 s dial connectTimeout** (Transport) + **20 s share wait** (AppGraph); re-ran the same
  worst case: **arrived ~8 s after cold start**, sha256 identical. Root cause + the real fix
  (refresh stored endpoints on contact) logged in DEFERRED-QUESTIONS.

Harness gotcha, twice-earned: `am start` **drops extras** when the same activity is already
top (`Intent.filterEquals` ignores extras) — either `am force-stop` first or vary the data
URI (`-d clipsync://send/N`) to force delivery.

Also done in that session: **Shizuku server started on the S24 over adb** (the app's
`start.sh` isn't visible to shell under scoped storage on Android 16 — exec the starter lib
directly: `pm path moe.shizuku.privileged.api` → `…/lib/arm64/libshizuku.so`; survives until
reboot).

### Live clipboard on real hardware (2026-08-12 16:12) — VERIFIED

Eric granted Shizuku (16:12:32) and re-scanned the Mac's QR. Both history DBs (metadata
checked from both sides) tell the same story:

- **Phone→Mac**: the phone's first Shizuku clipboard read captured a `local` text row at
  16:12:32; the Mac's DB has the same clip at 16:12:32 attributed to `633db2f59f4d9afa`
  (the S24) — Shizuku read → capture → seal → TLS → applied on the Mac.
- **Mac→phone**: Eric's 16:13:12 Mac copy appears on the phone attributed to
  `614186691d70d0e1`. Mac-copied **images** are recorded on the phone (16:03, 16:06) but not
  applied — Android image apply is the known open item, working as documented.
- **Re-scan refreshed the phone's stored endpoints** to the clean list
  (`[192.168.1.32:47653, 100.72.29.68:47653]`, Parallels gone), SAS 773702 matching the
  original pairing (key continuity). Observed edge, harmless here: with the link already up,
  the armed reciprocal `PairRequest` isn't sent until the *next* new link — so a re-scan
  refreshes the *scanner's* stored endpoints immediately but the peer's only at reconnect
  (the documented `pendingReciprocalPair` single-flag design).

Still open on M2, deliberately: the strict gate is a copy from another app with clipsync
**backgrounded** (and surviving Doze). Normal phone use will prove it in the field — if
copies keep appearing on the Mac today, it's closed.

### Follow-up hardening, same day (all live-verified on the S24)

- **Self-healing endpoints**: every Hello now carries the sender's current dial endpoints;
  a receiver holding the peer's key persists them (`PeerStore.updateAddresses`). Live: Mac
  tracks the phone at `192.168.1.45:47653`; phone holds the clean Mac list. Cold-start
  share is down to **~4 s** (was ~8 s, was ∞ before the dial-timeout fix).
- **Reciprocal pairing is per-device-id** (was one global flag): the payload goes out when
  the scanned peer's Hello arrives, or **immediately over an existing link** — a re-scan
  refreshes the peer without waiting for a reconnect. Closes the documented multi-peer
  half-pair edge.
- **mDNS on real Wi-Fi — VERIFIED both directions**, after fixing a real bug the new
  attribution log exposed: the phone "discovered" the Mac at **127.0.0.1** and dialed
  itself. Cause: JmDNS was created with `InetAddress.getLocalHost()`, which is loopback on
  stock macOS — it both advertised 127.0.0.1 and couldn't see LAN multicast. Now bound to
  the first real LAN IPv4. Evidence: Mac log `mDNS discovered <S24> at 192.168.1.45:47653;
  dialing`; `dns-sd -Z _clipsync._tcp` shows both adverts with real SRV targets
  (`192-168-1-32.local.`, `Android_1MX8PLN1.local.`). A pre-fix loopback record can linger
  in Android's mDNS cache until TTL; harmless.
- **CI verified green** on its first real run (the workflow had never executed).
  `versionName 0.2.0`, suite at 66 tests.

### M7 + M8 built and verified (2026-08-12 20:00) — notifications + messages

Eric signed off ("go with both"). One protocol addition carries the pair: a `mirror`
envelope whose body is the per-pair-sealed JSON of a `MirrorEvent` (notification text and
SMS bodies E2E-encrypted like clips; pre-0.3 peers drop the unknown type harmlessly).
`MirrorEngine` (jvmShared) seals/opens and routes; `ConnectionManager` gained a `mirror`
param. 9 new unit tests (engine seal/tamper/wrong-key/unknown-subtype + protocol round
trips); suite at 75.

**M7 verified on-device end to end.** Shell-posted notification → Mac in ~2 s
(`mirror notif from Shell (26 chars)`) + native macOS notification via the tray. Reply:
a clipsync test notification carrying a RemoteInput action mirrored with `reply=true`;
the desktop's reply (via the new `~/.clipsync/mirror-cmd.txt` harness hook, `notif-reply
<text>`) landed back through the stored action's PendingIntent — `notif reply … ok=true`,
receiver logged `test-reply received len=20`. Filters: own package (except the
`clipsync-mirror-test` tag), ongoing, group summaries.

**M8 read path verified on-device.** `sms-threads` → 30 conversations in ~2 s;
`sms-thread 1325` → 15 messages. Threads derive from the newest 500 provider rows,
address-only (contacts stay off the permission surface). Two Samsung/Android-16 findings
the hard way: (1) SmsProvider posts change notifications on **content://mms-sms**, not
content://sms — the observer registers on both; (2) shell-uid `content insert` into the
SMS provider **silently no-ops** (non-default-app write protection), so the observer can't
be exercised synthetically — it proves itself on the first real text (desktop logs an
unprompted "sms threads: N"). Live radio send deliberately left for Eric (one tap in the
Messages tab, e.g. a text to his own number); everything up to the SmsManager call is the
same verified path.

Permission surface as signed off: notification access + READ_SMS/SEND_SMS, both opt-in
cards in the phone UI, both granted this session via adb (`cmd notification
allow_listener`, `pm grant`) — reversible with `cmd notification disallow_listener` /
`pm revoke`. Desktop grew Notifications and Messages tabs next to Activity. 0.3.0 / vc4.

### GUI status pass (2026-08-12 19:20) — closing the visible gap vs LinkMyMac

Eric named the next gap: phone↔desktop GUI status. Both screens went status-first with a
shared look (per-platform code): a colored status chip in the header ("Connected to
SM-S921U" / "Waiting for …" / "Not paired yet"), per-peer rows (status dot, device name,
"Connected · code 773702" — SAS demoted to a detail), transfer rows with a real progress
bar, and the history feed renamed Activity with attribution by device *name* ("This Mac" /
"SM-S921U") plus HH:mm times and an image glyph for image entries. Desktop: the tray menu
now carries the same status line, the window title too, and pairing moved behind a "Pair
another device…" footer once at least one peer exists (the QR leads only on first run).
Android keeps its actionable setup cards (Shizuku/notifications/battery) under the new
header. Desktop verified by screenshot (window capture, two iterations — a wrapping hint
fixed); the phone build is installed but its visual check is pending an unlock, since the
activity renders behind the keyguard. The organic Activity feed during verification showed
Eric's real copies syncing live — the M2 field check happening on its own.

### Replay-on-connect VERIFIED on-device (2026-08-12 19:01)

With 0.2.1 on the phone and the keyguard understood, the lock-state-keyed harness
(`scratchpad/replay-verify.sh` pattern: wait for `mInputRestricted=false` → kill desktop →
plant clip → assert `capture … genuine=true` in logcat → relaunch desktop → assert pbpaste)
passed on the first attempt: **a clip captured on the phone with zero peers connected arrived
on the Mac ~2 s after the desktop relaunched** — replay held it as lastLocal and delivered on
register. Eric's own hand-copied string had already verified the live phone→Mac path on 0.2.1
minutes earlier. That closes the last open item from the LTE session: offline copies now
survive to the next link on real hardware, both directions of the marooned-photo scenario.

### Keyguard capture boundary (2026-08-12 18:55) — found while verifying 0.2.1 on-device

After installing 0.2.1 over adb (wireless debugging re-enabled; trust survived, no re-pair),
the new `set_text_clip` harness hook set the phone clipboard (`ok=true`) but nothing ever
synced — no capture logs, healthy single TLS link, no exceptions, Shizuku binder live. The
`debug_read_clip` hook pinned it in one shot: **`sig=null text=null` while
`mInputRestricted=true mDreamingLockscreen=true`** — Android denies `getPrimaryClip` under
keyguard **even to the shell uid**, while `setPrimaryClip` still succeeds. Every earlier
success (16:12 grant test, 17:31 LTE photo) ran with Eric actively using the phone; every
failure today ran against a locked phone on a desk. Not a regression — a platform boundary
the 0.2.0 runs never crossed. Product impact ~none (copying implies unlocked; capture is at
copy time; Mac→phone applies fine while locked) — but harness runs must keep the phone
unlocked, and README now documents the asymmetry. New harness extras this exposed the need
for: `--es set_text_clip <s>` (clipboard-as-another-app via Shizuku write, bypassing the
applier so the engine treats it as a genuine capture) and `--es debug_read_clip 1` (one
summarized read: signature + text length, never content). Capture emissions now log
(`capture text len=… genuine=…`) — the whole pipeline was silent before, which is why this
took bisection instead of one glance at logcat.

### Homecoming session (2026-08-12 18:20) — 0.2.1 delivered to the phone by clipsync itself

The phone came back to the LAN (mDNS re-discovered it; Hello refreshed its stored endpoints
back to `[192.168.1.45:47653]`), but **adb was unreachable**: wireless debugging turned itself
off during the LTE excursion. Evidence, not guesswork: the phone still *advertises*
`_adb-tls-connect` on 45129 (a known stale-advert quirk after a network switch) but the port
answers RST, and a full TCP sweep of 1024–49999 finds exactly one open port — 47653, clipsync's
own server. So the update APK went over **clipsync's own file transfer** instead:
`clipsync-0.2.1.apk`, **55 MB in ~15 s** — the largest real transfer yet (previous record
1.5 MB), windowed flow control exercised at scale (210 chunks, ack cadence held to the end).
It sits in `Download/clipsync/` awaiting a tap-to-install (a consent step that stays Eric's).
Shizuku's grant survives an update install (same debug signature); the sync service needs one
app-open afterwards. Alongside two 0.2.0 copies from the same exercise — install **0.2.1**,
then the folder can be emptied.

Two gaps this exposed, both fixed and pushed:

- **Sender success was silent.** Completion lived only in the transfers UI state; proving the
  first 55 MB delivery took TCP byte counters (`nettop`: 55,121,360 B out, socket idle — full
  payload can't leave without live acks, and the final ack only follows the receiver's sha256
  verify + publish). The engine now logs every terminal transfer state (done/failed, both
  directions) through an injected `log` — `println` on desktop, `Log.i` on Android. The very
  next send printed `file send done: clipsync-0.2.1.apk (54839340 B) peer=633db…`.
- **`send-file.txt` re-fired on restart.** The watcher's dedup var resets with the process, so
  a desktop relaunch re-sent whatever was last queued (observed live: the relaunch re-sent
  0.2.0 unprompted). The queue file is now deleted once consumed — at-most-once across
  restarts.

`versionName 0.2.1` / `versionCode 3`. Suite still 66 green; desktop runs the new build
(log now at `build/desktop-run.log`).

### LTE session, part 2 (2026-08-12 17:31) — m5 gate MET

Eric enabled Tailscale on the phone: tailnet answered at 17:28:38, link up at 17:29:08 with
the phone's Hello refreshing its stored endpoints to `[100.84.20.32:47653, 100.82.0.66:47653]`
(carrier CGNAT + tailnet — the refresh surviving a network switch, as designed). At link-up
the Mac replayed its newest clip to the phone over the tailnet (the phone's "Received
clipboard-….png" notification is the visible receipt). At **17:31:37** a Gallery photo copied
on the phone landed on the Mac pasteboard **within ~3 s** over LTE via the relay —
4000×3000, compressed JPEG on the wire, E2E-encrypted. **m5 tagged.** This also hand-confirms
the Gallery "copy photo" capture shape left open under image clipboard. The first copy
(pre-link) stayed marooned as expected: the phone still runs the pre-replay APK — install the
current build at next Wi-Fi/USB contact and offline copies replay from then on.

### LTE session, part 1 (2026-08-12 evening) — blocked on phone Tailscale, and a gap found + fixed

Eric switched the S24 to LTE and copied a photo. Nothing arrived, for two stacked reasons:

1. **The phone's Tailscale is offline ("last seen 3 days ago")** — on LTE there is no path
   to the Mac at all until Eric opens Tailscale on the phone and connects (an expired node
   key needs a fresh sign-in, not a toggle). The m5 LTE gate stays open pending that.
2. **Replay gap (now fixed):** even with a path, the copy predated the link — and clipsync
   had no replay: capture broadcasts only to currently-connected peers, so an offline copy
   was marooned forever. The engine now keeps the newest local capture in memory and
   **replays it to each peer that registers**; both sides replay, receiver LWW keeps the
   newest (SyncEngineReplayTest, 3 cases). The Mac runs the replay build; **the phone still
   needs this APK** — install on next Wi-Fi/USB contact. Until then, a copy made while
   linked syncs as always; the marooned photo can just be re-copied once the tailnet is up.

## On-device pairing run (2026-08-08) — VERIFIED

Harness: `scripts/pairing-test.sh` (`preflight | reset | run | verify | evidence | sync | logs | stop`).
Run against a physical **SM-S921U, Android 16** on the LAN, paired with the Mac desktop app.

What the run established, all green:

- Camera scan accepted the payload (`clipsyncScan: scan-pair ok=true`). — *asserted by `verify`*
- Both sides derived the same key: **SAS 773702**, logged on both *and* shown on both screens
  ("SM-S921U: 773702" on the Mac, "Mac: 773702" on the phone). — *asserted by `verify`*
- Reciprocal pairing over the wire worked — the camera-less desktop got the phone's key. — *asserted by
  `verify`, which now requires the desktop's `via=wire` log line specifically; the byte-identical
  `via=file` line from the `peer-payload.txt` poller no longer satisfies it.*
- Peer row present in both DBs; TLS link established. — *asserted by `verify`*
- Bonus: Mac→phone text sync arrived intact on the real phone. — *`sync`; it now also asserts the
  applier's own `applyText … ok=true`, because the history row is written before the clipboard write
  and so passes even when the write fails.*
- QR **decodes to byte-identical** content to the app's own payload (248 B) — checked by decoding a
  screenshot of the window, so a scan failure could never be blamed on the QR. **This one was a manual
  step during the run, not something `verify` re-checks** — there is no QR decode in the harness.

**Re-certifying needs a fresh `run`.** `verify` now demands the desktop's `via=wire` pairing line, and
the stored logs from this run predate that marker — replaying `verify` against them reports "desktop
never paired over the wire". That is the assertion getting stricter, not evidence the run was fake
(`peer-payload.txt` was absent throughout). The originals are kept at
`build/pairing-test/archive-2026-08-08-m3/`, since `run` truncates both logs unconditionally.

Also proven here for the first time (the emulator could not): **`serving=true` on real Android hardware**
— Netty binds on-device, so the phone advertises real addresses and P2P is genuinely symmetric.

Two honest caveats:

- The phone reached the Mac over the **tailnet** (`100.82.0.66 → 100.72.29.68`), not the LAN. It dials
  the payload's address list in order, and the Mac advertises Parallels virtual interfaces
  (`10.37.129.2`, `10.211.55.2`) ahead of the real LAN address. Worth filtering those out in
  `localAddresses()`: this is not cosmetic — `PeerDialer` backs off 2s→60s per peer, so two dead
  endpoints ahead of the live one add real latency to every *reconnect*, not just the first connect.
  They also masked the LAN path in this run.
- Shizuku is installed on the phone but **not started**, so clipboard *capture* on the phone
  (and therefore the phone→Mac direction) is still unverified on real hardware.

To reproduce: `./scripts/pairing-test.sh preflight` → `reset` → `run` → scan → `verify`.

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
- **Still pending for a "complete" M4:** mDNS auto-discovery (JmDNS/NsdManager, `_clipsync._tcp`) — untestable on the emulator (NAT blocks multicast), needs real Wi-Fi. (Image sync is now wired for desktop + transport; Android image capture is the remaining piece — see below.)

### M5 — DONE (built)
- **TLS identity persistence** ✅ `TlsIdentityStore` (PKCS12 + Keychain/Keystore); fingerprint stable across restart.
- **Android as server** ✅ symmetric P2P; Netty binds on-device (`serving=true`), advertises real LAN addresses.
- **Backoff dialer** ✅ `PeerDialer` (2s→60s per-peer); dials stored `host:port` endpoints (tailnet direct-dial when mDNS silent).
- **Connection-status UI** ✅ tray tooltip/title + Android connected-peer count.
- **CI** ✅ GitHub Actions (desktop tests on macOS, Android APK on Ubuntu) — unverified until first push.
- **README + AGPL LICENSE + F-Droid fastlane metadata** ✅.

### Genuinely remaining (needs Eric / a device)
- ~~QR pairing UI camera scan~~ — **DONE on a real phone.** See "On-device pairing run" above.
- **Real-phone verification** — still open: M2 background capture on a physical phone (needs Shizuku *started*, not just installed), M4/mDNS cross-device discovery on real Wi-Fi, and the phone→Mac clipboard direction. The LAN dial path is also still unexercised: the phone reached the Mac over the **tailnet** first, so LAN/mDNS remains unproven.
- ~~Android image capture/apply~~ — **DONE 2026-08-12, verified live on the S24 both
  directions.** Capture: the change token now hashes a URI clip's URI (an image-only
  clipboard used to collapse to the EMPTY sentinel), bytes are read via this app's resolver
  or, failing that, a SHELL-uid `content read` through Shizuku, and only accepted if they
  sniff as PNG/JPEG. Live: a URI clip set on the phone landed on the Mac pasteboard in ~3 s
  («class PNGf» in `clipboard info`, history row attributed to the S24). Apply: a clipboard
  image received from a peer saves to `Download/clipsync` with a notification (the
  LinkMyMac-style behavior) — putting it on the Android clipboard proper is still out, since
  a shell-uid `setPrimaryClip` can't make the URI read grant flow to whichever app pastes.
  Caveats: a Gallery "copy photo" by hand is the remaining human confirmation (same clip
  shape as the verified vehicle); provider URIs that even shell can't read are dropped with
  a log.
- **LTE + Tailscale sim** — Eric's on-device step.

## Harnesses already in place
- **`scripts/pairing-test.sh`** — the on-device harness described above. Prefers the phone's LAN adb
  transport (the phone shows up 3× alongside an emulator) but falls back to tailnet/USB rather than
  refusing to run, and asserts the SAS from *both apps' own logs* rather than re-deriving the hash
  outside the app. `run` force-stops the phone app so this run's log actually contains this run's
  startup lines, and refuses to start behind an already-running desktop whose stdout it cannot read.
  `evidence` screenshots both screens by CGWindowID (region capture silently grabs whatever occludes
  the tray window — it did, twice).
  Stale-state handling is a *warning*, not a gate: `preflight` flags leftover peer rows and a leftover
  `peer-payload.txt` with `→`, and only `reset` actually clears them. What stops a stale pass is
  narrower and load-bearing — `run` truncates both logs, and `verify` only accepts the desktop's
  `via=wire` pairing line. `verify` does *not* check how old the run was.
- Android 16 AVD `clipsync-a16` (API 36, google_apis, arm64); Shizuku installed + authorized for clipsync; `cliptester` helper APK (scratchpad `cliphelper/`, appId `ca.beric.cliptester`) injects clipboard text from a separate uid via `am start -n ca.beric.cliptester/.SetClipActivity --es text "…"`.
- Emulators/AVDs and the CrossPaste reference clone (`~/Arik/dev/_reference/crosspaste-desktop`) persist outside the repo.

## Open items for Eric
See `DEFERRED-QUESTIONS.md` — notably: confirm M2 background capture on your real phone.
