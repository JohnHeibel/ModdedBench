# SPDX-License-Identifier: MIT
# Copyright (c) 2026 ModdedBench contributors
"""Rules: the model's table of what to watch for and what to do when it crosses; the mb_reflex and mb_wait tools.

A rule watches one kind of danger at its level, checked every tick by the server (SimulationClock, time.rules: that table
is the world's and outlives this process), or is a check of the model's own on any read, polled by interrupts.py's
supervisor (kept in its journal, so it too outlives a restart; mb_interrupt is the checks' old name). When it crosses,
the world freezes for the model; or the rule only tells, and the world runs on; or a script the model wrote ahead of time
answers the freeze. A script calls no model and has only the tools the model has. What tells and scripts did, and what
stopped a check, is handed to the model once, on its next tool result (``reflexes``, through tasks.fields), and wakes
mb_wait, which also returns the checks' journal.

``around`` is Kernel.around_action: it goes round every acting game call of the model's own process. The call that meets
a freeze a script answers (its job held, its action refused or cut short) runs the script there, so the tool that made
the call, and any script above it, sees the work finish as if nothing had paused. With no call in flight the watcher
thread answers it, so a script also runs while the model thinks. A freeze is answered once (the clock numbers its
pauses), and other threads' acting calls wait for a script that is running. The watcher also turns the server's tells
into reports. It runs in the model's own MCP server only (MODBENCH_REFLEX_WATCH=1, which server.main sets): tests and
smokes call what it calls.

The script runs in a stepped world: its first action resumes time for at most max_ticks. After it, then="resume" carries
on with the work in flight: the held job is waited on again; if the script's own actions ended it, a checkpointed job
(its receipt has a jobId: mine, build) goes on from its journal and any other call is sent again. Whatever cannot be
carried on leaves the world frozen for the model, as with no script: the script failed or ran out of ticks, another
pause began inside it, rules crossed together, its limits are spent, the call was cut short instead of held, or the
pause ended a background task.

Each script rule's firings, the reports not yet delivered, the last pause answered and the tells already heard are kept
in MODBENCH_REFLEXES_FILE (default ``.state/reflexes.json``); the live copy, its locks and the watcher live in
``mbtool.state["reflexes"]`` and so survive a reload of this module.
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
from mbtools_gtnh import interrupts, scripts, tasks

SCRIPT = {"then": "resume", "times": None, "every_ticks": 40, "max_ticks": 200}  # a script rule's defaults
ORDER = ("name", "kind", "level", "do", *SCRIPT)
CHECK = {"oneShot": False, "edge": True}  # a check set here crosses as any rule does: as it comes to match, and again only after it has stopped
WAIT_SLICE = 5.0  # seconds between mb_wait's own looks at the clock


def _file() -> Path:
    return Path(os.environ.get("MODBENCH_REFLEXES_FILE") or Path(__file__).resolve().parents[2] / ".state" / "reflexes.json")  # tests point it elsewhere


def _box() -> dict:
    """The live copy. ``inside`` is the firing whose script the context is running: kept here, with the locks, because a
    reload makes a new copy of this module while a script of the old one may still be running."""
    box = state.get("reflexes")
    if box is None:
        try: saved = json.loads(_file().read_text(encoding="utf-8"))
        except (OSError, ValueError): saved = {}
        box = state["reflexes"] = {"counts": saved.get("counts") or {}, "fired": saved.get("fired") or [], "answered": saved.get("answered"),
                                   "heard": saved.get("heard"), "lock": threading.RLock(), "running": threading.Lock(), "flight": 0,
                                   "noticed": None, "scripted": False, "stop": None, "inside": contextvars.ContextVar("reflex_inside", default=None)}
    return box


def _save(box: dict) -> None:
    try:
        file = _file(); file.parent.mkdir(parents=True, exist_ok=True)
        tmp = file.with_suffix(".tmp"); tmp.write_text(json.dumps({k: box[k] for k in ("counts", "fired", "answered", "heard")}), encoding="utf-8")
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
    """The reason of the pause in force if a rule's script may answer it: not one a job waits out, not a script's own."""
    reason = str(clock.get("reason"))
    return reason if clock.get("paused") and not tasks.waited_out(reason) and not reason.startswith("reflex:") else None


def _reason(r: dict) -> str:
    """A rule's pause reason: its kind, or kind:name when the name differs (SimulationClock.Rule.reason); a check's is the
    client's interrupt:<its reason, default its name>."""
    if r["kind"] == "check": return "interrupt:" + r["check"].get("reason", r["name"])
    return r["kind"] if r["name"] == r["kind"] else f"{r['kind']}:{r['name']}"


