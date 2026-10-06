# SPDX-License-Identifier: MIT
# Copyright (c) 2026 ModdedBench contributors
"""Ladders: the walker goes up one, stops on one and comes down one, on each side of a block and through a roof.

A stone brick block three wide and four high stands in a fresh arena with one ladder on one side of it. Each side is a
case: goto the top of the block, back to the start, then to the middle of the ladder. `roof_hole` is a small room with a
ladder inside that leaves through a one-cell hole in its roof: up onto the roof and back out of the door. `roof_run` is
a shut room under a long roof, entered only by the hole at the roof's far end: the player runs the roof's length at the
wall the ladder hangs on, and has to come down the ladder, not rest on the top of its box (3/16 of the cell in this pack).

A case passes when every goto ends succeeded with the player in the goal cell.

    bash harness/smoke/mbtest.sh harness/smoke/ladder_course.py [--case NAME ...]
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import builder_shell as bs  # noqa: E402
from movement_course import BridgeError  # noqa: E402

BRICK, LADDER = "minecraft:stonebrick", "minecraft:ladder"
OUT = Path(__file__).resolve().parents[2] / ".runtime" / "evidence" / "ladder-course.json"
# side: the ladder's column (x, z), its meta (which neighbour it hangs on) and where the player starts, two cells off.
SIDES = {"west": ((1, 3), 4, (-1, 3)), "east": ((5, 3), 5, (7, 3)), "north": ((3, 1), 2, (3, -1)), "south": ((3, 5), 3, (3, 7))}
CASES = [*SIDES, "roof_hole", "roof_run"]


class Ladders(bs.Shells):
    def put(self, cells: dict[tuple, tuple]):
        """Solid blocks first: a ladder set before its wall drops."""
        for (x, y, z), (block, meta) in sorted(cells.items(), key=lambda kv: kv[1][0] == LADDER):
            self.s.call(bs.FIX + ".set_block", x=bs.PLOT[0] + x, y=bs.FLOOR + y, z=bs.PLOT[1] + z, id=block, meta=meta)

    def goto(self, origin, cell) -> dict:
        goal = [origin[i] + cell[i] for i in range(3)]
        try: r = self.c.call("nav.goto", x=goal[0], y=goal[1], z=goal[2], timeoutTicks=600)
        except BridgeError as e: r = ((e.reply or {}).get("error") or {}).get("receipt") or {"state": "failed", "reason": e.msg}
        pos = self.c.call("obs.player")["pos"]
        there = [int(pos[0] // 1), round(pos[1] - .3), int(pos[2] // 1)] == goal or [int(pos[0] // 1), int(pos[1] // 1), int(pos[2] // 1)] == goal
        return {"goal": list(cell), "state": r.get("state"), "reason": r.get("reason"), "ticks": r.get("ticks"), "moves": r.get("movementTypes"),
                "at": [round(v, 2) for v in pos], "passed": r.get("state") == "succeeded" and there,
                **({"failure": r.get("failure"), "path": r.get("path")} if r.get("state") != "succeeded" else {})}

    def trial(self, name: str) -> dict:
        origin = self.arena()
        if name == "roof_hole":   # walls on the ring of 5x5, two high, a roof on layer 2, the door east, the ladder on the west wall inside
            cells = {(x, y, z): (BRICK, 0) for x in range(5) for z in range(5) for y in range(3)
                     if y == 2 or ((x in (0, 4) or z in (0, 4)) and (x, z) != (4, 2))}
            cells.update({(1, y, 2): (LADDER, 5) for y in range(3)})
            start, goals = (7, 2), [(3, 3, 3), (7, 0, 2), (1, 1, 2)]
        elif name == "roof_run":  # a shut room one wide under a long roof, the hole at its west end with the ladder's top in it, a wall above
            cells = {(x, y, z): (BRICK, 0) for x in range(8) for z in (1, 2, 3) for y in range(3)
                     if (y == 2 and (x, z) != (1, 2)) or (y < 2 and (x in (0, 7) or z in (1, 3)))}
            cells.update({(0, 3, z): (BRICK, 0) for z in (1, 2, 3)})
            cells.update({(1, y, 2): (LADDER, 5) for y in range(3)})
            start, goals = (6, 2, 3), [(4, 0, 2), (6, 3, 2), (4, 0, 2)]
        else:
            (lx, lz), meta, start = SIDES[name]
            cells = {(x, y, z): (BRICK, 0) for x in (2, 3, 4) for z in (2, 3, 4) for y in range(4)}
            cells.update({(lx, y, lz): (LADDER, meta) for y in range(4)})
            goals = [(3, 4, 3), (start[0], 0, start[1]), (lx, 2, lz)]
        self.put(cells)
        self.stand([origin[0] + start[0] + .5, bs.FLOOR + (start[2] if len(start) > 2 else 0), origin[2] + start[1] + .5])
        legs = [self.goto(origin, g) for g in goals]
        return {"origin": origin, "legs": legs, "passed": all(leg["passed"] for leg in legs)}

    def run(self):
        names = self.args.case or CASES
        try:
            self.setup()
            for name in names:
                try: row = self.trial(name)
                except Exception as e: row = {"passed": False, "legs": [], "error": f"{type(e).__name__}: {e}"}
                self.evidence["cases"][name] = row
                print(f"{name:10} {'PASS' if row['passed'] else 'FAIL'} " + " | ".join(
                    f"{leg['goal']} {leg['state']} {leg['reason'] if leg['state'] != 'succeeded' else ''} {leg['ticks']}t" for leg in row["legs"]) + f" {row.get('error') or ''}", flush=True)
            self.evidence["ok"] = all(self.evidence["cases"][n]["passed"] for n in names)
            print(f"{sum(self.evidence['cases'][n]['passed'] for n in names)}/{len(names)} passed", flush=True)
        finally:
            self.teardown()
            OUT.parent.mkdir(parents=True, exist_ok=True); OUT.write_text(json.dumps(self.evidence, indent=2))
            print(OUT, flush=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--case", action="append", choices=CASES, help="case name (repeatable); default all")
    ap.add_argument("--keep", action="store_true", help="leave the last arena in place")
    ap.add_argument("--server-host", default="127.0.0.1", help="the server address as the client sees it, to rejoin")
    args = ap.parse_args()
    args.seed, args.trials = 1, 1
    course = Ladders(args)
    course.run()
    return 0 if course.evidence["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
