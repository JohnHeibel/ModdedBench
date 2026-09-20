# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 Modbench contributors
"""ModdedBench MCP server: exposes the in-game bridge to a coding agent over stdio.

Start (from the ModdedBench directory)::

    python harness/mcp/server.py            # stdio transport, what .mcp.json uses
    python harness/mcp/server.py --check    # import every tool module, print the tool table, exit

Tools come from harness/tools (see mbtool.py for the contract). Modules are re-imported when their file changes (checked before every tool call) or on
``mb_reload_tools``; an import error is reported as a tool error and the previous version of that
module's tools stays registered. ``mb_tools_status`` shows what is loaded and any errors.

Kernel connection: lazy, to ``ws://127.0.0.1:47223/ws`` (override with MB_BRIDGE_URL); reconnects if
the game restarts. When the game is not up, tools return a ``bridge_unavailable`` error.
"""

from __future__ import annotations

import asyncio
import contextvars
from concurrent.futures import ThreadPoolExecutor
import functools
import inspect
import typing
import importlib
import importlib.util
import json
import os
import sys
import traceback
from typing import Any, Sequence

HERE = os.path.dirname(os.path.abspath(__file__))          # harness/mcp
ROOT = os.path.dirname(os.path.dirname(HERE))              # repository root
TOOLS_DIR = os.path.join(os.path.dirname(HERE), "tools")  # harness/tools: the hot-reloaded, model-editable surface
PROFILE_CONFIG = {
    # The GTNH client bridge listens on 47223; its server-side endpoint is 47224.
    "gtnh": {"tools_dir": TOOLS_DIR, "bridge_url": "ws://127.0.0.1:47223/ws"},
}
if HERE not in sys.path:
    sys.path.insert(0, HERE)

from mcp.server.fastmcp import FastMCP  # noqa: E402
from mcp.types import ContentBlock, TextContent, CallToolResult, ToolAnnotations
from mcp.server.fastmcp.tools import Tool  # noqa: E402

import mbtool  # noqa: E402
from kernel import BridgeError, Kernel, reply_trace, CancellationScope, cancel_scope  # noqa: E402


def log(msg: str) -> None:
    print(f"[moddedbench-mcp] {msg}", file=sys.stderr, flush=True)


class ToolModule:
    def __init__(self, path: str, tools_dir: str = TOOLS_DIR, module_prefix: str = "mbtools"):
        self.path = path
        rel = os.path.relpath(path, tools_dir).replace(os.sep, "/")[:-3]
        self.modname = module_prefix + "_" + rel.replace("/", "_")
        self.mtime = 0.0
        self.module = None
        self.tools: dict[str, dict] = {}  # tool name -> meta
        self.error: str | None = None

    def stale(self) -> bool:
        try:
            return os.path.getmtime(self.path) != self.mtime
        except OSError:
            return True


