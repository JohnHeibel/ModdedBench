# SPDX-License-Identifier: MIT
# Copyright (c) 2026 ModdedBench contributors
"""Builds that try to break the stepped build order, the check that a build is done, and the scaffold cleanup: one
mb_build call each, judged by the cells the server reads back. Every row says what a good builder does (passed), what
main 737490e did when the row was first run (predicted, asPredicted; first written for the local-order branch's order,
here fitted to main's) and why; a row that fails as predicted is a finding, not a broken suite. A row that ends built
with a plan cell wrong never passes.

  ceiling_lamp         a 7 x 7 lining under a stone ceiling with a glowstone lamp hung from its middle, one stage: every
                       lining cell waits for the lamp, which has nothing to go against (predicted no_stance on the lamp)
  ceiling_lamp_staged  the same with the lamp a stage later: built
  chest_on_machine     a chest on a furnace clicked in looking north, one stage: plain cells go before click cells, so the
                       chest is shown first with nothing under it (predicted no_stance on the chest, nothing placed)
  chest_on_machine_staged  the chest a stage later: built
  covered_dust         a run of redstone (its item is no block, so a click cell) between two walls, a cover over all
                       three rows, one stage: the cover goes in first and the dust under it is in view from nowhere
                       (predicted no_vantage on a dust cell inside the run)
  covered_dust_staged  the cover a stage later: built
  oven_floating        build_suite's oven with one floating cell of its stage far off: probes the half-stall lift of the
                       cell that would shut the player in (predicted: the walk towards the floating cell takes the player
                       out first and the job stops no_stance on it; if it stays in, it seals itself in)
  hut_then_outside     builder_shell's sealed 7 x 7 shell from inside, then a path outside it as stage 1 (main: the
                       shell is built and the player is out of it, and the path stops as no_route)
  sand_over_clear      a sand cell over a cell asked to be empty, a stone post beside it: the sand falls into the empty
                       cell. Main said built 9 ticks in, before it fell, with the cell empty; good is a stop on the cell
                       after a bounded number of clicks
  sand_over_air        a sand cell three up beside a stone post, nothing under it: main said built before the sand fell.
                       Good is the cell standing (on what fell under it: up to two sand outside the plan) or a stop on it
  room_from_middle     walls three high round 21 x 21 and a roof, started from the middle: the roof cells more than 4
                       from a wall come at once and the source keeps the 101 nearest (main: most of it built, then
                       no_route on a roof cell)
  tower_line           a row of 9 nine up between two stone posts, allow_place: it is reached from a scaffold pillar under
                       one of its cells, and the player ends on the row. Good is no pillar left; main and the pre-run branch
                       leave all 9 and say so (scaffoldLeft)
  roofed_box           a closed box 5 x 5, walls five high and a roof, from outside with allow_place: no scaffold left

The pack's (only when named, --only NAME): ids as faceclick_course uses them, and AE2's as AeTerminalFixture does.
  covered_cable        covered_dust with an AE2 glass cable (a cable bus placed by a part item) for the dust
  covered_cable_staged the cover a stage later: built
  machine_topped       chest_on_machine on a GregTech machine (kind 261)

Needs the throwaway mbtest stack with the dev fixtures; run it from a container on the server's network:

    bash harness/smoke/mbtest.sh harness/smoke/build_edges.py [--only NAME ...] [--skip NAME ...] [--keep]

Every scenario has its own fresh bare arena (64 x 48, top 190); positions are relative to builder_shell's plot corner.
The arena needs open ground round the player's spot: on the mbtest world stand it at 1470,100,1400 first.
Evidence: .runtime/evidence/build-edges.json (history: one entry per run with the commit), and a table on stdout.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_suite  # noqa: E402
import builder_shell as bs  # noqa: E402
import faceclick_course as fc  # noqa: E402
import movement_course as mc  # noqa: E402
from build_suite import call  # noqa: E402
from movement_course import work  # noqa: E402

OUT = mc.ROOT / ".runtime" / "evidence" / "build-edges.json"
ARENA = {"width": 64, "depth": 48, "top": 190, "bare": True}
AIR, DIRT, STONE, COBBLE = "minecraft:air", "minecraft:dirt", "minecraft:stone", "minecraft:cobblestone"
GLOW, CHEST, FURNACE, SAND = "minecraft:glowstone", "minecraft:chest", "minecraft:furnace", "minecraft:sand"
WIRE, REDSTONE = "minecraft:redstone_wire", "minecraft:redstone"
CABLE_BUS, PARTS = "appliedenergistics2:tile.BlockCableBus", "appliedenergistics2:item.ItemMultiPart"
NEAR, KEPT = 4, 101   # BuildSteps.NEAR; incorrectSize + 1, what the source's full rescan keeps
BUILT, BUILT_P = {"end": ("built",), "left": [], "inside": False}, {"end": ("built",), "left": 0}   # good, and predicted


def cell(pos, id: str, stage: int = 0, **more) -> dict:
    return {"pos": list(pos), "id": id, **({"stage": stage} if stage else {}), **more}


def scenario(cells, *, start, good=BUILT, predict, box, terrain=None, kit=((DIRT, 64), (DIRT, 64)), inside=(), timeout=3000, **options) -> dict:
    """One build: plan cells, the terrain set first {rel: id | (id, meta)}, the loadout from slot 0 [(id, count, meta?)],
    the start (x, y, z), mb_build's other options, the box read back for strays, the cells that are inside (endedInside).
    good and predict: {end: ("built" | stop reasons...), pos?: the cell the stop names, left?: cells (good) or how many
    (predict) are still wrong, strays?: how many cells outside the plan changed (at most, for good), inside?, note?}."""
    return {"cells": cells, "terrain": dict(terrain or {}), "kit": [list(k) for k in kit], "start": list(start), "good": good, "predict": predict,
            "box": [list(box[0]), list(box[1])], "inside": [list(p) for p in inside], "options": {"timeout_ticks": timeout, **options}}


def first_shown(cells: list[dict], stage: int = 0) -> list[list[int]]:
    """The plain cells of a stage that come at its start with nothing built (BuildSteps.waits): no cell of the stage lies
    lower in a column within NEAR of it. Plain cells only: a click cell is the stage's next step and is not asked."""
    mine = [c["pos"] for c in cells if c.get("stage", 0) == stage and not c.get("clear") and "click" not in c]
    lowest: dict[tuple, int] = {}
    for x, y, z in mine: lowest[(x, z)] = min(y, lowest.get((x, z), y))
    return [p for p in mine if not any(lowest.get((p[0] + dx, p[2] + dz), p[1]) < p[1] for dx in range(-NEAR, NEAR + 1) for dz in range(-NEAR, NEAR + 1))]


