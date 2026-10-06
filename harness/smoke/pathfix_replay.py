# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Live replay of the pathfix branch's cases in a CLONE of the played world (no fixture is built, no block is changed).

The player is teleported to each case's start (dev.replay.place), guards are switched off as the movement course does,
the search timeouts are set through nav.settings (the model's mb_settings path) and the case's call is issued through
the model's own tool (mb_process). What is at the start and the goal cells is read from the server first and recorded,
since the clone may differ from the snapshot the case came from. Run it from a container on the server's network:

    bash harness/smoke/mbtest.sh harness/smoke/pathfix_replay.py --list
    bash harness/smoke/mbtest.sh harness/smoke/pathfix_replay.py --case c_cobble_48_67_89 --trials 3 --snapshot 20261001-143459
    bash harness/smoke/mbtest.sh harness/smoke/pathfix_replay.py --group A --snapshot 20261001-113831

Groups: A tunnel legs that searched slowly (class a), B plan-while-paused A/B (unpaused vs paused + _resume),
C goal_not_standable and failure labels (class b), D a long xz goal, S the movement snags (each from its own snapshot,
started at the snag's exact position). The movement course's fixture regressions
(pit/corner loops) need the fixture world and are not replayed here.
Evidence: .runtime/evidence/pathfix-<case>-<utc>.json per case, and a summary table on stdout.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import movement_course as mc  # noqa: E402  (Kernel, Sampler, perf, log counts, cost fields, with_resume)
from movement_course import BridgeError, Course, Sampler, cost_fields, log_counts, log_offset, with_resume, work  # noqa: E402

mc.LOG_PATTERNS["impossible"] = r"has become impossible"
OUT = mc.ROOT / ".runtime" / "evidence"
TIMEOUTS = {"primaryTimeoutMS": 500, "failureTimeoutMS": 2000, "planAheadPrimaryTimeoutMS": 4000, "planAheadFailureTimeoutMS": 5000}
SNAP_A, BASE = "20261001-113831", [52, 66, -85]
TUNNEL = dict(group="A", snapshot=SNAP_A, allow=(True, True), expect="arrive")
# start: feet cell. goal: the mb_process goal. expect: arrive | refuse | label | ab. cells: what the case's snapshot had
# there ({"x,y,z": block}); a clone that differs makes the expectation unverifiable. settings: nav.settings over TIMEOUTS.
CASES: dict[str, dict] = {
    # 113831 was taken before the dig: these chain from the cave the agent dug from (18:42), each leg starting where the last ended.
    "a_cave_96": dict(TUNNEL, utc="18:42:08..18:46", start=[34, 39, -186], goal={"type": "block", "pos": [96, 38, -199]}, duration=4000, stall=600),
    "a_near_120": dict(TUNNEL, utc="18:46:24", start=[96, 38, -199], goal={"type": "near", "pos": [120, 38, -200], "radius": 3}, duration=1000),
    "a_block_104": dict(TUNNEL, utc="18:46:37", start=[96, 38, -199], goal={"type": "block", "pos": [104, 38, -200]}, duration=1000),
    "a_down_120_223": dict(TUNNEL, utc="18:48..18:52", start=[120, 38, -200], goal={"type": "block", "pos": [120, 35, -223]}, duration=2000, stall=600),
    "a_stall_120_248": dict(TUNNEL, utc="18:52:13/18:53:25", start=[120, 35, -223], goal={"type": "block", "pos": [120, 35, -248]}, duration=1600, stall=200,
                            settings={"planAheadPrimaryTimeoutMS": 8000, "planAheadFailureTimeoutMS": 15000}, no_stall=True),
    "a_deep_72": dict(TUNNEL, utc="19:00:59", start=[72, 12, -250], goal={"type": "block", "pos": [72, 12, -296]}, duration=2400, stall=600),
    "b_tunnel_ab": dict(group="B", utc="18:52:13 leg", snapshot=SNAP_A, start=[120, 35, -223], goal={"type": "block", "pos": [120, 35, -248]},
                        allow=(True, True), duration=1600, expect="ab"),
    "b_surface_ab": dict(group="B", utc="base, starts of 19:32:34 and 02:38:00", snapshot="any", start=BASE, goal={"type": "block", "pos": [72, 65, -50]},
                         allow=(True, True), duration=1200, expect="ab"),
    "c_cobble_48_67_89": dict(group="C", utc="19:31:47", snapshot="20261001-120846", start=[51, 66, -86], goal={"type": "block", "pos": [48, 67, -89]},
                              allow=(False, True), override=True, duration=600, expect="refuse", why="no_room_for_the_body",
                              cells={"48,67,-89": "minecraft:cobblestone", "48,68,-89": "minecraft:cobblestone"}),
    "c_dirt_54_66_89": dict(group="C", utc="19:53:04", snapshot="20261001-123900", start=[51, 66, -86], goal={"type": "block", "pos": [54, 66, -89]},
                            duration=600, expect="refuse", why="no_room_for_the_body",
                            cells={"54,66,-89": "minecraft:dirt", "54,67,-89": "minecraft:cobblestone"}),
    "c_air_49_67_89": dict(group="C", utc="19:33:37", snapshot="20261001-120846", start=[49, 66, -90], goal={"type": "block", "pos": [49, 67, -89]},
                           override=True, duration=100, expect="refuse", why="nothing_to_stand_on",
                           cells={"49,67,-89": "minecraft:air", "49,68,-89": "minecraft:air", "49,66,-89": "minecraft:air"}),
    "c_label_48_69_82": dict(group="C", utc="06:34:24", snapshot="20260930-232051", start=[47, 68, -82], goal={"type": "block", "pos": [48, 69, -82]},
                             duration=120, expect="label", cells={"48,69,-82": "minecraft:air", "48,70,-82": "minecraft:air", "48,68,-82": "minecraft:cobblestone"}),
    "d_far_xz": dict(group="D", utc="new", snapshot="any", start=BASE, goal={"type": "xz", "x": BASE[0] - 400, "z": BASE[2]}, allow=(True, True), duration=12000, expect="arrive"),
}

# Group S: the movement snags the agent hit (receipt snag: movement_timeout), each from the snapshot nearest it (some just after). at: the exact
# position the snag recorded (the player is put there, not at a cell centre); edge: the snagged movement, src -> dest. The goal is
# the edge's dest, so the planner meets the same step; region: a region-only extract laid over the full snapshot.
def snag(utc, snapshot, at, edge, allow=(True, True), region=None, goal=None, **kw):
    return dict(group="S", utc=utc, snapshot=snapshot, region=region, start=[int(v // 1) for v in at], at=at, edge=edge,
                goal=goal or {"type": "block", "pos": edge[1]}, allow=allow, duration=400, expect="arrive", **kw)


CASES.update({
    "s_trav_49_66_87": snag("02:03:27", "20260930-191308", [49.543, 67.128, -86.663], [[49, 66, -87], [49, 66, -88]]),
    "s_trav_48_66_84": snag("02:07:22", "20260930-191308", [49.3, 66.0, -83.642], [[49, 66, -84], [48, 66, -84]]),
    "s_ascend_44_69_85": snag("03:35:56", "20260930-203840", [45.3, 68.938, -84.043], [[45, 68, -85], [44, 69, -85]]),
    "s_descend_47_68_85": snag("03:36:47", "20260930-203840", [47.498, 69.222, -84.52], [[46, 69, -85], [47, 68, -85]]),
    "s_trav_64_65_55": snag("04:03:49", "20260930-210855", [64.387, 64.0, -54.5], [[63, 65, -55], [64, 65, -55]]),
    "s_trav_63_66_19": snag("04:05:51", "20260930-210855", [63.519, 66.0, -18.702], [[63, 66, -20], [63, 66, -19]]),
    "s_down_41_68_83": snag("04:36:11", "20260930-211952", [41.504, 69.627, -82.512], [[41, 69, -83], [41, 68, -83]], allow=(False, False),
                            goal={"type": "near", "pos": [16, 63, -72], "radius": 3}),
    "s_descend_13_61_71": snag("04:38:11", "20260930-211952", [13.712, 62.02, -70.529], [[14, 62, -71], [13, 61, -71]]),
    "s_trav_49_65_64": snag("18:07:59", "20261001-110816", [49.5, 65.0, -63.298], [[49, 65, -63], [49, 65, -64]]),
    "s_ascend_54_56_117": snag("19:28:18", "20261001-123900", [54.531, 55.0, -117.64], [[54, 55, -118], [54, 56, -117]]),
    "s_trav_49_64_70": snag("20:30:11", "20261001-133930", [49.561, 64.0, -68.32], [[49, 64, -69], [49, 64, -70]]),
    "s_trav_49_64_62": snag("22:20:24", "20261001-153530", [48.7, 64.0, -61.405], [[48, 64, -62], [49, 64, -62]]),
})
REFUSED_LABELS = ("no_route_to_goal", "no_route_in_loaded_chunks", "search_failed_timeout")


def goal_cells(goal: dict) -> list[list[int]]:
    p = goal.get("pos")
    return [] if p is None else [[p[0], p[1] + dy, p[2]] for dy in (-1, 0, 1)]


def line_cells(a: list[int], b: list[int], step: int = 4) -> list[list[int]]:
    """Feet and head cells every `step` blocks along the straight leg: whether a tunnel is already dug in this clone."""
    n = max(abs(b[0] - a[0]), abs(b[2] - a[2])) // step
    return [[round(a[0] + (b[0] - a[0]) * i / max(1, n)), a[1] + dy, round(a[2] + (b[2] - a[2]) * i / max(1, n))] for i in range(n + 1) for dy in (0, 1)][:60]


class Replay(Course):
    def __init__(self, args):
        super().__init__(args)
        # The tools must act through the session that owns the clock, or a _resume is refused as another agent's.
        mc.mbtool.set_kernel_factory(lambda: self.c)
        self.evidence = {"snapshotRestored": args.snapshot, "started": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}

    def setup(self):
        self.ensure_world()
        state = self.c.call("time.status")["state"]
        self.previous_clock = state.get("conditions")
        self.c.call("time.configure", healthDrop=False, healthBelow=-1, airBelow=-1, foodBelow=-1, burning=False,
                    threatWithin=-1, actionFailed=False, pauseOnDisconnect=False)
        if state.get("paused"): self.c.call("time.resume")
        try: self.c.call("act.stop")
        except BridgeError: pass
        rows = work.mb_settings("get", "")["settings"]
        self.settings = {r["name"]: r["value"] for r in rows}
        self.previous_timeouts = {k: self.settings.get(k) for k in [*TIMEOUTS, "movementTrace"]}
        self.player0 = self.s.call("dev.replay.status")
        if self.player0.get("dead") or not self.player0.get("health"):  # a restored world can leave the player on the death screen
            self.c.call("gui.button", index=0)
            until = time.monotonic() + 20
            while time.monotonic() < until and not self.s.call("dev.replay.status").get("health"): time.sleep(.5)
            self.player0 = self.s.call("dev.replay.status")
        print(f"player {self.player0['name']} at {[round(v, 2) for v in self.player0['pos']]}, health {self.player0['health']}, "
              f"inventory {[(i['slot'], i['id'], i['count']) for i in self.player0['inventory']]}", flush=True)

    def teardown(self):
        for step in (lambda: self.c.call("act.stop"),
                     lambda: self.c.call("time.resume") if self.c.call("time.status")["state"].get("paused") else None,
                     lambda: work.mb_settings("set", values={k: v for k, v in self.previous_timeouts.items() if v is not None}),
                     lambda: self.c.call("time.configure", **{**(self.previous_clock or {}), "pauseOnDisconnect": False})):
            try: step()
            except Exception as e: self.evidence.setdefault("teardownErrors", []).append(str(e))

    def place(self, spec: dict) -> dict:
        if self.args.from_here:  # chained legs: the previous leg's end is the start the agent really had
            return {"startActual": self.c.call("obs.player")["pos"]}
        if spec.get("at") and not self.args.cell_start: return self.place_exact(spec["at"])
        x, y, z = spec["edge"][0] if spec.get("at") else spec["start"]
        rows = self.s.call("dev.replay.status", cells=[[x, y, z], [x, y + 1, z]])["cells"]
        body = {",".join(map(str, c["pos"])): c["block"] for c in rows}
        if any(c.get("boxes") for c in rows):  # a start inside rock suffocates the player: it was dug after the snapshot
            raise RuntimeError(f"start {spec['start']} is not open in this clone: {body}")
        for attempt in range(3):
            self.s.call("dev.replay.place", x=x + .5, y=y, z=z + .5, yaw=0)
            until, placed = time.monotonic() + 15, False
            while time.monotonic() < until and not placed:
                try: p = self.c.call("obs.player")["pos"]
                except BridgeError: time.sleep(.5); continue
                placed = abs(p[0] - x - .5) < .05 and abs(p[2] - z - .5) < .05 and abs(p[1] - y) < .6
                if not placed: time.sleep(.2)
            if placed: break
            self.resync()
        else:
            raise RuntimeError(f"client never reached the start {spec['start']}")
        tick = self.c.call_reply("obs.player").tick
        while self.c.call_reply("obs.player").tick < tick + 40: time.sleep(.05)   # the client's chunks round a far start
        return {"startActual": self.c.call("obs.player")["pos"]}

    def place_exact(self, at: list) -> dict:
        """A snag's own position: the player is put exactly there (it may stand on a partial block), then settles."""
        for attempt in range(3):
            self.s.call("dev.replay.place", x=at[0], y=at[1], z=at[2], yaw=0)
            until, placed = time.monotonic() + 15, False
            while time.monotonic() < until and not placed:
                try: p = self.c.call("obs.player")["pos"]
                except BridgeError: time.sleep(.5); continue
                placed = abs(p[0] - at[0]) < .3 and abs(p[2] - at[2]) < .3 and abs(p[1] - at[1]) < .6  # water drifts the player
                if not placed: time.sleep(.2)
            if placed: break
            self.resync()
        else:
            raise RuntimeError(f"client never reached {at}")
        tick = self.c.call_reply("obs.player").tick
        while self.c.call_reply("obs.player").tick < tick + 40: time.sleep(.05)
        return {"startActual": self.c.call("obs.player")["pos"]}

    def probe(self, spec: dict) -> list[dict]:
        """Every non-air cell round a snag's edge (one block out, from below the floor to above the head), with its boxes."""
        a, b = spec["edge"]
        cells = [[x, y, z] for x in range(min(a[0], b[0]) - 1, max(a[0], b[0]) + 2) for z in range(min(a[2], b[2]) - 1, max(a[2], b[2]) + 2)
                 for y in range(min(a[1], b[1]) - 1, max(a[1], b[1]) + 3)]
        out = []
        for i in range(0, len(cells), 64):
            out += [c for c in self.s.call("dev.replay.status", cells=cells[i:i + 64])["cells"] if c["block"] != "minecraft:air"]
        return out

    def world_check(self, spec: dict) -> dict:
        cells = goal_cells(spec["goal"]) + [[spec["start"][0], spec["start"][1] + dy, spec["start"][2]] for dy in (-1, 0, 1)]
        if spec["group"] in ("A", "B") and spec["goal"].get("pos"): cells += line_cells(spec["start"], spec["goal"]["pos"])
        seen = {",".join(map(str, c["pos"])): c["block"] for c in self.s.call("dev.replay.status", cells=cells[:64])["cells"]}
        differs = {k: {"expected": v, "found": seen.get(k)} for k, v in (spec.get("cells") or {}).items() if seen.get(k) != v}
        warn = spec["snapshot"] not in ("any", self.args.snapshot)
        out = {"cells": seen, "differs": differs, "snapshotMismatch": warn}
        if spec.get("edge"): out["probe"] = self.probe(spec)
        return out

    def settings_for(self, spec: dict):
        work.mb_settings("set", values={**TIMEOUTS, **(spec.get("settings") or {}), **({"movementTrace": True} if self.args.trace else {})})

    def call(self, spec: dict, paused: bool) -> tuple[str, dict, float]:
        brk, plc = spec.get("allow", (False, False))
        duration = spec["duration"]
        kw = dict(goal=spec["goal"], duration_ticks=duration, allow_break=brk, allow_place=plc,
                  override_protection=spec.get("override", False), timeout_s=duration / 20 + 60)
        if spec.get("stall") is not None: kw["stall_ticks"] = spec["stall"]
        t = time.monotonic()
        try:
            if paused:
                self.c.call("time.pause", reason="pathfix replay: plan while paused")
                r = with_resume(True, work.mb_process, "goal", **kw)
            else:
                r = work.mb_process("goal", **kw)
            return "ok", r, time.monotonic() - t
        except BridgeError as e:
            err = (e.reply or {}).get("error") or {}
            receipt = err.get("receipt") if isinstance(err.get("receipt"), dict) else {}
            return "error", {**receipt, "errorCode": e.code, "errorMsg": e.msg}, time.monotonic() - t
        except Exception as e:
            return "exception", {"errorMsg": f"{type(e).__name__}: {e}"}, time.monotonic() - t

    def attempt(self, name: str, spec: dict, paused: bool = False) -> dict:
        info = self.place(spec)
        self.settings_for(spec)
        before = self.s.call("dev.replay.status")
        seen = max([r.get("n", 0) for r in (self.c.call("nav.status", trace=True).get("trace") or [])] or [0]) if self.args.trace else 0
        offset = log_offset()
        sampler = Sampler(); sampler.start()
        kind, receipt, wall = self.call(spec, paused)
        time.sleep(.3)
        perf = sampler.finish()
        try: self.c.call("act.stop")
        except BridgeError: pass
        after = self.s.call("dev.replay.status")
        clock = self.c.call("time.status")
        trace = [r for r in (self.c.call("nav.status", trace=True).get("trace") or []) if r.get("n", 0) > seen] if self.args.trace else []
        failure = receipt.get("failure") or {}
        last = failure.get("lastCalculation") or {}
        search = last.get("search") or {}
        nodes, elapsed = search.get("nodes"), last.get("elapsedMs")
        cost = receipt.get("cost") or {}
        goal = spec["goal"].get("pos")
        return {
            "paused": paused, "startActual": info["startActual"], "state": receipt.get("state") or ("succeeded" if kind == "ok" else "failed"),
            "reason": receipt.get("reason") or receipt.get("errorMsg"), "ticks": receipt.get("ticks"), "wallS": round(wall, 2),
            "final": [round(v, 3) for v in after["pos"]], "distanceToGoal": None if goal is None else round(sum((a - b - (.5 if i != 1 else 0)) ** 2 for i, (a, b) in enumerate(zip(after["pos"], goal))) ** .5, 2),
            "healthBefore": before["health"], "healthAfter": after["health"], "dead": after["dead"],
            "why": failure.get("why"), "obstructions": failure.get("obstructions"), "below": failure.get("below"), "goalLoaded": failure.get("goalLoaded"),
            "search": {"why": search.get("why"), "nodes": nodes, "elapsedMs": elapsed, "usPerNode": round(elapsed * 1000 / nodes, 1) if nodes and elapsed else None},
            "cost": cost, "searches": cost.get("searches"), "stall": receipt.get("stall"),
            "firstTick": {k: receipt.get(k) for k in ("searchStartedPaused", "planReadyAtFirstTick", "firstPathTick", "firstMovedTick")},
            "planWhilePaused": (clock.get("planWhilePaused") or (clock.get("state") or {}).get("planWhilePaused")),
            "movementTypes": receipt.get("movementTypes"), "log": log_counts(offset), "perf": perf, "baritoneCost": cost_fields(receipt) or None,
            "receipt": {k: v for k, v in receipt.items() if k not in ("notes",)}, "snags": find_key(receipt, "snag"), "trace": trace,
        }

    def judge(self, spec: dict, r: dict, world: dict) -> tuple[str, list[str]]:
        f, ok = [], r["state"] == "succeeded"
        if r["dead"] or r["healthAfter"] < r["healthBefore"] - 1e-6: f.append(f"health {r['healthBefore']}->{r['healthAfter']}{' (died)' if r['dead'] else ''}")
        if r["log"] and r["log"]["spam"]: f.append(f"log spam: {r['log']['unreachable']} UNREACHABLE, {r['log']['plans']} plans")
        e = spec["expect"]
        if e in ("arrive", "ab") and not ok: f.append(f"did not arrive: {r['reason']} after {r['ticks']} ticks")
        if spec.get("no_stall") and str(r["reason"]).startswith("stalled_no_progress"): f.append("stalled while searching")
        if spec.get("no_stall") and "searchTicksExcused" not in (r["stall"] or {}): f.append("stall status lacks searchTicksExcused")
        if e == "refuse":
            if ok: f.append("arrived where it should have refused")
            elif r["reason"] != "goal_not_standable" or r["why"] != spec["why"]: f.append(f"not refused as {spec['why']}: {r['reason']} / {r['why']}")
            elif (r["ticks"] or 0) > mc.REFUSE_TICKS or r["wallS"] > mc.REFUSE_WALL_S: f.append(f"refusal slow: {r['ticks']} ticks / {r['wallS']} s")
            elif spec["why"] == "no_room_for_the_body" and not r["obstructions"]: f.append("obstruction not named")
        if e == "label" and not ok and r["reason"].split(";")[0] not in REFUSED_LABELS + ("goal_not_standable",): f.append(f"label {r['reason']}")
        verdict = "unverifiable" if world["differs"] and e in ("refuse", "label") else "pass" if not f else "fail"
        return verdict, f

    def run(self):
        names = [n for n, s in CASES.items() if (not self.args.case or n in self.args.case) and (not self.args.group or s["group"] in self.args.group)]
        if self.args.all: names = list(CASES)
        if not names: raise SystemExit("no case selected: --case NAME, --group A|B|C|D or --all; --list shows them")
        self.setup()
        try:
            if not self.args.no_idle:
                self.place(CASES[names[0]]); sampler = Sampler(); sampler.start(); time.sleep(10)
                self.evidence["idle"] = sampler.finish(); print("idle", json.dumps(self.evidence["idle"]), flush=True)
            for name in names: self.case(name)
        finally:
            self.teardown()

    def case(self, name: str):
        spec = CASES[name]
        world = self.world_check(spec)
        if world["snapshotMismatch"]: print(f"WARNING {name}: case comes from snapshot {spec['snapshot']}, this clone is {self.args.snapshot or 'unspecified'}", flush=True)
        if world["differs"]: print(f"WARNING {name}: clone differs from the case's snapshot at {world['differs']}: expectation unverifiable", flush=True)
        if world.get("probe"):
            print(f"{name} edge {spec['edge']} at {spec.get('at')}:", flush=True)
            for c in world["probe"]: print(f"   {c['pos']} {c['block']}:{c['meta']} boxes={c.get('boxes')}", flush=True)
        ev = {"case": name, "spec": spec, "world": world, "player": self.player0, "settings": self.settings, "idle": self.evidence.get("idle"),
              "snapshotRestored": self.args.snapshot, "trials": []}
        for i in range(self.args.trials):
            arms = (False, True) if spec["expect"] == "ab" else (False,)
            for paused in arms:
                try:
                    r = self.attempt(name, spec, paused)
                    r["verdict"], r["failures"] = self.judge(spec, r, world)
                    self.costed(f"{name}[{i}]{' paused' if paused else ''}", r, "verdict")
                except Exception as e:
                    r = {"paused": paused, "verdict": "fail", "failures": [f"harness error: {type(e).__name__}: {e}"]}
                r["trial"] = i; ev["trials"].append(r)
                if r.get("snags"): print(f"   snags: {json.dumps(r['snags'])[:1500]}", flush=True)
                if r.get("trace"): print(render_trace(r["trace"], self.args.trace), flush=True)
                print(f"{name}[{i}]{' paused' if paused else ''} {r['verdict'].upper()} {r.get('state', '')} {r.get('reason') or ''} ticks={r.get('ticks')} "
                      f"wall={r.get('wallS')}s search={r.get('search')} first={r.get('firstTick')} {'; '.join(r['failures'])}", flush=True)
        ev["summary"] = summarize(spec, ev["trials"])
        print(f"== {name}: {json.dumps(ev['summary'])}", flush=True)
        OUT.mkdir(parents=True, exist_ok=True)
        (OUT / f"pathfix-{name}-{time.strftime('%Y%m%dT%H%M%SZ', time.gmtime())}.json").write_text(json.dumps(ev, indent=1, default=str))


def render_trace(rows: list[dict], limit: int) -> str:
    """The movement trace as text, runs of identical ticks (same movement, inputs, feet, flags) folded into one line."""
    out, run = [], None
    def key(r): return (r.get("event"), r.get("mv"), str(r.get("src")), str(r.get("dest")), r.get("status"), str(r.get("in")), str(r.get("feet")),
                        r.get("ground"), r.get("collided"), r.get("sneaking"), str(r.get("hit")))
    for r in rows:
        if run and key(r) == key(run[0]): run.append(r); continue
        if run: out.append((run[0], run[-1], len(run)))
        run = [r]
    if run: out.append((run[0], run[-1], len(run)))
    lines = []
    for a, b, n in out:
        if a.get("event"):
            lines.append(f"   #{a['n']} EVENT {a['event']} {json.dumps({k: v for k, v in a.items() if k not in ('n', 'event', 'touching')})[:300]} touching={json.dumps(a.get('touching'))[:300]}")
            continue
        flags = "".join(c for c, f in (("G", a.get("ground")), ("C", a.get("collided")), ("S", a.get("sneaking"))) if f)
        lines.append(f"   #{a['n']}{'' if n == 1 else '..' + str(b['n'])} x{n} {a['mv']} {a['src']}->{a['dest']} {a['status']} feet={a['feet']} "
                     f"pos={a['pos']}{'' if n == 1 else '->' + str(b['pos'])} [{flags}] in={','.join(a['in'])} aim={a.get('aim')} look={b.get('look')} hit={a.get('hit')}")
    return "\n".join(lines[-limit:])


def find_key(v, key: str) -> list:
    """Every value under `key` anywhere in a receipt (a snag may sit in the failure or in a movement record)."""
    if isinstance(v, dict): return ([v[key]] if v.get(key) else []) + [x for k, w in v.items() if k != key for x in find_key(w, key)]
    if isinstance(v, list): return [x for w in v for x in find_key(w, key)]
    return []


def summarize(spec: dict, trials: list[dict]) -> dict:
    def med(rows, get):
        vals = sorted(v for v in (get(t) for t in rows) if isinstance(v, (int, float)))
        return vals[len(vals) // 2] if vals else None
    out = {"verdicts": {v: sum(t.get("verdict") == v for t in trials) for v in ("pass", "fail", "unverifiable")},
           "reasons": sorted({str(t.get("reason")) for t in trials})}
    for label, rows in (("unpaused", [t for t in trials if not t.get("paused")]), ("paused", [t for t in trials if t.get("paused")])):
        if not rows: continue
        out[label] = {"ticks": med(rows, lambda t: t.get("ticks")), "firstMovedTick": med(rows, lambda t: (t.get("firstTick") or {}).get("firstMovedTick")),
                      "firstPathTick": med(rows, lambda t: (t.get("firstTick") or {}).get("firstPathTick")),
                      "searchMsMax": med(rows, lambda t: (t.get("cost") or {}).get("searchMsMax")), "searches": med(rows, lambda t: t.get("searches")),
                      "tickNsMax": med(rows, lambda t: (t.get("cost") or {}).get("tickNsMax")), "pausedNsMax": med(rows, lambda t: (t.get("cost") or {}).get("pausedNsMax")),
                      "usPerNode": med(rows, lambda t: (t.get("search") or {}).get("usPerNode")), "clientTps": med(rows, lambda t: (t.get("perf") or {}).get("clientTps")),
                      "impossible": med(rows, lambda t: (t.get("log") or {}).get("impossible")), "plans": med(rows, lambda t: (t.get("log") or {}).get("plans"))}
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--case", action="append", help="case name (repeatable)")
    ap.add_argument("--group", action="append", choices=["A", "B", "C", "D", "S"])
    ap.add_argument("--trace", type=int, default=0, help="record the movement trace and print its last N folded lines")
    ap.add_argument("--cell-start", action="store_true", help="S cases: start at the snag's cell centre instead of its exact position")
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--trials", type=int, default=3)
    ap.add_argument("--snapshot", default="", help="the snapshot this clone was restored from (warns on cases from another)")
    ap.add_argument("--no-idle", action="store_true", help="skip the idle performance baseline")
    ap.add_argument("--server-host", default="127.0.0.1", help="the server address as the client sees it, to rejoin")
    ap.add_argument("--from-here", action="store_true", help="start from the player's position instead of the case's start (chained legs)")
    ap.add_argument("--list", action="store_true"); mc.tick_cost.argument(ap)
    args = ap.parse_args()
    args.seed = 1
    if args.list:
        for n, s in CASES.items(): print(f"{n:20} {s['group']} {s['expect']:7} {s['utc']:30} snapshot {s['snapshot']}  {json.dumps(s['goal'])} from {s['start']}")
        return 0
    Replay(args).run()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
