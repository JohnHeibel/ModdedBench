# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Deterministic movement and flowing-liquid course, run through the model's own tools (mb_process, mb_mine, mb_act).

Each case is rebuilt from scratch by dev.movement_fixture.position before every trial, and the player starts from a
random yaw. Per trial: ticks, outcome and reason, health, deviation from the expected route, final position, client
log spam (UNREACHABLE, re-plans), and performance (client and server ticks per second, the worst client-thread gap,
bridge latency, any Baritone cost fields in the receipt) against an idle baseline taken in the same world.
Needs the throwaway mbtest stack; run it from a container on the server's network:

    bash harness/smoke/mbtest.sh harness/smoke/movement_course.py [--case NAME ...] [--trials 5] [--seed 1]

Evidence: .runtime/evidence/movement-course.json, and a summary table on stdout.
"""
from __future__ import annotations

import argparse
import contextvars
import json
import math
import os
import random
import re
import statistics
import sys
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "harness" / "mcp"))
from kernel import BridgeError, Kernel, bridge_url, resume_once  # noqa: E402
import mbtool  # noqa: E402,F401  (installs the mbtools_gtnh package)
from mbtools_gtnh import core, work  # noqa: E402

OUT = ROOT / ".runtime" / "evidence" / "movement-course.json"
FIX = "dev.movement_fixture"
REFUSE_TICKS, REFUSE_WALL_S = 100, 5.0            # a refusal must come within 5 s, never by stall or timeout
GENERIC = ("source_process_stopped", "timeout", "cancelled", "stalled_no_progress", "request deadline")
PERF_MIN_TPS_RATIO, PERF_MAX_GAP_MS = 0.9, 250.0
LOG_PATTERNS = {
    "unreachable": r"Movement returns status UNREACHABLE",
    "failedMovement": r"Movement returns status FAILED",
    "plans": r"Starting to search for path",
    "cancelling": r"Cancelling",
    "wrongY": r"Wrong Y",
    "noProgress": r"couldn't get more than",
    "tooLong": r"has taken too long",
}

# expect: succeed (every trial arrives), refuse (fails fast with a named cause), either (arrives or refuses fast),
# script (a scripted primitive sequence). bound: a sensible tick count; a trial passes within 1.5x of it.
# max_dev: blocks from the expected route (xz). allow: (break, place). settle: ticks to wait after placement.
CASES: dict[str, dict] = {
    "corner_l": dict(expect="succeed", bound=60, max_dev=1.0),
    **{f"diag_{k}": dict(expect="succeed", bound=35, max_dev=1.6) for k in ("fence", "wall", "pane", "slab", "trapdoor", "pack0", "pack1", "pack2", "pack3")},
    **{f"inside_{k}": dict(expect="succeed", bound=45, max_dev=1.0) for k in ("fence", "wall", "pane", "pack0")},
    **{f"strip_{k}": dict(expect="succeed", bound=85, max_dev=4.0) for k in ("thin0", "thin1", "low0")},
    "pit_loop": dict(expect="succeed", bound=70, max_dev=1.5),
    "pit_trapped": dict(expect="refuse"),
    "edge_start": dict(expect="succeed", bound=35, max_dev=1.5),
    "edge_start_across": dict(expect="succeed", bound=45, max_dev=2.5),
    "shaft_jam": dict(expect="succeed", bound=160, allow=(True, True)),
    "stairs_up": dict(expect="succeed", bound=60, max_dev=1.5),
    "stairs_down": dict(expect="succeed", bound=50, max_dev=1.5),
    "door_wood": dict(expect="succeed", bound=40, max_dev=1.0),
    "door_iron": dict(expect="refuse"),
    "gate": dict(expect="succeed", bound=40, max_dev=1.0),
    "gravel_lava": dict(expect="either", bound=80, allow=(True, False)),
    "underwater_corner": dict(expect="succeed", bound=90),
    "lava_approach": dict(expect="succeed", bound=70, max_dev=2.0, change_at_start="lava_approach_open"),
    "lava_approach_mid": dict(expect="succeed", bound=70, max_dev=2.0, change_at_u=8, change="lava_approach_open"),
    "mine_lava_beside": dict(expect="either", bound=200, mine=True),
    "mine_lava_pocket": dict(expect="either", bound=200, mine=True),
    "water_current": dict(expect="succeed", bound=90, max_dev=3.5, settle=60),  # a planned detour around the spread is fine; being pushed off is not (see replans)
    "water_stream_down": dict(expect="succeed", bound=80, max_dev=1.0, settle=60),
    "water_hole_climb_wet": dict(expect="succeed", bound=60),
    "water_hole_climb_dry": dict(expect="succeed", bound=60),
    # the agent's movement snags of 2026-10-01 (pathfix_replay.py group S), rebuilt
    "bridge_plant": dict(expect="succeed", bound=60, max_dev=1.0, allow=(False, True)),
    "overhang_plant": dict(expect="succeed", bound=30, allow=(False, True)),
    "head_plant_leaves": dict(expect="succeed", bound=60, max_dev=1.0, allow=(True, False)),
    "bridge_drop": dict(expect="succeed", bound=60, allow=(False, True), change_at_u=6, change="bridge_drop_fall"),
    # parkour: gaps a player jumps; with break and place off nothing else crosses them
    "gap1": dict(expect="succeed", bound=60, max_dev=1.0),
    "gap2": dict(expect="succeed", bound=60, max_dev=1.0),
    "gap3": dict(expect="succeed", bound=60, max_dev=1.0),
    "gap1_up": dict(expect="succeed", bound=60, max_dev=1.0),
    "gap2_place": dict(expect="succeed", bound=60, max_dev=1.0, allow=(False, True)),
    "gap2_lava": dict(expect="succeed", bound=60, max_dev=1.0),
    "gap2_turn": dict(expect="succeed", bound=60, max_dev=1.0),
    # liquids the agent meets all the time: diving for clay (mine, then swim back to the bank), a flooded passage, and
    # flowing lava that has finished spreading before the start (settle), so the edge has to be walked around
    "dive_clay5": dict(expect="succeed", bound=300, mine=True, surface=True),
    "dive_clay10": dict(expect="succeed", bound=450, mine=True, surface=True),
    "swim_u": dict(expect="succeed", bound=200),
    "lava_corner": dict(expect="succeed", bound=120, settle=200),
    "lava_wall": dict(expect="succeed", bound=120, settle=200),
    # deliberately adversarial liquids (plots 27-36): currents, waterfalls, falling gravel, flooding, breath, lava/water;
    # each passable by a careful human with the case's kit (see the fixture notes). settle covers the flows.
    "dive_clay_current": dict(expect="succeed", bound=400, mine=True, surface=True, settle=80),
    "dive_clay_falls": dict(expect="succeed", bound=300, mine=True, surface=True, settle=60),
    "dive_clay_gravel": dict(expect="succeed", bound=450, mine=True, surface=True),
    "dive_clay_flooding": dict(expect="succeed", bound=350, mine=True, surface=True),
    "water_maze": dict(expect="succeed", bound=260, settle=80),
    "swim_pocket": dict(expect="succeed", bound=600),
    "swim_long_dry_detour": dict(expect="succeed", bound=520),  # minY in the fixture: a dive is a fail
    "lava_water_mix": dict(expect="succeed", bound=120, settle=200),
    "waterfall_climb": dict(expect="succeed", bound=250, settle=80),
    "dive_clay_stream": dict(expect="succeed", bound=450, mine=True, surface=True, settle=120),
    # script: the obsidian cases (movement_obsidian.py); fixture: the fixture case to build when it differs.
    "obsidian": dict(expect="script", script="simple"),
    "obsidian_natural": dict(expect="script", script="natural"),
    "obsidian_natural_tools": dict(expect="script", script="tools", fixture="obsidian_natural"),
    "obsidian_natural_mid_tools": dict(expect="script", script="tools", fixture="obsidian_natural_mid",
                                       change_after_s=3, change="natural_flow_on"),
}
CATALOGUE = {"pit_loop": "#1", "pit_trapped": "#1", "edge_start": "#2", "edge_start_across": "#2", "shaft_jam": "#3",
             "underwater_corner": "#5", "gravel_lava": "#6/P2-6", "mine_lava_beside": "#6", "mine_lava_pocket": "#6/P2-7",
             "door_wood": "P1-4", "door_iron": "P1-4", "gate": "P1-4", **{f"strip_{k}": "P1-1" for k in ("thin0", "thin1", "low0")},
             **{f"diag_{k}": "P2-5/#3" for k in ("fence", "wall", "pane", "slab", "trapdoor", "pack0", "pack1", "pack2", "pack3")},
             **{f"inside_{k}": "P1-3/P2-5" for k in ("fence", "wall", "pane", "pack0")}}


def server_kernel(timeout: float = 120) -> Kernel:
    return Kernel(url=bridge_url("server"), token=os.environ.get("MB_SERVER_BRIDGE_TOKEN"), timeout=timeout)


def with_resume(ticks, fn, *args, **kwargs):
    """What the MCP server does for resume=True / resume=N: the call's first action resumes or steps the world."""
    def run():
        resume_once.set({} if ticks is True else {"ticks": int(ticks)})
        return fn(*args, **kwargs)
    return contextvars.copy_context().run(run)


