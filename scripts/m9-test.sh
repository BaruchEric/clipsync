#!/usr/bin/env bash
# m9-test.sh — drives and asserts the M9 phone file & photo browse on-device session.
#
# M9 shipped gate-verified only: nothing in its Android half (Shizuku user service, MediaStore)
# or its desktop Files tab has ever executed on a device. This script is the checklist from
# docs/superpowers/plans/2026-08-13-m9-phone-browse.md Task 12 Step 4, turned into something
# that runs and asserts instead of something to follow by hand.
#
#   preflight   read-only: is everything in place for a run? (safe with no phone attached)
#   selftest    check the assertion engine itself — needs no phone and no desktop
#   run         items 1-7: roots, list, pull, push, delete, rename, disabled-refusal
#   ui          items 8-11: the four Files-tab states, which need a human and a mouse
#   verify      re-derive pass/fail from the last run's captured logs
#   evidence    print a HANDOFF-ready summary of the last run
#   logs        tail the desktop log and logcat
#   stop        quit the desktop this harness started; leave the phone alone
#   clean       remove the phone-side scratch directory and this run's local files
#
# Verbs are sent through ~/.clipsync/mirror-cmd.txt, the same harness hook M6/M7/M8 used
# (see watchMirrorCmd in desktopApp/.../Main.kt). Conventions, helpers and the
# desktop-ownership rule are the same as scripts/pairing-test.sh — the shared helper set
# lives in scripts/lib.sh, sourced by both (the copies drifted once too often).
#
# What this script will NOT do: turn the browse consent card on, or grant the photo
# permission. Both are consent, they are Eric's to give, and a harness that flips them
# would be testing a toggle it forged rather than the one a user taps.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

RUN_DIR="$REPO/build/m9-test"
DESKTOP_LOG="$RUN_DIR/desktop.log"
LOGCAT_LOG="$RUN_DIR/logcat.log"
RESULTS="$RUN_DIR/results.txt"
MIRROR_CMD="$HOME/.clipsync/mirror-cmd.txt"
RECV_DIR="$HOME/Downloads/clipsync"

# Everything destructive happens inside this one directory, under the `download` root.
# The plan is explicit: use a scratch directory for every destructive assertion, never a
# real photo. `delete` moves into <root>/.clipsync-trash, so the trash checked is Download's.
SCRATCH_REL="clipsync-m9"
SCRATCH_ABS="/sdcard/Download/$SCRATCH_REL"
TRASH_ABS="/sdcard/Download/.clipsync-trash"

CMD="${1:-preflight}"

mkdir -p "$RUN_DIR"

# Shared harness helpers (ok/bad/action/info/head1, resolve_phone, a, require_phone,
# phone_locked, desktop_pid, harness_pid, PKG/ACTIVITY/DESKTOP_BIN). RESULTS is set
# above, so ok()/bad() also record PASS/FAIL lines for 'verify'.
source "$REPO/scripts/lib.sh"

PHONE="$(resolve_phone || true)"

# --- device -----------------------------------------------------------------

# Debug builds allow run-as, which is the only way to read the consent flag without
# trusting the UI. Absent file / absent key both mean the default: off.
browse_enabled() {
  a shell run-as "$PKG" cat shared_prefs/clipsync.xml 2>/dev/null |
    grep -q 'name="browse_enabled" value="true"'
}
photos_granted() {
  a shell dumpsys package "$PKG" 2>/dev/null |
    grep -q 'READ_MEDIA_IMAGES: granted=true'
}
shizuku_running() { a shell pidof moe.shizuku.privileged.api >/dev/null 2>&1; }

# --- harness plumbing -------------------------------------------------------

# Write one verb. watchMirrorCmd polls once a second and deletes the file once read, so a
# second write landing before that poll silently replaces the first — wait for the consume.
send() {
  mkdir -p "$(dirname "$MIRROR_CMD")"
  # Rewrite rather than give up: a file still on disk was provably NOT consumed, so a resend
  # cannot double-fire. Observed for real — the first verb after startup was silently lost
  # because `clipsync: identity` prints before watchMirrorCmd's poller is running, and the
  # next write simply overwrote it. start_desktop now probes for the watcher too.
  for _ in 1 2 3; do
    printf '%s\n' "$*" >"$MIRROR_CMD"
    local w=0
    while [[ -f "$MIRROR_CMD" ]] && (( w < 8 )); do sleep 1; w=$((w + 1)); done
    [[ -f "$MIRROR_CMD" ]] || return 0
  done
  bad "desktop never consumed mirror-cmd.txt after 3 tries — is the app running? ($*)"
  rm -f "$MIRROR_CMD"
  return 1
}