def _check(name: str, spec: dict) -> dict:
    """A check as a rule: the supervisor's spec less the rule's own fields, and what it does (a script, or by its effects)."""
    does = spec.get("do") or ("ask" if "pause" in spec.get("effects", ["notify"]) else "tell")
    return {"name": name, "kind": "check", "check": {k: v for k, v in spec.items() if k not in ("do", *SCRIPT)}, "do": does,
            **{k: spec[k] for k in SCRIPT if k in spec}}


def _sup() -> interrupts.InterruptSupervisor:
    return interrupts.get_supervisor(on_event=_noticed)  # made, with the checks a restart left, on first use; refused in a background task


def _checks() -> list[dict]:
    """The checks as rules; a background task has none (they are its model's own process's)."""
    if state.get("task"): return []
    sup = _sup()
    with sup.lock: return [_check(n, w.spec) for n, w in sup.watches.items()]


def _standing(clock: dict) -> list[dict]:
    """The rules as the clock shows them, and the checks of a supervisor already made (none is made here)."""
    sup = state.get("interrupts")
    if sup is None or state.get("task"): return list(clock.get("rules") or [])
    with sup.lock: return [*(clock.get("rules") or []), *(_check(n, w.spec) for n, w in sup.watches.items())]


def _scripted(clock: dict, reason: str | None) -> str | None:
    """The rule whose script answers a pause with this reason."""
    return next((r["name"] for r in _standing(clock) if r.get("do") not in ("ask", "tell") and _reason(r) == reason), None)


def _table(k: Kernel) -> list[dict]:
    return k.call("time.rules", timeout=10).get("rules") or []  # call: a background task's kernel sends time control through the model's server


def _rules(k: Kernel) -> list[dict]:
    return [*_table(k), *_checks()]


def _wake() -> None:
    """mb_wait waits on the checks' journal: have it look again."""
    sup = state.get("interrupts")
    if sup is not None:
        with sup.changed: sup.changed.notify_all()


def _record(box: dict, entry: dict) -> None:
    with box["lock"]:
        box["fired"] = (box["fired"] + [entry])[-40:]; _save(box)
    _wake()  # not under box's lock: the supervisor tells _noticed under its own


def _watching() -> bool:
    return os.environ.get("MODBENCH_REFLEX_WATCH") == "1" and not state.get("task")


# ---- round each acting call ----

def around(k: Kernel, method: str, timeout: float | None, params: dict) -> Any:
    box = _box(); me = box["inside"].get()
    if me is not None:
        return _within(k, me, method, timeout, params)
    if state.get("task") or method.startswith(("time.", "interrupt.")) or method in tasks.UNGATED:
        return k._call(method, timeout, **params)
    _watch(box)
    if not box["scripted"] or not k._acts(method): return k._call(method, timeout, **params)  # no script to run: nothing to go round
    spent = None
    try:
        while True:
            with box["running"]: pass  # a script on another thread has the body: its actions come first
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
tasks.TaskKernel.around_action = None  # a background task's pause ends the task; the script runs from the model's server once the body is free


def _flying(box: dict, by: int) -> None:
    with box["lock"]: box["flight"] += by


