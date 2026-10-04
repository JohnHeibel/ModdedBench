"""Bridging a gap from a block lower than a whole one, in the game: a chest's top is 0.875, so a body leaning over its
edge is in the cell it wants to fill and no block goes in there. The walker has to keep back out of that cell.

    bash harness/smoke/mbtest.sh harness/smoke/bridge_course.py

Cases: from the chest's middle beside a wall to place against; the same from the chest's lip, where a body that came in
fast is left (the storey build froze there for good, sneaking and clicking); and with no wall, where only the chest is
left to place against and the walker has to find another way. A case passes when the walk arrives unhurt.

The arena's own wall is glass at plot z 8: nothing of a case may stand there.
"""
from __future__ import annotations

import argparse
import json
import sys
import time

import builder_shell as bs
from movement_course import BridgeError
from mbtools_gtnh import work

STONE, CHEST = "minecraft:stonebrick", "minecraft:chest"
X, UP = 4, 12                             # the chest stands on a column too high to drop from; the far column's top is level with a whole block there
NEAR, FAR = 6, -1                         # z of the chest and of the far column: the gap between is too wide to jump (z 8 is the arena's glass)
LIP = 0.16                                # how far past the chest's edge the body's middle is while its box still rests on it


class Bridges(bs.Shells):
    def trial(self, lip: bool, wall: bool) -> dict:
        origin = self.arena(bare=True, top=200)
        for y in range(UP): self.set_block((X, y, NEAR), STONE)
        self.set_block((X, UP, NEAR), CHEST)
        for y in range(UP + 1): self.set_block((X, y, FAR), STONE)
        if wall:
            for z in range(FAR + 1, NEAR):
                for y in range(UP, UP + 3): self.set_block((X - 1, y, z), STONE)      # too high to walk along
        at = lambda rel: [origin[0] + rel[0], bs.FLOOR + rel[1], origin[2] + rel[2]]
        start = at((X + .5, UP + .875, NEAR - LIP if lip else NEAR + .5))
        goal = [int(v) for v in at((X, UP + 1, FAR))]
        self.stand(start)
        before = self.c.call("obs.player")
        lo, hi = (X - 2, 0, FAR - 1), (X + 2, UP + 4, NEAR + 1)
        was = self.region(lo, hi)
        try:
            receipt = work.mb_process("goal", goal={"type": "block", "pos": goal}, duration_ticks=900, allow_break=False, allow_place=True, timeout_s=120)
        except BridgeError as e:
            err = (e.reply or {}).get("error") or {}
            receipt = {**(err.get("receipt") if isinstance(err.get("receipt"), dict) else {}), "state": "failed", "errorMsg": e.msg}
        time.sleep(.3)
        try: self.c.call("act.stop")
        except BridgeError: pass
        after = self.c.call("obs.player")
        placed = sorted(p for p in self.region(lo, hi) if p not in was)
        row = dict(state=receipt.get("state") or "succeeded", reason=receipt.get("reason") or receipt.get("errorMsg"), ticks=receipt.get("ticks"),
                   started=[round(v, 2) for v in before["pos"]], final=[round(v, 2) for v in after["pos"]], hurt=round(before["health"] - after["health"], 2), placed=placed)
        row["ok"] = row["state"] == "succeeded" and row["hurt"] == 0 and max(abs(a - g - .5) for a, g in zip(after["pos"][::2], goal[::2])) < 1 and after["pos"][1] == goal[1]
        return row


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--keep", action="store_true")
    ap.add_argument("--server-host", default="127.0.0.1")
    args = ap.parse_args(); args.seed, args.trials, args.case = 1, 1, None
    t = Bridges(args); t.setup()
    rows = []
    try:
        for name, lip, wall in (("bridge_from_chest", False, True), ("bridge_from_chest_lip", True, True), ("bridge_from_chest_no_wall", False, False)):
            row = t.trial(lip, wall); rows.append(row)
            print(f"{name} {'PASS' if row['ok'] else 'FAIL'} {json.dumps({k: v for k, v in row.items() if k != 'ok'})}", flush=True)
    finally:
        t.teardown()
    print(f"{sum(r['ok'] for r in rows)}/{len(rows)} passed", flush=True)
    sys.exit(0 if all(r["ok"] for r in rows) else 1)


if __name__ == "__main__":
    main()
