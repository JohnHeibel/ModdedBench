# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 Modbench contributors
"""Small composition support for agent-written GTNH tools; no recipe or machine assumptions.

Each mutation is guarded by the observed screen epoch and cursor/stack. A session
does not reserve the GUI between calls. Serialize routines that share a client.
On failure inspect the attached receipts and current state; never replay a whole
routine automatically. Native click acceptance is not proof of machine completion.
"""
from __future__ import annotations

import time
from kernel import BridgeError


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