def _met(result: Any, error: BridgeError | None) -> dict | None:
    """How a call met a rule's pause: its work held (the receipt), its action refused before it began, or cut short."""
    receipt = result if error is None else ((error.reply or {}).get("error") or {}).get("receipt")
    if isinstance(receipt, dict) and receipt.get("state") == "suspended" and receipt.get("pausedBy"):
        return {"how": "held", "receipt": receipt}
    if error is None: return None
    if refused(error, "interrupt_latched") or refused(error, "time_paused") and "by a rule" in str(error.msg): return {"how": "refused"}
    if error.code == "cancelled" and "world paused by a rule" in str(error.msg): return {"how": "cut"}
    return None


def _within(k: Kernel, me: dict, method: str, timeout: float | None, params: dict) -> Any:
    """A game call from inside a rule's script."""
    if method in ("time.resume", "time.step") or resume_once.get() is not me["budget"]:
        raise ValueError("a rule's script runs inside its own tick budget (max_ticks): resume= and time resume or step are refused in it")
    if time.monotonic() > me["deadline"]:
        raise scripts.ScriptInterrupted("the script ran past its time limit")
    if not method.startswith("time.") and method not in tasks.UNGATED and k._acts(method): me["acted"] = True
    return k._call(method, timeout, **params)


def _logged(name: str):
    """wrap for scripts.execute: each tool call a script makes goes in the call log, as a background task's do."""
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
    """With box["running"] held: runs the script of the rule whose freeze is in force, if nothing has answered it yet.
    Returns the call to send in place of the one that met the pause, or None where that call's own outcome stands."""
    clock = _clock(k); reason = _guard(clock); name = _scripted(clock, reason)
    pause, tick = _pause_id(clock), int(clock.get("simulationTicks") or 0)
    with box["lock"]:
        if name is None or box["answered"] == pause: return None
        box["answered"] = pause; _save(box)
    r = next((r for r in _rules(k) if r.get("name") == name), None)
    if r is None: return None  # removed since the clock's last word
    r = {**SCRIPT, **r}; entry = {"rule": name, "when": reason, "tick": tick}
    with box["lock"]:
        count = box["counts"].setdefault(name, {"fired": 0, "lastTick": None})
        since, joined = tick - count["lastTick"] if count["lastTick"] is not None else None, ((clock.get("events") or [{}])[-1]).get("with")
        skipped = (f"rules crossed together ({', '.join([reason, *joined])}): they wait for you" if joined else
                   "its times are spent" if r["times"] and count["fired"] >= r["times"] else
                   f"it fired {since} ticks ago (every_ticks {r['every_ticks']})" if since is not None and 0 <= since < r["every_ticks"] else None)
        if not skipped: count["fired"] += 1; count["lastTick"] = tick; _save(box)
    if skipped:
        _record(box, {**entry, "skipped": skipped}); return None
    how, receipt = met["how"], met.get("receipt") or {}
    work = None if method is None else {"call": method, "how": how, **({"jobId": receipt.get("suspendedJobId")} if how == "held" else {})}
    try:
        if r["kind"] == "check":  # its latch would refuse the script's first action, and this script is the answer to it
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
    except Exception as e:  # the bridge failed under the script itself: say so, and leave the call's own outcome standing
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
    """Leaves the world paused: a pause in force stands as it is; a running world, or one the script's step paused, is paused in the rule's name."""
    if clock.get("paused") and clock.get("reason") != "step": return "paused"
    try: k._call("time.pause", reason=f"reflex:{name}", timeout=15); return "paused"
    except Exception as e: return f"pause failed: {e}"[:200]


# ---- tells and checks ----

def _news(box: dict, clock: dict) -> None:
    """The server's tells (status.told) not heard yet become reports. The first look ever takes what is there as heard."""
    told = clock.get("told")
    if told is None: return
    ids = [[t.get("sequence"), t.get("wallMs")] for t in told]
    with box["lock"]:
        if box["heard"] == ids: return
        fresh = [t for t, i in zip(told, ids) if box["heard"] is not None and i not in box["heard"]]
        box["heard"] = ids; _save(box)
    for t in fresh: _record(box, {"told": t.get("rule"), "tick": t.get("tick")})


