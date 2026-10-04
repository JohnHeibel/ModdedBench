# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Background tasks: a script that works the body while the model goes on thinking, and the mb_task tool.

mb_run(background=True) starts one detached process per task (``harness/mcp/task.py <id>``), which loads the
tool modules fresh, opens its own bridge session and holds the body lock (``.state/tasks/body.lock``) until it
ends, so a task outlives the MCP server and survives a thread refresh. Each task is ``.state/tasks/<id>.json``:
the MCP server reads it on every tool call (``fields``) to say what the body is doing and to hand over each
finished task once. While the lock is held, every other session's game call that would move the body is refused
(``gate``, as Kernel.body_gate): the game says which of its methods only read. A running task is alive while the body lock is held
(a dead or zombie process holds nothing); one whose lock is free is crashed, whoever reads it first.

Time: the client gives time control to one connected session and pauses the world when that session leaves
(pauseOnDisconnect). That session is the model's MCP server, so a task never sends a time command itself: the
server relays them (``relay``). With no server up the request waits in a file until one starts (relayPending).
"""
from __future__ import annotations

import json
import os
import signal
import subprocess
import sys
import threading
import time
import uuid
from pathlib import Path
from typing import Any

import mbtool
from kernel import BridgeError, Kernel, resume_once
from mbtool import kernel, state, tool
from mbtools_gtnh import scripts

TASKS = Path(os.environ.get("MB_TASKS_DIR") or Path(__file__).resolve().parents[2] / ".state" / "tasks")  # tests point it elsewhere
RUNNER = Path(mbtool.__file__).resolve().parent / "task.py"
ENDED = ("done", "failed", "cancelled", "crashed", "interrupted", "paused_by_script")
FAILED = ("failed", "crashed", "interrupted")
# Pauses a running job waits out (ClientClock.WAITED_OUT): a task waits them out too instead of ending. Any other reason is a guard's.
WAITED_OUT = {"requested_pause", "operator_hold", "client_disconnected", "client_unresponsive", "agent_disconnected",
              "paused_packet_overflow", "clock_protocol_error", "step"}
KEEP = 20  # status files kept; older delivered ones are removed
_write = threading.Lock()


class ScriptPaused(scripts.ScriptInterrupted):
    """The script paused the world: the task ends after the pause, whatever the script catches."""


# ---- status files ----

def _zombie(pid) -> bool:
    """An exited process nobody has reaped (the container's PID 1 reaps nothing); False where there is no /proc."""
    try:
        with open(f"/proc/{int(pid)}/stat") as f: return f.read().rsplit(")", 1)[1].split()[0] in ("Z", "X")
    except (OSError, IndexError, ValueError, TypeError): return False


def _alive(pid) -> bool:
    if not pid or _zombie(pid): return False
    if os.name == "nt":
        import ctypes
        k32 = ctypes.windll.kernel32; handle = k32.OpenProcess(0x1000, False, int(pid))
        if not handle: return False
        code = ctypes.c_ulong(); k32.GetExitCodeProcess(handle, ctypes.byref(code)); k32.CloseHandle(handle)
        return code.value == 259  # STILL_ACTIVE
    try: os.kill(int(pid), 0); return True
    except ProcessLookupError: return False
    except PermissionError: return True


def save(st: dict, folder: Path | None = None) -> None:
    folder = folder or TASKS
    with _write:
        tmp = folder / f"{st['task']}.tmp"; tmp.write_text(json.dumps(st, default=str), encoding="utf-8")
        for _ in range(20):
            try: tmp.replace(folder / f"{st['task']}.json"); return
            except PermissionError: time.sleep(0.01)  # Windows refuses the swap while a reader has it open


def _read(task_id: str, folder: Path | None = None) -> dict | None:
    try: return json.loads(((folder or TASKS) / f"{task_id}.json").read_text(encoding="utf-8"))
    except (OSError, ValueError): return None


def load(task_id: str, folder: Path | None = None) -> dict | None:
    folder, st = folder or TASKS, _read(task_id, folder)
    if st is None: return None
    if st.get("state") == "running" and not _running(st, folder):
        _crash(st, "the task process died without reporting", folder)
    return st


def _running(st: dict, folder: Path | None = None) -> bool:
    """Whether a running task's process is still there: it holds the body lock while it runs, and a dead or zombie
    process holds nothing. One just spawned has 30 s to take the lock and write its pid."""
    if st.get("pid") and _zombie(st["pid"]): return False
    return held(folder) or (not st.get("pid") and time.time() - st.get("started", 0) < 30)


def _crash(st: dict, error: str, folder: Path | None = None) -> None:
    try: seen = ((folder or TASKS) / f"{st['task']}.json").stat().st_mtime  # its last word: when it started its last call
    except OSError: seen = time.time()
    st.update(state="crashed", endedAt=time.time(), ended="crash", error=error, lastSeen=seen); save(st, folder)


def every(folder: Path | None = None) -> list[dict]:
    """All known tasks, newest first."""
    folder = folder or TASKS
    found = [load(p.stem, folder) for p in folder.glob("*.json") if p.stem != "relay"] if folder.is_dir() else []
    return sorted((t for t in found if t), key=lambda t: -t.get("started", 0))


def live(folder: Path | None = None) -> dict | None:
    return next((t for t in every(folder) if t["state"] == "running"), None)


def _age(st: dict) -> int:
    return int(time.time() - st.get("started", time.time()))


def body(st: dict | None) -> dict | None:
    """What every result says about a running task."""
    return st and {"task": st["task"], "name": st.get("name"), "for": _age(st), "now": st.get("now")}


def _brief(value: Any, limit: int = 1500) -> Any:
    text = json.dumps(value, default=str)
    return value if len(text) <= limit else text[:limit] + f"... (cut; mb_task shows it whole)"


def entry(st: dict) -> dict:
    """A finished task as the model is handed it: compact; mb_task(task=id) has everything."""
    out = {"task": st["task"], "name": st.get("name"), "state": st["state"], "ended": st.get("ended"),
           "ago": int(time.time() - st.get("endedAt", time.time()))}
    for key in ("result", "error", "line", "source", "interrupted", "guardsChanged", "guardsRestored", "paused", "relayPending"):
        if st.get(key) is not None: out[key] = _brief(st[key]) if key == "result" else st[key]
    return out


# ---- the body lock ----

def _lock(folder: Path, wait: float = 0.0):
    """The open, exclusively locked body.lock, or None if another process holds it after wait seconds."""
    folder.mkdir(parents=True, exist_ok=True)
    f = open(folder / "body.lock", "a+b"); until = time.monotonic() + wait
    while True:
        try:
            if os.name == "nt":
                import msvcrt; f.seek(0); msvcrt.locking(f.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl; fcntl.flock(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return f
        except OSError:
            if time.monotonic() >= until: f.close(); return None
            time.sleep(0.1)


def held(folder: Path | None = None) -> bool:
    f = _lock(folder or TASKS)
    if f is None: return True
    f.close(); return False


# ---- the MCP server's side ----

def start(code: str, args: dict | None, name: str | None, on_fail: str | None, minutes: float) -> dict:
    if state.get("task"): raise ValueError("a background task cannot start another; run the script in this one")
    if on_fail not in (None, "pause"): raise ValueError('on_fail is None or "pause"')
    if not 0 < minutes <= 60: raise ValueError("minutes is more than 0 and at most 60")
    if live() or held(): raise BridgeError("body_busy", refusal(live()), "mb_run")
    out, k = {}, kernel()
    if (k.call("time.status", timeout=5).get("state") or {}).get("paused"):
        record = {}; k._resume_for(record); out["resumedWorld"] = record  # as resume=True does; an operator hold refuses it
    task_id = uuid.uuid4().hex[:8]
    TASKS.mkdir(parents=True, exist_ok=True); (TASKS / f"{task_id}.py").write_text(code, encoding="utf-8")
    st = {"task": task_id, "name": name, "started": time.time(), "state": "running", "args": args or {}, "on_fail": on_fail,
          "minutes": minutes, "delivered": False, "pid": None}  # the task writes its own once it holds the body lock
    save(st); state["task_spawned"] = st["started"]
    with open(TASKS / f"{task_id}.log", "ab") as log:  # never the server's stdout: that is the MCP channel
        flags = {"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.DETACHED_PROCESS} if os.name == "nt" else {"start_new_session": True}
        proc = subprocess.Popen([sys.executable, str(RUNNER), task_id], stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                                cwd=str(TASKS.parents[1]), close_fds=True, **flags)
    state.setdefault("task_procs", {})[task_id] = proc  # polled, so a finished task is reaped and never looks alive
    return {"task": task_id, "started": st["started"], **out}


def refusal(st: dict | None) -> str:
    if not st: return "another process holds the body lock; mb_task shows what is running"
    return (f"the body is busy with background task {st['task']}" + (f" ({st['name']})" if st.get("name") else "") +
            f" for {_age(st)} s, now in {st.get('now') or 'its script'}: wait for it with mb_task(wait=...) or end it with mb_task(cancel=True)")


_releasing = threading.local()  # release() hands the body back, so its calls pass the gate
# Game calls that change state but never the body: stopping, the transport's own, world-memory bookkeeping (a
# region's protection is not here: changing it cancels the running job), and the model's own watches firing and
# being acknowledged, which are what it armed them for while the task works.
UNGATED = ("act.stop", "requests.cancel", "sys.methods", "memory.waypoint", "memory.route", "memory.record", "interrupt.fire", "interrupt.ack")


def gate(k: Kernel, method: str) -> None:
    """Refuses a game call that would move the body while a task has it (Kernel.body_gate, in every process but the
    task's own). The game says which of its methods only read; those, time control, UNGATED, and anything that does
    not call the game at all (notes, goals, files, recipes) still run."""
    if method.startswith("time.") or method in UNGATED or getattr(_releasing, "on", False):
        return
    if not held() and time.time() - state.get("task_spawned", 0) > 30:  # a task just spawned has 30 s to take the lock
        return
    effects = k.__dict__.get("_effects")
    if effects is None:
        from mbtools_gtnh.core import methods_map
        effects = k._effects = {n: m.get("effect") for n, m in methods_map(k.call("sys.methods", timeout=10)).items()}
    if effects.get(method) != "read":
        st = live()
        if st: raise BridgeError("body_busy", refusal(st), method)


Kernel.body_gate = gate


def settle(st: dict, folder: Path | None = None) -> dict:
    """What a task that ended without its own process doing it still owes: guards put back, on_fail's pause."""
    k = kernel()
    if st.get("guards") and st.get("guardsRestored") is None:
        try: st["guardsRestored"] = "pending" if (k.call("time.configure", **st["guards"]) or {}).get("pending") else True
        except Exception as e: st["guardsRestored"] = f"failed: {e}"[:200]
    if st.get("on_fail") == "pause" and st["state"] in FAILED and not st.get("paused"):
        st["paused"] = _pause(k, st["state"])
    save(st, folder); return st


def _pause(k, why: str):
    try:
        if not (k.call("time.status", timeout=5).get("state") or {}).get("paused"):
            if (k.call("time.pause", reason=f"background_task_{why}") or {}).get("pending"): return "pending"
        return True
    except Exception as e: return f"failed: {e}"[:200]


def deliver(folder: Path | None = None, limit: int = 5) -> list[dict]:
    """Each ended task not yet handed to the model, newest first, marked delivered; only in the model's own process."""
    if state.get("task"): return []
    for task_id, proc in list(state.get("task_procs", {}).items()):
        if proc.poll() is None: continue
        del state["task_procs"][task_id]
        st = load(task_id, folder)
        if st and st["state"] == "running" and not st.get("pid"):  # it died before it could write its own pid
            _crash(st, f"the task process exited ({proc.returncode}) before it started", folder)
    tasks, out = every(folder), []
    for st in tasks:
        if st["state"] in ENDED and not st.get("delivered"):
            if st["state"] == "crashed": st = settle(st, folder)  # this can be a while after the crash: the next call is when it is seen
            st["delivered"] = True; save(st, folder)
            out.append(entry(st))
    for st in tasks[KEEP:]:
        if st.get("delivered"):
            for p in (folder or TASKS).glob(f"{st['task']}.*"): p.unlink(missing_ok=True)
    return out[:limit] + ([{"older": len(out) - limit}] if len(out) > limit else [])


def fields(read: bool = False, folder: Path | None = None) -> dict:
    """The body and finished fields the server adds to every tool result."""
    out, st = {}, live(folder)
    if st:
        out["body"] = body(st)
        try: clock = kernel().call("time.status", timeout=5).get("state") or {}
        except Exception: clock = {}
        if clock.get("paused"): out["body"]["paused"] = clock.get("reason")  # no tick runs, so the task works on nothing until the world resumes
    if st and read: out["bodyBusy"] = True
    done = deliver(folder)
    if done: out["finished"] = done
    return out


def release(k) -> None:
    """Gives the body back: no job running, nothing on the cursor, no screen open."""
    _releasing.on = True
    try: k.call("act.stop", timeout=10)
    except Exception: pass
    try:
        view = k.call("obs.container", detail="compact", timeout=10)
        if view.get("open") and view.get("cursor"):
            mine = [s["i"] for s in view.get("slots") or [] if s.get("kind") in ("main", "hotbar") and s.get("ordinary")]
            k.call("gui.return_cursor", windowId=view["windowId"], epoch=view["epoch"], expectedCursor=view["cursor"], destinations=mine, timeout=10)
            view = k.call("obs.container", detail="compact", timeout=10)
        if view.get("open") and not view.get("cursor"): k.call("gui.close", timeout=10)  # closing with a stack held would drop it
    except Exception: pass
    finally: _releasing.on = False


def cancel_task(task_id: str, why: str = "cancelled", grace: float = 20.0, folder: Path | None = None) -> dict | None:
    """Ends a task and gives the body back. Never pauses: the task stops its job, puts guards back and exits; if it does
    not within grace seconds it is killed and the same is done from here."""
    folder = folder or TASKS
    st = load(task_id, folder)
    if not st or st["state"] != "running": return st
    (folder / f"{task_id}.cancel").write_text(why, encoding="utf-8")
    if os.name != "nt" and st.get("pid") not in (None, os.getpid()): os.kill(st["pid"], signal.SIGTERM)  # wakes it at once; the file says why
    until = time.monotonic() + grace
    while time.monotonic() < until and (load(task_id, folder) or {}).get("state") == "running":
        time.sleep(0.2)
    st = load(task_id, folder)
    if st and st["state"] in ("running", "crashed"):
        try:
            if st.get("pid") not in (None, os.getpid()): os.kill(st["pid"], signal.SIGKILL if os.name != "nt" else signal.SIGTERM)
        except OSError: pass
        try: release(kernel())
        except Exception: pass
        st.update(state="cancelled", ended=why, endedAt=time.time(), error="killed: it did not stop within %d s" % grace)
        st = settle(st, folder)
    return st


def end_all(why: str, wait: float = 0.0, folder: Path | None = None) -> dict | None:
    """Waits up to wait seconds for a running task to end, then cancels it (a deploy, the end of the run)."""
    st, until = live(folder), time.monotonic() + wait
    while st and time.monotonic() < until:
        time.sleep(1); st = live(folder)
    return cancel_task(st["task"], why, folder=folder) if st else None


def relay(folder: Path | None = None, get_kernel=None, stop: threading.Event | None = None) -> None:
    """The MCP server's thread that sends the tasks' time commands on its own session (the one that owns time), oldest
    first; requests left pending while no server was up are sent as soon as it starts."""
    folder, get_kernel, stop = folder or TASKS, get_kernel or kernel, stop or threading.Event()
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "relay.json").write_text(json.dumps({"pid": os.getpid()}), encoding="utf-8")
    while not stop.wait(0.1):
        try: asked = sorted(folder.glob("*.req"))
        except OSError: continue
        for req in asked:
            run = req.with_suffix(".run")
            try: req.rename(run)  # claimed: exactly one relay sends it
            except OSError: continue
            ask = {}
            try:
                ask = json.loads(run.read_text(encoding="utf-8"))
                reply = {"ok": True, "data": get_kernel().call(ask["method"], timeout=ask.get("timeout"), **ask.get("params", {}))}
            except BridgeError as e: reply = {"ok": False, "code": e.code, "msg": e.msg}
            except Exception as e: reply = {"ok": False, "code": type(e).__name__, "msg": str(e)}
            try:
                if not ask.get("pending"): req.with_suffix(".rep").write_text(json.dumps(reply, default=str), encoding="utf-8")  # nobody waits for a pending one
                run.unlink(missing_ok=True)
            except OSError: pass


# ---- the task process ----

TASK: dict = {}  # this process's task, once run() has it
_halt: list = []  # why the script must not make another call: a guard stop, its pause, a cancel


class TaskKernel(Kernel):
    """The task's own session. Time commands go through the model's server; resume and step are refused (a task never
    lifts a guard pause); a pause the task did not cause and no guard made is waited out, then the refused call is sent again."""
    body_gate = None  # the task is the body's owner

    def call(self, method, /, timeout=None, **params):
        if _halt and method != "time.status" and threading.current_thread() is not threading.main_thread():
            raise scripts.ScriptInterrupted(_halt[0])  # stopped: the tool in flight makes no further call (the task's own last calls are the main thread's)
        if method in ("time.resume", "time.step") or resume_once.get() is not None:
            raise ValueError("a background task never resumes or steps the world: lifting a pause is your decision, outside the task")
        if method.startswith("time.") and method != "time.status":
            running = TASK.get("state") == "running"  # the script's own call, not the task's last word
            if method == "time.configure" and running:
                before = (Kernel.call(self, "time.status", timeout=5).get("state") or {}).get("conditions") or {}
                TASK["guards"] = {**{k: before[k] for k in params if k in before and not k.startswith("_")}, **TASK.get("guards", {})}
                save(TASK)  # what to put back, even if this process dies
            out = self._clock(method, timeout, params)
            if method == "time.pause" and running:
                _halt.append("the script paused the world"); TASK["pausedByScript"] = True
                raise ScriptPaused("the script paused the world, which ends a background task")
            return out
        while True:
            try: return super().call(method, timeout, **params)
            except BridgeError as e:
                if not str(e.msg).startswith("time_paused") or not self._wait_out(): raise

    def _wait_out(self) -> bool:
        while True:
            clock = Kernel.call(self, "time.status", timeout=5).get("state") or {}
            if not clock.get("paused"): return True
            if _halt or not clock.get("held") and clock.get("reason") not in WAITED_OUT: return False
            time.sleep(0.5)

    def _clock(self, method, timeout, params):
        try: pid = json.loads((TASKS / "relay.json").read_text(encoding="utf-8"))["pid"]
        except (OSError, ValueError, KeyError): pid = None
        pending = not _alive(pid)  # never sent from this session: it would take the clock, and its exit would pause the world
        name = TASKS / f"{time.time_ns()}-{TASK['task']}-{uuid.uuid4().hex[:4]}"
        name.with_suffix(".tmp").write_text(json.dumps({"method": method, "params": params, "timeout": timeout, "pending": pending}), encoding="utf-8")
        name.with_suffix(".tmp").replace(name.with_suffix(".req"))
        if pending:
            TASK["relayPending"] = True; save(TASK)
            return {"pending": True, "note": "no MCP server is up: it sends this when it starts"}
        until = time.monotonic() + (timeout or self.timeout) + 10
        while time.monotonic() < until:
            try: reply = json.loads(name.with_suffix(".rep").read_text(encoding="utf-8"))
            except (OSError, ValueError): time.sleep(0.05); continue
            name.with_suffix(".rep").unlink(missing_ok=True)
            if reply["ok"]: return reply["data"]
            raise BridgeError(reply["code"], reply["msg"], method)
        raise TimeoutError(f"the MCP server did not relay {method}; the outcome is unknown")


def _traced(name: str, fn):
    """A tool as a background script calls it: what the body is doing goes in the status file, and each call in the call log."""
    def call(*args, **kwargs):
        if _halt: raise scripts.ScriptInterrupted(_halt[0])
        method = args[0] if args and isinstance(args[0], str) else kwargs.get("method")
        TASK["now"] = name + (f"({method})" if isinstance(method, str) else ""); save(TASK)
        started, error = time.time(), None
        try: return fn(*args, **kwargs)
        except scripts.ScriptInterrupted as e:
            error = "interrupted"; _halt.append(str(e)); raise
        except Exception as e:
            error = getattr(e, "code", None) or type(e).__name__; raise
        finally:
            log_call({"t": round(started, 2), "s": round(time.time() - started, 2), "tool": name,
                      "method": method if isinstance(method, str) else None, "error": error, "caller": TASK["task"]})
    return call


def log_call(entry: dict) -> None:
    try:
        with open(mbtool.CALL_LOG, "a", encoding="utf-8") as f: f.write(json.dumps(entry) + "\n")
    except OSError: pass


def run(task_id: str) -> int:
    """The task process: holds the body lock, runs the script on a worker thread, and watches for a cancel or the time limit."""
    TASK.update(_read(task_id) or {"task": task_id, "started": time.time(), "minutes": 20})
    lock = _lock(TASKS, wait=5)
    if lock is None:
        TASK.update(state="failed", ended="body_busy", endedAt=time.time(), error="another process holds the body lock"); save(TASK); return 1
    TASK.update(pid=os.getpid(), state="running"); save(TASK)  # alive from here: the lock says so
    state["task"] = task_id
    mbtool.set_kernel_factory(lambda: TaskKernel(connect_retries=3, retry_delay=1.0))
    woken = threading.Event()
    if os.name != "nt": signal.signal(signal.SIGTERM, lambda *_: woken.set())
    outcome = {"log": []}

    def work():
        try: outcome.update(scripts.execute((TASKS / f"{task_id}.py").read_text(encoding="utf-8"), TASK.get("args"), TASK.get("name"), outcome["log"], _traced))
        except BaseException as e: outcome.update(stopped=f"{type(e).__name__}: {e}"[:1500])
    worker = threading.Thread(target=work, daemon=True); worker.start()
    why, deadline = None, TASK["started"] + TASK.get("minutes", 20) * 60
    while worker.is_alive() and not why:
        woken.wait(0.5)
        cancel_file = TASKS / f"{task_id}.cancel"
        if cancel_file.exists() or woken.is_set(): why = cancel_file.read_text(encoding="utf-8").strip() if cancel_file.exists() else "cancelled"
        elif time.time() >= deadline: why = "time_limit"
    try: k = kernel()
    except Exception: k = None  # the bridge is down (a client restart): the result is still written; release and settle find nothing to do
    if why:
        _halt.append(why); release(k); worker.join(5)
    finish(k, outcome, why)
    lock.close()
    return 0


def finish(k, out: dict, why: str | None) -> dict:
    """The task's last word: its state and result, guards put back, on_fail's pause, a line in the call log."""
    if why: state_ = "failed" if why == "time_limit" else "cancelled"
    elif TASK.get("pausedByScript"): state_ = "paused_by_script"
    elif out.get("interrupted"): state_ = "interrupted"
    elif "stopped" in out: state_ = "failed"
    else: state_ = "done"
    TASK.update(state=state_, endedAt=time.time(), now=None, log=out.get("log", [])[-40:],
                ended=why or {"done": "returned", "failed": "error", "interrupted": "cancelled", "paused_by_script": "pause"}[state_])
    if state_ == "done": TASK["result"] = out.get("result")
    elif state_ in ("failed", "interrupted", "paused_by_script") and out.get("stopped"):
        TASK.update(error=out["stopped"], line=out.get("line"), source=out.get("source"))
    if state_ == "interrupted":
        TASK["interrupted"] = dict(out["interrupted"])  # its error says what the tool met; a guard is named only when one paused the world
        try: clock = k.call("time.status", timeout=5).get("state") or {}
        except Exception: clock = {}
        if clock.get("paused"):
            TASK["interrupted"]["pausedBy"] = clock["reason"]
            if clock["reason"] not in WAITED_OUT: TASK["ended"] = "guard"
    if TASK.get("guards"):
        try:
            now = (k.call("time.status", timeout=5).get("state") or {}).get("conditions") or {}
            TASK["guardsChanged"] = {g: [v, now.get(g)] for g, v in TASK["guards"].items() if now.get(g) != v} or None
            if not TASK["guardsChanged"]: del TASK["guards"]  # the script put them back itself
        except Exception: pass
    if TASK.get("guards") or state_ in FAILED:
        try: settle(TASK)
        except Exception as e: TASK["paused"] = TASK.get("paused") or f"failed: {e}"[:200]
    save(TASK)
    log_call({"t": round(TASK["started"], 2), "s": round(TASK["endedAt"] - TASK["started"], 2), "tool": "task", "method": TASK.get("name"),
              "error": None if state_ == "done" else state_, "caller": TASK["task"], "end": TASK["endedAt"]})
    return TASK


@tool(lane="control", coverage=["meta"])
def mb_task(task: str | None = None, wait: float = 0, cancel: bool = False) -> Any:
    """Your background task (mb_run background=True): its status, a wait for it to end, or cancel it.

    task defaults to the running one, else the newest. wait (seconds, at most 900) returns as soon as it ends.
    cancel=True ends it and gives the body back: its job stops, a stack held on the cursor goes back to your
    inventory, an open screen closes, guard settings it changed are put back; the world is not paused. The
    result is the whole status: state (running, done, failed, cancelled, crashed, interrupted, paused_by_script),
    ended (why), result or error with line, log, interrupted, paused (on_fail), and recent: your last few tasks.
    """
    if not 0 <= wait <= 900: raise ValueError("wait is 0..900 seconds")
    st = load(task) if task else (live() or next(iter(every()), None))
    if st is None: return {"task": None, "recent": []}
    if cancel: st = cancel_task(st["task"])
    until = time.monotonic() + wait
    while st["state"] == "running" and time.monotonic() < until:
        time.sleep(0.5); st = load(st["task"]) or st
    if st["state"] in ENDED and not st.get("delivered") and not state.get("task"):
        if st["state"] == "crashed": st = settle(st)
        st["delivered"] = True; save(st)
    out = {k: v for k, v in st.items() if k not in ("pid", "delivered", "guards", "pausedByScript")}
    if st["state"] == "running": out["for"] = _age(st)
    out["recent"] = [{"task": t["task"], "name": t.get("name"), "state": t["state"]} for t in every()[:5] if t["task"] != st["task"]]
    return out
