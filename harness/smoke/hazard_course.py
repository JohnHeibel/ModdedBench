# SPDX-License-Identifier: MIT
# Copyright (c) 2026 ModdedBench contributors
"""The hazard reflexes, in the game: what the client does by itself in fire and when a hit throws it toward a drop
(Baritone's Hazards), and the edges the clock status lists (the client's Edges). Judged from the server at 10 Hz, in
the combat course's box, guards off, the world running.

  fire_idle  standing in fire on netherrack (it never goes out) with nothing running: out of it within 2 seconds
  fire_goto  the same with a walk 12 blocks on just started: out of it within 2 seconds, by the walk or the reflex
  fire_hold  the same holding sneak (act.input, 100 ticks): the hold ends with stepped_out_of_fire, and the player is out
  ledge_hit  on a platform 15 up over lava, the edge 1.6 blocks behind, a zombie coming from ahead and gone once it has hit
             (one hit; a zombie that keeps hitting is the fight's to beat, in combat_course): a hit's throw carries an
             unheld player about 2 blocks, so off it; held against, about 1. Won when the throw lands on the platform and
             the player has walked back 1.5 blocks or more from the edge within 1.5 seconds. time.status must list the
             edge behind (west, onto lava, burns).

    MB_CLIENT_INSTANCE=<instance>/.minecraft bash harness/smoke/mbtest.sh harness/smoke/hazard_course.py [--case NAME ...] [--trials 3]

Evidence: .runtime/evidence/hazard-course.json and a line a trial on stdout.
"""
from __future__ import annotations

import argparse
import json
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import combat_course as cc  # noqa: E402  (the box, the player's kit, the fixtures)
import movement_course as mc  # noqa: E402
from movement_course import BridgeError  # noqa: E402

OUT = mc.ROOT / ".runtime" / "evidence" / "hazard-course.json"
FIRE = [("minecraft:netherrack", (-1, -1, -1), (1, -1, 1))]
LIGHT = [("minecraft:fire", (-1, 0, -1), (1, 0, 1))]   # lit after the job starts: on its own the player is out before then
UP = 15   # the platform's top: the player stands at FLOOR + UP
LEDGE = [(cc.STONE, (-2, 0, -3), (12, UP - 1, 3)), (cc.LAVA, (-10, 0, -10), (-3, 0, 10))]
CASES = {
    "fire_idle": dict(ground=FIRE, light=LIGHT),
    "fire_goto": dict(ground=FIRE, light=LIGHT, goto=(12, 0, 0)),
    "fire_hold": dict(ground=FIRE, light=LIGHT, hold=["sneak"]),
    "ledge_hit": dict(ground=LEDGE, at=(-.9, UP, 0), mobs=[("Zombie", 6, UP, 1)]),
}


