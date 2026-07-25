#!/usr/bin/env bash
# Shared verification helpers.
#
# run_step <name> <logfile> <cmd...>  — runs the command, echoes a trimmed log,
# and returns the command's real exit status (not the tail's, which is why the
# output is written to a file first rather than piped).
run_step () {
  local name=$1 log=$2; shift 2
  echo "=== $name ==="
  "$@" > "$log" 2>&1
  local rc=$?
  tail -40 "$log"
  return $rc
}

verdict () {
  echo "VERDICT build=$1 test=$2"
  [ "$1" = pass ] && [ "$2" = pass ]
}
