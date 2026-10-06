# SPDX-License-Identifier: MIT
# Copyright (c) 2026 ModdedBench contributors
"""The obsidian cases of the movement course: what a careful player does with raw mb_act primitives, and what the
model's high-level mining tool does in the same room.

obsidian             plot 15: a still 3x3 lava pool two deep, recessed one block below the bank. Pour the water
                     bucket at the pool edge, wait for the top layer to set, mine one block while the water still
                     covers the crust (the water runs into the hole and sets the lava under it before the drop lands),
                     step into the hole to collect, recover the water.
obsidian_natural     plot 16: an enclosed cave room; a 5x5 pool three deep whose top layer is a patchy obsidian and
                     cobblestone crust flush with the ledge, some cells still open lava; a roof water source spreads
                     over the crust. Script: scoop the roof source, dig a trench along the ledge beside the pool, flood
                     it, deepen it under the water so the water sets the lava under the crust edge, then mine that
                     crust (the drops land on obsidian) and collect from the trench.
obsidian_natural_tools / _mid_tools
                     the same room through mb_mine (obsidian inside the pool bounds, quantity 4, break and place
                     allowed); _mid starts dry and turns the roof source on a few seconds in, so the terrain the
                     planner measured goes stale under it.

Every run records what GTNH actually did: each pool cell's block over time (which block forms where water meets
lava, and how fast the crust changes), and the fate of every item that entered the room (picked, burned, gone).
"""
from __future__ import annotations

import math
import threading
import time

from movement_course import FIX, PERF_MAX_GAP_MS, PERF_MIN_TPS_RATIO, BridgeError, Kernel, Sampler, core, log_counts, log_offset, server_kernel, work

B = 200
NEED = 4          # obsidian the natural cases must collect


# ---------------------------------------------------------------- server-side watch (2 Hz): player, pool cells, item fates

class PoolWatch(threading.Thread):
    """Polls the fixture status for one case: authoritative player state, every liquid/obsidian/cobblestone cell in
    the plot, and the fixture's item-fate list. Records each cell change with its server tick."""

    def __init__(self, fixture: str, origin: list[int], hz: float = 2.0):
        super().__init__(daemon=True)
        self.s, self.fixture, self.X, self.Z = server_kernel(10), fixture, origin[0], origin[2]
        self.period, self.stop_flag = 1 / hz, threading.Event()
        self.samples, self.changes, self.grid, self.last, self.t0 = [], [], {}, None, None

    def cells(self, r) -> dict:
        return {(b[0] - self.X, b[1], b[2] - self.Z): f"{b[3]}:{b[4]}" for b in r["blocks"]}

    def poll(self):
        r = self.s.call(FIX + ".status", name=self.fixture, timeout=5)
        tick = r["serverTick"]
        if self.t0 is None: self.t0 = tick
        g = self.cells(r)
        if self.last is not None:
            for k in set(g) | set(self.grid):
                if g.get(k) != self.grid.get(k): self.changes.append((tick - self.t0, list(k), self.grid.get(k, "air/other"), g.get(k, "air/other")))
        self.grid, self.last = g, r
        self.samples.append(dict(tick=tick - self.t0, pos=r["pos"], health=r["health"], burning=r["burning"], inLava=r["inLava"],
                                 inWater=r["inWater"], fireTicks=r.get("fireTicks")))
        return r

    def run(self):
        while not self.stop_flag.is_set():
            t = time.monotonic()
            try: self.poll()
            except Exception: pass
            self.stop_flag.wait(max(0.0, self.period - (time.monotonic() - t)))

    def finish(self) -> dict:
        self.stop_flag.set(); self.join(5)
        try: self.poll()
        except Exception: pass
        try: self.s.close()
        except Exception: pass
        return self.last or {}


