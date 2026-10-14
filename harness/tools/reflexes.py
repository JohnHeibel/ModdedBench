# SPDX-License-Identifier: MIT
# Copyright (c) 2026 ModdedBench contributors
"""Reflexes: a script the model arms ahead of time, run the moment a guard pauses the world, and the mb_reflex tool.

A guard's pause hands the model a decision, and the model takes about a minute to give one. A reflex is that decision
written beforehand: mb_reflex(name, when=<the pause's reason>, do=<a script>). Nothing is armed until the model arms it,
a reflex calls no model and has only the tools the model has, and every firing is handed to the model on its next tool
result (``reflexes``, through tasks.fields) and written to the call log.

``around`` is Kernel.around_action: it goes round every acting game call of the model's own process. The call that meets
a pause (its job held, its action refused or cut short) answers the pause there, so the tool that made the call, and any
script above it, sees the work finish as if nothing had paused. With no call in flight the watcher thread answers it, so
a reflex also fires while the model thinks. A pause is answered once (the clock numbers its pauses), and other threads'
acting calls wait for a reflex that is running.

The script runs in a stepped world: its first action resumes time for at most max_ticks. After it, then="resume" carries
on with the work in flight: the held job is waited on again; if the script's own actions ended it, a checkpointed job
(its receipt has a jobId: mine, build) goes on from its journal and any other call is sent again. Whatever cannot be
carried on leaves the world paused, as with no reflex: the script failed or ran out of ticks, another pause began inside
it, its limits are spent, the call was cut short instead of held, or the pause ended a background task.

Armed reflexes, firings not yet delivered and the last pause answered are kept in MODBENCH_REFLEXES_FILE (default
``.state/reflexes.json``); the live copy, its locks and the watcher live in ``mbtool.state["reflexes"]`` and so survive
a reload of this module.
"""
from __future__ import annotations

import ast
import contextvars
import json
import os
import re
import threading
import time
from pathlib import Path
from typing import Any

import mbtool
from kernel import BridgeError, Kernel, refused, resume_once
from mbtool import kernel, state, tool
from mbtools_gtnh import scripts, tasks

def _file() -> Path:
    return Path(os.environ.get("MODBENCH_REFLEXES_FILE") or Path(__file__).resolve().parents[2] / ".state" / "reflexes.json")  # tests point it elsewhere


def _box() -> dict:
    """The live copy. ``inside`` is the firing whose script the context is running: kept here, with the locks, because a
    reload makes a new copy of this module while a script of the old one may still be running."""
    box = state.get("reflexes")
    if box is None:
        try: saved = json.loads(_file().read_text(encoding="utf-8"))
        except (OSError, ValueError): saved = {}
        box = state["reflexes"] = {"armed": saved.get("armed") or {}, "fired": saved.get("fired") or [], "answered": saved.get("answered"),
                                   "lock": threading.RLock(), "running": threading.Lock(), "flight": 0, "stop": None,
                                   "inside": contextvars.ContextVar("reflex_inside", default=None)}
    return box


def _save(box: dict) -> None:
    try:
        file = _file(); file.parent.mkdir(parents=True, exist_ok=True)
        tmp = file.with_suffix(".tmp"); tmp.write_text(json.dumps({k: box[k] for k in ("armed", "fired", "answered")}), encoding="utf-8")
        tmp.replace(file)
    except OSError:
        pass  # the live copy still holds it; a restart would lose what this write carried


def _clock(k: Kernel) -> dict:
    return k._call("time.status", timeout=5).get("state") or {}


def _pause_id(clock: dict) -> list:
    """Which pause this is: the clock numbers each one, and a restarted client's numbers start over at another time."""
    last = (clock.get("events") or [{}])[-1]
    return [last.get("sequence"), last.get("wallMs")]


def _guard(clock: dict) -> str | None:
    """The reason of the pause in force if it is one a reflex may answer: not one a job waits out, not a reflex's own."""
    reason = str(clock.get("reason"))
    return reason if clock.get("paused") and not tasks.waited_out(reason) and not reason.startswith("reflex:") else None


def _record(box: dict, entry: dict) -> None:
    with box["lock"]:
        box["fired"] = (box["fired"] + [entry])[-40:]; _save(box)


# ---- round each acting call ----