# -- the plans: pure, so the offline tests check them without a game
def ceiling_lamp(staged: bool = False) -> dict:
    ceiling = {(x, 4, z): STONE for x in range(-1, 8) for z in range(-1, 8)}
    cells = [cell((x, 3, z), DIRT) for x in range(7) for z in range(7)] + [cell((3, 2, 3), GLOW, 1 if staged else 0)]
    stop = {"end": ("no_stance",), "pos": [3, 2, 3], "left": 50, "note": "the lamp comes alone (BuildSteps.waits: all 49 lining cells are within 4 of its column, "
                                                                        "one lower); nothing beside it, so no face; the lining never comes"}
    return scenario(cells, terrain=ceiling, kit=[(DIRT, 64), (GLOW, 4)], start=(3, 0, -3), box=((-3, 0, -5), (9, 6, 9)),
                    predict=BUILT_P if staged else stop)


def chest_on_machine(staged: bool = False, machine: dict | None = None, kit=((FURNACE, 2),)) -> dict:
    cells = [{"pos": [2, 0, 3], **(machine or {"id": FURNACE}), "click": {"look": {"toward": "north"}}}, cell((2, 1, 3), CHEST, 1 if staged else 0)]
    stop = {"end": ("no_stance",), "pos": [2, 1, 3], "left": 2, "note": "a stage's plain cells are one step and its click cells the next: the chest is shown "
                                                                       "alone over an empty cell, its step never ends, the machine's never starts"}
    return scenario(cells, kit=[(DIRT, 64), *kit, (CHEST, 2)], start=(2, 0, -1), box=((-1, 0, -3), (6, 4, 7)), predict=BUILT_P if staged else stop)


def covered_run(staged: bool = False, run: dict | None = None, kit=((REDSTONE, 16),)) -> dict:
    """x 0..6: walls at z 2 and 4, the run at z 3 (cells placed by an item that is no block: click cells), a cover at y 1
    over all three rows. Its ends are open: the end cells may be clicked in from outside, the others only from above."""
    run = run or {"id": WIRE}
    cells = ([cell((x, 0, z), COBBLE) for x in range(7) for z in (2, 4)] + [{"pos": [x, 0, 3], **run} for x in range(7)]
             + [cell((x, 1, z), COBBLE, 1 if staged else 0) for x in range(7) for z in (2, 3, 4)])
    stop = {"end": ("no_vantage", "look_unreachable"), "note": "the cover waits only for the walls (the run is the next step), so it closes the run "
                                                               "over before any of it is in; a run cell between the ends is then in view from nowhere"}
    return scenario(cells, kit=[(COBBLE, 64), *kit], start=(3, 0, 7), box=((-3, 0, -1), (9, 3, 8)), predict=BUILT_P if staged else stop)


