# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Click cells and uses on the dummy world, through the model's own tools (mb_build, mb_work_resume): one call a build.

Each scenario makes a fresh work-process arena, sets its blocks and the loadout with the fixture setters, builds, and
reads the cells back from the server (dev.replay.status: block and meta). The receipt is checked against what the
server finds. Facings are recorded, and a scenario fails when clicks that should differ made the same thing.

  click_hopper     a hopper under a furnace, clicked against a chest's west face: it must feed the chest (meta 5)
  click_boxed      the same with every stance walled off: stops as no_vantage naming the cell, with and without
                   allow_break (the wall is beside tile entities, which access leaves alone); nothing placed or dug
  click_access     a face seen only through one dirt block, no tile entity near: refused without allow_break; with it
                   the dirt goes, the click is made and the same block is back
  click_look       four furnaces placed looking north, south, east, west and a dispenser placed looking down:
                   four different furnace facings
  click_chain      a drawing whose legend entry carries the click: three logs, each against the one before
  click_use        uses alone: an empty-hand click on a fence gate with expect changed; a wrong id stops it untouched
  click_gui        a use that opens a chest: the screen is closed, the job stops as gui_opened, and a resume does not
                   click again
  click_scaffold   a block on a platform four up: no_route_from_here without allow_place; with it the click is made
                   from a scaffold that is gone when the job ends
  click_multiblock the acceptance shape in one call and one drawing: a 3x3x4 body of plain cells, a controller and three
                   hatches clicked in with a look (one in the roof, looking down), two cables each against the one
                   before, a chest against a hatch. SHAPE holds stand-in blocks; put the pack's ids there to build the
                   real thing (a machine needs id and item: {id, meta}).

The pack's own blocks (PACK), each one call:
  click_machines   five machines in a row before a wall, every front away from it, then five wrench uses that set the
                   output sides (up, up, down, toward the third, away from the fourth): kinds, fronts and sides read
                   from the server's tiles
  click_smeltery   a smeltery with a controller, two drains facing in, a faucet on each, a basin and a table under the
                   faucets and a bucket of lava into the tank: the controller reports a valid structure
  click_ebf        an Electric Blast Furnace: 37 structure cells, six hatches and the controller each facing out, the
                   muffler in the roof facing up, two cable runs coming in from the west, a chest at the output bus, the
                   controller last: kinds and facings from the tiles, and the controller reports a formed structure

Needs the throwaway mbtest stack with the dev fixtures; run it from a container on the server's network:

    bash harness/smoke/mbtest.sh harness/smoke/faceclick_course.py [--case NAME ...] [--keep]

The scenarios are methods of Clicks, which a suite may inherit beside builder_shell.Shells (see SCENARIOS).
Evidence: .runtime/evidence/faceclick-course.json, and a table on stdout.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import builder_shell as bs  # noqa: E402
import movement_course as mc  # noqa: E402
from movement_course import BridgeError, work  # noqa: E402
from mbtools_gtnh import plan  # noqa: E402

OUT = mc.ROOT / ".runtime" / "evidence" / "faceclick-course.json"
SCENARIOS = ("click_hopper", "click_boxed", "click_access", "click_look", "click_chain", "click_use", "click_gui",
             "click_scaffold", "click_multiblock")
PACK = ("click_machines", "click_smeltery", "click_ebf")
AIR, STONE, DIRT, LOG, COBBLE = "minecraft:air", "minecraft:stone", "minecraft:dirt", "minecraft:log", "minecraft:cobblestone"
BOX = (7, 7, 7)   # the plot: builder_shell's corner of the arena, clear floor, nothing with a tile entity within reach
# The acceptance shape's blocks. Stand-ins that take their facing from the click, as machines do.
SHAPE = {"casing": {"id": "minecraft:stonebrick"}, "coil": {"id": "minecraft:planks"}, "controller": {"id": "minecraft:furnace"},
         "hatch": {"id": "minecraft:dispenser"}, "cable": {"id": LOG}, "chest": {"id": "minecraft:chest"}}


GT, TOOL = "gregtech:gt.blockmachines", "gregtech:gt.metatool.01"   # every machine is one block: the item's damage is the kind
TOOL_NBT = '{GT.ToolStats:{PrimaryMaterial:"Iron",SecondaryMaterial:"Iron",MaxDamage:25600L,Damage:0L}}'
WRENCH = {"id": TOOL, "meta": 16}


