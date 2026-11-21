#!/usr/bin/env bash
# The app against a real frict-server, which runs at FRICT_SERVER_COMMIT
# behind TLS from a throwaway test CA on 127.0.0.1:443, which the emulator
# reaches as 10.0.2.2. Every server call is Duo-signed, as Authentik makes it.
#
# 1. Enroll: creates an activation (/auth/v2/enroll), opens the link in the
#    debug app, picks MODE's unlock, taps Enroll, and requires
#    /auth/v2/enroll_status to go from waiting to success.
# 2. Approve: starts a login (/auth/v2/auth), requires the app to show its
#    details before the prompt, taps Approve, unlocks the prompt (screen-lock:
#    types PIN; fingerprint: touches the sensor), and requires /auth to return
#    allow.
# fingerprint only:
# 3. Not me: the same, denied with "Not me": /auth must return deny with status
#    fraud, and the next /auth must be refused because the server paused
#    pushes after the fraud report.
# 4. Alert: the e2e user is its own alert recipient, so its poll must list the
#    fraud report, with both Authentik links tappable.
#
# Needs docker and a booted, rootable emulator in ANDROID_SERIAL with screen
# lock PIN; fingerprint also needs a strong biometric enrolled
# (scripts/emulator.sh does both).
#
# Usage: PIN=… scripts/e2e.sh screen-lock|fingerprint
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
FRICT_SERVER_COMMIT=${FRICT_SERVER_COMMIT:-7b80fe736a33fa9f80f7af241a62d0a884df0dd0}
DIAGNOSTICS_DIR=${DIAGNOSTICS_DIR:-$ROOT/app/build/emulator-diagnostics}
: "${ANDROID_SERIAL:?set ANDROID_SERIAL to the booted emulator}"
API_HOST=10.0.2.2
CLIENT_ID=DIFRICTANDROIDE2E000
PACKAGE=app.frict.android
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
mkdir "$src/cmd/duo"
cp "$ROOT/scripts/duo/main.go" "$src/cmd/duo/"
docker build -q -t "$NAME" -f "$src/e2e/frict-server.Dockerfile" "$src" >/dev/null
docker run --rm -v "$src":/src -v "$work":/out -w /src -u "$(id -u):$(id -g)" \
  -e HOME=/tmp -e GOCACHE=/tmp/gocache -e CGO_ENABLED=0 \
  golang:1.27.3 go build -o /out/duo ./cmd/duo

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

docker network create "$NAME" >/dev/null
docker run -d --name "$NAME-server" --network "$NAME" --network-alias frict-server \
  --tmpfs /data:uid=65532,gid=65532,mode=0700 -v "$secrets":/secrets:ro \
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

# Android trusts user-added CAs from this directory, named by OpenSSL's old subject hash.
ca_name=$(openssl x509 -subject_hash_old -noout -in "$secrets/ca.pem").0
adb_root
adb push "$secrets/ca.pem" "/data/local/tmp/$ca_name" >/dev/null 2>&1
adb shell "mkdir -p /data/misc/user/0/cacerts-added && mv /data/local/tmp/$ca_name /data/misc/user/0/cacerts-added/ \
  && chown -R system:system /data/misc/user/0/cacerts-added && chmod 644 /data/misc/user/0/cacerts-added/$ca_name \
  && restorecon -R /data/misc/user/0/cacerts-added" >/dev/null 2>&1

(cd "$ROOT" && ./gradlew installDebug --console=plain -q)
adb shell pm clear "$PACKAGE" >/dev/null

link=$(duo enroll android-e2e)
user_id=$(sed -n 's/.*[?&]user_id=\([^&]*\).*/\1/p' <<<"$link")
echo "activation for $user_id"
before=$(duo status "$user_id" "$link")
[[ $before == waiting ]] || { echo "enroll_status before the app enrolled: $before, want waiting" >&2; exit 1; }

disable_soft_keyboards
unlock_screen "$PIN"
adb shell am start -W -a android.intent.action.VIEW -d "'$link'" "$PACKAGE" >/dev/null
wait_for_text Enroll
[[ $MODE == fingerprint ]] && tap_text "$MODE_LABEL"
tap_text Enroll
for ((i = 0; i < 30; i++)); do
  after=$(duo status "$user_id" "$link")
  [[ $after == waiting ]] || break
  sleep 1
done
echo "enroll_status after the app enrolled: $after"
[[ $after == success ]] || { echo "enroll_status after the app enrolled: $after, want success" >&2; exit 1; }
wait_for_text "Enrolled as $user_id with $API_HOST"
wait_for_text "Approve sign-ins with: $MODE_LABEL. To change, enroll again."

# unlock_prompt TITLE: answers the prompt titled TITLE the way MODE allows.
unlock_prompt() {
  if [[ $MODE == fingerprint ]]; then
    touch_fingerprint_until_gone "$1"
  else
    enter_pin_until_gone "$1" "$PIN"
  fi
}

# login ANSWER_BUTTON PROMPT_TITLE WANT: starts a login, waits for the app to
# show it, answers it through the prompt, and requires /auth's
# "<result> <status>: <status_msg>" to start with WANT.
login() {
  local out=$work/auth-$RANDOM pid got
  duo auth "$user_id" android-e2e >"$out" &
  pid=$!
  # The details must be on screen before anything asks to unlock.
  wait_for_text "Sign-in request" 60
  wait_for_text "User: android-e2e"
  wait_for_text "From: 203.0.113.7"
  wait_for_text "Application: Frict Android e2e"
  tap_text "$1"
  wait_for_text "$2"
  unlock_prompt "$2"
  wait "$pid"
  got=$(<"$out")
  echo "/auth after $1: $got"
  [[ $got == "$3"* ]] || { echo "/auth after $1: $got, want $3" >&2; exit 1; }
}

login Approve "Approve this sign-in" "allow allow:"
wait_for_text "Approved."
[[ $MODE == fingerprint ]] || exit 0
login "Not me" "Report this sign-in as not you" "deny fraud:"
wait_for_text "Denied and reported as not you."
paused=$(duo auth "$user_id" android-e2e)
echo "/auth after the fraud report: $paused"
[[ $paused == "deny locked_out: Pushes paused"* ]] ||
  { echo "/auth after the fraud report: $paused, want deny locked_out: Pushes paused…" >&2; exit 1; }

wait_for_text "Sign-in reported as not theirs" 30
wait_for_text "Reported by android-e2e"
for link in "Reset password" "Revoke sessions"; do
  wait_for_text "$link"
  node=$(grep -m1 -iF "text=\"$link\"" <<<"$(ui_dump)")
  [[ $node == *'clickable="true"'* ]] || { echo "\"$link\" is on screen but not tappable: $node" >&2; exit 1; }
done
echo "alert shown with tappable links"
