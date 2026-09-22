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

BLOB = re.compile(r"[A-Za-z0-9+/=]{4000,}")  # base64 image data inside a logged tool result


def _name(x):
    """An item selector or stack as a viewer would say it: its display name, else its id without the mod."""
    if isinstance(x, list): return ", ".join(dict.fromkeys(_name(i) for i in x if i))[:80]
    if not isinstance(x, dict): return str(x)
    if x.get("name"): return str(x["name"])
    if x.get("ore"): words = re.findall("[A-Za-z][a-z]*|[0-9]+", str(x["ore"])); return " ".join(words[1:] + words[:1]).lower()  # oreCopper: copper ore
    last = str(x.get("id", "?")).split(":")[-1]
    return IDS.get(last) or last.replace("_", " ")

IDS = {"gt.blockores": "GregTech ore", "gt.blockmachines": "GregTech machine", "gt.metaitem.01": "GregTech item"}  # ids that say nothing to a viewer
SAID = {  # bridge method -> what a viewer reads; a method that is absent is shown with its underscores as spaces
    "eat": "eating", "use_block": "using a block", "use_item": "using the held item", "select_hotbar": "changing the held item", "attack": "attacking", "drop": "dropping items",
    "close": "closing the menu", "open_inventory": "opening the inventory", "click_slot": "in a menu: moving items",
    "pause": "paused the world to think", "resume": "let the world run again", "configure": "setting its survival guards", "status": "",
    "protect": "marking an area as its own", "waypoint": "saving a waypoint"}
