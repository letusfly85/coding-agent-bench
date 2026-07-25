#!/usr/bin/env bash
set -uo pipefail
source "$(dirname "$0")/../../scripts/verify_common.sh"

PROJ=${1:?usage: verify.sh <project-dir>}
cd "$PROJ" || { verdict missing missing; exit 2; }

BUILD=fail; TEST=skip
run_step "scala-cli compile" /tmp/v_build.log scala-cli --power compile . && BUILD=pass
[ "$BUILD" = pass ] && { run_step "scala-cli test" /tmp/v_test.log scala-cli --power test . && TEST=pass || TEST=fail; }
verdict "$BUILD" "$TEST"
