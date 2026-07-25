#!/usr/bin/env bash
set -uo pipefail
source "$(dirname "$0")/../../scripts/verify_common.sh"
export PATH=/usr/local/go/bin:$PATH

PROJ=${1:?usage: verify.sh <project-dir>}
cd "$PROJ" || { verdict missing missing; exit 2; }

BUILD=fail; TEST=skip
run_step "go vet" /tmp/v_vet.log go vet ./... || true
run_step "go build" /tmp/v_build.log go build ./... && BUILD=pass
[ "$BUILD" = pass ] && { run_step "go test" /tmp/v_test.log go test ./... && TEST=pass || TEST=fail; }
verdict "$BUILD" "$TEST"