# Wait for a line matching $1 in the desktop log, up to $2 seconds. Only lines appended
# after the marker taken at call time count, so a verb cannot be satisfied by an identical
# line from an earlier step.
expect() {
  local re="$1" secs="${2:-10}" from="$3" w=0
  while (( w < secs )); do
    if tail -n "+$from" "$DESKTOP_LOG" 2>/dev/null | grep -qE "$re"; then return 0; fi
    sleep 1; w=$((w + 1))
  done
  return 1
}

log_mark() { wc -l <"$DESKTOP_LOG" 2>/dev/null | tr -d ' ' || echo 0; }
log_since() { tail -n "+$1" "$DESKTOP_LOG" 2>/dev/null; }

# One verb, one expected reply, one assertion line. Returns non-zero on failure so a step
# that depends on the previous one can bail rather than cascade misleading failures.
step() {
  local label="$1" verb="$2" re="$3" secs="${4:-10}" mark
  mark="$(log_mark)"; mark=$((mark + 1))
  send "$verb" || return 1
  if expect "$re" "$secs" "$mark"; then
    ok "$label — $(log_since "$mark" | grep -E "$re" | head -1 | sed 's/^clipsync: //')"
  else
    bad "$label — no line matching /$re/ within ${secs}s"
    log_since "$mark" | grep -E 'clipsync: (fs|media)' | tail -3 | while read -r l; do info "saw: $l"; done
    return 1
  fi
}

# Ask for a tap, then WAIT FOR THE STATE rather than for a keypress. `read` returns 1 at EOF,
# which under `set -e` exits the script outright — and stdin is not a TTY whenever this runs
# from an agent session, nohup, or cron, which is the likeliest way it gets driven. Polling the
# real flag also removes the "you said you did it but the flag disagrees" branch entirely.
# $1 = message, $2 = predicate command, $3 = "on"|"off" — the value the predicate must reach.
prompt_until() {
  local msg="$1" pred="$2" want="$3" w=0 now
  action "$msg"
  while (( w < 180 )); do
    if "$pred"; then now="on"; else now="off"; fi
    [[ "$now" == "$want" ]] && return 0
    sleep 3; w=$((w + 3))
  done
  return 1
}

# `|| true` throughout: under `set -o pipefail` a grep that matches nothing, or an adb call
# on a dropped transport, makes the assignment itself fatal — and those are precisely the
# states an assertion needs to survive in order to REPORT them.
phone_sha() { a shell sha256sum "$1" 2>/dev/null | awk '{print $1}' | tr -d '\r' || true; }
mac_sha()   { shasum -a 256 "$1" 2>/dev/null | awk '{print $1}' || true; }

# --- preflight --------------------------------------------------------------

