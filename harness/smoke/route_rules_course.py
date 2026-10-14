# SPDX-License-Identifier: MIT
# Copyright (c) 2026 ModdedBench contributors
"""Route rules live (mb_route_rules / nav.route_rules), through the model's own tools on the combat stack's box:
a walk past a hole in the floor without and with a drop rule, a never box set while a walk runs, the built-in rules,
and the old avoidance setting refused with a pointer to its rule.

    MBTEST_SERVER=mbcombat-server-1 MBTEST_IMAGE=mbcombat-agent:latest bash harness/smoke/mbtest.sh harness/smoke/route_rules_course.py [--trials 2]

Evidence: .runtime/evidence/route-rules-course.json, and a line per check on stdout.
"""
from __future__ import annotations

import argparse
import json
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from combat_course import AIR, ARENA, FIX, FLOOR, START, X0, Z0, Combat, batches, mc, told, work  # noqa: E402
from movement_course import BridgeError, Kernel  # noqa: E402

OUT = mc.ROOT / ".runtime" / "evidence" / "route-rules-course.json"
HOLE = [(AIR, (4, -1, 1), (14, -1, 1))]   # a hole in the floor beside the straight way, open to the sky below the box
EDGES = {"name": "edges", "where": {"drop": {"depth": 4, "within": 1}}, "factor": 10}
WALL = {"name": "wall", "where": {"box": {"min": [X0 + 15, FLOOR - 1, Z0 - 3], "max": [X0 + 15, FLOOR + 2, Z0 + 3]}}, "never": True}


class Routes(Combat):
    def rules(self, op: str = "get", **kw) -> dict:
        return work.mb_route_rules(op, **kw)

    def walk(self, dx: int, during=None) -> tuple[dict, list]:
        """From START along +x to X0+dx, with the client's [x, z] every 0.1 s; during = (x, fn(kernel)) runs fn once
        beside the walk when the player first passes x."""
        self.quiet(); self.s.call(FIX + ".arena", **ARENA)
        for boxes in batches(HOLE): self.s.call(FIX + ".fill", boxes=boxes)
        self.s.call(FIX + ".reset", empty=True, at=list(START), yaw=-90)
        time.sleep(1)
        trace, done, side = [], threading.Event(), Kernel(timeout=30)

        def watch():
            fired = False
            while not done.is_set():
                try:
                    p = side.call("obs.player")["pos"]; trace.append([round(p[0], 2), round(p[2], 2)])
                    if during and not fired and p[0] >= during[0]: fired = True; trace.append(["changed", during[1](side)])
                except BridgeError as e: trace.append(["error", e.msg[:200]])
                time.sleep(.1)
        t = threading.Thread(target=watch, daemon=True); t.start()
        try:
            receipt = told(lambda: work.mb_process("goal", goal={"type": "block", "pos": [X0 + dx, FLOOR, Z0]}, duration_ticks=1200, timeout_s=90))
        finally:
            done.set(); t.join(2); side.close()
        receipt.setdefault("state", "succeeded")
        return receipt, trace


def beside_hole(trace: list) -> int:
    """Samples in the row next to the hole (z cell Z0) over its length."""
    return sum(1 for p in trace if isinstance(p[0], float) and X0 + 6 <= p[0] < X0 + 13 and int(p[1] // 1) == Z0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--trials", type=int, default=2)
    ap.add_argument("--keep", action="store_true", help="leave the box standing")
    ap.add_argument("--server-host", default="127.0.0.1")
    args = ap.parse_args(); args.seed, args.avoidance, args.guards, args.one_by_one = 1, False, False, False
    t = Routes(args); t.setup()
    checks: list[dict] = []

    def check(name: str, ok: bool, **facts):
        checks.append({"check": name, "ok": bool(ok), **facts})
        print(f"{'PASS' if ok else 'FAIL'} {name} {json.dumps(facts)[:600]}", flush=True)
    try:
        got = t.rules()
        check("builtins", [r["name"] for r in got["rules"]] == ["mobs", "spawners", "underwater", "current_to_harm"]
              and [r["enabled"] for r in got["rules"]] == [False, False, True, True], rules=got["rules"], settings=got["settings"])
        try:
            t.c.call("nav.settings", operation="set", values={"avoidance": True}, save=False); check("avoidance_refused", False)
        except BridgeError as e:
            check("avoidance_refused", "mobs and spawners" in e.msg, error=e.msg)
        for trial in range(args.trials):
            t.rules("reset")
            r, trace = t.walk(18)
            check(f"no_rule_walks_by_the_hole[{trial}]", r.get("state") == "succeeded" and beside_hole(trace) > 0,
                  state=r.get("state"), reason=r.get("reason"), beside=beside_hole(trace), pathRules=r.get("pathRules"))
            t.rules("set", rules=[EDGES])
            r, trace = t.walk(18)
            routes = (r.get("pathRules") or {}).get("routes") or {}
            check(f"drop_rule_keeps_off_the_edge[{trial}]", r.get("state") == "succeeded" and beside_hole(trace) == 0 and routes.get("edges", 0) > 0,
                  state=r.get("state"), reason=r.get("reason"), beside=beside_hole(trace), routes=routes)
            t.rules("remove", names=["edges"])
            r, trace = t.walk(30, during=(X0 + 5, lambda k: k.call("nav.route_rules", operation="set", rules=[WALL]).get("replanned")))
            # A rule tests the cell a move ends in, so a diagonal step may pass the box's corner point: only a centre inside counts.
            walked = [p for p in trace if isinstance(p[0], float)]
            crossed = [p for p in walked if X0 + 15 < p[0] < X0 + 16 and Z0 - 3 < p[1] < Z0 + 4]
            touched = [p for p in walked if X0 + 15 <= p[0] <= X0 + 16 and Z0 - 3 <= p[1] <= Z0 + 4 and p not in crossed]
            changed = [p[1] for p in trace if p[0] == "changed"]
            check(f"never_set_mid_walk_replans[{trial}]", r.get("state") == "succeeded" and not crossed and changed == [True],
                  state=r.get("state"), reason=r.get("reason"), replanned=changed, crossed=crossed[:5], cornerTouches=touched[:5],
                  routes=(r.get("pathRules") or {}).get("routes"))
            t.rules("remove", names=["wall"])
    finally:
        try: t.rules("reset")
        except Exception as e: print("reset failed:", e)
        t.teardown()
        t.evidence.update(ok=all(c["ok"] for c in checks), checks=checks)
        OUT.parent.mkdir(parents=True, exist_ok=True); OUT.write_text(json.dumps(t.evidence, indent=1))
    print(f"{sum(c['ok'] for c in checks)}/{len(checks)} checks passed", flush=True)
    sys.exit(0 if checks and all(c["ok"] for c in checks) else 1)


if __name__ == "__main__":
    main()
