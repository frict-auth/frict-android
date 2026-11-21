#!/usr/bin/env bash
# Boots a headless emulator and gives it a PIN; runs the screen-lock e2e
# (scripts/e2e.sh) before any fingerprint exists; enrolls a fingerprint; runs
# the instrumented tests and the fingerprint e2e; and stops it. On failure it saves the screen, a screenshot and logcat to
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

# The 4 KB page size Google APIs images ship Play services too old for
# firebase-messaging (26.11 on API 37.0); this one ships 26.22.
IMAGE=${IMAGE:-"system-images;android-37.1;google_apis_ps16k;x86_64"}
# firebase-messaging's minimum: "Google Play services out of date ... Requires 261200000".
MIN_GMS_VERSION_CODE=261200000
AVD=${AVD:-frict-test}
PORT=${PORT:-5554}
BOOT_TIMEOUT=${BOOT_TIMEOUT:-300}
DIAGNOSTICS_DIR=${DIAGNOSTICS_DIR:-$ROOT/app/build/emulator-diagnostics}
EMULATOR_LOG=$ROOT/app/build/emulator.log
# What the back camera sees; scripts/e2e.sh writes it.
export CAMERA_IMAGE=$ROOT/app/build/camera-back.png
export ANDROID_SERIAL=emulator-$PORT
export PIN=1111

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

# Through Settings, as a user would: Keystore only creates a fingerprint-only
# approval_key once a strong biometric is enrolled. Right after boot Settings
# can stall or stop responding, so a stalled attempt starts it over, and
# success is the fingerprint service's own count, not what the screen shows.
enroll_fingerprint() {
  local attempt screen
  for ((attempt = 1; attempt <= 3; attempt++)); do
    if fingerprint_enroll_attempt; then
      adb shell am force-stop com.android.settings
      adb shell input keyevent KEYCODE_HOME
      return 0
    fi
    echo "fingerprint enrollment attempt $attempt stalled; restarting Settings" >&2
    screen=$(ui_dump)
    grep -o 'text="[^"]\+"' <<<"$screen" >&2 || echo "the screen shows no text" >&2
    adb shell am force-stop com.android.settings
  done
  echo "no fingerprint enrolled in 3 attempts" >&2
  return 1
}

fingerprint_enrolled() {
  [[ $(adb shell dumpsys fingerprint) =~ \"count\":[1-9] ]]
}

# One pass through Settings' enrollment screens, answering whichever is up,
# until a fingerprint is enrolled or 90 s pass.
fingerprint_enroll_attempt() {
  local screen pin_entered='' sensing='' deadline=$((SECONDS + 90))
  adb shell am start -a android.settings.FINGERPRINT_ENROLL >/dev/null
  while ((SECONDS < deadline)); do
    fingerprint_enrolled && return 0
    screen=$(ui_dump) || return 1
    if grep -qiF "isn't responding\"" <<<"$screen"; then
      dismiss_anr "$screen"
    elif [[ -z $pin_entered ]] && grep -qiE 'text="(Re-enter your PIN|Enter your device PIN)"' <<<"$screen"; then
      adb shell input text "$PIN"
      adb shell input keyevent KEYCODE_ENTER
      pin_entered=1
    elif grep -qiF 'text="I agree"' <<<"$screen"; then
      tap_text "I agree"
    elif grep -qiF 'text="More"' <<<"$screen"; then
      tap_text More
    elif [[ -n $sensing ]] || grep -qiF 'text="Touch the sensor"' <<<"$screen"; then
      sensing=1
      adb emu finger touch 1 >/dev/null
      sleep 0.5
      adb emu finger remove 1 >/dev/null
    fi
    sleep 1
  done
  return 1
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
# avdmanager and the emulator can disagree on the AVD directory (the runner's
# avdmanager follows XDG_CONFIG_HOME), so point the emulator where it went.
avd_path=$(avdmanager list avd | sed -n "s|^ *Path: \(.*/$AVD\.avd\)$|\1|p")
[[ -n $avd_path ]] || { avdmanager list avd; echo "avdmanager does not list $AVD" >&2; exit 1; }
export ANDROID_AVD_HOME=${avd_path%/*}

mkdir -p "$ROOT/app/build"
emulator -avd "$AVD" -port "$PORT" -no-window -no-audio -no-boot-anim -no-snapshot -wipe-data -no-metrics \
  -gpu swiftshader_indirect -accel on -camera-back "imagefile:$CAMERA_IMAGE" >"$EMULATOR_LOG" 2>&1 &
emu_pid=$!
trap on_exit EXIT

start=$SECONDS
until [[ $(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]]; do
  kill -0 "$emu_pid" 2>/dev/null || { cat "$EMULATOR_LOG"; echo "emulator exited before boot completed" >&2; exit 1; }
  ((SECONDS - start < BOOT_TIMEOUT)) || { echo "no sys.boot_completed=1 within ${BOOT_TIMEOUT}s" >&2; exit 1; }
  sleep 2
done
echo "$ANDROID_SERIAL booted in $((SECONDS - start))s: API $(adb shell getprop ro.build.version.sdk | tr -d '\r')"
gms=$(adb shell dumpsys package com.google.android.gms)
gms_code=$(sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' <<<"$gms" | head -n1)
echo "Play services $(sed -n 's/.*versionName=//p' <<<"$gms" | head -n1), versionCode $gms_code"
((gms_code >= MIN_GMS_VERSION_CODE)) \
  || { echo "Play services versionCode $gms_code is older than $MIN_GMS_VERSION_CODE" >&2; exit 1; }

adb_root
adb shell svc power stayon true
wait_unlocked
adb shell locksettings set-pin "$PIN" >/dev/null
! fingerprint_enrolled || { echo "a fingerprint is enrolled before the screen-lock e2e" >&2; exit 1; }
"$ROOT/scripts/e2e.sh" screen-lock
adb uninstall app.frict.android.dev >/dev/null

enroll_fingerprint
echo "fingerprint enrolled"
unlock_screen "$PIN"
(cd "$ROOT" && ./gradlew connectedDebugAndroidTest --console=plain)
"$ROOT/scripts/e2e.sh" fingerprint