def _noticed(kind: str, name: str, data: dict) -> None:
    """The supervisor's word on a check, as a report: a crossing that does not freeze (a tell), or what stopped it
    (a fault, a stall, a crossing never delivered, another world). A freeze says so itself."""
    sup = state.get("interrupts"); w = sup.watches.get(name) if sup is not None else None
    if kind == "triggered":
        if w is None or "pause" in w.spec.get("effects", ["notify"]): return
        entry = {"told": name, **({"payload": data["payload"]} if data.get("payload") else {})}
    elif kind == "fault": entry = {"rule": name or None, "fault": str(data.get("error"))[:600]}
    elif kind == "stalled": entry = {"rule": name, "fault": f"evaluate ran past its timeout_s ({data.get('timeout_s')})"}
    elif kind == "reaction_error": entry = {"rule": name, "fault": f"its crossing was not delivered: {data.get('error')}"[:600]}
    elif kind == "disarmed" and data.get("reason") == "context_changed": entry = {"rule": name, "fault": "the world, dimension or client changed: set it again"}
    else: return
    _record(_box(), entry)


# ---- the watcher ----

def _watch(box: dict) -> None:
    """Starts the thread that answers a freeze no call is there to meet; one a process, replaced when this module reloads."""
    if not _watching(): return
    with box["lock"]:
        if box["stop"] is not None and not box["stop"].is_set(): return
        box["stop"] = threading.Event()
        threading.Thread(target=_loop, args=(box, box["stop"]), name="mb-reflexes", daemon=True).start()


def _loop(box: dict, stop: threading.Event) -> None:
    seen, every = None, 0.1  # seen: [the pause, when it was first seen, whether a background task had the body]
    try: _sup()  # the checks a restart left watch again from here, not only once a tool asks for them
    except Exception: pass
    while not stop.wait(every):
        try:
            k = kernel(); clock = _clock(k)
            _news(box, clock)
            rules = _standing(clock); box["scripted"] = any(r.get("do") not in ("ask", "tell") for r in rules)
            every = 0.1 if any(r.get("do") != "ask" for r in rules) else 1.0  # only freezes to ask about: nothing to do quickly
            reason, pause = _guard(clock), _pause_id(clock)
            if clock.get("paused") and pause != box["noticed"]:  # a new pause: mb_wait looks again
                box["noticed"] = pause; _wake()
            if pause == box["answered"] or _scripted(clock, reason) is None:
                seen = None; continue
            if seen is None or seen[0] != pause: seen = [pause, time.monotonic(), False]
            if box["flight"] and time.monotonic() - seen[1] < 1: continue  # the call in flight meets this pause itself
            if tasks.held():  # a background task has the body, and a rule's pause ends it: the body is free once it has
                seen[2] = True; continue
            with box["running"]:
                _answer(k, box, {"how": "task_ended" if seen[2] else "none"})
        except Exception:
            stop.wait(1)  # no bridge, or a client restarting: the pause is still there when it is back


def _stop() -> None:
    box = state.get("reflexes")
    if box and box.get("stop") is not None: box["stop"].set()


_stop()  # a reload: this module's watcher takes over on the next acting call
mbtool.on_shutdown["reflexes"] = _stop


def answers(clock: dict) -> bool:
    """Whether a script is about to answer the pause this clock shows (mb_wait goes on waiting through such a pause)."""
    box = _box()
    if not _watching() or _scripted(clock, _guard(clock)) is None: return False
    if _pause_id(clock) == box["answered"]: return box["running"].locked()  # answered already, or being answered now
    _watch(box); return True


