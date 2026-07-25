#!/usr/bin/env python3
"""Drive a coding task against an OpenAI-compatible endpoint (llama-server / vLLM / Ollama).

Measures TTFT and generation throughput, strips reasoning blocks, and materialises
the generated files on disk so they can be compiled and tested.

Stdlib only — the benchmark box should not need pip.
"""
import argparse
import json
import os
import re
import sys
import time
import urllib.request

FILE_RE = re.compile(r"^###\s*FILE:\s*(\S+)\s*$", re.MULTILINE)
FENCE_RE = re.compile(r"```[a-zA-Z0-9_+-]*\n(.*?)```", re.DOTALL)
THINK_RE = re.compile(r"<think>.*?</think>", re.DOTALL)


def stream_completion(base_url, model, prompt, temperature, top_p, top_k, max_tokens):
    """POST a streaming chat completion. Returns (content, reasoning, metrics)."""
    body = {
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
        "temperature": temperature,
        "top_p": top_p,
        "top_k": top_k,
        "max_tokens": max_tokens,
        "stream": True,
        "stream_options": {"include_usage": True},
    }
    req = urllib.request.Request(
        base_url.rstrip("/") + "/v1/chat/completions",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "Authorization": "Bearer no-key"},
    )

    content, reasoning = [], []
    ttft = None
    usage, timings = {}, {}
    t0 = time.perf_counter()

    with urllib.request.urlopen(req, timeout=3600) as resp:
        for raw in resp:
            line = raw.decode("utf-8", "replace").strip()
            if not line.startswith("data:"):
                continue
            payload = line[5:].strip()
            if payload == "[DONE]":
                break
            try:
                chunk = json.loads(payload)
            except json.JSONDecodeError:
                continue
            if chunk.get("usage"):
                usage = chunk["usage"]
            # llama.cpp reports its own timings block on the final chunk; keep it as a
            # fallback when the server does not honour stream_options.include_usage.
            if chunk.get("timings"):
                timings.update(chunk["timings"])
            for choice in chunk.get("choices", []):
                delta = choice.get("delta") or {}
                # llama.cpp emits reasoning separately when --reasoning-format is used,
                # and inline <think> tags otherwise. Handle both.
                if delta.get("reasoning_content"):
                    reasoning.append(delta["reasoning_content"])
                    if ttft is None:
                        ttft = time.perf_counter() - t0
                if delta.get("content"):
                    content.append(delta["content"])
                    if ttft is None:
                        ttft = time.perf_counter() - t0

    total = time.perf_counter() - t0
    text = "".join(content)
    think = "".join(reasoning)

    # Inline <think> blocks land in content; split them out so they are measured
    # as reasoning but excluded from the file-extraction pass.
    inline = THINK_RE.findall(text)
    if inline:
        think += "".join(inline)
        text = THINK_RE.sub("", text)
    # An unterminated <think> (hit max_tokens mid-reasoning) leaves a dangling open tag.
    if "<think>" in text and "</think>" not in text:
        head, _, tail = text.partition("<think>")
        think += tail
        text = head

    prompt_tokens = usage.get("prompt_tokens") or timings.get("prompt_n")
    completion_tokens = usage.get("completion_tokens") or timings.get("predicted_n")

    metrics = {
        "ttft_s": round(ttft, 3) if ttft is not None else None,
        "total_s": round(total, 3),
        "prompt_tokens": prompt_tokens,
        "completion_tokens": completion_tokens,
        "reasoning_chars": len(think),
        "answer_chars": len(text),
    }
    if timings:
        metrics["prompt_tok_s"] = round(timings.get("prompt_per_second", 0), 2) or None
        metrics["predicted_tok_s"] = round(timings.get("predicted_per_second", 0), 2) or None
    if completion_tokens and total > 0:
        metrics["output_tok_s_wall"] = round(completion_tokens / total, 2)
    return text.strip(), think, metrics


def extract_files(text):
    """Parse `### FILE: path` + fenced block pairs into {path: content}."""
    files, marks = {}, list(FILE_RE.finditer(text))
    for i, m in enumerate(marks):
        end = marks[i + 1].start() if i + 1 < len(marks) else len(text)
        fence = FENCE_RE.search(text, m.end(), end)
        if fence:
            files[m.group(1)] = fence.group(1)
    return files


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://127.0.0.1:8080")
    ap.add_argument("--model", default="local")
    ap.add_argument("--prompt-file", required=True)
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--label", required=True)
    ap.add_argument("--temperature", type=float, default=0.6)
    ap.add_argument("--top-p", type=float, default=0.95)
    ap.add_argument("--top-k", type=int, default=20)
    ap.add_argument("--max-tokens", type=int, default=16384)
    args = ap.parse_args()

    prompt = open(args.prompt_file, encoding="utf-8").read()
    out = os.path.join(args.out_dir, args.label)
    os.makedirs(os.path.join(out, "raw"), exist_ok=True)

    print(f"[{args.label}] requesting…", flush=True)
    text, think, metrics = stream_completion(
        args.base_url, args.model, prompt,
        args.temperature, args.top_p, args.top_k, args.max_tokens,
    )

    with open(os.path.join(out, "raw", "answer.md"), "w", encoding="utf-8") as f:
        f.write(text)
    with open(os.path.join(out, "raw", "reasoning.md"), "w", encoding="utf-8") as f:
        f.write(think)

    files = extract_files(text)
    metrics["files_extracted"] = sorted(files)
    for path, content in files.items():
        # Reject path traversal — the model controls these names.
        dest = os.path.normpath(os.path.join(out, "project", path))
        if not dest.startswith(os.path.join(out, "project")):
            print(f"  !! refusing unsafe path {path}", file=sys.stderr)
            continue
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with open(dest, "w", encoding="utf-8") as f:
            f.write(content)
        print(f"  wrote {path} ({len(content)} bytes)")

    with open(os.path.join(out, "metrics.json"), "w", encoding="utf-8") as f:
        json.dump(metrics, f, indent=2)
    print(json.dumps(metrics, indent=2))


if __name__ == "__main__":
    main()