cmd_preflight() {
  # Its own file: preflight is routinely run AFTER a run (to re-check state), and sharing
  # $RESULTS would silently replace that run's verdict with preflight's.
  RESULTS="$RUN_DIR/preflight.txt"
  : >"$RESULTS"
  head1 "Device"
  if [[ -z "$PHONE" ]]; then
    bad "no phone on adb"
    info "connect with: ~/.claude/skills/android-device/scripts/adb-wifi.sh connect"
    info "everything below needs the phone; the rest of this preflight still runs"
  else
    ok "phone $PHONE ($(a shell getprop ro.product.model | tr -d '\r'), Android $(a shell getprop ro.build.version.release | tr -d '\r'))"
    if phone_locked; then
      action "phone is locked — unlock it (adb needs it, and the Files tab session is interactive)"
    else
      ok "phone unlocked"
    fi
    if a shell pm list packages 2>/dev/null | grep -q "package:$PKG"; then
      local vn want
      vn="$(a shell dumpsys package "$PKG" 2>/dev/null | awk -F= '/versionName=/{print $2; exit}' | tr -d '\r' || true)"
      # The phone must run the version THIS TREE builds — a pinned "0.4.0" here went stale
      # the first time the version bumped (0.4.1, M9.1) and failed a correct install.
      want="$(awk -F'"' '/versionName/{print $2; exit}' "$REPO/androidApp/build.gradle.kts")"
      if [[ "$vn" == "$want" ]]; then
        ok "clipsync $vn installed (matches this tree)"
      else
        bad "clipsync $vn installed, but this tree builds $want — install androidApp/build/outputs/apk/debug/androidApp-debug.apk"
        info "the browse card does not exist before 0.4.0, so on an older build every step below would refuse"
      fi
    else
      bad "clipsync not installed on the phone"
    fi
    if shizuku_running; then
      ok "Shizuku running"
    else
      bad "Shizuku is NOT running — the file bridge cannot bind, so every browse op fails closed"
      info "Shizuku stops on every reboot; start it from the Shizuku app (wireless debugging)"
    fi
    head1 "Consent (yours to give — this script never sets either)"
    if browse_enabled; then
      ok "'Let a paired Mac browse my files' is ON"
    else
      action "browse card is OFF — turn it on in clipsync on the phone before 'run'"
      info "it is off by default and gates every read and write; 'run' asserts the OFF state too"
    fi
    if photos_granted; then
      ok "READ_MEDIA_IMAGES granted (photo grid will have data)"
    else
      action "READ_MEDIA_IMAGES not granted — the grid will correctly say so; grant it when asked"
    fi
  fi

  head1 "Mac"
  if [[ -x "$DESKTOP_BIN" ]]; then
    ok "desktop app image built"
  else
    bad "desktop app image missing — ./gradlew :desktopApp:createDistributable"
  fi
  local live own
  live="$(desktop_pid)"
  own="$(harness_pid)"
  if [[ -n "$live" && "$live" != "$own" ]]; then
    action "a desktop clipsync this harness did not start is running (pid $live)"
    info "'run' will refuse: its stdout goes somewhere unreadable, and the assertions read the log"
  elif [[ -n "$live" ]]; then
    ok "desktop running, started by this harness (pid $live)"
  else
    ok "no desktop running — 'run' will start one"
  fi
  mkdir -p "$(dirname "$MIRROR_CMD")"
  if [[ -w "$(dirname "$MIRROR_CMD")" ]]; then
    ok "$(dirname "$MIRROR_CMD") writable (harness hook)"
  else
    bad "$(dirname "$MIRROR_CMD") not writable"
  fi

  head1 "Summary"
  info "$fails failed, $actions need you"
  (( fails == 0 )) || exit 1
}

# --- run --------------------------------------------------------------------

start_desktop() {
  local live own
  live="$(desktop_pid)"
  own="$(harness_pid)"
  if [[ -n "$live" && "$live" == "$own" ]]; then
    kill "$live" 2>/dev/null || true
    local w=0
    while [[ -n "$(desktop_pid)" ]] && (( w < 10 )); do sleep 1; w=$((w + 1)); done
    info "recycled the desktop this harness started (pid $live) so this run owns its log"
  elif [[ -n "$live" ]]; then
    bad "a desktop clipsync this harness did not start is running (pid $live) — quit it so this run owns its log"
    return 1
  fi
  [[ -x "$DESKTOP_BIN" ]] || { bad "desktop app image missing — ./gradlew :desktopApp:createDistributable"; return 1; }
  : >"$DESKTOP_LOG"
  # Real home on purpose: this run must use Eric's real identity and the existing S24
  # pairing. M6's isolated-home trick was for the emulator, where a throwaway identity was
  # the point; here a fresh identity would have no peer to talk to.
  "$DESKTOP_BIN" >>"$DESKTOP_LOG" 2>&1 &
  echo $! >"$RUN_DIR/desktop.pid"
  local w=0
  while (( w < 20 )); do
    grep -q 'clipsync: identity' "$DESKTOP_LOG" 2>/dev/null && break
    sleep 1; w=$((w + 1))
  done
  ok "desktop started (pid $(cat "$RUN_DIR/desktop.pid")), log → $DESKTOP_LOG"

  # `identity` is printed during boot; watchMirrorCmd is launched later and polls on a 1s
  # tick, so a verb sent between the two is written, never read, and then overwritten by the
  # next one — the step times out for a reason that has nothing to do with the phone. Prove
  # the watcher is live by sending something it is guaranteed to reject and waiting for the
  # rejection: an unrecognized verb reaches the same code path without sending anything.
  local mark; mark="$(log_mark)"; mark=$((mark + 1))
  printf 'harness-ping\n' >"$MIRROR_CMD"
  if expect 'clipsync: mirror-cmd unrecognized: harness-ping' 20 "$mark"; then
    ok "mirror-cmd watcher is live"
  else
    bad "the mirror-cmd watcher never answered a ping — every verb below would time out"
    rm -f "$MIRROR_CMD"
    return 1
  fi
}

