#!/usr/bin/env bash
#
# clipsync — on-device pairing test harness.
#
# Drives the one milestone that cannot be proven on an emulator: a real phone
# scanning the desktop's pairing QR with its camera, both sides deriving the
# same per-pair key, and the short auth string (SAS) matching on both screens.
#
#   ./scripts/pairing-test.sh preflight   # is everything ready? (safe, read-only)
#   ./scripts/pairing-test.sh reset       # clear pairing state on both sides
#   ./scripts/pairing-test.sh run         # launch both apps, wait for the scan
#   ./scripts/pairing-test.sh verify      # assert the success criteria
#   ./scripts/pairing-test.sh evidence    # screenshot both screens (SAS record)
#   ./scripts/pairing-test.sh sync        # bonus: text round-trip (needs Shizuku)
#   ./scripts/pairing-test.sh logs        # tail both logs
#
# The only manual steps are the ones a tool genuinely cannot do: unlocking the
# phone, granting the camera permission, pointing the camera at the Mac screen,
# and eyeballing that the two six-digit codes match.
#
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG="ca.beric.clipsync"
ACTIVITY="$PKG/ca.beric.clipsync.android.MainActivity"
PORT=47653

RUN_DIR="$REPO/build/pairing-test"
DESKTOP_LOG="$RUN_DIR/desktop.log"
LOGCAT_LOG="$RUN_DIR/logcat.log"
PHONE_DB_COPY="$RUN_DIR/phone-clipsync.db"
DESKTOP_BIN="$REPO/desktopApp/build/compose/binaries/main/app/clipsync.app/Contents/MacOS/clipsync"
DESKTOP_APP_DIR="$HOME/Library/Application Support/clipsync"
DESKTOP_DB="$DESKTOP_APP_DIR/history.db"
PEER_PAYLOAD="$HOME/.clipsync/peer-payload.txt"
APK="$REPO/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
CMD="${1:-preflight}"

# Created once, here, rather than per-subcommand: 'reset' and 'sync' also write
# $PHONE_DB_COPY, and a redirect into a missing dir fails *silently inside an if-
# condition* — 'reset' then reports "phone peers: 0" without having read the DB.
mkdir -p "$RUN_DIR"

fails=0
actions=0
ok()     { printf '  \033[32m✓\033[0m %s\n' "$*"; }
bad()    { printf '  \033[31m✗\033[0m %s\n' "$*"; fails=$((fails + 1)); }
action() { printf '  \033[33m→\033[0m %s\n' "$*"; actions=$((actions + 1)); }
info()   { printf '    %s\n' "$*"; }
head1()  { printf '\n\033[1m%s\033[0m\n' "$*"; }

# --- device -----------------------------------------------------------------

# The phone shows up on adb up to three times (LAN, tailnet, mDNS) alongside an
# emulator. Prefer a private-LAN transport: this test exercises LAN discovery and
# dial, so routing adb itself over the tailnet would muddy what is being observed.
# But "prefer" must mean fall back, not refuse: the LAN transport regularly sits in
# state 'offline' while the tailnet one is live, and a USB serial carries no IP at
# all — refusing those makes the whole harness unusable on a connected phone.
resolve_phone() {
  if [[ -n "${CLIPSYNC_PHONE:-}" ]]; then echo "$CLIPSYNC_PHONE"; return; fi
  local list; list="$(adb devices -l 2>/dev/null || true)"
  awk '/model:SM_/ && $2 == "device" && /^(192\.168\.|10\.|172\.(1[6-9]|2[0-9]|3[01])\.)/ { print $1; exit }' <<<"$list" |
    grep . ||
    awk '/model:SM_/ && $2 == "device" { print $1; exit }' <<<"$list"
}