def oven_floating() -> dict:
    """build_suite's oven (its floor in, a stone ceiling one above its top, started inside) and a cell of its stage at
    (1, 4, 8): nothing beside it, and more than 4 columns from the oven, so it comes at once and never goes in."""
    terrain = {**{(x, 0, z): DIRT for x in range(3) for z in range(3)}, **{(x, 3, z): STONE for x in range(-2, 5) for z in range(-2, 5)}}
    oven = [cell((x, y, z), DIRT) for y in range(3) for x in range(3) for z in range(3) if (x, y, z) != (1, 1, 1)]
    good = {"end": ("no_stance",), "pos": [1, 4, 8], "left": [[1, 4, 8]], "inside": False}
    predict = {**good, "left": 1,
               "note": "likely: with only the floating cell shown, the walk towards it leaves through the last gap and the gap is filled from outside. "
                       "If the player stays in, half a stall lifts the cell that would shut it in (step(): shut=Set.of()), the gap goes in from inside "
                       "and it is sealed in, the cell over its head left: stalled or no_route, 2 left, endedInside"}
    return scenario(oven + [cell((1, 4, 8), DIRT)], terrain=terrain, start=(1, 1, 1), box=((-3, 0, -3), (5, 6, 10)), inside=[(1, 1, 1)], good=good, predict=predict)


def hut_then_outside() -> dict:
    shell = [cell(c["pos"], DIRT) for c in bs.shell(False)]
    path = [cell((x, 0, -2), DIRT, 1) for x in range(7)]
    predict = {"end": ("no_route", "stalled"), "left": 7, "inside": False,
               "note": "main builds the shell and leaves the player outside it, then stops on the first path cell as no_route"}
    return scenario(shell + path, start=(3, 0, 3), box=((-2, 0, -4), (8, 5, 8)), inside=[(x, 0, z) for x in range(1, 6) for z in range(1, 6)], predict=predict)


def sand_over_clear() -> dict:
    cells = [cell((2, 1, 3), SAND), {"pos": [2, 0, 3], "clear": True}]
    good = {"end": ("attempt_limit", "placement_rejected"), "pos": [2, 1, 3], "maxTicks": 2500}
    predict = {"end": ("built",), "left": 1, "note": "success is said the tick the source holds every cell done and the client shows the sand in; "
                                                     "the server drops it a few ticks later"}
    return scenario(cells, terrain={(3, 0, 3): STONE, (3, 1, 3): STONE}, kit=[(SAND, 64)], start=(2, 0, -2), box=((-1, 0, -3), (6, 4, 6)), good=good, predict=predict)


def sand_over_air() -> dict:
    good = {"end": ("built", "attempt_limit", "placement_rejected"), "strays": 2}
    predict = {"end": ("built",), "left": 1, "strays": 1, "note": "said built before the sand fell; it lies on the floor under the cell"}
    return scenario([cell((2, 2, 3), SAND)], terrain={(3, y, 3): STONE for y in range(3)}, kit=[(SAND, 64)], start=(2, 0, -2), box=((-1, 0, -3), (6, 4, 6)),
                    good=good, predict=predict)


def room_from_middle() -> dict:
    walls = [cell((x, y, z), DIRT) for x in range(21) for z in range(21) if x in (0, 20) or z in (0, 20) for y in range(3)]
    roof = [cell((x, 3, z), DIRT) for x in range(21) for z in range(21)]
    predict = {"end": ("no_route", "stalled", "timeout"),
               "note": "main builds most of it (574 of 681 on 2026-10-16) and stops on a roof cell; the 121 roof cells over nothing come at once"}
    return scenario(walls + roof, kit=[(DIRT, 64)] * 12, start=(10, 0, 10), box=((-2, 0, -2), (22, 5, 22)), timeout=6000, predict=predict)


def tower_line() -> dict:
    """x 0..8 at y 9, z 3, between stone posts at x -1 and 9: no cell is in reach from the floor, so the source pillars."""
    posts = {(x, y, 3): STONE for x in (-1, 9) for y in range(10)}
    good = {"end": ("built",), "left": [], "strays": 0}
    predict = {"end": ("built",), "strays": 9, "note": "the row is built from a pillar under one of its cells and the player ends on the row, 10 up: no stance sees "
                                                       "the pillar's top under the row, and no walk comes down 10 blocks, so the pillar stays (scaffoldLeft 9)"}
    return scenario([cell((x, 9, 3), DIRT) for x in range(9)], terrain=posts, kit=[(DIRT, 64), (DIRT, 64)], start=(4, 0, 0), box=((-2, 0, -1), (10, 11, 7)),
                    good=good, predict=predict, timeout=6000, allow_place=True)