def deliver(limit: int = 6) -> list[dict]:
    """Each report not yet handed to the model, oldest first, once; only in the model's own process."""
    if state.get("task"): return []
    box = _box(); _watch(box)
    with box["lock"]:
        if not box["fired"]: return []
        out, box["fired"] = box["fired"], []; _save(box)
    return out if len(out) <= limit else [{"older": len(out) - limit}] + out[-limit:]


def lines(clock: dict) -> list[str]:
    """For mb_status: each rule in a few words, so a standing order the model has forgotten is found there."""
    out = []
    for r in [*(clock.get("rules") or []), *_checks()]:
        when, does = _row(r)["when"], r.get("do", "ask") if r.get("do", "ask") in ("ask", "tell") else "script"
        out.append(f"{when}: {does}" if r["name"] == r["kind"] else f"{r['name']} ({when}): {does}")
    return out


# ---- the tools ----

def _row(r: dict) -> dict:
    """A rule as the list shows it, with its firings and, for a check that is not watching, why."""
    does = r.get("do", "ask")
    out = {"name": r["name"], "when": r["kind"] + (f" {r['level']:g}" if "level" in r else ""), "do": does if does in ("ask", "tell") else "script"}
    fired = _box()["counts"].get(r["name"], {}).get("fired")
    return {**out, **({"fired": fired} if fired else {}), **_off(r)}


def _off(r: dict) -> dict:
    """A check that is not watching: one that fired (oneShot), faulted, or met another world. Setting it again re-arms it."""
    if r["kind"] != "check" or state.get("interrupts") is None: return {}
    st = state["interrupts"].status(r["name"])
    return {} if st["armed"] else {"armed": False, **({"fault": st["fault"]} if st.get("fault") else {})}