# ---------------------------------------------------------------- sampling (light: 4 Hz client, 1 Hz server)

class Sampler(threading.Thread):
    def __init__(self, hz: float = 4.0, on_sample=None):
        super().__init__(daemon=True)
        self.c, self.s = Kernel(timeout=10), server_kernel(10)
        self.period, self.on_sample = 1 / hz, on_sample
        self.samples, self.server, self.errors = [], [], 0
        self.stop_flag = threading.Event()

    def run(self):
        n = 0
        while not self.stop_flag.is_set():
            t = time.monotonic()
            try:
                r = self.c.call_reply("obs.player", timeout=5)
                wall = time.monotonic()
                if r.ok:
                    d = r.data
                    self.samples.append(dict(wall=wall, rtt_ms=(wall - t) * 1000, tick=r.tick, cost_ms=r.cost_ms, pos=d["pos"], health=d["health"],
                                             burning=d.get("burning"), onGround=d.get("onGround"), inWater=d.get("inWater"), air=d.get("air")))
                    if self.on_sample: self.on_sample(self.samples[-1])
                if n % 4 == 0:
                    st = self.s.call_reply("time.status", timeout=5)
                    if st.ok: self.server.append(dict(wall=time.monotonic(), ticks=st.data.get("simulationTicks", (st.data.get("state") or {}).get("simulationTicks")), cost_ms=st.cost_ms))
            except Exception:
                self.errors += 1
            n += 1
            self.stop_flag.wait(max(0.0, self.period - (time.monotonic() - t)))

    def finish(self):
        self.stop_flag.set(); self.join(5)
        for k in (self.c, self.s):
            try: k.close()
            except Exception: pass
        return perf(self.samples, self.server)