def roofed_box() -> dict:
    walls = [cell((x, y, z), DIRT) for x in range(5) for z in range(5) if x in (0, 4) or z in (0, 4) for y in range(5)]
    roof = [cell((x, 5, z), DIRT) for x in range(5) for z in range(5)]
    good = {"end": ("built",), "left": [], "strays": 0, "inside": False}
    predict = {"end": ("built",), "note": "not run on main before this file: walls and roof are built from scaffold outside; none is left standing"}
    return scenario(walls + roof, kit=[(DIRT, 64)] * 3, start=(2, 0, -3), box=((-3, 0, -4), (7, 8, 7)),
                    inside=[(x, y, z) for x in range(1, 4) for z in range(1, 4) for y in range(4)], good=good, predict=predict, timeout=6000, allow_place=True)


def covered_cable(staged: bool = False) -> dict:
    return covered_run(staged, run={"id": CABLE_BUS, "item": {"id": PARTS, "meta": 0}}, kit=((PARTS, 16, 0),))


def machine_topped() -> dict:
    return chest_on_machine(machine=fc.part(261), kit=((fc.GT, 1, 261),))


SCENARIOS = {"ceiling_lamp": ceiling_lamp, "ceiling_lamp_staged": lambda: ceiling_lamp(True),
             "chest_on_machine": chest_on_machine, "chest_on_machine_staged": lambda: chest_on_machine(True),
             "covered_dust": covered_run, "covered_dust_staged": lambda: covered_run(True),
             "oven_floating": oven_floating, "hut_then_outside": hut_then_outside,
             "sand_over_clear": sand_over_clear, "sand_over_air": sand_over_air, "room_from_middle": room_from_middle,
             "tower_line": tower_line, "roofed_box": roofed_box}
PACK = {"covered_cable": covered_cable, "covered_cable_staged": lambda: covered_cable(True), "machine_topped": machine_topped}


def ended(r: dict) -> str:
    """How the job ended: built when it succeeded, else the reason it stopped on."""
    return "built" if r.get("state") == "succeeded" else (r.get("stopped") or {}).get("reason") or r.get("errorCode") or str(r.get("state"))


def matches(want: dict, row: dict, exact: bool) -> bool:
    """Whether a row is what `want` says. exact (good): left lists the cells, strays is a ceiling, and built means no
    plan cell is wrong; else they are counts."""
    left, strays = want.get("left"), want.get("strays", 0 if exact else None)
    return (row["end"] in want["end"] and not (exact and row["end"] == "built" and row["wrong"]) and ("pos" not in want or row["stoppedAt"] == want["pos"])
            and (left is None or (sorted(row["wrong"]) == sorted(left) if exact else len(row["wrong"]) == left))
            and (strays is None or (len(row["strays"]) <= strays if exact else len(row["strays"]) == strays))
            and ("inside" not in want or row["endedInside"] == want["inside"]) and (not want.get("maxTicks") or row["ticks"] <= want["maxTicks"]))


