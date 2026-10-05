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
from mbtools_gtnh.core import lane_by_method, method_name, no_threat


@tool(lane=lane_by_method("gui"), coverage=["meta"])
def mb_gui(method: str, params: dict | None = None) -> Any:
    """General UI primitives: click_slot, transfer, return_cursor, click_at, drag,
    scroll, key, type, text_field, button, container_button, hover, hit_test, status,
    open_inventory, close. mb_methods(name="gui.") has the parameter schemas. Native event clicks
    reach modded/ghost slots; structured clicks and exact transfers use ordinary slots.
    Observe windowId/epoch and pass expected stacks/cursor to guard stale state.
    Inspect receipts after partial effects; never retry a click merely because its
    visible slot did not change. Custom machine packets need their own postconditions.
    Resume world time before mutating. Build reusable routines in reloadable tool
    modules using these primitives; mbtools_gtnh.inventory.ContainerSession helps composition.
    """
    return kernel().call(method_name("gui", method), **(params or {}))


@tool(lane="read", coverage=["inventory"])
def mb_inventory(detail: str = "compact", container: bool = False) -> Any:
    """Observe item identities with metadata/NBT, cursor and slot ownership.
    Player detail: compact (the default: occupied slots), full (all 36), counts (totals
    per item). Container detail: compact (the default: every container slot and your
    occupied ones, with stacks and slot flags), summary (adds slot x/y and clickAt,
    buttons, labels and the custom widget tree) or full (summary plus every GUI field):
    tens of kilobytes on a machine GUI, so ask for them only to click what is not a slot.
    Container indices differ from player inventory indices. Native events take the
    clickAt coordinates summary and full observe.
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

    def click(self, slot, click_type="pickup", button=0, path=None, growing=False):
        """growing: a slot a machine is still filling. A press the stale-stack guard refused because the
        same stack grew since the look is tried again on a fresh look, twice at most; nothing sent is repeated."""
        view = self.observe()
        for again in (True, True, False):
            current = next(s for s in view["slots"] if s["i"] == slot)
            params = dict(slot=slot, expected=current.get("stack"), type=click_type, button=button)
            if path is not None:
                params["path"] = path
            try:
                return self._mutate("gui.click_slot", view, **params)
            except ProcedureStopped:
                error = self.receipts[-1].get("failed", {}).get("error") or {}
                if not (growing and again and current.get("ordinary") and str(error.get("msg")).startswith("stale_stack")
                        and ((error.get("receipt") or {}).get("transactions") or {}).get("sent") == 0):
                    raise
                was, cursor, view = current.get("stack"), view.get("cursor"), self.observe()
                now = next(s for s in view["slots"] if s["i"] == slot).get("stack")
                if (view.get("cursor") != cursor or not was or not now
                        or {**was, "count": 0} != {**now, "count": 0} or now["count"] <= was["count"]):
                    raise

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
    """Every field the selector gives must hold: id, meta, nbt_hash or nbt exactly, name as a case-insensitive part of the display name."""
    return (bool(stack) and all(want.get(k) in (None, stack.get(k)) for k in ("id", "meta", "nbt_hash", "nbt"))
            and str(want.get("name", "")).lower() in str(stack.get("name", "")).lower())


def _precious(stack):
    """Why a stack is not junk by default: it carries NBT (a tool that keeps its state there, a named or enchanted item) or is worn. None if neither."""
    return "carries NBT" if stack.get("nbt") or stack.get("nbt_hash") else "damaged" if (stack.get("dmg") or [0])[0] else None


def _names(want):
    """A selector names NBT-carrying/damaged stacks explicitly when it picks them by NBT or name, or says withNbt."""
    return any(want.get(k) for k in ("nbt_hash", "nbt", "name", "withNbt"))


def _player(view):
    return [s for s in view["slots"] if s["kind"] not in ("container", "armor")]


def _held(view, stack):
    """How many items like stack (same id, meta, nbt_hash, nbt) the player's own slots of this view hold."""
    identity = {k: stack[k] for k in ("id", "meta", "nbt_hash", "nbt") if k in stack}
    return sum(s["stack"]["count"] for s in _player(view) if _matches(s.get("stack"), identity))