def _said(method, prefix=""):
    method = str(method or ""); return SAID[method] if method in SAID else prefix + method.replace("_", " ")

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
    "mb_process": lambda a, r: (("going to " + _pos([v for k, v in sorted((a.get("goal") or {}).items()) if k in "xyz"])).removesuffix("going to ") or "walking" if a.get("process") == "goal" else str(a.get("process", "")).replace("_", " ")),
    "mb_route": lambda a, r: "following route " + str(a.get("name")),
    "mb_scan": lambda a, r: "looking for " + _name(a.get("blocks") or [{"id": "blocks"}]),
    "mb_inventory": lambda a, r: "checking inventory", "mb_find": lambda a, r: "looking for " + _name(a.get("selector")),
    "mb_transfer": lambda a, r: f"moving {a.get('count', '')} {_name(a.get('expected'))}".replace("  ", " "),
    "mb_note_write": lambda a, r: "note: " + str((a.get("patch") or {}).get("title") or a.get("id")),
    "mb_notes": lambda a, r: "reading notes", "mb_memory": lambda a, r: _said(a.get("method") or "status", "map memory: "),
    "mb_quest_claim": lambda a, r: "claimed a quest", "mb_quest_detect": lambda a, r: "handing in a quest",
    "mb_quest_observe": lambda a, r: "reading a quest: " + str(r.get("title") or r.get("name") or ""), "mb_quest_lines": lambda a, r: "reading the quest book",
    "mb_quest_search": lambda a, r: "quest search: " + str(a.get("query")),
    "mb_wait": lambda a, r: "waiting on the base" + (": woke" if r.get("woke") else ""), "mb_interrupt": lambda a, r: ("set a watch: " if a.get("operation") == "add" else f"watch {a.get('operation')}: ") + str(a.get("name", "")),
    "mb_run": lambda a, r: "script" + (f" {a['name']}" if a.get("name") else "") + (": stopped, " + str(r["stopped"])[:80] if r.get("stopped") else ""),
    "mb_wiki_search": lambda a, r: "wiki search: " + str(a.get("query")), "mb_wiki_read": lambda a, r: "wiki: " + str(a.get("title")),
    "mb_obs": lambda a, r: "looking around", "mb_gui": lambda a, r: _said(a.get("method"), "in a menu: "), "mb_click_slot": lambda a, r: "in a menu: moving " + _name(a.get("expected") or {"id": "items"}),
    "mb_act": lambda a, r: _said(a.get("method") or "acting"), "mb_call": lambda a, r: str(a.get("method", "")),
    "mb_time": lambda a, r: _said(a.get("method") or "status", "clock: "), "mb_item_info": lambda a, r: "looking up " + _name(a), "mb_quest_status": lambda a, r: "checking its quests", "mb_screenshot": lambda a, r: "looking at the screen", "mb_map": lambda a, r: "looking at the map",
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
                                                       "tokens": {"input": 0, "cached": 0, "output": 0, "estimated": 0}}}
        self.context = 0  # characters Codex has been shown this turn, for the estimate below
        try: self.live.update(json.loads((self.folder / "live.json").read_text(encoding="utf-8")))  # a restarted loop keeps the run's totals
        except (OSError, ValueError): pass

    def _save(self):
        tmp = self.folder / "live.tmp"; tmp.write_text(json.dumps(self.live), encoding="utf-8")
        try: tmp.replace(self.folder / "live.json")
        except OSError: pass  # the console has it open for a read (Windows refuses the swap); the next save lands

    def add(self, kind, text, **more):
        text = re.sub("§.", "", text)  # Minecraft colour codes in quest titles
        if not text: return
        with open(self.folder / "feed.jsonl", "a", encoding="utf-8") as f: f.write(json.dumps({"ts": time.time(), "kind": kind, "text": text, **more}) + "\n")

    def billed(self):
        """Input tokens so far: exact for finished turns, estimated for the one running."""
        t = self.live["stats"]["tokens"]; return t["input"] + t.get("estimated", 0)
    def status(self, state, text=""):
        """thinking | acting | waiting | between_turns | game_down | backing_off | ended"""
        self.live["status"] = {"state": state, "text": text, "since": time.time()}; self._save()

    def event(self, e):
        kind, item = e.get("type"), e.get("item") if isinstance(e.get("item"), dict) else {}
        stats, what = self.live["stats"], item.get("type")
        if kind == "turn.started": stats["turns"] += 1; self.context = 0; self.status("thinking")
        elif kind == "turn.completed":
            u = e.get("usage") or {}
            for key, field in (("input", "input_tokens"), ("cached", "cached_input_tokens"), ("output", "output_tokens")): stats["tokens"][key] += int(u.get(field) or 0)
            stats["tokens"]["estimated"] = 0  # the exact count has replaced it
            self.status("between_turns")
        if kind == "item.completed" and what in ("mcp_tool_call", "agent_message", "reasoning", "command_execution"):
            # Codex reports usage only when a turn ends, and a turn can last hours. Every call is billed for the whole context
            # again (mostly cached), so the bill grows with context x calls: this estimate is that sum, with the context
            # held at the size Codex compacts it to. Roughly right for gpt-6 (~4 characters a token); the turn's end corrects it.
            # An image is billed by its size in tiles, about a thousand tokens for a screenshot, not by its base64 text.
            self.context = min(self.context + len(BLOB.sub("x" * 4000, json.dumps(item, default=str))) // 4, 120000)
            stats["tokens"]["estimated"] += self.context
        if kind == "item.started" and what == "mcp_tool_call":
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
            if tool == "mb_quest_claim" and not failed and isinstance(result, dict) and (result.get("claimed") or result.get("accepted")):
                stats["claims"] += 1; self.add("mark", "quest claimed: " + str((result.get("quest") or {}).get("name") or self.live["goal"].get("quest") or "?"))
            else:
                why = result.get("error") if isinstance(result, dict) and isinstance(result.get("error"), dict) else {}
                why = "" if not failed else ": bad arguments" if why.get("code") == "bad_request" else ": " + str(why.get("code") or "failed").replace("_", " ")
                text = line(tool, item.get("arguments"), result)
                self.add("fail" if failed or isinstance(result, dict) and result.get("stopped") else "tool", text and text + why, tool=tool)
            self.status("thinking")
