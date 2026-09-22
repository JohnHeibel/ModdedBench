# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Outer loop that keeps Codex CLI on the mission: one ``codex exec``, then ``codex exec resume <id>`` per turn.

MCP cannot push, so inside a turn the agent blocks in ``mb_wait``; this loop only restarts a turn that
ended. A turn is not a unit of the run: Codex is told not to stop, one turn can last hours, and a new turn is
recovery after it did. The run's size is ``--max-minutes`` and ``--max-tokens`` (billed input tokens, estimated
while a turn runs from what has streamed, exact at its end), enforced mid-turn by ending the Codex process; the
thread stays resumable. It also stops on a ``MISSION COMPLETE`` line in a turn's last message, on
``<repo>/.state/STOP``, after ``--max-turns``, or after 12 failed turns in a row: the wait after a failure grows from 30 s to an hour, so a
usage limit is slept through (about 9 hours) rather than ending the run. No turn starts while the game is
down or the player is out of the world, because such a turn only burns tokens. The thread id is kept in ``--state``, so a restarted
loop resumes the same conversation; delete that file to start a new one. Arguments after ``--`` go to
``codex exec`` (for example ``-- -m <model> -s workspace-write``).
"""
from __future__ import annotations
import argparse, hashlib, json, os, shutil, subprocess, sys, threading, time
from pathlib import Path
from feed import Feed

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

def _quests():
    """Quests completed in the book, from the server's own count; None when it cannot be read."""
    sys.path.insert(0, str(REPO / "harness" / "mcp"))
    try:
        from kernel import Kernel
        with Kernel(timeout=10) as k: return sum(l["completed"] for l in k.call("quest.lines", query="", offset=0, limit=100)["lines"])
    except Exception: return None

def _git(repo, *args):
    try: return subprocess.run(["git", *args], cwd=repo, capture_output=True, text=True, timeout=20).stdout.strip() or None
    except Exception: return None

