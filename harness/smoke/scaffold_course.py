"""The mining job's scaffold in the game: a target out of reach is climbed to on placed blocks, and with
cleanup_scaffold the job breaks them again before it ends; without, they stand and the receipt says where.

    bash harness/smoke/mbtest.sh harness/smoke/scaffold_course.py
"""
from __future__ import annotations

import argparse
import json
import sys

import builder_shell as bs
from movement_course import BridgeError
from mbtools_gtnh import work

TARGET, POLE = "minecraft:glowstone", "minecraft:stonebrick"   # a block a hand breaks, on a pole a body cannot climb
UP = 9                                                          # the target's height over the floor: out of reach from the ground


class Scaffolds(bs.Shells):
    def trial(self, cleanup: bool) -> dict:
        origin = self.arena(bare=True, top=200)
        for y in range(UP): self.set_block((5, y, 1), POLE)
        self.set_block((5, UP, 1), TARGET)
        self.stand([origin[0] + 2.5, bs.FLOOR, origin[2] + 1.5])
        lo, hi = (0, 0, -3), (10, UP + 4, 5)
        before = self.region(lo, hi)
        bounds = {"min": [origin[0] + 5, bs.FLOOR + UP, origin[2] + 1], "max": [origin[0] + 5, bs.FLOOR + UP, origin[2] + 1]}
        try:
            receipt = work.mb_mine(blocks=[{"id": TARGET}], quantity=1, bounds=bounds, allow_break=False, allow_place=True,
                                   cleanup_scaffold=cleanup, timeout_ticks=3000)
        except BridgeError as e:
            err = (e.reply or {}).get("error") or {}
            receipt = {**(err.get("receipt") if isinstance(err.get("receipt"), dict) else {}), "state": "failed", "errorMsg": e.msg}
        self.wait(10)
        after = self.region(lo, hi)
        standing = sorted(p for p in after if p not in before)          # blocks that were not there: what the job left
        sc = receipt.get("scaffold") or {}
        row = dict(state=receipt.get("state") or "succeeded", reason=receipt.get("reason") or receipt.get("errorMsg"), ticks=receipt.get("ticks"),
                   mined=(5, UP, 1) not in after, scaffold={k: sc.get(k) for k in ("cleanup", "placed", "removed", "left")}, standing=len(standing),
                   closingCut=receipt.get("closingCut"), player=[round(v, 2) for v in self.c.call("obs.player")["pos"]])
        placed = sc.get("placed") or 0
        if cleanup: row["ok"] = row["state"] == "succeeded" and row["mined"] and placed >= 1 and sc.get("cleanup") == "done" and sc.get("removed") == placed and not sc.get("left") and not standing
        else: row["ok"] = row["state"] == "succeeded" and row["mined"] and placed >= 1 and sc.get("cleanup") == "off" and len(standing) == placed
        return row


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--keep", action="store_true")
    ap.add_argument("--server-host", default="127.0.0.1")
    args = ap.parse_args(); args.seed, args.trials, args.case = 1, 1, None
    t = Scaffolds(args); t.setup()
    rows = []
    try:
        for name, cleanup in (("scaffold_cleanup", True), ("scaffold_kept", False)):
            row = t.trial(cleanup); rows.append(row)
            print(f"{name} {'PASS' if row['ok'] else 'FAIL'} {json.dumps({k: v for k, v in row.items() if k != 'ok'})}", flush=True)
    finally:
        t.teardown()
    print(f"{sum(r['ok'] for r in rows)}/{len(rows)} passed", flush=True)
    sys.exit(0 if all(r["ok"] for r in rows) else 1)


if __name__ == "__main__":
    main()