def perf(samples: list[dict], server: list[dict]) -> dict:
    out: dict = {"samples": len(samples)}
    if len(samples) >= 2:
        a, b = samples[0], samples[-1]
        out["clientTps"] = round((b["tick"] - a["tick"]) / max(1e-6, b["wall"] - a["wall"]), 2)
        costs = sorted(x["cost_ms"] for x in samples)
        out["obsCostMs"] = {"median": round(statistics.median(costs), 1), "p95": round(costs[int(.95 * (len(costs) - 1))], 1), "max": round(costs[-1], 1)}
        rtt = sorted(x["rtt_ms"] for x in samples)
        out["obsRoundTripMs"] = {"median": round(statistics.median(rtt), 1), "max": round(rtt[-1], 1)}
        # A reply waits for the client thread: its cost bounds the gap to the next client tick/frame that serves it.
        out["worstGapMs"] = round(costs[-1], 1)
        stuck, since = 0.0, None
        for p, q in zip(samples, samples[1:]):
            if q["tick"] == p["tick"]: since = since or p["wall"]; stuck = max(stuck, q["wall"] - since)
            else: since = None
        out["longestNoTickMs"] = round(stuck * 1000, 1)
    ok = [x for x in server if isinstance(x.get("ticks"), int)]
    if len(ok) >= 2:
        out["serverTps"] = round((ok[-1]["ticks"] - ok[0]["ticks"]) / max(1e-6, ok[-1]["wall"] - ok[0]["wall"]), 2)
    return out