# Neither Main.kt nor ConnectionManager prints anything on connect — `connectedPeers` is a
# StateFlow read by the status UI and nothing else — so there is no link-up line to grep for.
# What IS observable is the harness's own targeting: a browse verb answers either
# "no connected peer" or "sent=true". Poll with fs-roots, which is read-only and idempotent.
wait_for_peer() {
  local w=0 mark
  while (( w < 60 )); do
    mark="$(log_mark)"; mark=$((mark + 1))
    send "fs-roots" || return 1
    if expect 'clipsync: mirror-cmd fs-roots sent=true' 3 "$mark"; then
      ok "phone connected (fs-roots addressed a peer)"
      return 0
    fi
    if ! log_since "$mark" | grep -q 'fs-roots: no connected peer'; then
      action "fs-roots neither sent nor refused — unexpected, continuing"
      return 0
    fi
    sleep 3; w=$((w + 6))
  done
  bad "no peer connected after ${w}s — is clipsync running on the phone, on the same network?"
  return 1
}

cmd_run() {
  require_phone
  : >"$RESULTS"

  head1 "0. Preconditions"
  if phone_locked; then bad "phone is locked — unlock it and re-run"; return 1; fi
  ok "phone unlocked"
  shizuku_running || { bad "Shizuku is not running — start it on the phone first"; return 1; }
  ok "Shizuku running"
  if ! browse_enabled; then
    bad "browse consent is OFF — turn on 'Let a paired Mac browse my files' in clipsync"
    info "this script will not set it for you: it is the consent surface under test"
    return 1
  fi
  ok "browse consent ON"

  # Restart the phone app, as the plan's Step 4 prescribes: the file bridge binds at
  # startup and only when consent is on, so an app that was running while the card was off
  # has no bound bridge and every op would fail closed for a reason unrelated to the test.
  a shell am force-stop "$PKG" >/dev/null 2>&1 || true
  a shell am start -n "$ACTIVITY" >/dev/null 2>&1 || true
  sleep 3
  ok "phone app restarted (bridge binds under the current consent state)"

  # Kill a logcat left over from an earlier run FIRST. It keeps an fd on the old inode while
  # `>` truncates, so the two interleave into a sparse, misleading evidence file — the exact
  # guard pairing-test.sh documents. An earlier run dying mid-way is not hypothetical.
  if [[ -f "$RUN_DIR/logcat.pid" ]]; then kill "$(cat "$RUN_DIR/logcat.pid")" 2>/dev/null || true; fi
  a logcat -c >/dev/null 2>&1 || true
  a logcat -v time >"$LOGCAT_LOG" 2>&1 &
  echo $! >"$RUN_DIR/logcat.pid"
  # Stop logcat however this run ends — including an early return — because a survivor is what
  # corrupts the NEXT run's evidence file. The desktop is deliberately NOT trapped: `ui` is the
  # next step and needs the window. `stop` quits it when you are done.
  trap stop_logcat EXIT

  start_desktop || return 1
  wait_for_peer

  head1 "Scratch"
  # Recreate from nothing so a re-run is idempotent: a prior run's b.txt makes the rename
  # step collide ('name already taken' — the engine refusing that is by design).
  a shell rm -rf "$SCRATCH_ABS" >/dev/null 2>&1 || true
  a shell mkdir -p "$SCRATCH_ABS" >/dev/null 2>&1 || true
  # A known-content source file for the pull, made on the phone so the pull is the only
  # thing that moves it across.
  a shell "head -c 300000 /dev/urandom > $SCRATCH_ABS/pull-me.bin" >/dev/null 2>&1 || true
  a shell "echo m9-rename-probe > $SCRATCH_ABS/a.txt" >/dev/null 2>&1 || true
  local src_sha; src_sha="$(phone_sha "$SCRATCH_ABS/pull-me.bin")"
  if [[ -n "$src_sha" ]]; then ok "scratch $SCRATCH_ABS ready (pull-me.bin ${src_sha:0:12}…)"; else bad "could not create the phone-side scratch files"; return 1; fi

  head1 "1. Roots"
  step "roots" "fs-roots" 'clipsync: fs roots: [0-9]+' 15 || true
  local roots_line; roots_line="$(grep -E 'clipsync: fs roots:' "$DESKTOP_LOG" | tail -1 || true)"
  if grep -qE 'fs roots: 7 ' <<<"$roots_line"; then
    ok "7 roots, as declared"
  elif grep -qE 'ok=false browsing disabled' <<<"$(tail -5 "$DESKTOP_LOG")"; then
    bad "roots refused: browsing disabled — the toggle went off mid-run"
    return 1
  else
    bad "expected 7 roots — got: ${roots_line:-<nothing>}"
  fi
  for r in internal download documents dcim pictures movies music; do
    grep -q "$r" <<<"$roots_line" || bad "root '$r' missing from the roots reply"
  done

  head1 "2. Listing"
  step "list download" "fs-list download" 'clipsync: fs entries download/:' 15 || true
  step "list scratch" "fs-list download $SCRATCH_REL" "clipsync: fs entries download/$SCRATCH_REL:" 15 || true
  local scratch_line; scratch_line="$(grep -E "fs entries download/$SCRATCH_REL:" "$DESKTOP_LOG" | tail -1 || true)"
  grep -q 'pull-me.bin' <<<"$scratch_line" && ok "pull-me.bin visible in the listing" || bad "pull-me.bin not in the scratch listing"

  head1 "3. Pull (phone → Mac)"
  rm -f "$RECV_DIR/pull-me.bin"
  send "fs-pull download $SCRATCH_REL/pull-me.bin" || true
  local w=0
  while (( w < 30 )); do [[ -f "$RECV_DIR/pull-me.bin" ]] && break; sleep 1; w=$((w + 1)); done
  if [[ -f "$RECV_DIR/pull-me.bin" ]]; then
    local got; got="$(mac_sha "$RECV_DIR/pull-me.bin")"
    if [[ "$got" == "$src_sha" ]]; then ok "pull landed in $RECV_DIR, sha256 identical (${got:0:12}…)"
    else bad "pull landed but sha256 differs (phone ${src_sha:0:12}… vs mac ${got:0:12}…)"; fi
  else
    bad "pull never landed in $RECV_DIR within ${w}s"
    grep -E 'clipsync: fs pull' "$DESKTOP_LOG" | tail -2 | while read -r l; do info "saw: $l"; done
  fi

  head1 "4. Push (Mac → phone)"
  local pushfile="$RUN_DIR/push-me.bin"
  # fs-push fuses "<dir> <localfile>" into one field and splits on the LAST space, so a repo
  # checked out under a path containing a space would silently send a truncated filename
  # rather than fail. Say so instead of reporting a confusing push failure.
  if [[ "$pushfile" == *" "* ]]; then
    bad "repo path contains a space ($pushfile) — fs-push splits on the last space and would mis-parse it"
    return 1
  fi
  head -c 250000 /dev/urandom >"$pushfile"
  local push_sha; push_sha="$(mac_sha "$pushfile")"
  step "push" "fs-push download $SCRATCH_REL $pushfile" 'clipsync: fs push ok=true' 15 || true
  if expect 'clipsync: fs-push: sent=true' 30 1; then
    local landed; landed="$(phone_sha "$SCRATCH_ABS/push-me.bin")"
    if [[ "$landed" == "$push_sha" ]]; then ok "push landed at $SCRATCH_ABS/push-me.bin, sha256 identical (${landed:0:12}…)"
    elif [[ -z "$landed" ]]; then bad "push reported sent, but nothing at $SCRATCH_ABS/push-me.bin"
    else bad "push landed but sha256 differs (mac ${push_sha:0:12}… vs phone ${landed:0:12}…)"; fi
  else
    bad "push never reported sent=true"
    grep -E 'clipsync: fs-push' "$DESKTOP_LOG" | tail -3 | while read -r l; do info "saw: $l"; done
  fi

  head1 "5. Delete (trash-first)"
  step "delete" "fs-delete download $SCRATCH_REL/push-me.bin" 'clipsync: fs delete ok=true' 15 || true
  if [[ -z "$(phone_sha "$SCRATCH_ABS/push-me.bin")" ]]; then ok "gone from $SCRATCH_ABS"; else bad "still present at $SCRATCH_ABS/push-me.bin after delete"; fi
  # BrowseEngine stamps every trash entry yyyyMMdd-HHmmss-<name> (collision-proof, see
  # BrowseEngine.onDelete), so look for the newest stamped entry, not the bare name.
  local trashname
  trashname="$(a shell ls -t "$TRASH_ABS" 2>/dev/null | tr -d '\r' |
    grep -E -- '^[0-9]{8}-[0-9]{6}-push-me\.bin$' | head -1 || true)"
  local trashed=""
  [[ -n "$trashname" ]] && trashed="$(phone_sha "$TRASH_ABS/$trashname")"
  if [[ "$trashed" == "$push_sha" ]]; then
    ok "moved to $TRASH_ABS/$trashname, bytes intact — trash-first, not erased"
  elif [[ -n "$trashed" ]]; then
    bad "in the trash but the bytes changed (${trashed:0:12}… vs ${push_sha:0:12}…)"
  else
    bad "not found in $TRASH_ABS — a delete that erases would be a spec violation"
    a shell ls -la "$TRASH_ABS" 2>/dev/null | head -5 | while read -r l; do info "trash: $l"; done
  fi

  head1 "6. Rename"
  step "rename" "fs-rename download $SCRATCH_REL/a.txt b.txt" 'clipsync: fs rename ok=true' 15 || true
  if a shell test -f "$SCRATCH_ABS/b.txt" 2>/dev/null; then ok "renamed in place → b.txt"; else bad "b.txt not present after rename"; fi
  if a shell test -f "$SCRATCH_ABS/a.txt" 2>/dev/null; then bad "a.txt still present after rename"; else ok "a.txt gone"; fi

  head1 "7. The consent gate (the one that matters)"
  if ! prompt_until "turn OFF 'Let a paired Mac browse my files' on the phone (waiting up to 3 min…)" browse_enabled off; then
    bad "the flag never went OFF — skipping the refusal assertion rather than faking it"
  else
    ok "consent flag reads OFF"
    local mark; mark="$(log_mark)"; mark=$((mark + 1))
    send "fs-list download" || true
    if expect 'clipsync: fs list ok=false browsing disabled' 15 "$mark"; then
      ok "refused with the exact reason the Files tab displays: 'browsing disabled'"
    else
      bad "no 'browsing disabled' refusal — the gate or its reply text has drifted"
      log_since "$mark" | grep -E 'clipsync: fs' | tail -3 | while read -r l; do info "saw: $l"; done
    fi
    if log_since "$mark" | grep -qE 'clipsync: fs entries'; then
      bad "a listing arrived while browsing was disabled — the gate leaks"
    else
      ok "no listing arrived while disabled"
    fi
  fi
  if prompt_until "turn the browse card back ON (waiting up to 3 min…)" browse_enabled on; then
    ok "consent flag reads ON again"
  else
    action "still reads OFF — 'media' below will refuse for that reason, not a real one"
  fi

  head1 "8. Photos"
  if photos_granted; then
    step "media" "media" 'clipsync: media items: [0-9]+' 20 || true
    local items; items="$(grep -E 'clipsync: media items:' "$DESKTOP_LOG" | tail -1 | grep -oE '[0-9]+$' || true)"
    if [[ -n "$items" && "$items" -gt 0 ]]; then ok "$items media items returned"; else action "0 media items — real if this phone has no photos, suspicious otherwise"; fi
  else
    step "media (permission denied path)" "media" 'clipsync: fs media ok=false photo permission not granted' 20 || true
    info "this is the denied path on purpose — grant READ_MEDIA_IMAGES and re-run for the data path"
  fi

  head1 "Summary"
  info "$fails failed, $actions needed you"
  info "results: $RESULTS   desktop log: $DESKTOP_LOG   logcat: $LOGCAT_LOG"
  info "next: '$0 ui' for the four Files-tab states, which need a mouse"
  (( fails == 0 )) || exit 1
}