@tool(lane="control", effect="privileged", coverage=["time"])
def mb_reflex(name: str | None = None, when: str | dict | None = None, do: str | None = None, then: str | None = None,
              times: int | None = None, every_ticks: int | None = None, max_ticks: int | None = None, remove: bool = False) -> Any:
    """Your rules: each watches for one thing and, when it crosses, freezes the world for you, tells you, or runs your script.

    mb_reflex() lists them; mb_reflex(name) shows one whole; mb_reflex(name, when=..., do=...) adds
    or replaces one; naming one with only the fields to change edits it; remove=True deletes it.
    They outlive restarts. A new world has health_drop, health_below 8, air_below 180, food_below 6,
    burning and hostile_within 12, all asking you: change or delete any.
    when is a kind, with its level after it where it has one: health_drop (any hit), health_below N,
    air_below N (300 is full; under water it falls 1 a tick), food_below N, burning, hostile_within N
    (a mob takes you as its target within N blocks, 2N in line of sight, or a creeper swells; once a
    mob; mb_time status.threats lists them), action_failed (a job failed, or time report_failure).
    These are checked every tick. Or a check of your own on any watchable read, polled every 0.1 s:
    {queries: {alias: {method, params}}, condition}, where condition is one of all/any/not,
    eq/ne/lt/lte/gt/gte [path, value], exists/changed/increased/decreased path, any_of/all_of
    {path, where}; or {file, queries?}, a Python file defining evaluate(context) -> bool or {match,
    payload, prompt?}, with context.values, previous, state, read(method, **params) and prompt(text,
    **observations). A check also takes effects (notify, cancel: stop the job in flight, pause),
    latch (default with cancel or pause: your next action is refused once with what it saw, or
    mb_interrupt ack), oneShot (default false: off once it fires), edge (default true), consecutive,
    cooldown, timeout_s, operationScope, reason (its pause reads interrupt:<reason, default name>)
    and prompt. Setting a check again re-reads its file and re-arms it; a fault turns it off until
    then. Several rules may share a kind at different levels. Each crosses once, and again only
    after it has recovered.
    do: "ask" (default) freezes the world on the crossing and leaves it to you; the pause reason is
    the kind, or kind:name when the name differs, and rules crossing together freeze once. "tell"
    lets the world run and tells you on your next tool result (reflexes), waking mb_wait. For a
    check, do and "pause" in effects agree. Or Python defining main(event), with every mb_* tool
    and log(text) in scope as in mb_run: your answer, written ahead of time, run at once in the
    frozen world with no model asked. event is {reason, tick, threats, edges, work}, work being the
    call the pause met ({call, how, jobId}) or None. Its first action lets time run, at most
    max_ticks (default 200) in all; resume= and time resume or step are refused in it.
    then="resume" (default) carries on with the work the pause held as if nothing had paused, or
    resumes time with none; then="pause" leaves the world paused. It is left paused for you also
    when the script fails, runs out of ticks, meets another pause, when rules crossed together, or
    when the pause ended a background task (reason reflex:<name> if it had run on). times (default
    no limit) caps its firings, every_ticks (default 40) spaces them. Each firing comes back once
    in reflexes: {rule, when, tick, ticks, result or stopped with line, log, work, world}, or
    skipped and why. While mb_fight runs, its own hits, burns and mobs cross no rule.
    """
    k, box = kernel(), _box()
    rules = {r["name"]: r for r in _rules(k)}
    if name is None:
        return {"rules": [_row(r) for r in rules.values()]}
    old = rules.get(name)
    if old is None and (remove or when is None):
        raise ValueError(f"no rule is named {name}" + ("" if remove else ": add it with when") + f"; rules: {sorted(rules)}")
    if old is None and not re.fullmatch(r"[a-z][a-z0-9_]{0,48}", name): raise ValueError("name is lower_snake_case, at most 49 characters")
    if remove:
        if old["kind"] == "check": _sup().remove(name)
        else: k.call("time.rules", remove=[name], timeout=10)
    elif all(v is None for v in (when, do, then, times, every_ticks, max_ticks)):
        return {**old, **{key: v for key, v in _row(old).items() if key in ("fired", "armed", "fault")}}
    else:
        spec = {k_: v for k_, v in (old or {"name": name}).items() if when is None or k_ not in ("kind", "level", "check")}
        if when is not None: spec.update(_when(when))
        given = when.get("effects") if isinstance(when, dict) else None
        if do is not None: spec["do"] = do if do in ("ask", "tell") else _script(do)
        elif given is not None and ("pause" in given) == (spec.get("do", "ask") == "tell"): spec["do"] = "ask" if "pause" in given else "tell"  # the effects given say what it does
        does = spec.get("do", "ask")
        if given is not None and ("pause" in given) == (does == "tell"):
            raise ValueError(f'do and effects {given} disagree: "pause" in effects is what freezes the world, and a tell does not')
        if does in ("ask", "tell"): spec = {k_: v for k_, v in spec.items() if k_ not in SCRIPT}
        options = {"then": then, "times": times, "every_ticks": every_ticks, "max_ticks": max_ticks}
        if any(v is not None for v in options.values()) and does in ("ask", "tell"):
            raise ValueError("then, times, every_ticks and max_ticks are a script's: give do a script")
        if then is not None and then not in ("resume", "pause"): raise ValueError('then is "resume" or "pause"')
        for key, lo, hi in (("times", 0, 100000), ("every_ticks", 0, 72000), ("max_ticks", 1, 1200)):
            if options[key] is not None and not lo <= options[key] <= hi: raise ValueError(f"{key} is {lo}..{hi}")
        spec.update({key: v for key, v in options.items() if v is not None})
        if spec.get("times") == 0: del spec["times"]  # 0 lifts the cap
        if spec["kind"] == "check":
            check = {**CHECK, **spec["check"]}
            check["effects"] = [e for e in check.get("effects", []) if e != "pause"] + (["pause"] if does != "tell" else []) or ["notify"]
            _sup().add(name, {**check, **({key: spec[key] for key in ("do", *SCRIPT) if key in spec} if does not in ("ask", "tell") else {})}, replace=True)
            if old is not None and old["kind"] != "check": k.call("time.rules", remove=[name], timeout=10)
        else:
            k.call("time.rules", set=[{key: spec[key] for key in ORDER if key in spec}], timeout=10)
            if old is not None and old["kind"] == "check": _sup().remove(name)
    with box["lock"]: box["counts"].pop(name, None); _save(box)  # a rule set afresh counts its firings afresh
    _watch(box)
    rules = _rules(k); rows = [_row(r) for r in rules]
    box["scripted"] = any(r.get("do") not in ("ask", "tell") for r in rules)
    return {"removed": name, "rules": rows} if remove else {"rule": next(r for r in rows if r["name"] == name)}


