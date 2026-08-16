# lib.sh — helpers shared by the on-device harnesses (pairing-test.sh, m9-test.sh).
#
# Sourced, not executed. Callers must set REPO before sourcing (DESKTOP_BIN derives from
# it) and RUN_DIR before calling harness_pid. Setting RESULTS makes ok()/bad() also append
# PASS/FAIL lines there for a later 'verify'; leaving it unset skips the append.
#
# Extracted once the two scripts' copies started drifting for real: the pipefail guard on
# desktop_pid was fixed in m9-test.sh and hand-ported back, and ok()/bad() grew a $RESULTS
# append on one side only.

PKG="ca.beric.clipsync"
ACTIVITY="$PKG/ca.beric.clipsync.android.MainActivity"
DESKTOP_BIN="$REPO/desktopApp/build/compose/binaries/main/app/clipsync.app/Contents/MacOS/clipsync"

fails=0
actions=0
ok()     { printf '  \033[32m✓\033[0m %s\n' "$*"; [[ -z "${RESULTS:-}" ]] || echo "PASS $*" >>"$RESULTS"; }
bad()    { printf '  \033[31m✗\033[0m %s\n' "$*"; [[ -z "${RESULTS:-}" ]] || echo "FAIL $*" >>"$RESULTS"; fails=$((fails + 1)); }
action() { printf '  \033[33m→\033[0m %s\n' "$*"; actions=$((actions + 1)); }
info()   { printf '    %s\n' "$*"; }
head1()  { printf '\n\033[1m%s\033[0m\n' "$*"; }

# The phone shows up on adb up to three times (LAN, tailnet, mDNS) alongside an emulator.
# Prefer a private-LAN transport, but "prefer" must mean fall back, not refuse: the LAN
# transport regularly sits in state 'offline' while the tailnet one is live, and a USB
# serial carries no IP at all — refusing those makes a harness unusable on a connected phone.
resolve_phone() {
  if [[ -n "${CLIPSYNC_PHONE:-}" ]]; then echo "$CLIPSYNC_PHONE"; return; fi
  local list; list="$(adb devices -l 2>/dev/null || true)"
  awk '/model:SM_/ && $2 == "device" && /^(192\.168\.|10\.|172\.(1[6-9]|2[0-9]|3[01])\.)/ { print $1; exit }' <<<"$list" |
    grep . ||
    awk '/model:SM_/ && $2 == "device" { print $1; exit }' <<<"$list"
}

a() { adb -s "$PHONE" "$@"; }

require_phone() {
  if [[ -z "$PHONE" ]]; then
    bad "no phone on adb (looked for a transport with model:SM_* in state 'device')"
    info "connect with: ~/.claude/skills/android-device/scripts/adb-wifi.sh connect"
    info "or override:  CLIPSYNC_PHONE=<serial> $0 $CMD"
    exit 1
  fi
}

phone_locked() { a shell dumpsys window 2>/dev/null | grep -q 'isKeyguardShowing=true'; }

# `|| true` is load-bearing: pgrep exits 1 when nothing matches, and under `set -o pipefail`
# that becomes the pipeline's status, so `live="$(desktop_pid)"` would abort the caller
# under set -e in the ordinary case of no desktop running.
desktop_pid() { pgrep -f 'clipsync.app/Contents/MacOS/clipsync' 2>/dev/null | head -1 || true; }

# The set -e-safe desktop.pid read. Not `[[ -f … ]] && own="$(cat …)"` at a call site: as a
# bare statement that returns 1 when the file is absent, which set -e turns into a silent
# exit right there. The trailing `|| true` pins this function's status to 0 either way.
harness_pid() { [[ -f "$RUN_DIR/desktop.pid" ]] && cat "$RUN_DIR/desktop.pid" || true; }