# --- ui ---------------------------------------------------------------------

# Items 8-11 of the plan. clipsync is a menu-bar app: its window does not exist until the
# status item is clicked, so no automated run in M9 ever saw the Files tab. Task 11's
# controller tried and recorded the negative result (window count 0) rather than fake it.
# These four states are therefore the only part of M9 that has never been seen by anything.
cmd_ui() {
  head1 "Files tab — the four states nothing has ever seen"
  info "Click the clipsync status item, open the Files tab, and check each state."
  info "Screenshot each one; they are the evidence for the m9 tag."
  cat <<'EOS'

  8. NOT PAIRED / PEER OFFLINE
     Turn Wi-Fi off on the phone (or quit clipsync there), then open the Files tab.
     EXPECT: the no-roots message. NOT a blank pane.

  9. BROWSING DISABLED
     Phone: turn OFF "Let a paired Mac browse my files". Back on the Mac, refresh.
     EXPECT, verbatim: "The phone refused: browsing disabled."
     If it reads generically, the signal three tasks were spent plumbing is broken.

 10. PHOTOS DENIED, BROWSING ON  (the highest-value screenshot of the set)
     Phone: browse card ON. Then revoke the photo permission:
       adb shell pm revoke ca.beric.clipsync android.permission.READ_MEDIA_IMAGES
     Open the Photos grid.
     EXPECT: "The phone refused: photo permission not granted."
     AND the file tree must still work. This is exactly the case the roots-empty branch
     missed — roots are permission-independent, so the early return never fires and the
     grid used to render blank with no message at all.

 11. FULLY WORKING
     Re-grant: adb shell pm grant ca.beric.clipsync android.permission.READ_MEDIA_IMAGES
     - grid renders thumbnails PAST the 24th item (scroll): MediaQuery returns up to 60 and
       each ThumbQuery carries 24, so items 25+ are what prove the backfill loop runs
     - a folder listing renders
     - a pull lands in ~/Downloads/clipsync
     - the delete dialog names the right count and says items MOVE TO THE TRASH rather
       than being erased

  ALSO, one deliberate attempt: navigate quickly between two directories that both contain
  a same-named file, and confirm the listing matches the breadcrumb. That is the
  stale-listing guard; the failure it prevents is a delete hitting the wrong file.

  KNOWN, EXPECTED, NOT BUGS (parked in the M9 ledger):
    - a listing over 2000 entries is silently capped, with no indicator (R5)
    - opening the tab before the phone connects leaves it empty with no retry —
      switch tabs and back to retry (R10)
    - with two phones connected the tab targets whichever connected first (R9)
EOS
}

