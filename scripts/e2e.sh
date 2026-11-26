#!/usr/bin/env bash
# The app against a real frict-server, which runs at FRICT_SERVER_COMMIT
# behind TLS from a throwaway test CA on 127.0.0.1:443, which the emulator
# reaches as 10.0.2.2. Every server call is Duo-signed, as Authentik makes it.
#
# 1. Enroll: creates an activation (/auth/v2/enroll) and hands the debug app
#    its link, picks MODE's unlock, taps Enroll, and requires
#    /auth/v2/enroll_status to go from waiting to success. fingerprint opens
#    the link; screen-lock scans the server's QR code of it with the emulator's
#    back camera (CAMERA_IMAGE, scripts/emulator.sh's -camera-back image), after
#    refusing the camera once, rejecting a QR code that is not an activation
#    link, and requiring the camera to close when the app leaves the screen and
#    once the link is scanned.
#    The server must hold the app's Firebase Installation ID, registered with
#    Firebase (which the dev API key's package and certificate restriction
#    allows), and get it again on the app's next start.
# 2. Approve: starts a login (/auth/v2/auth), requires the app to show its
#    details before the prompt, taps Approve, unlocks the prompt (screen-lock:
#    types PIN; fingerprint: touches the sensor), and requires /auth to return
#    allow.
# screen-lock only:
# 3. Update: with every request answered 426 app_update_required (only if it
#    carries a numeric Frict-App-Version), the app must show "Update Frict",
#    whose button opens the app's Play listing.
# 4. Key lost: removing the screen lock invalidates the approval_key; the app
#    must say so and offer only "Remove this account".
# 5. Remove: with the server unreachable, "Remove this account" must say the
#    server could not be told and offer "Remove anyway"; Cancel keeps the
#    account. With the server back, removal must return to the enrollment
#    screen, and /auth for the user must then get 400, code 40002. The PIN is
#    set again for what runs next.
# fingerprint only, with a second user (the admin account) enrolled beside the
# first, unlocked with fingerprint or screen lock:
# 3. Accounts: the first user's link again must be refused; the admin's link
#    adds a second account, both shown with their unlock modes, and the server
#    must hold the app's FID for both users, again after the next start.
# 4. Push: with the app in the background, starts an admin login and hands the
#    app the push frict-server would send, through FCM's receiver as Play
#    services delivers it, with made-up details beside the ID. Its notification
#    must name the admin account, open the app on the challenge with the
#    server's details, never the push's, and Approve must reach /auth. Deny on
#    the first user's next login must reach its /auth.
# 5. Not me: the first user's next login, denied with "Not me": /auth must
#    return deny with status fraud, and the next /auth must be refused because
#    the server paused pushes after the fraud report (FRICT_NOT_ME_COUNT=1).
# 6. Remove one: removing the first user must remove it on the server and keep
#    the app's Firebase installation, and the admin must still approve.
# 7. Remove the last: with the server unreachable, "Remove anyway" must return
#    to the enrollment screen while the server still holds the device, and
#    delete the app's Firebase installation.
#
# Needs docker and a booted, rootable emulator in ANDROID_SERIAL with screen
# lock PIN; fingerprint also needs a strong biometric enrolled
# (scripts/emulator.sh does both).
#
# Usage: PIN=… CAMERA_IMAGE=… scripts/e2e.sh screen-lock|fingerprint
set -Eeuo pipefail
trap 'echo "$0: line $LINENO: \"$BASH_COMMAND\" failed" >&2' ERR

ROOT=$(cd "$(dirname "$0")/.." && pwd)
MODE=${1:?usage: $0 screen-lock|fingerprint}
: "${PIN:?set PIN to the screen lock PIN of the emulator}"
case $MODE in
  screen-lock) MODE_LABEL="Fingerprint or screen lock" ;;
  fingerprint) MODE_LABEL="Fingerprint only" ;;
  *) echo "unknown mode $MODE; want screen-lock or fingerprint" >&2; exit 2 ;;
