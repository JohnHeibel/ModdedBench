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
