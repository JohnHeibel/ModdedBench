# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Closed shells built in one job on a flat floor, through the model's own tool (mb_build), with no scaffolding.

The shell of the run of 2026-10-04 (job 9d600db0): a 7x7 footprint, walls three high with a door gap one wide and two
high in the west wall, a full roof on the fourth layer, 119 cells of dirt, allow_break and replace_existing on,
allow_place off. Each case rebuilds the journalled work-process arena, hands the player two stacks of dirt and places
them at the case's start; afterwards every cell is read back from the server.

  door_inside    that shell, started from the middle of its floor; passes only if all 119 cells are dirt
  door_outside   that shell, started two cells west of the door; passes only if all 119 cells are dirt
  sealed_inside  the same without the door gap (121 cells), started inside; passes if all 121 are dirt, or if the job
                 ended paused or failed and its receipt counts (`left`) exactly the cells the server still finds wrong
                 and names one of them as the cell it stopped on (`stopped`)

Needs the throwaway mbtest stack with the dev fixtures; run it from a container on the server's network:

    bash harness/smoke/mbtest.sh harness/smoke/builder_shell.py [--case NAME ...] [--keep]

Evidence: .runtime/evidence/builder-shell.json, and a summary table on stdout.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import movement_course as mc  # noqa: E402  (Kernel, the server bridge, rejoin)
from movement_course import BridgeError, Course, work  # noqa: E402

OUT = mc.ROOT / ".runtime" / "evidence" / "builder-shell.json"
FIX = "dev.work_process_fixture"
BLOCK = "minecraft:dirt"
PLOT, FLOOR = (22, 7), 176      # arena-relative low corner: clear of the arena's own courses, open floor on every side
BARE = re.compile(r"[^,}\]]*")   # a number in text NBT: everything up to the end of its entry
# start: arena cell relative to the shell's low corner. honest: an unfinished build passes if its receipt says so exactly.
CASES: dict[str, dict] = {
    "door_inside": dict(door=True, start=(3, 3)),
    "door_outside": dict(door=True, start=(-2, 3)),
    "sealed_inside": dict(door=False, start=(3, 3), honest=True),
}


def shell(door: bool) -> list[dict]:
    """Walls on the ring of a 7x7 footprint, layers 0..2, and a full roof on layer 3; the door gap is west, two high."""
    walls = [(x, y, z) for y in range(3) for x in range(7) for z in range(7)
             if (x in (0, 6) or z in (0, 6)) and not (door and (x, z) == (0, 3) and y < 2)]
    roof = [(x, 3, z) for x in range(7) for z in range(7)]
    return [{"pos": list(p), "id": BLOCK, "meta": 0} for p in walls + roof]


def snbt(text: str):
    """A tile entity's text NBT (the snbt of region and inspect_block) as dicts and lists; a leaf stays the token as written."""
    i = 0

    def quoted() -> str:
        nonlocal i; j = i + 1
        while text[j] != '"': j += 2 if text[j] == "\\" else 1
        token, i = text[i:j + 1], j + 1
        return token

    def value():
        nonlocal i
        if text[i] == "{":
            out, i = {}, i + 1
            while text[i] != "}":
                if text[i] == '"': key = quoted()
                else: j = text.index(":", i); key, i = text[i:j], j
                i += 1; out[key] = value(); i += text[i] == ","
            i += 1; return out
        if text[i] == "[" and text[i + 1:i + 3] not in ("B;", "I;"):
            out, i = [], i + 1
            while text[i] != "]":
                i = text.index(":", i) + 1; out.append(value()); i += text[i] == ","
            i += 1; return out
        if text[i] == '"': return quoted()
        j = text.index("]", i) + 1 if text[i] == "[" else BARE.match(text, i).end()
        token, i = text[i:j], j
        return token
    return value()


def leaves(node, path: tuple = ()) -> dict[tuple, object]:
    """{path: token} for every leaf of parsed tile NBT; a path is the keys and list indices that lead to it."""
    if not node or not isinstance(node, (dict, list)): return {path: node}
    return {p: v for k, child in (node.items() if isinstance(node, dict) else enumerate(node)) for p, v in leaves(child, path + (k,)).items()}


