#!/usr/bin/env python3
"""Single-turn tool-calling fidelity.

Checks the things that break an agent loop on turn one: does it call a tool when
it should, pick the right one, emit parseable arguments that match the schema —
and equally important, does it stay quiet when no tool is warranted.

Stdlib only.
"""
import argparse
import json
import time
import urllib.request
from collections import Counter

TOOLS = [
    {"type": "function", "function": {
        "name": "list_dir",
        "description": "List the files in a directory.",
        "parameters": {"type": "object",
                       "properties": {"path": {"type": "string"}},
                       "required": ["path"]}}},
    {"type": "function", "function": {
        "name": "read_file",
        "description": "Read the contents of a file.",
        "parameters": {"type": "object",
                       "properties": {"path": {"type": "string"},
                                      "start_line": {"type": "integer"},
                                      "end_line": {"type": "integer"}},
                       "required": ["path"]}}},
    {"type": "function", "function": {
        "name": "write_file",
        "description": "Write content to a file, overwriting it.",
        "parameters": {"type": "object",
                       "properties": {"path": {"type": "string"},
                                      "content": {"type": "string"}},
                       "required": ["path", "content"]}}},
    {"type": "function", "function": {
        "name": "run_command",
        "description": "Run a shell command in the project root and return its output.",
        "parameters": {"type": "object",
                       "properties": {"command": {"type": "string"},
                                      "timeout_sec": {"type": "integer"}},
                       "required": ["command"]}}},
    {"type": "function", "function": {
        "name": "search_code",
        "description": "Search the repository for a regular expression.",
        "parameters": {"type": "object",
                       "properties": {"pattern": {"type": "string"},
                                      "glob": {"type": "string"}},
                       "required": ["pattern"]}}},
]

TOOL_SCHEMAS = {t["function"]["name"]: t["function"]["parameters"] for t in TOOLS}

# expected == None means: answering directly is correct, calling a tool is a false positive.
CASES = [
    ("List everything under the src directory.", "list_dir"),
    ("Show me what's in Cargo.toml.", "read_file"),
    ("Save the text 'hello' into notes.txt.", "write_file"),
    ("Run the test suite and tell me if it passes.", "run_command"),
    ("Find every place we call `unwrap()` in this repo.", "search_code"),
    ("Read lines 40 through 80 of src/main.rs.", "read_file"),
    ("What files sit at the top level of the project?", "list_dir"),
    ("Create a .gitignore containing the single line 'target/'.", "write_file"),
    ("What is the difference between a Vec and a VecDeque in Rust?", None),
    ("Explain what HTTP status code 204 means.", None),
]

TYPE_MAP = {"string": str, "integer": int, "number": (int, float), "boolean": bool}


def check_args(tool_name, raw_args):
    """Return (parsed_ok, schema_ok, detail)."""
    try:
        args = json.loads(raw_args) if isinstance(raw_args, str) else raw_args
    except (json.JSONDecodeError, TypeError) as e:
        return False, False, f"unparseable: {e}"
    if not isinstance(args, dict):
        return False, False, "arguments is not an object"

    schema = TOOL_SCHEMAS.get(tool_name)
    if schema is None:
        return True, False, "unknown tool"

    missing = [k for k in schema.get("required", []) if k not in args]
    if missing:
        return True, False, f"missing required: {missing}"
    for k, v in args.items():
        spec = schema["properties"].get(k)
        if spec is None:
            return True, False, f"undeclared arg: {k}"
        want = TYPE_MAP.get(spec["type"])
        if want and not isinstance(v, want):
            return True, False, f"wrong type for {k}: {type(v).__name__} != {spec['type']}"
    return True, True, "ok"


def call(base_url, prompt, temperature, top_p, top_k, max_tokens):
    body = {
        "model": "local",
        "messages": [
            {"role": "system", "content":
             "You are a coding agent working in a project directory. "
             "Use a tool when the request requires inspecting or changing the project. "
             "Answer directly, without a tool, for general knowledge questions."},
            {"role": "user", "content": prompt},
        ],
        "tools": TOOLS, "tool_choice": "auto",
        "temperature": temperature, "top_p": top_p, "top_k": top_k,
        "max_tokens": max_tokens,
    }
    req = urllib.request.Request(
        base_url.rstrip("/") + "/v1/chat/completions",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"})
    t0 = time.perf_counter()
    with urllib.request.urlopen(req, timeout=900) as r:
        d = json.load(r)
    return d, round(time.perf_counter() - t0, 2)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://127.0.0.1:8080")
    ap.add_argument("--reps", type=int, default=3)
    ap.add_argument("--out", default="tool_fidelity.json")
    ap.add_argument("--temperature", type=float, default=0.6)
    ap.add_argument("--top-p", type=float, default=0.95)
    ap.add_argument("--top-k", type=int, default=20)
    ap.add_argument("--max-tokens", type=int, default=4096)
    args = ap.parse_args()

    records, fails = [], Counter()
    for rep in range(1, args.reps + 1):
        for prompt, expected in CASES:
            d, secs = call(args.base_url, prompt, args.temperature,
                           args.top_p, args.top_k, args.max_tokens)
            ch = d["choices"][0]
            msg = ch["message"]
            calls = msg.get("tool_calls") or []
            usage = d.get("usage", {})

            rec = {"rep": rep, "prompt": prompt, "expected": expected,
                   "secs": secs, "n_calls": len(calls),
                   "completion_tokens": usage.get("completion_tokens"),
                   "prompt_tokens": usage.get("prompt_tokens"),
                   "finish_reason": ch.get("finish_reason")}

            if expected is None:
                rec["verdict"] = "ok" if not calls else "false_positive"
                if calls:
                    rec["called"] = calls[0]["function"]["name"]
                    fails["called tool when none needed"] += 1
            elif not calls:
                rec["verdict"] = "no_call"
                fails["no tool call emitted"] += 1
            else:
                fn = calls[0]["function"]
                rec["called"] = fn["name"]
                parsed, schema_ok, detail = check_args(fn["name"], fn.get("arguments"))
                rec["args_detail"] = detail
                if fn["name"] not in TOOL_SCHEMAS:
                    rec["verdict"] = "hallucinated_tool"
                    fails["hallucinated tool name"] += 1
                elif not parsed:
                    rec["verdict"] = "bad_json"
                    fails["unparseable arguments"] += 1
                elif fn["name"] != expected:
                    rec["verdict"] = "wrong_tool"
                    fails["wrong tool selected"] += 1
                elif not schema_ok:
                    rec["verdict"] = "bad_schema"
                    fails[f"schema violation ({detail})"] += 1
                else:
                    rec["verdict"] = "ok"

            records.append(rec)
            print(f"[rep{rep}] {rec['verdict']:<18} {secs:>6.2f}s  "
                  f"{rec.get('called', '-'):<12} {prompt[:48]}", flush=True)

    n = len(records)
    ok = sum(1 for r in records if r["verdict"] == "ok")
    secs = [r["secs"] for r in records]
    toks = [r["completion_tokens"] for r in records if r["completion_tokens"]]
    summary = {
        "n": n, "ok": ok, "accuracy": round(100 * ok / n, 1),
        "failures": dict(fails),
        "latency_s": {"min": min(secs), "median": sorted(secs)[len(secs) // 2], "max": max(secs)},
        "completion_tokens": {"min": min(toks), "median": sorted(toks)[len(toks) // 2],
                              "max": max(toks)} if toks else None,
    }
    with open(args.out, "w") as f:
        json.dump({"summary": summary, "records": records}, f, indent=2, ensure_ascii=False)
    print("\n=== SUMMARY ===")
    print(json.dumps(summary, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