class Edges(build_suite.Suite):
    def scene(self, spec: dict) -> list[int]:
        origin = self.arena(**ARENA)
        for rel, block in spec["terrain"].items():
            name, meta = block if isinstance(block, tuple) else (block, 0); self.set_block(rel, name, meta)
        for slot, (item, count, *meta) in enumerate(spec["kit"]): self.s.call(bs.FIX + ".set_stack", slot=slot, id=item, meta=meta[0] if meta else 0, count=count)
        x, y, z = spec["start"]; self.stand([origin[0] + x + .5, bs.FLOOR + y, origin[2] + z + .5])
        return origin

    def found(self, box) -> dict[tuple, str]:
        """{plot-relative pos: id} of every block in the box, as the server has it (air left out)."""
        return {p: c["id"] for p, c in self.region(*box).items()}

    def feet(self, origin) -> list[int]:
        return [int(v // 1) - o for v, o in zip(self.c.call("obs.player")["pos"], origin)]

    def edge(self, name: str) -> dict:
        spec = (SCENARIOS | PACK)[name](); origin = self.scene(spec)
        r, walked = self.tracked(lambda: call(work.mb_build, cells=spec["cells"], origin=origin, **spec["options"]))
        plan = {tuple(c["pos"]): AIR if c.get("clear") else c["id"] for c in spec["cells"]}
        want = {**{p: b if isinstance(b, str) else b[0] for p, b in spec["terrain"].items()}, **plan}
        found = self.found(spec["box"]); feet = self.feet(origin)
        lo, hi = spec["box"]; box = [(x, y, z) for x in range(lo[0], hi[0] + 1) for y in range(lo[1], hi[1] + 1) for z in range(lo[2], hi[2] + 1)]
        stop = r.get("stopped") or {}
        row = {"end": ended(r), "stoppedAt": [a - o for a, o in zip(stop["pos"], origin)] if stop.get("pos") else None,
               "wrong": sorted(list(p) for p, id in plan.items() if found.get(p, AIR) != id),
               "strays": sorted([list(p), found.get(p, AIR)] for p in box if p not in plan and found.get(p, AIR) != want.get(p, AIR)),
               "endedInside": feet in spec["inside"], "feet": feet, "ticks": int(r.get("ticks") or 0), "walked": walked,
               "placed": r.get("placed"), "removed": r.get("removed"), "step": r.get("step"), "clicks": r.get("clicks"), "cells": len(plan),
               "scaffoldLeft": r.get("scaffoldLeft"), "closingCut": r.get("closingCut"), "receipt": r}
        row["passed"], row["asPredicted"] = matches(spec["good"], row, True), matches(spec["predict"], row, False)
        row["predicted"] = {k: v for k, v in spec["predict"].items()}
        row["why"] = (f"end={row['end']} at={row['stoppedAt']} wrong={len(row['wrong'])}/{len(plan)} strays={len(row['strays'])} inside={row['endedInside']} "
                      f"ticks={row['ticks']} placed={row['placed']} removed={row['removed']} walked={walked.get('blocks')}"
                      f"{' scaffoldLeft=' + str((row['scaffoldLeft'] or {}).get('count')) if row['scaffoldLeft'] else ''}"
                      f"{' closingCut=' + str(row['closingCut']) if row['closingCut'] else ''} | predicted {spec['predict']['end']}"
                      f"{' at ' + str(spec['predict']['pos']) if 'pos' in spec['predict'] else ''}: {'as predicted' if row['asPredicted'] else 'NOT as predicted'}")
        return row

    def run(self):
        every = SCENARIOS | PACK
        names = [n for n in (self.args.only or list(SCENARIOS)) if n not in (self.args.skip or [])]
        unknown = [n for n in names if n not in every]
        if unknown: raise SystemExit(f"unknown scenario(s) {unknown}; known: {list(every)}")
        try: past = json.loads(OUT.read_text())
        except (OSError, ValueError): past = {}
        self.history = past if isinstance(past.get("runs"), list) else {"runs": []}
        rows = {}
        try:
            self.setup()
            for name in names:
                print(f"== {name}", flush=True); t = time.monotonic()
                try: row = self.edge(name)
                except Exception as e: row = {"passed": False, "asPredicted": False, "why": f"error {type(e).__name__}: {e}"}
                row["wallS"] = round(time.monotonic() - t, 1); rows[name] = row; self.costed(name, row)
                print(f"{name:24} {'PASS' if row['passed'] else 'FAIL'} {row['wallS']:7.1f}s  {row['why']}", flush=True)
        finally:
            self.teardown()
            self.history["runs"].append({"at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "commit": self.args.commit,
                                         "rows": {n: {k: r.get(k) for k in ("passed", "asPredicted", "why", "wallS")} for n, r in rows.items()}})
            self.history["last"] = rows
            OUT.parent.mkdir(parents=True, exist_ok=True); OUT.write_text(json.dumps(self.history, indent=1, default=str))
            print(f"\n{sum(bool(r['passed']) for r in rows.values())}/{len(rows)} built as a good builder would; "
                  f"{sum(bool(r.get('asPredicted')) for r in rows.values())}/{len(rows)} as predicted at {self.args.commit}", flush=True)
            self.evidence["ok"] = bool(rows) and all(r.get("asPredicted") or r["passed"] for r in rows.values())


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--only", action="append", help="scenario name (repeatable); the pack's run only when named")
    ap.add_argument("--skip", action="append")
    ap.add_argument("--keep", action="store_true", help="leave the arena and the journalled player in place")
    ap.add_argument("--server-host", default="127.0.0.1")
    mc.tick_cost.argument(ap); args = ap.parse_args(); args.seed, args.trials, args.case = 1, 1, None
    args.commit = os.environ.get("MB_COMMIT") or "unknown"
    suite = Edges(args); suite.run()
    return 0 if suite.evidence["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
