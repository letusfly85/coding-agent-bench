#!/usr/bin/env bash
# Repeat every task N times against an already-running llama-server.
#
# One sample per task cannot separate "this quant is worse" from "temperature 0.6
# rolled badly". Anything reported as a pass rate needs this.
set -uo pipefail

REPO=${REPO:-/workspace/coding-agent-bench}
OUT=${OUT:-/workspace/results/repeat}
PORT=${PORT:-8080}
N=${N:-5}
TASKS=${TASKS:-"rust-axum-rest go-nethttp-rest python-fastapi-rest scala-http4s-rest"}

mkdir -p "$OUT"
for T in $TASKS; do
  for i in $(seq 1 "$N"); do
    L="${T}-run${i}"
    echo "##################### $L #####################"
    python3 "$REPO/bench/scripts/run_coding_task.py" \
      --base-url "http://127.0.0.1:$PORT" \
      --prompt-file "$REPO/bench/tasks/$T/PROMPT.md" \
      --out-dir "$OUT" --label "$L" \
      --temperature 0.6 --top-p 0.95 --top-k 20 --max-tokens 24000 \
      > "$OUT/$L.gen.log" 2>&1
    bash "$REPO/bench/tasks/$T/verify.sh" "$OUT/$L/project" > "$OUT/$L/verify.log" 2>&1
    echo "$L $(grep -hE '^VERDICT' "$OUT/$L/verify.log" 2>/dev/null || echo 'VERDICT build=? test=?')"
  done
done

echo "=== REPEAT DONE ==="
for T in $TASKS; do
  P=0
  for i in $(seq 1 "$N"); do
    grep -q "VERDICT build=pass test=pass" "$OUT/${T}-run${i}/verify.log" 2>/dev/null && P=$((P+1))
  done
  printf "%-24s pass@1: %d/%d\n" "$T" "$P" "$N"
done
