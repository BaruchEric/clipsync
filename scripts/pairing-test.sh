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

fails=0
actions=0
ok()     { printf '  \033[32m✓\033[0m %s\n' "$*"; }
bad()    { printf '  \033[31m✗\033[0m %s\n' "$*"; fails=$((fails + 1)); }
action() { printf '  \033[33m→\033[0m %s\n' "$*"; actions=$((actions + 1)); }
info()   { printf '    %s\n' "$*"; }
head1()  { printf '\n\033[1m%s\033[0m\n' "$*"; }

# --- device -----------------------------------------------------------------

# The phone shows up on adb up to three times (LAN, tailnet, mDNS) alongside an
# emulator. Prefer the LAN transport: this test exercises LAN discovery and dial,
# so routing adb itself over the tailnet would muddy what is being observed.
resolve_phone() {
  if [[ -n "${CLIPSYNC_PHONE:-}" ]]; then echo "$CLIPSYNC_PHONE"; return; fi
  adb devices -l 2>/dev/null |
    awk '/model:SM_/ && /^192\.168\./ && $2 == "device" { print $1; exit }'
}

PHONE="$(resolve_phone || true)"
a() { adb -s "$PHONE" "$@"; }

require_phone() {
  if [[ -z "$PHONE" ]]; then
    bad "no phone on adb (looked for a LAN transport with model:SM_*)"
    info "connect with: ~/.claude/skills/android-device/scripts/adb-wifi.sh connect"
    info "or override:  CLIPSYNC_PHONE=<serial> $0 $*"
    exit 1
  fi
}

phone_locked() { a shell dumpsys window 2>/dev/null | grep -q 'isKeyguardShowing=true'; }
mac_ip()       { ipconfig getifaddr en0 2>/dev/null || true; }
phone_ip()     { a shell ip -4 addr show wlan0 2>/dev/null | awk '/inet /{sub(/\/.*/,"",$2); print $2; exit}'; }
desktop_pid()  { pgrep -f 'clipsync.app/Contents/MacOS/clipsync' 2>/dev/null | head -1; }

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
  mip="$(mac_ip)"; pip="$(phone_ip | tr -d '\r')"
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
  if [[ -n "$(desktop_pid)" ]]; then
    action "a desktop clipsync is already running (pid $(desktop_pid)) — 'run' will reuse nothing; quit it first"
  else
    ok "no stale desktop clipsync running"
  fi

  local peers=0
  [[ -f "$DESKTOP_DB" ]] && peers="$(sqlite3 "$DESKTOP_DB" 'select count(*) from peer' 2>/dev/null || echo 0)"
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
  rm -f "$PEER_PAYLOAD" && ok "removed $PEER_PAYLOAD"

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
  a shell am force-stop "$PKG" >/dev/null && ok "stopped the phone app"

  if [[ "${1:-}" == "--hard" ]]; then
    a shell pm clear "$PKG" >/dev/null && ok "phone app data cleared (Shizuku grant must be re-given)"
  else
    a shell run-as "$PKG" rm -f databases/clipsync.db databases/clipsync.db-journal &&
      ok "phone DB removed (identity + peers regenerate on next launch)"
    info "use 'reset --hard' to also drop the phone's TLS identity"
  fi

  # Report on observed state, not on the exit status of the delete.
  head1 "Confirming both sides are clean"
  local dp=0 pp=0
  [[ -f "$DESKTOP_DB" ]] && dp="$(sqlite3 "$DESKTOP_DB" 'select count(*) from peer' 2>/dev/null || echo 0)"
  [[ "$dp" == "0" ]] && ok "desktop peers: 0" || bad "desktop still has $dp peer(s)"
  if a exec-out run-as "$PKG" cat databases/clipsync.db > "$PHONE_DB_COPY" 2>/dev/null && [[ -s "$PHONE_DB_COPY" ]]; then
    pp="$(sqlite3 "$PHONE_DB_COPY" 'select count(*) from peer' 2>/dev/null || echo 0)"
    [[ "$pp" == "0" ]] && ok "phone peers: 0" || bad "phone still has $pp peer(s)"
  else
    ok "phone peers: 0 (no DB — regenerates on next launch)"
  fi
  [[ $fails -eq 0 ]] || return 1
}