class Server(FastMCP):
    def __init__(self, profile: str = "gtnh"):
        if profile not in PROFILE_CONFIG:
            raise ValueError(f"unknown profile: {profile}; choose one of {', '.join(PROFILE_CONFIG)}")
        self.profile = profile
        self.tools_dir = PROFILE_CONFIG[profile]["tools_dir"]
        # Resolve the environment here, instead of relying on kernel.DEFAULT_URL captured at import time.
        self.bridge_url = os.environ.get("MB_BRIDGE_URL", PROFILE_CONFIG[profile]["bridge_url"])
        super().__init__(
            "moddedbench",
            instructions=(
                "Tools for playing Minecraft through the ModdedBench bridge. Tool modules for the selected profile are "
                "hot-reloaded; mb_call(method, params) can invoke any bridge capability and mb_methods lists methods."
            ),
        )
        self._workers = ThreadPoolExecutor(max_workers=8, thread_name_prefix="mb-tools")
        self._control_workers = ThreadPoolExecutor(max_workers=4, thread_name_prefix="mb-control")
        self.modules: dict[str, ToolModule] = {}
        self.name_owner: dict[str, str] = {}
        mbtool.set_kernel_factory(lambda: Kernel(url=self.bridge_url, connect_retries=2, retry_delay=1.0))
        self._register_builtin()

    # ---- discovery / reload ----

    def discover(self) -> list[str]:
        paths = []
        for sub in ("", "mods", "composed"):
            d = os.path.join(self.tools_dir, sub)
            if not os.path.isdir(d):
                continue
            for f in sorted(os.listdir(d)):
                if f.endswith(".py") and not f.startswith("_"):
                    paths.append(os.path.join(d, f))
        return paths

    def check_reload(self, force: bool = False) -> list[str]:
        """Loads new modules and reloads changed ones. Returns a list of messages."""
        msgs = []
        seen = set()
        for path in self.discover():
            seen.add(path)
            tm = self.modules.get(path)
            if tm is None:
                tm = ToolModule(path, self.tools_dir, f"mbtools_{self.profile}")
                self.modules[path] = tm
            if force or tm.stale():
                msgs.append(self._load(tm))
        for path in list(self.modules):
            if path not in seen:
                tm = self.modules.pop(path)
                for name in tm.tools:
                    self._unregister(name)
                msgs.append(f"removed {tm.modname} ({len(tm.tools)} tools)")
        return msgs

    def _load(self, tm: ToolModule) -> str:
        previous_module = sys.modules.get(tm.modname)
        try:
            tm.mtime = os.path.getmtime(tm.path)
            spec = importlib.util.spec_from_file_location(tm.modname, tm.path)
            mod = importlib.util.module_from_spec(spec)
            sys.modules[tm.modname] = mod
            # Execute the current source bytes directly. SourceFileLoader may accept a valid
            # timestamp/size .pyc after a same-second, same-size hot edit.
            with open(tm.path, "rb") as source:
                exec(compile(source.read(), tm.path, "exec"), mod.__dict__)
            candidates, metadata = {}, {}
            for attr in dir(mod):
                fn = getattr(mod, attr)
                meta = getattr(fn, "_mb_tool", None)
                if not callable(fn) or meta is None or meta.get("module") != tm.modname:
                    continue
                name = meta["name"]
                if name in candidates or (name in self._tool_manager._tools and name not in tm.tools):
                    raise ValueError(f"duplicate tool name: {name}")
                effect = meta.get("effect", "read" if meta["rung"] == 0 else "interaction")
                candidates[name] = Tool.from_function(self._worker(fn), name=name, title=meta.get("title"),
                    description=(fn.__doc__ or name).strip(), meta={"moddedbench": meta | {"effect": effect}},
                    annotations=ToolAnnotations(readOnlyHint=effect == "read", destructiveHint=effect != "read"))
                metadata[name] = meta
        except Exception:
            if previous_module is None:
                sys.modules.pop(tm.modname, None)
            else:
                sys.modules[tm.modname] = previous_module
            tm.error = traceback.format_exc()
            log(f"load failed for {os.path.basename(tm.path)}:\n{tm.error}")
            return f"ERROR {os.path.basename(tm.path)}: {tm.error.strip().splitlines()[-1]}"
        # All imports and signatures validated before replacing anything. Runs on the MCP loop.
        for name in tm.tools:
            self._unregister(name)
        self._tool_manager._tools.update(candidates)
        for name in candidates:
            self.name_owner[name] = tm.path
        tm.module, tm.tools, tm.error = mod, metadata, None
        return f"loaded {os.path.basename(tm.path)}: {len(candidates)} tools"

    def _worker(self, fn):
        if inspect.iscoroutinefunction(fn):
            return fn
        @functools.wraps(fn)
        async def call(**kwargs):
            name = getattr(fn, "_mb_tool", {}).get("name", fn.__name__)
            method = kwargs.get("method", "").split(".")[-1]
            control = name in ("mb_interrupt", "mb_tick", "mb_time", "mb_build_pause", "mb_build_cancel", "mb_stop") or (name in ("mb_act", "mb_actions", "mb_baritone", "mb_call")
                and method in ("stop", "cancel", "pause", "input_clear", "step", "mode"))
            pool = self._control_workers if control else self._workers
            context = contextvars.copy_context()
            return await asyncio.get_running_loop().run_in_executor(pool, context.run, functools.partial(fn, **kwargs))
        # Resolve string annotations in the original module, not this wrapper's globals.
        hints = typing.get_type_hints(fn)
        sig = inspect.signature(fn)
        call.__signature__ = sig.replace(parameters=[p.replace(annotation=hints.get(n, p.annotation))
            for n, p in sig.parameters.items()], return_annotation=hints.get("return", sig.return_annotation))
        call.__annotations__ = hints
        return call

    def _unregister(self, name: str) -> None:
        try:
            self.remove_tool(name)
        except Exception:
            self._tool_manager._tools.pop(name, None)  # noqa: SLF001
        self.name_owner.pop(name, None)

    # ---- call path ----

    async def call_tool(self, name: str, arguments: dict[str, Any]) -> CallToolResult:
        if not name.startswith("mb_reload"):
            for m in self.check_reload():
                log(m)
        trace = []
        token = reply_trace.set(trace)
        scope = CancellationScope()
        scope_token = cancel_scope.set(scope)
        try:
            result = await super().call_tool(name, arguments)
            if isinstance(result, CallToolResult):
                return result.model_copy(update={"meta": {**(result.meta or {}), "bridge": trace}})
            if isinstance(result, tuple):
                content, structured = result
            elif isinstance(result, dict):
                structured = result
                content = [TextContent(type="text", text=json.dumps(result))]
            else:
                content, structured = list(result), None
            return CallToolResult(content=content, structuredContent=structured, isError=False, _meta={"bridge": trace})
        except asyncio.CancelledError:
            await asyncio.shield(asyncio.to_thread(scope.cancel))
            raise
        except Exception as outer:
            e = outer
            procedure_receipts = None
            while True:
                if isinstance(getattr(e, "receipts", None), list):
                    procedure_receipts = e.receipts
                if e.__cause__ is None:
                    break
                e = e.__cause__
            if isinstance(e, BridgeError):
                error = {"code": e.code, "msg": e.msg, "method": e.method, "reply": e.reply}
            elif isinstance(e, ConnectionError):
                error = {"code": "bridge_unavailable", "msg": str(e)}
            elif isinstance(e, TimeoutError):
                error = {"code": "transport_timeout", "msg": str(e)}
            elif isinstance(e, ValueError):
                error = {"code": "bad_request", "msg": str(e)}
            else:
                log(traceback.format_exc())
                error = {"code": "tool_exception", "msg": str(e)}
            if procedure_receipts is not None:
                error["procedureReceipts"] = procedure_receipts
            payload = {"ok": False, "error": error}
            return CallToolResult(isError=True, content=[TextContent(type="text", text=json.dumps(payload))],
                                  structuredContent=payload, _meta={"bridge": trace})
        finally:
            reply_trace.reset(token)
            cancel_scope.reset(scope_token)

    # ---- built-in management tools ----

    def _register_builtin(self):
        srv = self

        def mb_reload_tools(force: bool = True) -> dict:
            """Re-import selected profile tool modules (changed ones, or all with force) and report load errors."""
            msgs = srv.check_reload(force=force)
            return {"messages": msgs, "tools": sorted(srv.name_owner)}

        def mb_tools_status() -> dict:
            """Loaded tool modules, their tools (with rung/coverage) and any import errors."""
            out = []
            for tm in srv.modules.values():
                out.append({"file": os.path.relpath(tm.path, ROOT), "tools": tm.tools, "error": tm.error})
            return {"profile": srv.profile, "bridge_url": srv.bridge_url, "modules": out,
                    "bridge": mbtool._kernel is not None}  # noqa: SLF001

        def mb_coverage(write: bool = True) -> str:
            """Regenerate bridge-research/COVERAGE.md from tool metadata and the C1–C15 taxonomy; returns the markdown."""
            import coverage as cov

            text = cov.generate(srv)
            if write:
                cov.write(text)
            return text

        self.add_tool(mb_reload_tools, name="mb_reload_tools", description=mb_reload_tools.__doc__)
        self.add_tool(mb_tools_status, name="mb_tools_status", description=mb_tools_status.__doc__)
        self.add_tool(mb_coverage, name="mb_coverage", description=mb_coverage.__doc__)


def main() -> int:
    import argparse

    parser = argparse.ArgumentParser(description="Run the ModdedBench MCP server")
    parser.add_argument("--profile", choices=sorted(PROFILE_CONFIG), default="gtnh",
                        help="tool and bridge profile (default: gtnh)")
    parser.add_argument("--check", action="store_true", help="load tools, print them, and exit")
    args = parser.parse_args()
    srv = Server(profile=args.profile)
    for m in srv.check_reload(force=True):
        log(m)
    if args.check:
        for name, owner in sorted(srv.name_owner.items()):
            print(f"{name:32s} {os.path.relpath(owner, ROOT)}")
        for tm in srv.modules.values():
            if tm.error:
                print(f"ERROR {tm.path}\n{tm.error}")
        return 1 if any(tm.error for tm in srv.modules.values()) else 0
    try:
        srv.run(transport="stdio")
    finally:
        mbtool.drop_kernel()
        srv._workers.shutdown(wait=False, cancel_futures=True)
        srv._control_workers.shutdown(wait=False, cancel_futures=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