def cost_fields(receipt) -> dict:
    """Baritone cost fields in a receipt (game-thread ns per tick, search time per plan), whatever they are called."""
    found = {}
    def walk(v, path):
        if isinstance(v, dict):
            for k, x in v.items():
                key = f"{path}.{k}" if path else k
                if re.search(r"(nanos|Ns$|ns$|[sS]earch.*(ms|Ms)|plan.*(ms|Ms)|perf|gameThread|cost)", k) and not isinstance(x, (dict, list)): found[key] = x
                elif isinstance(x, dict) and len(path.split(".")) < 3: walk(x, key)
                elif re.search(r"(perf|gameThread|search)", k) and isinstance(x, (dict, list)): found[key] = x
    walk(receipt, "")
    return found


# ---------------------------------------------------------------- geometry and logs

def seg_dist(p, a, b):
    ax, az, bx, bz, px, pz = a[0], a[1], b[0], b[1], p[0], p[1]
    dx, dz = bx - ax, bz - az
    t = 0.0 if dx == dz == 0 else max(0.0, min(1.0, ((px - ax) * dx + (pz - az) * dz) / (dx * dx + dz * dz)))
    return math.hypot(px - (ax + t * dx), pz - (az + t * dz))


def deviation(samples, route):
    if not route or len(route) < 2 or not samples: return None
    return round(max(min(seg_dist((s["pos"][0], s["pos"][2]), a, b) for a, b in zip(route, route[1:])) for s in samples), 3)


def log_offset() -> int | None:
    path = os.environ.get("MB_CLIENT_LOG")
    try: return os.path.getsize(path) if path else None
    except OSError: return None


def log_counts(start: int | None) -> dict | None:
    path = os.environ.get("MB_CLIENT_LOG")
    if start is None or not path: return None
    try:
        with open(path, "rb") as f:
            f.seek(start if os.path.getsize(path) >= start else 0); text = f.read().decode("utf-8", "replace")
    except OSError: return None
    counts = {k: len(re.findall(p, text)) for k, p in LOG_PATTERNS.items()}
    counts["spam"] = counts["unreachable"] >= 3 or counts["plans"] >= 8
    return counts


# ---------------------------------------------------------------- the course