def around(k: Kernel, method: str, timeout: float | None, params: dict) -> Any:
    box = _box(); me = box["inside"].get()
    if me is not None:
        return _within(k, me, method, timeout, params)
    if not box["armed"] or state.get("task") or method.startswith("time.") or method in tasks.UNGATED or not k._acts(method):
        return k._call(method, timeout, **params)
    _watch(box)
    spent = None
    try:
        while True:
            with box["running"]: pass  # a reflex on another thread has the body: its actions come first
            _flying(box, 1)
            try:
                result = error = carry = None
                try: result = k._call(method, timeout, **params)
                except BridgeError as e: error = e
                met = _met(result, error)
                if met is not None:
                    with box["running"]: carry = _answer(k, box, met, method, params)
            finally:
                _flying(box, -1)
            if carry is None: break
            method, params = carry
            if spent is None: spent = resume_once.set(None)  # what carries on is sent as it is: the tool call's own resume belonged to its first action
    finally:
        if spent is not None: resume_once.reset(spent)
    if error is not None: raise error
    return result


Kernel.around_action = around
tasks.TaskKernel.around_action = None  # a background task's pause ends the task; the reflex runs from the model's server once the body is free


def _flying(box: dict, by: int) -> None:
    with box["lock"]: box["flight"] += by


def _met(result: Any, error: BridgeError | None) -> dict | None:
    """How a call met a guard's pause: its work held (the receipt), its action refused before it began, or cut short."""
    receipt = result if error is None else ((error.reply or {}).get("error") or {}).get("receipt")
    if isinstance(receipt, dict) and receipt.get("state") == "suspended" and receipt.get("pausedBy"):
        return {"how": "held", "receipt": receipt}
    if error is None: return None
    if refused(error, "interrupt_latched") or refused(error, "time_paused") and "by a guard" in str(error.msg): return {"how": "refused"}
    if error.code == "cancelled" and "world paused by a guard" in str(error.msg): return {"how": "cut"}
    return None


def _within(k: Kernel, me: dict, method: str, timeout: float | None, params: dict) -> Any:
    """A game call from inside a reflex's script."""
    if method in ("time.resume", "time.step") or resume_once.get() is not me["budget"]:
        raise ValueError("a reflex runs inside its own tick budget (max_ticks): resume= and time resume or step are refused in it")
    if time.monotonic() > me["deadline"]:
        raise scripts.ScriptInterrupted("the reflex ran past its time limit")
    if not method.startswith("time.") and method not in tasks.UNGATED and k._acts(method): me["acted"] = True
    return k._call(method, timeout, **params)


def _logged(name: str):
    """wrap for scripts.execute: each tool call a reflex makes goes in the call log, as a background task's do."""
    def wrap(tool_name, fn):
        def call(*args, **kwargs):
            method, started, error = args[0] if args and isinstance(args[0], str) else kwargs.get("method"), time.time(), None
            try: return fn(*args, **kwargs)
            except BaseException as e:
                error = getattr(e, "code", None) or type(e).__name__; raise
            finally:
                tasks.log_call({"t": round(started, 2), "s": round(time.time() - started, 2), "tool": tool_name,
                                "method": method if isinstance(method, str) else None, "error": error, "caller": f"reflex:{name}"})
        return call
    return wrap


