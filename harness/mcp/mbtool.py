# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 Modbench contributors
"""Tool-module support for the ModdedBench MCP server.

A tool module (any ``harness/tools/*.py`` or ``harness/tools/mods/*.py``) declares MCP tools with::

    from mbtool import tool, kernel, BridgeError

    @tool(rung=1, coverage=["C1"], name="mb_open_chest")   # name defaults to the function name
    def open_chest(x: int, y: int, z: int) -> dict:
        \"\"\"Right-click the chest at x,y,z and return the container view.\"\"\"
        k = kernel()
        k.call("act.use_block", pos=[x, y, z])
        k.call("act.wait_until", cond={"gui": "open"}, maxTicks=40)
        return k.call("obs.container")

Rules: plain typed parameters (int/float/str/bool/list/dict, Optional with a default), a docstring (it
becomes the tool description), JSON-serialisable return values (or an ``mcp.server.fastmcp.Image``).
Raise ``BridgeError``/``ValueError`` for failures; the server turns them into tool errors. Modules are
hot-reloaded when their file changes, so edits take effect on the next call without restarting.

``rung`` is the interaction rung from BRIDGE_PLAN.md (0 reads, 1 Baritone, 2 settings, 3 primitives,
4 OS fallback); ``coverage`` lists taxonomy ids (C1..C15, "move", "machine", "meta") for COVERAGE.md.
"""

from __future__ import annotations

import functools
import threading
import os
import sys
from typing import Any, Callable

_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

from kernel import BridgeError, Kernel  # noqa: E402,F401

_kernel: Kernel | None = None
_kernel_factory: Callable[[], Kernel] | None = None


_kernel_lock = threading.RLock()

def set_kernel_factory(factory: Callable[[], Kernel]) -> None:
    global _kernel_factory
    with _kernel_lock:
        drop_kernel()
        _kernel_factory = factory

def kernel() -> Kernel:
    """Shared concurrent connection; reconnect only after an actual transport failure."""
    global _kernel
    with _kernel_lock:
        if _kernel is None or not _kernel.connected:
            if _kernel is not None:
                _kernel.close()
            _kernel = _kernel_factory() if _kernel_factory else Kernel(connect_retries=3)
        return _kernel

def drop_kernel() -> None:
    global _kernel
    with _kernel_lock:
        if "gtnh_interrupts" in sys.modules:
            sys.modules["gtnh_interrupts"].close_supervisor()
        if _kernel is not None:
            _kernel.close()
        _kernel = None


def tool(rung: int = 3, coverage: list[str] | None = None, name: str | None = None, title: str | None = None, effect: str | None = None):
    """Marks a function as an MCP tool; metadata is read by server.py on (re)load."""

    def deco(fn):
        fn._mb_tool = {  # noqa: SLF001
            "name": name or fn.__name__,
            "title": title,
            "rung": rung,
            "coverage": list(coverage or []),
            "module": fn.__module__,
            "effect": effect or ("read" if rung == 0 else "interaction"),
        }
        return fn

    return deco


def glob_match(pattern: str, s: str) -> bool:
    import fnmatch

    return fnmatch.fnmatchcase(s, pattern)


def pos_of(x: int | list | tuple, y: int | None = None, z: int | None = None) -> list[int]:
    """Accepts (x,y,z) or a single [x,y,z]."""
    if isinstance(x, (list, tuple)):
        return [int(x[0]), int(x[1]), int(x[2])]
    return [int(x), int(y), int(z)]


def compact(obj: Any, max_len: int = 4000) -> Any:
    """Trims very long strings inside a JSON-ish value so tool outputs stay turn-sized."""
    if isinstance(obj, str):
        return obj if len(obj) <= max_len else obj[:max_len] + f"...(+{len(obj) - max_len} chars)"
    if isinstance(obj, list):
        return [compact(v, max_len) for v in obj]
    if isinstance(obj, dict):
        return {k: compact(v, max_len) for k, v in obj.items()}
    return obj


def cached(fn):
    """Per-process memo for expensive registry reads (cleared on module reload)."""
    return functools.lru_cache(maxsize=32)(fn)
