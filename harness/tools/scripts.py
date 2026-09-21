# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Disposable scripts: one call that chains many tool calls, for a chore that is not worth a decision per step."""

from __future__ import annotations

import importlib
import re
import traceback
from pathlib import Path
from typing import Any

from mbtool import PACKAGE, tool

SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"


def _tools() -> dict:
    found = {}
    for path in Path(__file__).parent.glob("*.py"):
        if not path.stem.startswith("_"):
            found.update({n: f for n, f in vars(importlib.import_module(f"{PACKAGE}.{path.stem}")).items() if hasattr(f, "_mb_tool")})
    return found


@tool(effect="privileged", coverage=["meta"])
def mb_run(code: str | None = None, args: dict | None = None, name: str | None = None) -> Any:
    """Run a script that chains tool calls, so a whole chore costs one call and one decision instead of thirty.

    code is Python defining main(**args); every mb_* tool is already in scope as a function
    with the parameters you know (mb_craft(pattern=..., at=...)), plus log(text). "Go to the
    table, craft the casings, go to the furnace, load it, fetch the plates from the chest" is
    one script. By default it runs once and is gone. Pass name as well to keep it as
    harness/scripts/<name>.py, and name alone (with new args) to run a kept one again; keep one
    only if you expect to use it again soon. Scripts are disposable: most are obsolete within
    the hour, when the base changes or a machine takes the job over. Do not collect or polish
    them; a chore you keep scripting is a production line you have not built yet.
    Write steps as "make sure X holds" (check, then act), so that after an interruption you
    deal with the cause and can simply run it again. The first tool error stops the script:
    you get the error, the line, and what you logged, never a retry. One run must finish inside
    20 minutes: start long mining or building jobs and return rather than waiting on them.
    Nothing stops a script that never ends, and until the 20 minutes are up you can do nothing
    else: give every loop in it a count or a deadline of its own (time.monotonic()), and let a
    wait that has run out return what it saw instead of going round again. Try a new script on
    a small count before a large one.
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
    lines: list[str] = []
    scope = {**_tools(), "log": lambda text: lines.append(str(text)[:300]), "__name__": name or "script"}
    try:
        exec(compile(code, "<script>", "exec"), scope)
        return {"result": scope["main"](**(args or {})), "log": lines[-40:]}
    except Exception as error:  # the script is the model's own code: say where it stopped instead of failing the call opaquely
        here = [f.lineno for f in traceback.extract_tb(error.__traceback__) if f.filename == "<script>"]
        line = here[-1] if here else getattr(error, "lineno", None)
        return {"stopped": f"{type(error).__name__}: {error}"[:1500], "line": line,
                "source": code.splitlines()[line - 1].strip() if line and line <= len(code.splitlines()) else None, "log": lines[-40:]}