def _answer(k: Kernel, box: dict, met: dict, method: str | None = None, params: dict | None = None) -> tuple | None:
    """With box["running"] held: runs the reflex armed for the pause in force, if nothing has answered that pause yet.
    Returns the call to send in place of the one that met the pause, or None where that call's own outcome stands."""
    clock = _clock(k); reason = _guard(clock)
    if reason is None: return None
    pause, tick = _pause_id(clock), int(clock.get("simulationTicks") or 0)
    with box["lock"]:
        name = next((n for n, r in box["armed"].items() if reason in r["when"]), None)
        if name is None or box["answered"] == pause: return None
        box["answered"] = pause
        r = box["armed"][name]; since = tick - r["lastTick"] if r.get("lastTick") is not None else None
        skipped = ("its times are spent" if r.get("left") == 0 else
                   f"it fired {since} ticks ago (every_ticks {r['every_ticks']})" if since is not None and 0 <= since < r["every_ticks"] else None)
        if skipped:
            _record(box, {"reflex": name, "when": reason, "tick": tick, "skipped": skipped}); return None
        r["fired"] = r.get("fired", 0) + 1; r["lastTick"] = tick
        if r.get("left"): r["left"] -= 1
        _save(box); r = dict(r)
    how, receipt = met["how"], met.get("receipt") or {}
    work = None if method is None else {"call": method, "how": how, **({"jobId": receipt.get("suspendedJobId")} if how == "held" else {})}
    entry = {"reflex": name, "when": reason, "tick": tick}
    try:
        if reason.startswith("interrupt:"):  # the watch's latch would refuse the script's first action, and this script is the answer to that watch
            for event_id in k._call("interrupt.status", timeout=5).get("latched") or []: k._call("interrupt.ack", eventId=event_id, timeout=5)
        out, me = _run(box, name, r, {"reason": reason, "tick": tick, "threats": clock.get("threats") or [], "edges": clock.get("edges") or [], "work": work})
        after = _clock(k)
        entry["ticks"] = max(0, int(after.get("simulationTicks") or tick) - tick)
        if out.get("result") is not None: entry["result"] = tasks._brief(out["result"], 600)
        if out.get("log"): entry["log"] = out["log"][-8:]
        stopped, inside = out.get("stopped"), after.get("paused") and _pause_id(after) != pause and str(after.get("reason"))
        if inside == "step": stopped = f"out of ticks (max_ticks {r['max_ticks']})" + (f": {stopped}" if stopped else "")
        elif inside and not stopped: stopped = f"the world paused inside it ({inside})"
        carry, send = r["then"] == "resume" and not stopped, None
        if stopped: entry.update(stopped=stopped[:600], **({"line": out["line"]} if out.get("line") else {}))
        if how == "held":
            if not carry: entry["work"] = "ended" if me["acted"] else "held"
            elif not me["acted"] or receipt.get("jobId"): send, entry["work"] = ("nav.resume", {"jobId": receipt.get("suspendedJobId")}), "carried_on"
            else: send, entry["work"] = (method, params), "restarted"
        elif how == "refused":
            if carry: send, entry["work"] = (method, params), "sent"
            else: entry["work"] = "not_started"
        elif how in ("cut", "task_ended"):
            carry, entry["work"] = False, how
        if not carry:
            entry["world"] = _halt(k, name, after)
        else:
            entry["world"] = "running"
            if send is None or not after.get("paused"):  # nothing to send with a resume of its own: end the pause, or the step the script ran in
                if after.get("paused") or after.get("stepping"): k._call("time.resume", timeout=10)
            if send is not None:
                send = (send[0], {**{a: b for a, b in send[1].items() if a != "_resume"}, **({"_resume": True} if after.get("paused") else {})})
        return send
    except Exception as e:  # the bridge failed under the reflex itself: say so, and leave the call's own outcome standing
        entry.update(stopped=f"{type(e).__name__}: {e}"[:600], world="unknown"); return None
    finally:
        _record(box, entry)


def _run(box: dict, name: str, r: dict, event: dict) -> tuple[dict, dict]:
    me = {"name": name, "acted": False, "budget": {"ticks": r["max_ticks"]}, "deadline": time.monotonic() + 30 + r["max_ticks"] * 0.15}
    lines, started = [], time.time()
    inside, budget = box["inside"].set(me), resume_once.set(me["budget"])  # the script's first action steps the world max_ticks
    try: out = scripts.execute(r["do"], {"event": event}, f"reflex_{name}", lines, _logged(name))
    except BaseException as e: out = {"stopped": f"{type(e).__name__}: {e}"[:600], "log": lines}
    finally:
        resume_once.reset(budget); box["inside"].reset(inside)
    tasks.log_call({"t": round(started, 2), "s": round(time.time() - started, 2), "tool": "reflex", "method": name,
                    "error": "stopped" if "stopped" in out else None, "caller": "reflex"})
    return out, me


def _halt(k: Kernel, name: str, clock: dict) -> str:
    """Leaves the world paused: a pause in force stands as it is; a running world, or one the script's step paused, is paused in the reflex's name."""
    if clock.get("paused") and clock.get("reason") != "step": return "paused"
    try: k._call("time.pause", reason=f"reflex:{name}", timeout=15); return "paused"
    except Exception as e: return f"pause failed: {e}"[:200]


# ---- with no call in flight ----

def _watch(box: dict) -> None:
    """Starts the thread that answers a pause no call is there to meet; one a process, replaced when this module reloads."""
    if state.get("task") or os.environ.get("MODBENCH_REFLEX_WATCH") == "0": return
    with box["lock"]:
        if box["stop"] is not None and not box["stop"].is_set(): return
        box["stop"] = threading.Event()
        threading.Thread(target=_loop, args=(box, box["stop"]), name="mb-reflexes", daemon=True).start()


