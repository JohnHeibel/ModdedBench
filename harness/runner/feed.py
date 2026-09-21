# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""What a viewer sees of a run: Codex's event stream turned into a feed of short lines and a live summary.

The loop hands every Codex event to ``Feed.event``. Two files come out, in ``$MODBENCH_OUTBOX/overlay`` (the
host sees that folder, ``.state/overlay`` without one; the console serves it to OBS): ``feed.jsonl``, one line per thing the model said or
did ({ts, kind: say|tool|fail|mark, text, tool?}; ``mark`` lines are the run's milestones), and ``live.json``,
the goal stack, what the agent is doing now, and running totals. Nothing here is shown to the model or asked
of it: the goal is the one it already keeps with ``mb_goal``, and tool lines are templates, not summaries.
"""
from __future__ import annotations
import json, re, time
from pathlib import Path


def _name(x):
    """An item selector or stack as a viewer would say it: its display name, else its id without the mod."""
    if isinstance(x, list): return ", ".join(dict.fromkeys(_name(i) for i in x if i))[:80]
    if not isinstance(x, dict): return str(x)
    return str(x.get("name") or x.get("ore") or str(x.get("id", "?")).split(":")[-1].replace("_", " "))

def _pos(p): return " ".join(str(int(v)) for v in p) if isinstance(p, list) else ""

LINES = {  # tool -> line from (arguments, result); anything absent gets the generic line
    "mb_goal": lambda a, r: "goal: " + str(a.get("subgoal") or a.get("quest") or a.get("chapter")) if any(a.values()) else "",
    "mb_status": lambda a, r: "", "mb_methods": lambda a, r: "", "mb_work_status": lambda a, r: "",
    "mb_craft": lambda a, r: (f"crafted {r.get('gained', '')} × {_name(r['crafted'])}" if r.get("crafted") else
                              "machine: " + " and ".join(f"{k} {_name(r[k])}" for k in ("loaded", "collected") if r.get(k)) if r.get("loaded") or r.get("collected") else "crafting"),
    "mb_recipes": lambda a, r: ("what uses " if a.get("mode") == "uses" else "recipe lookup: ") + _name(a),
    "mb_item_search": lambda a, r: "item search: " + str(a.get("query") or a.get("ore") or a.get("id") or ""),
    "mb_mine": lambda a, r: f"mining {a.get('quantity', 1)} × {_name(a.get('items') or a.get('blocks'))}" + (f" ({r['state']})" if r.get("state") not in (None, "succeeded") else ""),
    "mb_build": lambda a, r: f"building {len(a.get('cells') or []) or ''} blocks".replace("  ", " ") + (f" ({r['state']})" if r.get("state") not in (None, "succeeded") else ""),
    "mb_build_preview": lambda a, r: "planning a build",
    "mb_process": lambda a, r: ("going to " + _pos([v for k, v in sorted((a.get("goal") or {}).items()) if k in "xyz"]) if a.get("process") == "goal" else str(a.get("process", "")).replace("_", " ")),
    "mb_route": lambda a, r: "following route " + str(a.get("name")),
    "mb_scan": lambda a, r: "looking for " + _name(a.get("blocks") or [{"id": "blocks"}]),
    "mb_inventory": lambda a, r: "checking inventory", "mb_find": lambda a, r: "looking for " + _name(a.get("selector")),
    "mb_transfer": lambda a, r: f"moving {a.get('count', '')} {_name(a.get('expected'))}".replace("  ", " "),
    "mb_note_write": lambda a, r: "note: " + str((a.get("patch") or {}).get("title") or a.get("id")),
    "mb_notes": lambda a, r: "reading notes", "mb_memory": lambda a, r: "map memory: " + str(a.get("method", "status")),
    "mb_quest_claim": lambda a, r: "claimed a quest", "mb_quest_detect": lambda a, r: "handing in a quest",
    "mb_quest_observe": lambda a, r: "reading a quest: " + str(r.get("title") or r.get("name") or ""), "mb_quest_lines": lambda a, r: "reading the quest book",
    "mb_quest_search": lambda a, r: "quest search: " + str(a.get("query")),
    "mb_wait": lambda a, r: "waiting on the base" + (": woke" if r.get("woke") else ""), "mb_interrupt": lambda a, r: f"watch {a.get('operation')}: {a.get('name', '')}",
    "mb_run": lambda a, r: "script" + (f" {a['name']}" if a.get("name") else "") + (": stopped, " + str(r["stopped"])[:80] if r.get("stopped") else ""),
    "mb_wiki_search": lambda a, r: "wiki search: " + str(a.get("query")), "mb_wiki_read": lambda a, r: "wiki: " + str(a.get("title")),
    "mb_obs": lambda a, r: "observing", "mb_gui": lambda a, r: "in a menu: " + str(a.get("method", "")).replace("_", " "),
    "mb_act": lambda a, r: str(a.get("method", "acting")).replace("_", " "), "mb_call": lambda a, r: str(a.get("method", "")),
    "mb_time": lambda a, r: "clock: " + str(a.get("method", "status")), "mb_screenshot": lambda a, r: "looking at the screen", "mb_map": lambda a, r: "looking at the map",
}

def line(tool, args, result):
    args, result = (args if isinstance(args, dict) else {}), (result if isinstance(result, dict) else {})
    try:
        if tool in LINES: return LINES[tool](args, result)[:160]
    except Exception: pass  # a template that met a shape it did not expect falls back to the generic line
    return (tool.removeprefix("mb_").replace("_", " ") + " " + " ".join(str(v) for v in args.values() if isinstance(v, (str, int)) and len(str(v)) < 40)[:80]).strip()


class Feed:
    def __init__(self, folder):
        self.folder = Path(folder); self.folder.mkdir(parents=True, exist_ok=True)
        self.live = {"goal": {}, "status": {}, "stats": {"startedAt": time.time(), "turns": 0, "calls": 0, "failed": 0, "scripts": 0, "claims": 0,
                                                       "tokens": {"input": 0, "cached": 0, "output": 0}}}
        try: self.live.update(json.loads((self.folder / "live.json").read_text(encoding="utf-8")))  # a restarted loop keeps the run's totals
        except (OSError, ValueError): pass

    def _save(self):
        tmp = self.folder / "live.tmp"; tmp.write_text(json.dumps(self.live), encoding="utf-8"); tmp.replace(self.folder / "live.json")

    def add(self, kind, text, **more):
        text = re.sub("§.", "", text)  # Minecraft colour codes in quest titles
        if not text: return
        with open(self.folder / "feed.jsonl", "a", encoding="utf-8") as f: f.write(json.dumps({"ts": time.time(), "kind": kind, "text": text, **more}) + "\n")

    def status(self, state, text=""):
        """thinking | acting | waiting | between_turns | game_down | backing_off | ended"""
        self.live["status"] = {"state": state, "text": text, "since": time.time()}; self._save()

    def event(self, e):
        kind, item = e.get("type"), e.get("item") if isinstance(e.get("item"), dict) else {}
        stats, what = self.live["stats"], item.get("type")
        if kind == "turn.started": stats["turns"] += 1; self.status("thinking")
        elif kind == "turn.completed":
            u = e.get("usage") or {}
            for key, field in (("input", "input_tokens"), ("cached", "cached_input_tokens"), ("output", "output_tokens")): stats["tokens"][key] += int(u.get(field) or 0)
            self.status("between_turns")
        elif kind == "item.started" and what == "mcp_tool_call":
            self.status("waiting" if item.get("tool") == "mb_wait" else "acting", line(item.get("tool", ""), item.get("arguments"), None))
        elif kind == "item.started" and what == "command_execution": self.status("acting", "shell")
        elif kind == "item.completed" and what == "agent_message": self.add("say", str(item.get("text", "")).strip()[:600])
        elif kind == "item.completed" and what == "command_execution":
            cmd = str(item.get("command", ""))
            if "deploy.py request" in cmd: self.add("mark", "asked for a deploy of its own Java changes")
            self.status("thinking")
        elif kind == "item.completed" and what == "mcp_tool_call":
            tool = item.get("tool", ""); stats["calls"] += 1
            try: result = json.loads(item["result"]["content"][0]["text"])
            except (KeyError, IndexError, TypeError, ValueError): result = {}
            failed = item.get("status") == "failed" or bool(item.get("error")); stats["failed"] += failed
            goal = result if tool == "mb_goal" else result.get("goal") if tool == "mb_status" and isinstance(result, dict) else None
            if isinstance(goal, dict) and "subgoal" in goal and not failed:
                if goal.get("chapter") and self.live["goal"].get("chapter") not in (None, goal["chapter"]): self.add("mark", "new chapter: " + str(goal["chapter"]))
                self.live["goal"] = {k: goal.get(k) or "" for k in ("chapter", "quest", "subgoal", "serves")}
            if tool == "mb_run": stats["scripts"] += 1
            if tool == "mb_quest_claim" and not failed and isinstance(result, dict) and result.get("accepted"):
                stats["claims"] += 1; self.add("mark", "quest claimed: " + str(self.live["goal"].get("quest") or "?"))
            else: self.add("fail" if failed or isinstance(result, dict) and result.get("stopped") else "tool", line(tool, item.get("arguments"), result), tool=tool)
            self.status("thinking")
