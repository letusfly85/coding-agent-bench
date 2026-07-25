#!/usr/bin/env bash
# End-to-end: start llama-server, run every task once, verify each generated project.
set -uo pipefail

# Sampling defaults follow the model card. Override per model, e.g.
#   TEMP=1.0 TOP_K=40 ...   (Qwen3-Coder-Next)
#   TEMP=0.6 TOP_K=20 ...   (Qwen3.6-27B thinking, coding preset)
TEMP=${TEMP:-0.6}
TOP_P=${TOP_P:-0.95}
TOP_K=${TOP_K:-20}
MAX_TOKENS=${MAX_TOKENS:-24000}

REPO=${REPO:-/workspace/coding-agent-bench}
MODEL=${MODEL:-/workspace/models/Qwen3.6-27B-NEO-CODE-HERE-2T-OT-Q4_K_M.gguf}
OUT=${OUT:-/workspace/results/tasks}
PORT=${PORT:-8080}
CTX=${CTX:-32768}
# On a network filesystem mmap can hang the loader; set LOAD_MODE=none there.
LOAD_MODE=${LOAD_MODE:-}
LOAD_ARGS=(); [ -n "$LOAD_MODE" ] && LOAD_ARGS=(--load-mode "$LOAD_MODE")
TASKS=${TASKS:-"rust-axum-rest go-nethttp-rest python-fastapi-rest scala-http4s-rest"}

mkdir -p "$OUT"

echo "=== starting llama-server (ctx=$CTX) ==="
setsid nohup /workspace/llama.cpp/build/bin/llama-server \
  -m "$MODEL" "${LOAD_ARGS[@]}" -ngl 99 -fa 1 -c "$CTX" -np 1 \
  --host 127.0.0.1 --port "$PORT" --jinja \
  --temp "$TEMP" --top-p "$TOP_P" --top-k "$TOP_K" \
  </dev/null > /workspace/results/server.log 2>&1 &

for _ in $(seq 1 120); do
  sleep 2
  grep -q "listening on http" /workspace/results/server.log && break
done
grep -q "listening on http" /workspace/results/server.log \
  || { echo "server failed to start"; tail -20 /workspace/results/server.log; exit 1; }
echo "server up"
nvidia-smi --query-gpu=memory.used --format=csv,noheader | sed 's/^/VRAM in use: /'

for T in $TASKS; do
  echo
  echo "##################### $T #####################"
  python3 "$REPO/bench/scripts/run_coding_task.py" \
    --base-url "http://127.0.0.1:$PORT" \
    --prompt-file "$REPO/bench/tasks/$T/PROMPT.md" \
    --out-dir "$OUT" --label "$T" \
    --temperature "$TEMP" --top-p "$TOP_P" --top-k "$TOP_K" --max-tokens "$MAX_TOKENS"

  echo "----- verify $T -----"
  bash "$REPO/bench/tasks/$T/verify.sh" "$OUT/$T/project" \
    > "$OUT/$T/verify.log" 2>&1
  grep -E "^VERDICT" "$OUT/$T/verify.log" || echo "VERDICT build=? test=? (see verify.log)"
done

echo
echo "=== ALL TASKS DONE ==="
for T in $TASKS; do
  printf "%-24s %s\n" "$T" "$(grep -hE '^VERDICT' "$OUT/$T/verify.log" 2>/dev/null || echo 'n/a')"
done
pkill -x llama-server
