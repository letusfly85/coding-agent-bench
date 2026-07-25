#!/usr/bin/env bash
# Python has no build step, so "build" is an import check: does the app module
# load at all? That separates syntax/import errors from behavioural test failures.
set -uo pipefail
source "$(dirname "$0")/../../scripts/verify_common.sh"
PY=${PY:-/workspace/venv/bin/python}

PROJ=${1:?usage: verify.sh <project-dir>}
cd "$PROJ" || { verdict missing missing; exit 2; }

BUILD=fail; TEST=skip
run_step "import check" /tmp/v_build.log "$PY" -c "import app.main; print(app.main.app)" && BUILD=pass
[ "$BUILD" = pass ] && { run_step "pytest" /tmp/v_test.log "$PY" -m pytest -q && TEST=pass || TEST=fail; }
verdict "$BUILD" "$TEST"
