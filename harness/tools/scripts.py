# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Disposable scripts: one call that chains many tool calls, for a chore that is not worth a decision per step."""

from __future__ import annotations

import functools
import importlib
import re
import traceback
from pathlib import Path
from typing import Any

from kernel import call_resuming, resume_arg
from mbtool import PACKAGE, kernel, resumable, tool

SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
# What a guard interrupt, a guard pause or a paused world says when it ends or refuses a tool's work.
_INTERRUPTS = ("interrupt_latched", "time_paused", "world_paused", "world paused by a guard")


def _tools() -> dict:
    found = {}
    for path in Path(__file__).parent.glob("*.py"):
        if not path.stem.startswith("_"):
            found.update({n: _resuming(f) for n, f in vars(importlib.import_module(f"{PACKAGE}.{path.stem}")).items() if hasattr(f, "_mb_tool")})
    return found


def _resuming(fn):
    """A tool as a script sees it: acting tools take resume=True|N, each call its own directive, as a direct call does."""
    if not resumable(fn):
        return fn

    @functools.wraps(fn)
    def call(*args, resume=False, **kwargs):
        return call_resuming(fn, resume_arg(resume), *args, **kwargs)
    return call


class ScriptInterrupted(BaseException):
    """A guard interrupt or pause met by a tool inside a script. Not an Exception, so the script's own
    except Exception cannot swallow it: it stops the script and mb_run reports it."""


def _interrupt(error):
    """The interrupt, guard pause or cancellation behind error, through the errors it wraps, or None."""
    for _ in range(8):
        if error is None:
            return None
        if getattr(error, "code", None) == "cancelled" or any(m in str(error) for m in _INTERRUPTS):
            return error
        error = error.__cause__ or error.__context__
    return None


def _configures(name, args, kwargs):
    method = args[0] if args else kwargs.get("method")
    return (name, method) in {("mb_time", "configure"), ("mb_time", "time.configure"), ("mb_call", "time.configure")}


def _guards():
    return (kernel().call("time.status", timeout=5).get("state") or {}).get("conditions") or {}


class _Watch:
    """What the tools a script calls meet that the script must not hide: the first interrupt, and guard changes."""
    def __init__(self):
        self.interrupted, self.guards = None, None

    def wrap(self, name, fn):
        @functools.wraps(fn)
        def call(*args, **kwargs):
            if self.guards is None and _configures(name, args, kwargs):
                self.guards = _guards()  # read once, before the script's first change
            try:
                result = fn(*args, **kwargs)
            except Exception as error:
                if _interrupt(error) is None:
                    raise
                self.interrupted = self.interrupted or {"tool": name, "error": str(error)[:1500]}
                raise ScriptInterrupted(str(error)) from error
            if isinstance(result, dict) and result.get("interrupted"):  # a nested mb_run
                self.interrupted = self.interrupted or {"tool": name, **result["interrupted"]}
            return result
        return call

    def report(self, out):
        if self.interrupted:
            out["interrupted"] = self.interrupted
        if self.guards is not None:
            try:
                after = _guards()
            except Exception as error:
                out["guardsChanged"] = f"unread after the script: {error}"[:300]
                return out
            changed = {k: [self.guards.get(k), after.get(k)] for k in {*self.guards, *after} if self.guards.get(k) != after.get(k)}
            if changed:
                out["guardsChanged"] = changed
        return out