esac
FRICT_SERVER_COMMIT=${FRICT_SERVER_COMMIT:-3c5f6894154702cf34dcb98c847105bd06fc03a4}
DIAGNOSTICS_DIR=${DIAGNOSTICS_DIR:-$ROOT/app/build/emulator-diagnostics}
: "${ANDROID_SERIAL:?set ANDROID_SERIAL to the booted emulator}"
API_HOST=10.0.2.2
CLIENT_ID=DIFRICTANDROIDE2E000
PACKAGE=app.frict.android.dev
ACTIVITY=app.frict.android.MainActivity
NAME=frict-android-e2e-$$

. "$ROOT/scripts/adb.sh"

work=$(mktemp -d)
cleanup() {
  local status=$?
  set +e
  jobs -p | xargs -r kill
  mkdir -p "$DIAGNOSTICS_DIR"
  docker logs "$NAME-server" >"$DIAGNOSTICS_DIR/frict-server.log" 2>&1
  docker logs "$NAME-tls" >"$DIAGNOSTICS_DIR/frict-tls.log" 2>&1
  docker rm -f "$NAME-server" "$NAME-tls" >/dev/null
  docker network rm "$NAME" >/dev/null
  rm -rf "$work"
  exit "$status"
}
trap cleanup EXIT

src=$work/frict-server
git init -q "$src"
git -C "$src" fetch -q --depth 1 https://github.com/frict-auth/frict-server.git "$FRICT_SERVER_COMMIT"
git -C "$src" checkout -q FETCH_HEAD
mkdir "$src/cmd/duo" "$src/cmd/store"
cp "$ROOT/scripts/duo/main.go" "$src/cmd/duo/"
cp "$ROOT/scripts/store/main.go" "$src/cmd/store/"
docker build -q -t "$NAME" "$src" >/dev/null
docker run --rm -v "$src":/src -v "$work":/out -w /src -u "$(id -u):$(id -g)" \
  -e HOME=/tmp -e GOCACHE=/tmp/gocache -e CGO_ENABLED=0 \
  docker.io/library/golang:1.27.4@sha256:5e8afd493a7124a8586e134d9b532731161d4b9becf9dee5a5956b107426f93d sh -c 'go build -o /out/duo ./cmd/duo && go build -o /out/store ./cmd/store'

secrets=$work/secrets
mkdir "$secrets"
openssl ecparam -name prime256v1 -genkey -noout -out "$work/ca.key"
openssl req -x509 -new -key "$work/ca.key" -sha256 -days 2 -subj "/CN=Frict Android e2e throwaway CA" \
  -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" \
  -out "$secrets/ca.pem"
openssl ecparam -name prime256v1 -genkey -noout -out "$secrets/frict.key"
openssl req -new -key "$secrets/frict.key" -subj "/CN=$API_HOST" -out "$work/frict.csr"
openssl x509 -req -in "$work/frict.csr" -CA "$secrets/ca.pem" -CAkey "$work/ca.key" -CAcreateserial \
  -CAserial "$work/ca.srl" -sha256 -days 2 \
  -extfile <(printf '%s\n' "subjectAltName=IP:$API_HOST" "basicConstraints=critical,CA:FALSE" \
    "keyUsage=critical,digitalSignature" "extendedKeyUsage=serverAuth") \
  -out "$secrets/frict.pem" 2>/dev/null
