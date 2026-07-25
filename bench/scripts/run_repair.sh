#!/usr/bin/env bash
# Repair round: hand the model its own output plus the compiler's complaint.
# This is the signal that actually matters for agent use — an agent always gets
# to see the build output and try again.
#
#   TASKS="go-nethttp-rest" FROM="" TO="-repair1" run_repair.sh
#   TASKS="go-nethttp-rest" FROM="-repair1" TO="-repair2" run_repair.sh
set -uo pipefail

# Sampling defaults follow the model card. Override per model, e.g.
#   TEMP=1.0 TOP_K=40 ...   (Qwen3-Coder-Next)
#   TEMP=0.6 TOP_K=20 ...   (Qwen3.6-27B thinking, coding preset)
TEMP=${TEMP:-0.6}
TOP_P=${TOP_P:-0.95}
TOP_K=${TOP_K:-20}
MAX_TOKENS=${MAX_TOKENS:-24000}

REPO=${REPO:-/workspace/coding-agent-bench}
OUT=${OUT:-/workspace/results/tasks}
PORT=${PORT:-8080}
TASKS=${TASKS:-"go-nethttp-rest scala-http4s-rest"}
FROM=${FROM:-""}       # label suffix of the attempt being repaired
TO=${TO:-"-repair1"}   # label suffix to write

for T in $TASKS; do
  SRC="$OUT/${T}${FROM}"
  DST_LABEL="${T}${TO}"
  echo "##################### repair: $T (${FROM:-attempt1} -> $TO) #####################"

  P=/tmp/repair-$DST_LABEL.md
  {
    cat "$REPO/bench/tasks/$T/PROMPT.md"
    echo; echo "---"; echo
    echo "## Your previous attempt"; echo
    cat "$SRC/raw/answer.md"
    echo; echo "---"; echo
    echo "## It failed to build"; echo
    echo '```'
    sed 's/\x1b\[[0-9;]*m//g' "$SRC/verify.log" | head -60
    echo '```'
    echo
    echo "Fix **every** error, including any that are the same kind of mistake on other lines."
    echo "Output the complete corrected files again, in the same \`### FILE: <path>\` format."
    echo "Output only the files, no commentary."
  } > "$P"

  python3 "$REPO/bench/scripts/run_coding_task.py" \
    --base-url "http://127.0.0.1:$PORT" \
    --prompt-file "$P" \
    --out-dir "$OUT" --label "$DST_LABEL" \
    --temperature "$TEMP" --top-p "$TOP_P" --top-k "$TOP_K" --max-tokens "$MAX_TOKENS"

  echo "----- verify $DST_LABEL -----"
  bash "$REPO/bench/tasks/$T/verify.sh" "$OUT/$DST_LABEL/project" \
    > "$OUT/$DST_LABEL/verify.log" 2>&1
  grep -E "^VERDICT" "$OUT/$DST_LABEL/verify.log" || echo "VERDICT build=? test=?"
done

echo "=== REPAIR DONE ==="
