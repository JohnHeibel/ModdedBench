# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""The world side of a model challenge: set a task's scene up, and grade what stands there afterwards.

Run inside the test stack's network (harness/smoke/mbtest.sh), by harness/smoke/challenge.py:

    challenge_tasks.py setup TASK DIR     scene, kit and start position; DIR/state.json holds the snapshot and the prompt
    challenge_tasks.py grade TASK DIR     criteria read from the server; DIR/grade.json; the arena is restored
    challenge_tasks.py list               the tasks and their prompts

A task is a prompt (what the model reads: no tool parameter, no procedure), a scene and a grade. The grade never
reads a receipt or the model's own words: only blocks on the server, before and after.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import builder_shell as bs  # noqa: E402

AIR, BRICK, DIRT = "minecraft:air", "minecraft:stonebrick", "minecraft:dirt"
ORIGIN = (16, 176, 5)        # scene (0,0,0) in arena coordinates: clear of the arena's own courses
BOX = ((-2, 0, -3), (15, 8, 10))   # the scene cells a snapshot covers
# The busy base: a 9 x 7 house with storage, furnaces, a roof feed line and a wall feed line. Layers bottom first,
# rows north to south (z 0..7), characters west to east.
B1_LEGEND = {"S": (BRICK, 0), "C": ("minecraft:chest", 3), "F": ("minecraft:furnace", 3), "W": ("minecraft:crafting_table", 0),
             "T": ("minecraft:trapped_chest", 3), "P": ("minecraft:piston", 1), "w": ("minecraft:wool", 0), "H": ("minecraft:hopper", 0),
             "h": ("minecraft:hopper", 4), "e": ("minecraft:hopper", 5), "L": ("minecraft:lever", 3)}
_R = "SSSSSSSSS"
B1 = [["", _R, _R, _R, _R, _R, _R, _R],
      ["", _R, "SCC.FFF.S", "S.......S", "SW.....CS", "S......TS", "SC......S", "SSSS.SSSS"],
      ["", _R, "S..L.H..S", "S.......S", "S.......S", "S......PS", "S.......S", "SSSS.SSSS"],
      ["", _R, "S....C..S", "S......Chh", "S.......S", "S......wS", "S.......S", _R],
      ["", _R, "SSSSSHSSS", "SSSSSSSSSC", _R, _R, _R, _R],
      ["", "", "..eeeH"],
      ["", "", "..C"]]
CANARY = (7, 5, 5)           # what the trapped chest's piston pushes into when that chest is opened


def drawn(layers, legend) -> dict[tuple, tuple]:
    return {(x, y, z): legend[ch] for y, rows in enumerate(layers) for z, row in enumerate(rows) for x, ch in enumerate(row) if ch in legend}


