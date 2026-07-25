#!/usr/bin/env bash
# Verify a generated project: does it build, and do its tests pass?
# Exits non-zero on failure but always prints a machine-readable verdict line.
set -uo pipefail

PROJ=${1:?usage: verify.sh <project-dir>}
cd "$PROJ" || { echo "VERDICT build=missing test=missing"; exit 2; }

BUILD=fail
TEST=skip

echo "=== cargo build ==="
cargo build 2>&1 | tail -40
if cargo build >/dev/null 2>&1; then BUILD=pass; fi

if [ "$BUILD" = pass ]; then
  echo "=== cargo test ==="
  if cargo test 2>&1 | tail -40; then TEST=pass; else TEST=fail; fi
fi

echo "VERDICT build=$BUILD test=$TEST"
[ "$BUILD" = pass ] && [ "$TEST" = pass ]