def _when(when: str | dict) -> dict:
    if isinstance(when, dict):
        if ("file" in when) == ("condition" in when): raise ValueError("a check has exactly one of file or condition")
        return {"kind": "check", "check": when}  # the supervisor checks the rest as it arms it
    parts = str(when).split()
    if not 1 <= len(parts) <= 2: raise ValueError('when is a kind with its level after it ("air_below 120"), or a check {queries, condition} or {file}')
    if len(parts) == 1: return {"kind": parts[0]}
    try: level = float(parts[1])
    except ValueError: raise ValueError(f"{parts[1]} is not a level: give a number after the kind") from None
    return {"kind": parts[0], "level": int(level) if level.is_integer() else level}


def _script(code: str) -> str:
    try: tree = ast.parse(code)
    except SyntaxError as e: raise ValueError(f"do is ask, tell, or a script, and this one does not parse: {e}") from None
    main = next((n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == "main"), None)
    if main is None or [a.arg for a in main.args.args] != ["event"] or main.args.posonlyargs:
        raise ValueError("a script defines main(event)")
    return code


@tool(lane="read", coverage=["meta"])
def mb_wait(after: int | None = None, timeout_s: float = 600) -> Any:
    """Block until a rule needs you or timeout_s (1..900) passes.

    Call this instead of ending your turn whenever you are only waiting for machines or jobs: a
    finished turn cannot be woken. It wakes (woke true) when a rule tells you something or a script
    of yours fires (reflexes on its result says what), or when a check's journal has news: events
    are those (triggered with data.eventId and data.payload, fault, stalled, a failed delivery,
    another world), cursor is where the next wait goes on from (after; without it, from your last
    wait's; 0 replays what is kept), gap whether older ones were dropped (mb_interrupt_events
    replays the whole journal). Nothing you wait for happens in a paused world, so a wait never sits
    in one: paused when you call or paused while you wait, it returns at once with paused: the
    reason, unless a script of yours is answering that pause.
    """
    if (after is not None and after < 0) or not 1 <= timeout_s <= 900: raise ValueError("after>=0, timeout_s 1..900 required")
    sup, box = _sup(), _box(); _watch(box)
    if after is None: after = getattr(sup, "waited", 0)  # kept on the journal's own supervisor: a cursor means nothing in another journal
    end, wait, noticed = time.monotonic() + timeout_s, 0, box["noticed"]  # the first look does not wait: the world may be paused already
    while True:
        e = sup.events(after, 1000, wait, also=lambda: bool(box["fired"]) or box["noticed"] != noticed); after = sup.waited = e["cursor"]
        woke = [x for x in e["events"] if interrupts._wakes_runner(x)]
        out = {"woke": bool(woke or e["gap"] or box["fired"]), "cursor": after, "events": woke, "gap": e["gap"]}
        if out["woke"] or time.monotonic() >= end: return out
        try: clock = kernel().call("time.status", timeout=5).get("state") or {}
        except Exception: clock = {}  # no bridge now: wait on as in a running world
        _news(box, clock); noticed = box["noticed"]
        if box["fired"]: return {**out, "woke": True}
        if clock.get("paused") and not answers(clock): return {**out, "paused": clock.get("reason")}
        wait = max(0, min(WAIT_SLICE, end - time.monotonic()))
