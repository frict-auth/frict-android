#!/usr/bin/env bash
# Fails unless the debug APK is signed by app/debug.keystore, not a per-machine default debug key.
set -euo pipefail
cd "$(dirname "$0")/.."

apk=app/build/outputs/apk/debug/app-debug.apk
apksigner=$(ls -d "$ANDROID_HOME"/build-tools/*/apksigner | sort -V | tail -n1)

want=$(keytool -exportcert -keystore app/debug.keystore -storepass android -alias androiddebugkey \
  | sha256sum | cut -d' ' -f1)
got=$("$apksigner" verify --print-certs "$apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')

if [[ "$got" != "$want" ]]; then
  echo "$apk is signed by SHA-256 '$got', expected app/debug.keystore's $want" >&2
  exit 1
fi
echo "$apk signed by app/debug.keystore ($want)"
