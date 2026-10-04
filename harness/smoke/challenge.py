# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Model challenges: one fresh model on one prepared task, graded from the server, one row a trial.

    python harness/smoke/challenge.py TASK [TASK ...] [--trials 3] [--minutes 12] [--model M] [--effort E] [--checkout DIR]

Runs on the host against the throwaway `mbtest` stack (server up with dev fixtures, client in its world). A trial:
  1. the scene, kit and start position (challenge_tasks.py setup, inside the server's network);
  2. a fresh clone of the commit under test (its tools, an empty .state: no notes, no thread), and the real brief with
     the task put at its top;
  3. the agent container on that clone: one Codex turn, ended by its own MISSION COMPLETE line or the minute cap;
  4. the grade (challenge_tasks.py grade): blocks on the server, never a receipt.
Rows go to .runtime/evidence/challenge.json; everything a trial left (calls, loop log, brief, snapshots) stays in
.runtime/trials/<stamp>-<task>-<n>/. `--minutes-left` in the ledger there keeps a total budget across invocations.
"""
from __future__ import annotations

import argparse
import collections
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RUNTIME = ROOT / ".runtime"
OUT = RUNTIME / "evidence" / "challenge.json"
BASH = os.environ.get("MB_BASH", r"C:\Program Files\Git\bin\bash.exe" if os.name == "nt" else "bash")
DOCKER = os.environ.get("MB_DOCKER", r"C:\Program Files\Docker\Docker\resources\bin\docker.exe" if os.name == "nt" else "docker")
TASK = """# Operator task for this session (read this first)

This session is a short operator test in a prepared test world, not the long mission. The mission, the quest target and the
long-game advice in the brief below do not apply to it; the rules, the facts about the world and the tool reference do. There
is no quest to reach and nothing to keep for later sessions.

Task: {task}

You stand where the task starts, carrying what it needs. When it is done, end your turn with one or two sentences on what you
did and what, if anything, is not as asked, and then a line containing only `MISSION COMPLETE`. If you conclude it cannot be
done, say why and end the same way.

---

"""


def sh(cmd, **kw): return subprocess.run(cmd, text=True, capture_output=True, **kw)


def world(op, task, folder):
    """challenge_tasks.py inside the server's network; its last line is the JSON it reports."""
    rel = folder.relative_to(ROOT).as_posix()
    r = sh([BASH, str(ROOT / "harness" / "smoke" / "mbtest.sh"), "harness/smoke/challenge_tasks.py", op, task, "/repo/" + rel], cwd=ROOT)
    lines = [l for l in r.stdout.splitlines() if l.startswith("{")]
    if r.returncode or not lines: raise RuntimeError(f"{op} {task}: {r.stdout[-1500:]}\n{r.stderr[-1500:]}")
    return json.loads(lines[-1])


def brief(checkout: Path, task: str) -> str:
    text = (checkout / "PROMPT.md").read_text(encoding="utf-8")
    for key, value in (("TARGET_QUEST", "none: an operator test session, see the top of this brief"), ("TARGET_CHAPTER", "none"),
                       ("WORLD", "the contained GTNH test server (one world)"), ("REPO", "/work/modbench")):
        text = re.sub(rf'^({key}\s*=\s*)"<[^\n]*>"', lambda m: f'{m.group(1)}"{value}"', text, count=1, flags=re.M)
    return TASK.format(task=task) + text


def agent(folder: Path, a) -> dict:
    """One Codex turn in the agent image, on the trial's own clone; the container is gone when it returns."""
    codex = f'-m {a.model} -c model_reasoning_effort=\\"{a.effort}\\" -c model_reasoning_summary=\\"detailed\\"'
    inner = ("cp /work/modbench/docker/codex-config.toml ~/.codex/config.toml && cp /work/modbench/AGENTS.md ~/.codex/AGENTS.md && "
             "git config --global --add safe.directory /work/modbench && cd /work/modbench && "
             f"exec python3 harness/runner/codex_loop.py --prompt /brief/PROMPT.md --max-turns 1 --max-minutes {a.minutes} -- {codex}")
    tokens = Path.home() / ".moddedbench"
    cmd = [DOCKER, "run", "--rm", "--name", "mbtrial", "--network", "mbtest_agent", "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true",
           "--memory", "8g", "--pids-limit", "2048",
           "-v", f"{folder / 'checkout'}:/work/modbench", "-v", "moddedbench_agent-home:/home/agent/.codex", "-v", f"{folder / 'outbox'}:/outbox",
           "-v", f"{folder / 'brief'}:/brief:ro", "-v", f"{a.wiki}:/wiki:ro", "-v", f"{tokens}:/run/bridge:ro",
           a.image, "sh", "-c", inner]
    t = time.time()
    with open(folder / "agent.jsonl", "w", encoding="utf-8") as out, open(folder / "agent.err", "w", encoding="utf-8") as err:
        r = subprocess.run(cmd, stdout=out, stderr=err, timeout=a.minutes * 60 + 300)   # the whole event stream: the trial's transcript
    return {"exit": r.returncode, "wallS": round(time.time() - t, 1)}


def calls(folder: Path) -> dict:
    path = folder / "checkout" / ".state" / "calls.jsonl"; by, failed, busy = collections.Counter(), collections.Counter(), 0.0
    for line in path.read_text(encoding="utf-8").splitlines() if path.is_file() else []:
        row = json.loads(line); by[row["tool"]] += 1; busy += row.get("s") or 0
        if row.get("error"): failed[row["tool"]] += 1
    return {"calls": sum(by.values()), "failed": sum(failed.values()), "byTool": dict(by.most_common()), "failedByTool": dict(failed), "toolS": round(busy, 1)}


def record(folder: Path) -> dict:
    runs = sorted((folder / "outbox" / "runs").glob("*.json")) if (folder / "outbox" / "runs").is_dir() else []
    row = json.loads(runs[-1].read_text(encoding="utf-8")) if runs else {}
    return {k: row.get(k) for k in ("endReason", "minutes", "tokens", "tokensSpent", "thread", "harnessCommitsDuring")}


def trial(task: str, n: int, a) -> dict:
    folder = RUNTIME / "trials" / f"{time.strftime('%Y%m%dT%H%M%S')}-{task}-{n}"; folder.mkdir(parents=True)
    for d in ("outbox", "brief"): (folder / d).mkdir()
    state = world("setup", task, folder); prompt = json.loads((folder / "state.json").read_text())["prompt"]
    src = Path(a.checkout).resolve()
    c = sh(["git", "clone", "-q", "--no-hardlinks", str(src), str(folder / "checkout")])
    if c.returncode: raise RuntimeError("clone: " + c.stderr)
    commit = sh(["git", "-C", str(folder / "checkout"), "rev-parse", "--short", "HEAD"]).stdout.strip()
    (folder / "brief" / "PROMPT.md").write_text(brief(folder / "checkout", prompt), encoding="utf-8", newline="\n")
    row = {"task": task, "trial": n, "model": a.model, "effort": a.effort, "commit": commit, "folder": folder.name, "started": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
    try: row.update(agent(folder, a))
    except subprocess.TimeoutExpired:
        sh([DOCKER, "rm", "-f", "mbtrial"]); row.update(exit="timeout", wallS=a.minutes * 60 + 300)
    row.update(calls(folder)); row.update(record(folder))
    row["selfEdits"] = bool(sh(["git", "-C", str(folder / "checkout"), "status", "--porcelain", "--", "harness", "mods"]).stdout.strip())
    grade = world("grade", task, folder); row["grade"] = grade; row["passed"] = bool(grade.get("passed"))
    return row


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("tasks", nargs="+"); ap.add_argument("--trials", type=int, default=1); ap.add_argument("--minutes", type=float, default=12)
    ap.add_argument("--model", default="gpt-6.1-sol"); ap.add_argument("--effort", default="medium")
    ap.add_argument("--checkout", default=str(ROOT), help="the repository whose HEAD the agent runs (cloned fresh for every trial)")
    ap.add_argument("--image", default="mbtest-agent:latest"); ap.add_argument("--wiki", default=str(ROOT.parent.parent / "modbench" / ".runtime" / "wiki"))
    ap.add_argument("--budget-minutes", type=float, help="stop before a trial that could take the ledger's total past this")
    a = ap.parse_args()
    try: history = json.loads(OUT.read_text())
    except (OSError, ValueError): history = {"rows": []}
    for task in a.tasks:
        for n in range(1, a.trials + 1):
            spent = sum((r.get("wallS") or 0) for r in history["rows"] if isinstance(r.get("wallS"), (int, float))) / 60
            if a.budget_minutes and spent + a.minutes > a.budget_minutes: print(f"budget: {spent:.1f} of {a.budget_minutes} model minutes used; stopping"); return 0
            print(f"== {task} trial {n}", flush=True)
            try: row = trial(task, n, a)
            except Exception as e: row = {"task": task, "trial": n, "passed": False, "error": f"{type(e).__name__}: {e}"[:2000]}
            history["rows"].append(row); OUT.parent.mkdir(parents=True, exist_ok=True); OUT.write_text(json.dumps(history, indent=1))
            print(f"{task:10} {n} {'PASS' if row.get('passed') else 'FAIL'} wall={row.get('wallS')}s end={row.get('endReason')} calls={row.get('calls')} failed={row.get('failed')} "
                  f"tokens={row.get('tokensSpent')} tools={row.get('byTool')} grade={ {k: v for k, v in (row.get('grade') or {}).items() if k not in ('stray', 'doorCells', 'leaksTo')} } {row.get('error') or ''}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