openssl rand -hex 20 >"$secrets/duo-client-secret"
# tls_conf LOCATION: the TLS front's config, whose location / does LOCATION.
tls_conf() {
  cat >"$work/tls.conf" <<CONF
server {
    listen 443 ssl;
    ssl_certificate     /secrets/frict.pem;
    ssl_certificate_key /secrets/frict.key;
    location / { $1 }
}
CONF
}
PROXY='proxy_pass http://frict-server:8080;'
tls_conf "$PROXY"
chmod 0644 "$secrets"/* "$work/tls.conf"

# The server runs as this user, so the store helper can open its database.
mkdir "$work/data"
docker network create "$NAME" >/dev/null
docker run -d --name "$NAME-server" --network "$NAME" --network-alias frict-server \
  -u "$(id -u):$(id -g)" -v "$work/data":/data -v "$secrets":/secrets:ro \
  -e FRICT_API_HOSTNAME=$API_HOST -e FRICT_CLIENT_ID=$CLIENT_ID \
  -e FRICT_CLIENT_SECRET_FILE=/secrets/duo-client-secret -e FRICT_DB_PATH=/data/frict.db \
  -e FRICT_NOT_ME_COUNT=1 "$NAME" >/dev/null
docker run -d --name "$NAME-tls" --network "$NAME" -p 127.0.0.1:443:443 \
  -v "$secrets":/secrets:ro -v "$work/tls.conf":/etc/nginx/conf.d/default.conf:ro \
  docker.io/library/nginx:1.29.3-alpine@sha256:b3c656d55d7ad751196f21b7fd2e8d4da9cb430e32f646adcf92441b72f82b14 >/dev/null

wait_healthy() {
  local i
  for ((i = 0; i < 30; i++)); do
    curl -fsS --cacert "$secrets/ca.pem" --connect-to "::127.0.0.1:443" "https://$API_HOST/healthz" >/dev/null 2>&1 && break
    sleep 1
  done
  curl -fsS --cacert "$secrets/ca.pem" --connect-to "::127.0.0.1:443" "https://$API_HOST/healthz"
  echo
}
wait_healthy

duo() {
  E2E_HOST=$API_HOST E2E_ADDR=127.0.0.1:443 E2E_CA=$secrets/ca.pem E2E_IKEY=$CLIENT_ID \
    E2E_SKEY_FILE=$secrets/duo-client-secret "$work/duo" "$@"
}

store() { E2E_DB=$work/data/frict.db "$work/store" "$@"; }

# installation: the app's saved Firebase installation as "FID STATUS", "-" for
# what it lacks. The file also holds the installation's tokens, so it goes
# straight to python and never into a variable or the log.
installation() {
  adb shell "cat /data/data/$PACKAGE/no_backup/PersistedInstallation.*.json 2>/dev/null; true" | python3 -I -c 'import json, sys
text = sys.stdin.read().strip()
try:
    p = json.loads(text) if text else {}
except ValueError:
    p = {}
print(p.get("Fid") or "-", p.get("Status", "-"))'
}

# app_fid: the FID Firebase Installations saved for the app, once Firebase has
# registered it.
app_fid() {
  local i fid status
  for ((i = 0; i < 60; i++)); do
    read -r fid status <<<"$(installation)"
    # Status 3 is REGISTERED in PersistedInstallation.RegistrationStatus.
    if [[ $status == 3 && $fid != - ]]; then
      echo "$fid"
      return 0
    fi
    sleep 1
  done
  echo "no Firebase-registered FID for $PACKAGE within 60s; FID $([[ $fid == - ]] && echo absent || echo present), status $status" >&2
  return 1
}

# wait_server_fid USER_ID WANT: waits until the server stores WANT as USER_ID's FID.
wait_server_fid() {
  local i got
  for ((i = 0; i < 30; i++)); do
    got=$(store fid "$1")
    [[ $got == "$2" ]] && return 0
    sleep 1
  done
  echo "server FID of $1 after 30s: \"$got\", want the app's" >&2
  return 1
}

# push KEY=VALUE...: delivers a data message to the app the way Play services
# does, through firebase-messaging's receiver (root can send what only Play
# services may).
push() {
  local args=() kv
  for kv in "$@"; do args+=(--es "${kv%%=*}" "${kv#*=}"); done
  adb shell am broadcast -n "$PACKAGE/com.google.firebase.iid.FirebaseInstanceIdReceiver" \
    -a com.google.android.c2dm.intent.RECEIVE --es google.message_id "e2e-$RANDOM" "${args[@]}" >/dev/null
}

# notification_extras KEY: the KEY extra (title, text) of each of the app's notifications.
notification_extras() {
  adb shell dumpsys notification --noredact | tr -d '\r' | awk -v pkg="pkg=$PACKAGE " -v key="android.$1=String (" '
    /NotificationRecord\(/ { ours = index($0, pkg) > 0 }
    ours && index($0, key) { sub(/.*android\.[a-z]+=String \(/, ""); sub(/\)$/, ""); print }'
}

# open_notification TITLE TEXT: waits for the app's notification titled TITLE
# saying TEXT and taps it.
open_notification() {
  local i
  for ((i = 0; i < 30; i++)); do
    if grep -qxF "$1" <<<"$(notification_extras title)" && grep -qxF "$2" <<<"$(notification_extras text)"; then
      echo "notification shown: $1: $2"
      adb shell cmd statusbar expand-notifications
      wait_for_text "$1"
      tap_text "$1"
      return 0
    fi
    sleep 1
  done
  echo "no notification \"$1: $2\" from $PACKAGE within 30s; it has: $(notification_extras title) $(notification_extras text)" >&2
  return 1
}

# camera_is APP_STATE: waits until the app has a camera open (open) or none (closed).
camera_is() {
  local i cameras open
  for ((i = 0; i < 15; i++)); do
    cameras=$(adb shell dumpsys media.camera)
    if grep -qF "Client Package Name: $PACKAGE" <<<"$cameras"; then open=open; else open=closed; fi
    [[ $open == "$1" ]] && return 0
    sleep 1
  done
  echo "the app's camera is $open after 15s, want $1" >&2
  return 1
}

# scan_enrollment CAMERA_PNG: has the app scan the activation's QR code,
# CAMERA_PNG, with the back camera, as a user would, and the failure paths
# before it.
scan_enrollment() {
  duo qr "https://$API_HOST/enroll?code=e2e&host=$API_HOST&user_id=$user_id" "$CAMERA_IMAGE"
  adb shell am start -W -n "$PACKAGE/$ACTIVITY" >/dev/null
  wait_for_text "Scan QR code"
  tap_text "Scan QR code"
  wait_for_text "Don’t allow"
  tap_text "Don’t allow"
  wait_for_text "Without the camera, paste the activation link below or open it as a link on this phone."
  camera_is closed
  echo "camera refused: the app offers the link instead"
  tap_text "Scan QR code"
  wait_for_text "While using the app"
  tap_text "While using the app"
  wait_for_text "That QR code is not a Frict activation link. It was not opened." 30
  camera_is open
  echo "a QR code that is not an activation link is rejected; the camera stays on"
  adb shell input keyevent KEYCODE_HOME
  camera_is closed
  echo "the camera closes when the app leaves the screen"
  # The emulator reads the image each time the camera opens.
  cp "$1" "$CAMERA_IMAGE"
  adb shell am start -W -n "$PACKAGE/$ACTIVITY" >/dev/null
  wait_for_text "Activation link scanned. Choose how to approve sign-ins, then tap Enroll." 30
  camera_is closed
  echo "activation link scanned; the camera closed"
}

# Android trusts user-added CAs from this directory, named by OpenSSL's old subject hash.
ca_name=$(openssl x509 -subject_hash_old -noout -in "$secrets/ca.pem").0
adb_root
adb push "$secrets/ca.pem" "/data/local/tmp/$ca_name" >/dev/null 2>&1
adb shell "mkdir -p /data/misc/user/0/cacerts-added && mv /data/local/tmp/$ca_name /data/misc/user/0/cacerts-added/ \
  && chown -R system:system /data/misc/user/0/cacerts-added && chmod 644 /data/misc/user/0/cacerts-added/$ca_name \
  && restorecon -R /data/misc/user/0/cacerts-added" >/dev/null 2>&1

(cd "$ROOT" && ./gradlew installDebug --console=plain -q)
adb shell pm clear "$PACKAGE" >/dev/null
# The server is 10.0.2.2, which Android 17 treats as local network.
adb shell pm grant "$PACKAGE" android.permission.ACCESS_LOCAL_NETWORK
adb shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS
echo "Google Play services: $(adb shell dumpsys package com.google.android.gms | tr -d '\r' | sed -n 's/^ *versionName=//p' | head -1)"

if [[ $MODE == screen-lock ]]; then
  : "${CAMERA_IMAGE:?set CAMERA_IMAGE to the -camera-back imagefile of the emulator}"
  link=$(duo enroll android-e2e "$work/activation-qr.png")
else
  link=$(duo enroll android-e2e)
fi
user_id=$(sed -n 's/.*[?&]user_id=\([^&]*\).*/\1/p' <<<"$link")
echo "activation for $user_id"
before=$(duo status "$user_id" "$link")
[[ $before == waiting ]] || { echo "enroll_status before the app enrolled: $before, want waiting" >&2; exit 1; }

