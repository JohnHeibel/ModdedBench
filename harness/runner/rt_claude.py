# SPDX-License-Identifier: MIT
# Copyright (c) 2026 ModdedBench contributors
"""Claude Code as an agent runtime: its command line, and its ``-p --output-format stream-json`` stream as the loop's
events (rt_codex.py lists them). Written against Claude Code 2.1.284; harness/tests/fixtures/claude holds its streams.

Unlike Codex it reports every model call's usage as it happens and says when it compacts, so nothing is estimated but
the output tokens of a turn that has not ended (its ``result`` line corrects them). A resumed session keeps none of the
launch's flags, so every launch carries all of them.
"""
from __future__ import annotations
import json
from pathlib import Path

NAME, BRIEF_FIRST, CREDENTIALS = "claude", False, ("CLAUDE_CODE_OAUTH_TOKEN", "ANTHROPIC_API_KEY")
# A tool call may run 15 minutes (mb_wait); Claude Code's own limit is lower. Tool search would hide the mb_* schemas
# behind a lookup. Automatic memory is a second set of notes outside the world's own; the rest is traffic the gateway refuses.
ENV = {"MCP_TOOL_TIMEOUT": "1200000", "MCP_TIMEOUT": "30000", "ENABLE_TOOL_SEARCH": "false", "CLAUDE_CODE_DISABLE_AUTO_MEMORY": "1",
       "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC": "1", "DISABLE_AUTOUPDATER": "1"}
# What Codex has: a shell and file edits. Named, so that a tool a later version adds (sub-agents, workflows, schedules,
# skills, the web) is not there until someone decides it should be; the web and sub-agents are refused by name as well.
TOOLS, DENIED = "Bash,Read,Edit,Write,Glob,Grep", "WebSearch,WebFetch,Task,Agent"
MCP = "mcp__moddedbench__"

def command(exe, repo, prompt, thread=None, model="", effort="", extra=()):
    """``claude -p``, the message on stdin and the brief appended to the system prompt on every launch (it stays cached:
    the second of two launches read it from the cache). The container is the sandbox, so permissions are bypassed."""
    return [*exe, "-p", "--output-format", "stream-json", "--verbose", "--append-system-prompt-file", str(prompt),
            "--mcp-config", str(Path(repo) / "docker" / "claude-mcp.json"), "--strict-mcp-config", "--permission-mode", "bypassPermissions",
            "--tools", TOOLS, "--disallowedTools", DENIED, "--disable-slash-commands",
            *(["--model", model] if model else []), *(["--effort", effort] if effort else []), *extra, *(["--resume", thread] if thread else [])]

def _content(c):
    """A tool result as MCP content blocks: Claude Code gives a string (an error, a note that the result went to a file) or
    its own blocks, with an image's data one level down."""
    if isinstance(c, str): return [{"type": "text", "text": c}]
    return [{"type": "image", "data": (b.get("source") or {}).get("data")} if isinstance(b, dict) and b.get("type") == "image" else b for b in c or []]

class Parser:
    def __init__(self):
        self.started, self.seen, self.calls = False, set(), {}  # message ids counted; tool_use id -> its start event
        self.sum, self.pending = {"input": 0, "cached": 0, "output": 0}, 0  # tokens reported so far; output estimated since the last report

    def usage(self, **tokens):
        for key, n in tokens.items(): self.sum[key] += n
        return {"type": "usage", **tokens}

    def events(self, e):
        out, kind, sub = [], e.get("type"), e.get("subtype")
        if not self.started and e.get("session_id"): self.started = True; out += [{"type": "thread", "id": e["session_id"]}, {"type": "turn_start"}]
        if kind == "system" and sub == "thinking_tokens": self.pending += int(e.get("estimated_tokens_delta") or 0)
        elif kind == "system" and sub == "status":
            if e.get("status") == "compacting": out.append({"type": "compacting"})
            elif "compact_result" in e: out.append({"type": "compacting", "done": True})
        elif kind == "system" and sub == "compact_boundary":
            # The summary was a model call over the context as it stood. No message reports it and the result line's totals
            # leave it out, so it is counted here and is no part of what that line corrects.
            meta = e.get("compact_metadata") or {}
            out += [{"type": "usage", "input": int(meta.get("pre_tokens") or 0)}, {"type": "compacting", "done": True, "context": meta.get("post_tokens")}]
        elif kind == "assistant":
            msg = e.get("message") or {}; u = msg.get("usage") or {}
            read = int(u.get("cache_read_input_tokens") or 0); sent = int(u.get("input_tokens") or 0) + int(u.get("cache_creation_input_tokens") or 0) + read
            if sent and msg.get("id") not in self.seen:  # one line per content block, each with the message's usage; its output count is not final
                self.seen.add(msg.get("id")); out.append({**self.usage(input=sent, cached=read, output=self.pending), "context": sent}); self.pending = 0
            for b in msg.get("content") or []:
                what = b.get("type"); self.pending += len(json.dumps(b.get("input") if what == "tool_use" else b.get("text") or "")) // 4
                if what == "text": out.append({"type": "say", "text": str(b.get("text", ""))})
                elif what == "thinking" and b.get("thinking"): out.append({"type": "think", "text": str(b["thinking"])})
                elif what == "tool_use":
                    name, args = str(b.get("name", "")), b.get("input") if isinstance(b.get("input"), dict) else {}
                    start = ({"type": "tool_start", "tool": name[len(MCP):], "args": args} if name.startswith(MCP) else
                             {"type": "shell", "command": str(args.get("command") or (name + " " + str(args.get("file_path") or args.get("pattern") or "")).strip())})
                    self.calls[b.get("id")] = start; out.append(start)
        elif kind == "user" and isinstance((e.get("message") or {}).get("content"), list):
            for b in e["message"]["content"]:
                start = self.calls.pop(b.get("tool_use_id"), None) if isinstance(b, dict) and b.get("type") == "tool_result" else None
                if start and start["type"] == "shell": out.append({**start, "done": True})
                elif start: out.append({**start, "type": "tool_end", "content": _content(b.get("content")), "failed": bool(b.get("is_error"))})
        elif kind == "result":
            u = e.get("usage") or {}; read = int(u.get("cache_read_input_tokens") or 0)
            exact = {"input": int(u.get("input_tokens") or 0) + int(u.get("cache_creation_input_tokens") or 0) + read, "cached": read, "output": int(u.get("output_tokens") or 0)}
            window = max((int(m.get("contextWindow") or 0) for m in (e.get("modelUsage") or {}).values() if isinstance(m, dict)), default=0)
            if window: out.append({"type": "usage", "window": window})  # the only line that says how large the context window is
            out.append({"type": "turn_end", "usage": {key: exact[key] - self.sum[key] for key in exact}})
        return out