PHONE="$(resolve_phone || true)"
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
# The Mac's Wi-Fi is not always en0 (docked Ethernet, Intel Macs); ask the routing
# table which interface actually carries the default route.
mac_ip()       { ipconfig getifaddr "$(route -n get default 2>/dev/null | awk '/interface:/{print $2; exit}')" 2>/dev/null || true; }
# `|| true`: with pipefail an offline transport makes adb's status the pipeline's,
# which would abort the caller's assignment under set -e with no message at all.
phone_ip()     { a shell ip -4 addr show wlan0 2>/dev/null | awk '/inet /{sub(/\/.*/,"",$2); print $2; exit}' || true; }
desktop_pid()  { pgrep -f 'clipsync.app/Contents/MacOS/clipsync' 2>/dev/null | head -1; }
# Single-quoted SQL literals: double quotes are identifiers in SQLite and only work
# here by way of a legacy fallback. One query, not a count followed by a select.
peer_count()   { sqlite3 "$1" 'select count(*) from peer' 2>/dev/null || echo 0; }
peer_row()     { sqlite3 "$1" "select device_name||' / '||device_id||' @ '||addresses from peer" 2>/dev/null || true; }

# --- preflight --------------------------------------------------------------

cmd_preflight() {
  require_phone
  head1 "Device"
  ok "phone $PHONE ($(a shell getprop ro.product.model | tr -d '\r'), Android $(a shell getprop ro.build.version.release | tr -d '\r'))"

  if phone_locked; then
    action "phone is locked — unlock it (the camera scan needs the screen anyway)"
  else
    ok "phone unlocked"
  fi

  local mip pip
  mip="$(mac_ip)"; pip="$(phone_ip | tr -d '\r' || true)"
  if [[ -n "$mip" && -n "$pip" && "${mip%.*}" == "${pip%.*}" ]]; then
    ok "same LAN subnet (mac $mip, phone $pip)"
  else
    bad "mac ($mip) and phone ($pip) are not on the same /24 — mDNS discovery cannot work"
  fi

  head1 "Apps"
  if a shell pm list packages 2>/dev/null | grep -q "package:$PKG"; then
    ok "clipsync installed on phone"
  else
    action "clipsync not installed — 'run' will install it (or: adb -s $PHONE install -r $APK)"
  fi

  # Shizuku is not needed to pair, but is needed for any clipboard round-trip.
  if a shell pm list packages 2>/dev/null | grep -q 'moe.shizuku.privileged.api'; then
    ok "Shizuku installed"
  else
    action "Shizuku NOT installed — pairing will still work; 'sync' will not."
    info "install from https://shizuku.rikka.app/ then start it via wireless debugging"
  fi

  if [[ -x "$DESKTOP_BIN" ]]; then
    ok "desktop app image built"
  else
    bad "desktop app image missing — ./gradlew :desktopApp:createDistributable"
  fi

  head1 "State"
  # Blocking, not advisory: 'run' truncates DESKTOP_LOG and a live desktop never
  # reprints its startup line, so it refuses outright on one it did not start.
  if [[ -n "$(desktop_pid)" ]]; then
    bad "a desktop clipsync is already running (pid $(desktop_pid)) — quit it; 'run' cannot read its stdout"
  else
    ok "no stale desktop clipsync running"
  fi

  local peers=0
  [[ -f "$DESKTOP_DB" ]] && peers="$(peer_count "$DESKTOP_DB")"
  if [[ "$peers" == "0" ]]; then
    ok "desktop has no stored peers (clean pairing test)"
  else
    action "desktop already has $peers peer(s) — run 'reset' so the test cannot pass on stale state"
  fi
  if [[ -s "$PEER_PAYLOAD" ]]; then
    action "$PEER_PAYLOAD exists and is polled — 'reset' removes it (it would re-pair the old peer)"
  else
    ok "no stale peer-payload.txt"
  fi

  head1 "Summary"
  printf '  %d blocking, %d needing you\n' "$fails" "$actions"
  [[ $fails -eq 0 ]] || return 1
}

# --- reset ------------------------------------------------------------------