# --- verify / evidence / logs / stop / clean --------------------------------

cmd_verify() {
  [[ -f "$RESULTS" ]] || { bad "no results — run '$0 run' first"; exit 1; }
  head1 "Last run"
  local p f
  p="$(grep -c '^PASS' "$RESULTS" || true)"; f="$(grep -c '^FAIL' "$RESULTS" || true)"
  grep '^FAIL' "$RESULTS" | sed 's/^FAIL /  ✗ /' || true
  info "$p passed, $f failed"
  [[ "$f" == "0" ]]
}

cmd_evidence() {
  [[ -f "$RESULTS" ]] || { bad "no results — run '$0 run' first"; exit 1; }
  head1 "M9 on-device evidence"
  info "phone:   ${PHONE:-<none>} $( [[ -n "$PHONE" ]] && a shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
  info "app:     $( [[ -n "$PHONE" ]] && a shell dumpsys package "$PKG" 2>/dev/null | awk -F= '/versionName=/{print $2; exit}' | tr -d '\r')"
  info "date:    $(date '+%Y-%m-%d %H:%M')"
  echo
  grep -E '^(PASS|FAIL)' "$RESULTS" | sed 's/^PASS/  ✓/; s/^FAIL/  ✗/' || info "(no assertions recorded)"
  echo
  info "Paste into HANDOFF.md's M9 row once the Files-tab states are screenshotted too."
}

