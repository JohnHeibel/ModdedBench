# SPDX-License-Identifier: MIT
# Copyright (c) 2026 ModdedBench contributors
"""What a viewer sees of a run: the agent runtime's event stream turned into a feed of short lines and a live summary.

The loop hands every event to ``Feed.event``, as the runtime's module (rt_codex.py, rt_claude.py) normalised it. Two files come out, in ``$MODBENCH_OUTBOX/overlay`` (the
host sees that folder, ``.state/overlay`` without one; the console serves it to OBS): ``feed.jsonl``, one line per thing the model said or
did ({ts, kind: say|tool|fail|mark, text, tool?}; ``mark`` lines are the run's milestones), and ``live.json``,
the goal stack, what the agent is doing now, its background task (``body``), and running totals; and ``pops.json`` with ``pop-<n>.png``, the last few
things it looked at, for the stream's pop-ups. Nothing here is shown to the model or asked
of it: the goal is the one it already keeps with ``mb_goal``, and tool lines are templates, not summaries.
"""
from __future__ import annotations
import base64, json, re, time
from pathlib import Path

ACTIVE = ("thinking", "acting", "waiting")  # the states of a running turn
BASE = 30000  # tokens every call carries before the conversation: system prompt, brief and the tool schemas (a guess, corrected at turn end)


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
    "mb_goal": lambda a, r: "goal: " + str(a.get("subgoal") or a.get("quest") or a.get("chapter")) if a.get("subgoal") or a.get("quest") or a.get("chapter") else "",  # progress or serves alone is no news for the log
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
    "mb_view": lambda a, r: "looking down on the area" if a.get("look_down") else "looking at the blocks around it",
    "mb_scan": lambda a, r: "looking for " + _name(a.get("blocks") or [{"id": "blocks"}]),
    "mb_inventory": lambda a, r: "checking inventory", "mb_find": lambda a, r: "looking for " + _name(a.get("selector")),
    "mb_transfer": lambda a, r: f"moving {a.get('count', '')} {_name(a.get('expected'))}".replace("  ", " "),
    "mb_note_new": lambda a, r: "note: " + str(a.get("title") or a.get("id")),
    "mb_note_append": lambda a, r: "note: " + str(a.get("id")),
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


# Pop-ups: for the stream only, what the agent just looked at, drawn big for a few seconds. Each record is the part of a
# look tool's result the page draws, so the page never sees the rest; images (the map, a screenshot) go beside it as files.
POPS = 8  # records kept in pops.json, with the images they name
def _stack(s): return {"name": _name(s), "count": s.get("count", 1), "id": s.get("id", "")} if isinstance(s, dict) else {}
def _plain(lines): return [t for t in (re.sub("§.|¤¦.", "", str(x)).strip() for x in lines or []) if t and not t.startswith("{")][:8]  # not raw NBT

def _view(a, r, me):
    legend = {ch: {k: v[k] for k in ("id", "count", "name", "tile") if k in v} for ch, v in (r.get("legend") or {}).items()}
    named = [{"name": t.get("name"), "pos": t.get("pos")} for t in r.get("things") or [] if t.get("pos") and t.get("name")]
    return ("down" if a.get("look_down") else "view"), {"origin": r["origin"], "layers": r["layers"], "legend": legend, "things": named[:12], "you": r.get("you") or me}

def _radar(a, r, me):
    keep = ("type", "name", "pos", "distance", "visible", "hostile")
    ents = [{**{k: e[k] for k in keep if k in e}, "stack": _stack(e.get("stack")) or None} for e in r.get("entities") or []]
    return "radar", {"radius": (a.get("params") or {}).get("radius") or 32, "me": me, "entities": sorted(ents, key=lambda e: e.get("distance", 99))[:60]}

