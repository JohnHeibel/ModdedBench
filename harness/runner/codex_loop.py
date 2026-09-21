# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Outer loop that keeps Codex CLI on the mission: one ``codex exec``, then ``codex exec resume <id>`` per turn.

MCP cannot push, so inside a turn the agent blocks in ``mb_wait``; this loop only restarts a turn that
ended. It stops on a ``MISSION COMPLETE`` line in a turn's last message, on ``<repo>/.state/STOP``, after
``--max-turns``, or after 12 failed turns in a row: the wait after a failure grows from 30 s to an hour, so a
usage limit is slept through (about 9 hours) rather than ending the run. No turn starts while the game is
down or the player is out of the world, because such a turn only burns tokens. The thread id is kept in ``--state``, so a restarted
loop resumes the same conversation; delete that file to start a new one. Arguments after ``--`` go to
``codex exec`` (for example ``-- -m <model> -s workspace-write``).
"""
from __future__ import annotations
import argparse, json, shutil, subprocess, sys, time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
BACKOFF = (1, 10, 60, 120)  # times backoff_s: 30 s, 5 min, 30 min, then hourly
CONTINUE = ("Continue the mission in your standing brief (mb_status says where it is). Rebuild your picture from mb_status, mb_quest_status and notes. "
            "If you are only waiting, call mb_wait instead of ending the turn.")

def _find_id(value):
    """``thread.started`` carries ``thread_id``; ``session_id`` and nesting are accepted defensively."""
    if isinstance(value, dict):
        for key in ("thread_id", "session_id"):
            if isinstance(value.get(key), str) and value[key]: return value[key]
        value = list(value.values())
    if isinstance(value, list):
        for child in value:
            found = _find_id(child)
            if found: return found
    return None

def _in_world():
    """The client is up and the player is in the world."""
    sys.path.insert(0, str(REPO / "harness" / "mcp"))
    try:
        from kernel import Kernel
        with Kernel(timeout=10) as k: return k.call("obs.world").get("inWorld") is True
    except Exception: return False

def turn(cmd, prompt, cwd, log):
    """One Codex turn, streamed to the log and stdout: (exit code, thread id or None, last agent message)."""
    thread, last = None, ""
    with subprocess.Popen(cmd, cwd=cwd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace") as p:
        p.stdin.write(prompt); p.stdin.close()
        for line in p.stdout:
            log.write(line); log.flush(); sys.stdout.write(line); sys.stdout.flush()
            try: event = json.loads(line)
            except ValueError: continue
            if not isinstance(event, dict): continue
            thread = thread or _find_id(event); item = event.get("item")
            if event.get("type") == "item.completed" and isinstance(item, dict) and item.get("type") == "agent_message" and isinstance(item.get("text"), str): last = item["text"]
    return p.returncode, thread, last

def run(repo=REPO, prompt=None, max_turns=50, state=None, codex=None, extra=(), backoff_s=30, ready=_in_world, max_failures=12):
    """Returns why the loop ended: complete, stop_file, failed or max_turns."""
    repo = Path(repo); prompt = Path(prompt or repo / "PROMPT.md"); state = Path(state or repo / ".state" / "codex-loop.json")
    codex = list(codex or [shutil.which("codex") or "codex"])  # the npm shim is codex.cmd on Windows
    for d in (repo / ".state", state.parent): d.mkdir(parents=True, exist_ok=True)
    thread = json.loads(state.read_text(encoding="utf-8")).get("thread") if state.exists() else None
    failures = 0
    def idle(seconds):  # sleep that the stop file cuts short
        end = time.monotonic() + seconds
        while time.monotonic() < end and not (repo / ".state" / "STOP").exists(): time.sleep(min(5, max(0, end - time.monotonic())))
    with open(repo / ".state" / "codex-loop.log", "a", encoding="utf-8") as log:
        for _ in range(max_turns):
            waited = False
            while not (repo / ".state" / "STOP").exists() and not ready():
                if not waited: log.write("# %s waiting for the game: no bridge, or the player is not in the world\n" % time.strftime("%Y-%m-%dT%H:%M:%S")); log.flush()
                waited = True; idle(backoff_s)
            if (repo / ".state" / "STOP").exists(): return "stop_file"
            # Options go before ``resume``: that subcommand does not accept all of them (-C, -s) after it.
            cmd = [*codex, "exec", "--json", "-C", str(repo), *extra, *(["resume", thread] if thread else []), "-"]
            log.write("# %s %s\n" % (time.strftime("%Y-%m-%dT%H:%M:%S"), " ".join(cmd)))
            code, found, last = turn(cmd, CONTINUE if thread else prompt.read_text(encoding="utf-8"), repo, log)
            if found and not thread:
                thread = found; state.write_text(json.dumps({"thread": thread}), encoding="utf-8")
            if "MISSION COMPLETE" in [x.strip() for x in last.splitlines()]: return "complete"
            failures = failures + 1 if code else 0
            if failures == max_failures: return "failed"
            if code: idle(backoff_s * BACKOFF[min(failures, len(BACKOFF)) - 1])
    return "max_turns"

def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    extra = argv[argv.index("--") + 1:] if "--" in argv else []
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--repo", default=str(REPO)); ap.add_argument("--prompt"); ap.add_argument("--state")
    ap.add_argument("--max-turns", type=int, default=50)
    a = ap.parse_args(argv[:argv.index("--")] if "--" in argv else argv)
    reason = run(a.repo, a.prompt, a.max_turns, a.state, extra=extra)
    print("codex_loop: " + reason); return 0 if reason in ("complete", "stop_file") else 1

if __name__ == "__main__": sys.exit(main())