def _empty_hand(k):
    """Open blocks without letting a held item's interaction intercept the GUI click."""
    view = k.call("obs.inventory", detail="full")
    if not view.get("held"):
        return
    empty = next((s for s in view["main"] if not s.get("stack") and s["kind"] == "hotbar"), None)
    if empty is not None:
        k.call("act.select_hotbar", slot=empty["slot"])
    else:
        if not any(not s.get("stack") for s in view["main"]):
            raise ValueError("opening a block GUI needs an empty hand: make one inventory slot free, or open the GUI explicitly with mb_act first")
        k.call("gui.open_inventory")
        session = ContainerSession(k)
        empty = next(s for s in session.observe()["slots"] if s["kind"] == "main" and not s.get("stack"))
        session.click(empty["i"], "swap", button=view["selected"])
        k.call("gui.close")
    if k.call("obs.inventory", detail="compact").get("held"):
        raise ValueError("empty-hand preparation was not observed; inspect inventory before opening the block")
    return (view["selected"], empty["idx"]) if empty.get("kind") == "main" else None


def _station(k, at, despite_threat=False, face=None, hit=None):
    """The GUI one mb_craft call works in: the one already open, else the block at `at`, else your inventory. True if this call opened it."""
    seen = k.call("obs.container")
    if seen["open"]:
        if at is None or not seen["class"].endswith("ContainerPlayer"): return False
    no_threat("open a GUI", k, despite_threat)
    if seen["open"]:
        k.call("gui.close")  # your own inventory left open (an interrupted craft does that) is not the station you named
    restore = None
    if at is None: k.call("gui.open_inventory")
    else:
        restore = _empty_hand(k)
        target = dict(x=at[0], y=at[1], z=at[2])
        if face is not None: target["face"] = face
        if hit is not None: target["hit"] = hit
        k.call("act.use_block", **target)
    deadline = time.monotonic() + 3
    while not k.call("obs.container")["open"]:
        if time.monotonic() > deadline:
            raise ValueError(f"no GUI opened at {at}: stand within reach (4 blocks) with a clear line to the block, and resume time first")
        time.sleep(.1)
    if restore is not None:
        session = ContainerSession(k)
        original = next(s for s in session.observe()["slots"] if s["kind"] == "main" and s["idx"] == restore[1])
        session.click(original["i"], "swap", button=restore[0])
    return True


def _grid_slots(view, grid, result_slot):
    """The crafting grid as rows of slots, its result slot, and where they came from: the game's own crafting inventory
    (the result slot reports craftResultOf) unless grid/result_slot name the slot indices."""
    slots = {s["i"]: s for s in view["slots"]}
    if any(i not in slots for i in [*(i for row in grid or [] for i in row), *([result_slot] if result_slot is not None else [])]):
        raise ValueError("grid and result_slot must be slot indices of this GUI (mb_inventory(container=True) lists them)")
    out = slots[result_slot] if result_slot is not None else next((s for s in view["slots"] if s.get("craftResultOf")), None)
    if grid is not None:
        if out is None: raise ValueError("this GUI shows no crafting result slot: pass result_slot with grid")
        return [[slots[i] for i in row] for row in grid], out, "grid param"
    m = (out or {}).get("craftResultOf")
    if not m:
        raise ValueError("this GUI has no crafting grid the game reports: give pattern for your inventory (2x2) or a crafting table at `at`, "
                         "inputs for a machine, or grid=[[slot, ...], ...] and result_slot if you can see one")
    cells = sorted((s for s in view["slots"] if s["inventory"] == m["inventory"] and s["i"] != out["i"]), key=lambda s: s.get("idx", s["i"]))
    if not m.get("width"): raise ValueError(f"the game did not say how wide this {len(cells)}-slot grid is: pass grid=[[slot, ...], ...] rows")
    return [cells[r:r + m["width"]] for r in range(0, len(cells), m["width"])], out, "game"