def _loop(box: dict, stop: threading.Event) -> None:
    seen = None  # [the pause, when it was first seen, whether a background task had the body]
    while not stop.wait(0.1):
        try:
            clock = _clock(kernel()) if box["armed"] else {}
            reason, pause = _guard(clock), _pause_id(clock)
            if reason is None or pause == box["answered"] or not any(reason in r["when"] for r in list(box["armed"].values())):
                seen = None; continue
            if seen is None or seen[0] != pause: seen = [pause, time.monotonic(), False]
            if box["flight"] and time.monotonic() - seen[1] < 1: continue  # the call in flight meets this pause itself
            if tasks.held():  # a background task has the body, and a guard's pause ends it: the body is free once it has
                seen[2] = True; continue
            with box["running"]:
                _answer(kernel(), box, {"how": "task_ended" if seen[2] else "none"})
        except Exception:
            stop.wait(1)  # no bridge, or a client restarting: the pause is still there when it is back


def _stop() -> None:
    box = state.get("reflexes")
    if box and box["stop"] is not None: box["stop"].set()


_stop()  # a reload: this module's watcher takes over on the next acting call
mbtool.on_shutdown["reflexes"] = _stop


def answers(clock: dict) -> bool:
    """Whether a reflex is about to answer the pause this clock shows (mb_wait goes on waiting through such a pause)."""
    box, reason = _box(), _guard(clock)
    if reason is None or state.get("task") or os.environ.get("MODBENCH_REFLEX_WATCH") == "0": return False
    if _pause_id(clock) == box["answered"] or not any(reason in r["when"] for r in list(box["armed"].values())): return False
    _watch(box); return True


def deliver(limit: int = 6) -> list[dict]:
    """Each firing not yet handed to the model, oldest first, once; only in the model's own process."""
    if state.get("task"): return []
    box = _box()
    if box["armed"]: _watch(box)
    with box["lock"]:
        if not box["fired"]: return []
        out, box["fired"] = box["fired"], []; _save(box)
    return out if len(out) <= limit else [{"older": len(out) - limit}] + out[-limit:]


def armed() -> list[dict]:
    """For mb_status: a reflex is a standing order, and the model must be able to find the ones it has forgotten."""
    return [{"name": n, "when": r["when"], "then": r["then"]} for n, r in _box()["armed"].items()]


# ---- the tool ----

def _shown(name: str, r: dict, whole: bool = False) -> dict:
    out = {"name": name, "when": r["when"], "then": r["then"], "times_left": r.get("left"), "every_ticks": r["every_ticks"],
           "max_ticks": r["max_ticks"], "fired": r.get("fired", 0)}
    return {**out, "do": r["do"]} if whole else {**out, "lines": len(r["do"].splitlines())}