class Course:
    def __init__(self, args):
        self.args = args
        self.c, self.s = Kernel(timeout=120), server_kernel(120)
        self.rng = random.Random(args.seed)
        self.evidence: dict = {"ok": False, "seed": args.seed, "trials": args.trials, "cases": {}, "started": time.strftime("%Y-%m-%dT%H:%M:%S")}

    # -- setup
    def ensure_world(self):
        try: self.c.call("obs.player"); return
        except BridgeError: pass
        self.c.call("sys.connect", host=self.args.server_host, port=25575)
        until = time.monotonic() + 120
        while time.monotonic() < until:
            time.sleep(2)
            try: self.c.call("obs.player"); time.sleep(3); return
            except BridgeError: pass
        raise RuntimeError("client did not rejoin the test server")

    def resync(self):
        try: self.c.call("sys.disconnect")
        except BridgeError: pass
        until = time.monotonic() + 30
        while time.monotonic() < until:
            time.sleep(1)
            try: self.c.call("obs.player")
            except BridgeError: break
        self.ensure_world()
        self.evidence["rejoins"] = self.evidence.get("rejoins", 0) + 1

    def setup(self):
        self.ensure_world()
        state = self.c.call("time.status")["state"]
        self.evidence["previousClock"] = state.get("conditions")
        self.c.call("time.configure", healthDrop=False, healthBelow=-1, airBelow=-1, foodBelow=-1, burning=False,
                    threatWithin=-1, actionFailed=False, pauseOnDisconnect=False)
        if state.get("paused"): self.c.call("time.resume")
        try: self.c.call("act.stop")
        except BridgeError: pass
        status = None
        try: status = self.s.call(FIX + ".status")
        except BridgeError: pass
        self.created = status is None
        created = self.s.call(FIX + ".create", timeout=300) if status is None else status
        self.evidence["origin"], self.evidence["picks"] = created["origin"], created.get("picks")
        self.cases = {c["name"]: c for c in created["cases"]}
        try: self.evidence["settings"] = work.mb_settings("get", "allowSprint")
        except Exception as e: self.evidence["settings"] = str(e)

    def teardown(self):
        for step in (lambda: self.c.call("act.stop"),
                     lambda: self.s.call(FIX + ".restore") if not self.args.keep else None,
                     lambda: self.c.call("time.configure", **{**(self.evidence.get("previousClock") or {}), "pauseOnDisconnect": False})):
            try: step()
            except Exception as e: self.evidence.setdefault("teardownErrors", []).append(str(e))

    # -- one trial
    def place(self, name: str, yaw: float) -> dict:
        # Deaths are caught by the fixture, but a player who did die is only usable again after a clean rejoin
        # (the fixture revives them on login); check both sides stand at the start, and rejoin if they do not.
        for attempt in range(3):
            try: info = self.s.call(FIX + ".position", name=CASES[name].get("fixture", name), yaw=yaw)
            except BridgeError as e:
                if "rejoin" not in str(e) or attempt == 2: raise
                self.resync(); continue
            if info.get("missing"): return info
            start = info["start"]; settle = CASES[name].get("settle", 10)
            until, placed = time.monotonic() + 6, False
            while time.monotonic() < until and not placed:
                try: p = self.c.call("obs.player")["pos"]
                except BridgeError: time.sleep(.5); continue
                placed = abs(p[0] - start[0]) < .05 and abs(p[2] - start[2]) < .05 and abs(p[1] - start[1]) < .6
                if not placed: time.sleep(.1)
            if placed:
                q = self.s.call(FIX + ".status")["pos"]      # the server's player must be the one the client drives
                placed = abs(q[0] - start[0]) < .5 and abs(q[2] - start[2]) < .5 and abs(q[1] - start[1]) < 1
            if placed: break
            # The client and the server's player disagree about where the player is: rejoin.
            self.resync()
        else:
            raise RuntimeError(f"client never reached the start {start}")
        tick = self.c.call_reply("obs.player").tick
        while self.c.call_reply("obs.player").tick < tick + settle: time.sleep(.05)
        info["startActual"] = self.c.call("obs.player")["pos"]
        return info

    def idle_baseline(self, seconds: float = 10.0):
        self.place("corner_l", 0)
        sampler = Sampler(); sampler.start(); time.sleep(seconds)
        self.evidence["idle"] = sampler.finish()
        print("idle baseline", json.dumps(self.evidence["idle"]), flush=True)

    def run_job(self, name: str, info: dict) -> tuple[str, dict, float]:
        spec = CASES[name]
        brk, plc = spec.get("allow", (False, False))
        duration = max(self.args.duration, math.ceil(1.5 * spec.get("bound", 0)))  # room for the case's whole pass window
        timeout_s = duration / 20 + 30
        t = time.monotonic()
        try:
            if spec.get("mine"):
                m = info["mine"]
                items = [{"id": i} for i in dict.fromkeys(m.get("drops") or [])] if spec.get("surface") else None  # only the block's own drops count
                r = work.mb_mine(blocks=[{"id": m["id"]}], items=items, quantity=1, bounds=m["bounds"], allow_break=True, allow_place=True,
                                 timeout_ticks=duration, timeout_s=timeout_s)
                if spec.get("surface") and (r.get("state") or "succeeded") == "succeeded":  # then back to the bank
                    back = work.mb_process("goal", goal={"type": "block", "pos": info["goal"]}, duration_ticks=duration,
                                           allow_break=brk, allow_place=plc, timeout_s=timeout_s)
                    r = {**back, "ticks": (r.get("ticks") or 0) + (back.get("ticks") or 0), "mine": r}
            else:
                r = work.mb_process("goal", goal={"type": "block", "pos": info["goal"]}, duration_ticks=duration,
                                    allow_break=brk, allow_place=plc, timeout_s=timeout_s)
            return "ok", r, time.monotonic() - t
        except BridgeError as e:
            err = (e.reply or {}).get("error") or {}
            receipt = err.get("receipt") if isinstance(err.get("receipt"), dict) else {}
            return "error", {**receipt, "errorCode": e.code, "errorMsg": e.msg}, time.monotonic() - t
        except Exception as e:  # a wall-clock timeout of the tool call itself
            return "exception", {"errorMsg": f"{type(e).__name__}: {e}"}, time.monotonic() - t

    def trial(self, name: str, index: int) -> dict:
        spec = CASES[name]
        yaw = round(self.rng.uniform(-180, 180), 1)
        info = self.place(name, yaw)
        if info.get("missing"): return {"trial": index, "skipped": info["missing"]}
        if spec["expect"] == "script": return self.obsidian(name, info, index, yaw)
        before = self.s.call(FIX + ".status")
        offset = log_offset()
        fired = {}
        def on_sample(sample):
            if "change_at_u" in spec and not fired and sample["pos"][0] >= info["plotMin"][0] + spec["change_at_u"]:
                fired["at"] = sample["pos"]
                try: self.s.call(FIX + ".change", name=spec["change"])
                except Exception as e: fired["error"] = str(e)
        if "change_at_start" in spec: self.s.call(FIX + ".change", name=spec["change_at_start"]); fired["at"] = "start"
        sampler = Sampler(on_sample=on_sample); sampler.start()
        kind, receipt, wall = self.run_job(name, info)
        time.sleep(.3)
        perf_ = sampler.finish()
        try: self.c.call("act.stop")
        except BridgeError: pass
        after = self.s.call(FIX + ".status")
        samples = sampler.samples
        health_min = min([s["health"] for s in samples] + [after["health"]])
        result = {
            "trial": index, "yaw": yaw, "startActual": info.get("startActual"),
            "state": receipt.get("state") or ("succeeded" if kind == "ok" else "failed"),
            "reason": receipt.get("reason") or receipt.get("errorMsg"), "ticks": receipt.get("ticks"), "wallS": round(wall, 2),
            "healthBefore": before["health"], "healthAfter": after["health"], "healthMin": health_min,
            "burned": any(s.get("burning") for s in samples) or after["burning"], "dead": after["dead"] or after.get("fatal", 0) > 0,
            "fatal": after.get("fatal"), "fatalCause": after.get("fatalCause") or None,
            "final": [round(v, 3) for v in after["pos"]], "maxDeviation": deviation(samples, info.get("route")),
            "minY": round(min([s["pos"][1] for s in samples] + [after["pos"][1]]), 3),
            "movementTypes": receipt.get("movementTypes"), "stall": receipt.get("stall"),
            "replans": {k: receipt[k] for k in receipt if re.search(r"(replan|revision|segments)", k, re.I)} or None,
            "log": log_counts(offset), "perf": perf_, "baritoneCost": cost_fields(receipt) or None,
            "change": fired or None,
            "receipt": {k: v for k, v in receipt.items() if k not in ("notes",)},
        }
        result["failures"] = self.judge(name, info, result)
        result["passed"] = not result["failures"]
        return result

    def judge(self, name: str, info: dict, r: dict) -> list[str]:
        spec, f = CASES[name], []
        succeeded = r["state"] == "succeeded"
        if r["healthMin"] < r["healthBefore"] - 1e-6: f.append(f"health {r['healthBefore']}->{r['healthMin']}")
        if r["burned"]: f.append("burned")
        if r["dead"]: f.append("died")
        refused_well = (not succeeded and (r["ticks"] or 1e9) <= REFUSE_TICKS and r["wallS"] <= REFUSE_WALL_S
                        and r["reason"] and not any(g in str(r["reason"]) for g in GENERIC))
        bound = spec.get("bound")
        if spec["expect"] == "succeed" or spec["expect"] == "either" and succeeded:
            if not succeeded: f.append(f"did not arrive: {r['reason']} after {r['ticks']} ticks")
            elif bound and (r["ticks"] or 0) > 1.5 * bound: f.append(f"slow: {r['ticks']} ticks > 1.5x{bound}")
        elif spec["expect"] == "refuse" and succeeded: f.append("arrived where it should have refused")
        if spec["expect"] in ("refuse", "either") and not succeeded and not refused_well:
            f.append(f"refusal not fast or not named: {r['reason']} after {r['ticks']} ticks / {r['wallS']} s")
        if "minY" in info and r["minY"] < info["minY"]: f.append(f"fell to y={r['minY']} (floor {info['minY']})")
        if spec.get("max_dev") is not None and r["maxDeviation"] is not None and r["maxDeviation"] > spec["max_dev"]:
            f.append(f"deviation {r['maxDeviation']} > {spec['max_dev']}")
        if r["log"] and r["log"]["spam"]: f.append(f"log spam: {r['log']['unreachable']} UNREACHABLE, {r['log']['plans']} plans")
        idle, p = self.evidence.get("idle") or {}, r["perf"]
        if idle.get("clientTps") and p.get("clientTps") is not None and p["clientTps"] < PERF_MIN_TPS_RATIO * idle["clientTps"]:
            f.append(f"client tps {p['clientTps']} < 90% of idle {idle['clientTps']}")
        if p.get("worstGapMs") is not None and p["worstGapMs"] > PERF_MAX_GAP_MS: f.append(f"client gap {p['worstGapMs']} ms")
        return f

    # -- the owner's obsidian cases: raw primitives the way a careful player would, and mb_mine in the same room
    def obsidian(self, name: str, info: dict, index: int, yaw: float) -> dict:
        import movement_obsidian as mo
        kind = CASES[name]["script"]
        if kind == "simple": return mo.run_obsidian(self, info, index, yaw)
        if kind == "natural": return mo.run_natural(self, info, index, yaw)
        return mo.run_natural_tools(self, info, index, yaw, name)

    # -- all
    def run(self):
        names = self.args.case or list(CASES)
        unknown = [n for n in names if n not in CASES]
        if unknown: raise SystemExit(f"unknown case(s): {unknown}; known: {list(CASES)}")
        self.setup()
        try:
            if not self.args.no_idle: self.idle_baseline()
            for name in names:
                trials = []
                for i in range(self.args.trials if CASES[name]["expect"] != "script" else min(self.args.trials, self.args.script_trials)):
                    try: t = self.trial(name, i)
                    except Exception as e: t = {"trial": i, "passed": False, "failures": [f"harness error: {type(e).__name__}: {e}"]}
                    trials.append(t)
                    print(f"{name}[{i}] {'SKIP' if t.get('skipped') else 'PASS' if t.get('passed') else 'FAIL'} "
                          f"{t.get('state','')} {t.get('reason','') or ''} ticks={t.get('ticks')} {'; '.join(t.get('failures') or [])}", flush=True)
                    if t.get("skipped"): break
                self.evidence["cases"][name] = {"spec": CASES[name], "catalogue": CATALOGUE.get(name), "case": self.cases.get(name),
                                                "trials": trials, "summary": summarize(trials)}
                self.save()
            self.evidence["ok"] = True
        finally:
            self.teardown(); self.save()
        print_table(self.evidence)

    def save(self):
        OUT.parent.mkdir(parents=True, exist_ok=True)
        OUT.write_text(json.dumps(self.evidence, indent=1, default=str))