def part(kind: int) -> dict:
    """A plan cell for one kind of machine: the block, the item that makes it, and the item it must pick as."""
    return {"id": GT, "item": {"id": GT, "meta": kind}, "verify": {"pickedItem": {"id": GT, "meta": kind}}}


def find(value, key):
    """The first value under this key anywhere in a nested result, or None."""
    if isinstance(value, dict):
        if key in value: return value[key]
        value = list(value.values())
    for v in value if isinstance(value, list) else ():
        found = find(v, key)
        if found is not None: return found
    return None


def call(fn, **kw) -> dict:
    """A tool's receipt, whether it came back as a result or inside the error."""
    try: return fn(**kw)
    except BridgeError as e:
        err = (e.reply or {}).get("error") or {}
        return {**(err.get("receipt") if isinstance(err.get("receipt"), dict) else {}), "errorCode": e.code, "errorMsg": e.msg}


def brief(r: dict) -> str:
    return f"state={r.get('state')} stopped={ {k: v for k, v in (r.get('stopped') or {}).items() if k != 'click'} } clicks={r.get('clicks')} ticks={r.get('ticks')} {r.get('errorMsg') or ''}"


class Clicks:
    """Scenarios; needs s, c, arena() and stand() of builder_shell.Shells. Positions are relative to the plot's corner."""

    def scene(self, blocks=(), stacks=(), start=(0, 0)) -> list[int]:
        """A fresh arena with these blocks {rel: id | (id, meta)} and this loadout [(id, count)], the player at start."""
        origin = self.arena()
        for rel, block in dict(blocks).items():
            name, meta = block if isinstance(block, tuple) else (block, 0)
            self.s.call(bs.FIX + ".set_block", x=bs.PLOT[0] + rel[0], y=bs.FLOOR + rel[1], z=bs.PLOT[1] + rel[2], id=name, meta=meta)
        for slot, (item, count, *more) in enumerate(stacks):   # (id, count), then meta, then an NBT string
            self.s.call(bs.FIX + ".set_stack", slot=slot, id=item, count=count, meta=more[0] if more else 0, **({"nbt": more[1]} if more[1:] else {}))
        self.stand([origin[0] + start[0] + .5, bs.FLOOR, origin[2] + start[1] + .5])
        return origin

    def seen(self, origin, rels=None) -> dict[tuple, tuple]:
        """{rel: (block, meta)} as the server has it; the whole plot when no cells are named."""
        rels = [tuple(r) for r in rels] if rels is not None else [(x, y, z) for x in range(BOX[0]) for y in range(BOX[1]) for z in range(BOX[2])]
        found = {}
        for i in range(0, len(rels), 64):
            cells = self.s.call("dev.replay.status", cells=[[origin[j] + r[j] for j in range(3)] for r in rels[i:i + 64]])["cells"]
            for r, c in zip(rels[i:i + 64], cells): found[r] = (c["block"], c["meta"])
        return found

    def tile(self, rel) -> dict:
        """The tile's own saved state at a plot cell, from the server."""
        return self.s.call(bs.FIX + ".inspect_block", x=bs.PLOT[0] + rel[0], y=bs.FLOOR + rel[1], z=bs.PLOT[1] + rel[2]).get("tile") or {}

    def strays(self, origin, expected: dict) -> list:
        """Cells of the plot that hold anything but what is expected there (air where nothing is)."""
        return sorted((r, got) for r, got in self.seen(origin).items() if got[0] != expected.get(r, AIR))

    HOPPER = {(3, 0, 3): ("minecraft:chest", 4), (2, 1, 3): ("minecraft:furnace", 2), (2, 0, 2): COBBLE, (2, 0, 4): COBBLE}
    HOPPER_CELL = {"pos": [2, 0, 3], "id": "minecraft:hopper", "click": {"face": "west"},
                   "expect": [{"method": "obs.block", "path": "meta", "equals": 5}]}   # a hopper's meta is the side it feeds

    def click_hopper(self):
        origin = self.scene(self.HOPPER, [("minecraft:hopper", 4)], start=(0, 5))
        seen = call(work.mb_build_preview, cells=[self.HOPPER_CELL], origin=origin)
        r = call(work.mb_build, cells=[self.HOPPER_CELL], origin=origin, timeout_ticks=3000)
        got = self.seen(origin, [(2, 0, 3)])[(2, 0, 3)]
        ok = r.get("state") == "succeeded" and got == ("minecraft:hopper", 5) and (r.get("clicks") or {}).get("verified") == 1
        return {"passed": ok, "receipt": r, "preview": seen, "server": got, "why": f"{brief(r)} server={got} preview={(seen or {}).get('clicks')}"}

    def click_boxed(self):
        walls = {**self.HOPPER, (1, 0, 3): COBBLE, (1, 1, 3): COBBLE}; rows = []; ok = True
        for allow in (False, True):
            origin = self.scene(walls, [("minecraft:hopper", 4)], start=(0, 5)); t = time.monotonic()
            r = call(work.mb_build, cells=[self.HOPPER_CELL], origin=origin, allow_break=allow, timeout_ticks=3000)
            stop = r.get("stopped") or {}; left = self.strays(origin, {k: v[0] if isinstance(v, tuple) else v for k, v in walls.items()})
            ok = (ok and r.get("state") in ("paused", "failed") and stop.get("reason") in ("no_vantage", "look_unreachable")
                  and stop.get("pos") == [origin[0] + 2, origin[1], origin[2] + 3] and bool((stop.get("click") or {}).get("blocking")) and not left)
            rows.append({"allowBreak": allow, "receipt": r, "changed": left, "wallS": round(time.monotonic() - t, 1)})
        return {"passed": ok, "rows": rows, "why": " | ".join(f"allow_break={x['allowBreak']}: {brief(x['receipt'])} changed={len(x['changed'])} wall={x['wallS']}s" for x in rows)}

    def click_access(self):
        walls = {(3, 0, 3): STONE, (2, 1, 3): STONE, (2, 0, 2): STONE, (2, 0, 4): STONE, (1, 0, 3): DIRT}
        cell = {"pos": [2, 0, 3], "id": LOG, "click": {"face": "west"}, "expect": [{"method": "obs.block", "path": "meta", "equals": 4}]}
        rows = []
        for allow in (False, True):
            origin = self.scene(walls, [(LOG, 4), (DIRT, 8)], start=(0, 5))
            r = call(work.mb_build, cells=[cell], origin=origin, allow_break=allow, timeout_ticks=3000)
            rows.append({"receipt": r, "strays": self.strays(origin, {**walls, (2, 0, 3): LOG} if allow else walls), "log": self.seen(origin, [(2, 0, 3)])[(2, 0, 3)]})
        refused, made = rows
        ok = ((refused["receipt"].get("stopped") or {}).get("reason") == "no_vantage" and not refused["strays"]
              and made["receipt"].get("state") == "succeeded" and made["log"] == (LOG, 4) and not made["strays"] and not made["receipt"].get("accessLeft"))
        return {"passed": ok, "rows": rows, "why": f"without: {brief(refused['receipt'])} changed={len(refused['strays'])} | with: {brief(made['receipt'])} "
                                                   f"log={made['log']} strays={made['strays'][:3]} accessLeft={made['receipt'].get('accessLeft')}"}

    def click_look(self):
        spots = {"north": [1, 0, 1], "south": [3, 0, 1], "east": [5, 0, 1], "west": [3, 0, 4]}
        cells = [{"pos": p, "id": "minecraft:furnace", "click": {"look": {"toward": way}}} for way, p in spots.items()]
        cells.append({"pos": [5, 0, 4], "id": "minecraft:dispenser", "click": {"look": {"toward": "down"}}})
        origin = self.scene({}, [("minecraft:furnace", 4), ("minecraft:dispenser", 1)], start=(0, 6))
        r = call(work.mb_build, cells=cells, origin=origin, timeout_ticks=6000)
        got = self.seen(origin, [c["pos"] for c in cells]); facing = {way: got[tuple(p)] for way, p in spots.items()}
        ok = (r.get("state") == "succeeded" and all(b == "minecraft:furnace" for b, _ in facing.values()) and len({m for _, m in facing.values()}) == 4
              and got[(5, 0, 4)][0] == "minecraft:dispenser")
        return {"passed": ok, "receipt": r, "facing": facing, "down": got[(5, 0, 4)], "why": f"{brief(r)} furnace meta by look={ {w: m for w, (_, m) in facing.items()} } down={got[(5, 0, 4)]}"}

    def click_chain(self):
        origin = self.scene({}, [(STONE, 4), (LOG, 8)], start=(0, 5))
        drawing = {"origin": [origin[0] + 1, origin[1], origin[2] + 3], "stages": ["S", "p"], "layers": [["Sppp"]],
                   "legend": {"S": {"id": STONE}, "p": {"id": LOG, "click": {"face": "east"}}}}
        r = call(work.mb_build, drawing=drawing, timeout_ticks=6000)
        got = self.seen(origin, [(x, 0, 3) for x in (2, 3, 4)])
        ok = r.get("state") == "succeeded" and all(v == (LOG, 4) for v in got.values()) and (r.get("clicks") or {}).get("done") == 3
        return {"passed": ok, "receipt": r, "why": f"{brief(r)} logs={sorted(got.values())}"}

    def click_use(self):
        gate = (3, 0, 3); origin = self.scene({gate: "minecraft:fence_gate"}, [], start=(0, 5))
        before = self.seen(origin, [gate])[gate]
        use = {"pos": list(gate), "item": {"empty": True}, "id": "minecraft:fence_gate", "expect": [{"method": "obs.block", "path": "meta", "changed": True}]}
        r = call(work.mb_build, uses=[use], origin=origin, timeout_ticks=3000)
        after = self.seen(origin, [gate])[gate]
        r2 = call(work.mb_build, uses=[{**use, "id": "minecraft:fence"}], origin=origin, timeout_ticks=3000)
        still = self.seen(origin, [gate])[gate]
        ok = (r.get("state") == "succeeded" and after != before and (r.get("clicks") or {}).get("verified") == 1
              and (r2.get("stopped") or {}).get("reason") == "use_target_changed" and still == after)
        return {"passed": ok, "receipt": r, "wrongId": r2, "why": f"{brief(r)} gate {before}->{after} | wrong id: {brief(r2)} gate={still}"}

    def click_gui(self):
        chest = (3, 0, 3); origin = self.scene({chest: ("minecraft:chest", 2)}, [], start=(0, 5))
        r = call(work.mb_build, uses=[{"pos": list(chest), "item": {"empty": True}}], origin=origin, timeout_ticks=3000)
        screen = self.c.call("obs.gui").get("class")
        r2 = call(work.mb_work_resume, job_id=r["jobId"]) if r.get("jobId") else {}
        again = self.c.call("obs.gui").get("class")
        ok = ((r.get("stopped") or {}).get("reason") == "gui_opened" and screen is None and (r.get("clicks") or {}).get("done") == 1
              and r2.get("state") == "succeeded" and again is None)
        return {"passed": ok, "receipt": r, "resumed": r2, "why": f"{brief(r)} screenAfter={screen} | resumed: {brief(r2)} screenAfter={again}"}

    def click_scaffold(self):
        deck = {(x, 3, z): STONE for x in (2, 3, 4) for z in (2, 3, 4)}
        cell = {"pos": [3, 4, 3], "id": LOG, "click": {"face": "up"}, "expect": [{"method": "obs.block", "path": "meta", "equals": 0}]}
        rows = []
        for allow in (False, True):
            origin = self.scene(deck, [(LOG, 4), (DIRT, 64)], start=(0, 0))
            r = call(work.mb_build, cells=[cell], origin=origin, allow_place=allow, timeout_ticks=6000)
            rows.append({"receipt": r, "strays": self.strays(origin, {**deck, (3, 4, 3): LOG} if allow else deck)})
        refused, made = rows
        ok = ((refused["receipt"].get("stopped") or {}).get("reason") == "no_route_from_here" and not refused["strays"]
              and made["receipt"].get("state") == "succeeded" and not made["strays"] and not made["receipt"].get("scaffoldLeft"))
        return {"passed": ok, "rows": rows, "why": f"without: {brief(refused['receipt'])} changed={len(refused['strays'])} | with: {brief(made['receipt'])} "
                                                   f"strays={made['strays'][:3]} scaffoldLeft={made['receipt'].get('scaffoldLeft')}"}

    def click_multiblock(self):
        look = lambda way: {"click": {"look": {"toward": way}}}
        legend = {"#": SHAPE["casing"], "o": SHAPE["coil"], "C": {**SHAPE["controller"], **look("south")},
                  "W": {**SHAPE["hatch"], **look("east")}, "E": {**SHAPE["hatch"], **look("west")}, "T": {**SHAPE["hatch"], **look("down")},
                  "w": {**SHAPE["cable"], "click": {"face": "east"}}, "K": {**SHAPE["chest"], "click": {"face": "west"}}}
        ring = ["..ooo..", "..o.o..", "..ooo.."]
        layers = [["..#C#..", ".KW#Eww", "..###.."], ring, ring, ["..###..", "..#T#..", "..###.."]]
        origin = self.scene({}, [(DIRT, 64)] + [(SHAPE[k].get("item", SHAPE[k])["id"], n) for k, n in
                                                (("casing", 32), ("coil", 32), ("controller", 2), ("hatch", 4), ("cable", 4), ("chest", 2))], start=(3, 0))
        drawing = {"origin": [origin[0], origin[1], origin[2] + 2], "stages": ["#o", "CWET", "wK"], "legend": legend, "layers": layers}
        seen = call(work.mb_build_preview, drawing=drawing, allow_place=True)
        r = call(work.mb_build, drawing=drawing, allow_place=True)
        want = {(c["pos"][0], c["pos"][1], c["pos"][2] + 2): c["id"] for c in plan.from_drawing(drawing)[0]}
        strays = self.strays(origin, want); got = self.seen(origin, list(want))
        clicked = {char: [got[(x, y, z + 2)] for y, layer in enumerate(layers) for z, row in enumerate(layer) for x, c in enumerate(row) if c == char] for char in "CWETwK"}
        ok = r.get("state") == "succeeded" and not strays and (r.get("clicks") or {}).get("done") == 7 and not r.get("scaffoldLeft")
        return {"passed": ok, "receipt": r, "preview": seen, "clicked": clicked, "strays": strays[:16],
                "why": f"{brief(r)} strays={strays[:3]} made={clicked} scaffoldLeft={r.get('scaffoldLeft')} preview={(seen or {}).get('clicks')}"}


    def click_machines(self):
        kinds = (201, 241, 261, 271, 301)
        wall = {(x, y, 1): "minecraft:stonebrick" for x in range(7) for y in range(3)}
        origin = self.scene(wall, [(GT, 1, k) for k in kinds] + [(TOOL, 1, 16, TOOL_NBT)], start=(3, 6))
        at = [[1 + i, 0, 2] for i in range(5)]
        cells = [{"pos": p, **part(k), "click": {"look": {"toward": "north"}}, "expect": [{"method": "obs.tile", "path": "tile.mMainFacing", "equals": 3}]}
                 for p, k in zip(at, kinds)]
        # A wrench click sets the side by where on the face it lands: the middle is that face, an edge the side beyond
        # it, a corner the side behind.
        sides = ((1, "up", [.5, 1, .5]), (1, "up", [.5, 1, .5]), (0, "up", [.1, 1, .1]), (4, "up", [.1, 1, .5]), (5, "east", [1, .5, .5]))
        uses = [{"pos": p, "item": WRENCH, "click": {"face": face, "hit": hit}, "expect": [{"method": "obs.tile", "path": "tile.mFacing", "equals": want}]}
                for p, (want, face, hit) in zip(at, sides)]
        r = call(work.mb_build, cells=cells, uses=uses, origin=origin, timeout_ticks=6000)
        tiles = [self.tile(p) for p in at]; got = [(t.get("mID"), t.get("mMainFacing"), t.get("mFacing")) for t in tiles]
        want = [(k, 3, side) for k, (side, _, _) in zip(kinds, sides)]
        left = self.strays(origin, {**wall, **{tuple(p): GT for p in at}})
        ok = r.get("state") == "succeeded" and got == want and not left
        return {"passed": ok, "receipt": r, "tiles": tiles, "why": f"{brief(r)} (kind, front, output)={got} want={want} strays={left[:3]}"}

    def click_smeltery(self):
        tc = lambda name, meta: {"id": "TConstruct:" + name, "meta": meta}
        look = lambda way: {"click": {"look": {"toward": way}}}
        legend = {"#": tc("Smeltery", 2), "c": {"id": COBBLE}, "K": tc("LavaTank", 0), "B": tc("SearedBlock", 2), "T": tc("SearedBlock", 0),
                  "M": {"id": "TConstruct:Smeltery", "item": tc("Smeltery", 0), **look("east")},
                  "D": {"id": "TConstruct:Smeltery", "item": tc("Smeltery", 1), **look("west")},
                  "E": {"id": "TConstruct:Smeltery", "item": tc("Smeltery", 1), **look("north")},
                  "f": {"id": "TConstruct:SearedBlock", "item": tc("SearedBlock", 1), "click": {"face": "east"}},
                  "g": {"id": "TConstruct:SearedBlock", "item": tc("SearedBlock", 1), "click": {"face": "south"}}}
        layers = [[".ccc..", "c###c.", "c###cB", "c###c.", ".ccc..", "..T..."],
                  [".#K#..", "#...#.", "M...Df", "#...#.", ".#E#..", "..g..."]]
        kit = [(COBBLE, 16), ("TConstruct:Smeltery", 17, 2), ("TConstruct:Smeltery", 1, 0), ("TConstruct:Smeltery", 2, 1), ("TConstruct:LavaTank", 1, 0),
               ("TConstruct:SearedBlock", 2, 1), ("TConstruct:SearedBlock", 1, 2), ("TConstruct:SearedBlock", 1, 0), ("minecraft:lava_bucket", 1), (DIRT, 32)]
        origin = self.scene({}, kit, start=(3, 6))
        drawing = {"origin": [origin[0] + 1, origin[1], origin[2]], "stages": ["c#", "KMDE", "fgBT"], "legend": legend, "layers": layers}
        uses = [{"pos": [2, 1, 0], "item": {"id": "minecraft:lava_bucket"}, "click": {"face": "north"}, "stage": 2}]
        r = call(work.mb_build, drawing=drawing, uses=uses, allow_place=True, timeout_ticks=8000)
        controller, tank = self.tile((1, 1, 2)), self.tile((3, 1, 0))
        ok = r.get("state") == "succeeded" and controller.get("ValidStructure") in (1, True) and not r.get("scaffoldLeft")
        return {"passed": ok, "receipt": r, "controller": controller, "tank": tank,
                "why": f"{brief(r)} valid={controller.get('ValidStructure')} layers={controller.get('Layers')} tank={ {k: v for k, v in tank.items() if k not in ('x', 'y', 'z', 'id')} } scaffoldLeft={r.get('scaffoldLeft')}"}

    def click_ebf(self):
        casing, coil = {"id": "gregtech:gt.blockcasings", "meta": 11}, {"id": "gregtech:gt.blockcasings5", "meta": 0}
        at = (4, 0, 2); cells = []; facing = {}
        def add(pos, block, stage, **more): cells.append({"pos": [at[0] + pos[0], pos[1], at[2] + pos[2]], **block, "stage": stage, **more})
        look = lambda way, front: {"click": {"look": {"toward": way}}, "expect": [{"method": "obs.tile", "path": "tile.mFacing", "equals": front}]}
        # (kind, how the player faces as it goes in, the front that makes): a machine fronts whoever places it.
        low = {(0, 0, 0): (41, "east", 4), (0, 0, 1): (41, "east", 4), (1, 0, 0): (111, "south", 2), (2, 0, 1): (81, "west", 5),
               (2, 0, 2): (71, "north", 3), (1, 0, 2): (1000, "north", 3)}
        for x in range(3):
            for z in range(3):
                # The controller goes in last: one that finds no structure when placed switches itself off.
                if (x, 0, z) in low: kind, way, front = low[(x, 0, z)]; add((x, 0, z), part(kind), 3 if kind == 1000 else 1, **look(way, front)); facing[(x, 0, z)] = (kind, front)
                else: add((x, 0, z), casing, 0)
                if (x, z) != (1, 1):
                    for y in (1, 2): add((x, y, z), coil, 0)
                    add((x, 3, z), casing, 0)
        add((1, 3, 1), part(91), 1, click={"look": {"pitch": [65, 90]}}, expect=[{"method": "obs.tile", "path": "tile.mFacing", "equals": 1}]); facing[(1, 3, 1)] = (91, 1)   # a steep look down: it faces up
        add((3, 0, 1), {"id": "minecraft:chest"}, 1)
        cables = [(-n, 0, z) for z in (0, 1) for n in (1, 2, 3)]
        for c in cables: add(c, part(1246), 2, click={"face": "west"})   # each on the west face of what stands east of it
        kit = [(DIRT, 64), (casing["id"], 11, 11), (coil["id"], 16, 0), (GT, 1, 1000), (GT, 2, 41), (GT, 1, 111), (GT, 1, 71), (GT, 1, 81), (GT, 1, 91),
               (GT, 6, 1246), ("minecraft:chest", 1)]
        origin = self.scene({}, kit, start=(5, 6))
        seen = call(work.mb_build_preview, cells=cells, origin=origin, allow_place=True)
        r = call(work.mb_build, cells=cells, origin=origin, allow_place=True, timeout_ticks=12000)
        rel = lambda p: (at[0] + p[0], p[1], at[2] + p[2])
        tiles = {p: self.tile(rel(p)) for p in list(facing) + cables}
        got = {p: (tiles[p].get("mID"), tiles[p].get("mFacing")) for p in facing}
        joined = {p: tiles[p].get("mConnections") for p in cables}   # a bit a side: 16 west, 32 east
        want = {tuple(c["pos"]): c["id"] for c in cells}
        box = [(x, y, z) for x in range(0, 9) for y in range(0, 7) for z in range(0, 7)]
        left = sorted((p, b) for p, b in self.seen(origin, box).items() if b[0] != want.get(p, AIR))
        time.sleep(8)   # a structure check comes round within a few seconds
        controller = rel((1, 0, 2)); waila = self.c.call("obs.waila", pos=[origin[0] + controller[0], origin[1], origin[2] + controller[2]])
        lines = " ".join(waila.get("lines") or [])
        # Formed is the build's to answer for. A fresh one then switches itself off for want of maintenance (tools into
        # the hatch's own screen), which no block click does: reported, not required.
        formed, off = "Efficiency" in lines and "INCOMPLETE STRUCTURE" not in lines, (self.tile(controller).get("shutDownReason") or {}).get("key")
        wired = all(tiles[c].get("mID") == 1246 and (joined[c] or 0) & 32 and not (joined[c] or 0) & ~48 for c in cables)
        ok = r.get("state") == "succeeded" and got == facing and wired and not left and formed and off != "structure_incomplete" and not r.get("scaffoldLeft")
        return {"passed": ok, "receipt": r, "preview": seen, "tiles": {str(k): v for k, v in tiles.items()}, "waila": waila, "strays": left[:16],
                "why": f"{brief(r)} (kind, front)={got} cables={joined} formed={formed} switchedOffFor={off} strays={left[:3]} scaffoldLeft={r.get('scaffoldLeft')}"}


