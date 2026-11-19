#!/usr/bin/env bash
# Boots a headless emulator, gives it a PIN and an enrolled fingerprint, runs
# the instrumented tests and the enrollment e2e (scripts/enroll-e2e.sh) on it,
# and stops it. On failure it saves the screen, a screenshot and logcat to
# DIAGNOSTICS_DIR.
#
# Usage: scripts/emulator.sh
#
# IMAGE is installed with sdkmanager unless ANDROID_HOME already has it.
set -Eeuo pipefail
trap 'echo "$0: line $LINENO: \"$BASH_COMMAND\" failed" >&2' ERR

ROOT=$(cd "$(dirname "$0")/.." && pwd)
: "${ANDROID_HOME:?set ANDROID_HOME to the Android SDK}"
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$PATH

IMAGE=${IMAGE:-"system-images;android-36;google_apis;x86_64"}
AVD=${AVD:-frict-test}
PORT=${PORT:-5554}
BOOT_TIMEOUT=${BOOT_TIMEOUT:-300}
DIAGNOSTICS_DIR=${DIAGNOSTICS_DIR:-$ROOT/app/build/emulator-diagnostics}
EMULATOR_LOG=$ROOT/app/build/emulator.log
export ANDROID_SERIAL=emulator-$PORT
PIN=1111

. "$ROOT/scripts/adb.sh"

# Each adb call is bounded so a wedged device can't hang the capture.
capture_diagnostics() {
  set +e
  local t=15
  mkdir -p "$DIAGNOSTICS_DIR"
  cp "$EMULATOR_LOG" "$DIAGNOSTICS_DIR/"
  timeout $t adb exec-out uiautomator dump /dev/tty >"$DIAGNOSTICS_DIR/screen.xml"
  timeout $t adb exec-out screencap -p >"$DIAGNOSTICS_DIR/screen.png"
  timeout $t adb logcat -d >"$DIAGNOSTICS_DIR/logcat.txt"
  timeout $t adb shell dumpsys activity activities >"$DIAGNOSTICS_DIR/activities.txt"
  echo "diagnostics saved to $DIAGNOSTICS_DIR" >&2
}

# `adb emu kill` can answer OK and leave the emulator running, so wait for it
# and escalate.
# Through Settings, as a user would: Keystore only creates the approval_key once
# a strong biometric is enrolled.
enroll_fingerprint() {
  local i screen
  wait_unlocked
  adb shell locksettings set-pin "$PIN" >/dev/null
  adb shell am start -a android.settings.FINGERPRINT_ENROLL >/dev/null
  wait_for_text "Re-enter your PIN" 60
  adb shell input text "$PIN"
  adb shell input keyevent KEYCODE_ENTER
  for ((i = 0; i < 60; i++)); do
    screen=$(ui_dump)
    if grep -qiF 'text="Touch the sensor"' <<<"$screen"; then
      break
    elif grep -qiF 'text="I agree"' <<<"$screen"; then
      tap_text "I agree"
    elif grep -qiF 'text="More"' <<<"$screen"; then
      tap_text More
    fi
    sleep 2
  done
  wait_for_text "Touch the sensor" 60
  for ((i = 0; i < 10; i++)); do
    screen=$(ui_dump)
    if grep -qiF 'text="Fingerprint added"' <<<"$screen"; then break; fi
    adb emu finger touch 1 >/dev/null
    sleep 0.5
    adb emu finger remove 1 >/dev/null
    sleep 1
  done
  wait_for_text "Fingerprint added"
  tap_text Done
  adb shell input keyevent KEYCODE_HOME
}

stop_emulator() {
  [[ -n ${emu_pid:-} ]] || return 0
  adb emu kill >/dev/null 2>&1 || echo "adb emu kill failed; signalling the emulator" >&2
  local i
  for ((i = 0; i < 30; i++)); do
    kill -0 "$emu_pid" 2>/dev/null || break
    sleep 1
  done
  if kill -0 "$emu_pid" 2>/dev/null; then
    echo "emulator still running 30 s after adb emu kill; sending SIGKILL" >&2
    kill -9 "$emu_pid"
  fi
  wait "$emu_pid" 2>/dev/null || echo "emulator exited with status $?" >&2
  adb kill-server
}

on_exit() {
  local status=$?
  ((status == 0)) || capture_diagnostics
  stop_emulator
  exit "$status"
}

[[ -r /dev/kvm && -w /dev/kvm ]] || { echo "/dev/kvm is not accessible" >&2; exit 1; }
[[ -d $ANDROID_HOME/${IMAGE//;//} ]] || sdkmanager --install "$IMAGE" <<<"$(printf 'y\n%.0s' {1..20})" >/dev/null
echo no | avdmanager create avd --force --name "$AVD" --package "$IMAGE" >/dev/null
# avdmanager and the emulator can disagree on the AVD directory (the runner's
# avdmanager follows XDG_CONFIG_HOME), so point the emulator where it went.
avd_path=$(avdmanager list avd | sed -n "s|^ *Path: \(.*/$AVD\.avd\)$|\1|p")
[[ -n $avd_path ]] || { avdmanager list avd; echo "avdmanager does not list $AVD" >&2; exit 1; }
export ANDROID_AVD_HOME=${avd_path%/*}

mkdir -p "$ROOT/app/build"
emulator -avd "$AVD" -port "$PORT" -no-window -no-audio -no-boot-anim -no-snapshot -wipe-data -no-metrics \
  -gpu swiftshader_indirect -accel on >"$EMULATOR_LOG" 2>&1 &
emu_pid=$!
trap on_exit EXIT

start=$SECONDS
until [[ $(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]]; do
  kill -0 "$emu_pid" 2>/dev/null || { cat "$EMULATOR_LOG"; echo "emulator exited before boot completed" >&2; exit 1; }
  ((SECONDS - start < BOOT_TIMEOUT)) || { echo "no sys.boot_completed=1 within ${BOOT_TIMEOUT}s" >&2; exit 1; }
  sleep 2
done
echo "$ANDROID_SERIAL booted in $((SECONDS - start))s: API $(adb shell getprop ro.build.version.sdk | tr -d '\r')"

adb_root
adb shell svc power stayon true
enroll_fingerprint
echo "fingerprint enrolled"

(cd "$ROOT" && ./gradlew connectedDebugAndroidTest --console=plain)
"$ROOT/scripts/enroll-e2e.sh"