def summarize(trials: list[dict]) -> dict:
    run = [t for t in trials if not t.get("skipped")]
    if not run: return {"skipped": True, "why": trials[0].get("skipped") if trials else None}
    def med(key, sub=None):
        vals = [(t.get(key) or {}).get(sub) if sub else t.get(key) for t in run]
        vals = [v for v in vals if isinstance(v, (int, float))]
        return round(statistics.median(vals), 2) if vals else None
    reasons = {}
    for t in run: reasons[str(t.get("reason"))] = reasons.get(str(t.get("reason")), 0) + 1
    return {"passed": sum(bool(t.get("passed")) for t in run), "trials": len(run), "medianTicks": med("ticks"),
            "maxDeviation": max([t["maxDeviation"] for t in run if t.get("maxDeviation") is not None], default=None),
            "healthLost": max([t["healthBefore"] - t["healthMin"] for t in run if "healthMin" in t], default=None),
            "reasons": reasons, "unreachable": sum(((t.get("log") or {}).get("unreachable") or 0) for t in run),
            "plans": med("log", "plans"),
            "clientTps": med("perf", "clientTps"), "serverTps": med("perf", "serverTps"),
            "worstGapMs": max([(t.get("perf") or {}).get("worstGapMs") or 0 for t in run], default=None),
            "failures": sorted({f for t in run for f in (t.get("failures") or [])})[:6],
            **({"obsidian": med("obsidianCollected"), "obsidianMined": med("obsidianMined"),
                "dropsBurned": sum((t.get("obsidianDrops") or {}).get("burned", 0) for t in run),
                "onPoolSamples": sum(t.get("onPoolSamples") or 0 for t in run), "offEdgeSamples": sum(t.get("offEdgeSamples") or 0 for t in run)}
               if any("obsidianCollected" in t for t in run) else {})}