def turn(cmd, prompt, cwd, log, feed=None, over=lambda: None):
    """One Codex turn, streamed to the log and stdout: (exit code, thread id or None, last agent message, budget reason or None)."""
    thread, last, spent, cut = None, "", None, []
    with subprocess.Popen(cmd, cwd=cwd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace") as p:
        p.stdin.write(prompt); p.stdin.close()
        done = threading.Event()
        def watch():  # Codex prints nothing while a tool runs (a 15-minute mb_wait, a script), and the budget still holds then
            while not done.wait(5):
                why = over()
                if why: cut.append(why); log.write("# %s budget: %s\n" % (time.strftime("%Y-%m-%dT%H:%M:%S"), why)); p.terminate(); return
        threading.Thread(target=watch, daemon=True).start()
        for line in p.stdout:
            log.write(line); log.flush(); sys.stdout.write(line); sys.stdout.flush()
            try: event = json.loads(line)
            except ValueError: continue
            if not isinstance(event, dict): continue
            thread = thread or _find_id(event); item = event.get("item")
            try: feed and feed.event(event)
            except Exception as e: log.write("# feed: %r\n" % e)  # the overlay never costs a turn
            if event.get("type") == "item.completed" and isinstance(item, dict) and item.get("type") == "agent_message" and isinstance(item.get("text"), str): last = item["text"]
            spent = over()
            if spent:  # the budget ends the turn where it stands; Codex has written the thread so far, which resumes later
                log.write("# %s budget: %s\n" % (time.strftime("%Y-%m-%dT%H:%M:%S"), spent)); p.terminate()
                try: p.wait(15)
                except subprocess.TimeoutExpired: p.kill()
                break
        done.set()
    return p.returncode, thread, last, spent or (cut[0] if cut else None)

def run(repo=REPO, prompt=None, max_turns=50, state=None, codex=None, extra=(), backoff_s=30, ready=_in_world, max_failures=12,
        max_minutes=None, max_tokens=None, quests=_quests):
    """Returns why the loop ended: complete, stop_file, failed, max_turns, time_budget or token_budget."""
    repo = Path(repo); prompt = Path(prompt or repo / "PROMPT.md"); state = Path(state or repo / ".state" / "codex-loop.json")
    codex = list(codex or [shutil.which("codex") or "codex"])  # the npm shim is codex.cmd on Windows
    for d in (repo / ".state", state.parent): d.mkdir(parents=True, exist_ok=True)
    thread = json.loads(state.read_text(encoding="utf-8")).get("thread") if state.exists() else None
    failures = 0; feed = Feed(Path(os.environ.get("MODBENCH_OUTBOX", repo / ".state")) / "overlay")
    effort = [x.split("=", 1)[1].strip('"') for x in extra if x.startswith("model_reasoning_effort=")]  # what the viewer is watching, from the arguments for codex
    feed.live["run"] = {"model": extra[extra.index("-m") + 1] if "-m" in extra[:-1] else "", "effort": effort[-1] if effort else ""}
    started, tokens0 = time.time(), feed.billed()  # budgets count from this start, not from the run's first
    # One record per start, beside the overlay feed (outside the agent's reach in a contained run): what ran, on what, and what it did.
    record_path = feed.folder.parent / "runs" / (time.strftime("%Y%m%dT%H%M%S", time.localtime(started)) + ".json")
    record = {"model": feed.live["run"]["model"], "effort": feed.live["run"]["effort"], "codexArgs": list(extra), "thread": thread,
              "promptSha256": hashlib.sha256(prompt.read_bytes()).hexdigest(), "harnessCommit": _git(repo, "rev-parse", "HEAD"),
              "startedAt": started, "budget": {"minutes": max_minutes, "tokens": max_tokens}, "tokensBefore": tokens0, "questsBefore": quests()}
    def save_record(**more):
        record.update(more)
        try: record_path.parent.mkdir(parents=True, exist_ok=True); record_path.write_text(json.dumps(record, indent=1), encoding="utf-8")
        except OSError as e: print("codex_loop: run record: %r" % e, file=sys.stderr)
    save_record()
    def end(reason):
        feed.status("ended", reason)
        tokens = dict(feed.live.get("stats", {}).get("tokens") or {})
        harness = [c for c in (_git(repo, "rev-list", record["harnessCommit"] + "..HEAD") or "").split() if c] if record["harnessCommit"] else []
        save_record(endedAt=time.time(), minutes=round((time.time() - started) / 60, 1), endReason=reason, thread=thread,
                    tokens=tokens, tokensSpent=feed.billed() - tokens0, questsAfter=quests(), harnessCommitsDuring=len(harness),
                    harnessCommitAtEnd=_git(repo, "rev-parse", "HEAD"))
        return reason
    feed.live["budget"] = {"minutes": max_minutes, "tokens": max_tokens, "startedAt": started, "tokensBefore": tokens0}
    def over():
        if (repo / ".state" / "STOP").exists(): return "stop_file"  # a run is usually one long turn: stop means now, and the thread resumes
        if max_minutes and time.time() - started >= max_minutes * 60: return "time_budget"
        if max_tokens and feed.billed() - tokens0 >= max_tokens: return "token_budget"
        return None
    def idle(seconds):  # sleep that the stop file cuts short
        until = time.monotonic() + seconds
        while time.monotonic() < until and not (repo / ".state" / "STOP").exists(): time.sleep(min(5, max(0, until - time.monotonic())))
    with open(repo / ".state" / "codex-loop.log", "a", encoding="utf-8") as log:
        for _ in range(max_turns):
            waited = False
            while not (repo / ".state" / "STOP").exists() and not ready():
                if not waited: log.write("# %s waiting for the game: no bridge, or the player is not in the world\n" % time.strftime("%Y-%m-%dT%H:%M:%S")); log.flush()
                waited = True; feed.status("game_down"); idle(backoff_s)
            if (repo / ".state" / "STOP").exists(): return end("stop_file")
            if over(): return end(over())
            # Options go before ``resume``: that subcommand does not accept all of them (-C, -s) after it.
            cmd = [*codex, "exec", "--json", "-C", str(repo), *extra, *(["resume", thread] if thread else []), "-"]
            log.write("# %s %s\n" % (time.strftime("%Y-%m-%dT%H:%M:%S"), " ".join(cmd)))
            code, found, last, spent = turn(cmd, CONTINUE if thread else prompt.read_text(encoding="utf-8"), repo, log, feed, over)
            if found and not thread:
                thread = found; state.write_text(json.dumps({"thread": thread}), encoding="utf-8")
            if spent: return end(spent)
            if "MISSION COMPLETE" in [x.strip() for x in last.splitlines()]: feed.add("mark", "MISSION COMPLETE"); return end("complete")
            failures = failures + 1 if code else 0
            if failures == max_failures: return end("failed")
            if code: feed.status("backing_off", "turn failed %d in a row" % failures); idle(backoff_s * BACKOFF[min(failures, len(BACKOFF)) - 1])
        return end("max_turns")

def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    extra = argv[argv.index("--") + 1:] if "--" in argv else []
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--repo", default=str(REPO)); ap.add_argument("--prompt"); ap.add_argument("--state")
    ap.add_argument("--max-turns", type=int, default=50)
    ap.add_argument("--max-minutes", type=float, help="wall-clock budget for this start; the turn is ended where it stands")
    ap.add_argument("--max-tokens", type=int, help="billed input tokens for this start, estimated mid-turn")
    a = ap.parse_args(argv[:argv.index("--")] if "--" in argv else argv)
    reason = run(a.repo, a.prompt, a.max_turns, a.state, extra=extra, max_minutes=a.max_minutes, max_tokens=a.max_tokens)
    print("codex_loop: " + reason); return 0 if reason in ("complete", "stop_file") else 1

if __name__ == "__main__": sys.exit(main())
