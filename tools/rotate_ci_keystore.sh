#!/usr/bin/env bash
# tools/rotate_ci_keystore.sh — generate a fresh DETERMINISTIC CI keystore.
#
# Run once (locally or in CI shell) and commit app/ci-release.keystore.
# Requires: keytool (JDK). Every rebuild with the same keystore keeps the
# same certificate fingerprint, so Android accepts updates over prior builds.
#
# Usage: bash tools/rotate_ci_keystore.sh
set -euo pipefail

OUT="app/ci-release.keystore"

if ! command -v keytool >/dev/null 2>&1; then
  echo "ERROR: keytool not found (install a JDK first)" >&2
  exit 1
fi

if [ -f "$OUT" ]; then
  echo "ERROR: $OUT already exists. Delete it first if you really want to rotate." >&2
  echo "WARNING: rotating the key BREAKS update-install over older APKs." >&2
  exit 1
fi

keytool -genkeypair \
  -keystore "$OUT" \
  -storepass "devcode-ci" \
  -keypass "devcode-ci" \
  -alias "devcode" \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10950 \
  -storetype PKCS12 \
  -dname "CN=DEVCODE CI, OU=DEVCODE, O=DEVCODE, L=Jakarta, ST=Jakarta, C=ID"

echo "Wrote $OUT — commit it to the repo."