def _grid(session, pattern, times, grid=None, result_slot=None):
    view = session.observe()
    rows, out, origin = _grid_slots(view, grid, result_slot)
    cells = [s for row in rows for s in row]
    if len(pattern) > len(rows) or any(len(row) > len(rows[r]) for r, row in enumerate(pattern)):
        raise ValueError(f"pattern does not fit this grid of {[len(r) for r in rows]} slots per row" + ("; pass at=[x,y,z] of a crafting table for 3x3 recipes" if len(rows) == 2 else ""))
    if any(s.get("stack") for s in cells):
        raise ValueError("the crafting grid must be empty before mb_craft")
    for r, row in enumerate(pattern):
        for c, want in enumerate(row):
            limit = rows[r][c].get("limit")  # the slot's own stack limit, as the game reports it
            if want and (type(want.get("count", times)) is not int or want.get("count", times) < 1 or limit and want.get("count", times) > limit):
                raise ValueError(f"pattern cell count must be an integer from 1 to this slot's limit ({limit})")
    stacks = {s["i"]: s["stack"] for s in _player(view) if s.get("stack")}
    have = {i: st["count"] for i, st in stacks.items()}
    try:
        for r, row in enumerate(pattern):
            for c, want in enumerate(row):
                need = want.get("count", times) if want else 0
                while need:
                    source = next((i for i, st in stacks.items() if have[i] and _matches(st, want)), None)
                    if source is None:
                        raise ProcedureStopped(f"not enough {want.get('id') or want.get('name')} in your inventory for {times} craft(s)", session.receipts)
                    moved = min(need, have[source], 64)  # one transfer moves at most 64; a larger cell takes several
                    session.transfer(source, [rows[r][c]["i"]], moved)
                    have[source] -= moved; need -= moved
        result = next(s for s in session.observe()["slots"] if s["i"] == out["i"]).get("stack")
        if not result:
            raise ProcedureStopped("the game shows no output for this pattern: it is not a recipe in this pack as laid out; check mb_recipes", session.receipts)
        session.click(out["i"], "quick_move")
    except ProcedureStopped:
        for s in session.observe()["slots"]:  # put the ingredients back so the next attempt starts clean and closing drops nothing
            if s["i"] in {g["i"] for g in cells} and s.get("stack"): session.click(s["i"], "quick_move")
        raise
    for s in session.observe()["slots"]:
        if s["i"] in {g["i"] for g in cells} and s.get("stack"):
            session.click(s["i"], "quick_move")
    if any(s.get("stack") for s in session.observe()["slots"] if s["i"] in {g["i"] for g in cells}):
        raise ProcedureStopped("craft finished but ingredients remain in the grid; make inventory space", session.receipts)
    kind = {"id": result["id"], "meta": result.get("meta")}
    gained = sum(s["stack"]["count"] for s in _player(session.observe()) if _matches(s.get("stack"), kind))
    return {"crafted": result, "gained": gained - sum(st["count"] for st in stacks.values() if _matches(st, kind)),
            "grid": {"slots": [[s["i"] for s in row] for row in rows], "result": out["i"], "from": origin}}