cmd_reset() {
  require_phone
  head1 "Reset pairing state"
  # The desktop polls this file and re-pairs from it, so a survivor is a silent
  # false-pass source — report the removal on observed state, not on rm's status.
  rm -f "$PEER_PAYLOAD" || true
  if [[ -e "$PEER_PAYLOAD" ]]; then
    bad "could not remove $PEER_PAYLOAD — the desktop would re-pair the old peer from it"
  else
    ok "no peer-payload.txt (the desktop's file-pairing bootstrap is clear)"
  fi

  if [[ -f "$DESKTOP_DB" ]]; then
    [[ -z "$(desktop_pid)" ]] || { bad "quit the desktop clipsync first (pid $(desktop_pid))"; return 1; }
    sqlite3 "$DESKTOP_DB" 'delete from peer; delete from history;' && ok "cleared desktop peer + history rows"
  else
    ok "no desktop DB yet"
  fi

  # Surgical by default: keep the TLS identity and Keychain secret so the cert
  # fingerprint stays stable (that stability is itself an M5 guarantee).
  # --hard wipes app data, which also revokes the Shizuku grant.
  # Stop the app first: SQLDelight holds the DB open, and deleting the file out from
  # under a live process leaves it free to recreate and repopulate it immediately.
  if a shell am force-stop "$PKG" >/dev/null; then ok "stopped the phone app"
  else bad "could not force-stop $PKG on $PHONE — the phone was not reset"; return 1; fi

  if [[ "${1:-}" == "--hard" ]]; then
    a shell pm clear "$PKG" >/dev/null && ok "phone app data cleared (Shizuku grant must be re-given)" ||
      bad "pm clear failed"
  else
    a shell run-as "$PKG" rm -f databases/clipsync.db databases/clipsync.db-journal >/dev/null 2>&1 &&
      ok "phone DB removed (identity + peers regenerate on next launch)" ||
      bad "run-as rm failed — the phone DB is still there (is this the debug build?)"
    info "use 'reset --hard' to also drop the phone's TLS identity"
  fi

  # Report on observed state, not on the exit status of the delete.
  head1 "Confirming both sides are clean"
  local dp=0 pp=0
  [[ -f "$DESKTOP_DB" ]] && dp="$(peer_count "$DESKTOP_DB")"
  [[ "$dp" == "0" ]] && ok "desktop peers: 0" || bad "desktop still has $dp peer(s)"
  # "Could not read it" is not "it is clean". Probe run-as first, so a denied
  # sandbox (release build, wrong package) fails loudly instead of green-lighting
  # a phone whose peer row is still sitting there.
  if ! a shell run-as "$PKG" true >/dev/null 2>&1; then
    bad "run-as denied for $PKG — cannot confirm the phone is clean (needs the debug build)"
  elif a exec-out run-as "$PKG" cat databases/clipsync.db > "$PHONE_DB_COPY" 2>/dev/null && [[ -s "$PHONE_DB_COPY" ]]; then
    pp="$(peer_count "$PHONE_DB_COPY")"
    [[ "$pp" == "0" ]] && ok "phone peers: 0" || bad "phone still has $pp peer(s)"
  else
    ok "phone peers: 0 (no DB — regenerates on next launch)"
  fi
  [[ $fails -eq 0 ]] || return 1
}

# --- run --------------------------------------------------------------------

