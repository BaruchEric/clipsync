# Release notes

## 0.4.0 (versionCode 5) — Phone file & photo browse — **DRAFT, NOT YET RELEASED**

> **Status: not shipped.** The `m9` tag is deliberately withheld until the on-device session
> runs. Every other tag in this project (`m1`–`m7`) means "verified on real hardware", and
> nothing in this release's Android half or its desktop Files tab has executed on a device yet.
> Drive that session with `scripts/m9-test.sh` (`preflight` → `run` → `ui`). These notes are
> written now so the constraints below are not rediscovered during it.

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
