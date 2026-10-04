"""The glide in the game (docs/MOVEMENTS.md): a body with wings on crosses a drop by gliding and arrives unhurt; without
them, or with something in the flight's way, it stays where it is.

    bash harness/smoke/mbtest.sh harness/smoke/glide_course.py                 # the course
    bash harness/smoke/mbtest.sh harness/smoke/glide_course.py --measure 20    # one flight at a held pitch, tick by tick

The measured flight is what ElytraGlideTest pins the arithmetic to. The wings come off again at the end: a body left
wearing them would glide in every later test.
"""
from __future__ import annotations

import argparse
import json
import sys
import time

import builder_shell as bs
import movement_course as mc
from movement_course import BridgeError
from mbtools_gtnh import inventory, work

WINGS = "etfuturum:elytra"
HEIGHT, EDGE, FAR = 20, -19, 30        # a tower twenty up whose east edge is at plot x -19; the goal is on the floor at x 30
CHEST = 6                              # the player's own screen: the slot the wings are worn in


class Glides(bs.Shells):
    home = 44                          # the slot the wings came from, and go back to

    def build(self, pillar: bool = False) -> tuple[list[float], list[int]]:
        origin = self.arena(width=64, depth=16, top=200, bare=True)
        for y in range(HEIGHT):
            for x in (EDGE - 1, EDGE):
                for z in (0, 1, 2): self.set_block((x, y, z), "minecraft:stonebrick")
        if pillar:
            for y in range(HEIGHT + 4):
                for z in (0, 1, 2): self.set_block((0, y, z), "minecraft:stonebrick")
        return [origin[0] + EDGE + .5, bs.FLOOR + HEIGHT, origin[2] + 1.5], [origin[0] + FAR, bs.FLOOR, origin[2] + 1]

    def wings(self, on: bool) -> bool:
        """Put the wings on or take them off as a player does in their own screen: pick up, click the chest slot, put down
        what came off it where the wings were. Whether they are on now."""
        if on and not any(row["id"] == WINGS for row in self.s.call(bs.FIX + ".status")["inventory"]):
            self.s.call(bs.FIX + ".set_stack", slot=8, id=WINGS, meta=0, count=1); self.wait(5)
        self.c.call("gui.open_inventory")
        session = inventory.ContainerSession(self.c)
        where = lambda: next((s["i"] for s in session.observe()["slots"] if (s.get("stack") or {}).get("id") == WINGS), None)
        try:
            at = where()
            if at is not None and (at == CHEST) != on:
                if on: self.home = at
                for i in (self.home, CHEST, self.home): session.click(i, "pickup")
            return where() == CHEST
        finally:
            self.c.call("gui.close")

    def discard(self):
        """Off, and out of the inventory: the fixture overwrites the slot they went to."""
        if self.wings(False): raise RuntimeError("the wings did not come off")
        for row in self.s.call(bs.FIX + ".status")["inventory"]:
            if row["id"] == WINGS: self.s.call(bs.FIX + ".set_stack", slot=row["slot"], id=bs.BLOCK, meta=0, count=1)

    def go(self, start: list[float], goal: list[int]) -> dict:
        self.stand(start)
        before = self.c.call("obs.player")
        sampler = mc.Sampler(hz=20); sampler.start()
        try:
            receipt = work.mb_process("goal", goal={"type": "block", "pos": goal}, duration_ticks=400, allow_break=False, allow_place=False, timeout_s=60)
        except BridgeError as e:
            err = (e.reply or {}).get("error") or {}
            receipt = {**(err.get("receipt") if isinstance(err.get("receipt"), dict) else {}), "state": "failed", "errorMsg": e.msg}
        time.sleep(.3); sampler.finish()
        try: self.c.call("act.stop")
        except BridgeError: pass
        after = self.c.call("obs.player")
        rows = sampler.samples
        if self.args.trace:
            for r in rows: print("  ", r["tick"], [round(v, 3) for v in r["pos"]], "ground" if r.get("onGround") else "", r.get("health"), flush=True)
        speed = max((abs(b["pos"][0] - a["pos"][0]) / (b["tick"] - a["tick"]) for a, b in zip(rows, rows[1:]) if b["tick"] > a["tick"]), default=0.0)
        return dict(state=receipt.get("state") or "succeeded", reason=receipt.get("reason") or receipt.get("errorMsg"), ticks=receipt.get("ticks"),
                    final=[round(v, 2) for v in after["pos"]], hurt=round(before["health"] - min([after["health"]] + [r["health"] for r in rows]), 2),
                    topSpeed=round(speed, 2))

    def course(self) -> list[dict]:
        out = []
        def case(name, row, ok):
            row = dict(case=name, ok=bool(ok), **row); out.append(row)
            print(f"{name} {'PASS' if ok else 'FAIL'} {json.dumps({k: v for k, v in row.items() if k not in ('case', 'ok')})}", flush=True)
        start, goal = self.build()
        stayed = lambda r: r["state"] != "succeeded" and r["final"][1] >= start[1] - .1 and r["hurt"] == 0
        self.wings(False)
        r = self.go(start, goal); case("no_wings", r, stayed(r))
        worn = self.wings(True)
        r = self.go(start, goal)
        case("glide", {**r, "worn": worn}, worn and r["state"] == "succeeded" and r["hurt"] == 0 and r["topSpeed"] > .6
             and abs(r["final"][0] - goal[0] - .5) < 1 and abs(r["final"][1] - goal[1]) < .1)
        start, goal = self.build(pillar=True)
        r = self.go(start, goal); case("glide_blocked", r, stayed(r))
        return out

    def measure(self, pitches: list[float]):
        """A flight at a held pitch, the world stepped a tick at a time: position and motion after every tick."""
        start, _ = self.build()
        print("worn", self.wings(True), flush=True)
        for pitch in pitches:
            self.stand(start)
            self.c.call("act.look", yaw=-90, pitch=0)
            until = time.monotonic() + 5
            while self.c.call("obs.player").get("onGround") and time.monotonic() < until: self.c.call("act.input", keys=["forward"], ticks=1)
            self.c.call("act.input", keys=["jump"], ticks=1)          # in the air: the wings open
            self.c.call("act.look", yaw=-90, pitch=pitch)
            rows = []
            until = time.monotonic() + 30
            while time.monotonic() < until:
                r = self.c.call_reply("obs.player"); p = r.data
                if not rows or r.tick != rows[-1][0]: rows.append((r.tick, p["pos"], p.get("onGround"), p.get("health")))
                if p.get("onGround") and len(rows) > 5: break
            print(f"pitch {pitch}: {len(rows)} samples, health {rows[-1][3]}", flush=True)
            for a, b in zip(rows, rows[1:]):                          # motion is the step between two ticks seen in a row
                step = [round((q - p) / (b[0] - a[0]), 4) for p, q in zip(a[1], b[1])]
                print("  ", b[0] - rows[0][0], [round(v, 3) for v in b[1]], step, "" if b[0] - a[0] == 1 else f"(over {b[0] - a[0]} ticks)", "ground" if b[2] else "", flush=True)
            self.wait(30)
            p = self.c.call("obs.player"); print("  at rest", [round(v, 3) for v in p["pos"]], "health", p.get("health"), flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--measure", nargs="*", type=float, default=None)
    ap.add_argument("--keep", action="store_true")
    ap.add_argument("--trace", action="store_true", help="print every sampled tick of each run")
    ap.add_argument("--server-host", default="127.0.0.1")
    args = ap.parse_args(); args.seed, args.trials, args.case = 1, 1, None
    t = Glides(args); t.setup()
    rows = None
    try:
        if args.measure is not None: t.measure(args.measure or [20.0])
        else: rows = t.course()
    finally:
        try: t.discard()
        finally: t.teardown()
    if rows is not None:
        print(f"{sum(r['ok'] for r in rows)}/{len(rows)} passed", flush=True)
        sys.exit(0 if all(r["ok"] for r in rows) else 1)


if __name__ == "__main__":
    main()