cmd_run() {
  require_phone

  # Preconditions BEFORE the logs are wiped: every early return below used to destroy
  # the previous run's SAS record on its way out, while still stamping a run-id that
  # made 'verify' believe a run had happened.
  head1 "1. Phone app"
  if a shell pm list packages 2>/dev/null | grep -q "package:$PKG"; then
    ok "clipsync installed on phone"
  elif [[ -f "$APK" ]]; then
    info "installing…"; a install -r "$APK" >/dev/null; ok "installed $APK"
  else
    bad "clipsync not installed and no APK at $APK — ./gradlew :androidApp:assembleDebug"
    return 1
  fi
  if phone_locked; then
    a shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
    bad "phone is locked — unlock it and re-run 'run'"
    return 1
  fi
  # 'clipsync: identity' is printed once at startup, so a desktop that is already up
  # can never refill the freshly truncated DESKTOP_LOG. Recycle the one WE started
  # (same as the stale logcat below) and refuse only on a hand-started one, whose
  # stdout goes somewhere we cannot read.
  local live_desktop own_desktop=""
  live_desktop="$(desktop_pid)"
  [[ -f "$RUN_DIR/desktop.pid" ]] && own_desktop="$(cat "$RUN_DIR/desktop.pid")"
  if [[ -n "$live_desktop" && "$live_desktop" == "$own_desktop" ]]; then
    kill "$live_desktop" 2>/dev/null || true
    local w=0
    while [[ -n "$(desktop_pid)" ]] && (( w < 10 )); do sleep 1; w=$((w + 1)); done
    info "recycled the desktop this harness started (pid $live_desktop) so this run owns its log"
  elif [[ -n "$live_desktop" ]]; then
    bad "a desktop clipsync this harness did not start is running (pid $live_desktop) — quit it so this run owns its log"
    return 1
  fi
  [[ -x "$DESKTOP_BIN" ]] || { bad "desktop app image missing — ./gradlew :desktopApp:createDistributable"; return 1; }

  # A logcat left over from an earlier run appends to the same file and can inject a
  # different device's lines into this run's evidence.
  [[ -f "$RUN_DIR/logcat.pid" ]] && kill "$(cat "$RUN_DIR/logcat.pid")" 2>/dev/null || true

  # Both logs are truncated on every run, unconditionally. 'verify' greps them for
  # SAS=, so a line left over from a previous run would let it report PASS for a run
  # that never happened — the same stale-state trap 'preflight' guards for peer rows.
  : > "$LOGCAT_LOG"
  : > "$DESKTOP_LOG"
  date +%Y-%m-%dT%H:%M:%S > "$RUN_DIR/run-id"

  # `|| true`: `logcat -c` returns non-zero on plenty of devices ("failed to clear
  # the 'main' log"), and as a bare command under set -e that killed the whole run.
  a logcat -c >/dev/null 2>&1 || true
  # clipsyncApply/clipsyncShizuku are what explain a failed clipboard write — 'sync'
  # points the operator at Shizuku, so its log has to be in the capture.
  a logcat -v time clipsyncGraph:I clipsyncScan:I clipsyncPair:I clipsyncApply:I clipsyncShizuku:W clipsyncFg:I '*:S' \
    >> "$LOGCAT_LOG" 2>&1 &
  echo $! > "$RUN_DIR/logcat.pid"
  # startSync() runs once per process and the foreground service keeps that process
  # alive, so `am start` alone would never re-emit clipsync-payload into the log we
  # just truncated — this run needs its own startup lines.
  a shell am force-stop "$PKG" >/dev/null 2>&1 || true
  a shell am start -n "$ACTIVITY" >/dev/null
  ok "launched $ACTIVITY (logcat → $LOGCAT_LOG)"

  head1 "2. Desktop app"
  "$DESKTOP_BIN" >> "$DESKTOP_LOG" 2>&1 &
  echo $! > "$RUN_DIR/desktop.pid"
  ok "launched desktop (pid $(cat "$RUN_DIR/desktop.pid"), log → $DESKTOP_LOG)"

  head1 "3. Waiting for both sides to come up"
  local waited=0
  while (( waited < 45 )); do
    grep -q 'clipsync: identity' "$DESKTOP_LOG" 2>/dev/null &&
      grep -q 'clipsync-payload' "$LOGCAT_LOG" 2>/dev/null && break
    sleep 1; waited=$((waited + 1))
  done

  if grep -q 'clipsync: identity' "$DESKTOP_LOG" 2>/dev/null; then
    ok "desktop: $(grep -m1 'clipsync: identity' "$DESKTOP_LOG")"
  else
    bad "desktop never printed its identity — see $DESKTOP_LOG"
  fi

  # If Netty fails to bind on the phone, the payload it sends back carries no
  # addresses and the desktop can never dial it: pairing "succeeds" but sync
  # never connects. Catch that here rather than debugging the wrong layer later.
  local serving
  serving="$(grep -o 'serving=[a-z]*' "$LOGCAT_LOG" 2>/dev/null | tail -1 || true)"
  case "$serving" in
    serving=true)  ok "phone is serving on :$PORT (symmetric P2P)" ;;
    serving=false) bad "phone reports serving=false — it advertises no addresses; the desktop cannot dial back" ;;
    *)             bad "phone never printed its pairing payload — see $LOGCAT_LOG" ;;
  esac

  [[ $fails -eq 0 ]] || return 1

  head1 "4. Your turn — the part no script can do"
  cat <<EOF
    a. On the Mac: the clipsync window is open and showing a QR code.
       (If it is hidden, click the teal tray icon → "Open history & pairing".)
    b. On the phone: tap "Scan a device's QR to pair" and allow the camera.
    c. Point the phone at the QR on the Mac screen.
    d. Both screens then list the peer with a six-digit code. Check they MATCH.

    Then: $0 verify