disable_soft_keyboards
unlock_screen "$PIN"
if [[ $MODE == screen-lock ]]; then
  scan_enrollment "$work/activation-qr.png"
else
  adb shell am start -W -a android.intent.action.VIEW -d "'$link'" "$PACKAGE" >/dev/null
fi
wait_for_text Enroll
[[ $MODE == fingerprint ]] && tap_text "$MODE_LABEL"

# enroll USER_ID LINK: taps Enroll on LINK's form and requires the server to
# report USER_ID enrolled.
enroll() {
  local i failed after
  tap_text Enroll
  # Keystore attests through remote key provisioning, which can report no network
  # for a while after boot; the app shows that, and a user would tap Enroll again.
  for ((i = 0; i < 3; i++)); do
    sleep 5
    failed=$(grep -o 'text="Could not create keys:[^"]*"' <<<"$(ui_dump)") || break
    echo "app shows $failed; tapping Enroll again" >&2
    sleep 15
    tap_text Enroll
  done
  for ((i = 0; i < 30; i++)); do
    after=$(duo status "$1" "$2")
    [[ $after == waiting ]] || break
    sleep 1
  done
  echo "enroll_status of $1 after the app enrolled: $after"
  [[ $after == success ]] || { echo "enroll_status of $1 after the app enrolled: $after, want success" >&2; exit 1; }
  wait_for_text "Enrolled as $1 with $API_HOST"
}
enroll "$user_id" "$link"
unlocks_with() { echo "Approve sign-ins with: $1. To change, remove the account, then enroll again."; }
wait_for_text "$(unlocks_with "$MODE_LABEL")"

