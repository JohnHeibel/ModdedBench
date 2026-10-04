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
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import builder_shell as bs  # noqa: E402

AIR, BRICK, DIRT = "minecraft:air", "minecraft:stonebrick", "minecraft:dirt"
PLANKS = "minecraft:planks"   # what a task has the player take out by hand: the pack's stone wants the pack's tools
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
# The pack's machines are one block: the item's damage (and the tile's mID) is the kind. Tools carry their stats in NBT.
GT, TC = "gregtech:gt.blockmachines", "TConstruct:"
WRENCH = ("gregtech:gt.metatool.01", 1, 16, '{GT.ToolStats:{PrimaryMaterial:"Iron",SecondaryMaterial:"Iron",MaxDamage:25600L,Damage:0L}}')
MACHINES = (201, 241, 261, 271, 301)
WALL = {(x, y, 1): (BRICK, 0) for x in range(7) for y in range(3)}
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
        """From slot 0 up: (id, count), then meta, then an NBT string. The fixture's good tools stay (12..14); its worn-out pickaxe in slot 2 goes."""
        free = [s for s in range(36) if s not in (12, 13, 14)]; items = list(items)
        if len(items) < 3: items += [("minecraft:torch", 16)] * (3 - len(items))
        for slot, item in zip(free, items):
            self.s.call(bs.FIX + ".set_stack", slot=slot, id=item[0], meta=item[2] if len(item) > 2 else 0, count=item[1], **({"nbt": item[3]} if len(item) > 3 else {}))

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

    @staticmethod
    def cell(key): return tuple(int(v) for v in key.split(","))

    def nbt(self, cell) -> dict:
        """A tile's own saved state, from the server."""
        return self.s.call(bs.FIX + ".inspect_block", x=ORIGIN[0] + cell[0], y=ORIGIN[1] + cell[1], z=ORIGIN[2] + cell[2]).get("tile") or {}

    def tiles(self, after: dict, block: str) -> dict:
        return {self.cell(k): self.nbt(self.cell(k)) for k, v in after.items() if v.startswith(block + ":")}

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
        "hoppers": "Inside the stone brick house the middle furnace is fed by a hopper standing on it. Give the furnace on each side of it "
                   "one too: a hopper on top of the furnace, pointing down into it. Then add one more hopper against the east side of "
                   "the eastern new hopper, pointing into that hopper. Everything that is there must stay as it is.",
        "smeltline": "Inside the stone brick house, along the south wall east of the doorway: two furnaces side by side facing into the "
                     "room, a hopper on top of each feeding it, and a chest in the corner next to them. Do not block the doorway and "
                     "do not disturb anything that is there.",
        "repair": "The hopper line on the roof of the stone brick house should carry items from the chest at its west end east along the "
                  "roof and down into the chest above the middle furnace inside. One hopper in that line points the wrong way. Fix it. "
                  "Everything else must stay as it is, and nothing of yours may be left behind.",
        "autofurnace": "Make the furnace in front of you run by itself: what is put in the chest above it gets smelted, fuel comes from the "
                       "chest above and to the west of it, and the result ends up in the chest on the floor to its east. The furnace and the three "
                       "chests stay where they are.",
        "forge": "Each of the three furnaces in front of you has its own chest above it. Make every chest feed its furnace, and make all "
                 "three furnaces empty into the chest set in the floor at the west end of the row. Nothing that stands there may be "
                 "moved, and the floor must be whole when you finish.",
        "machines": "Set the five machines you carry side by side on the floor against this side of the stone brick wall in front of you, "
                    "every front facing away from the wall. Then set each machine's output side with the wrench you carry, counting "
                    "from the west end of the row: the first and the second output upward, the third downward, the fourth toward the "
                    "third, the fifth away from the fourth.",
        "smeltery": "Build a working smeltery on the open floor in front of you from the parts you carry. It must hold the bucket of lava "
                    "you carry and have two pour points: one over a casting basin, one over a casting table.",
        "ebf_fixed": "Build an Electric Blast Furnace on the open floor in front of you from the parts you carry, so that its controller reports "
                     "a formed structure with no maintenance problems: you carry the duct tape for its maintenance hatch. The controller "
                     "faces south, toward where you stand now. Power comes in from the west: both energy hatches in the west side, each "
                     "with a run of three of the cables you carry leading west from it along the floor. Output leaves to the east: the "
                     "output bus in the east side, with the chest you carry standing against it.",
        "wiring": "Set the four machines you carry: the battery buffer at {buffer} with its front (the side it gives power from) facing "
                  "east, the macerator at {macerator}, the electric furnace at {furnace} on top of the stone brick pillar, the alloy "
                  "smelter at {smelter}. Then run the cable you carry from the battery buffer's front so that the macerator and the "
                  "electric furnace are both connected to it. The alloy smelter must stay unconnected, and the stone brick wall stays "
                  "as it is.",
        "ebf": "Build an Electric Blast Furnace on the open floor in front of you from the parts you carry, so that its controller reports "
               "a formed structure. The controller faces south, toward where you stand now. Power comes in from the west: both energy "
               "hatches in the west side, each with a run of three of the cables you carry leading west from it along the floor. Output "
               "leaves to the east: the output bus in the east side, with the chest you carry standing against it.",
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


    def hoppers_setup(self): return self.begin(drawn(B1, B1_LEGEND), [("minecraft:hopper", 4), (DIRT, 16)], (4, 1, 4))

    def hoppers_grade(self, state, after):
        want = {(4, 2, 2): 0, (6, 2, 2): 0, (7, 2, 2): 4}                                         # down, down, west: a hopper's meta is where it points
        got = {c: after.get(self.key(c), AIR) for c in want}
        made = all(v.startswith("minecraft:hopper:") and int(v.rsplit(":", 1)[1]) & 7 == want[c] for c, v in got.items())
        stray = self.changed(state["before"], after, lambda c, v: c in want and v.startswith("minecraft:hopper:"))
        return {"hoppersAsAsked": made, "found": {self.key(c): v for c, v in got.items()}, "nothingElseChanged": not stray, "stray": stray[:12],
                "canaryUntripped": self.key(CANARY) not in after}

    LINE = {(5, 1, 6): ("minecraft:furnace", 2), (6, 1, 6): ("minecraft:furnace", 2), (5, 2, 6): ("minecraft:hopper", 0), (6, 2, 6): ("minecraft:hopper", 0),
            (7, 1, 6): ("minecraft:chest", None)}

    def hoppered(self, after, want) -> tuple[bool, dict]:
        """Whether each cell holds a hopper pointing as asked (a hopper's meta is where it points), and what stands there."""
        got = {c: after.get(self.key(c), AIR) for c in want}
        return all(v.startswith("minecraft:hopper:") and int(v.rsplit(":", 1)[1]) & 7 == want[c] for c, v in got.items()), {self.key(c): v for c, v in got.items()}

    def smeltline_setup(self):
        return self.begin(drawn(B1, B1_LEGEND), [("minecraft:furnace", 2), ("minecraft:hopper", 2), ("minecraft:chest", 1)], (4, 1, 5))

    def smeltline_grade(self, state, after):
        got = {c: after.get(self.key(c), AIR) for c in self.LINE}
        made = all(v.startswith(block + ":") and (meta is None or int(v.rsplit(":", 1)[1]) & 7 == meta) for (c, (block, meta)), v in zip(self.LINE.items(), got.values()))
        stray = self.changed(state["before"], after, lambda c, v: c in self.LINE)
        return {"lineAsAsked": made, "found": {self.key(c): v for c, v in got.items()}, "doorOpen": all(self.key(c) not in after for c in ((4, 1, 7), (4, 2, 7), (4, 1, 6), (4, 2, 6))),
                "nothingElseChanged": not stray, "stray": stray[:12], "canaryUntripped": self.key(CANARY) not in after}

    def repair_setup(self):
        scene = drawn(B1, B1_LEGEND); scene[(3, 5, 2)] = ("minecraft:hopper", 4)   # the middle of the roof line, turned round
        return self.begin(scene, [("minecraft:hopper", 1), (DIRT, 32)], (4, 0, 9))

    def repair_grade(self, state, after):
        made, found = self.hoppered(after, {(2, 5, 2): 5, (3, 5, 2): 5, (4, 5, 2): 5, (5, 5, 2): 0, (5, 4, 2): 0})
        stray = self.changed(state["before"], after, lambda c, v: c == (3, 5, 2))
        return {"lineAsAsked": made, "found": found, "nothingElseChanged": not stray, "stray": stray[:12], "canaryUntripped": self.key(CANARY) not in after}

    AUTO = {(6, 2, 4): 0, (5, 1, 4): 5, (6, 0, 4): 5}   # in from above; fuel from under its chest into the furnace's side; out from below into the chest east

    def autofurnace_setup(self):
        scene = {(6, 0, 4): (PLANKS, 0), (6, 1, 4): ("minecraft:furnace", 3), (6, 3, 4): ("minecraft:chest", 3), (5, 2, 4): ("minecraft:chest", 3), (7, 0, 4): ("minecraft:chest", 3)}
        return self.begin(scene, [("minecraft:hopper", 4), (DIRT, 16)], (6, 0, 7))

    def autofurnace_grade(self, state, after):
        made, found = self.hoppered(after, self.AUTO)
        stray = self.changed(state["before"], after, lambda c, v: c in self.AUTO)
        return {"hoppersAsAsked": made, "found": found, "nothingElseChanged": not stray, "stray": stray[:12]}

    def forge_setup(self):
        scene = {(x, 0, z): (PLANKS, 0) for x in range(2, 11) for z in range(2, 7)}
        scene.update({(3, 0, 4): ("minecraft:chest", 3), (4, 3, 4): ("minecraft:chest", 3), (5, 3, 4): ("minecraft:trapped_chest", 3), (6, 3, 4): ("minecraft:chest", 3)})
        scene.update({(x, 1, 4): ("minecraft:furnace", 3) for x in (4, 5, 6)})
        return self.begin(scene, [("minecraft:hopper", 6), (DIRT, 16)], (5, 1, 6))

    def forge_grade(self, state, after):
        want = {**{(x, 2, 4): 0 for x in (4, 5, 6)}, **{(x, 0, 4): 4 for x in (4, 5, 6)}}
        made, found = self.hoppered(after, want)
        floor = [self.key((x, 0, z)) for x in range(2, 11) for z in range(2, 7) if (x, 0, z) not in want and (x, 0, z) != (3, 0, 4)]
        holes = [k for k in floor if after.get(k) != f"{PLANKS}:0"]
        stray = self.changed(state["before"], after, lambda c, v: c in want)
        return {"hoppersAsAsked": made, "found": found, "floorWhole": not holes, "holes": holes[:8], "nothingElseChanged": not stray, "stray": stray[:12]}

    def machines_setup(self): return self.begin(WALL, [(GT, 1, k) for k in MACHINES] + [WRENCH, (DIRT, 32)], (3, 0, 6))

    def machines_grade(self, state, after):
        tiles = self.tiles(after, GT); row = sorted(tiles)
        placed = sorted(t.get("mID") for t in tiles.values()) == sorted(MACHINES)
        inRow = placed and all(c[1] == 0 and c[2] == 2 for c in row) and [c[0] for c in row] == list(range(row[0][0], row[0][0] + 5))
        fronts, sides = [tiles[c].get("mMainFacing") for c in row], [tiles[c].get("mFacing") for c in row]
        stray = self.changed(state["before"], after, lambda c, v: v.startswith(GT + ":"))
        return {"placed": placed, "inRowAtWall": bool(inRow), "fronts": fronts, "frontsAway": fronts == [3] * 5, "outputs": sides,
                "outputsAsAsked": sides == [1, 1, 0, 4, 5], "nothingElseChanged": not stray, "stray": stray[:12]}

    def smeltery_setup(self):
        tc = lambda name, count, meta: (TC + name, count, meta)
        return self.begin({}, [tc("Smeltery", 40, 2), tc("Smeltery", 1, 0), tc("Smeltery", 2, 1), tc("LavaTank", 1, 0), tc("SearedBlock", 2, 1),
                               tc("SearedBlock", 1, 2), tc("SearedBlock", 1, 0), ("minecraft:lava_bucket", 1), ("minecraft:cobblestone", 32), (DIRT, 32)], (3, 0, 6))

    def smeltery_grade(self, state, after):
        at = lambda c: after.get(self.key(c), AIR)
        controllers = [t for c, t in self.tiles(after, TC + "Smeltery").items() if at(c) == TC + "Smeltery:0"]
        tanks = list(self.tiles(after, TC + "LavaTank").values())
        under = {}
        for k, v in after.items():
            if v != TC + "SearedBlock:1": continue                                              # a faucet
            x, y, z = self.cell(k)
            if any(at(n) == TC + "Smeltery:1" for n in ((x + 1, y, z), (x - 1, y, z), (x, y, z + 1), (x, y, z - 1))): under[(x, y, z)] = at((x, y - 1, z))
        leftovers = sorted(k for k, v in after.items() if v.startswith(DIRT))
        return {"validStructure": any(t.get("ValidStructure") in (1, True) for t in controllers), "lavaInTank": any((t.get("amount") or 0) > 0 for t in tanks),
                "faucetsOnDrains": {self.key(c): v for c, v in under.items()}, "basinPour": TC + "SearedBlock:2" in under.values(),
                "tablePour": TC + "SearedBlock:0" in under.values(), "noScaffoldLeft": not leftovers}

    def ebf_setup(self):
        kit = [("gregtech:gt.blockcasings", 14, 11), ("gregtech:gt.blockcasings5", 16, 0)] + [(GT, n, k) for k, n in ((1000, 1), (41, 2), (90, 1), (71, 1), (81, 1), (91, 1), (1246, 6))]
        return self.begin({}, kit + [("minecraft:chest", 1), WRENCH, (DIRT, 64)], (5, 0, 8))

    def ebf_grade(self, state, after):
        time.sleep(10)                                                                           # a controller looks at its structure every few seconds
        tiles = self.tiles(after, GT); kind = lambda k: {c: t for c, t in tiles.items() if t.get("mID") == k}
        at = lambda c: after.get(self.key(c), AIR)
        controller, lines = kind(1000), ""
        if len(controller) == 1: lines = " ".join(self.c.call("obs.waila", pos=self.world(next(iter(controller)))).get("lines") or [])
        cable = lambda c: tiles.get(c, {}).get("mID") == 1246 and bool((tiles[c].get("mConnections") or 0) & 32)   # joined to what stands east of it
        powered = {self.key(c): [cable((c[0] - n, c[1], c[2])) for n in (1, 2, 3)] for c in kind(41)}
        hatchesWest = len(kind(41)) == 2 and all(t.get("mFacing") == 4 for t in kind(41).values())
        bus = kind(81)
        leftovers = sorted(k for k, v in after.items() if v.startswith(DIRT))
        return {"formed": "Efficiency" in lines and "INCOMPLETE STRUCTURE" not in lines, "controllerSouth": [t.get("mFacing") for t in controller.values()] == [3],
                "energyHatchesWest": hatchesWest, "cableRuns": powered, "cablesJoined": hatchesWest and all(all(v) for v in powered.values()),
                "outputBusEast": [t.get("mFacing") for t in bus.values()] == [5], "chestAtBus": any(at((c[0] + 1, c[1], c[2])).startswith("minecraft:chest") for c in bus),
                "noScaffoldLeft": not leftovers, "switchedOffFor": [(t.get("shutDownReason") or {}).get("key") for t in controller.values()]}


    MAINTENANCE = ("mWrench", "mScrewdriver", "mSoftHammer", "mHardHammer", "mSolderingTool", "mCrowbar")

    def ebf_fixed_setup(self):
        kit = [("gregtech:gt.blockcasings", 14, 11), ("gregtech:gt.blockcasings5", 16, 0)] + [(GT, n, k) for k, n in ((1000, 1), (41, 2), (90, 1), (71, 1), (81, 1), (91, 1), (1246, 6))]
        return self.begin({}, kit + [("minecraft:chest", 1), ("gregtech:gt.metaitem.01", 1, 32764), WRENCH, (DIRT, 64)], (5, 0, 8))

    def ebf_fixed_grade(self, state, after):
        grade = self.ebf_grade(state, after)
        controller = [t for t in self.tiles(after, GT).values() if t.get("mID") == 1000]
        # The six states a controller saves, each under the name the pack gives it: the mallet's has had two.
        flags = {k: next((t[n] for n in (k, k.replace("Hammer", "Mallet")) if n in t), None) if k == "mSoftHammer" else t.get(k) for t in controller for k in self.MAINTENANCE}
        grade.update(maintenance=flags, maintained=bool(flags) and all(v in (1, True) for v in flags.values()),
                     hatch=[{k: t.get(k) for k in ("mInventory", "Inventory", *self.MAINTENANCE) if k in t} for t in self.tiles(after, GT).values() if t.get("mID") == 90])
        return grade

    WIRE = {"buffer": (1, 0, 4), "macerator": (9, 0, 1), "furnace": (9, 2, 7), "smelter": (5, 0, 3)}
    KINDS = {"buffer": 171, "macerator": 301, "furnace": 261, "smelter": 201}
    SIDES = ((0, -1, 0), (0, 1, 0), (0, 0, -1), (0, 0, 1), (-1, 0, 0), (1, 0, 0))   # bit n of a cable's mConnections: down, up, north, south, west, east

    def wiring_setup(self):
        scene = {(7, y, z): (BRICK, 0) for y in range(3) for z in range(2, 7) if (y, z) != (0, 4)}
        scene.update({(9, 0, 7): (BRICK, 0), (9, 1, 7): (BRICK, 0)})
        state = self.begin(scene, [(GT, 1, k) for k in self.KINDS.values()] + [(GT, 16, 1246), (WRENCH[0], 1, 26, WRENCH[3]), WRENCH, (DIRT, 16)], (3, 0, 6))
        state["prompt"] = Tasks.PROMPTS["wiring"].format(**{k: self.world(c) for k, c in self.WIRE.items()})
        return state

    def wiring_grade(self, state, after):
        tiles = self.tiles(after, GT)
        cables = {c: t.get("mConnections") or 0 for c, t in tiles.items() if t.get("mID") == 1246}
        step = lambda c, n: (c[0] + self.SIDES[n][0], c[1] + self.SIDES[n][1], c[2] + self.SIDES[n][2])
        touches = lambda cell: {c for c, bits in cables.items() for n in range(6) if bits >> n & 1 and step(c, n) == cell}   # cables with a side open toward it
        b = self.WIRE["buffer"]; first = (b[0] + 1, b[1], b[2])
        seen, todo = set(), [first] if first in touches(b) else []
        while todo:
            c = todo.pop()
            if c in seen: continue
            seen.add(c)
            todo += [step(c, n) for n in range(6) if cables[c] >> n & 1 and cables.get(step(c, n), 0) >> (n ^ 1) & 1]   # joined when both open toward each other
        stray = self.changed(state["before"], after, lambda c, v: v.startswith(GT + ":"))
        return {"machinesPlaced": all(tiles.get(c, {}).get("mID") == self.KINDS[n] for n, c in self.WIRE.items()), "bufferFrontEast": tiles.get(b, {}).get("mFacing") == 5,
                "cables": len(cables), "withinSixteen": len(cables) <= 16, "fromBufferFront": bool(seen), "maceratorJoined": bool(seen & touches(self.WIRE["macerator"])),
                "furnaceJoined": bool(seen & touches(self.WIRE["furnace"])), "smelterUnconnected": not touches(self.WIRE["smelter"]),
                "connections": {self.key(c): v for c, v in sorted(cables.items())}, "nothingElseChanged": not stray, "stray": stray[:12],
                "noScaffoldLeft": not any(v.startswith(DIRT) for v in after.values())}


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
            state = getattr(t, args.task + "_setup")(); state.setdefault("prompt", Tasks.PROMPTS[args.task])
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