cmd_logs() {
  head1 "desktop"; tail -30 "$DESKTOP_LOG" 2>/dev/null || info "no desktop log"
  head1 "logcat (clipsync)"; grep -iE 'clipsync|shizuku' "$LOGCAT_LOG" 2>/dev/null | tail -30 || info "no logcat"
}

# Separate from cmd_stop because the EXIT trap in `run` wants this and NOT the desktop kill.
stop_logcat() {
  if [[ -f "$RUN_DIR/logcat.pid" ]]; then
    kill "$(cat "$RUN_DIR/logcat.pid")" 2>/dev/null || true
    rm -f "$RUN_DIR/logcat.pid"
  fi
}

cmd_stop() {
  local own
  own="$(harness_pid)"
  if [[ -n "$own" ]] && kill "$own" 2>/dev/null; then ok "stopped the desktop this harness started (pid $own)"; else info "no harness-started desktop running"; fi
  stop_logcat
  rm -f "$RUN_DIR/desktop.pid"
  info "the phone is left exactly as it is — consent flags included"
}

# Deliberately does NOT empty .clipsync-trash: no auto-purge is the shipped promise, and a
# harness that quietly erased trashed files would be contradicting the thing it just tested.
cmd_clean() {
  require_phone
  a shell rm -rf "$SCRATCH_ABS" >/dev/null 2>&1 && ok "removed $SCRATCH_ABS" || bad "could not remove $SCRATCH_ABS"
  rm -f "$RUN_DIR/push-me.bin" "$RECV_DIR/pull-me.bin"
  ok "removed local scratch files"
  info "left alone on purpose: $TRASH_ABS (no auto-purge is the shipped promise)"
  info "  inspect with: adb -s $PHONE shell ls -la $TRASH_ABS"
}

