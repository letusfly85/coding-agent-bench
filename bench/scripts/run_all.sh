#!/usr/bin/env bash
# End-to-end: start llama-server, run every task once, verify each generated project.
set -uo pipefail

REPO=${REPO:-/workspace/coding-agent-bench}
MODEL=${MODEL:-/workspace/models/Qwen3.6-27B-NEO-CODE-HERE-2T-OT-Q4_K_M.gguf}
OUT=${OUT:-/workspace/results/tasks}
PORT=${PORT:-8080}
CTX=${CTX:-32768}
TASKS=${TASKS:-"rust-axum-rest go-nethttp-rest python-fastapi-rest scala-http4s-rest"}

mkdir -p "$OUT"

echo "=== starting llama-server (ctx=$CTX) ==="
setsid nohup /workspace/llama.cpp/build/bin/llama-server \
  -m "$MODEL" -ngl 99 -fa 1 -c "$CTX" -np 1 \
  --host 127.0.0.1 --port "$PORT" --jinja \
  --temp 0.6 --top-p 0.95 --top-k 20 \
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
    --temperature 0.6 --top-p 0.95 --top-k 20 --max-tokens 24000

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
