# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""GUI and inventory tools plus ContainerSession, the guarded composition helper for model-written routines.

Each ContainerSession mutation is guarded by the observed screen epoch and cursor/stack. A session
does not reserve the GUI between calls; keep one across tool calls in ``mbtool.state`` if you need
to. On failure inspect the attached receipts and current state; never replay a whole routine
automatically. Native click acceptance is not proof of machine completion.
"""

from __future__ import annotations

import time
from typing import Any

from mbtool import BridgeError, kernel, tool
from mbtools_gtnh import notes
from mbtools_gtnh.core import lane_by_method, method_name


@tool(lane=lane_by_method("gui"), coverage=["meta"])
def mb_gui(method: str, params: dict | None = None) -> Any:
    """General UI primitives: click_slot, transfer, return_cursor, click_at, drag,
    scroll, key, type, text_field, button, container_button, hover, hit_test, status,
    open_inventory, close. Use mb_methods for parameter schemas. Native event clicks
    reach modded/ghost slots; structured clicks and exact transfers use ordinary slots.
    Observe windowId/epoch and pass expected stacks/cursor to guard stale state.
    Inspect receipts after partial effects; never retry a click merely because its
    visible slot did not change. Custom machine packets need their own postconditions.
    Resume world time before mutating. Build reusable routines in reloadable tool
    modules using these primitives; mbtools_gtnh.inventory.ContainerSession helps composition.
    """
    return kernel().call(method_name("gui", method), **(params or {}))


@tool(lane="read", coverage=["inventory"])
def mb_inventory(detail: str = "full", container: bool = False) -> Any:
    """Observe item identities with metadata/NBT, cursor and slot ownership.
    Player detail: full, compact, counts. Container detail: summary, full, compact;
    full includes bounded custom widget inspection. Container indices differ from
    player inventory indices. Use observed clickAt coordinates for native events.
    """
    return notes.with_item_notes(kernel().call("obs.container" if container else "obs.inventory", detail=detail))


@tool(lane="read", coverage=["inventory"])
def mb_find(selector: dict, scope: str = "player") -> Any:
    """Find actual held/container items by exact {id, meta?, nbt_hash?, nbt?}.
    Scope player returns player inventory indices; container returns current slot
    indices. Metadata and NBT variants remain separate. Use mb_item_search for the NEI catalogue.
    """
    return kernel().call("obs.find", selector=selector, scope=scope)


@tool(coverage=["inventory"])
def mb_transfer(window_id: int, epoch: int, source: int, expected: dict,
                destinations: list[int], count: int, destination_policy: str = "passive") -> Any:
    """Move up to count (1..64) items through native clicks to explicit ordinary slots.
    Pass windowId, epoch and expected = the source slot's stack object exactly as
    mb_inventory returned it (every field, not just id/meta/count): a shortened object
    fails with stale_stack. Requires empty cursor; source
    must accept its remainder (output/ghost slots require event clicks instead).
    Capacity may produce a partial move: inspect transfer.moved. Passive destinations
    must retain items; consuming allows fuel/machine processing without assuming
    disappearance proves consumption. Receipt includes server transaction acceptance,
    immediate/settled deltas and cursor. Errors may contain partial effects. No retry.
    """
    return kernel().call("gui.transfer", windowId=window_id, epoch=epoch, source=source,
                         expected=expected, destinations=destinations, count=count,
                         destinationPolicy=destination_policy, expectedCursor=None)


@tool(coverage=["inventory"])
def mb_click_slot(window_id: int, epoch: int, slot: int, expected: dict | None,
                  expected_cursor: dict | None, click_type: str = "pickup", button: int = 0,
                  path: str | None = None) -> Any:
    """Click an observed slot with explicit stale-stack/cursor guards.
    expected/expected_cursor null means "must be empty"; pass the observed stack otherwise.
    Pickup/quick_move/clone default to native events for custom and virtual slots.
    Swap (button=hotbar 0..8), throw and pickup_all default to structured ordinary
    slot clicks. Does not retry or infer crafting completion. Use gui.drag for native
    distribution and gui.return_cursor for recovery to explicit safe destinations.
    """
    params = dict(windowId=window_id, epoch=epoch, slot=slot, expected=expected,
                  expectedCursor=expected_cursor, type=click_type, button=button)
    if path is not None:
        params["path"] = path
    return kernel().call("gui.click_slot", **params)


class ProcedureStopped(RuntimeError):
    def __init__(self, reason, receipts):
        self.receipts = list(receipts)
        super().__init__(f"{reason}; completed/partial receipts: {self.receipts}")


class ContainerSession:
    def __init__(self, kernel):
        self.kernel = kernel
        self.receipts = []
        initial = kernel.call("obs.container")
        if not initial["open"]:
            raise ValueError("open a container before composing UI operations")
        self.window_id, self.epoch = initial["windowId"], initial["epoch"]

    def observe(self):
        try:
            view = self.kernel.call("obs.container")
        except (BridgeError, TimeoutError, ConnectionError) as error:
            raise ProcedureStopped(str(error), self.receipts) from error
        if (view["windowId"], view["epoch"]) != (self.window_id, self.epoch) or not view["open"]:
            raise ProcedureStopped("screen/container changed", self.receipts)
        return view

    def _mutate(self, method, view, **params):
        try:
            result = self.kernel.call(method, windowId=self.window_id, epoch=self.epoch,
                                      expectedCursor=view.get("cursor"), **params)
        except (BridgeError, TimeoutError, ConnectionError) as error:
            if isinstance(error, BridgeError) and error.reply:
                self.receipts.append({"failed": error.reply})
            else:
                self.receipts.append({"uncertain": {"method": method, "reason": str(error)}})
            raise ProcedureStopped(str(error), self.receipts) from error
        self.receipts.append(result)
        if result.get("state") != "completed":
            raise ProcedureStopped("operation changed the screen or did not complete", self.receipts)
        return result

    def click(self, slot, click_type="pickup", button=0, path=None):
        view = self.observe()
        current = next(s for s in view["slots"] if s["i"] == slot)
        params = dict(slot=slot, expected=current.get("stack"), type=click_type, button=button)
        if path is not None:
            params["path"] = path
        return self._mutate("gui.click_slot", view, **params)

    def transfer(self, source, destinations, count, destination_policy="passive"):
        view = self.observe()
        current = next(s for s in view["slots"] if s["i"] == source)
        result = self._mutate("gui.transfer", view, source=source, expected=current.get("stack"),
                              destinations=destinations, count=count, destinationPolicy=destination_policy)
        if result["transfer"]["moved"] != count:
            raise ProcedureStopped("only part of requested quantity fit", self.receipts)
        return result

    def wait_for(self, predicate, timeout_s=30, poll_s=.25):
        """Observe until an adapter-specific postcondition holds; never repeats inputs."""
        if not 0 < timeout_s <= 3600 or not .05 <= poll_s <= 10:
            raise ValueError("timeout_s must be (0,3600], poll_s must be .05..10")
        deadline = time.monotonic() + timeout_s
        while True:
            view = self.observe()
            if predicate(view):
                return view
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise ProcedureStopped("postcondition timed out; inspect before retrying", self.receipts)
            time.sleep(min(poll_s, remaining))


def _matches(stack, want):
    return bool(stack) and stack["id"] == want["id"] and want.get("meta") in (None, stack.get("meta"))


def _player(view):
    return [s for s in view["slots"] if s["kind"] not in ("container", "armor")]


def _station(k, at):
    """The GUI one mb_craft call works in: the one already open, else the block at `at`, else your inventory. True if this call opened it."""
    seen = k.call("obs.container")
    if seen["open"]:
        if at is None or not seen["class"].endswith("ContainerPlayer"): return False
        k.call("gui.close")  # your own inventory left open (an interrupted craft does that) is not the station you named
    if at is None: k.call("gui.open_inventory")
    else: k.call("act.use_block", x=at[0], y=at[1], z=at[2], face=1)
    deadline = time.monotonic() + 3
    while not k.call("obs.container")["open"]:
        if time.monotonic() > deadline:
            raise ValueError(f"no GUI opened at {at}: stand within reach (4 blocks) with a clear line to the block, and resume time first")
        time.sleep(.1)
    return True


def _grid(session, pattern, times):
    view = session.observe()
    out = next((s for s in view["slots"] if s["slotClass"].endswith("SlotCrafting")), None)
    groups: dict = {}
    for s in view["slots"]:
        if out and s["kind"] == "container" and s["inventory"] != out["inventory"]: groups.setdefault(s["inventory"], []).append(s)
    grid = next((g for g in groups.values() if len(g) in (4, 9)), None)  # a table variant's own storage is another inventory
    if grid is None:
        raise ValueError("this GUI has no crafting grid: give pattern for your inventory (2x2) or a crafting table at `at` (3x3), inputs for a machine")
    width = {4: 2, 9: 3}[len(grid)]
    if len(pattern) > width or max(map(len, pattern)) > width:
        raise ValueError(f"pattern does not fit this {width}x{width} grid" + ("; pass at=[x,y,z] of a crafting table for 3x3 recipes" if width == 2 else ""))
    if any(s.get("stack") for s in grid):
        raise ValueError("the crafting grid must be empty before mb_craft")
    for row in pattern:
        for want in row:
            if want and (type(want.get("count", times)) is not int or not 1 <= want.get("count", times) <= 64):
                raise ValueError("pattern cell count must be an integer in 1..64")
    stacks = {s["i"]: s["stack"] for s in _player(view) if s.get("stack")}
    have = {i: st["count"] for i, st in stacks.items()}
    try:
        for r, row in enumerate(pattern):
            for c, want in enumerate(row):
                need = want.get("count", times) if want else 0
                while need:
                    source = next((i for i, st in stacks.items() if have[i] and _matches(st, want)), None)
                    if source is None:
                        raise ProcedureStopped(f"not enough {want['id']} in your inventory for {times} craft(s)", session.receipts)
                    moved = min(need, have[source])
                    session.transfer(source, [grid[r * width + c]["i"]], moved)
                    have[source] -= moved; need -= moved
        result = next(s for s in session.observe()["slots"] if s["i"] == out["i"]).get("stack")
        if not result:
            raise ProcedureStopped("the game shows no output for this pattern: it is not a recipe in this pack as laid out; check mb_recipes", session.receipts)
        session.click(out["i"], "quick_move")
    except ProcedureStopped:
        for s in session.observe()["slots"]:  # put the ingredients back so the next attempt starts clean and closing drops nothing
            if s["i"] in {g["i"] for g in grid} and s.get("stack"): session.click(s["i"], "quick_move")
        raise
    for s in session.observe()["slots"]:
        if s["i"] in {g["i"] for g in grid} and s.get("stack"):
            session.click(s["i"], "quick_move")
    if any(s.get("stack") for s in session.observe()["slots"] if s["i"] in {g["i"] for g in grid}):
        raise ProcedureStopped("craft finished but ingredients remain in the grid; make inventory space", session.receipts)
    gained = sum(s["stack"]["count"] for s in _player(session.observe()) if _matches(s.get("stack"), result))
    return {"crafted": result, "gained": gained - sum(st["count"] for st in stacks.values() if _matches(st, result))}


def _machine(session, inputs, wait_s):
    k, loaded, collected, output = session.kernel, [], [], {}
    for want in inputs:
        need = int(want.get("count", 1))
        while need:
            source = next((s for s in _player(session.observe()) if _matches(s.get("stack"), want)), None)
            if source is None:
                raise ProcedureStopped(f"not enough {want['id']} in your inventory", session.receipts)
            # The slot's own validity check decides where an item may go, so this works for any machine without a slot table.
            dest = next((s for s in k.call("obs.container", probeSlot=source["i"])["slots"] if s["kind"] == "container" and s["ordinary"]
                         and s.get("spaceForProbe") and want.get("slot") in (None, s["i"])), None)
            if dest is None:
                raise ProcedureStopped(f"no free slot of this GUI accepts {want['id']}", session.receipts)
            moved = min(need, source["stack"]["count"], dest["spaceForProbe"])
            session.transfer(source["i"], [dest["i"]], moved, "consuming")
            loaded.append({"slot": dest["i"], "id": want["id"], "count": moved}); need -= moved
    deadline = time.monotonic() + wait_s
    while True:
        for s in session.observe()["slots"]:
            stack = s.get("stack")
            if s["kind"] != "container" or not stack or not s["canTake"]: continue
            key = (s["i"], stack["id"], stack.get("meta"))
            if key not in output:  # an output slot is one that would not take its own contents back
                try: output[key] = not next(p for p in k.call("obs.container", probeSlot=s["i"])["slots"] if p["i"] == s["i"])["acceptsProbe"]
                except BridgeError: continue  # a running machine emptied the slot between the two looks; the next pass sees it
            if output[key]:
                session.click(s["i"], "quick_move")
                left = next(p for p in session.observe()["slots"] if p["i"] == s["i"]).get("stack")
                if left and left["count"] == stack["count"]:
                    raise ProcedureStopped("the output did not move: your inventory is full", session.receipts)
                collected.append({"id": stack["id"], "meta": stack.get("meta"), "count": stack["count"] - (left or {}).get("count", 0)})
        inside = [dict(slot=s["i"], id=s["stack"]["id"], meta=s["stack"].get("meta"), count=s["stack"]["count"])
                  for s in session.observe()["slots"] if s["kind"] == "container" and s.get("stack")]
        slots = {x["slot"] for x in loaded}  # done when what this call loaded is used up, or, collecting only, when the machine is empty
        if time.monotonic() >= deadline or not any(not slots or i["slot"] in slots for i in inside): break
        time.sleep(1)
    return {"loaded": loaded, "collected": collected, "inside": inside}


@tool(coverage=["inventory"])
def mb_craft(pattern: list[list[dict | None]] | None = None, times: int = 1, at: list[int] | None = None,
             inputs: list[dict] | None = None, wait_s: float = 0.0) -> Any:
    """Make something in ONE call, at any station with a GUI: it opens the station, moves the items, takes the result and closes.

    Look the recipe up first (mb_recipes), every time it is new to you: assume no recipe in this pack,
    vanilla ones least of all.
    Station: at=[x,y,z] is the block to open (crafting table or a variant, furnace, any machine);
    omit it for your inventory's own 2x2 grid. Stand within reach. A GUI that is already open is
    used as it is and left open.
    Grid crafting: pattern is rows of cells, each {id, meta?} or null, laid out as mb_recipes
    shows the shaped recipe, e.g. sticks: [[{"id":"minecraft:planks"}],[{"id":"minecraft:planks"}]].
    times (1..64) loads that many items per cell; a cell's count overrides its total load
    (e.g. count:1 for a retained mortar). The native shift-click decides how many crafts
    actually run; gained reports the observed result. Remaining tools/ingredients are returned.
    If the game shows no output the pattern is not a recipe in this
    pack (GTNH changes many vanilla recipes and often wants a tool in the grid): the ingredients
    go back and the error says so. Returns {crafted, gained}: crafted is what ONE craft yields, gained
    is how many you now have more than before.
    Machines: inputs is [{id, meta?, count, slot?}] in the order to load. Each goes to the first
    slot the machine itself accepts it in (furnace: [ore, fuel]); slot forces one. Then every
    output slot is emptied into your inventory, again for up to wait_s seconds (0..300) while the
    machine runs; it returns early once the loaded slots are empty. Returns {loaded, collected,
    inside}: inside is what is still in the machine. For a long job load with wait_s=0, do other
    work, and come back with mb_craft(at=...) alone, which only collects. Fluids, steam, power and
    circuits are yours to arrange; for recipes made in the world rather than in a GUI (dropping
    items, multiblocks fed by hatches) compose the primitives and save your own tool.
    It never retries; on a stop, read the receipts and observe.
    """
    if pattern is not None and inputs is not None:
        raise ValueError("give pattern (a crafting grid) or inputs (a machine), not both")
    if not 1 <= times <= 64 or not 0 <= wait_s <= 300 or pattern is not None and not (pattern and all(isinstance(row, list) and row for row in pattern)):
        raise ValueError("pattern is a non-empty list of rows; times is 1..64; wait_s is 0..300")
    k = kernel()
    opened = _station(k, at)
    try:
        session = ContainerSession(k)
        if session.observe().get("cursor"):
            raise ValueError("the cursor must be empty before mb_craft")
        result = _grid(session, pattern, times) if pattern else _machine(session, inputs or [], wait_s)
        return notes.with_item_notes(dict(result, clicks=len(session.receipts)))
    finally:
        if opened:
            try: k.call("gui.close")
            except BridgeError: pass  # a stop with the cursor full leaves the GUI open for you to inspect