def _machine(session, inputs, wait_s):
    k, loaded, collected, output = session.kernel, [], [], {}
    for want in inputs:
        need = int(want.get("count", 1))
        while need:
            source = next((s for s in _player(session.observe()) if _matches(s.get("stack"), want)), None)
            if source is None:
                raise ProcedureStopped(f"not enough {want['id']} in your inventory", session.receipts)
            # The slot's own validity check decides where an item may go, so this works for any machine without a slot table.
            destinations = [s for s in k.call("obs.container", probeSlot=source["i"])["slots"] if s["kind"] == "container" and s["ordinary"]
                            and s.get("spaceForProbe") and want.get("slot") in (None, s["i"])]
            if not destinations:
                raise ProcedureStopped(f"no free slot of this GUI accepts {want['id']}", session.receipts)
            moved = min(need, source["stack"]["count"], sum(s["spaceForProbe"] for s in destinations))
            session.transfer(source["i"], [s["i"] for s in destinations], moved, "consuming")
            left = moved
            for dest in destinations:
                amount = min(left, dest["spaceForProbe"])
                if amount:
                    loaded.append({"slot": dest["i"], "id": want["id"], "count": amount})
                    left -= amount
            need -= moved
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
                before = _held(session.observe(), stack)
                session.click(s["i"], "quick_move", growing=True)
                gained = _held(session.observe(), stack) - before
                if gained <= 0:
                    raise ProcedureStopped("the output did not reach your inventory; inspect the GUI and available space", session.receipts)
                collected.append({"id": stack["id"], "meta": stack.get("meta"), "count": gained})
        inside = [dict(slot=s["i"], id=s["stack"]["id"], meta=s["stack"].get("meta"), count=s["stack"]["count"])
                  for s in session.observe()["slots"] if s["kind"] == "container" and s.get("stack")]
        slots = {x["slot"] for x in loaded}  # done when what this call loaded is used up, or, collecting only, when the machine is empty
        if time.monotonic() >= deadline or not any(not slots or i["slot"] in slots for i in inside): break
        time.sleep(1)
    return {"loaded": loaded, "collected": collected, "inside": inside}


def _shift(session, slot):
    """Shift-click one slot; how many items moved, measured on the player's side: automation may refill or drain the container slot meanwhile."""
    stack = slot["stack"]; before = _held(session.observe(), stack)
    direction = 1 if slot["kind"] == "container" else -1
    try:
        session.click(slot["i"], "quick_move", path="structured" if slot.get("ordinary") else None)
    except ProcedureStopped as error:
        # A rejected transaction can still have effects. Observe, report, and stop;
        # native acceptance stays authoritative and no input is replayed.
        try:
            observed = direction * (_held(session.observe(), stack) - before)
        except ProcedureStopped:
            raise error
        receipts = error.receipts + [{"observedMovement": {"id": stack["id"], "meta": stack.get("meta"), "count": observed}, "slot": slot["i"]}]
        raise ProcedureStopped(str(error), receipts) from error
    return direction * (_held(session.observe(), stack) - before)


