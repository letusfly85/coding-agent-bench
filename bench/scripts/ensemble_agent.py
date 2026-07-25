#!/usr/bin/env python3
"""Two-model agent loop: one model orchestrates, another writes the code.

The premise being tested is that these are different jobs. An orchestrator needs
reliable tool calls and cheap turns; a coder needs to get a whole file right.
A model can be good at one and mediocre at the other, so this runs the loop on
the orchestrator endpoint and routes `write_code` to a separate coder endpoint.

Set --coder-url equal to --base-url to get a single-model control run.

The task's test file is fixed and supplied by the fixture — the agent implements
against it and may not edit it. That removes the "model writes its own broken
tests" failure mode and isolates implementation plus iteration.

Stdlib only.
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import signal
import subprocess
import time
import urllib.request

FENCE_RE = re.compile(r"```[a-zA-Z0-9_+-]*\n(.*?)```", re.DOTALL)

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
        "name": "write_code",
        "description": ("Ask the coder model to write one source file, then save it. "
                        "Give a complete, self-contained specification: this call cannot "
                        "see the project, only what you put in `spec`."),
        "parameters": {"type": "object",
                       "properties": {
                           "path": {"type": "string",
                                    "description": "Destination path, relative to the project root."},
                           "language": {"type": "string",
                                        "description": "Language for the fenced block, e.g. go or rust."},
                           "spec": {"type": "string",
                                    "description": ("What the file must contain: required API, behaviour, "
                                                    "dependencies, and any compiler errors to fix.")}},
                       "required": ["path", "language", "spec"]}}},
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

SYSTEM = """You are a coding agent. A project has a fixed test suite and a missing
implementation. Make the tests pass.

You do not write code yourself — `write_code` delegates that to a coder model.
Your job is to read the project, decide what each file must contain, describe it
precisely, run the tests, and feed failures back into another `write_code` call.

Rules:
- One tool per turn.
- Read the test file before specifying anything: it is the specification.
- `write_code` cannot see the project. Put everything the coder needs in `spec`,
  including the exact required function signatures and, on a retry, the verbatim
  compiler or test output.
