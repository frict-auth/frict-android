#!/usr/bin/env bash
# Fails unless the AAB is signed by the upload key in $KEYSTORE_FILE, and that key is not app/debug.keystore's.
set -euo pipefail
cd "$(dirname "$0")/.."

aab=${1:-app/build/outputs/bundle/release/app-release.aab}

digest() { openssl x509 -noout -fingerprint -sha256 | cut -d= -f2; }

want=$(keytool -exportcert -rfc -keystore "${KEYSTORE_FILE:?}" -storepass "${KEYSTORE_PASSWORD:?}" -alias "${KEY_ALIAS:?}" \
  | digest)
debug=$(keytool -exportcert -rfc -keystore app/debug.keystore -storepass android -alias androiddebugkey | digest)

if [[ "$want" == "$debug" ]]; then
  echo "KEYSTORE_FILE holds the debug key ($debug); release needs the upload key" >&2
  exit 1
fi

verify=$(jarsigner -verify "$aab")
if [[ "$verify" != *"jar verified."* ]]; then
  echo "$verify" >&2
  echo "$aab has no valid signature" >&2
  exit 1
fi
got=$(unzip -p "$aab" 'META-INF/*.RSA' | openssl pkcs7 -inform DER -print_certs | digest)

if [[ "$got" != "$want" ]]; then
  echo "$aab is signed by SHA-256 '$got', expected the upload key's $want" >&2
  exit 1
fi
echo "$aab signed by the upload key ($want)"