@tool(coverage=["inventory"])
def mb_move_items(at: list[int] | None = None, put: list[dict] | str | None = None, keep: list[dict] | None = None,
                  take: list[dict] | None = None, drop: list[dict] | None = None, despite_threat: bool = False,
                  face: int | None = None, hit: list[float] | None = None) -> Any:
    """Store, fetch and discard in ONE call, at anything with a GUI: it opens the block, shift-clicks whole stacks, and closes.

    It knows no container by name. A shift-click hands the stack to the GUI, and the GUI decides where it goes and
    whether it fits: chests of any mod, crates, backpacks, a machine's input, a storage terminal taking items in.
    at=[x,y,z] is the block to open (stand within reach); omit it to work in the GUI that is already open, or,
    for drop alone, in your own inventory.
    face=0..5 and block-local hit=[x,y,z] optionally target an exposed part of the block;
    omit them to use the native visible face. They only apply when this call opens at.
    Block GUIs are opened with an observed empty hand so held tools cannot configure the block.
    With a full hotbar the held stack is temporarily parked and restored after opening; a full
    inventory needs a free slot or a GUI explicitly opened through mb_act.
    A selector is {id?, meta?, nbt_hash?, nbt?, name?}: every field given must match (name: part of the display
    name, any case); give id or name. put: selectors of stacks to move in, or "all" for everything outside your
    hotbar; keep: selectors never moved by put. take: selectors with count? for whole stacks to bring out until at
    least count (all of it without count). drop: selectors of stacks to throw on the ground in front of you: for
    junk, which you may discard freely (walk away from it, or it comes back). A stack that carries NBT or is
    damaged (a tool that keeps its state in NBT, anything named or enchanted) is not dropped unless its selector picks it by
    nbt_hash, nbt or name, or says withNbt:true; it is listed in skipped with why. Order: put, take, drop.
    Returns {put, took, dropped, unmoved, skipped, free: {you, there}}: unmoved is what found no room, free counts
    empty slots on each side afterwards. Counts are what your inventory gained or lost, so a hopper or pipe
    working the container meanwhile does not skew them; a refused click reports what moved and stops, never retried. Opening a block is refused while the clock lists a threat (the error's
    procedureReceipts name the mobs); despite_threat=True opens it anyway. Blocks that store without a GUI (barrels, drawers: right-click with the stack
    in hand, left-click to take) are driven with mb_act, not with this.
    """
    if not (put or take or drop): raise ValueError("give put, take or drop")
    if put is not None and put != "all" and not isinstance(put, list): raise ValueError('put is a list of selectors or "all"')
    if not all(isinstance(w, dict) and (w.get("id") or w.get("name")) for w in [*(put if isinstance(put, list) else []), *(keep or []), *(take or []), *(drop or [])]):
        raise ValueError("every selector is a {id?, meta?, nbt_hash?, nbt?, name?} with id or name")
    k = kernel(); opened = _station(k, at, despite_threat, face, hit)
    try:
        session = ContainerSession(k); view = session.observe()
        if view.get("cursor"): raise ValueError("the cursor must be empty before mb_move_items")
        if (put or take) and str(view.get("class", "")).endswith("ContainerPlayer"):
            raise ValueError("put and take need a container: pass at=[x,y,z] of one, or open it first")
        wanted = lambda stack, selectors: any(_matches(stack, w) for w in selectors or [])
        moved = {"put": [], "took": [], "dropped": [], "unmoved": [], "skipped": []}
        def note(key, stack, count):
            if count: moved[key].append({"id": stack["id"], "meta": stack.get("meta"), "count": count})
        for s in _player(view) if put else []:
            stack = s.get("stack")
            if not stack or wanted(stack, keep) or (put == "all" and s["kind"] == "hotbar") or (put != "all" and not wanted(stack, put)): continue
            count = _shift(session, s); note("put", stack, count); note("unmoved", stack, max(0, stack["count"] - count))
        for want in take or []:
            need = want.get("count")
            for s in session.observe()["slots"]:
                if s["kind"] != "container" or not _matches(s.get("stack"), want) or need is not None and need <= 0: continue
                count = _shift(session, s); note("took", s["stack"], count); note("unmoved", s["stack"], max(0, s["stack"]["count"] - count))
                if need is not None: need -= count
            if need is not None and need > 0: raise ProcedureStopped(f"{need} {want.get('id') or want['name']} short: not there, or no room in your inventory", session.receipts)
        for s in _player(session.observe()) if drop else []:
            hit = next((w for w in drop if _matches(s.get("stack"), w)), None)
            if hit is None: continue
            why = _precious(s["stack"])  # discarding is the one move that cannot be undone: a worked tool is not junk unless you say so
            if why and not _names(hit): moved["skipped"].append({"id": s["stack"]["id"], "meta": s["stack"].get("meta"), "name": s["stack"].get("name"), "count": s["stack"]["count"], "why": why}); continue
            session.click(s["i"], "throw", button=1); note("dropped", s["stack"], s["stack"]["count"])
        after = session.observe()["slots"]
        free = lambda kinds: sum(1 for s in after if s["kind"] in kinds and not s.get("stack"))
        return dict(moved, free={"you": free(("main", "hotbar")), "there": free(("container",))}, clicks=len(session.receipts))
    finally:
        if opened:
            try: k.call("gui.close")
            except BridgeError: pass


