#!/usr/bin/env bash
# Regenerates app/src/test/resources/signing-vectors.json with frict-server's
# own signing and verification code, from REF (default origin/main) of a
# frict-server clone. Needs docker; runs Go in golang:1.27.3.
#
# Usage: scripts/gen-signing-vectors.sh FRICT_SERVER_REPO [REF]
set -euo pipefail
[[ $# -ge 1 ]] || { sed -n '2,6p' "$0"; exit 2; }
root=$(cd "$(dirname "$0")/.." && pwd)
server=$1
commit=$(git -C "$server" rev-parse --verify "${2:-origin/main}^{commit}")

src=$(mktemp -d)
trap 'rm -rf "$src"' EXIT
git -C "$server" archive "$commit" | tar -x -C "$src"
mkdir "$src/cmd/signing-vectors"
cp "$root/scripts/signing-vectors/main.go" "$src/cmd/signing-vectors/"

docker run --rm -v "$src":/src -w /src -u "$(id -u):$(id -g)" \
  -e HOME=/tmp -e GOCACHE=/tmp/gocache \
  golang:1.27.3 go run ./cmd/signing-vectors "$commit" \
  >"$root/app/src/test/resources/signing-vectors.json"