EOF
}

# --- verify -----------------------------------------------------------------

cmd_verify() {
  require_phone
  head1 "Pairing criteria"

  # Only ever read THIS run's logs. Falling back to the device ring buffer would
  # reach across previous runs and could green-light a pairing that never happened.
  local phone_log="$LOGCAT_LOG"
  [[ -f "$RUN_DIR/run-id" ]] || { bad "no run recorded — use '$0 run' first"; return 1; }
  info "run started $(cat "$RUN_DIR/run-id")"
  [[ -s "$phone_log" ]] || { bad "phone log empty for this run — was the app launched by 'run'?"; return 1; }
  [[ -s "$DESKTOP_LOG" ]] || { bad "desktop log empty for this run — quit any hand-started desktop clipsync and re-run 'run'"; return 1; }

  if grep -q 'scan-pair ok=true' "$phone_log" 2>/dev/null; then
    ok "phone scanned the QR and accepted the payload (scan-pair ok=true)"
  elif grep -q 'scan-pair ok=false' "$phone_log" 2>/dev/null; then
    bad "phone scanned a QR but rejected the payload (scan-pair ok=false)"
  else
    bad "no camera scan recorded yet — do step 4 of 'run' first"
  fi

  local phone_sas desk_sas
  phone_sas="$(grep -oE 'SAS=[0-9]{6}' "$phone_log" 2>/dev/null | tail -1 | cut -d= -f2 || true)"
  # via=wire ONLY. The desktop prints a byte-identical line from the
  # ~/.clipsync/peer-payload.txt poller (via=file); accepting that would certify the
  # reciprocal-pairing gate on a run where nothing crossed the network.
  desk_sas="$(grep -oE 'via=wire .*SAS=[0-9]{6}' "$DESKTOP_LOG" 2>/dev/null | grep -oE 'SAS=[0-9]{6}' | tail -1 | cut -d= -f2 || true)"

  [[ -n "$phone_sas" ]] && ok "phone derived a per-pair key (SAS $phone_sas)" || bad "phone never derived a per-pair key"
  # The desktop has no camera: it only learns the phone's key if the phone sends
  # its payload back over the wire (reciprocal pairing). This line proves that ran.
  [[ -n "$desk_sas" ]] && ok "desktop paired over the wire (SAS $desk_sas) — reciprocal pairing worked" ||
    bad "desktop never paired over the wire (no via=wire line) — reciprocal pairing did not reach it"
  if grep -q 'via=file' "$DESKTOP_LOG" 2>/dev/null; then
    action "the desktop also paired from peer-payload.txt (via=file) — run 'reset' so that path cannot mask the wire one"
  fi

  if [[ -n "$phone_sas" && -n "$desk_sas" ]]; then
    if [[ "$phone_sas" == "$desk_sas" ]]; then
      ok "SAS MATCHES on both sides: $phone_sas"
      info "this is the number that must also match on the two screens"
    else
      bad "SAS MISMATCH — phone $phone_sas vs desktop $desk_sas (do not trust this pairing)"
    fi
  fi

  head1 "Stored state"
  # A missing DB is a failed check rather than a skipped one: an absent file used to
  # make the whole desktop block vanish from the report.
  local row
  if [[ -f "$DESKTOP_DB" ]]; then
    row="$(peer_row "$DESKTOP_DB")"
    [[ -n "$row" ]] && ok "desktop peer row: $row" || bad "no peer row in the desktop DB"
  else
    bad "no desktop DB at $DESKTOP_DB — the desktop persisted nothing"
  fi
  # No sqlite3 on the device, so pull the DB and read it here.
  if a exec-out run-as "$PKG" cat databases/clipsync.db > "$PHONE_DB_COPY" 2>/dev/null && [[ -s "$PHONE_DB_COPY" ]]; then
    row="$(peer_row "$PHONE_DB_COPY")"
    [[ -n "$row" ]] && ok "phone peer row: $row" || bad "no peer row in the phone DB"
  else
    info "could not pull the phone DB (needs the debug build)"
  fi

  head1 "Live connection"
  # The transport logs nothing, so assert at the TCP layer instead. The phone may
  # arrive over ANY of its advertised addresses — it dials the payload's address
  # list in order, so a tailnet hit before the LAN one is normal, not a failure.
  local addrs sockets hit=""
  # `|| true` on the trailing substitution: with serving=false the payload logs
  # "addr":[] , the IP grep matches nothing, and the bare assignment used to abort
  # the whole of verify under set -e — silently, right before the Result section.
  addrs="$(phone_ip | tr -d '\r' || true)
$(grep -o '"addr":\[[^]]*\]' "$phone_log" 2>/dev/null | tail -1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+' || true)"
  sockets="$(lsof -nP -iTCP:"$PORT" -sTCP:ESTABLISHED 2>/dev/null || true)"
  while read -r ip; do
    [[ -n "$ip" ]] || continue
    # Match the REMOTE side only, as a fixed string: an unanchored match hits the
    # Mac's own address in the same line, and 192.168.1.4 matches 192.168.1.45.
    grep -qF -- "->$ip:" <<<"$sockets" && { hit="$ip"; break; }
  done <<<"$addrs"

  if [[ -n "$hit" ]]; then
    ok "TLS link established with the phone at $hit on :$PORT"
    case "$hit" in
      100.*) info "arrived over the TAILNET — the LAN path was not exercised by this run" ;;
      *)     info "arrived over the LAN" ;;
    esac
  else
    bad "no established connection from any phone address on :$PORT (paired but not connected)"
    info "phone addresses tried: $(tr '\n' ' ' <<<"$addrs")"
  fi

  head1 "Result"
  if [[ $fails -eq 0 ]]; then
    printf '  \033[32mPAIRING VERIFIED\033[0m — m3 camera-scan gate is met.\n'
  else
    printf '  \033[31m%d check(s) failed\033[0m — see above.\n' "$fails"
    return 1
  fi
}