def _obs(a, r, me):
    m = a.get("method")
    if m == "entities": return _radar(a, r, me)
    if m == "waila": return "block", {"pos": r.get("pos"), "title": (_plain(r.get("head")) or ["?"])[0], "lines": _plain((r.get("body") or []) + (r.get("tail") or []))}
    if m == "tile": return "block", {"pos": r.get("pos"), "title": str(r.get("tileClass") or "?").split(".")[-1], "lines": [_name({"id": r.get("id")})]}
    if m == "block" and r.get("id") not in (None, "minecraft:air"):
        return "block", {"pos": r.get("pos"), "title": _name(r.get("picked") or r), "lines": [f"hardness {r['hardness']}" if "hardness" in r else "", "a tile entity" if r.get("hasTile") else ""]}

def _inventory(a, r, me):
    if r.get("totals"): return "inventory", {"totals": [{"name": _name(t.get("identity") or {}), "count": t.get("count")} for t in r["totals"]][:36]}
    if not r.get("main"): return None  # a container's slots: not drawn yet
    return "inventory", {"selected": r.get("selected"), "slots": [{"slot": s.get("slot"), **_stack(s.get("stack"))} for s in r.get("main") or [] if s.get("stack")],
                         "armor": [_stack(s.get("stack", s)) for s in r.get("armor") or [] if isinstance(s, dict)]}

def _recipe(a, r, me):
    first = (r.get("recipes") or [None])[0]
    if not isinstance(first, dict) or not first.get("inputs"): return None
    cell = lambda c: {"x": c.get("x"), "y": c.get("y"), **_stack((c.get("alternatives") or c.get("examples") or [c])[0])} if isinstance(c, dict) else {}  # a summary position with one item is that item
    return "recipe", {"handler": first.get("name") or (r.get("shared") or {}).get("name") or "", "inputs": [cell(c) for c in first["inputs"]][:16], "result": cell(first.get("result")) or _stack(r.get("target")),
                      "target": _name(r.get("target") or a), "total": r.get("total")}

def _notes(a, r, me):
    if "notes" in r: return "note", {"title": "looking up its notes", "titles": [n.get("title") for n in r["notes"]][:8]}

SCRIPTS = Path(__file__).resolve().parents[2] / "harness" / "scripts"

def _script(a, r, me):
    """Shown as a script starts (r is empty then): its name, arguments and the tools it chains; the console asks for a summary of the code."""
    if r: return None
    code = a.get("code")
    if code is None and a.get("name"):
        try: code = (SCRIPTS / f"{a['name']}.py").read_text(encoding="utf-8")
        except OSError: code = ""
    code = str(code or "")
    calls = list(dict.fromkeys(re.findall(r"\b(mb_\w+|bq_\w+|jei_\w+)\s*\(", code)))
    shown = {k: (json.dumps(v, default=str) if not isinstance(v, str) else v)[:60] for k, v in list((a.get("args") or {}).items())[:8]}
    return "script", {"name": a.get("name") or "", "args": shown, "calls": calls[:10], "code": code[:6000]}

_planned = {"key": None, "ts": 0.0}

