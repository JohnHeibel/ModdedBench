# SPDX-License-Identifier: MIT
# Copyright (c) 2026 ModdedBench contributors
"""The Codex CLI as an agent runtime: its command line, and its ``exec --json`` stream as the loop's events.

A runtime module is what the loop (agent_loop.py) and the feed (feed.py) know of an agent CLI: ``command`` builds one
launch, ``Parser().events(line)`` turns one parsed line of its output into the events below, ``BRIEF_FIRST`` says whether
the brief is the first message (else the command line carries it), ``ENV`` is set for the process and ``CREDENTIALS``
names the variables that hold a key (an empty one is not passed on: it would stand in for a login).

    {"type": "thread", "id"}                         the conversation's id, to resume it
    {"type": "turn_start"} / {"type": "turn_end", "usage": {input, cached, output}}   usage: what the totals still lack
    {"type": "usage", input, cached, output, context, window}   one model call, exact, for a runtime that reports each
                                                     (Codex: read from its own record of the thread, see Parser)
    {"type": "tool_start", "tool", "args"} / {"type": "tool_end", "tool", "args", "content", "failed"}   content: MCP blocks
    {"type": "say", "text"} / {"type": "think", "text"}
    {"type": "shell", "command"[, "done": True]}     the runtime's own tools: a command, a file edit
    {"type": "compacting"[, "done": True, "context"]}   for a runtime that says when it compacts
``size`` on an event is the characters it added to the context, for a runtime whose usage comes only at a turn's end
(Codex when its record of the thread cannot be found).
"""
from __future__ import annotations
import datetime, json, os, re, time
from pathlib import Path

NAME, BRIEF_FIRST, ENV, CREDENTIALS = "codex", True, {}, ("CODEX_API_KEY",)
BLOB = re.compile(r"[A-Za-z0-9+/=]{4000,}")  # base64 image data inside a logged tool result
USAGE = (("input", "input_tokens"), ("cached", "cached_input_tokens"), ("output", "output_tokens"))

def _sessions(): return Path(os.environ.get("CODEX_HOME") or Path.home() / ".codex") / "sessions"

def _when(stamp):
    try: return datetime.datetime.fromisoformat(str(stamp).replace("Z", "+00:00")).timestamp()
    except ValueError: return 0.0

def command(exe, repo, prompt, thread=None, model="", effort="", extra=()):
    """``codex exec --json``, the message on stdin. Options go before ``resume``: that subcommand does not accept all of
    them (-C, -s) after it. The reasoning summary is Codex's readable account of each reasoning step, for the stream's
    feed; the model's own reasoning is unchanged by it."""
    return [*exe, "exec", "--json", "-C", str(repo), *(["-m", model] if model else []), *(["-c", f'model_reasoning_effort="{effort}"'] if effort else []),
            "-c", 'model_reasoning_summary="detailed"', *extra, *(["resume", thread] if thread else []), "-"]

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

class Parser:
    """``exec --json`` reports usage only when a turn ends, and then as the thread's total, earlier launches included. Codex's
    own record of the thread (``$CODEX_HOME/sessions/.../rollout-*-<thread>.jsonl``) has a ``token_count`` after every model
    call: read from there the bill is exact mid-turn, and the calls recorded before this launch are a resumed thread's base,
    not counted again. Without that file the turn's end is the only count, and the feed estimates until then."""
    def __init__(self): self.thread, self.began, self.rollout, self.total = None, time.time(), None, {k: 0 for k, _ in USAGE}

    def _usage(self):
        """One usage event per model call the record has gained since the last look."""
        if self.rollout is None:
            found = sorted(_sessions().glob(f"*/*/*/rollout-*-{self.thread}.jsonl")) if self.thread else []
            if not found: return []
            self.rollout = [found[-1], 0]
        try:
            with open(self.rollout[0], "rb") as f: f.seek(self.rollout[1]); chunk = f.read()
        except OSError: return []
        done = chunk.rfind(b"\n") + 1; self.rollout[1] += done  # a line still being written waits for the next look
        out = []
        for line in chunk[:done].splitlines():
            if b'"token_count"' not in line: continue
            try: d = json.loads(line); info = d["payload"]["info"] if d.get("type") == "event_msg" and d["payload"].get("type") == "token_count" else None
            except (ValueError, KeyError, TypeError, AttributeError): continue
            if not isinstance(info, dict): continue  # a rate-limit update carries no usage
            total = {k: int((info.get("total_token_usage") or {}).get(f) or 0) for k, f in USAGE}
            spent = {k: max(0, total[k] - self.total[k]) for k, _ in USAGE}; self.total = total
            if _when(d.get("timestamp")) >= self.began:
                out.append({"type": "usage", **spent, "context": int((info.get("last_token_usage") or {}).get("input_tokens") or 0), "window": info.get("model_context_window")})
        return out

    def events(self, e):
        out = []
        if not self.thread:
            self.thread = _find_id(e)
            if self.thread: out.append({"type": "thread", "id": self.thread})
        out += self._usage()
        kind, item = e.get("type"), e.get("item") if isinstance(e.get("item"), dict) else {}
        what = item.get("type")
        if kind == "turn.started": out.append({"type": "turn_start"})
        elif kind == "turn.completed":
            u = e.get("usage") or {}; total = {k: int(u.get(f) or 0) for k, f in USAGE}
            # The thread's total: with the record read, only what it has not shown yet is missing.
            lack = {k: max(0, total[k] - self.total[k]) for k, _ in USAGE} if self.rollout else total
            if self.rollout: self.total = {k: max(total[k], self.total[k]) for k, _ in USAGE}
            out.append({"type": "turn_end", "usage": lack})
        elif kind == "item.started" and what == "mcp_tool_call": out.append({"type": "tool_start", "tool": item.get("tool", ""), "args": item.get("arguments")})
        elif kind == "item.started" and what == "command_execution": out.append({"type": "shell", "command": str(item.get("command", ""))})
        elif kind == "item.completed" and what in ("mcp_tool_call", "agent_message", "reasoning", "command_execution"):
            # For the estimate, when the record is not there. An image is billed by its size in tiles, about a thousand
            # tokens for a screenshot, not by its base64 text.
            size = {} if self.rollout else {"size": len(BLOB.sub("x" * 4000, json.dumps(item, default=str)))}
            if what == "agent_message": out.append({"type": "say", "text": str(item.get("text", "")), **size})
            elif what == "reasoning": out.append({"type": "think", "text": str(item.get("text") or ""), **size})  # "**Title**\n\nbody", maybe several
            elif what == "command_execution": out.append({"type": "shell", "command": str(item.get("command", "")), "done": True, **size})
            else:
                result = item.get("result") if isinstance(item.get("result"), dict) else {}
                out.append({"type": "tool_end", "tool": item.get("tool", ""), "args": item.get("arguments"), "content": result.get("content") or [],
                            "failed": item.get("status") == "failed" or bool(item.get("error")), **size})
        return out