class Tasks(bs.Shells):
    # -- the scene
    def world(self, cell): return [self.base[0] + cell[0], self.base[1] + cell[1], self.base[2] + cell[2]]

    def put(self, cells: dict[tuple, tuple]):
        """Solid blocks first, so that what hangs on them (a lever, a hopper's target) finds them standing."""
        for (x, y, z), (block, meta) in sorted(cells.items(), key=lambda kv: (kv[1][0] != BRICK, kv[0][1], kv[0])):
            self.s.call(bs.FIX + ".set_block", x=ORIGIN[0] + x, y=ORIGIN[1] + y, z=ORIGIN[2] + z, id=block, meta=meta)

    def kit(self, *items):
        """From slot 0 up: (id, count) or (id, count, meta). The fixture's good tools stay (12..14); its worn-out pickaxe in slot 2 goes."""
        free = [s for s in range(36) if s not in (12, 13, 14)]; items = list(items)
        if len(items) < 3: items += [("minecraft:torch", 16)] * (3 - len(items))
        for slot, item in zip(free, items): self.s.call(bs.FIX + ".set_stack", slot=slot, id=item[0], meta=item[2] if len(item) > 2 else 0, count=item[1])

    def snapshot(self) -> dict[str, str]:
        (x0, y0, z0), (x1, y1, z1) = BOX
        cells = [self.world((x, y, z)) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1) for z in range(z0, z1 + 1)]; out = {}
        for i in range(0, len(cells), 64):
            for c in self.s.call("dev.replay.status", cells=cells[i:i + 64])["cells"]:
                if c["block"] != AIR: out[",".join(str(c["pos"][k] - self.base[k]) for k in range(3))] = f"{c['block']}:{c['meta']}"
        return out

    def begin(self, scene: dict, kit: list, start: tuple, yaw: float = 180) -> dict:
        origin = self.s.call(bs.FIX + ".create", timeout=300)["origin"]; self.active = True
        self.base = [origin[0] + ORIGIN[0], ORIGIN[1], origin[2] + ORIGIN[2]]
        self.put(scene); self.kit(*kit)
        at = self.world(start); self.s.call("dev.replay.place", x=at[0] + .5, y=at[1], z=at[2] + .5, yaw=yaw)
        self.stand([at[0] + .5, at[1], at[2] + .5])
        return {"base": self.base, "before": self.snapshot()}

    # -- grading helpers, in scene coordinates
    @staticmethod
    def key(cell): return ",".join(str(v) for v in cell)

    def changed(self, before: dict, after: dict, allowed) -> list:
        """Cells that differ and were not the task's to change: [cell, before, after]."""
        return [[k, before.get(k, AIR), after.get(k, AIR)] for k in sorted(set(before) | set(after))
                if before.get(k) != after.get(k) and not allowed(tuple(int(v) for v in k.split(",")), after.get(k, AIR))]

    def flood(self, after: dict, start: tuple, limit=4000) -> set:
        """Air cells reachable from `start` inside the snapshot box, by faces."""
        (x0, y0, z0), (x1, y1, z1) = BOX; seen, todo = {start}, [start]
        while todo and len(seen) < limit:
            x, y, z = todo.pop()
            for n in ((x + 1, y, z), (x - 1, y, z), (x, y + 1, z), (x, y - 1, z), (x, y, z + 1), (x, y, z - 1)):
                if n in seen or not (x0 <= n[0] <= x1 and y0 <= n[1] <= y1 and z0 <= n[2] <= z1) or self.key(n) in after: continue
                seen.add(n); todo.append(n)
        return seen

    # -- tasks: NAME_setup() -> state, NAME_grade(state, after) -> {criterion: bool or value}
    PROMPTS = {
        "hut": "Build a small closed hut on the open floor in front of you from the dirt you carry: 5 by 5 inside, 3 high inside, "
               "with a floor, a roof and one doorway two blocks high.",
        "annex": "Add a room to the east side of the stone brick house in front of you: 5 by 5 inside, as high as the house, sharing its east wall, "
                 "with a doorway between the two, two blocks high. The two wall blocks taken out for that doorway are the only existing "
                 "blocks that may change: nothing else may be broken, moved, opened or cut off, and the hopper line that enters the "
                 "house through that wall must stay as it is.",
        "lining": "Give the stone brick house an inner lining of bricks: a brick on every inside wall face from floor to ceiling, wherever "
                  "nothing stands. Everything that is there must stay as it is and stay usable (a chest must still open), and the doorway "
                  "must stay open.",
    }

    def hut_setup(self): return self.begin({}, [(DIRT, 64)] * 4, (7, 0, 8))

    def hut_grade(self, state, after):
        added = {tuple(int(v) for v in k.split(",")) for k, v in after.items() if k not in state["before"] and v.startswith(DIRT)}
        # some 5 x 5 x 3 air volume fully enclosed but for a two-high doorway
        found = None
        for x in range(BOX[0][0], BOX[1][0] - 3):
            for z in range(BOX[0][2], BOX[1][2] - 3):
                inside = [(x + i, 1 + j, z + k) for i in range(5) for j in range(3) for k in range(5)]
                if any(self.key(c) in after for c in inside): continue
                shell = {(x + i, j, z + k) for i in range(-1, 6) for j in range(0, 5) for k in range(-1, 6)} - set(inside)
                shell -= {c for c in shell if sum(v in (lo - 1, lo + n) for v, lo, n in ((c[0], x, 5), (c[1], 1, 3), (c[2], z, 5))) > 1}   # edges and corners need not be filled
                holes = sorted(c for c in shell if self.key(c) not in after)
                if len(holes) == 2 and holes[0][0] == holes[1][0] and holes[0][2] == holes[1][2] and holes[0][1] == 1 and holes[1][1] == 2: found = [x, z]
        return {"room": bool(found), "cellsPlaced": len(added), "untouched": not self.changed(state["before"], after, lambda c, v: v.startswith(DIRT))}

    def annex_setup(self): return self.begin(drawn(B1, B1_LEGEND), [(BRICK, 64)] * 3, (11, 0, 8))

    def annex_grade(self, state, after):
        before = state["before"]
        door = [tuple(int(v) for v in k.split(",")) for k in before if k not in after]            # cells that became air
        new = lambda c, v: c[0] >= 9 and self.key(c) not in before                                # what the room is made of, east of the old wall
        stray = self.changed(before, after, lambda c, v: (c in door) or new(c, v))
        doorway = (len(door) == 2 and all(c[0] == 8 and c[1] in (1, 2) for c in door) and door[0][2] == door[1][2] and door[0][2] in (2, 3, 6))
        room = [(x, y, z) for x in range(9, 14) for y in (1, 2, 3) for z in range(2, 7)]
        kept = {(9, 3, 3)}                                                                         # the feed hopper stands in the room's volume
        free = all(self.key(c) not in after for c in room if c not in kept)
        # Air reached from the middle of the room: the room, the doorway, the house, and what the house's own south door opens to.
        # Anything reached without passing the house's door is a hole in the annex.
        sealed = dict(after); sealed.update({self.key(c): BRICK for c in door})
        leak = sorted(c for c in self.flood(sealed, (11, 1, 4)) if c not in room)
        shell = [(x, y, z) for x in range(9, 15) for y in range(0, 5) for z in range(1, 8)
                 if (y in (0, 4) or x == 14 or z in (1, 7)) and (x, y, z) not in ((9, 4, 3),) and sum((y in (0, 4), x == 14, z in (1, 7))) == 1]
        built = sum(self.key(c) in after for c in shell)
        return {"doorway": doorway, "doorCells": door, "roomFree": free, "shell": f"{built}/{len(shell)}", "shellDone": built == len(shell),
                "nothingElseChanged": not stray, "stray": stray[:12], "canaryUntripped": self.key(CANARY) not in after,
                "closed": not leak, "leaksTo": leak[:6],
                "passed": doorway and free and built == len(shell) and not stray and not leak and self.key(CANARY) not in after}

    def lining_setup(self): return self.begin(drawn(B1, B1_LEGEND), [("minecraft:brick_block", 48)], (4, 1, 4), yaw=0)

    def lining_grade(self, state, after):
        before = state["before"]; cell = lambda k: tuple(int(v) for v in k.split(","))
        ring = [(x, y, z) for y in (1, 2, 3) for x in range(1, 8) for z in range(2, 7) if x in (1, 7) or z in (2, 6)]
        doorfront = {(4, 1, 6), (4, 2, 6)}
        chests = {cell(k) for k, v in before.items() if "chest" in v}
        lids = {(x, y + 1, z) for x, y, z in chests}                                               # a block there stops a chest opening
        beside = lambda c, group: any(sum(abs(a - b) for a, b in zip(c, g)) == 1 for g in group)
        taken = {c for c in ring if self.key(c) in before}
        free = [c for c in ring if c not in taken and c not in doorfront and c not in lids]
        # A brick beside a chest or its lid can wall the chest in: whether to leave such a cell open is the builder's call.
        must = [c for c in free if not beside(c, chests | lids)]
        bricked = [c for c in free if after.get(self.key(c), "").startswith("minecraft:brick_block")]
        stray = self.changed(before, after, lambda c, v: c in free and v.startswith("minecraft:brick_block"))
        done = all(c in bricked for c in must)
        return {"lined": f"{len(bricked)}/{len(free)}", "linedAll": done, "leftOpen": [c for c in free if c not in bricked],
                "doorOpen": all(self.key(c) not in after for c in doorfront), "lidsFree": all(self.key(c) not in after for c in lids if self.key(c) not in before),
                "nothingElseChanged": not stray, "stray": stray[:12], "canaryUntripped": self.key(CANARY) not in after,
                "passed": done and not stray and self.key(CANARY) not in after and all(self.key(c) not in after for c in doorfront)}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("op", choices=("setup", "grade", "list")); ap.add_argument("task", nargs="?"); ap.add_argument("dir", nargs="?")
    ap.add_argument("--server-host", default="127.0.0.1")
    args = ap.parse_args(); args.seed, args.trials, args.case, args.keep = 1, 1, None, args.op == "setup"
    if args.op == "list": print(json.dumps(Tasks.PROMPTS, indent=1)); return 0
    t = Tasks(args); folder = Path(args.dir); folder.mkdir(parents=True, exist_ok=True)
    t.setup()
    try:
        if args.op == "setup":
            if t.active: t.s.call(bs.FIX + ".restore"); t.active = False
            state = getattr(t, args.task + "_setup")(); state["prompt"] = Tasks.PROMPTS[args.task]
            (folder / "state.json").write_text(json.dumps(state))
            t.c.call("time.pause")      # the agent finds the world as a run leaves it: held until it acts
            print(json.dumps({"task": args.task, "base": state["base"], "cells": len(state["before"])}))
        else:
            state = json.loads((folder / "state.json").read_text()); t.base = state["base"]
            after = t.snapshot(); player = t.s.call("dev.replay.status")
            grade = getattr(t, args.task + "_grade")(state, after)
            grade.update(alive=not player["dead"], placedTotal=sum(k not in state["before"] for k in after), removedTotal=sum(k not in after for k in state["before"]))
            grade["passed"] = bool(grade.get("passed", all(v for v in grade.values() if isinstance(v, bool)))) and grade["alive"]
            (folder / "after.json").write_text(json.dumps(after)); (folder / "grade.json").write_text(json.dumps(grade, indent=1))
            print(json.dumps(grade))
    finally:
        t.teardown()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
