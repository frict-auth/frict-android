#!/usr/bin/env bash
# The app against a real frict-server, which runs at FRICT_SERVER_COMMIT
# behind TLS from a throwaway test CA on 127.0.0.1:443, which the emulator
# reaches as 10.0.2.2. Every server call is Duo-signed, as Authentik makes it.
#
# 1. Enroll: creates an activation (/auth/v2/enroll), opens the link in the
#    debug app, taps Enroll, and requires /auth/v2/enroll_status to go from
#    waiting to success.
#    The server must hold the app's Firebase Installation ID, registered with
#    Firebase (which the dev API key's package and certificate restriction
#    allows), and get it again on the app's next start.
# 2. Push (only with FCM_ACCESS_TOKEN, an OAuth token for the dev Firebase
#    project): with the app in the background, starts a login and sends the
#    push frict-server would, through FCM to the app's FID. Its notification
#    must open the app on the challenge, fetched from the server, and Deny
#    must reach /auth.
# 3. Approve: starts a login (/auth/v2/auth), requires the app to show its
#    details before any fingerprint, taps Approve, touches the fingerprint
#    sensor, and requires /auth to return allow.
# 4. Not me: the same, denied with "Not me": /auth must return deny with status
#    fraud, and the next /auth must be refused because the server paused
#    pushes after the fraud report.
# 5. Alert: the e2e user is its own alert recipient, so its poll must list the
#    fraud report, with both Authentik links tappable. With FCM_ACCESS_TOKEN,
#    the alert's push is sent first, and its notification opens the app.
#
# Needs docker and a booted, rootable emulator in ANDROID_SERIAL with a strong
# biometric enrolled (scripts/emulator.sh does both).
#
# Usage: scripts/e2e.sh
set -Eeuo pipefail
trap 'echo "$0: line $LINENO: \"$BASH_COMMAND\" failed" >&2' ERR