# unlock_prompt TITLE: answers the prompt titled TITLE the way MODE allows.
unlock_prompt() {
  if [[ $MODE == fingerprint ]]; then
    touch_fingerprint_until_gone "$1"
  else
    enter_pin_until_gone "$1" "$PIN"
  fi
}

fid=$(app_fid)
echo "the app has a Firebase-registered FID"
wait_server_fid "$user_id" "$fid"
echo "the server holds the app's FID after enrollment"

admin_username=android-e2e-admin
if [[ $MODE == fingerprint ]]; then
  adb shell am start -W -a android.intent.action.VIEW -d "'$link'" "$PACKAGE" >/dev/null
  wait_for_text "Add an account"
  tap_text Enroll
  wait_for_text "This phone already has that account."
  echo "enrolling the same user on the same server again is refused"
  tap_text Cancel
  admin_link=$(duo enroll "$admin_username")
  admin_id=$(sed -n 's/.*[?&]user_id=\([^&]*\).*/\1/p' <<<"$admin_link")
  echo "activation for the admin account $admin_id"
  adb shell am start -W -a android.intent.action.VIEW -d "'$admin_link'" "$PACKAGE" >/dev/null
  wait_for_text "Add an account"
  wait_for_text Enroll
  tap_text "Fingerprint or screen lock"
  enroll "$admin_id" "$admin_link"
  wait_for_text "Enrolled as $user_id with $API_HOST"
  wait_for_text "$(unlocks_with "$MODE_LABEL")"
  wait_for_text "$(unlocks_with "Fingerprint or screen lock")"
  echo "two accounts, each shown with its unlock mode"
  wait_server_fid "$admin_id" "$fid"
  echo "the server holds the app's FID for the admin account"
  store setfid "$admin_id" stale-e2e-fid
fi
store setfid "$user_id" stale-e2e-fid
adb shell am force-stop "$PACKAGE"
adb shell am start -W -n "$PACKAGE/$ACTIVITY" >/dev/null
wait_server_fid "$user_id" "$fid"
[[ $MODE == screen-lock ]] || wait_server_fid "$admin_id" "$fid"
echo "the server holds the app's FID again for every account after the app's next start"

# start_login [USER_ID USERNAME]: starts a login, by default the first
# user's, in the background; answer waits for its result.
start_login() {
  auth_out=$work/auth-$RANDOM
  login_username=${2:-android-e2e}
  duo auth "${1:-$user_id}" "$login_username" >"$auth_out" &
  auth_pid=$!
}

