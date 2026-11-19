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

# One node per line. Callers grep a variable, not this pipe: grep -q exiting
# early would fail the pipe under pipefail.
ui_dump() { adb exec-out uiautomator dump /dev/tty | tr '>' '\n'; }

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