# --- run --------------------------------------------------------------------

cmd_run() {
  require_phone
  mkdir -p "$RUN_DIR"

  # Both logs are truncated on every run, unconditionally. 'verify' greps them for
  # SAS=, so a line left over from a previous run would let it report PASS for a run
  # that never happened — the same stale-state trap 'preflight' guards for peer rows.
  : > "$LOGCAT_LOG"
  : > "$DESKTOP_LOG"
  date +%Y-%m-%dT%H:%M:%S > "$RUN_DIR/run-id"

  head1 "1. Phone app"
  a shell pm list packages | grep -q "package:$PKG" ||
    { info "installing…"; a install -r "$APK" >/dev/null; }
  if phone_locked; then
    a shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
    bad "phone is locked — unlock it and re-run 'run'"
    return 1
  fi
  a logcat -c
  a logcat -v time clipsyncGraph:I clipsyncScan:I clipsyncPair:I '*:S' >> "$LOGCAT_LOG" 2>&1 &
  echo $! > "$RUN_DIR/logcat.pid"
  a shell am start -n "$ACTIVITY" >/dev/null
  ok "launched $ACTIVITY (logcat → $LOGCAT_LOG)"

  head1 "2. Desktop app"
  if [[ -n "$(desktop_pid)" ]]; then
    action "already running (pid $(desktop_pid)) — its stdout goes wherever it was launched from"
    info "if $DESKTOP_LOG stays empty, quit it and re-run so 'verify' has this run's log to read"
  else
    [[ -x "$DESKTOP_BIN" ]] || { bad "desktop app image missing"; return 1; }
    "$DESKTOP_BIN" >> "$DESKTOP_LOG" 2>&1 &
    ok "launched desktop (pid $!, log → $DESKTOP_LOG)"
  fi

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
  desk_sas="$(grep -oE 'SAS=[0-9]{6}' "$DESKTOP_LOG" 2>/dev/null | tail -1 | cut -d= -f2 || true)"

  [[ -n "$phone_sas" ]] && ok "phone derived a per-pair key (SAS $phone_sas)" || bad "phone never derived a per-pair key"
  # The desktop has no camera: it only learns the phone's key if the phone sends
  # its payload back over the wire (reciprocal pairing). This line proves that ran.
  [[ -n "$desk_sas" ]] && ok "desktop paired over the wire (SAS $desk_sas) — reciprocal pairing worked" ||
    bad "desktop never paired — reciprocal pairing did not reach it"

  if [[ -n "$phone_sas" && -n "$desk_sas" ]]; then
    if [[ "$phone_sas" == "$desk_sas" ]]; then
      ok "SAS MATCHES on both sides: $phone_sas"
      info "this is the number that must also match on the two screens"
    else
      bad "SAS MISMATCH — phone $phone_sas vs desktop $desk_sas (do not trust this pairing)"
    fi
  fi

  head1 "Stored state"
  if [[ -f "$DESKTOP_DB" ]]; then
    local n; n="$(sqlite3 "$DESKTOP_DB" 'select count(*) from peer' 2>/dev/null || echo 0)"
    [[ "$n" -gt 0 ]] && ok "desktop peer row: $(sqlite3 "$DESKTOP_DB" 'select device_name||" / "||device_id||" @ "||addresses from peer' 2>/dev/null)" ||
      bad "no peer row in the desktop DB"
  fi
  # No sqlite3 on the device, so pull the DB and read it here.
  if a exec-out run-as "$PKG" cat databases/clipsync.db > "$PHONE_DB_COPY" 2>/dev/null && [[ -s "$PHONE_DB_COPY" ]]; then
    local n; n="$(sqlite3 "$PHONE_DB_COPY" 'select count(*) from peer' 2>/dev/null || echo 0)"
    [[ "$n" -gt 0 ]] && ok "phone peer row: $(sqlite3 "$PHONE_DB_COPY" 'select device_name||" / "||device_id||" @ "||addresses from peer' 2>/dev/null)" ||
      bad "no peer row in the phone DB"
  else
    info "could not pull the phone DB (needs the debug build)"
  fi

  head1 "Live connection"
  # The transport logs nothing, so assert at the TCP layer instead. The phone may
  # arrive over ANY of its advertised addresses — it dials the payload's address
  # list in order, so a tailnet hit before the LAN one is normal, not a failure.
  local addrs sockets hit=""
  addrs="$(phone_ip | tr -d '\r')
$(grep -o '"addr":\[[^]]*\]' "$phone_log" 2>/dev/null | tail -1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+')"
  sockets="$(lsof -nP -iTCP:"$PORT" -sTCP:ESTABLISHED 2>/dev/null || true)"
  while read -r ip; do
    [[ -n "$ip" ]] || continue
    grep -q "$ip" <<<"$sockets" && { hit="$ip"; break; }
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
  local token="clipsync-harness-$(date +%s)"
  printf '%s' "$token" | pbcopy
  ok "copied on Mac: $token"
  sleep 4
  if a exec-out run-as "$PKG" cat databases/clipsync.db > "$PHONE_DB_COPY" 2>/dev/null &&
     sqlite3 "$PHONE_DB_COPY" 'select content from history' 2>/dev/null | grep -qF "$token"; then
    ok "phone received it (present in the phone's history)"
  else
    bad "phone did not receive it within 4s"
    info "needs Shizuku running and granted for the applier to set the clipboard"
  fi
  head1 "Phone → Mac"
  info "set the phone's clipboard from another app (Shizuku required), then run:"
  info "  pbpaste"
}

# --- misc -------------------------------------------------------------------

# Screenshots of both screens, as the human-facing record that the two SAS codes
# agree. The desktop capture goes by CGWindowID, not screen region: the tray window
# is easily occluded and a region grab silently captures whatever is on top of it.
cmd_evidence() {
  require_phone
  mkdir -p "$RUN_DIR"
  local stamp; stamp="$(date +%Y%m%d-%H%M%S)"

  a exec-out screencap -p > "$RUN_DIR/phone-$stamp.png" && ok "phone → $RUN_DIR/phone-$stamp.png"

  local wid
  wid="$(uv run --quiet --with pyobjc-framework-Quartz python -c "
import Quartz
for w in Quartz.CGWindowListCopyWindowInfo(
        Quartz.kCGWindowListOptionOnScreenOnly | Quartz.kCGWindowListExcludeDesktopElements,
        Quartz.kCGNullWindowID):
    if w.get('kCGWindowOwnerName') == 'clipsync':
        print(w['kCGWindowNumber']); break
" 2>/dev/null || true)"

  if [[ -n "$wid" ]]; then
    screencapture -x -o -l"$wid" "$RUN_DIR/desktop-$stamp.png" && ok "desktop → $RUN_DIR/desktop-$stamp.png"
  else
    action "could not find the clipsync window id — is the window open? (tray icon → Open history & pairing)"
  fi
}

cmd_logs() {
  mkdir -p "$RUN_DIR"; touch "$DESKTOP_LOG" "$LOGCAT_LOG"
  head1 "tailing $DESKTOP_LOG and $LOGCAT_LOG (ctrl-c to stop)"
  tail -f "$DESKTOP_LOG" "$LOGCAT_LOG"
}

cmd_stop() {
  [[ -f "$RUN_DIR/logcat.pid" ]] && kill "$(cat "$RUN_DIR/logcat.pid")" 2>/dev/null || true
  rm -f "$RUN_DIR/logcat.pid"
  ok "stopped background logcat (the apps are left running)"
}

case "${1:-preflight}" in
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
