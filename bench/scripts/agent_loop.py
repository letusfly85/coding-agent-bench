#!/usr/bin/env python3
"""Multi-turn agent loop against real tools on a real sandbox.

Single-turn fidelity says nothing about whether a loop survives twenty turns.
This runs the model as an actual agent — the tools really touch the filesystem
and really run the tests — and records where it degrades.

Stdlib only.
"""
import argparse
import json
import os
import shutil
import subprocess
import time
import urllib.request

TOOLS = [
    {"type": "function", "function": {
        "name": "list_dir",
        "description": "List files in a directory, relative to the project root.",
        "parameters": {"type": "object",
                       "properties": {"path": {"type": "string"}},
                       "required": ["path"]}}},
    {"type": "function", "function": {
        "name": "read_file",
        "description": "Read a file, relative to the project root. Returns numbered lines.",
        "parameters": {"type": "object",
                       "properties": {"path": {"type": "string"}},
                       "required": ["path"]}}},
    {"type": "function", "function": {
        "name": "write_file",
        "description": "Overwrite a file with new content, relative to the project root.",
        "parameters": {"type": "object",
                       "properties": {"path": {"type": "string"},
                                      "content": {"type": "string"}},
                       "required": ["path", "content"]}}},
    {"type": "function", "function": {
        "name": "run_tests",
        "description": "Run the project's test suite and return its output.",
        "parameters": {"type": "object", "properties": {}, "required": []}}},
    {"type": "function", "function": {
        "name": "finish",
        "description": "Call this once the tests pass, to end the session.",
        "parameters": {"type": "object",
                       "properties": {"summary": {"type": "string"}},
                       "required": ["summary"]}}},
]
NAMES = {t["function"]["name"] for t in TOOLS}

SYSTEM = """You are a coding agent working in a Python project.

Tests are failing. Investigate using the tools, fix the source, re-run the tests,
and call finish once they all pass. There may be more than one bug, in more than
one file. Fix the source, never the tests.

Rules:
- Use one tool per turn.
- write_file overwrites the whole file, so always read a file before writing it.
- Do not call finish until run_tests reports success."""


class Sandbox:
    """Executes the tool calls for real, inside one directory."""

    def __init__(self, root, pytest_bin):
        self.root = os.path.abspath(root)
        self.pytest = pytest_bin

    def _resolve(self, path):
        p = os.path.normpath(os.path.join(self.root, path.lstrip("/")))
        if not p.startswith(self.root):
            raise ValueError(f"path escapes sandbox: {path}")
        return p

    def list_dir(self, path="."):
        p = self._resolve(path)
        if not os.path.isdir(p):
            return f"error: not a directory: {path}"
        out = []
        for name in sorted(os.listdir(p)):
            if name.startswith((".", "__")):
                continue
            full = os.path.join(p, name)
            out.append(f"{name}/" if os.path.isdir(full) else name)
        return "\n".join(out) or "(empty)"

    def read_file(self, path):
        p = self._resolve(path)
        if not os.path.isfile(p):
            return f"error: no such file: {path}"
        with open(p, encoding="utf-8") as f:
            return "\n".join(f"{i:4d}| {ln.rstrip()}" for i, ln in enumerate(f, 1))

    def write_file(self, path, content):
        p = self._resolve(path)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w", encoding="utf-8") as f:
            f.write(content)
        return f"wrote {path} ({len(content)} bytes)"

    def run_tests(self):
        r = subprocess.run([self.pytest, "-m", "pytest", "-q"], cwd=self.root,
                           capture_output=True, text=True, timeout=120)
        return f"exit={r.returncode}\n{(r.stdout + r.stderr)[-2500:]}"

    def tests_pass(self):
        try:
            r = subprocess.run([self.pytest, "-m", "pytest", "-q"], cwd=self.root,
                               capture_output=True, text=True, timeout=120)
            return r.returncode == 0
        except subprocess.SubprocessError:
            return False