def trial(s, c, name: str) -> dict:
    case = CASES[name]
    c.call("time.configure", **cc.OFF)
    if c.call("time.status")["state"].get("paused"): c.call("time.resume")
    s.call(cc.FIX + ".clear")
    if s.call(cc.FIX + ".status")["player"]["dead"]:
        until = time.monotonic() + 20
        while s.call(cc.FIX + ".status")["player"]["dead"] and time.monotonic() < until:
            try: c.call("gui.button", index=0)
            except BridgeError: pass
            time.sleep(1)
    s.call(cc.FIX + ".arena", **cc.ARENA)
    for boxes in cc.batches(case["ground"]): s.call(cc.FIX + ".fill", boxes=boxes)
    at = [a + b for a, b in zip(cc.START, case.get("at", (0, 0, 0)))]
    s.call(cc.FIX + ".reset", empty=True, armor=cc.ARMOR, stacks=cc.STACKS, at=at, yaw=-90, effects=cc.SIGHT)
    s.call(cc.FIX + ".clear"); time.sleep(.5)
    row = {"case": name, "start": at}
    if name == "ledge_hit": row["edges"] = c.call("time.status")["state"].get("edges")
    for mob, ahead, up, count in case.get("mobs", []):
        s.call(cc.FIX + ".summon", type=mob, x=cc.START[0] + ahead, y=cc.FLOOR + up, z=cc.START[2], count=count)
    job = {}
    if "goto" in case or "hold" in case:  # the call answers when the job ends: on its own thread, with the server watched meanwhile
        def run():
            try:
                if "goto" in case:
                    dx, dy, dz = case["goto"]
                    job["answer"] = c.call("nav.goto", x=cc.X0 + dx, y=cc.FLOOR + dy, z=cc.Z0 + dz, timeoutTicks=400)
                else: job["answer"] = c.call("act.input", keys=case["hold"], ticks=100)
            except BridgeError as e: job["answer"] = {"error": str(e)}
        threading.Thread(target=run, daemon=True).start(); time.sleep(.3)
    for boxes in cc.batches(case.get("light", [])): s.call(cc.FIX + ".fill", boxes=boxes)
    samples, first_hit, health0 = [], None, None
    t0 = time.time()
    while time.time() - t0 < (12 if name == "ledge_hit" else 4):
        p = s.call(cc.FIX + ".status", radius=4)["player"]
        x, y, z = p["pos"]; health0 = p["health"] if health0 is None else health0
        samples.append([round(time.time() - t0, 1), round(x, 2), round(y, 2), round(z, 2), p["health"], p["dead"]])
        if first_hit is None and p["health"] < health0:
            first_hit = time.time()
            if name == "ledge_hit": s.call(cc.FIX + ".clear")
        if first_hit and time.time() - first_hit > 1.5: break
        if p["dead"]: break
        time.sleep(.1)
    end = samples[-1]
    if name.startswith("fire"):
        fx, fz = int(end[1] // 1), int(end[3] // 1)
        out_of_fire = not (abs(fx - cc.X0) <= 1 and abs(fz - cc.Z0) <= 1)
        left = next((t for t, x, y, z, h, d in samples if not (abs(int(x // 1) - cc.X0) <= 1 and abs(int(z // 1) - cc.Z0) <= 1)), None)
        row.update(won=out_of_fire and not end[5], outAfterS=left, healthLost=round(samples[0][4] - min(r[4] for r in samples), 1))
        row["won"] = row["won"] and left is not None and left <= 2
        if "goto" in case or "hold" in case:
            until = time.monotonic() + 10
            while "answer" not in job and time.monotonic() < until: time.sleep(.1)
            row["job"] = str(job.get("answer"))[:300]
            if "hold" in case: row["won"] = row["won"] and "stepped_out_of_fire" in row["job"]
    else:
        top, edge = cc.FLOOR + UP, cc.X0 - 2   # the platform's top; the edge's block (its west face at x = edge)
        first = next((i for i in range(1, len(samples)) if samples[i][4] < samples[i - 1][4]), None)
        after = samples[first:] if first else []
        landed = bool(after) and min(r[2] for r in after) >= top - .01 and any(r[2] <= top + .01 for r in after[1:])
        back = after[-1][1] if landed else None
        row.update(won=landed and back - edge >= 1.5, hit=first is not None, thrown=round(at[0] - min(r[1] for r in after), 2) if after else None,
                   landed=landed, backFromEdge=round(back - edge, 2) if back is not None else None, died=end[5])
    s.call(cc.FIX + ".clear")
    row["samples"] = samples
    return row


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--case", action="append", choices=sorted(CASES)); ap.add_argument("--trials", type=int, default=3)
    a = ap.parse_args()
    s, c = mc.server_kernel(120), mc.Kernel(timeout=60)
    s.call(cc.FIX + ".rules", rules=cc.RULES, difficulty=1)
    rows = []
    for name in a.case or list(CASES):
        for i in range(a.trials):
            try: row = trial(s, c, name)
            except Exception as e: row = {"case": name, "won": False, "error": f"{type(e).__name__}: {e}"}
            rows.append(row)
            print(json.dumps({k: v for k, v in row.items() if k != "samples"}), flush=True)
    OUT.parent.mkdir(parents=True, exist_ok=True); OUT.write_text(json.dumps(rows, indent=1))
    for name in a.case or list(CASES):
        got = [r for r in rows if r["case"] == name]
        print(f"{name}: {sum(r['won'] for r in got)}/{len(got)}")


if __name__ == "__main__":
    main()