# --- sync (bonus) -----------------------------------------------------------

cmd_sync() {
  require_phone
  head1 "Mac → phone text round-trip"
  # The Mac-side watcher lives in the desktop process; without it nothing is captured
  # and the failure would be blamed on the phone.
  [[ -n "$(desktop_pid)" ]] || { bad "no desktop clipsync running — nothing is watching the Mac clipboard ('$0 run' first)"; return 1; }
  local token; token="clipsync-harness-$(date +%s)"
  action "this overwrites the Mac clipboard (and, if sync works, the phone's) with a throwaway token"
  printf '%s' "$token" | pbcopy
  ok "copied on Mac: $token"

  local waited=0 arrived=""
  while (( waited < 15 )); do
    if a exec-out run-as "$PKG" cat databases/clipsync.db > "$PHONE_DB_COPY" 2>/dev/null && [[ -s "$PHONE_DB_COPY" ]] &&
       sqlite3 "$PHONE_DB_COPY" 'select content from history' 2>/dev/null | grep -qF "$token"; then
      arrived=1; break
    fi
    sleep 1; waited=$((waited + 1))
  done

  if [[ -n "$arrived" ]]; then
    ok "phone received it after ${waited}s (row in the phone's history)"
  else
    bad "phone did not receive it within 15s"
    info "if the DB pull itself failed this is an adb/run-as problem, not a sync one"
  fi

  # A history row is NOT proof the clipboard was set: SyncEngine.applyRemoteClip records
  # the row and only THEN calls the applier, which logs ok=false and returns normally when
  # Shizuku is not running. Assert the applier's own verdict.
  local applied
  applied="$(grep -oE 'applyText len=[0-9]+ ok=(true|false)' "$LOGCAT_LOG" 2>/dev/null | tail -1 || true)"
  case "$applied" in
    *ok=true)  ok "phone's clipboard was actually set ($applied)" ;;
    *ok=false) bad "sync arrived but the clipboard write FAILED ($applied) — Shizuku is installed but not started/granted" ;;
    *)         action "no applyText line in $LOGCAT_LOG — cannot confirm the clipboard was set (run '$0 run' so logcat is capturing)" ;;
  esac

  head1 "Phone → Mac"
  info "set the phone's clipboard from another app (Shizuku required), then run:"
  info "  pbpaste"
  [[ $fails -eq 0 ]] || return 1
}