def gtnh_facts(changes: list, pool: tuple[int, int, int, int]) -> dict:
    """Summarise cell changes: what each transition became and when it first happened (server ticks from start)."""
    u0, u1, v0, v1 = pool
    kinds: dict = {}
    for tick, (u, y, v), a, b in changes:
        short = lambda s: s.split(":")[1] if s.count(":") >= 1 and not s.startswith("air") else s
        key = f"{short(a)}->{short(b)}"
        in_pool = u0 <= u <= u1 and v0 <= v <= v1
        k = kinds.setdefault(key, {"count": 0, "firstTick": tick, "lastTick": tick, "inPool": 0})
        k["count"] += 1; k["lastTick"] = tick; k["inPool"] += in_pool
    solid = [t for t, (u, y, v), a, b in changes if u0 <= u <= u1 and v0 <= v <= v1 and y <= B + 2 and b.split(":")[-1] in ("obsidian", "cobblestone", "stone")]
    return {"transitions": dict(sorted(kinds.items(), key=lambda kv: -kv[1]["count"])),
            "poolCellsSolidified": len(solid), "solidifyTicks": solid[:40]}


def fate_summary(items: list[dict], item_id: str = "minecraft:obsidian") -> dict:
    out = {"picked": 0, "burned": 0, "gone": 0, "alive": 0}
    for it in items or []:
        if it.get("id") == item_id: out[it.get("fate", "gone")] = out.get(it.get("fate", "gone"), 0) + (it.get("count") or 1)
    out["other"] = sorted({f"{it['id']}:{it['fate']}" for it in items or [] if it.get("id") != item_id})
    return out


def count_item(status: dict, item_id: str) -> int:
    return sum(i["count"] for i in status.get("inventory", []) if i["id"] == item_id)


# ---------------------------------------------------------------- raw primitives

def look_at(c: Kernel, t):
    p = c.call("obs.player")["pos"]; eye = (p[0], p[1] + 1.62, p[2])
    dx, dy, dz = t[0] - eye[0], t[1] - eye[1], t[2] - eye[2]
    core.mb_act("look", {"yaw": math.degrees(math.atan2(dz, dx)) - 90, "pitch": -math.degrees(math.atan2(dy, math.hypot(dx, dz)))})


def walk_to(c: Kernel, x: float, z: float, tol: float = 0.2, limit: int = 40) -> float:
    """Short forward pulses toward (x, z), re-aimed each pulse; returns the final distance."""
    d = 9e9
    for _ in range(limit):
        p = c.call("obs.player")["pos"]; d = math.hypot(x - p[0], z - p[2])
        if d < tol: break
        core.mb_act("look", {"yaw": math.degrees(math.atan2(z - p[2], x - p[0])) - 90, "pitch": 30})
        core.mb_act("input", {"keys": ["forward"], "ticks": max(1, min(4, int(d / 0.2)))})
    return round(d, 3)


def block_at(s: Kernel, fixture: str, w: tuple[int, int, int]) -> str | None:
    r = s.call(FIX + ".status", name=fixture)
    for b in r["blocks"]:
        if (b[0], b[1], b[2]) == tuple(w): return b[3]
    return None


def dig(c: Kernel, s: Kernel, fixture: str, cell: tuple[int, int, int], aim, notes: list) -> bool:
    """Hold attack on one block until it breaks (the input ends when the target changes)."""
    core.mb_act("select_hotbar", {"slot": 0})
    for attempt in range(2):
        look_at(c, aim)
        r = core.mb_act("input", {"keys": ["attack"], "ticks": 200})
        until = time.monotonic() + 1.0          # the client sees the break a tick or two before the server does
        while (now := block_at(s, fixture, cell)) not in (None, "minecraft:water", "minecraft:flowing_water") and time.monotonic() < until:
            time.sleep(.1)
        notes.append(f"dig {list(cell)}: {r.get('outcome')} -> {now or 'air'} ({r.get('elapsedTicks')} ticks)")
        if now in (None, "minecraft:water", "minecraft:flowing_water"): return True
    return False


# ---------------------------------------------------------------- the trial frame shared by all four cases