class Course(Clicks, bs.Shells):
    def run(self):
        names = self.args.case or list(SCENARIOS); rows = {}
        try:
            self.setup()
            for name in names:
                print(f"== {name}", flush=True); t = time.monotonic()
                try: row = getattr(self, name)()
                except Exception as e: row = {"passed": False, "why": f"error {type(e).__name__}: {e}"}
                row["wallS"] = round(time.monotonic() - t, 1); rows[name] = row
                print(f"{name:17} {'PASS' if row['passed'] else 'FAIL'} {row['wallS']:7.1f}s  {row['why']}", flush=True)
            self.evidence["ok"] = bool(rows) and all(r["passed"] for r in rows.values())
        finally:
            self.teardown(); self.evidence["cases"] = rows
            OUT.parent.mkdir(parents=True, exist_ok=True); OUT.write_text(json.dumps(self.evidence, indent=1, default=str))
            print(OUT, flush=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--case", action="append", choices=list(SCENARIOS + PACK), help="scenario name (repeatable); default all but the pack's")
    ap.add_argument("--keep", action="store_true", help="leave the arena and the journalled player in place")
    ap.add_argument("--server-host", default="127.0.0.1", help="the server address as the client sees it, to rejoin")
    args = ap.parse_args(); args.seed, args.trials = 1, 1
    course = Course(args); course.run()
    return 0 if course.evidence["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