def chat(base_url, messages, temperature, top_p, top_k, max_tokens):
    body = {"model": "local", "messages": messages, "tools": TOOLS,
            "tool_choice": "auto", "temperature": temperature,
            "top_p": top_p, "top_k": top_k, "max_tokens": max_tokens}
    req = urllib.request.Request(base_url.rstrip("/") + "/v1/chat/completions",
                                 data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    t0 = time.perf_counter()
    with urllib.request.urlopen(req, timeout=900) as r:
        return json.load(r), round(time.perf_counter() - t0, 2)


def run_episode(base_url, sandbox, max_turns, sampling):
    messages = [{"role": "system", "content": SYSTEM},
                {"role": "user", "content":
                 "The test suite is failing. Find the bugs, fix them, and make the tests pass."}]
    turns, errors = [], 0

    for turn in range(1, max_turns + 1):
        try:
            d, secs = chat(base_url, messages, *sampling)
        except Exception as e:                                   # noqa: BLE001
            turns.append({"turn": turn, "status": "http_error", "detail": str(e)[:200]})
            break

        msg = d["choices"][0]["message"]
        usage = d.get("usage", {})
        calls = msg.get("tool_calls") or []
        rec = {"turn": turn, "secs": secs,
               "completion_tokens": usage.get("completion_tokens"),
               "prompt_tokens": usage.get("prompt_tokens"),
               "n_calls": len(calls)}

        if not calls:
            # No tool call: the loop stalls. Nudge once, then treat as a stall.
            rec.update(status="no_tool_call", text=(msg.get("content") or "")[:200])
            turns.append(rec)
            errors += 1
            messages.append({"role": "assistant", "content": msg.get("content") or ""})
            messages.append({"role": "user",
                             "content": "Continue by calling a tool."})
            if errors >= 3:
                rec["status"] = "stalled"
                break
            continue

        fn = calls[0]["function"]
        name, raw = fn["name"], fn.get("arguments") or "{}"
        rec["tool"] = name

        # Echo the assistant turn back verbatim — the template needs it to stay coherent.
        messages.append({"role": "assistant", "content": msg.get("content") or "",
                         "tool_calls": [calls[0]]})

        if name not in NAMES:
            rec["status"] = "hallucinated_tool"
            result, errors = f"error: no such tool '{name}'", errors + 1
        else:
            try:
                args = json.loads(raw) if isinstance(raw, str) else raw
                rec["status"] = "ok"
            except json.JSONDecodeError as e:
                rec["status"] = "bad_json"
                rec["detail"] = str(e)[:120]
                args, errors = None, errors + 1
                result = f"error: arguments were not valid JSON: {e}"

            if args is not None:
                try:
                    result = str(getattr(sandbox, name)(**args)) if name != "finish" \
                        else f"finished: {args.get('summary', '')}"
                except TypeError as e:
                    rec["status"] = "bad_args"
                    rec["detail"] = str(e)[:120]
                    result, errors = f"error: bad arguments: {e}", errors + 1
                except Exception as e:                            # noqa: BLE001
                    rec["status"] = "tool_error"
                    result = f"error: {e}"

        rec["result_head"] = result[:120]
        messages.append({"role": "tool", "tool_call_id": calls[0]["id"],
                         "name": name, "content": result[:4000]})
        turns.append(rec)

        if name == "finish" and rec["status"] == "ok":
            break

    return {"turns": turns,
            "n_turns": len(turns),
            "called_finish": any(t.get("tool") == "finish" for t in turns),
            "tests_pass": sandbox.tests_pass(),
            "tool_errors": errors}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://127.0.0.1:8080")
    ap.add_argument("--task-dir", required=True, help="pristine project to copy per episode")
    ap.add_argument("--work-root", required=True)
    ap.add_argument("--pytest-bin", default="/workspace/venv/bin/python")
    ap.add_argument("--episodes", type=int, default=3)
    ap.add_argument("--max-turns", type=int, default=30)
    ap.add_argument("--out", required=True)
    ap.add_argument("--temperature", type=float, default=0.6)
    ap.add_argument("--top-p", type=float, default=0.95)
    ap.add_argument("--top-k", type=int, default=20)
    ap.add_argument("--max-tokens", type=int, default=8192)
    a = ap.parse_args()

    sampling = (a.temperature, a.top_p, a.top_k, a.max_tokens)
    results = []
    for ep in range(1, a.episodes + 1):
        work = os.path.join(a.work_root, f"ep{ep}")
        shutil.rmtree(work, ignore_errors=True)
        shutil.copytree(a.task_dir, work)
        print(f"\n===== episode {ep} =====", flush=True)
        r = run_episode(a.base_url, Sandbox(work, a.pytest_bin), a.max_turns, sampling)
        r["episode"] = ep
        for t in r["turns"]:
            print(f"  t{t['turn']:>2} {t.get('status', '?'):<18} "
                  f"{t.get('tool', '-'):<12} {t.get('secs', 0):>6.2f}s  "
                  f"{str(t.get('result_head', ''))[:60]!r}", flush=True)
        print(f"  -> turns={r['n_turns']} finish={r['called_finish']} "
              f"tests_pass={r['tests_pass']} tool_errors={r['tool_errors']}", flush=True)
        results.append(r)

    solved = sum(1 for r in results if r["tests_pass"])
    all_turns = [t for r in results for t in r["turns"]]
    bad = [t for t in all_turns if t.get("status") not in ("ok", None)]
    secs = [t["secs"] for t in all_turns if "secs" in t]
    summary = {
        "episodes": len(results),
        "solved": solved,
        "turns_per_episode": [r["n_turns"] for r in results],
        "total_turns": len(all_turns),
        "malformed_turns": len(bad),
        "malformed_breakdown": {s: sum(1 for t in bad if t.get("status") == s)
                                for s in {t.get("status") for t in bad}},
        "turn_latency_s": {"min": min(secs), "median": sorted(secs)[len(secs) // 2],
                           "max": max(secs)} if secs else None,
    }
    with open(a.out, "w") as f:
        json.dump({"summary": summary, "episodes": results}, f, indent=2, ensure_ascii=False)
    print("\n=== SUMMARY ===")
    print(json.dumps(summary, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
