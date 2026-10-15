# SPDX-License-Identifier: MIT
# Copyright (c) 2026 ModdedBench contributors
"""The hazard reflexes, in the game: what the client does by itself in fire and when a hit throws it toward a drop
(Baritone's Hazards), and the edges the clock status lists (the client's Edges). Judged from the server at 10 Hz, in
the combat course's box, guards off, the world running.

  fire_idle  standing in fire on netherrack (it never goes out) with nothing running: out of it within 2 seconds
  fire_goto  the same with a walk 12 blocks on just started: out of it within 2 seconds, by the walk or the reflex
  fire_hold  the same holding sneak (act.input, 100 ticks): the hold ends with stepped_out_of_fire, and the player is out
  fire_stuck fire in a pit a block deep under a roof at head height: the step out finds the way up and cannot jump to it
             (dragon run 5's lava pool). Holding sneak ends as in fire_hold; three seconds in, a second hold (20 ticks)
             is the model's own way out and must run to its end: a step that is not getting the player out gives way to it.
  ledge_hit  on a platform 15 up over lava, the edge 1.6 blocks behind, a zombie coming from ahead and gone once it has hit
             (one hit; a zombie that keeps hitting is the fight's to beat, in combat_course): a hit's throw carries an
             unheld player about 2 blocks, so off it; held against, about 1. Won when the throw lands on the platform and
             the player has walked back 1.5 blocks or more from the edge within 1.5 seconds. time.status must list the
             edge behind (west, onto lava, burns).
  ledge_paused the same with a health_drop rule: the hit pauses the world mid-throw, with the hold on. An action (a 10-tick
             crouch) is then sent with resume, as the model would (fortress trial 8): the hold takes the controls from
             it on its first tick. Won when the world runs on, so the hold can finish, and the throw lands as in ledge_hit.
  ledge_over the same at the very edge: thrown over it. Once the feet are a block below the platform's top an action
             starts (a 10-tick crouch); won when it is not cancelled by the hold, which can no longer bring the player back
  fire_bow   a bow fight at a zombie 20 off, fire lit under the player mid-draw: out of it within 2 seconds, and every
             arrow the bow spent a shot the fight told (a draw let go as the step began would fly unaimed)
  ledge_bow  a bow duel on that platform with a skeleton 13 off, the edge behind: its arrows throw the player toward the
             lava. Won when the player never leaves the platform's top and every arrow spent was a shot told.
  lasting_harm  a health_drop rule, a poison for 6 seconds, then a wither: harm that lasts is one event, so each pauses the world
             once however often it hurts (each pause is resumed at once). Won when each made exactly one pause and 2+ hurts.

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
PIT = [(cc.STONE, (-1, 0, -1), (1, 0, -1)), (cc.STONE, (-1, 0, 1), (1, 0, 1)), (cc.STONE, (-1, 0, 0), (-1, 0, 0)), (cc.STONE, (1, 0, 0), (1, 0, 0)),
       ("minecraft:netherrack", (0, -1, 0), (0, -1, 0)), (cc.STONE, (0, 2, 0), (0, 2, 0))]
LEDGE = [(cc.STONE, (-2, 0, -3), (12, UP - 1, 3)), (cc.LAVA, (-10, 0, -10), (-3, 0, 10))]
CASES = {
    "fire_idle": dict(ground=FIRE, light=LIGHT),
    "fire_goto": dict(ground=FIRE, light=LIGHT, goto=(12, 0, 0)),
    "fire_hold": dict(ground=FIRE, light=LIGHT, hold=["sneak"]),
    "fire_stuck": dict(ground=PIT, light=[("minecraft:fire", (0, 0, 0), (0, 0, 0))], hold=["sneak"], stuck=True),
    "ledge_hit": dict(ground=LEDGE, at=(-.9, UP, 0), mobs=[("Zombie", 6, UP, 1)]),
    "ledge_paused": dict(ground=LEDGE, at=(-.9, UP, 0), mobs=[("Zombie", 6, UP, 1)], paused=True),
    "ledge_over": dict(ground=LEDGE, at=(-2.15, UP, 0), mobs=[("Zombie", 6, UP, 1)], probe=True),
    "fire_bow": dict(ground=FIRE, light=LIGHT, mobs=[("Zombie", 20, 0, 1)], fight=dict(weapon_slot=cc.BOW, ranged=True, duration_ticks=160)),
    "ledge_bow": dict(ground=LEDGE, at=(-.9, UP, 0), mobs=[("Skeleton", 12, UP, 1)], fight=dict(weapon_slot=cc.BOW, ranged=True, duration_ticks=300)),
    "lasting_harm": dict(ground=[], lasting=[("poison", 6), ("wither", 6)]),
}


def lasting(s, c, row: dict, harms) -> dict:
    """Each harm given afresh for its seconds with a health_drop rule: the pauses it made, and how often it hurt."""
    mc.quiet(c, mc.rule("health_drop"))
    for effect, seconds in harms:
        last = s.call(cc.FIX + ".status")["player"]["health"]
        s.call(cc.FIX + ".reset", health=last, effects=cc.SIGHT + [{"id": effect, "seconds": seconds}])
        pauses = hurts = 0; t0 = time.time()
        while time.time() - t0 < seconds + 1:
            state = c.call("time.status")["state"]
            if state.get("paused"):
                pauses += state.get("reason") == "health_drop"
                c.call("time.resume")
            health = s.call(cc.FIX + ".status")["player"]["health"]
            hurts += health < last; last = health
            time.sleep(.1)
        row[effect] = {"pauses": pauses, "hurts": hurts}
    mc.quiet(c)
    if c.call("time.status")["state"].get("paused"): c.call("time.resume")
    row["won"] = all(row[e]["pauses"] == 1 and row[e]["hurts"] >= 2 for e, _ in harms)
    return row


def trial(s, c, name: str) -> dict:
    case = CASES[name]
    mc.quiet(c)
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
    if "lasting" in case: return lasting(s, c, row, case["lasting"])
    if name == "ledge_hit": row["edges"] = c.call("time.status")["state"].get("edges")
    if case.get("paused"): mc.quiet(c, mc.rule("health_drop"))
    mobs = [m for mob, ahead, up, count in case.get("mobs", [])
            for m in s.call(cc.FIX + ".summon", type=mob, x=cc.START[0] + ahead, y=cc.FLOOR + up, z=cc.START[2], count=count)["summoned"]]
    bow = lambda: next((i.get("meta", 0) for i in s.call(cc.FIX + ".status")["player"]["inventory"] if i["slot"] == cc.BOW), 0)
    worn, job = bow(), {}
    if "goto" in case or "hold" in case or "fight" in case:  # the call answers when the job ends: on its own thread, with the server watched meanwhile
        def run():
            try:
                if "fight" in case: job["answer"] = cc.told(lambda: cc.work.mb_fight(entity_id=mobs[0]["entityId"], **case["fight"], timeout_s=60))
                elif "goto" in case:
                    dx, dy, dz = case["goto"]
                    job["answer"] = c.call("nav.goto", x=cc.X0 + dx, y=cc.FLOOR + dy, z=cc.Z0 + dz, timeoutTicks=400)
                else: job["answer"] = c.call("act.input", keys=case["hold"], ticks=100)
            except BridgeError as e: job["answer"] = {"error": str(e)}
        threading.Thread(target=run, daemon=True).start(); time.sleep(.3)
    for boxes in cc.batches(case.get("light", [])): s.call(cc.FIX + ".fill", boxes=boxes)
    samples, first_hit, health0 = [], None, None
    t0 = time.time()
    while time.time() - t0 < (12 if name.startswith("ledge") else 5 if case.get("stuck") else 4):
        p = s.call(cc.FIX + ".status", radius=4)["player"]
        x, y, z = p["pos"]; health0 = p["health"] if health0 is None else health0
        samples.append([round(time.time() - t0, 1), round(x, 2), round(y, 2), round(z, 2), p["health"], p["dead"]])
        if first_hit is None and p["health"] < health0:
            first_hit = time.time()
            if name in ("ledge_hit", "ledge_over", "ledge_paused"): s.call(cc.FIX + ".clear")
        if case.get("paused") and first_hit and "act" not in job and (state := c.call("time.status")["state"]).get("paused"):
            job["pausedBy"] = state.get("reason")
            try: job["act"] = c.call("act.input", keys=["sneak"], ticks=10, _resume=True)
            except BridgeError as e: job["act"] = {"error": str(e)}
            time.sleep(.3); state = c.call("time.status")["state"]
            job["after"] = state.get("reason") if state.get("paused") else "running"
            mc.quiet(c); first_hit = time.time()   # judged from here on as ledge_hit is
        if case.get("probe") and "probe" not in job and y < cc.FLOOR + UP - 1:
            job["probe"] = None
            def probe():
                try: job["probe"] = c.call("act.input", keys=["sneak"], ticks=10)
                except BridgeError as e: job["probe"] = {"error": str(e)}
            threading.Thread(target=probe, daemon=True).start()
        if case.get("stuck") and "late" not in job and time.time() - t0 > 3:
            job["late"] = None
            def late():
                try: job["late"] = c.call("act.input", keys=["sneak"], ticks=20)
                except BridgeError as e: job["late"] = {"error": str(e)}
            threading.Thread(target=late, daemon=True).start()
        if first_hit and time.time() - first_hit > 1.5 and "fight" not in case and not case.get("stuck"): break
        if p["dead"]: break
        time.sleep(.1)
    end = samples[-1]
    if case.get("stuck"):
        until = time.monotonic() + 5
        while ("answer" not in job or not job.get("late")) and time.monotonic() < until: time.sleep(.1)
        first, late = str(job.get("answer"))[:300], str(job.get("late"))[:300]
        row.update(won="stepped_out_of_fire" in first and "duration_elapsed" in late, job=first, late=late,
                   healthLost=round(samples[0][4] - min(r[4] for r in samples), 1), died=end[5])
    elif name.startswith("fire"):
        fx, fz = int(end[1] // 1), int(end[3] // 1)
        out_of_fire = not (abs(fx - cc.X0) <= 1 and abs(fz - cc.Z0) <= 1)
        left = next((t for t, x, y, z, h, d in samples if not (abs(int(x // 1) - cc.X0) <= 1 and abs(int(z // 1) - cc.Z0) <= 1)), None)
        row.update(won=out_of_fire and not end[5], outAfterS=left, healthLost=round(samples[0][4] - min(r[4] for r in samples), 1))
        row["won"] = row["won"] and left is not None and left <= 2
        if "goto" in case or "hold" in case or "fight" in case:
            until = time.monotonic() + 10
            while "answer" not in job and time.monotonic() < until: time.sleep(.1)
            row["job"] = str(job.get("answer"))[:300]
            if "hold" in case: row["won"] = row["won"] and "stepped_out_of_fire" in row["job"]
    elif case.get("probe"):
        until = time.monotonic() + 5
        while job.get("probe") is None and "probe" in job and time.monotonic() < until: time.sleep(.1)
        answer = str(job.get("probe"))[:300]
        row.update(won="probe" in job and job["probe"] is not None and "held_against_a_hit" not in answer and "error" not in answer, probe=answer,
                   lowestY=min(r[2] for r in samples), died=end[5])
    elif "fight" in case:
        until = time.monotonic() + 15
        while "answer" not in job and time.monotonic() < until: time.sleep(.1)
        row.update(won=min(r[2] for r in samples) >= cc.FLOOR + UP - .01 and not end[5], lowestY=min(r[2] for r in samples), died=end[5])
    else:
        top, edge = cc.FLOOR + UP, cc.X0 - 2   # the platform's top; the edge's block (its west face at x = edge)
        first = next((i for i in range(1, len(samples)) if samples[i][4] < samples[i - 1][4]), None)
        after = samples[first:] if first else []
        landed = bool(after) and min(r[2] for r in after) >= top - .01 and any(r[2] <= top + .01 for r in after[1:])
        back = after[-1][1] if landed else None
        if case.get("paused"):
            row.update(pausedBy=job.get("pausedBy"), act=str(job.get("act"))[:300], after=job.get("after"))
            landed = landed and job.get("after") == "running"
        row.update(won=landed and back - edge >= 1.5, hit=first is not None, thrown=round(at[0] - min(r[1] for r in after), 2) if after else None,
                   landed=landed, backFromEdge=round(back - edge, 2) if back is not None else None, died=end[5])
    if "fight" in case:
        receipt = job.get("answer") or {}
        row.update(fight={k: receipt.get(k) for k in ("state", "reason", "shots", "heldAgainstThrows") if receipt.get(k) is not None}, bowWear=bow() - worn)
        row["won"] = row["won"] and receipt.get("shots", 0) == row["bowWear"]   # an arrow the fight never told of flew unaimed
    s.call(cc.FIX + ".clear")
    row["samples"] = samples
    return row


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--case", action="append", choices=sorted(CASES)); ap.add_argument("--trials", type=int, default=3)
    a = ap.parse_args()
    s, c = mc.server_kernel(120), mc.Kernel(timeout=60)
    mc.mbtool.set_kernel_factory(lambda: c)   # mb_fight acts through this session
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
