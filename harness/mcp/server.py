# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""ModdedBench MCP server: exposes the in-game bridge to a coding agent over stdio.

Start (from the repository root)::

    python harness/mcp/server.py            # stdio transport, what .mcp.json uses
    python harness/mcp/server.py --check    # import every tool module, print the tool table, exit

Tools come from harness/tools (see mbtool.py for the contract). Before every tool call the server
checks whether any ``.py`` under harness/tools changed; if so the whole ``mbtools_gtnh`` package is
evicted and re-imported atomically: a syntax error or a duplicate tool name leaves the previous
modules and registrations in place and is reported by ``mb_tools_status``. Live state survives in
``mbtool.state``.

Kernel connection: lazy, to ``kernel.bridge_url()`` (MB_BRIDGE_URL, default ws://127.0.0.1:47223/ws);
reconnects if the game restarts. When the game is not up, tools return a ``bridge_unavailable`` error.
"""

from __future__ import annotations

import asyncio
import contextvars
from concurrent.futures import ThreadPoolExecutor
import functools
import importlib
import inspect
import json
import os
import sys
import threading
import time
import traceback
import typing
from typing import Any

HERE = os.path.dirname(os.path.abspath(__file__))          # harness/mcp
ROOT = os.path.dirname(os.path.dirname(HERE))              # repository root
if HERE not in sys.path:
    sys.path.insert(0, HERE)

from mcp.server.fastmcp import FastMCP  # noqa: E402
from mcp.types import TextContent, CallToolResult, ToolAnnotations  # noqa: E402
from mcp.server.fastmcp.tools import Tool  # noqa: E402

import mbtool  # noqa: E402
from kernel import BridgeError, Kernel, bridge_url, reply_trace, CancellationScope, cancel_scope, call_resuming  # noqa: E402
from kernel import resume_arg as _resume_arg  # noqa: E402


def log(msg: str) -> None:
    print(f"[moddedbench-mcp] {msg}", file=sys.stderr, flush=True)


class ToolModule:
    def __init__(self, path: str, modname: str):
        self.path, self.modname = path, modname
        self.mtime = os.path.getmtime(path)
        self.module = None
        self.tools: dict[str, dict] = {}  # tool name -> meta
        self.error: str | None = None

    def stale(self) -> bool:
        try:
            return os.path.getmtime(self.path) != self.mtime
        except OSError:
            return True


class Server(FastMCP):
    def __init__(self, tools_dir: str = mbtool.TOOLS_DIR):
        self.tools_dir = tools_dir
        self.bridge_url = bridge_url()
        super().__init__(
            "moddedbench",
            instructions=(
                "Tools for playing GT New Horizons through the ModdedBench bridge. Tool modules under harness/tools are "
                "hot-reloaded on the next call; mb_call(method, params) can invoke any bridge method and mb_methods lists them."
            ),
        )
        self._pools = {"act": ThreadPoolExecutor(max_workers=8, thread_name_prefix="mb-act"),
                       "read": ThreadPoolExecutor(max_workers=8, thread_name_prefix="mb-read"),
                       "control": ThreadPoolExecutor(max_workers=4, thread_name_prefix="mb-control")}
        self.modules: dict[str, ToolModule] = {}   # path -> loaded module record
        self.name_owner: dict[str, str] = {}       # tool name -> path
        self.error: str | None = None              # last failed reload, until a reload succeeds
        mbtool.set_kernel_factory(lambda: Kernel(url=self.bridge_url, connect_retries=2, retry_delay=1.0))
        self._register_builtin()

    def close(self) -> None:
        mbtool.drop_kernel()
        for pool in self._pools.values():
            pool.shutdown(wait=False, cancel_futures=True)

    # ---- discovery / reload ----

    def discover(self) -> list[str]:
        """Every ``.py`` under tools_dir, recursively; ``_``-prefixed files and directories are skipped."""
        paths = []
        for base, dirs, files in os.walk(self.tools_dir):
            dirs[:] = sorted(d for d in dirs if not d.startswith("_"))
            paths.extend(os.path.join(base, f) for f in sorted(files) if f.endswith(".py") and not f.startswith("_"))
        return paths

    def _modname(self, path: str) -> str:
        rel = os.path.relpath(path, self.tools_dir)[:-3].replace(os.sep, "/")
        return mbtool.PACKAGE + "." + rel.replace("/", ".")

    def check_reload(self, force: bool = False) -> list[str]:
        """Re-imports the whole tool package when any file changed. Returns a list of messages."""
        paths = self.discover()
        if not force and set(paths) == set(self.modules) and not any(tm.stale() for tm in self.modules.values()):
            return []
        return [self._reload(paths)]

    def _reload(self, paths: list[str]) -> str:
        mbtool.install_package(self.tools_dir)
        previous = mbtool.evict_package()
        fresh = {p: ToolModule(p, self._modname(p)) for p in paths}
        builtin = set(self._tool_manager._tools) - set(self.name_owner)
        candidates, owners, tm = {}, {}, None
        try:
            for tm in fresh.values():
                tm.module = importlib.import_module(tm.modname)
                for attr in dir(tm.module):
                    fn = getattr(tm.module, attr)
                    meta = getattr(fn, "_mb_tool", None)
                    if not callable(fn) or meta is None or meta.get("module") != tm.modname:
                        continue
                    name = meta["name"]
                    if name in candidates or name in builtin:
                        raise ValueError(f"duplicate tool name: {name}")
                    effect = meta["effect"]
                    candidates[name] = Tool.from_function(self._worker(fn), name=name, title=meta.get("title"),
                        description=(fn.__doc__ or name).strip(), meta={"moddedbench": meta | {"lane": str(meta["lane"])}},
                        annotations=ToolAnnotations(readOnlyHint=effect == "read", destructiveHint=effect != "read"))
                    owners[name], tm.tools[name] = tm.path, meta
        except Exception:
            error = traceback.format_exc()
            mbtool.evict_package()
            sys.modules.update(previous)
            # Remember the attempted mtimes so a broken file is not re-tried on every call; registrations stay as they were.
            for path in list(self.modules):
                if path not in fresh:
                    del self.modules[path]
            for path, new in fresh.items():
                old = self.modules.setdefault(path, new)
                old.mtime, old.error = new.mtime, error if new is tm else None
            self.error = error
            where = os.path.basename(tm.path) if tm else "?"
            log(f"reload failed in {where}:\n{error}")
            return f"ERROR {where}: {error.strip().splitlines()[-1]}"
        # Everything imported and validated before replacing anything. Runs on the MCP loop.
        for name in list(self.name_owner):
            self._unregister(name)
        self._tool_manager._tools.update(candidates)
        self.name_owner = owners
        self.modules = fresh
        self.error = None
        return f"loaded {len(fresh)} modules: {len(candidates)} tools"

    def _worker(self, fn):
        if inspect.iscoroutinefunction(fn):
            return fn
        lane = fn._mb_tool["lane"]  # noqa: SLF001
        sig = inspect.signature(fn)
        # Every tool that acts takes resume=True: decide while the world is paused, then resume and act in one call,
        # the action starting on the first resumed tick. resume=N steps N ticks instead, then the world pauses again.
        resumable = mbtool.resumable(fn)

        def run(resume, kwargs):
            return call_resuming(fn, resume, **kwargs)

        @functools.wraps(fn)
        async def call(**kwargs):
            resume = _resume_arg(kwargs.pop("resume", False)) if resumable else False
            chosen = lane
            if callable(lane):
                try:
                    chosen = lane(kwargs)
                except Exception:
                    chosen = "act"
            pool = self._pools.get(chosen, self._pools["act"])
            context = contextvars.copy_context()
            return await asyncio.get_running_loop().run_in_executor(pool, context.run, functools.partial(run, resume, kwargs))
        # Resolve string annotations in the original module, not this wrapper's globals.
        hints = typing.get_type_hints(fn)
        params = [p.replace(annotation=hints.get(n, p.annotation)) for n, p in sig.parameters.items()]
        if resumable:
            extra = inspect.Parameter("resume", inspect.Parameter.KEYWORD_ONLY, default=False, annotation=bool | int)
            at = next((i for i, p in enumerate(params) if p.kind is inspect.Parameter.VAR_KEYWORD), len(params))
            params.insert(at, extra)
            hints = {**hints, "resume": bool | int}
        call.__signature__ = sig.replace(parameters=params, return_annotation=hints.get("return", sig.return_annotation))
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
        # Background tasks (harness/tools/tasks.py): a game call that would move the body is refused while a task has it
        # (Kernel.body_gate), and every result says what the body is doing and hands over each finished task once.
        tasks = sys.modules.get(mbtool.PACKAGE + ".tasks")
        lane, _ = self._lane(name, arguments)
        result = await self._call(name, arguments)
        if tasks is None:
            return result
        try:
            return _with_fields(result, await asyncio.to_thread(tasks.fields, lane == "read"))
        except Exception as e:
            log(f"task fields skipped: {e}")
            return result

    def _lane(self, name: str, arguments: dict[str, Any]) -> tuple[str | None, str | None]:
        """(lane, effect) of a loaded tool for these arguments; (None, None) for the built-ins."""
        owner = self.modules.get(self.name_owner.get(name, ""))
        meta = owner.tools.get(name) if owner else None
        if meta is None:
            return None, None
        lane = meta["lane"]
        if callable(lane):
            try:
                lane = lane(dict(arguments or {}))
            except Exception:
                lane = "act"
        return lane, meta["effect"]

    async def _call(self, name: str, arguments: dict[str, Any]) -> CallToolResult:
        trace = []
        token = reply_trace.set(trace)
        scope = CancellationScope()
        scope_token = cancel_scope.set(scope)
        started, error_code = time.time(), "cancelled"
        try:
            result = await super().call_tool(name, arguments)
            error_code = "tool_error" if getattr(result, "isError", False) else None
            if isinstance(result, CallToolResult):
                return result.model_copy(update={"content": _compact(result.content), "meta": {**(result.meta or {}), "bridge": trace}})
            if isinstance(result, tuple):
                content, structured = result
            elif isinstance(result, dict):
                structured = result
                content = [TextContent(type="text", text=_dumps(result))]
            else:
                content, structured = list(result), None
            return CallToolResult(content=_compact(content), structuredContent=structured, isError=False, _meta={"bridge": trace})
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
            error_code = error["code"]
            if procedure_receipts is not None:
                error["procedureReceipts"] = procedure_receipts
            if getattr(outer, "resumed_world", None):
                error["resumedWorld"] = outer.resumed_world  # the world runs now, although the call failed
            payload = {"ok": False, "error": error}
            return CallToolResult(isError=True, content=[TextContent(type="text", text=_dumps(payload))],
                                  structuredContent=payload, _meta={"bridge": trace})
        finally:
            reply_trace.reset(token)
            cancel_scope.reset(scope_token)
            self._log_call(name, arguments, started, error_code)

    def _log_call(self, name: str, arguments: dict[str, Any], started: float, error: str | None) -> None:
        method = (arguments or {}).get("method")
        entry = {"t": round(started, 2), "s": round(time.time() - started, 2), "tool": name,
                 "method": method if isinstance(method, str) else None, "error": error}
        try:
            os.makedirs(os.path.dirname(mbtool.CALL_LOG), exist_ok=True)
            with open(mbtool.CALL_LOG, "a", encoding="utf-8") as f:
                f.write(json.dumps(entry) + "\n")
        except OSError as e:
            log(f"call log skipped: {e}")

    # ---- built-in management tools ----

    def _register_builtin(self):
        srv = self

        def mb_reload_tools(force: bool = True) -> dict:
            """Re-import the tool modules under harness/tools (changed ones, or all with force) and report load errors."""
            return {"messages": srv.check_reload(force=force), "tools": sorted(srv.name_owner), "error": srv.error}

        def mb_tools_status() -> dict:
            """Loaded tool modules, their tools (with lane/rung/coverage), live state keys and the last reload error."""
            out = [{"file": os.path.relpath(tm.path, ROOT), "tools": {n: {k: (str(v) if callable(v) else v) for k, v in m.items()
                    if k != "module"} for n, m in tm.tools.items()}, "error": tm.error} for tm in srv.modules.values()]
            return {"bridge_url": srv.bridge_url, "modules": out, "error": srv.error, "state": sorted(mbtool.state),
                    "bridge": mbtool.state.get("kernel") is not None}

        self.add_tool(mb_reload_tools, name="mb_reload_tools", description=mb_reload_tools.__doc__)
        self.add_tool(mb_tools_status, name="mb_tools_status", description=mb_tools_status.__doc__)


def _dumps(value: Any) -> str:
    """A result's JSON, without whitespace: the model pays for every byte, and indent=2 was a third of them."""
    return json.dumps(value, separators=(",", ":"), ensure_ascii=False)


def _compact(content: list) -> list:
    """Every JSON text block re-serialised by _dumps (FastMCP indents what a tool returns); any other block as it is."""
    out = []
    for c in content:
        if isinstance(c, TextContent) and c.text[:1] in ("{", "["):
            try:
                c = TextContent(type="text", text=_dumps(json.loads(c.text)))
            except ValueError:
                pass
        out.append(c)
    return out


def _with_fields(result: CallToolResult, extra: dict) -> CallToolResult:
    """The result with extra fields in its JSON object, text and structured alike, or as a text block of its own."""
    if not extra:
        return result
    content = list(result.content)
    texts = [i for i, c in enumerate(content) if isinstance(c, TextContent)]
    try:
        obj = json.loads(content[texts[0]].text) if len(texts) == 1 else None
    except ValueError:
        obj = None
    if isinstance(obj, dict):
        content[texts[0]] = TextContent(type="text", text=_dumps({**obj, **extra}))
    else:
        content.append(TextContent(type="text", text=_dumps(extra)))
    structured = {**result.structuredContent, **extra} if isinstance(result.structuredContent, dict) else result.structuredContent
    return result.model_copy(update={"content": content, "structuredContent": structured})


def main() -> int:
    import argparse

    parser = argparse.ArgumentParser(description="Run the ModdedBench MCP server")
    parser.add_argument("--check", action="store_true", help="load tools, print them, and exit")
    args = parser.parse_args()
    srv = Server()
    for m in srv.check_reload(force=True):
        log(m)
    if args.check:
        for name, owner in sorted(srv.name_owner.items()):
            print(f"{name:32s} {os.path.relpath(owner, ROOT)}")
        if srv.error:
            print(f"ERROR\n{srv.error}")
        return 1 if srv.error else 0
    tasks = sys.modules.get(mbtool.PACKAGE + ".tasks")
    if tasks:  # a background task's time commands go out on this server's session, the one that owns time
        threading.Thread(target=tasks.relay, name="mb-task-clock", daemon=True).start()
    try:
        srv.run(transport="stdio")
    finally:
        srv.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
