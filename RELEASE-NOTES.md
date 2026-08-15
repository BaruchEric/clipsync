# Release notes

## 0.4.2 (versionCode 7) — the deferred ledger, closed where it could be

> **Status: PARTIALLY verified.** The desktop/shared half is verified — 125 shared tests, 0
> failures, 1 skipped, and the security fix below is pinned by a test that provably fails
> without it. **The Android half is built but NOT device-verified**: the phone left mid-session,
> so the file-bridge leak fix has never run on hardware. Install 0.4.2 and re-run the
> reproduction in "Known" below before trusting it.

- **A leaked privileged helper process, found on-device and fixed.** Every clipsync restart
  stood up a *new* SHELL-uid file-bridge process beside the old one instead of reusing it —
  measured on the S24 going 2 → 3 → 4 across two force-stop/start cycles, unbounded. Each
  orphan is a privileged process with whole-filesystem reach and no client left to serve.
  `bindUserService` does not reuse a helper whose client process has died, and `daemon(false)`
  cannot help because a killed process never runs an unbind — so the bridge now unbinds
  (`remove = true`) on the way *in*, from the new process, keyed on the same service args.
  Turning the browse card **off** now also stops the helper rather than leaving it running to
  refuse requests, which is what that card's own copy already implied.
- **Delete and rename now refuse a folder that *contains* a declared root, not just one that
  *is* one.** The check compared paths by equality, which was sufficient only under an unwritten
  constraint — that no root sits more than one level below another. Today's seven roots satisfy
  it; adding, say, `internal/DCIM/Camera` would have quietly reopened the hole, and trashing
  `DCIM` would have swallowed a declared root. The constraint is now enforced instead of
  documented (M9 review residual R3, deferred out of M9 until the security core had run on a
  device — it has, 29/0 in M9 and clean again in M9.1). Behavior-preserving for the roots that
  ship: a path is newly refused only when it is a *strict* ancestor of a root, and the only such
  path reachable is `internal` itself, which equality already refused. Both halves are pinned by
  tests, and the refusal test fails against the old code.
- **Harness:** `pairing-test.sh`'s `desktop_pid` no longer aborts the whole script when no
  desktop is running — `pgrep` exits 1 on no match and `pipefail` promoted that to the
  assignment's status under `set -e`, killing the run silently in the ordinary case. The same
  construct was found and fixed in `m9-test.sh` during M9.1; this was its last unguarded twin,
  recorded then as "not fixed here".

### Known

- **The file-bridge changes are unverified on hardware.** Two things to check, not one:
  1. *The leak.* `adb shell ps -A | grep filebridge`, then `am force-stop ca.beric.clipsync` and
     `am start` a couple of times. Before the fix the count grew by one per start and old
     processes survived the force-stop; after it, it should stay at one. Orphans from *earlier*
     builds are only cleared by a reboot (Shizuku stops then anyway).
  2. *The consent toggle round-trip*, which is the riskier half. Turn the browse card **off**
     (the helper should stop), then **on** again, and browse a folder from the desktop. The
     failure mode to watch for is browsing silently dead after an off→on cycle rather than a
     leaked process — a user-visible regression on the same consent path M9.1 already found a
     `StateFlow` bug on.

  The cross-process mechanism itself *is* confirmed, from the `dev.rikka.shizuku:api:13.1.5`
  bytecode rather than by assumption: with `remove = true` the client sends a null connection
  and a Bundle of component + tag, so the server reaps by ComponentName, which is stable across
  processes. What remains unverified is the behavior on a real device, not the API contract.
- Everything else in the deferred ledger is untouched and still open — see `DEFERRED-QUESTIONS.md`.

---

## 0.4.1 (versionCode 6) — M9.1: the Files tab grows up

> **Status: verified on real hardware 2026-08-15.** Each fix was driven live on the
> SM-S921U, and the M9 regression harness re-ran clean against these builds (26/26 driven
> checks; the consent-toggle step waits for a human by design and was verified in the M9
> session). 123 shared tests, 0 failures, 1 skipped.

Five follow-ups from the M9 on-device session, all shipped:

- **A capped folder now says so.** A folder holding more than 2000 entries used to list
  exactly 2000 with nothing withheld-looking withheld. The listing now carries a
  `truncated` flag end to end (`fs-entries` gains a `trunc` field; a 0.4.0 peer omits it
  and it decodes as false), and the desktop shows *"Showing the first 2000 entries — this
  folder holds more."* On-device testing caught the bug that would have made the flag a
  no-op: the phone-side Shizuku bridge capped at exactly 2000 *before* the engine could
  count, so overfull and exactly-full were indistinguishable — a 2005-file folder listed
  as an unflagged 2000. The bridge now returns one entry beyond the wire cap.
- **The root chips scroll; Photos is always reachable.** The chip row used to overflow at
  the default window width — 'Movies' rendered letter-by-letter vertically and the Photos
  button sat out of view. The chips now scroll horizontally and the Photos/Files toggle is
  pinned outside the scroll region.