# --- selftest ---------------------------------------------------------------

# Runs today, with no phone and no desktop. The assertion engine is the part that can fail
# SILENTLY — an expect() that matches a line from an earlier step reports a pass nobody
# performed, which is worth more to catch than any single checklist item.
cmd_selftest() {
  local saved="$DESKTOP_LOG"
  DESKTOP_LOG="$(mktemp -t m9-selftest)"
  RESULTS="$RUN_DIR/selftest.txt"
  : >"$RESULTS"

  head1 "Assertion engine"
  printf 'clipsync: fs roots: 7 [internal,download]\n' >"$DESKTOP_LOG"
  local mark; mark="$(log_mark)"; mark=$((mark + 1))

  # The line above the mark must NOT satisfy a later step. This is the false-pass case.
  if expect 'clipsync: fs roots: [0-9]+' 1 "$mark"; then
    bad "expect() matched a line from BEFORE the mark — every step could false-pass"
  else
    ok "expect() ignores lines before the mark"
  fi

  printf 'clipsync: fs roots: 7 [internal,download]\n' >>"$DESKTOP_LOG"
  if expect 'clipsync: fs roots: [0-9]+' 2 "$mark"; then
    ok "expect() matches a line after the mark"
  else
    bad "expect() missed a line it should have matched"
  fi

  if expect 'clipsync: fs entries' 1 "$mark"; then
    bad "expect() matched a pattern that is not in the log"
  else
    ok "expect() times out on a pattern that never arrives"
  fi

  head1 "Guards"
  # The three bugs found while writing this script, pinned so they cannot come back.
  local missing; missing="$(grep -E 'nothing-matches-this' "$DESKTOP_LOG" | tail -1 || true)"
  [[ -z "$missing" ]] && ok "a grep miss does not abort the run (pipefail guard)" || bad "grep-miss guard broken"
  local nopid; nopid="$(desktop_pid)"
  ok "desktop_pid survives no-match (got '${nopid:-<empty>}')"
  local nosha; nosha="$(mac_sha /definitely/not/here)"
  [[ -z "$nosha" ]] && ok "mac_sha on a missing file returns empty, does not abort" || bad "mac_sha guard broken"

  # The consent prompts must never block on stdin: `read` returns 1 at EOF, which under set -e
  # exits mid-run — AFTER the destructive steps — and stdin is not a TTY when this is driven
  # from an agent session, nohup or cron, which is the likeliest way it gets run at all.
  if prompt_until "selftest: predicate already satisfied" true on </dev/null; then
    ok "prompt_until returns on state, never waits for a keypress"
  else
    bad "prompt_until did not return on an already-satisfied predicate"
  fi
  if grep -qE '^\s*read ' "$0"; then
    bad "a bare 'read' is back in the script — it exits under set -e when stdin is not a TTY"
  else
    ok "no bare 'read' anywhere in the script"
  fi

  rm -f "$DESKTOP_LOG"
  DESKTOP_LOG="$saved"
  head1 "Summary"
  info "$fails failed"
  (( fails == 0 )) || exit 1
}

case "$CMD" in
  preflight) cmd_preflight ;;
  selftest)  cmd_selftest ;;
  run)       cmd_run ;;
  ui)        cmd_ui ;;
  verify)    cmd_verify ;;
  evidence)  cmd_evidence ;;
  logs)      cmd_logs ;;
  stop)      cmd_stop ;;
  clean)     cmd_clean ;;
  *) printf 'usage: %s {preflight|run|ui|verify|evidence|logs|stop|clean|selftest}\n' "$0"; exit 2 ;;
esac