def _plan(a, r, me):
    """A build as the cells it names (cells, or a drawing's layers): a preview marks the cells already right; a build shows as it starts."""
    if r and "differences" not in r: return None  # a build's receipt: its start showed the plan
    cells = [(c["pos"], c.get("id")) for c in a.get("cells") or [] if isinstance(c, dict) and isinstance(c.get("pos"), list) and len(c["pos"]) == 3]
    d = a.get("drawing") if isinstance(a.get("drawing"), dict) else {}
    if d.get("layers") and isinstance(d.get("origin"), list):
        (ox, oy, oz), legend = d["origin"], d.get("legend") or {}
        for n, layer in enumerate(d["layers"]):
            rows, y = (layer.get("rows"), layer.get("y")) if isinstance(layer, dict) else (layer, None)
            for z, row in enumerate(rows or []):
                for x, ch in enumerate(str(row)):
                    if ch in legend: cells.append(([ox + x, oy + n if y is None else y, oz + z], (legend[ch] if isinstance(legend[ch], dict) else {"id": legend[ch]}).get("id")))
    cells = [(p, i) for p, i in cells if i and not str(i).endswith(":air")][:800]
    key = hash(json.dumps(cells))
    if not cells or not r and _planned["key"] == key and time.time() - _planned["ts"] < 300: return None  # just previewed: the same picture again says nothing
    _planned.update(key=key, ts=time.time())
    todo = {tuple(x["pos"]) for x in r.get("differences") or [] if isinstance(x, dict) and isinstance(x.get("pos"), list)}
    known = "differences" in r and not r.get("differencesTruncated") and bool(r.get("total"))
    base = [min(p[k] for p, _ in cells) for k in range(3)]
    ids = list(dict.fromkeys(i for _, i in cells)); at = {i: n for n, i in enumerate(ids)}
    mats = [{"name": _name(m.get("selector") or {}), "id": (m.get("selector") or {}).get("id"), "needed": m.get("needed"), "missing": m.get("missing")} for m in r.get("materials") or [] if isinstance(m, dict)]
    if not mats: mats = [{"name": _name({"id": i}), "id": i, "needed": sum(1 for _, j in cells if j == i)} for i in ids]
    return "plan", {"building": not r, "origin": base, "ids": ids, "names": [_name({"id": i}) for i in ids], "materials": mats[:7],
                    "cells": [[p[0] - base[0], p[1] - base[1], p[2] - base[2], at[i], int(tuple(p) in todo or not known)] for p, i in cells],
                    "total": r.get("total") or len(cells), "correct": r.get("correct") if known else None}

POP = {  # tool -> (arguments, result, last known position of the player) -> (kind, data), or None for nothing worth showing
    "mb_run": _script, "mb_build_preview": _plan, "mb_build": _plan,
    "mb_view": _view, "mb_obs": _obs, "mb_inventory": _inventory, "mb_recipes": _recipe, "mb_notes": _notes,
    "mb_scan": lambda a, r, me: ("scan", {"what": _name(a.get("blocks") or [{"id": "blocks"}]), "found": r.get("found", len(r.get("matches") or [])),
                                         "matches": [{"pos": m.get("pos"), "name": _name(m)} for m in r.get("matches") or []][:40], "me": me}),
    "mb_map": lambda a, r, me: ("map", {k: r.get(k) for k in ("bounds", "blocksPerPixel", "player", "layer")}),
    "mb_screenshot": lambda a, r, me: ("shot", {}),
    "mb_note_new": lambda a, r, me: ("note", {"title": a.get("title") or a.get("id"), "text": str(a.get("text", ""))[:500], "wrote": True}) if r.get("file") else None,  # the receipt does not repeat the note
}