# --- misc -------------------------------------------------------------------

# Screenshots of both screens, as the human-facing record that the two SAS codes
# agree. The desktop capture goes by CGWindowID, not screen region: the tray window
# is easily occluded and a region grab silently captures whatever is on top of it.
cmd_evidence() {
  require_phone
  local stamp; stamp="$(date +%Y%m%d-%H%M%S)"

  if phone_locked; then
    bad "phone is locked — a screenshot now records the lock screen, not the SAS"
  elif a exec-out screencap -p > "$RUN_DIR/phone-$stamp.png" 2>/dev/null && [[ -s "$RUN_DIR/phone-$stamp.png" ]]; then
    ok "phone → $RUN_DIR/phone-$stamp.png"
  else
    bad "phone screenshot failed or was empty"
  fi

  # Pick the largest clipsync window: the app owns a Tray item as well as the main
  # window, and only the latter shows the SAS list. stderr is kept OUT of $wid — a
  # pyobjc deprecation warning must not turn a valid window number into a failure.
  local wid werr="$RUN_DIR/window-lookup.err"
  wid="$(uv run --quiet --with pyobjc-framework-Quartz python -c "
import Quartz
ws = [w for w in Quartz.CGWindowListCopyWindowInfo(
        Quartz.kCGWindowListOptionOnScreenOnly | Quartz.kCGWindowListExcludeDesktopElements,
        Quartz.kCGNullWindowID)
      if w.get('kCGWindowOwnerName') == 'clipsync']
if ws:
    b = lambda w: w['kCGWindowBounds']['Width'] * w['kCGWindowBounds']['Height']
    print(max(ws, key=b)['kCGWindowNumber'])
" 2>"$werr" | tail -1 || true)"

  if [[ "$wid" =~ ^[0-9]+$ ]]; then
    screencapture -x -o -l"$wid" "$RUN_DIR/desktop-$stamp.png" && ok "desktop → $RUN_DIR/desktop-$stamp.png" ||
      bad "screencapture failed for window $wid (stale id?) — no desktop evidence written"
  elif [[ -s "$werr" ]]; then
    # A toolchain problem (uv missing, offline pyobjc fetch), not a UI one.
    bad "window lookup failed (uv/pyobjc): $(tail -1 "$werr")"
  else
    action "no on-screen clipsync window — open it (tray icon → Open history & pairing); minimized windows are not listed"
  fi
  [[ $fails -eq 0 ]] || return 1
}

cmd_logs() {
  touch "$DESKTOP_LOG" "$LOGCAT_LOG"
  head1 "tailing $DESKTOP_LOG and $LOGCAT_LOG (ctrl-c to stop)"
  tail -f "$DESKTOP_LOG" "$LOGCAT_LOG"
}

cmd_stop() {
  if [[ -f "$RUN_DIR/logcat.pid" ]] && kill "$(cat "$RUN_DIR/logcat.pid")" 2>/dev/null; then
    ok "stopped background logcat (the apps are left running)"
  else
    info "no background logcat to stop (the apps are left running)"
  fi
  rm -f "$RUN_DIR/logcat.pid"
  # Report on the process, not on the pid file: a dead pid left on disk would read
  # as "still running" forever.
  if [[ -f "$RUN_DIR/desktop.pid" ]] && kill -0 "$(cat "$RUN_DIR/desktop.pid")" 2>/dev/null; then
    info "desktop clipsync left running as pid $(cat "$RUN_DIR/desktop.pid") — the next 'run' recycles it"
  else
    rm -f "$RUN_DIR/desktop.pid"
  fi
}

case "$CMD" in
  preflight) cmd_preflight ;;
  reset)     shift; cmd_reset "$@" ;;
  run)       cmd_run ;;
  verify)    cmd_verify ;;
  evidence)  cmd_evidence ;;
  sync)      cmd_sync ;;
  logs)      cmd_logs ;;
  stop)      cmd_stop ;;
  *) sed -n '3,20p' "${BASH_SOURCE[0]}"; exit 2 ;;
esac
