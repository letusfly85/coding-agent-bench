#!/usr/bin/env bash
# Measure steady-state VRAM at a range of context sizes, with and without a
# quantised KV cache. What matters for an agent is the largest context that fits,
# so each probe starts a real llama-server and reads nvidia-smi once it is up.
set -uo pipefail

BIN=${BIN:-/workspace/llama.cpp/build/bin/llama-server}
MODEL=${MODEL:?set MODEL=/path/to/model.gguf}
OUT=${OUT:-./vram.tsv}
PORT=${PORT:-8099}
# On a network filesystem (FUSE/NFS/MooseFS) mmap can hang the loader outright;
# set LOAD_MODE=none there. Empty means keep llama.cpp's default (mmap).
LOAD_MODE=${LOAD_MODE:-}

printf "ctx\tkv_type\tvram_MiB\tstatus\n" > "$OUT"

probe () {
  local CTX=$1 KV=$2
  local ARGS=(-m "$MODEL" -ngl 99 -fa 1 -c "$CTX" -np 1 --host 127.0.0.1 --port "$PORT")
  [ -n "$LOAD_MODE" ] && ARGS+=(--load-mode "$LOAD_MODE")
  [ "$KV" != "f16" ] && ARGS+=(--cache-type-k "$KV" --cache-type-v "$KV")

  setsid nohup "$BIN" "${ARGS[@]}" </dev/null > /tmp/srv.log 2>&1 &
  local PID=$! OK=timeout
  for _ in $(seq 1 180); do
    sleep 2
    if grep -qE "listening on http|model loaded" /tmp/srv.log 2>/dev/null; then OK=ok; break; fi
    if grep -qiE "out of memory|CUDA error|failed to" /tmp/srv.log 2>/dev/null; then OK=oom; break; fi
    kill -0 $PID 2>/dev/null || { OK=died; break; }
  done

  local MEM=0
  # Settle before reading — allocation is not complete the instant the port opens.
  [ "$OK" = ok ] && { sleep 3; MEM=$(nvidia-smi --query-gpu=memory.used --format=csv,noheader,nounits); }
  printf "%s\t%s\t%s\t%s\n" "$CTX" "$KV" "$MEM" "$OK" | tee -a "$OUT"

  pkill -x llama-server 2>/dev/null
  # Wait for the driver to actually release the memory. A 45GB model takes well
  # over a fixed sleep to unmap, and starting the next probe early makes it look
  # like a timeout when it is really contention with the previous process.
  for _ in $(seq 1 60); do
    sleep 2
    [ "$(nvidia-smi --query-gpu=memory.used --format=csv,noheader,nounits)" -lt 2000 ] && break
  done
}

for CTX in 8192 32768 131072 262144; do probe "$CTX" f16; done
for CTX in 131072 262144; do probe "$CTX" q8_0; done

echo "=== VRAM SWEEP DONE ==="
cat "$OUT"