@tool(effect="privileged", coverage=["meta"])
def mb_run(code: str | None = None, args: dict | None = None, name: str | None = None, background: bool = False,
           on_fail: str | None = None, minutes: float = 20) -> Any:
    """Run a script that chains tool calls, so a whole chore costs one call and one decision instead of thirty.

    code is Python defining main(**args); every mb_* tool is already in scope as a function
    with the parameters you know (mb_craft(pattern=..., at=...)), plus log(text). "Go to the
    table, craft the casings, go to the furnace, load it, fetch the plates from the chest" is
    one script. Acting calls take resume=True or resume=N here too, each call its own directive,
    as they do when called directly. By default it runs once and is gone. Pass name as well to keep it as
    harness/scripts/<name>.py, and name alone (with new args) to run a kept one again; keep one
    only if you expect to use it again soon. Scripts are disposable: most are obsolete within
    the hour, when the base changes or a machine takes the job over. Do not collect or polish
    them; a chore you keep scripting is a production line you have not built yet.
    A guard interrupt or pause stops the script even inside try/except, and the result says
    which tool met it (interrupted). Guard settings the script changed with time configure come
    back as guardsChanged {name: [before, after]}; nothing is put back for you.
    Write steps as "make sure X holds" (check, then act), so that after an interruption you
    deal with the cause and can simply run it again. The first tool error stops the script:
    you get the error, the line, and what you logged, never a retry. A foreground run must finish
    inside 20 minutes and you can do nothing else until it does: give every loop in it a count
    or a deadline of its own (time.monotonic()), and let a wait that has run out return what it
    saw instead of going round again. Try a new script on a small count before a large one.

    background=True starts the script in its own process and returns {task, started} at once;
    it resumes a paused world first. The body is busy until the task ends: acting tools refuse
    with body_busy, reads still run (bodyBusy: true), and every result carries body {task, name,
    for (seconds), now (its current call)} and, once, finished [...] with each ended task's
    result or error and line. mb_task waits for it or cancels it. minutes (default 20, at most
    60) is its limit; past it the task is stopped and counts as failed (time_limit). Inside a
    background script resume= and time resume/step are refused; a time pause ends the task
    (paused_by_script); guard settings it configures are put back when it ends. A pause by you
    or the operator is waited out; a guard stop ends it (interrupted) whatever it catches.
    on_fail="pause" pauses the world if the task fails, crashes, is stopped by a guard or runs
    out of minutes; a cancel or a success never pauses.
    """
    if name is not None and not re.fullmatch(r"[a-z][a-z0-9_]{0,48}", name):
        raise ValueError("name is lower_snake_case, at most 49 characters")
    path = SCRIPTS / f"{name}.py" if name else None
    if code is None:
        if path is None or not path.is_file():
            raise ValueError(f"give code, or the name of a kept script: {sorted(p.stem for p in SCRIPTS.glob('*.py'))}")
        code = path.read_text(encoding="utf-8")
    elif path is not None:
        SCRIPTS.mkdir(exist_ok=True); path.write_text(code, encoding="utf-8", newline="\n")
    if background:
        from mbtools_gtnh import tasks
        return tasks.start(code, args, name, on_fail, minutes)
    return execute(code, args, name, [])


def execute(code: str, args: dict | None, name: str | None, lines: list, wrap=None) -> dict:
    """Runs a script's main(**args) with every tool in scope; wrap(name, fn), if given, goes around each tool (background tasks)."""
    watch = _Watch()
    tools = {n: watch.wrap(n, f) for n, f in _tools().items()}
    scope = {**({n: wrap(n, f) for n, f in tools.items()} if wrap else tools), "log": lambda text: lines.append(str(text)[:300]), "__name__": name or "script"}
    try:
        exec(compile(code, "<script>", "exec"), scope)
        return watch.report({"result": scope["main"](**(args or {})), "log": lines[-40:]})
    except (Exception, ScriptInterrupted) as error:  # the script is the model's own code: say where it stopped instead of failing the call opaquely
        here = [f.lineno for f in traceback.extract_tb(error.__traceback__) if f.filename == "<script>"]
        line = here[-1] if here else getattr(error, "lineno", None)
        shown = error.__cause__ if isinstance(error, ScriptInterrupted) and error.__cause__ else error
        return watch.report({"stopped": f"{type(shown).__name__}: {shown}"[:1500], "line": line,
                             "source": code.splitlines()[line - 1].strip() if line and line <= len(code.splitlines()) else None, "log": lines[-40:]})