- Never modify the test file.
- Do not call finish until run_tests reports success."""

CODER_SYSTEM = """You write a single source file. Output only the file contents in
one fenced code block. No commentary, no explanation, no extra files."""


class Project:
    def __init__(self, root, test_cmd, test_file):
        self.root = os.path.abspath(root)
        self.test_cmd = test_cmd
        self.test_file = test_file
        self.test_hash = self._hash_test()

    def _hash_test(self):
        p = os.path.join(self.root, self.test_file)
        if not os.path.isfile(p):
            return None
        return hashlib.sha256(open(p, "rb").read()).hexdigest()

    def test_file_untouched(self):
        return self._hash_test() == self.test_hash

    def _resolve(self, path):
        p = os.path.normpath(os.path.join(self.root, path.lstrip("/")))
        if not p.startswith(self.root):
            raise ValueError(f"path escapes project: {path}")
        return p

    def list_dir(self, path="."):
        p = self._resolve(path)
        if not os.path.isdir(p):
            return f"error: not a directory: {path}"
        out = []
        for name in sorted(os.listdir(p)):
            if name.startswith(".") or name in ("target", "__pycache__"):
                continue
            out.append(name + "/" if os.path.isdir(os.path.join(p, name)) else name)
        return "\n".join(out) or "(empty)"

    def read_file(self, path):
        p = self._resolve(path)
        if not os.path.isfile(p):
            return f"error: no such file: {path}"
        with open(p, encoding="utf-8") as f:
            return "\n".join(f"{i:4d}| {ln.rstrip()}" for i, ln in enumerate(f, 1))

    def save(self, path, content):
        p = self._resolve(path)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w", encoding="utf-8") as f:
            f.write(content)
        return p

    def run_tests(self, timeout=600):
        # Run in its own process group and kill the whole group on timeout.
        # subprocess.run(shell=True, timeout=...) only kills the shell; the real
        # build tool survives as an orphan. A stranded `cargo` keeps holding
        # ~/.cargo/.package-cache, which then blocks every later episode.
        p = subprocess.Popen(self.test_cmd, cwd=self.root, shell=True,
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                             text=True, start_new_session=True)
        try:
            out, _ = p.communicate(timeout=timeout)
            return p.returncode, out
        except subprocess.TimeoutExpired:
            try:
                os.killpg(os.getpgid(p.pid), signal.SIGKILL)
            except (ProcessLookupError, PermissionError):
                p.kill()
            out, _ = p.communicate()
            return 124, (out or "") + f"\n[timed out after {timeout}s]"

    def tests_pass(self):
        try:
            rc, _ = self.run_tests()
            return rc == 0
        except subprocess.SubprocessError:
            return False


def post(url, body, timeout=1800):
    req = urllib.request.Request(url.rstrip("/") + "/v1/chat/completions",
                                 data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    t0 = time.perf_counter()
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r), round(time.perf_counter() - t0, 2)


def call_coder(url, model_args, path, language, spec):
    """Ask the coder model for one file. Returns (content, secs, tokens)."""
    prompt = (f"Write the complete contents of `{path}`.\n\n{spec}\n\n"
              f"Output exactly one ```{language} fenced block containing the whole file.")
    body = {"model": "coder",
            "messages": [{"role": "system", "content": CODER_SYSTEM},
                         {"role": "user", "content": prompt}],
            **model_args}
    d, secs = post(url, body)
    text = d["choices"][0]["message"].get("content") or ""
    text = re.sub(r"<think>.*?</think>", "", text, flags=re.DOTALL)
    m = FENCE_RE.search(text)
    content = m.group(1) if m else text.strip()
    return content, secs, (d.get("usage") or {}).get("completion_tokens")


def run_episode(args, project, sampling, coder_sampling):
    messages = [{"role": "system", "content": SYSTEM},
                {"role": "user", "content":
                 "The test suite is failing because the implementation is missing. "
                 "Read the tests, implement what they require, and make them pass."}]
    turns, errors, coder_calls, coder_tokens = [], 0, 0, 0

    for turn in range(1, args.max_turns + 1):
        body = {"model": "orchestrator", "messages": messages,
                "tools": TOOLS, "tool_choice": "auto", **sampling}
        try:
            d, secs = post(args.base_url, body)
        except Exception as e:                                    # noqa: BLE001
            turns.append({"turn": turn, "status": "http_error", "detail": str(e)[:200]})
            break

        msg = d["choices"][0]["message"]
        calls = msg.get("tool_calls") or []
        usage = d.get("usage") or {}
        rec = {"turn": turn, "secs": secs, "n_calls": len(calls),
               "completion_tokens": usage.get("completion_tokens")}

        if not calls:
            rec.update(status="no_tool_call", text=(msg.get("content") or "")[:200])
            turns.append(rec)
            errors += 1
            messages.append({"role": "assistant", "content": msg.get("content") or ""})
            messages.append({"role": "user", "content": "Continue by calling a tool."})
            if errors >= 3:
                rec["status"] = "stalled"
                break
            continue

        fn = calls[0]["function"]
        name, raw = fn["name"], fn.get("arguments") or "{}"
        rec["tool"] = name
        messages.append({"role": "assistant", "content": msg.get("content") or "",
                         "tool_calls": [calls[0]]})

        try:
            fargs = json.loads(raw) if isinstance(raw, str) else raw
            rec["status"] = "ok"
        except json.JSONDecodeError as e:
            rec["status"] = "bad_json"
            errors += 1
            result = f"error: arguments were not valid JSON: {e}"
            fargs = None

        if fargs is not None:
            try:
                if name == "write_code":
                    if os.path.normpath(fargs["path"]).endswith(os.path.normpath(project.test_file)):
                        result = "error: the test file is fixed and must not be rewritten"
                    else:
                        content, csecs, ctok = call_coder(
                            args.coder_url, coder_sampling,
                            fargs["path"], fargs.get("language", ""), fargs["spec"])
                        project.save(fargs["path"], content)
                        coder_calls += 1
                        coder_tokens += ctok or 0
                        rec["coder_secs"] = csecs
                        rec["coder_tokens"] = ctok
                        result = f"wrote {fargs['path']} ({len(content)} bytes) via the coder model"
                elif name == "run_tests":
                    rc, out = project.run_tests()
                    result = f"exit={rc}\n{out[-3000:]}"
                elif name == "finish":
                    result = f"finished: {fargs.get('summary', '')}"
                elif name in NAMES:
                    result = str(getattr(project, name)(**fargs))
                else:
                    rec["status"] = "hallucinated_tool"
                    errors += 1
                    result = f"error: no such tool '{name}'"
            except TypeError as e:
                rec["status"] = "bad_args"
                errors += 1
                result = f"error: bad arguments: {e}"
            except Exception as e:                                # noqa: BLE001
                rec["status"] = "tool_error"
                result = f"error: {e}"

        rec["result_head"] = result[:110]
        messages.append({"role": "tool", "tool_call_id": calls[0]["id"],
                         "name": name, "content": result[:6000]})
        turns.append(rec)

        if name == "finish" and rec["status"] == "ok":
            break

    return {"turns": turns, "n_turns": len(turns),
            "called_finish": any(t.get("tool") == "finish" for t in turns),
            "tests_pass": project.tests_pass(),
            "test_file_untouched": project.test_file_untouched(),
            "tool_errors": errors,
            "coder_calls": coder_calls, "coder_tokens": coder_tokens}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://127.0.0.1:8080", help="orchestrator endpoint")
    ap.add_argument("--coder-url", default=None, help="coder endpoint (defaults to --base-url)")
    ap.add_argument("--task-dir", required=True)
    ap.add_argument("--work-root", required=True)
    ap.add_argument("--test-cmd", required=True)
    ap.add_argument("--test-file", required=True)
    ap.add_argument("--episodes", type=int, default=5)
    ap.add_argument("--max-turns", type=int, default=30)
    ap.add_argument("--out", required=True)
    ap.add_argument("--temperature", type=float, default=1.0)
    ap.add_argument("--top-p", type=float, default=0.95)
    ap.add_argument("--top-k", type=int, default=40)
    ap.add_argument("--max-tokens", type=int, default=8192)
    ap.add_argument("--coder-temperature", type=float, default=0.6)
    ap.add_argument("--coder-top-k", type=int, default=20)
    ap.add_argument("--coder-max-tokens", type=int, default=16384)
    a = ap.parse_args()
    a.coder_url = a.coder_url or a.base_url

    sampling = {"temperature": a.temperature, "top_p": a.top_p,
                "top_k": a.top_k, "max_tokens": a.max_tokens}
    coder_sampling = {"temperature": a.coder_temperature, "top_p": a.top_p,
                      "top_k": a.coder_top_k, "max_tokens": a.coder_max_tokens}

    results = []
    for ep in range(1, a.episodes + 1):
        work = os.path.join(a.work_root, f"ep{ep}")
        shutil.rmtree(work, ignore_errors=True)
        shutil.copytree(a.task_dir, work)
        print(f"\n===== episode {ep} =====", flush=True)
        r = run_episode(a, Project(work, a.test_cmd, a.test_file), sampling, coder_sampling)
        r["episode"] = ep
        for t in r["turns"]:
            print(f"  t{t['turn']:>2} {t.get('status','?'):<16} {t.get('tool','-'):<11} "
                  f"{t.get('secs',0):>6.2f}s  {str(t.get('result_head',''))[:56]!r}", flush=True)
        print(f"  -> turns={r['n_turns']} finish={r['called_finish']} "
              f"tests_pass={r['tests_pass']} untouched={r['test_file_untouched']} "
              f"coder_calls={r['coder_calls']} coder_tokens={r['coder_tokens']}", flush=True)
        results.append(r)

    solved = sum(1 for r in results if r["tests_pass"] and r["test_file_untouched"])
    all_turns = [t for r in results for t in r["turns"]]
    bad = [t for t in all_turns if t.get("status") not in ("ok", None)]
    secs = [t["secs"] for t in all_turns if "secs" in t]
    summary = {
        "episodes": len(results), "solved": solved,
        "turns_per_episode": [r["n_turns"] for r in results],
        "total_turns": len(all_turns), "malformed_turns": len(bad),
        "malformed_breakdown": {s: sum(1 for t in bad if t.get("status") == s)
                                for s in {t.get("status") for t in bad}},
        "coder_calls_per_episode": [r["coder_calls"] for r in results],
        "coder_tokens_total": sum(r["coder_tokens"] for r in results),
        "orchestrator_turn_latency_s": {
            "min": min(secs), "median": sorted(secs)[len(secs) // 2], "max": max(secs)} if secs else None,
    }
    with open(a.out, "w") as f:
        json.dump({"summary": summary, "episodes": results}, f, indent=2, ensure_ascii=False)
    print("\n=== SUMMARY ===")
    print(json.dumps(summary, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
