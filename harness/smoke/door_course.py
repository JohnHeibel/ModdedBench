# SPDX-License-Identifier: MIT
# Copyright (c) 2026 ModdedBench contributors
"""A doorway one block up, in the game: the step up from the ground lands in the cell an open door stands in, and the
door's leaf takes a strip of that cell's side. A body that comes round a corner and jumps for the cell's middle hits
the leaf's edge; it has to jump for the middle of the room the leaf leaves.

    bash harness/smoke/mbtest.sh harness/smoke/door_course.py [--trace] [--case leaf_north_from_north]

Cases: the leaf on the north or the south side of its cell, come at straight on, from the north and from the south
along the wall; each goes in through the doorway and back out to where it started.
"""
from __future__ import annotations

import argparse
import json
import sys
import time

import builder_shell as bs
import movement_course as mc
from movement_course import BridgeError
from mbtools_gtnh import work

STONE, STEP, DOOR = "minecraft:stonebrick", "minecraft:cobblestone", "minecraft:wooden_door"
WALL, GAP = 6, 4                                         # the wall's x and the doorway's z
STARTS = {"straight": (3.5, GAP + .5), "from_north": (WALL - .5, 1.5), "from_south": (WALL - .5, 7.5)}
LEAVES = {"north": 8, "south": 9}                        # the upper half's meta: which side the open leaf lies on
CLEAN = 80                                               # ticks: three times what a clean pass takes


class Doors(bs.Shells):
    def trial(self, leaf: str, start: str) -> dict:
        origin = self.arena(bare=True)
        for z in range(0, 9):
            for y in range(4):
                if (y, z) not in ((1, GAP), (2, GAP)): self.set_block((WALL, y, z), STEP if (y, z) == (0, GAP) else STONE)
        for x in range(WALL + 1, WALL + 4):
            for z in range(GAP - 1, GAP + 2): self.set_block((x, 0, z), STONE)
        self.set_block((WALL, 1, GAP), DOOR, 4); self.set_block((WALL, 2, GAP), DOOR, LEAVES[leaf])   # open, its leaf along the cell's side
        self.wait(5)
        door = {p: c for p, c in self.region((WALL, 1, GAP), (WALL, 2, GAP)).items()}
        at = lambda rel: [origin[0] + rel[0], bs.FLOOR + rel[1], origin[2] + rel[2]]
        x, z = STARTS[start]
        self.stand(at((x, 0, z)))
        before = self.c.call("obs.player")
        legs = [self.leg([int(v) for v in at(cell)], at((0, 0, 0))) for cell in ((WALL + 2, 1, GAP), (int(x), 0, int(z)))]   # in, and out again
        after = self.c.call("obs.player")
        row = dict(legs=legs, final=[round(v - o, 2) for v, o in zip(after["pos"], at((0, 0, 0)))], hurt=round(before["health"] - after["health"], 2),
                   door={str(p[1]): c.get("meta") for p, c in door.items()})
        row["ok"] = len(door) == 2 and row["hurt"] == 0 and all(leg["state"] == "succeeded" and (leg["ticks"] or 999) <= CLEAN and leg["jumps"] <= 1 for leg in legs)
        return row

    def leg(self, goal: list[int], origin: list[float]) -> dict:
        sampler = mc.Sampler(hz=20); sampler.start()
        try:
            receipt = work.mb_process("goal", goal={"type": "block", "pos": goal}, duration_ticks=400, allow_break=False, allow_place=False, timeout_s=60)
        except BridgeError as e:
            err = (e.reply or {}).get("error") or {}
            receipt = {**(err.get("receipt") if isinstance(err.get("receipt"), dict) else {}), "state": "failed", "errorMsg": e.msg}
        time.sleep(.3); sampler.finish()
        try: self.c.call("act.stop")
        except BridgeError: pass
        rows = sampler.samples
        if self.args.trace:
            for r in rows: print("  ", r["tick"], [round(v - o, 3) for v, o in zip(r["pos"], origin)], "ground" if r.get("onGround") else "", flush=True)
        jumps = sum(1 for a, b in zip(rows, rows[1:]) if a.get("onGround") and not b.get("onGround") and b["pos"][1] > a["pos"][1])
        return dict(state=receipt.get("state") or "succeeded", reason=receipt.get("reason") or receipt.get("errorMsg"), ticks=receipt.get("ticks"), jumps=jumps)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--keep", action="store_true")
    ap.add_argument("--trace", action="store_true", help="print every sampled tick, plot-relative")
    ap.add_argument("--case")
    ap.add_argument("--server-host", default="127.0.0.1")
    args = ap.parse_args(); args.seed, args.trials = 1, 1
    t = Doors(args); t.setup()
    rows = []
    try:
        for leaf in LEAVES:
            for start in STARTS:
                name = f"leaf_{leaf}_{start}"
                if args.case and args.case != name: continue
                row = t.trial(leaf, start); rows.append(row)
                print(f"{name} {'PASS' if row['ok'] else 'FAIL'} {json.dumps({k: v for k, v in row.items() if k != 'ok'})}", flush=True)
    finally:
        t.teardown()
    print(f"{sum(r['ok'] for r in rows)}/{len(rows)} passed", flush=True)
    sys.exit(0 if rows and all(r["ok"] for r in rows) else 1)


if __name__ == "__main__":
    main()
