"""get_to_block by registry id, in the game: the target is found without the engine's whole-world sweep, a search
that begins with no target sees one in a chunk that loads later, and the game thread stays inside its budget.

    bash harness/smoke/mbtest.sh harness/smoke/explore_course.py [--case found]

Cases: found (a block across the arena, at about the cost of the same walk by a plain goal), absent (no such block:
no tick costs more than BUDGET_MS), late (the job starts a view distance away with no target loaded; the player is
moved back beside one mid-job, its chunk loads, and the job reaches it).
"""
from __future__ import annotations

import argparse
import json
import sys
import threading
import time

import builder_shell as bs
from movement_course import BridgeError
from mbtools_gtnh import work

TARGET, ABSENT = "minecraft:lapis_block", "minecraft:emerald_block"
BUDGET_MS = 5.0   # the whole-world sweep this replaces measured 35 to 300 ms; our own tick budget is 1 ms


class Explore(bs.Shells):
    COST, costs = "walk", []

    def job(self, process="get_to_block", **kw) -> dict:
        try: out = work.mb_process(process, **kw)
        except BridgeError as e: out = {"error": e.msg, **((e.reply or {}).get("error") or {})}
        cost = out.get("cost") or (out.get("receipt") or {}).get("cost") or {}; self.costs.append(cost)
        return {"state": out.get("state"), "reason": out.get("reason") or out.get("error"), "tickMsMax": round(cost.get("tickNsMax", -1e6) / 1e6, 2),
                "tickMsMean": round(cost.get("tickNsMean", -1e6) / 1e6, 3), "ticks": cost.get("ticks"), "keys": sorted(out)[:14]}

    def near(self, rel) -> float:
        p = self.c.call("obs.player")["pos"]; o = self.origin
        return round(((p[0] - o[0] - rel[0] - .5) ** 2 + (p[2] - o[2] - rel[2] - .5) ** 2) ** .5, 1)

    def scene(self):
        self.origin = self.arena(bare=True); self.at = lambda rel: [self.origin[0] + rel[0], bs.FLOOR + rel[1], self.origin[2] + rel[2]]

    def found(self) -> dict:
        self.scene(); self.set_block((5, 0, 5), TARGET); self.wait(5); self.stand(self.at((1.5, 0, 1.5)))
        walk = self.job("goal", goal={"type": "near", "pos": [int(v) for v in self.at((5, 0, 4))], "radius": 1}, duration_ticks=600, timeout_s=120)
        self.stand(self.at((1.5, 0, 1.5)))
        row = self.job(block={"id": TARGET}, duration_ticks=600, timeout_s=120); row["distance"] = self.near((5, 0, 5))
        row["walk"] = {k: walk[k] for k in ("state", "tickMsMax", "tickMsMean", "ticks")}   # the same walk by a plain goal: what the engine costs without a search for blocks
        row["ok"] = row["state"] == "succeeded" and row["distance"] < 3 and 0 <= row["tickMsMean"] < 2 * walk["tickMsMean"] + 1
        return row

    def absent(self) -> dict:
        self.scene(); self.stand(self.at((1.5, 0, 1.5)))
        row = self.job(block={"id": ABSENT}, duration_ticks=300, stall_ticks=0, timeout_s=120)
        row["ok"] = row["state"] != "succeeded" and 0 <= row["tickMsMax"] < BUDGET_MS
        return row

    def late(self) -> dict:
        self.scene(); self.set_block((5, 0, 5), TARGET); self.wait(5)
        self.s.call("dev.replay.place", x=self.args.far[0], y=self.args.far[1], z=self.args.far[2], yaw=0); time.sleep(3); self.wait(40)
        away = self.near((5, 0, 5)); to = self.at((1.5, 0, 1.5))
        move = threading.Timer(4, lambda: self.s.call("dev.replay.place", x=to[0], y=to[1], z=to[2], yaw=0)); move.start()
        try: row = self.job(block={"id": TARGET}, duration_ticks=600, stall_ticks=0, timeout_s=150)
        finally: move.cancel()
        row["startedAway"] = away; row["distance"] = self.near((5, 0, 5))
        row["ok"] = row["state"] == "succeeded" and away > 200 and row["distance"] < 3
        return row


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--keep", action="store_true")
    ap.add_argument("--case")
    ap.add_argument("--far", type=float, nargs=3, default=[325.5, 85, 25.5], metavar=("X", "Y", "Z"), help="a standing place more than a view distance from the arena (the build suite's first terrain hall)")
    ap.add_argument("--server-host", default="127.0.0.1")
    bs.mc.tick_cost.argument(ap); args = ap.parse_args(); args.seed, args.trials = 1, 1
    t = Explore(args); t.setup(); rows = []
    try:
        for name in ("found", "absent", "late"):
            if args.case and args.case != name: continue
            try: row = getattr(t, name)()
            except Exception as e: row = {"ok": False, "crashed": f"{type(e).__name__}: {e}"}
            row["costs"], t.costs = t.costs, []; t.costed(name, row, "ok"); rows.append(row)
            print(f"{name} {'PASS' if row['ok'] else 'FAIL'} {json.dumps({k: v for k, v in row.items() if k not in ('ok', 'costs')}, default=str)[:900]}", flush=True)
            try: t.c.call("act.stop")
            except BridgeError: pass
    finally:
        t.teardown()
    print(f"{sum(r['ok'] for r in rows)}/{len(rows)} passed", flush=True)
    sys.exit(0 if rows and all(r["ok"] for r in rows) else 1)


if __name__ == "__main__":
    main()