# answer ANSWER_BUTTON PROMPT_TITLE WANT: waits for the app to show the started
# login, for its account, answers it through the prompt, and requires /auth's
# "<result> <status>: <status_msg>" to start with WANT.
answer() {
  local got
  # The details must be on screen before anything asks to unlock.
  wait_for_text "Sign-in request" 60
  wait_for_text "For $login_username on $API_HOST"
  wait_for_text "From: 203.0.113.7"
  wait_for_text "Application: Frict Android e2e"
  if grep -qE 'mallory|198\.51\.100\.66' <<<"$(ui_dump)"; then
    echo "the app shows details from the push, not the server" >&2
    exit 1
  fi
  tap_text "$1"
  wait_for_text "$2"
  unlock_prompt "$2"
  wait "$auth_pid"
  got=$(<"$auth_out")
  echo "/auth after $1: $got"
  [[ $got == "$3"* ]] || { echo "/auth after $1: $got, want $3" >&2; exit 1; }
}

login() {
  start_login
  answer "$@"
}

# login_as USER_ID USERNAME ANSWER_BUTTON PROMPT_TITLE WANT: login for that user.
login_as() {
  start_login "$1" "$2"
  answer "${@:3}"
}

login Approve "Approve this sign-in" "allow allow:"
wait_for_text "Approved."

# start_removal USER_ID: confirms "Remove this account" on the first account
# listed, which must be USER_ID's.
start_removal() {
  grep -qF 'text="Remove this account"' <<<"$(ui_dump)" || scroll_down
  tap_text "Remove this account"
  wait_for_text "Remove $1 on $API_HOST?"
  tap_text Remove
}

# server_unreachable: stops the TLS front, so the app's requests are refused.
server_unreachable() {
  docker stop "$NAME-tls" >/dev/null
  echo "server unreachable"
}

# serve LOCATION: reloads the TLS front with LOCATION. The config is bind
# mounted, so it is rewritten in place.
serve() {
  tls_conf "$1"
  docker exec "$NAME-tls" nginx -s reload 2>/dev/null
  sleep 1
}

# removed_on_server USER_ID: /auth for USER_ID must get 400, code 40002.
removed_on_server() {
  local gone
  if gone=$(duo auth "$1" android-e2e 2>&1); then
    echo "/auth after removal: $gone, want HTTP 400 code 40002" >&2
    exit 1
  fi
  echo "/auth after removal: $gone"
  [[ $gone == *"HTTP 400 "*'"code":40002'*'"Invalid request parameters: user_id"'* ]] ||
    { echo "/auth after removal: $gone, want HTTP 400 code 40002 for user_id" >&2; exit 1; }
  echo "the server no longer knows the removed device"
}

if [[ $MODE == screen-lock ]]; then
  serve 'if ($http_frict_app_version !~ "^[0-9]+$") { return 400 "{\"error\":\"no_app_version\"}"; }
    default_type application/json;
    return 426 "{\"error\":\"app_update_required\",\"min_version\":2147483647}";'
  wait_for_text "Update Frict"
  wait_for_text "This version of Frict is too old for the server. Update it to approve sign-ins."
  adb logcat -c
  tap_text "Update Frict"
  # ActivityTaskManager logs the start, with the URL cut after the host; the
  # window manager logs the whole link it opened.
  started="START u0 {act=android.intent.action.VIEW dat=https://play.google.com/"
  listing="https://play.google.com/store/apps/details?id=$PACKAGE"
  for ((i = 0; i < 15; i++)); do
    log=$(adb logcat -d)
    grep -F "$started" <<<"$log" | grep -qF "($PACKAGE)" && grep -qF "$listing" <<<"$log" && break
    sleep 1
  done
  grep -F "$started" <<<"$log" | grep -qF "($PACKAGE)" && grep -qF "$listing" <<<"$log" ||
    { echo "the app opened no $listing after tapping Update Frict" >&2; exit 1; }
  echo "426 app_update_required: the app shows Update Frict, which opens its Play listing"
  serve "$PROXY"
  adb shell am start -W -n "$PACKAGE/$ACTIVITY" >/dev/null

  # Keystore deletes auth-bound keys once the screen lock is gone; the app
  # checks its approval_key whenever it comes back to the screen.
  adb shell locksettings clear --old "$PIN" >/dev/null
  adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
  adb shell am start -W -n "$PACKAGE/$ACTIVITY" >/dev/null
  wait_for_text "This account can't approve sign-ins"
  wait_for_text "Android invalidated its approval key. With Fingerprint or screen lock, that happens when the screen lock is removed. Remove the account, then enroll again."
  echo "screen lock removed: the app says the approval key is invalidated"

  server_unreachable
  start_removal "$user_id"
  wait_for_text "The server could not be told"
  wait_for_text "Remove anyway"
  tap_text Cancel
  wait_for_text "Enrolled as $user_id with $API_HOST"
  echo "unreachable server: the app offers Remove anyway; Cancel keeps the account"
  docker start "$NAME-tls" >/dev/null
  wait_healthy
  start_removal "$user_id"
  wait_for_text "Removed $user_id on $API_HOST."
  wait_for_text "Not enrolled"
  echo "removed; back on the enrollment screen"
  removed_on_server "$user_id"
  adb shell locksettings set-pin "$PIN" >/dev/null
  exit 0