def print_table(ev: dict):
    idle = ev.get("idle") or {}
    print(f"\nidle: client {idle.get('clientTps')} tps, server {idle.get('serverTps')} tps, worst gap {idle.get('worstGapMs')} ms")
    print(f"{'case':22} {'cat':9} {'pass':>5} {'ticks':>6} {'dev':>5} {'hp-':>4} {'unr':>4} {'plans':>5} {'ctps':>5} {'stps':>5} {'gap':>6}  reasons")
    for name, c in ev["cases"].items():
        s = c["summary"]
        if s.get("skipped"): print(f"{name:22} {'':9} {'skip':>5}  {s.get('why')}"); continue
        print(f"{name:22} {str(c.get('catalogue') or ''):9} {s['passed']}/{s['trials']:<3} {str(s['medianTicks']):>6} {str(s['maxDeviation']):>5} "
              f"{str(s['healthLost']):>4} {s['unreachable']:>4} {str(s['plans']):>5} {str(s['clientTps']):>5} {str(s['serverTps']):>5} {str(s['worstGapMs']):>6}  "
              + ", ".join(f"{k} x{v}" for k, v in s["reasons"].items())
              + (f"  [obsidian {s['obsidian']} (mined {s['obsidianMined']}), drops burned {s['dropsBurned']}, on pool {s['onPoolSamples']}]" if "obsidian" in s else ""))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--case", action="append", help="case name (repeatable); default all")
    ap.add_argument("--trials", type=int, default=5)
    ap.add_argument("--script-trials", type=int, default=2, help="trials for the obsidian cases")
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--duration", type=int, default=600, help="job duration_ticks (simulated)")
    ap.add_argument("--keep", action="store_true", help="leave the course and the journalled player in place")
    ap.add_argument("--no-idle", action="store_true", help="skip the idle performance baseline")
    ap.add_argument("--server-host", default="127.0.0.1", help="the server address as the client sees it, to rejoin")
    ap.add_argument("--list", action="store_true")
    args = ap.parse_args()
    if args.list:
        for n, s in CASES.items(): print(f"{n:22} {s['expect']:8} {CATALOGUE.get(n, '')}")
        return 0
    course = Course(args)
    course.run()
    return 0 if course.evidence["ok"] else 1


if __name__ == "__main__":
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    raise SystemExit(main())
