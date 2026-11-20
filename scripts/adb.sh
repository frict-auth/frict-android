# shellcheck shell=bash
# Helpers for driving the emulator over adb; source this file.

# adb_root: restarts adbd as root. Right after boot adbd can drop the
# connection mid-restart, so retry until the shell runs as uid 0.
adb_root() {
  local i
  for ((i = 0; i < 10; i++)); do
    if adb root >/dev/null 2>&1 && timeout 30 adb wait-for-device &&
      [[ $(adb shell id -u 2>/dev/null | tr -d '\r') == 0 ]]; then
      return 0
    fi
    sleep 3
  done
  echo "adb root: shell is still not uid 0" >&2
  return 1
}

# ui_dump: the screen's UI hierarchy, one node per line. uiautomator fails now
# and then while the screen animates, so retry. Callers grep a variable, not a
# pipe from this: grep -q exiting early would fail the pipe under pipefail.
ui_dump() {
  local i out
  for ((i = 0; i < 5; i++)); do
    if out=$(adb exec-out uiautomator dump /dev/tty 2>&1) && [[ $out == *"</hierarchy>"* ]]; then
      tr '>' '\n' <<<"$out"
      return 0
    fi
    sleep 1
  done
  echo "uiautomator dump failed 5 times; last output: $out" >&2
  return 1
}

# wait_for_text TEXT [SECONDS]: waits until a node's text is exactly TEXT, ignoring case.
wait_for_text() {
  local i screen
  for ((i = 0; i < ${2:-30}; i++)); do
    screen=$(ui_dump)
    if grep -qiF "text=\"$1\"" <<<"$screen"; then return 0; fi
    sleep 1
  done
  echo "no \"$1\" on screen within ${2:-30}s; it shows:" >&2
  grep -o 'text="[^"]\+"' <<<"$screen" >&2
  return 1
}

# tap_text TEXT: taps the centre of the first node whose text is TEXT, ignoring case.
tap_text() {
  local screen b x1 y1 x2 y2
  screen=$(ui_dump)
  b=$(grep -m1 -iF "text=\"$1\"" <<<"$screen" | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
  [[ -n $b ]] || { echo "no node with text \"$1\"" >&2; return 1; }
  read -r x1 y1 x2 y2 <<<"$b"
  adb shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
}

# wait_unlocked: waits for a swipe-only keyguard to go away; after boot it can
# come up late.
wait_unlocked() {
  local i
  for ((i = 0; i < 60; i++)); do
    if ! grep -q 'resource-id="com.android.systemui:id/keyguard_root_view"' <<<"$(ui_dump)"; then return 0; fi
    adb shell input keyevent KEYCODE_WAKEUP
    adb shell wm dismiss-keyguard
    sleep 1
  done
  echo "the keyguard is still showing after 60s" >&2
  return 1
}

# touch_fingerprint_until_gone TEXT: touches the emulator's fingerprint sensor
# with the enrolled finger until TEXT (a biometric prompt's) leaves the screen.
touch_fingerprint_until_gone() {
  local i screen
  for ((i = 0; i < 10; i++)); do
    screen=$(ui_dump)
    if ! grep -qiF "text=\"$1\"" <<<"$screen"; then return 0; fi
    adb emu finger touch 1 >/dev/null
    sleep 0.5
    adb emu finger remove 1 >/dev/null
    sleep 1
  done
  echo "\"$1\" is still on screen after 10 fingerprint touches" >&2
  return 1
}

# hide_keyboard: closes the soft keyboard if it is up, so a tap reaches the app
# under it.
hide_keyboard() {
  local i
  for ((i = 0; i < 10; i++)); do
    [[ $(adb shell dumpsys input_method) == *mInputShown=true* ]] || return 0
    adb shell input keyevent KEYCODE_BACK
    sleep 1
  done
  echo "the soft keyboard is still up after 10 BACK presses" >&2
  return 1
}