ROOT=$(cd "$(dirname "$0")/.." && pwd)
FRICT_SERVER_COMMIT=${FRICT_SERVER_COMMIT:-7b80fe736a33fa9f80f7af241a62d0a884df0dd0}
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
docker build -q -t "$NAME" -f "$src/e2e/frict-server.Dockerfile" "$src" >/dev/null
docker run --rm -v "$src":/src -v "$work":/out -w /src -u "$(id -u):$(id -g)" \
  -e HOME=/tmp -e GOCACHE=/tmp/gocache -e CGO_ENABLED=0 \
  golang:1.27.3 sh -c 'go build -o /out/duo ./cmd/duo && go build -o /out/store ./cmd/store'

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
cat >"$work/tls.conf" <<CONF
server {
    listen 443 ssl;
    ssl_certificate     /secrets/frict.pem;
    ssl_certificate_key /secrets/frict.key;
    location / { proxy_pass http://frict-server:8080; }
}
CONF
chmod 0644 "$secrets"/* "$work/tls.conf"

# The server runs as this user, so the store helper can open its database.
mkdir "$work/data"
docker network create "$NAME" >/dev/null
docker run -d --name "$NAME-server" --network "$NAME" --network-alias frict-server \
  -u "$(id -u):$(id -g)" -v "$work/data":/data -v "$secrets":/secrets:ro \
  -e FRICT_API_HOSTNAME=$API_HOST -e FRICT_CLIENT_ID=$CLIENT_ID \
  -e FRICT_CLIENT_SECRET_FILE=/secrets/duo-client-secret -e FRICT_DB_PATH=/data/frict.db \
  -e FRICT_AUTHENTIK_URL=http://127.0.0.1:9000 -e FRICT_ALERT_RECIPIENTS=android-e2e "$NAME" >/dev/null
docker run -d --name "$NAME-tls" --network "$NAME" -p 127.0.0.1:443:443 \
  -v "$secrets":/secrets:ro -v "$work/tls.conf":/etc/nginx/conf.d/default.conf:ro \
  docker.io/library/nginx:1.29.3-alpine@sha256:b3c656d55d7ad751196f21b7fd2e8d4da9cb430e32f646adcf92441b72f82b14 >/dev/null
for ((i = 0; i < 30; i++)); do
  curl -fsS --cacert "$secrets/ca.pem" --connect-to "::127.0.0.1:443" "https://$API_HOST/healthz" >/dev/null 2>&1 && break
  sleep 1
done
curl -fsS --cacert "$secrets/ca.pem" --connect-to "::127.0.0.1:443" "https://$API_HOST/healthz"
echo

duo() {
  E2E_HOST=$API_HOST E2E_ADDR=127.0.0.1:443 E2E_CA=$secrets/ca.pem E2E_IKEY=$CLIENT_ID \
    E2E_SKEY_FILE=$secrets/duo-client-secret "$work/duo" "$@"
}

store() { E2E_DB=$work/data/frict.db "$work/store" "$@"; }

# app_fid: the FID Firebase Installations saved for the app, once Firebase has
# registered it.
app_fid() {
  local i json fid
  for ((i = 0; i < 60; i++)); do
    json=$(adb shell "cat /data/data/$PACKAGE/files/PersistedInstallation.*.json" 2>/dev/null) || json=
    if fid=$(python3 -I -c 'import json, sys
p = json.loads(sys.argv[1])
if p.get("Status") != 3: sys.exit(1)
print(p["Fid"])' "$json" 2>/dev/null); then
      echo "$fid"
      return 0
    fi
    sleep 1
  done
  echo "no Firebase-registered FID for $PACKAGE within 60s" >&2
  return 1
}

# wait_server_fid WANT: waits until the server stores WANT as the user's FID.
wait_server_fid() {
  local i got
  for ((i = 0; i < 30; i++)); do
    got=$(store fid "$user_id")
    [[ $got == "$1" ]] && return 0
    sleep 1
  done
  echo "server FID after 30s: \"$got\", want the app's" >&2
  return 1
}

# fcm_send DATA_JSON: sends what frict-server's push sender sends: a data
# message to the app's FID at Android priority high.
fcm_send() {
  local project body
  project=$(python3 -I -c 'import json, sys; print(json.load(open(sys.argv[1]))["project_info"]["project_id"])' \
    "$ROOT/app/src/debug/google-services.json")
  body=$(python3 -I -c 'import json, sys
print(json.dumps({"message": {"fid": sys.argv[1], "data": json.loads(sys.argv[2]), "android": {"priority": "high"}}}))' \
    "$fid" "$1")
  curl -fsS -o /dev/null -X POST "https://fcm.googleapis.com/v1/projects/$project/messages:send" \
    -H "Authorization: Bearer $FCM_ACCESS_TOKEN" -H "Content-Type: application/json" --data-binary "$body"
}

# notification_titles: titles of the app's notifications.
notification_titles() {
  adb shell dumpsys notification --noredact | tr -d '\r' | awk -v pkg="pkg=$PACKAGE " '
    /NotificationRecord\(/ { ours = index($0, pkg) > 0 }
    ours && /android\.title=String \(/ { sub(/.*android\.title=String \(/, ""); sub(/\)$/, ""); print }'
}

# open_notification TITLE: waits for the app's notification titled TITLE and taps it.
open_notification() {
  local i
  for ((i = 0; i < 30; i++)); do
    if grep -qxF "$1" <<<"$(notification_titles)"; then
      echo "notification shown: $1"
      adb shell cmd statusbar expand-notifications
      wait_for_text "$1"
      tap_text "$1"
      return 0
    fi
    sleep 1
  done
  echo "no notification titled \"$1\" from $PACKAGE within 30s; it has: $(notification_titles)" >&2
  return 1
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
adb shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS
echo "Google Play services: $(adb shell dumpsys package com.google.android.gms | tr -d '\r' | sed -n 's/^ *versionName=//p' | head -1)"

link=$(duo enroll android-e2e)
user_id=$(sed -n 's/.*[?&]user_id=\([^&]*\).*/\1/p' <<<"$link")
echo "activation for $user_id"
before=$(duo status "$user_id" "$link")
[[ $before == waiting ]] || { echo "enroll_status before the app enrolled: $before, want waiting" >&2; exit 1; }

disable_soft_keyboards
adb shell am start -W -a android.intent.action.VIEW -d "'$link'" "$PACKAGE" >/dev/null
wait_for_text Enroll
tap_text Enroll
for ((i = 0; i < 30; i++)); do
  after=$(duo status "$user_id" "$link")
  [[ $after == waiting ]] || break
  sleep 1
done
echo "enroll_status after the app enrolled: $after"
[[ $after == success ]] || { echo "enroll_status after the app enrolled: $after, want success" >&2; exit 1; }
wait_for_text "Enrolled as $user_id with $API_HOST"

fid=$(app_fid)
echo "the app has a Firebase-registered FID"
wait_server_fid "$fid"
echo "the server holds the app's FID after enrollment"
store setfid "$user_id" stale-e2e-fid
adb shell am force-stop "$PACKAGE"
adb shell am start -W -n "$PACKAGE/$ACTIVITY" >/dev/null
wait_server_fid "$fid"
echo "the server holds the app's FID again after the app's next start"

# start_login: starts a login in the background; answer waits for its result.
start_login() {
  auth_out=$work/auth-$RANDOM
  duo auth "$user_id" android-e2e >"$auth_out" &
  auth_pid=$!
}

# answer ANSWER_BUTTON PROMPT_TITLE WANT: waits for the app to show the started
# login, answers it with the fingerprint, and requires /auth's
# "<result> <status>: <status_msg>" to start with WANT.
answer() {
  local got
  # The details must be on screen before anything asks for a fingerprint.
  wait_for_text "Sign-in request" 60
  wait_for_text "User: android-e2e"
  wait_for_text "From: 203.0.113.7"
  wait_for_text "Application: Frict Android e2e"
  tap_text "$1"
  wait_for_text "$2"
  touch_fingerprint_until_gone "$2"
  wait "$auth_pid"
  got=$(<"$auth_out")
  echo "/auth after $1: $got"
  [[ $got == "$3"* ]] || { echo "/auth after $1: $got, want $3" >&2; exit 1; }
}

login() {
  start_login
  answer "$@"
}

if [[ -n ${FCM_ACCESS_TOKEN:-} ]]; then
  adb shell input keyevent KEYCODE_HOME
  start_login
  challenge_id=
  for ((i = 0; i < 30; i++)); do
    challenge_id=$(store challenge "$user_id" 2>/dev/null) && break
    sleep 1
  done
  [[ -n $challenge_id ]] || { echo "no pending challenge for $user_id within 30s" >&2; exit 1; }
  fcm_send "{\"challenge_id\": \"$challenge_id\"}"
  echo "login push sent through FCM"
  open_notification "Sign-in request"
  answer Deny "Deny this sign-in" "deny deny:"
  wait_for_text "Denied."
else
  echo "FCM_ACCESS_TOKEN is unset: no push through FCM in this run"
fi

login Approve "Approve this sign-in" "allow allow:"
wait_for_text "Approved."
login "Not me" "Report this sign-in as not you" "deny fraud:"
wait_for_text "Denied and reported as not you."
paused=$(duo auth "$user_id" android-e2e)
echo "/auth after the fraud report: $paused"
[[ $paused == "deny locked_out: Pushes paused"* ]] ||
  { echo "/auth after the fraud report: $paused, want deny locked_out: Pushes paused…" >&2; exit 1; }

if [[ -n ${FCM_ACCESS_TOKEN:-} ]]; then
  adb shell input keyevent KEYCODE_HOME
  fcm_send "{\"alert_id\": \"$(store alert "$user_id")\"}"
  echo "alert push sent through FCM"
  open_notification "Sign-in reported as not theirs"
fi
wait_for_text "Sign-in reported as not theirs" 30
wait_for_text "Reported by android-e2e"
for link in "Reset password" "Revoke sessions"; do
  wait_for_text "$link"
  node=$(grep -m1 -iF "text=\"$link\"" <<<"$(ui_dump)")
  [[ $node == *'clickable="true"'* ]] || { echo "\"$link\" is on screen but not tappable: $node" >&2; exit 1; }
done
echo "alert shown with tappable links"
