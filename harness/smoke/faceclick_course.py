# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Click-step builds on the dummy world, through the model's own tools (mb_build_preview, mb_build, mb_pattern).

Each case clears one plot in dev.work_process_fixture's journalled volume, sets its blocks and the player's loadout
with the fixture setters, previews, builds, and checks the result against the server's own reading
(dev.work_process_fixture.inspect_block: block, meta and tile NBT). Facing and side results are recorded, not
predicted: the course measures what each click made, and fails a case when the build's receipt disagrees with the
server or when two clicks that should differ made the same thing.
Needs the throwaway mbtest stack; run it from a container on the server's network:

    bash harness/smoke/mbtest.sh harness/smoke/faceclick_course.py [--case NAME ...] [--keep]

The mod ids in ITEMS are the GTNH ones this course was written against; confirm them with mb_item_search on the
first run (a wrong id fails that case's setup with "exact registered ... id required", not the build).
Evidence: .runtime/evidence/faceclick-course.json, and a summary table on stdout.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "harness" / "mcp"))
from kernel import BridgeError, Kernel, bridge_url  # noqa: E402
import mbtool  # noqa: E402,F401  (installs the mbtools_gtnh package)
from mbtools_gtnh import patterns, work  # noqa: E402

OUT = ROOT / ".runtime" / "evidence" / "faceclick-course.json"
FIX = "dev.work_process_fixture"
PLOT = (17, 176, 7)                  # fixture-relative min corner of the plot every case uses (clear of the other areas)
PLOT_SIZE = (12, 6, 8)
REFUSE_WALL_S = 10.0                 # a no-vantage refusal should come before any walking
ITEMS = {                            # confirm on the first run; see the module docstring
    "machine": {"id": "gregtech:gt.blockmachines", "meta": 201},       # an LV basic machine item
    "machine_block": "gregtech:gt.blockmachines",
    "cover": {"id": "gregtech:gt.metaitem.01", "meta": 32740},          # any cover item
    "pipe": {"id": "gregtech:gt.blockmachines", "meta": 5101},          # a small fluid pipe item
    "wrench": {"id": "gregtech:gt.metatool.01", "meta": 16},
}


def server_kernel(timeout: float = 120) -> Kernel:
    return Kernel(url=bridge_url("server"), token=os.environ.get("MB_SERVER_BRIDGE_TOKEN"), timeout=timeout)


def at(p):
    """Plot-relative [x, y, z] (y relative to the plot floor) to fixture-relative."""
    return [PLOT[0] + p[0], PLOT[1] + p[1], PLOT[2] + p[2]]