def frame(course, name: str, info: dict, index: int, yaw: float, body) -> dict:
    spec = course_cases()[name]
    fixture = spec.get("fixture", name)
    before = course.s.call(FIX + ".status", name=fixture)
    offset = log_offset()
    watch = PoolWatch(fixture, info["plotMin"]); watch.start()
    sampler = Sampler(); sampler.start()
    t = time.monotonic(); notes: list[str] = []; out: dict = {}
    try: out = body(fixture, notes) or {}
    except BridgeError as e:
        err = (e.reply or {}).get("error") or {}
        out = {"state": "failed", "reason": f"{e.code}: {e.msg}", "receipt": err.get("receipt")}
    except Exception as e:
        out = {"state": "failed", "reason": f"{type(e).__name__}: {e}"}
    wall = time.monotonic() - t
    time.sleep(1.0)
    perf_ = sampler.finish()
    after = watch.finish()
    try: course.c.call("act.stop")
    except BridgeError: pass
    X, Z = info["plotMin"][0], info["plotMin"][2]
    pool = (6, 10, 6, 10) if fixture.startswith("obsidian_natural") else (6, 8, 6, 8)
    samples = watch.samples
    on_crust = [x for x in samples if pool[0] <= x["pos"][0] - X < pool[1] + 1 and pool[2] <= x["pos"][2] - Z < pool[3] + 1]
    health_min = min([x["health"] for x in samples] + [after.get("health", before["health"])])
    got = count_item(after, "minecraft:obsidian") - count_item(before, "minecraft:obsidian")
    fates = fate_summary(after.get("items"))
    result = {
        "trial": index, "yaw": yaw, "startActual": info.get("startActual"), "wallS": round(wall, 2),
        "state": out.get("state", "succeeded"), "reason": out.get("reason"), "ticks": samples[-1]["tick"] if samples else None,
        "healthBefore": before["health"], "healthAfter": after.get("health"), "healthMin": health_min,
        "burned": any(x["burning"] for x in samples), "inLava": any(x["inLava"] for x in samples), "dead": bool(after.get("dead") or after.get("fatal")), "fatalCause": after.get("fatalCause") or None,
        "inWaterSamples": sum(bool(x["inWater"]) for x in samples), "onPoolSamples": len(on_crust), "samples": len(samples),
        "offEdgeSamples": sum(1 for x in on_crust if x["pos"][1] < B + 2.5) if fixture.startswith("obsidian_natural") else 0,
        "minY": round(min([x["pos"][1] for x in samples] + [after["pos"][1]]), 3) if after else None,
        "final": [round(v, 3) for v in after.get("pos", [])], "maxDeviation": None,
        "obsidianCollected": got, "obsidianDrops": fates,
        "obsidianMined": (after.get("broken") or {}).get("minecraft:obsidian", 0), "obsidianDropped": (after.get("dropped") or {}).get("minecraft:obsidian", 0),
        "broken": after.get("broken"), "dropped": after.get("dropped"), "inventory": after.get("inventory"),
        "gtnh": gtnh_facts(watch.changes, pool), "notes": notes,
        "poolChanges": [ch for ch in watch.changes if "water" not in ch[2] + ch[3]][:400], "items": after.get("items"), "log": log_counts(offset), "perf": perf_,
        "receipt": out.get("receipt"),
    }
    result.update({k: v for k, v in out.items() if k not in result or k in ("state", "reason")})
    result["failures"] = judge(course, name, info, result)
    result["passed"] = not result["failures"]
    return result


def course_cases():
    from movement_course import CASES
    return CASES


def judge(course, name: str, info: dict, r: dict) -> list[str]:
    f = []
    need = 1 if name == "obsidian" else NEED
    if r["obsidianCollected"] < need: f.append(f"obsidian {r['obsidianCollected']}/{need}: {r.get('reason') or ''}".strip())
    if r["healthMin"] < r["healthBefore"] - 1e-6: f.append(f"health {r['healthBefore']}->{r['healthMin']}")
    if r["burned"]: f.append("burned")
    if r["inLava"]: f.append("in lava")
    if r["dead"]: f.append("died")
    if r["obsidianDrops"]["burned"]: f.append(f"{r['obsidianDrops']['burned']} obsidian drop(s) burned")
    lost = r["obsidianDropped"] - r["obsidianCollected"] - r["obsidianDrops"]["alive"]
    if lost > 0: f.append(f"{lost} of {r['obsidianDropped']} obsidian dropped never reached the inventory")
    if name == "obsidian" and not r.get("waterRecovered"): f.append("water not recovered")
    if name == "obsidian" and r["minY"] is not None and r["minY"] < B + 0.9: f.append(f"sank to y={r['minY']}")
    if r.get("offEdgeSamples"): f.append(f"off the pool edge: {r['offEdgeSamples']} samples below the crust top over the pool")
    idle, p = course.evidence.get("idle") or {}, r["perf"]
    if idle.get("clientTps") and p.get("clientTps") is not None and p["clientTps"] < PERF_MIN_TPS_RATIO * idle["clientTps"]:
        f.append(f"client tps {p['clientTps']} < 90% of idle {idle['clientTps']}")
    if p.get("worstGapMs") is not None and p["worstGapMs"] > PERF_MAX_GAP_MS: f.append(f"client gap {p['worstGapMs']} ms")
    return f


