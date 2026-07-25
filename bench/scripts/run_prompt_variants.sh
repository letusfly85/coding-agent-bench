#!/usr/bin/env bash
# Run every prompt variant of one task N times, against an already-running llama-server.
#
# The point is to locate where a failing task starts passing: with the correct
# imports handed over? with the API traps spelled out? only with a skeleton?
# That distinguishes "does not know this library's surface" from "cannot reason
# about it".
#
#   TASK=scala-http4s-rest N=5 run_prompt_variants.sh
set -uo pipefail

TEMP=${TEMP:-0.6}
TOP_P=${TOP_P:-0.95}
TOP_K=${TOP_K:-20}
MAX_TOKENS=${MAX_TOKENS:-24000}

REPO=${REPO:-/workspace/coding-agent-bench}
TASK=${TASK:-scala-http4s-rest}
OUT=${OUT:-/workspace/results/variants}
PORT=${PORT:-8080}
N=${N:-5}
VARIANTS=${VARIANTS:-"a-baseline b-imports c-cheatsheet d-skeleton"}

mkdir -p "$OUT"
for V in $VARIANTS; do
  P="$REPO/bench/tasks/$TASK/variants/$V.md"
  [ -f "$P" ] || { echo "missing prompt: $P"; continue; }
  for i in $(seq 1 "$N"); do
    L="${V}-run${i}"
    echo "##################### $L #####################"
    python3 "$REPO/bench/scripts/run_coding_task.py" \
      --base-url "http://127.0.0.1:$PORT" \
      --prompt-file "$P" \
      --out-dir "$OUT" --label "$L" \
      --temperature "$TEMP" --top-p "$TOP_P" --top-k "$TOP_K" --max-tokens "$MAX_TOKENS" \
      > "$OUT/$L.gen.log" 2>&1
    bash "$REPO/bench/tasks/$TASK/verify.sh" "$OUT/$L/project" > "$OUT/$L/verify.log" 2>&1
    echo "$L $(grep -hE '^VERDICT' "$OUT/$L/verify.log" 2>/dev/null || echo 'VERDICT build=? test=?')"
  done
done

echo "=== VARIANTS DONE ==="
for V in $VARIANTS; do
  B=0; T=0
  for i in $(seq 1 "$N"); do
    V_LOG="$OUT/${V}-run${i}/verify.log"
    grep -q "build=pass" "$V_LOG" 2>/dev/null && B=$((B+1))
    grep -q "test=pass"  "$V_LOG" 2>/dev/null && T=$((T+1))
  done
  printf "%-16s build %d/%d   test %d/%d\n" "$V" "$B" "$N" "$T" "$N"
done
