# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 Modbench contributors
"""Tool-module contract for the ModdedBench MCP server. Restart to change this file.

Every ``harness/tools/**/*.py`` (not ``_``-prefixed) is a hot-reloaded tool module importable as
``mbtools_gtnh.<name>``; siblings import each other with ``from mbtools_gtnh.notes import surface``.
A module declares MCP tools with::

    from mbtool import tool, kernel, state

    @tool(lane="act", name="mb_open_chest")   # name defaults to the function name
    def open_chest(x: int, y: int, z: int) -> dict:
        \"\"\"Right-click the chest at x,y,z and return the container view.\"\"\"
        k = kernel()
        k.call("act.use_block", x=x, y=y, z=z)
        return k.call("obs.container")

Rules: plain typed parameters (int/float/str/bool/list/dict, Optional with a default), a docstring (it
becomes the tool description), JSON-serialisable return values (or an ``mcp.server.fastmcp.Image``).
Raise ``BridgeError``/``ValueError`` for failures; the server turns them into tool errors.

``lane`` picks the worker pool: ``"read"`` (observations, never starved behind actions), ``"act"``
(default), ``"control"`` (stop/pause/cancel/interrupts; a small dedicated pool). A callable
``lane(kwargs) -> str`` resolves it per call for dispatchers that take a ``method`` argument.
``rung``/``coverage`` are descriptive metadata only.

``state`` is a plain dict that survives reloads: keep live objects there (the kernel, the interrupt
supervisor, notes stores, caches) keyed by owner name, and re-attach to them after ``import``.
"""

from __future__ import annotations

import functools
import importlib.abc
import importlib.machinery
import importlib.util
import os
import sys
import threading
from typing import Any, Callable

_HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS_DIR = os.path.join(os.path.dirname(_HERE), "tools")
PACKAGE = "mbtools_gtnh"
LANES = ("read", "act", "control")
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

from kernel import BridgeError, Kernel, bridge_url  # noqa: E402,F401

state: dict[str, Any] = {}                       # survives tool reloads; keyed by owner name
on_shutdown: dict[str, Callable[[], None]] = {}  # owner name -> hook run by drop_kernel()
_kernel_factory: Callable[[], Kernel] | None = None
_kernel_lock = threading.RLock()


def set_kernel_factory(factory: Callable[[], Kernel]) -> None:
    global _kernel_factory
    with _kernel_lock:
        drop_kernel()
        _kernel_factory = factory


def kernel() -> Kernel:
    """Shared concurrent connection; reconnect only after an actual transport failure."""
    with _kernel_lock:
        k = state.get("kernel")
        if k is None or not k.connected:
            if k is not None:
                k.close()
            k = state["kernel"] = _kernel_factory() if _kernel_factory else Kernel(connect_retries=3)
        return k


def drop_kernel() -> None:
    with _kernel_lock:
        for hook in list(on_shutdown.values()):
            try:
                hook()
            except Exception:
                pass
        k = state.pop("kernel", None)
        if k is not None:
            k.close()


def tool(rung: int = 3, coverage: list[str] | None = None, name: str | None = None, title: str | None = None,
         effect: str | None = None, lane: str | Callable[[dict], str] = "act"):
    """Marks a function as an MCP tool; metadata is read by server.py on (re)load."""
    if not callable(lane) and lane not in LANES:
        raise ValueError(f"lane must be one of {LANES} or a callable")

    def deco(fn):
        fn._mb_tool = {  # noqa: SLF001
            "name": name or fn.__name__, "title": title, "rung": rung, "coverage": list(coverage or []),
            "module": fn.__module__, "lane": lane,
            "effect": effect or ("read" if lane == "read" else "interaction"),
        }
        return fn

    return deco


# ---- the mbtools_gtnh package: source is exec'd directly so a same-second edit never hits a stale .pyc ----

class _Loader(importlib.abc.Loader):
    def __init__(self, path: str):
        self.path = path

    def create_module(self, spec):
        return None

    def exec_module(self, module):
        with open(self.path, "rb") as source:
            exec(compile(source.read(), self.path, "exec"), module.__dict__)


class _Finder(importlib.abc.MetaPathFinder):
    def __init__(self, directory: str):
        self.directory = directory

    def find_spec(self, fullname, path=None, target=None):
        if fullname != PACKAGE and not fullname.startswith(PACKAGE + "."):
            return None
        base = os.path.join(self.directory, *fullname.split(".")[1:])
        if os.path.isfile(base + ".py"):
            return importlib.util.spec_from_file_location(fullname, base + ".py", loader=_Loader(base + ".py"))
        if os.path.isdir(base):
            spec = importlib.machinery.ModuleSpec(fullname, None, is_package=True)
            spec.submodule_search_locations = [base]
            return spec
        return None


_finder = _Finder(TOOLS_DIR)
sys.meta_path.insert(0, _finder)


def install_package(directory: str = TOOLS_DIR) -> None:
    """Points ``mbtools_gtnh`` at a tools directory (the server calls this; tests may re-point it)."""
    _finder.directory = os.path.abspath(directory)
    importlib.invalidate_caches()


def evict_package() -> dict[str, Any]:
    """Drops every mbtools_gtnh module from sys.modules; returns them so a failed reload can restore."""
    old = {k: sys.modules.pop(k) for k in list(sys.modules) if k == PACKAGE or k.startswith(PACKAGE + ".")}
    importlib.invalidate_caches()
    return old


# ---- small helpers for tool code ----

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
