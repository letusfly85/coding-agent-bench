#!/usr/bin/env bash
# Repeat every task N times against an already-running llama-server.
#
# One sample per task cannot separate "this quant is worse" from "temperature 0.6
# rolled badly". Anything reported as a pass rate needs this.
set -uo pipefail

# Sampling defaults follow the model card. Override per model, e.g.
#   TEMP=1.0 TOP_K=40 ...   (Qwen3-Coder-Next)
#   TEMP=0.6 TOP_K=20 ...   (Qwen3.6-27B thinking, coding preset)
TEMP=${TEMP:-0.6}
TOP_P=${TOP_P:-0.95}
TOP_K=${TOP_K:-20}
MAX_TOKENS=${MAX_TOKENS:-24000}

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
      --temperature "$TEMP" --top-p "$TOP_P" --top-k "$TOP_K" --max-tokens "$MAX_TOKENS" \
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