# ---------------------------------------------------------------- scripts

def run_obsidian(course, info: dict, index: int, yaw: float) -> dict:
    c, s = course.c, course.s
    X, Z = info["plotMin"][0], info["plotMin"][2]

    def body(fixture, notes):
        # 1. pour at the pool edge: target the far bank's west face, so the source lands on the crust layer.
        core.mb_act("select_hotbar", {"slot": 2})
        r = core.mb_act("use_item", {"x": X + 9, "y": B + 2, "z": Z + 7, "face": 4})
        notes.append(f"pour: {r.get('outcome')} changes={r.get('observedChanges')}")
        top = [(X + u, B + 1, Z + v) for u in range(6, 9) for v in range(6, 9)]
        until = time.monotonic() + 5
        while time.monotonic() < until:
            st = s.call(FIX + ".status", name=fixture)
            cells = {(b[0], b[1], b[2]): b[3] for b in st["blocks"]}
            if all(cells.get(k) == "minecraft:obsidian" for k in top): break
            time.sleep(.25)
        notes.append(f"top layer set: {sum(cells.get(k) == 'minecraft:obsidian' for k in top)}/9")
        # 2. mine the near block while water still covers the crust: water runs into the hole and sets the lava below.
        if not dig(c, s, fixture, (X + 6, B + 1, Z + 7), (X + 6.5, B + 1.99, Z + 7.5), notes):
            return {"state": "failed", "reason": "obsidian did not break"}
        time.sleep(.6)
        notes.append(f"under the hole: {block_at(s, fixture, (X + 6, B, Z + 7))}")
        # 3. step down into the hole to collect (2-block drop into water; no fall damage).
        notes.append(f"walk into hole: {walk_to(c, X + 6.5, Z + 7.5)}")
        time.sleep(1.0)
        # 4. recover the water source (still at the pool edge, two cells east of the hole).
        core.mb_act("select_hotbar", {"slot": 2})
        r = core.mb_act("use_item", {"x": X + 8, "y": B + 2, "z": Z + 7, "face": 1, "fluid": True})
        notes.append(f"recover: {r.get('outcome')} changes={r.get('observedChanges')}")
        time.sleep(1.0)
        st = s.call(FIX + ".status", name=fixture)
        water = count_item(st, "minecraft:water_bucket") > 0
        return {"state": "succeeded" if water and count_item(st, "minecraft:obsidian") else "failed",
                "reason": None if water else "bucket still empty", "waterRecovered": water}

    return frame(course, "obsidian", info, index, yaw, body)


