#!/usr/bin/env bash
# Throughput sweep with llama-bench.
#   pp = prompt processing (tok/s), tg = token generation (tok/s)
#   -d N measures with N tokens already in the KV cache, which is what actually
#        matters for a coding agent: performance at depth, not at depth 0.
set -euo pipefail

BIN=${BIN:-/workspace/llama.cpp/build/bin/llama-bench}
MODELS_DIR=${MODELS_DIR:-/workspace/models}
OUT=${OUT:-/workspace/results/llama-bench}
mkdir -p "$OUT"

for M in "$@"; do
  NAME=$(basename "$M" .gguf)
  echo "=== $NAME ==="
  "$BIN" -m "$MODELS_DIR/$M" \
    -ngl 99 -fa 1 \
    -p 512,4096 -n 128 \
    -d 0,8192,32768 \
    -r 3 \
    -o md | tee "$OUT/${NAME}.md"
  echo
done