def diff(before: dict, after: dict, allowed=(), ignore=()) -> list[dict]:
    """The cells that differ between two Shells.region pictures, outside the `allowed` positions. Tile NBT is compared
    leaf by leaf without the paths in `ignore` (Shells.volatile learns them); a cell that differs lists its changed paths."""
    keep, skip, out = {tuple(p) for p in allowed}, {tuple(p) for p in ignore}, []
    for pos in sorted(before.keys() | after.keys()):
        a, b = before.get(pos), after.get(pos)
        if pos in keep or a == b: continue
        la, lb = (leaves(snbt(c["snbt"])) if c and c.get("snbt") else {} for c in (a, b))
        paths = sorted((p for p in la.keys() | lb.keys() if p not in skip and la.get(p) != lb.get(p)), key=str)
        block = [c and [c["id"], c["meta"]] for c in (a, b)]
        if block[0] != block[1] or paths: out.append({"pos": list(pos), "before": block[0], "after": block[1], "paths": [list(p) for p in paths]})
    return out


class Shells(Course):
    def __init__(self, args):
        super().__init__(args)
        mc.mbtool.set_kernel_factory(lambda: self.c)   # the tool must act through the session that owns the clock
        self.evidence = {"ok": False, "started": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "cases": {}}
        self.active = False

    def setup(self):
        self.ensure_world()
        state = self.c.call("time.status")["state"]
        self.previous_clock = state.get("conditions")
        self.c.call("time.configure", healthDrop=False, healthBelow=-1, airBelow=-1, foodBelow=-1, burning=False,
                    threatWithin=-1, actionFailed=False, pauseOnDisconnect=False)
        if state.get("paused"): self.c.call("time.resume")
        try: self.c.call("act.stop")
        except BridgeError: pass
        try: self.s.call(FIX + ".status"); self.active = True     # a course left by an interrupted run
        except BridgeError: pass
        rows = self.c.call("nav.settings", operation="get")["settings"]
        self.evidence["settings"] = {r["name"]: r["value"] for r in rows if r.get("value") != r.get("default")}
        print("non-default settings:", self.evidence["settings"] or "none", flush=True)

    def teardown(self):
        for step in (lambda: self.c.call("act.stop"),
                     lambda: self.restore() if self.active and not self.args.keep else None,
                     lambda: self.c.call("time.configure", **{**(self.previous_clock or {}), "pauseOnDisconnect": False})):
            try: step()
            except Exception as e: self.evidence.setdefault("teardownErrors", []).append(str(e))

    def restore(self):
        self.active = False; failed = self.s.call(FIX + ".restore").get("worldCellsFailed")
        if failed: self.evidence.setdefault("worldCellsFailed", []).extend(failed); print("NOT PUT BACK:", failed, flush=True)

    def arena(self, **create) -> list[int]:
        """A fresh arena for every case: the last case's shell goes with the old one. create: width, depth, top, bare."""
        if self.active: self.restore()
        origin = self.s.call(FIX + ".create", timeout=300, **create)["origin"]; self.active = True
        for slot in (0, 1): self.s.call(FIX + ".set_stack", slot=slot, id=BLOCK, meta=0, count=64)
        return [origin[0] + PLOT[0], FLOOR, origin[2] + PLOT[1]]

    def stand(self, at: list[float]) -> list[float]:
        self.s.call("dev.replay.place", x=at[0], y=at[1], z=at[2], yaw=0)
        until = time.monotonic() + 15
        while time.monotonic() < until:
            p = self.c.call("obs.player")["pos"]
            if abs(p[0] - at[0]) < .05 and abs(p[2] - at[2]) < .05 and abs(p[1] - at[1]) < .6: break
            time.sleep(.1)
        else: raise RuntimeError(f"client never reached the start {at}")
        self.wait(20)   # inventory and chunk sync
        return self.c.call("obs.player")["pos"]

    def wait(self, ticks: int):
        tick = self.c.call_reply("obs.player").tick
        while self.c.call_reply("obs.player").tick < tick + ticks: time.sleep(.05)

    def set_block(self, rel, id: str, meta: int = 0, nbt: str | None = None):
        """A block at a plot-relative cell; nbt is its tile entity as text, the snbt that region and inspect_block return."""
        self.s.call(FIX + ".set_block", x=PLOT[0] + rel[0], y=FLOOR + rel[1], z=PLOT[1] + rel[2], id=id, meta=meta, **({"nbt": nbt} if nbt else {}))

    def region(self, lo, hi) -> dict[tuple, dict]:
        """{plot-relative pos: {id, meta, snbt?}} for every non-air cell of a plot-relative box, as the server has it."""
        off = (PLOT[0], FLOOR, PLOT[1])
        cells = self.s.call(FIX + ".region", min=[a + o for a, o in zip(lo, off)], max=[a + o for a, o in zip(hi, off)])["cells"]
        return {tuple(a - o for a, o in zip(c.pop("pos"), off)): c for c in cells}

    def volatile(self, lo, hi, ticks: int = 5) -> set[tuple]:
        """The tile NBT paths that change by themselves: the same box read twice, `ticks` apart. Hand it to diff as ignore."""
        before = self.region(lo, hi); self.wait(ticks)
        return {tuple(p) for d in diff(before, self.region(lo, hi)) for p in d["paths"]}

    def blocks(self, cells: list[list[int]]) -> dict[tuple, str]:
        found = {}
        for i in range(0, len(cells), 64):
            for c in self.s.call("dev.replay.status", cells=cells[i:i + 64])["cells"]: found[tuple(c["pos"])] = c["block"]
        return found

    def trial(self, name: str) -> dict:
        spec = CASES[name]; cells = shell(spec["door"]); origin = self.arena()
        world = [[origin[i] + c["pos"][i] for i in range(3)] for c in cells]
        gap = [[origin[0], FLOOR + y, origin[2] + 3] for y in range(2)] if spec["door"] else []
        row = {"origin": origin, "cells": len(cells)}
        row["startActual"] = self.stand([origin[0] + spec["start"][0] + .5, FLOOR, origin[2] + spec["start"][1] + .5])
        t = time.monotonic()
        try:   # the call of job 9d600db0, cell for cell
            receipt = work.mb_build(cells=cells, origin=origin, replace_existing=True, allow_break=True, allow_place=False,
                                    timeout_ticks=12000)
        except BridgeError as e:
            err = (e.reply or {}).get("error") or {}
            receipt = {**(err.get("receipt") if isinstance(err.get("receipt"), dict) else {}), "errorCode": e.code, "errorMsg": e.msg}
        found = self.blocks(world + gap); player = self.s.call("dev.replay.status")
        wrong = sorted(c for c in world if found.get(tuple(c)) != BLOCK)
        blocked = [c for c in gap if found.get(tuple(c)) != "minecraft:air"]
        left, stop = receipt.get("left") or {}, receipt.get("stopped") or {}
        row.update(wallS=round(time.monotonic() - t, 1), receipt=receipt, wrong=wrong, doorBlocked=blocked,
                   end={"pos": player["pos"], "health": player["health"], "dead": player["dead"]})
        built = receipt.get("state") == "succeeded" and not wrong
        told = (receipt.get("state") in ("paused", "failed") and bool(stop.get("reason")) and left.get("count") == len(wrong)
                and all(c in wrong for c in left.get("first") or []) and stop.get("pos") in wrong)
        row["outcome"] = "built" if built else "honest_receipt" if told else "unfinished"
        row["passed"] = not blocked and not player["dead"] and (built or bool(spec.get("honest")) and told)
        return row

    def run(self):
        names = self.args.case or list(CASES)
        try:
            self.setup()
            for name in names:
                print(f"== {name}", flush=True)
                try: row = self.trial(name)
                except Exception as e: row = {"passed": False, "outcome": "error", "error": f"{type(e).__name__}: {e}"}
                self.evidence["cases"][name] = row
                r = row.get("receipt") or {}
                print(f"{name:14} {'PASS' if row['passed'] else 'FAIL'} {row['outcome']:14} state={r.get('state')} reason={r.get('reason')} "
                      f"ticks={r.get('ticks')} placed={r.get('placed')} wrong={len(row.get('wrong') or [])}/{row.get('cells')} "
                      f"stopped={r.get('stopped')} cost={r.get('cost')} {row.get('error') or ''}", flush=True)
            self.evidence["ok"] = all(self.evidence["cases"][n]["passed"] for n in names)
        finally:
            self.teardown()
            OUT.parent.mkdir(parents=True, exist_ok=True); OUT.write_text(json.dumps(self.evidence, indent=2))
            print(OUT, flush=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--case", action="append", choices=list(CASES), help="case name (repeatable); default all")
    ap.add_argument("--keep", action="store_true", help="leave the arena, the last shell and the journalled player in place")
    ap.add_argument("--server-host", default="127.0.0.1", help="the server address as the client sees it, to rejoin")
    args = ap.parse_args()
    args.seed, args.trials = 1, 1
    course = Shells(args)
    course.run()
    return 0 if course.evidence["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