class Feed:
    def __init__(self, folder):
        self.folder = Path(folder); self.folder.mkdir(parents=True, exist_ok=True)
        self.live = {"goal": {}, "status": {}, "stats": {"activeSeconds": 0.0, "activeSince": None, "turns": 0, "calls": 0, "failed": 0, "scripts": 0, "claims": 0,
                                                       "tokens": {"input": 0, "cached": 0, "output": 0, "estimated": 0, "uncounted": 0}}}
        self.me = None  # where the player was last seen, for pop-ups of results that do not say
        self.context = 0  # tokens the runtime has been shown this turn, for the estimate below
        try: self.live.update(json.loads((self.folder / "live.json").read_text(encoding="utf-8")))  # a restarted loop keeps the run's totals
        except (OSError, ValueError): pass
        stats = self.live["stats"]; stats.setdefault("activeSeconds", 0.0)
        if stats.get("activeSince"):  # the last loop was killed inside a turn: count it up to its last recorded change
            stats["activeSeconds"] += max(0.0, (self.live["status"].get("since") or stats["activeSince"]) - stats["activeSince"])
        stats["activeSince"] = None

    def _save(self):
        try: tmp = self.folder / "live.tmp"; tmp.write_text(json.dumps(self.live), encoding="utf-8"); tmp.replace(self.folder / "live.json")
        except OSError: pass  # the console has it open for a read (Windows refuses the swap), or the outbox is away: the next save lands, and the feed never ends a run

    def add(self, kind, text, **more):
        text = re.sub("§.", "", text)  # Minecraft colour codes in quest titles
        if not text: return
        try:
            with open(self.folder / "feed.jsonl", "a", encoding="utf-8") as f: f.write(json.dumps({"ts": time.time(), "kind": kind, "text": text, **more}) + "\n")
        except OSError: pass  # a line the viewer misses, not a run that ends

    def body(self, value):
        """The background task the body is working on ({task, name, for, now}, None when idle): for the overlay and the console."""
        if self.live.get("body") != value: self.live["body"] = value; self._save()

    def billed(self):
        """Input tokens so far, cached ones included: exact for finished turns, estimated for the one running."""
        t = self.live["stats"]["tokens"]; return t["input"] + t.get("uncounted", 0) + t.get("estimated", 0)
    def status(self, state, text=""):
        """thinking | acting | waiting | between_turns | game_down | backing_off | ended"""
        now, stats = time.time(), self.live["stats"]
        # The overlay's clock is agent time: it runs only inside a turn, never between turns, in backoff or with the game down.
        if state in ACTIVE and not stats.get("activeSince"): stats["activeSince"] = now
        elif state not in ACTIVE and stats.get("activeSince"): stats["activeSeconds"] += now - stats["activeSince"]; stats["activeSince"] = None
        self.live["status"] = {"state": state, "text": text, "since": now}; self._save()

    def event(self, e):
        """One event of the runtime's stream, as its module normalised it (rt_codex.py lists them)."""
        kind, stats = e.get("type"), self.live["stats"]
        if kind == "turn_start":
            # A turn cut by the budget, a kill or a crash never reports its usage: keep its estimate instead of losing it.
            t = stats["tokens"]; t["uncounted"] = t.get("uncounted", 0) + t.get("estimated", 0); t["estimated"] = 0
            stats["turns"] += 1; self.context = BASE; self.live["compacting"] = False; self.status("thinking")
        elif kind == "turn_end":
            for key in ("input", "cached", "output"): stats["tokens"][key] += int((e.get("usage") or {}).get(key) or 0)
            stats["tokens"]["estimated"] = 0  # the exact count has replaced it
            self.status("between_turns")
        elif kind == "usage":  # exact, call by call: nothing to estimate
            for key in ("input", "cached", "output"): stats["tokens"][key] += int(e.get(key) or 0)
            self.live["context"] = {"tokens": e.get("context"), "window": e.get("window") or (self.live.get("context") or {}).get("window")}
        if "size" in e:
            # Codex reports usage only when a turn ends, and a turn can last hours. Every call is billed for the whole context
            # again (mostly cached), so the bill grows with context x calls: this estimate is that sum, with the context
            # held at the size Codex compacts it to. Roughly right for gpt-6 (~4 characters a token); the turn's end corrects it.
            self.context = min((self.context or BASE) + e["size"] // 4, 120000)
            stats["tokens"]["estimated"] += self.context
        if kind == "compacting":  # only a runtime that says so (Claude Code): the console holds the world on it
            if e.get("done"):
                stats["compactions"] = stats.get("compactions", 0) + bool(self.live.get("compacting")); self.live["compacting"] = False
                if e.get("context"): self.live["context"] = {**(self.live.get("context") or {}), "tokens": e["context"]}
            else: self.live["compacting"] = True
            self.status("thinking")
        elif kind == "tool_start":
            self.live["compacting"] = False
            self.status("waiting" if e.get("tool") == "mb_wait" else "acting", line(e.get("tool", ""), e.get("args"), None))
            if e.get("tool") in ("mb_run", "mb_build"):  # a script or a build can run for minutes: say what it is while it runs, not after
                try: self.look(e["tool"], e.get("args"), {}, [])
                except Exception: pass
        elif kind == "shell" and not e.get("done"): self.status("acting", "shell")
        elif kind == "say": self.add("say", str(e.get("text", "")).strip()[:600])
        elif kind == "think" and str(e.get("text") or "").strip():
            # The runtime's account of the model's reasoning, written for people: "**Title**\n\nbody", maybe several
            text = str(e["text"]).strip(); title = (re.findall(r"\*\*(.+?)\*\*", text) or [""])[0]
            body = re.sub(r"\s+", " ", re.sub(r"\*\*(.+?)\*\*", "", text)).strip()
            self.add("think", body[:700] or title, title=title); self.status("thinking", title)
        elif kind == "shell":
            if "deploy.py request" in str(e.get("command", "")): self.add("mark", "asked for a deploy of its own Java changes")
            self.status("thinking")
        elif kind == "tool_end":
            tool, content = e.get("tool", ""), e.get("content") if isinstance(e.get("content"), list) else []; stats["calls"] += 1
            try: result = json.loads(content[0]["text"])
            except (KeyError, IndexError, TypeError, ValueError): result = {}
            failed = bool(e.get("failed")); stats["failed"] += failed
            goal = result if tool == "mb_goal" else result.get("goal") if tool == "mb_status" and isinstance(result, dict) else None
            if isinstance(goal, dict) and "subgoal" in goal and not failed:
                if goal.get("chapter") and self.live["goal"].get("chapter") not in (None, goal["chapter"]): self.add("mark", "new chapter: " + str(goal["chapter"]))
                self.live["goal"] = {k: goal.get(k) or "" for k in ("chapter", "quest", "subgoal", "serves")}
                if goal.get("progress") is not None: self.live["goal"]["progress"] = goal["progress"]  # its own estimate of the quest, 0 to 100
            if tool == "mb_run": stats["scripts"] += 1
            if tool == "mb_quest_claim" and not failed and isinstance(result, dict) and (result.get("claimed") or result.get("accepted")):
                stats["claims"] += 1; self.add("mark", "quest claimed: " + str((result.get("quest") or {}).get("name") or self.live["goal"].get("quest") or "?"))
            else:
                why = result.get("error") if isinstance(result, dict) and isinstance(result.get("error"), dict) else {}
                why = "" if not failed else ": bad arguments" if why.get("code") == "bad_request" else ": " + str(why.get("code") or "failed").replace("_", " ")
                text = line(tool, e.get("args"), result)
                self.add("fail" if failed or isinstance(result, dict) and result.get("stopped") else "tool", text and text + why, tool=tool)
            if not failed:
                try: self.look(tool, e.get("args"), result, content)
                except Exception: pass  # a pop-up is decoration: a shape it did not expect shows nothing
            self.status("thinking")

    def look(self, tool, args, result, content):
        """Record a look tool's result as a pop-up for the stream: pops.json, the last few, and an image file for the map or a screenshot."""
        args, result = (args if isinstance(args, dict) else {}), (result if isinstance(result, dict) else {})
        me = result.get("pos") if tool == "mb_obs" and args.get("method") == "player" else result.get("you") if tool == "mb_view" else result.get("player") if tool == "mb_map" else None
        if isinstance(me, list) and len(me) == 3: self.me = me
        made = POP[tool](args, result, self.me) if tool in POP else None
        if not made: return
        try: pops = json.loads((self.folder / "pops.json").read_text(encoding="utf-8"))
        except (OSError, ValueError): pops = []
        seq = (pops[-1]["seq"] + 1) if pops else 1
        record = {"seq": seq, "ts": time.time(), "kind": made[0], "tool": tool, "data": made[1]}
        image = next((c for c in content if isinstance(c, dict) and c.get("type") == "image" and c.get("data")), None)
        if made[0] in ("map", "shot"):
            if not image: return
            (self.folder / f"pop-{seq}.png").write_bytes(base64.b64decode(image["data"])); record["image"] = f"pop-{seq}.png"
        pops = (pops + [record])[-POPS:]
        tmp = self.folder / "pops.tmp"; tmp.write_text(json.dumps(pops), encoding="utf-8")
        try: tmp.replace(self.folder / "pops.json")
        except OSError: pass
        for old in set(self.folder.glob("pop-*.png")) - {self.folder / p["image"] for p in pops if p.get("image")}: old.unlink(missing_ok=True)