fi

# installation_kept FID: the app's Firebase installation must still be FID,
# registered, and the app must not have deleted it.
installation_kept() {
  [[ $(app_fid) == "$1" ]] || { echo "the app's FID changed from $1" >&2; exit 1; }
  if grep -F "Firebase installation deleted" <<<"$(adb logcat -d -s FrictPush:I)"; then
    echo "the app deleted its Firebase installation" >&2
    exit 1
  fi
}

# installation_deleted FID: the app must have deleted its Firebase
# installation, whose ID was FID: Firebase confirmed it, and FID is no longer
# saved as registered.
installation_deleted() {
  local i log fid status
  for ((i = 0; i < 30; i++)); do
    log=$(adb logcat -d -s FrictPush:I)
    grep -qF "Firebase installation deleted" <<<"$log" && break
    sleep 1
  done
  grep -F "Firebase installation deleted" <<<"$log" || { echo "the app did not delete its Firebase installation; FrictPush log: $log" >&2; exit 1; }
  read -r fid status <<<"$(installation)"
  if [[ $fid == "$1" && $status == 3 ]]; then
    echo "the deleted FID is still saved as registered" >&2
    exit 1
  fi
  echo "the app's saved installation after deletion: status $status"
}

adb shell input keyevent KEYCODE_HOME
start_login "$admin_id" "$admin_username"
challenge_id=
for ((i = 0; i < 30; i++)); do
  challenge_id=$(store challenge "$admin_id" 2>/dev/null) && break
  sleep 1
done
[[ -n $challenge_id ]] || { echo "no pending challenge for $admin_id within 30s" >&2; exit 1; }
push challenge_id="$challenge_id" username=mallory ipaddr=198.51.100.66
echo "login push delivered"
open_notification "Sign-in request" "For $admin_username on $API_HOST"
answer Approve "Approve this sign-in" "allow allow:"
wait_for_text "Approved."

login Deny "Deny this sign-in" "deny deny:"
wait_for_text "Denied."
echo "approved on the admin account, denied on the first"

login "Not me" "Report this sign-in as not you" "deny fraud:"
wait_for_text "Denied and reported as not you."
paused=$(duo auth "$user_id" android-e2e)
echo "/auth after the fraud report: $paused"
[[ $paused == "deny locked_out: Pushes paused"* ]] ||
  { echo "/auth after the fraud report: $paused, want deny locked_out: Pushes paused…" >&2; exit 1; }

adb logcat -c
start_removal "$user_id"
wait_for_text "Removed $user_id on $API_HOST."
wait_for_text "Enrolled as $admin_id with $API_HOST"
removed_on_server "$user_id"
installation_kept "$fid"
echo "removed the first account; the Firebase installation is kept"
login_as "$admin_id" "$admin_username" Approve "Approve this sign-in" "allow allow:"
echo "the admin account still approves"

server_unreachable
start_removal "$admin_id"
wait_for_text "The server could not be told"
tap_text "Remove anyway"
wait_for_text "Removed $admin_id on $API_HOST."
wait_for_text "Not enrolled"
[[ -n $(store fid "$admin_id") ]] || { echo "the server lost the device after a local-only removal" >&2; exit 1; }
echo "removed anyway; the server still holds the device"
installation_deleted "$fid"
echo "removing the last account deleted the Firebase installation"