def run_natural(course, info: dict, index: int, yaw: float) -> dict:
    """Scoop the roof source with the empty bucket, let the surface water drain, then set the lava under the pool's west crust column from
    the side: a trench along the ledge, flooded one layer at a time, so water reaches each lower lava cell before
    the crust above it is mined. Every drop then lands on obsidian and is collected from the trench."""
    c, s = course.c, course.s
    X, Z = info["plotMin"][0], info["plotMin"][2]
    W = lambda u, y, v: (X + u, y, Z + v)

    def use(slot, x, y, z, face, notes, what, **extra):
        core.mb_act("select_hotbar", {"slot": slot})
        r = core.mb_act("use_item", {"x": x, "y": y, "z": z, "face": face, **extra})
        notes.append(f"{what}: {r.get('outcome')} changes={r.get('observedChanges')}")

    def body(fixture, notes):
        # 1. scoop the roof source with the empty bucket (a bucket ray passes the falling column: only sources stop it),
        #    then let the surface water drain.
        notes.append(f"to the falling column: {walk_to(c, X + 4.5, Z + 6.5)}")
        use(3, X + 5, B + 7, Z + 4, 0, notes, "scoop roof source", fluid=True)
        notes.append(f"roof cell now: {block_at(s, fixture, W(5, B + 7, 4)) or 'air'}")
        until = time.monotonic() + 8
        while time.monotonic() < until:
            st = s.call(FIX + ".status", name=fixture)
            if not any("water" in b[3] for b in st["blocks"]): break
            time.sleep(.5)
        notes.append(f"surface water left: {sum('water' in b[3] for b in st['blocks'])} cells")
        notes.append(f"to the west ledge: {walk_to(c, X + 4.5, Z + 8.5)}")
        # 2. a one-deep trench beside the pool (u=5, v=6..10): its east wall is the crust, so no lava is exposed.
        for v in (8, 7, 9, 6, 10):
            if not dig(c, s, fixture, W(5, B + 2, v), (X + 5.5, B + 2.99, Z + v + .5), notes):
                return {"state": "failed", "reason": f"trench cell v={v} did not break"}
        # 3. flood the trench from the middle; the water stays below the ledge surface we stand on.
        use(2, X + 5, B + 1, Z + 8, 1, notes, "pour into trench")
        time.sleep(1.5)
        # 4. deepen it under the water: each opened cell floods before the lava beside it (u=6, B+1) can flow,
        #    and water touching that lava source sets it to obsidian.
        for v in (8, 7, 9, 6, 10):
            dig(c, s, fixture, W(5, B + 1, v), (X + 5.5, B + 1.99, Z + v + .5), notes)
            time.sleep(.6)
        time.sleep(1.0)
        lower = {v: (block_at(s, fixture, W(6, B + 1, v)) or "air").split(":")[-1] for v in range(6, 11)}
        notes.append(f"lower layer under the west crust: {lower}")
        # 5. mine the west crust column where the lava under it is set; each drop lands on obsidian.
        mined = 0
        for v in (8, 7, 9, 6, 10):
            if lower.get(v) != "obsidian": notes.append(f"crust v={v}: lava still under it, skipped"); continue
            if block_at(s, fixture, W(6, B + 2, v)) != "minecraft:obsidian": notes.append(f"crust v={v} is not obsidian"); continue
            if dig(c, s, fixture, W(6, B + 2, v), (X + 6.02, B + 2.5, Z + v + .5), notes): mined += 1
            if mined >= NEED + 1: break
        # 6. take the water back (the trench drains), step down into the trench and walk it to collect.
        use(2, X + 5, B + 2, Z + 8, 1, notes, "recover water", fluid=True)
        time.sleep(2.0)
        for v in (8, 6, 10, 8):
            notes.append(f"trench v={v}: {walk_to(c, X + 5.5, Z + v + .5)}")
            time.sleep(.5)
        time.sleep(1.0)
        st = s.call(FIX + ".status", name=fixture)
        return {"state": "succeeded" if count_item(st, "minecraft:obsidian") >= NEED else "failed", "crustMined": mined,
                "waterRecovered": count_item(st, "minecraft:water_bucket") > 0}

    return frame(course, "obsidian_natural", info, index, yaw, body)


def run_natural_tools(course, info: dict, index: int, yaw: float, name: str) -> dict:
    spec = course_cases()[name]

    def body(fixture, notes):
        if spec.get("change_after_s"):
            def later():
                time.sleep(spec["change_after_s"])
                try: server_kernel(10).call(FIX + ".change", name=spec["change"]); notes.append(f"change {spec['change']} at +{spec['change_after_s']} s")
                except Exception as e: notes.append(f"change failed: {e}")
            threading.Thread(target=later, daemon=True).start()
        m = info["mine"]
        duration = course.args.duration * 4
        r = work.mb_mine(blocks=[{"id": m["id"]}], items=[{"id": m["id"]}], quantity=NEED, bounds=m["bounds"], allow_break=True, allow_place=True, cleanup_scaffold=False,
                         timeout_ticks=duration)
        return {"state": r.get("state") or "succeeded", "reason": r.get("reason"), "receipt": trim(r)}

    return frame(course, name, info, index, yaw, body)


def trim(r):
    if not isinstance(r, dict): return r
    return {k: v for k, v in r.items() if k not in ("notes", "before", "after")}