@tool(coverage=["inventory"])
def mb_hold(item: dict | None = None, slot: int | None = None) -> Any:
    """Select an item or an observed empty hand in ONE call, swapping inventory slots when necessary.

    item is a selector {id?, meta?, nbt_hash?, nbt?, name?} as in mb_move_items; give id or name. Of several matching
    stacks one on the hotbar wins, then the first in your inventory. slot=0..8 names the hotbar slot it goes to; omit
    it for the selected slot when your hand is empty, else an empty hotbar slot, else the selected one. Whatever was
    in that slot takes the item's old place. The swap happens in the GUI already open, else in your inventory, opened
    and closed for it. It is not refused near threats: arming yourself is what you do then. Mining picks its own tool;
    this is for what you place, use, eat, wield or throw. Omit item (or use null) to empty your hand before
    block interactions: selects an empty hotbar slot, else parks the held stack in a free inventory slot.
    Empty-hand selection requires a closed GUI and no explicit slot. Returns verified {held, slot}.
    """
    if item is not None and (not isinstance(item, dict) or not (item.get("id") or item.get("name"))):
        raise ValueError("item is a {id?, meta?, nbt_hash?, nbt?, name?} with id or name")
    if slot is not None and (type(slot) is not int or not 0 <= slot <= 8):
        raise ValueError("slot is a hotbar slot, 0..8")
    k = kernel(); inv = k.call("obs.inventory", detail="full")
    if inv.get("cursor"): raise ValueError("the cursor must be empty before mb_hold")
    if item is None:
        if slot is not None: raise ValueError("omit slot when selecting an empty hand")
        if k.call("obs.container")["open"]: raise ValueError("close the GUI before selecting an empty hand")
        _empty_hand(k)
        after = k.call("obs.inventory", detail="compact")
        if after.get("held"): raise ValueError("empty hand was not observed; inspect mb_inventory before trying again")
        return {"held": None, "slot": after["selected"]}
    found = [s for s in inv["main"] if _matches(s.get("stack"), item)]
    if not found: raise ValueError(f"no {item.get('id') or item['name']} in your inventory (mb_find with the selector shows what matches)")
    source = next((s for s in found if s["kind"] == "hotbar" and slot in (None, s["slot"])), found[0])
    if source["kind"] == "hotbar" and slot in (None, source["slot"]):
        target = source["slot"]
    else:
        empty = next((s["slot"] for s in inv["main"] if s["kind"] == "hotbar" and not s.get("stack")), None)
        target = slot if slot is not None else inv["selected"] if not inv.get("held") or empty is None else empty
        opened = not k.call("obs.container")["open"]
        if opened: k.call("gui.open_inventory")
        try:
            session = ContainerSession(k)
            view = session.observe()
            if view.get("cursor"): raise ValueError("the cursor must be empty before mb_hold")
            here = next(s for s in view["slots"] if s["kind"] in ("main", "hotbar") and s["idx"] == source["slot"])
            session.click(here["i"], "swap", button=target)
        finally:
            if opened:
                try: k.call("gui.close")
                except BridgeError: pass
    if inv["selected"] != target: k.call("act.select_hotbar", slot=target)
    held = k.call("obs.inventory", detail="compact").get("held")
    if not _matches(held, item):
        raise ValueError(f"after the swap your hand holds {held}, not the item: inspect mb_inventory before trying again")
    return {"held": held, "slot": target}


