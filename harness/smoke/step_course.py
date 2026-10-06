"""A step never cuts a single action short, in the game: a click, a slot selection and a held input sent with a step
shorter than they need answer as done, the world is paused again afterwards, and a guard still ends them.

    bash harness/smoke/mbtest.sh harness/smoke/step_course.py [--case click_place]

Cases: click_place (a block placed with resume=1), click_lever (a lever flipped once, not twice), select (a slot
selected with resume=1), hold (20 ticks of forward on a 3-tick step), guard (a fall during an extended hold: the
health guard's pause stands and the hold reports it).
"""
from __future__ import annotations

import argparse
import json
import sys
import time

import builder_shell as bs
from movement_course import BridgeError
from kernel import call_resuming
from mbtools_gtnh import core

STONE, LEVER = "minecraft:stonebrick", "minecraft:lever"


class Steps(bs.Shells):
    COST = "walk"   # its cases run no job today: the check judges one only when a row carries a cost

    def paused(self) -> dict:
        """Pause and wait for it to settle; the clock state."""
        state = self.c.call("time.status")["state"]
        if not state.get("paused"): self.c.call("time.pause")
        until = time.monotonic() + 15
        while time.monotonic() < until:
            state = self.c.call("time.status")["state"]
            if state.get("mode") == "paused": return state
            time.sleep(.05)
        raise RuntimeError(f"pause never settled: {state}")

    def act(self, resume: int, method: str, **params) -> dict:
        """One stepped action from a paused world: its receipt or its error, and the clock it left behind."""
        self.paused()
        try: out = {"receipt": call_resuming(core.mb_act, resume, method, params)}
        except BridgeError as e: out = {"error": e.msg, "receipt": {"resumedWorld": getattr(e, "resumed_world", None), **((e.reply or {}).get("error") or {})}}
        time.sleep(.5)
        state = self.c.call("time.status")["state"]
        out.update(paused=state.get("paused"), reason=state.get("reason"), extended=(out["receipt"].get("resumedWorld") or {}).get("extendedTicks"))
        return out

    def scene(self):
        origin = self.arena(bare=True)
        self.at = lambda rel: [origin[0] + rel[0], bs.FLOOR + rel[1], origin[2] + rel[2]]
        return origin

    def click_place(self) -> dict:
        self.scene(); self.stand(self.at((2.5, 0, 2.5)))
        self.c.call("time.resume") if self.c.call("time.status")["state"].get("paused") else None
        core.mb_act("select_hotbar", {"slot": 0}); self.wait(5)
        x, y, z = (int(v) for v in self.at((2, -1, 4)))
        row = self.act(1, "use_block", x=x, y=y, z=z, face=1)
        self.c.call("time.resume"); self.wait(5)
        row["placed"] = self.region((2, 0, 4), (2, 0, 4)).get((2, 0, 4), {}).get("id")
        row["ok"] = "error" not in row and row["placed"] == bs.BLOCK and bool(row["extended"]) and row["paused"] and row["reason"] == "step"
        return row

    def click_lever(self) -> dict:
        self.scene(); self.set_block((2, 0, 4), STONE); self.set_block((2, 1, 4), LEVER, 5); self.wait(5); self.stand(self.at((2.5, 0, 2.5)))   # 5: on a block's top, off (glowstone, the arena's floor, holds no lever)
        core.mb_act("select_hotbar", {"slot": 8}); self.wait(5)
        before = self.region((2, 1, 4), (2, 1, 4)).get((2, 1, 4), {}).get("meta", -1)
        x, y, z = (int(v) for v in self.at((2, 1, 4)))
        row = self.act(1, "use_block", x=x, y=y, z=z, face=1)
        self.c.call("time.resume"); self.wait(5)
        after = self.region((2, 1, 4), (2, 1, 4)).get((2, 1, 4), {}).get("meta", -1)
        row.update(meta=[before, after])
        row["ok"] = "error" not in row and after == before ^ 8 and bool(row["extended"]) and row["paused"] and row["reason"] == "step"
        return row

    def select(self) -> dict:
        self.scene(); self.stand(self.at((2.5, 0, 2.5)))
        core.mb_act("select_hotbar", {"slot": 0}); self.wait(5)
        row = self.act(1, "select_hotbar", slot=3)
        row["ok"] = "error" not in row and bool(row["extended"]) and row["paused"] and row["reason"] == "step"
        self.c.call("time.resume")
        return row

    def hold(self) -> dict:
        self.scene(); start = self.stand(self.at((2.5, 0, 2.5)))
        row = self.act(3, "input", keys=["forward"], ticks=20)
        self.c.call("time.resume"); self.wait(10)
        row["moved"] = round(self.c.call("obs.player")["pos"][2] - start[2], 2)
        row["ok"] = "error" not in row and row["extended"] is not None and row["extended"] >= 15 and row["moved"] > 2.5 and row["paused"] and row["reason"] == "step"
        return row

    def guard(self) -> dict:
        self.scene()
        for z in (2, 3): self.set_block((2, 4, z), STONE)
        self.wait(5); self.stand(self.at((2.5, 5, 2.5)))
        self.c.call("time.configure", healthDrop=True)
        try:
            row = self.act(2, "input", keys=["forward"], ticks=80)   # two blocks of ledge, then a five-block fall
        finally:
            self.c.call("time.configure", healthDrop=False)
        row["ok"] = "error" in row and "guard" in row["error"] and row["paused"] and row["reason"] not in ("step", None) and bool(row["extended"])
        self.c.call("time.resume")
        return row


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--keep", action="store_true")
    ap.add_argument("--case")
    ap.add_argument("--server-host", default="127.0.0.1")
    bs.mc.tick_cost.argument(ap); args = ap.parse_args(); args.seed, args.trials = 1, 1
    t = Steps(args); t.setup()
    rows = []
    try:
        for name in ("click_place", "click_lever", "select", "hold", "guard"):
            if args.case and args.case != name: continue
            try: row = getattr(t, name)()
            except Exception as e: row = {"ok": False, "crashed": f"{type(e).__name__}: {e}"}
            t.costed(name, row, "ok"); rows.append(row)
            print(f"{name} {'PASS' if row['ok'] else 'FAIL'} {json.dumps({k: v for k, v in row.items() if k != 'ok'}, default=str)[:1500]}", flush=True)
            try:
                if t.c.call("time.status")["state"].get("paused"): t.c.call("time.resume")
            except BridgeError: pass
    finally:
        t.teardown()
    print(f"{sum(r['ok'] for r in rows)}/{len(rows)} passed", flush=True)
    sys.exit(0 if rows and all(r["ok"] for r in rows) else 1)


if __name__ == "__main__":
    main()