class Course:
    def __init__(self, args):
        self.args = args
        self.c, self.s = Kernel(timeout=120), server_kernel(120)
        self.evidence: dict = {"ok": False, "cases": {}, "started": time.strftime("%Y-%m-%dT%H:%M:%S")}

    # -- setup
    def setup(self):
        self.c.call("obs.player")
        state = self.c.call("time.status")["state"]
        self.evidence["previousClock"] = state.get("conditions")
        self.c.call("time.configure", healthDrop=False, healthBelow=-1, airBelow=-1, foodBelow=-1, burning=False,
                    threatWithin=-1, actionFailed=False, pauseOnDisconnect=False)
        if state.get("paused"): self.c.call("time.resume")
        status = None
        try: status = self.s.call(FIX + ".status")
        except BridgeError: pass
        created = self.s.call(FIX + ".create", timeout=300) if status is None else status
        self.origin = created["origin"]          # world [x, 175, z] of the fixture's corner
        self.evidence["origin"] = self.origin

    def teardown(self):
        for step in (lambda: self.c.call("act.stop"),
                     lambda: self.s.call(FIX + ".restore") if not self.args.keep else None,
                     lambda: self.c.call("time.configure", **{**(self.evidence.get("previousClock") or {}), "pauseOnDisconnect": False})):
            try: step()
            except Exception as e: self.evidence.setdefault("teardownErrors", []).append(str(e))

    # -- world helpers (fixture-relative coordinates for the setters, world coordinates for the tools)
    def world(self, p):
        q = at(p)
        return [self.origin[0] + q[0], q[1], self.origin[2] + q[2]]

    def set(self, p, block, meta=0):
        q = at(p)
        self.s.call(FIX + ".set_block", x=q[0], y=q[1], z=q[2], id=block, meta=meta)

    def inspect(self, p):
        q = at(p)
        return self.s.call(FIX + ".inspect_block", x=q[0], y=q[1], z=q[2])

    def clear(self):
        for x in range(PLOT_SIZE[0]):
            for y in range(PLOT_SIZE[1]):
                for z in range(PLOT_SIZE[2]):
                    self.set((x, y, z), "minecraft:air")

    def loadout(self, stacks):
        for slot, stack in enumerate(stacks):
            self.s.call(FIX + ".set_stack", slot=slot, id=stack["id"], meta=stack.get("meta", 0), count=stack.get("count", 16),
                        **({"nbt": stack["nbt"]} if "nbt" in stack else {}))

    def start(self):
        self.s.call(FIX + ".position", name="build")
        time.sleep(1.0)

    def build(self, steps, preview=True, **kw):
        """Preview then build at the plot's world corner; returns (preview, receipt, wall seconds)."""
        origin = self.world((0, 0, 0))
        seen = work.mb_build_preview(steps=steps, origin=origin, **{k: v for k, v in kw.items() if k in ("access", "allow_break", "allow_place")}) if preview else None
        t = time.monotonic()
        try: receipt = work.mb_build(steps=steps, origin=origin, timeout_ticks=3600, timeout_s=300, **kw)
        except BridgeError as e: receipt = ((e.reply or {}).get("error") or {}).get("receipt") or {"error": str(e)}
        return seen, receipt, round(time.monotonic() - t, 1)

    # -- cases
    def case_hopper_under_tile(self):
        """A hopper under a block with a tile entity, fed sideways into a chest: the incident geometry.
        Looking down is blocked; a stance two blocks west sees the chest's west face under the furnace."""
        self.set((3, 0, 3), "minecraft:chest", 4); self.set((2, 1, 3), "minecraft:furnace", 2)
        self.set((2, 0, 2), "minecraft:cobblestone"); self.set((2, 0, 4), "minecraft:cobblestone")
        self.loadout([{"id": "minecraft:hopper"}])
        steps = [{"name": "hopper", "kind": "place", "pos": [2, 0, 3], "id": "minecraft:hopper",
                  "click": {"face": "west"},
                  "expect": [{"method": "obs.block", "pos": [2, 0, 3], "path": "meta", "equals": 5, "faces": True}]}]  # a hopper's meta is the face it feeds
        seen, r, wall = self.build(steps)
        got = self.inspect((2, 0, 3))
        ok = r.get("state") == "succeeded" and got.get("id") == "minecraft:hopper" and got.get("meta") == 5
        self.job_hopper = r.get("jobId") if ok else None
        return ok, {"preview": seen, "receipt": r, "wall": wall, "server": got}

    def case_hopper_boxed(self):
        """The same click with every stance closed and no access: the build refuses at once, naming the cell in the way."""
        self.set((3, 0, 3), "minecraft:chest", 4); self.set((2, 1, 3), "minecraft:furnace", 2)
        for p in ((2, 0, 2), (2, 0, 4), (1, 0, 3), (1, 1, 3)): self.set(p, "minecraft:cobblestone")
        self.loadout([{"id": "minecraft:hopper"}])
        steps = [{"name": "hopper", "kind": "place", "pos": [2, 0, 3], "id": "minecraft:hopper", "click": {"face": "west"}}]
        seen, r, wall = self.build(steps)
        why = str(r.get("reason") or r.get("error") or "")
        ok = r.get("state") == "failed" and why.startswith(("no_vantage", "look_unreachable")) and "blocked by" in why and wall < REFUSE_WALL_S
        return ok, {"preview": seen, "receipt": r, "wall": wall}

    def case_machine_six_faces(self):
        """One machine placed against each of the six faces of a pillar and a ceiling block; records the facing each made."""
        for y in range(3): self.set((5, y, 4), "minecraft:stone")
        self.set((8, 3, 4), "minecraft:stone")
        self.loadout([{**ITEMS["machine"], "count": 6}])
        cells = {"west": [4, 1, 4], "east": [6, 1, 4], "north": [5, 1, 3], "south": [5, 1, 5], "up": [5, 3, 4], "down": [8, 2, 4]}
        steps = [{"name": f, "kind": "place", "pos": p, "id": ITEMS["machine_block"], "item": ITEMS["machine"], "click": {"face": f}}
                 for f, p in cells.items()]
        seen, r, wall = self.build(steps)
        tiles = {f: (self.inspect(p).get("tile") or {}) for f, p in cells.items()}
        facings = {f: t.get("mFacing", t.get("facing")) for f, t in tiles.items()}
        receipts = r.get("receipts") or []
        ok = r.get("state") == "succeeded" and len({json.dumps(v) for v in facings.values()}) == 6
        return ok, {"preview": seen, "receipt": r, "wall": wall, "facings": facings,
                    "results": [x.get("result") for x in receipts]}

    def case_cover_by_grid(self):
        """A cover put on the east side by clicking the north face near its east edge (the machine's side grid)."""
        self.loadout([ITEMS["machine"], ITEMS["cover"]])
        steps = [{"name": "machine", "kind": "place", "pos": [4, 0, 4], "id": ITEMS["machine_block"], "item": ITEMS["machine"], "click": {"face": "up"}},
                 {"name": "cover", "kind": "use", "pos": [4, 0, 4], "item": ITEMS["cover"], "click": {"face": "north", "hit": [0.92, 0.5, 0.0]},
                  "expect": [{"method": "obs.tile", "params": {"detail": "full"}, "pos": [4, 0, 4], "path": "", "changed": True}]}]
        seen, r, wall = self.build(steps)
        tile = self.inspect((4, 0, 4)).get("tile") or {}
        sides = tile.get("mCoverSides")
        ok = r.get("state") == "succeeded" and isinstance(sides, list) and len(sides) == 6 and sides[5] != 0 and sides[2] == 0
        return ok, {"preview": seen, "receipt": r, "wall": wall, "coverSides": sides}

    def case_pipe_branch(self):
        """A straight pipe and a branch joined with a wrench on the centre pipe's face toward the branch."""
        self.loadout([{**ITEMS["pipe"], "count": 4}, ITEMS["wrench"]])
        line = [[3, 0, 4], [4, 0, 4], [5, 0, 4]]
        steps = [{"name": f"pipe{i}", "kind": "place", "pos": p, "id": ITEMS["machine_block"], "item": ITEMS["pipe"], "click": {"face": "up"}}
                 for i, p in enumerate(line)]
        steps.append({"name": "branch", "kind": "place", "pos": [4, 0, 5], "id": ITEMS["machine_block"], "item": ITEMS["pipe"], "click": {"face": "up"}})
        steps.append({"name": "connect", "kind": "use", "pos": [4, 0, 4], "item": ITEMS["wrench"], "click": {"face": "up", "hit": [0.5, 1.0, 0.92]},
                      "expect": [{"method": "obs.tile", "params": {"detail": "full"}, "pos": [4, 0, 4], "path": "", "changed": True}]})
        seen, r, wall = self.build(steps)
        tile = self.inspect((4, 0, 4)).get("tile") or {}
        ok = r.get("state") == "succeeded"
        return ok, {"preview": seen, "receipt": r, "wall": wall, "centre": {k: tile.get(k) for k in ("mConnections", "mCoverSides")}}

    def case_pattern_rotated(self):
        """Save the hopper job as a pattern, rebuild it turned 90 degrees: the hopper must now feed south."""
        if not getattr(self, "job_hopper", None): return False, {"skipped": "hopper_under_tile did not succeed in this run"}
        name = f"smoke_hopper_{int(time.time())}"
        saved = patterns.mb_pattern("save", name, job_id=self.job_hopper)
        try:
            # The fixture blocks turned by hand: chest south of the hopper, furnace above, walls east and west.
            self.set((3, 0, 4), "minecraft:chest", 2); self.set((3, 1, 3), "minecraft:furnace", 2)
            self.set((2, 0, 3), "minecraft:cobblestone"); self.set((4, 0, 3), "minecraft:cobblestone")
            self.loadout([{"id": "minecraft:hopper"}])
            t = time.monotonic()
            try: r = work.mb_build(pattern={"name": name, "at": self.world((3, 0, 3)), "rotate": 90}, timeout_ticks=3600, timeout_s=300)
            except BridgeError as e: r = ((e.reply or {}).get("error") or {}).get("receipt") or {"error": str(e)}
            got = self.inspect((3, 0, 3))
            ok = r.get("state") == "succeeded" and got.get("id") == "minecraft:hopper" and got.get("meta") == 3
            return ok, {"saved": saved, "receipt": r, "wall": round(time.monotonic() - t, 1), "server": got}
        finally:
            (patterns.PATTERNS / f"{name}.json").unlink(missing_ok=True)

    def case_access_beside_tile(self):
        """The only view of a click passes a cobblestone next to a furnace. Without bounds access leaves it (near a tile
        entity) and the build refuses; with bounds it takes the cobblestone, clicks, and puts the same block back."""
        def scene():
            self.clear()
            self.set((3, 0, 3), "minecraft:chest", 4); self.set((2, 1, 3), "minecraft:furnace", 2)
            for p in ((2, 0, 2), (2, 0, 4), (1, 1, 3)): self.set(p, "minecraft:cobblestone")
            self.set((1, 0, 3), "minecraft:cobblestone")       # the one block in the way
            self.loadout([{"id": "minecraft:hopper"}, {"id": "minecraft:dirt"}])
        steps = [{"name": "hopper", "kind": "place", "pos": [2, 0, 3], "id": "minecraft:hopper", "click": {"face": "west"}}]
        scene()
        seen0, r0, wall0 = self.build(steps, access={"allow": True})
        scene()
        bounds = [{"min": self.world((0, 0, 0)), "max": self.world((PLOT_SIZE[0] - 1, PLOT_SIZE[1] - 1, PLOT_SIZE[2] - 1))}]
        seen1, r1, wall1 = self.build(steps, access={"allow": True, "bounds": bounds})
        back = self.inspect((1, 0, 3))
        log = (r1.get("access") or [])
        ok = (r0.get("state") == "failed" and r1.get("state") == "succeeded" and back.get("id") == "minecraft:cobblestone"
              and not any(e.get("substitute") or e.get("late") for e in log if isinstance(e, dict)))
        return ok, {"withoutBounds": {"preview": seen0, "receipt": r0, "wall": wall0},
                    "withBounds": {"preview": seen1, "receipt": r1, "wall": wall1, "restored": back}}

    CASES = ("hopper_under_tile", "hopper_boxed", "machine_six_faces", "cover_by_grid", "pipe_branch", "pattern_rotated", "access_beside_tile")

    def run(self):
        names = self.args.case or list(self.CASES)
        try:
            self.setup()
            for name in names:
                t = time.monotonic()
                try:
                    self.clear(); self.start()
                    ok, detail = getattr(self, f"case_{name}")()
                except Exception as e:
                    ok, detail = False, {"error": f"{type(e).__name__}: {e}"[:1500]}
                try: self.c.call("act.stop")
                except BridgeError: pass
                self.evidence["cases"][name] = {"ok": ok, "seconds": round(time.monotonic() - t, 1), **detail}
                print(f"{name:22} {'ok' if ok else 'FAIL':5} {self.evidence['cases'][name]['seconds']:7.1f}s", flush=True)
            self.evidence["ok"] = all(c["ok"] for c in self.evidence["cases"].values())
        finally:
            self.teardown()
            OUT.parent.mkdir(parents=True, exist_ok=True)
            OUT.write_text(json.dumps(self.evidence, indent=1, default=str) + "\n", encoding="utf-8", newline="\n")
            print(f"evidence: {OUT}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--case", action="append", choices=Course.CASES, help="case name (repeatable); default all, in order")
    ap.add_argument("--keep", action="store_true", help="leave the fixture and the journalled player in place")
    args = ap.parse_args()
    course = Course(args)
    course.run()
    return 0 if course.evidence["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
