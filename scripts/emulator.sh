#!/usr/bin/env bash
# Boots a headless emulator, runs the instrumented tests on it, and stops it.
# On failure it saves the screen, a screenshot and logcat to DIAGNOSTICS_DIR.
#
# Usage: scripts/emulator.sh
#
# IMAGE is installed with sdkmanager unless ANDROID_HOME already has it.
set -Eeuo pipefail

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

mkdir -p "$ROOT/app/build"
emulator -avd "$AVD" -port "$PORT" -no-window -no-audio -no-boot-anim -no-snapshot -no-metrics \
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

(cd "$ROOT" && ./gradlew connectedDebugAndroidTest --console=plain)