@tool(lane="control", effect="privileged", coverage=["time"])
def mb_reflex(name: str | None = None, when: str | list[str] | None = None, do: str | None = None, then: str | None = None,
              times: int | None = None, every_ticks: int | None = None, max_ticks: int | None = None, remove: bool = False) -> Any:
    """Arm a reflex: a script of yours that runs the moment a guard pauses the world, in place of the world waiting for you.

    A guard's pause stops everything until you answer it. A reflex is your answer written ahead
    of time. Nothing is armed until you arm it, and it does only what its script says.
    mb_reflex(name, when=..., do=...) arms or replaces one; mb_reflex() lists them; mb_reflex(name)
    shows one with its script; mb_reflex(name, remove=True) removes it; naming an armed one with
    only the fields to change edits it. They are kept across restarts until you remove them.

    when is the pause it answers, as mb_time status and pausedBy name it: health_dropped,
    health_threshold, air_threshold, burning, food_threshold, threat, action_failed, or
    interrupt:<reason> for an mb_interrupt watch of yours whose effects include pause. A list
    answers several. One reason has one reflex.
    do is Python defining main(event), with every mb_* tool and log(text) in scope, as in mb_run.
    event is {reason, tick, threats, edges, work} (edges as in mb_time status); work is the call the pause met ({call, how, jobId}),
    or None when no call of yours was running. The script starts in the paused world. Its first
    action lets time run, for at most max_ticks (default 200) in all; resume= and time resume or
    step are refused inside it. It asks no model: what happens is what you wrote.
    then="resume" (default) puts things back as they were going when main returns. The job or
    use the pause held carries on, and the call that was waiting for it returns as if nothing
    had paused. If the script's own actions ended that job, a mine or build goes on from its
    journal and any other call is sent again from the start (a fight's limits count from zero).
    With no call running, time resumes. then="pause" leaves the world paused for you instead.
    The world is also left paused, and the call returns what it would with no reflex, when the
    script fails, when another pause begins inside it (a guard, or its own time pause: that is
    how a script hands a case back to you), when it runs out of ticks, when the pause cut a call
    short instead of holding it, or when the pause ended a background task. A pause a reflex
    leaves standing reads reflex:<name> unless a guard's reason is already in place.
    times is how many firings it has (default no limit; 0 lifts one); every_ticks (default 40)
    is the least world time between two firings. A pause it lets stand for either says so.
    Every firing comes back once, as reflexes on your next tool result: {reflex, when, tick,
    ticks (the world time it took), result or stopped with line, log, work (carried_on,
    restarted, sent, held, ended, not_started, cut, task_ended), world (running or paused)}, or
    skipped with the reason.
    """
    box = _box()
    with box["lock"]:
        if name is None:
            return {"armed": [_shown(n, r) for n, r in box["armed"].items()]}
        if not re.fullmatch(r"[a-z][a-z0-9_]{0,48}", name): raise ValueError("name is lower_snake_case, at most 49 characters")
        old = box["armed"].get(name)
        if remove:
            box["armed"].pop(name, None); _save(box)
            return {"removed": old is not None, "armed": [_shown(n, r) for n, r in box["armed"].items()]}
        if all(v is None for v in (when, do, then, times, every_ticks, max_ticks)):
            if old is None: raise ValueError(f"no reflex is named {name}: arm it with when and do; armed: {sorted(box['armed'])}")
            return _shown(name, old, whole=True)
        if old is None and (when is None or do is None): raise ValueError("a new reflex needs when (the pause it answers) and do (its script)")
        r = dict(old or {"then": "resume", "every_ticks": 40, "max_ticks": 200, "left": None, "fired": 0, "lastTick": None})
        if when is not None: r["when"] = _reasons(box, name, when)
        if do is not None: r["do"] = _script(do)
        if then is not None:
            if then not in ("resume", "pause"): raise ValueError('then is "resume" or "pause"')
            r["then"] = then
        if times is not None:
            if not 0 <= times <= 100000: raise ValueError("times is 0 (no limit) to 100000")
            r["left"] = times or None
        if every_ticks is not None:
            if not 0 <= every_ticks <= 72000: raise ValueError("every_ticks is 0..72000")
            r["every_ticks"] = every_ticks
        if max_ticks is not None:
            if not 1 <= max_ticks <= 1200: raise ValueError("max_ticks is 1..1200")
            r["max_ticks"] = max_ticks
        box["armed"][name] = r; _save(box)
    _watch(box)
    return {"armed": _shown(name, r)}


def _reasons(box: dict, name: str, when: str | list[str]) -> list[str]:
    asked = [when] if isinstance(when, str) else list(when)
    if not asked or not all(isinstance(w, str) and 0 < len(w) <= 256 for w in asked): raise ValueError("when is a pause reason or a list of them")
    try: settings = _clock(kernel()).get("conditions") or {}
    except Exception: settings = {}  # no bridge now: the names are checked against what it says when there is one
    for w in asked:
        if tasks.waited_out(w) or w.startswith("reflex:") or w == "requested_resume":
            raise ValueError(f"{w} is not a guard's pause: a reflex answers the pauses that hand you a decision")
        if w in settings:
            raise ValueError(f"{w} is a guard's setting; name the pause it makes, as mb_time status and pausedBy show it (health_threshold, not healthBelow)")
        other = next((n for n, r in box["armed"].items() if n != name and w in r["when"]), None)
        if other: raise ValueError(f"{w} already has the reflex {other}: one reason has one reflex; edit or remove that one")
    return asked


def _script(code: str) -> str:
    try: tree = ast.parse(code)
    except SyntaxError as e: raise ValueError(f"do does not parse: {e}") from None
    main = next((n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == "main"), None)
    if main is None or [a.arg for a in main.args.args] != ["event"] or main.args.posonlyargs:
        raise ValueError("do defines main(event)")
    return code