- **Disconnect gets its own state.** The tab used to keep the previous phone's chips over
  an indefinite "Loading…"; it now says *"No phone connected. Browsing resumes when a
  paired phone reconnects."* — and reconnect refetches roots, the open folder, and the
  photo grid automatically. This also retires "open the tab before the phone connects and
  it stays empty": the fetch is keyed on the peer, not on tab entry.
- **A refusal is never invisible.** Revoking the photo permission while photos were on
  screen used to leave stale names with silently blank thumbs; the refusal banner now
  renders above the stale content, in the file tree as well as the grid. Recovery is fixed
  too: thumbnails re-ask after a permission is re-granted instead of staying blank until
  the tab was closed and reopened.
- **Two phones: an explicit picker.** With more than one phone connected, the Files tab
  used to silently target whichever connected first. A picker row now names the choice;
  with one phone it never renders. A picked phone that disconnects falls back to whichever
  is still connected. (Built and reviewed; seeing it live needs a second paired phone.)

Harness: `m9-test.sh` no longer pins the phone build to 0.4.0 — it expects whatever
version the tree builds.

---

## 0.4.0 (versionCode 5) — Phone file & photo browse

> **Status: verified on real hardware 2026-08-14.** The on-device session ran clean —
> `scripts/m9-test.sh run` 29/0 on the SM-S921U plus all four Files-tab `ui` states (record
> in HANDOFF.md). It caught one real bug, fixed in-session: MediaStore paging used a
> `LIMIT` suffix in the sortOrder, which Android 11+ rejects and the app read as an empty
> gallery; paging now uses the Bundle query args. The `m9` tag goes on the commit that
> carries that fix.

### New: browse your phone's files and photos from the Mac

Turn on **"Let a paired Mac browse my files"** on the phone, and the desktop's **Files** tab can
browse seven storage roots — Internal storage, Download, Documents, Camera, Pictures, Movies,
Music — plus a Photos grid.

- **Off by default.** Nothing is readable until you turn the card on, and turning it off takes
  effect on the very next request. While it is off, no browse request touches storage at all —
  the refusal happens before any filesystem call, and the phone does not even spawn its
  privileged helper process.
- **No new storage permission for files.** File access rides the same Shizuku SHELL-uid bridge
  that clipboard capture already needed. The photo grid asks for one additional grant,
  `READ_MEDIA_IMAGES` — images only. (`READ_MEDIA_VIDEO` was requested during development and
  dropped before shipping: the grid never queried video, and an unused media permission would
  undercut the same restraint that keeps `READ_CONTACTS` out of this app.)
- **Pull, push, rename, and delete.** Pulled files land in `~/Downloads/clipsync`, verified by
  whole-file SHA-256 before they are published. Pushes land only inside a destination the
  *phone* resolves and confines — the desktop never honors a peer-supplied write path.
- **Delete moves to the trash; it never erases.** Deleted items go to `<root>/.clipsync-trash`.

### Know before you use it

- **Delete is trash-first, and there is no restore UI.** Items are moved to
  `<root>/.clipsync-trash`, not erased, and nothing purges that directory automatically — so it
  grows until you empty it yourself. Recovering something means going to that folder on the
  phone. The delete dialog says "moved to trash" rather than promising a restore feature that
  does not exist.
- **A folder with more than 2000 entries is capped silently.** You see 2000 entries and no
  indicator that anything was withheld, and which 2000 is arbitrary rather than alphabetical.
  A truncation indicator needs a new protocol field and is deferred to M9.1. Below the cap the
  listing is complete; above ~15k entries this is strictly better than what preceded it, which
  was a silently empty listing.
- **Open the Files tab before the phone connects and it stays empty.** There is no automatic
  retry — switch to another tab and back.
- **With two phones paired, the Files tab silently targets whichever connected first.** A peer
  picker is the real fix, deferred to M9.1. With one phone this cannot be observed.
- **Shizuku stops on every phone reboot.** Browsing stays dead until you start it again; the
  app re-binds automatically once Shizuku is back. If the phone-side file bridge process dies
  on its own (rather than Shizuku restarting), browsing stays dead until the app is relaunched.

### Also in this release

- The desktop now logs what comes *back* from a browse request — roots, listings, photo counts
  and thumbnail batches. Previously only mutating operations logged, so a headless session could
  see that a query was sent and never what answered. (This is what makes `scripts/m9-test.sh`
  able to assert rather than just drive.)
- Transfer rows no longer drop updates when two transfers run at once (a read-modify-write on
  the shared transfer state). The same fix was applied to incoming thumbnail batches.
- The `~/.clipsync/mirror-cmd.txt` harness verbs now address a single peer, matching the Files
  tab. Previously the tab was fixed and the harness was not, which mattered because the harness
  is how an on-device session drives this feature. With one phone the two are identical.

### Under the hood

Path confinement is enforced per request against the canonical path of the declared root, and
destructive operations additionally refuse any declared root's own directory — without which
`delete(root="internal", path="DCIM")` would have trashed the entire camera roll and then
rendered the Camera root as a permanently empty folder rather than an error.

122 shared tests, 0 failures, 1 skipped (opt-in mDNS smoke test).

---

Earlier milestones (0.1.x–0.3.x: clipboard sync, pairing, tailnet, file transfer, notification
mirroring, messages) are documented in `HANDOFF.md`.