@tool(coverage=["inventory"])
def mb_craft(pattern: list[list[dict | None]] | None = None, times: int = 1, at: list[int] | None = None,
             inputs: list[dict] | None = None, wait_s: float = 0.0, grid: list[list[int]] | None = None,
             result_slot: int | None = None, despite_threat: bool = False,
             face: int | None = None, hit: list[float] | None = None) -> Any:
    """Make something in ONE call, at any station with a GUI: it opens the station, moves the items, takes the result and closes.

    Look the recipe up first (mb_recipes), every time it is new to you: assume no recipe in this pack,
    vanilla ones least of all.
    Station: at=[x,y,z] is the block to open (crafting table or a variant, furnace, any machine);
    omit it for your inventory's own 2x2 grid. Stand within reach. A GUI that is already open is
    used as it is and left open.
    face=0..5 and block-local hit=[x,y,z] optionally target an exposed part of the station;
    omit them to use the native visible face. They only apply when this call opens at.
    Block GUIs are opened with an observed empty hand; held tools are temporarily parked if
    needed and restored after opening. A full inventory needs a free slot or an explicitly opened GUI.
    Grid crafting: pattern is rows of cells, each {id, meta?} or null, laid out as mb_recipes
    shows the shaped recipe, e.g. sticks: [[{"id":"minecraft:planks"}],[{"id":"minecraft:planks"}]].
    times loads that many items per cell, up to the grid slot's own limit; a cell's count overrides its
    total load (e.g. count:1 for a retained mortar). The native shift-click decides how many crafts
    actually run; gained reports the observed result. Remaining tools/ingredients are returned.
    If the game shows no output the pattern is not a recipe in this
    pack (GTNH changes many vanilla recipes and often wants a tool in the grid): the ingredients
    go back and the error says so. Returns {crafted, gained, grid}: crafted is what ONE craft yields, gained
    is how many you now have more than before, grid {slots, result, from} the slots used. The grid is the
    one the game's result slot reads; for a GUI where it is not found, grid=[[slot, ...], ...] (rows of
    container slot indices) and result_slot name them.
    Machines: inputs is [{id, meta?, count, slot?}] in the order to load. Each goes to the first
    slot the machine itself accepts it in (furnace: [ore, fuel]); slot forces one. Then every
    output slot is emptied into your inventory, again for up to wait_s seconds (0..300) while the
    machine runs; it returns early once the loaded slots are empty. Returns {loaded, collected,
    inside}: inside is what is still in the machine. For a long job load with wait_s=0, do other
    work, and come back with mb_craft(at=...) alone, which only collects. Fluids, steam, power and
    circuits are yours to arrange; for recipes made in the world rather than in a GUI (dropping
    items, multiblocks fed by hatches) compose the primitives and save your own tool.
    It never retries what it sent; on a stop, read the receipts and observe. Opening a station is refused while the
    clock lists a threat (procedureReceipts name the mobs); despite_threat=True opens it anyway.
    """
    if pattern is not None and inputs is not None:
        raise ValueError("give pattern (a crafting grid) or inputs (a machine), not both")
    if type(times) is not int or times < 1 or not 0 <= wait_s <= 300 or pattern is not None and not (pattern and all(isinstance(row, list) and row for row in pattern)):
        raise ValueError("pattern is a non-empty list of rows; times is a whole number from 1; wait_s is 0..300")
    selectors = [cell for row in pattern for cell in row if cell is not None] if pattern is not None else inputs or []
    for cell in selectors:
        if not isinstance(cell, dict) or not isinstance(cell.get("id"), str) or not cell["id"]:
            raise ValueError("craft cells and machine inputs require an item id")
        if "count" in cell and (type(cell["count"]) is not int or cell["count"] < 1):
            raise ValueError("craft cell and machine input counts must be positive whole numbers")
    k = kernel()
    opened = _station(k, at, despite_threat, face, hit)
    try:
        session = ContainerSession(k)
        if session.observe().get("cursor"):
            raise ValueError("the cursor must be empty before mb_craft")
        result = _grid(session, pattern, times, grid, result_slot) if pattern else _machine(session, inputs or [], wait_s)
        return notes.with_item_notes(dict(result, clicks=len(session.receipts)))
    finally:
        if opened:
            try: k.call("gui.close")
            except BridgeError: pass  # a stop with the cursor full leaves the GUI open for you to inspect
