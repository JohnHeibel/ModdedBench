# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""resume=True: an action a paused world refuses resumes the world and runs, once per tool call."""
import asyncio, concurrent.futures, inspect, os, sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "mcp"))
import pytest
import server
from kernel import BridgeError, Kernel, Reply, resume_once


class FakeWorld(Kernel):
    """The clock and one action; the world pauses again after `repause` actions ran."""
    def __init__(self, held=False, repause=None):
        self.paused, self.held, self.repause, self.sent, self.ran = True, held, repause, [], 0

    def call_reply(self, method, timeout=None, **params):
        self.sent.append(method)
        if method == "time.status":
            return Reply(True, 0, 0, 0, {"state": {"reason": "threat", "threats": [{"entityId": 7}]}, "clientPaused": self.paused})
        if method == "time.resume":
            if self.held: return Reply(False, 0, 0, 0, error={"code": "clock_error", "msg": "held by the operator"})
            self.paused = False; return Reply(True, 0, 0, 0, {"paused": False})
        if self.paused: return Reply(False, 0, 0, 0, error={"code": "bad_request", "msg": "time_paused: resume before starting simulation actions"})
        self.ran += 1
        if self.repause and self.ran >= self.repause: self.paused = True
        return Reply(True, 0, 0, 0, {"done": True})


def test_without_resume_the_paused_refusal_stands():
    k = FakeWorld()
    with pytest.raises(BridgeError, match="time_paused"): k.call("act.input")
    assert "time.resume" not in k.sent


def test_resume_lifts_the_pause_once_and_records_it():
    k = FakeWorld(repause=1); record = {}; resume_once.set(record)
    assert k.call("act.input") == {"done": True}
    assert record == {"pausedBy": "threat", "threats": [{"entityId": 7}], "resumed": True}
    with pytest.raises(BridgeError, match="time_paused"): k.call("act.input")  # a second pause in the same call is news
    assert k.sent.count("time.resume") == 1


def test_an_operator_hold_is_not_lifted():
    k = FakeWorld(held=True); resume_once.set({})
    with pytest.raises(BridgeError, match="held"): k.call("act.input")
    assert k.ran == 0


def test_the_server_adds_resume_to_acting_tools_and_reports_it():
    k = FakeWorld()
    def act() -> dict:
        """acts"""
        return k.call("act.input")
    act._mb_tool = {"lane": "act", "effect": "interaction"}
    def look() -> dict:
        """reads"""
    look._mb_tool = {"lane": "read", "effect": "read"}
    srv = server.Server.__new__(server.Server)
    srv._pools = {"act": concurrent.futures.ThreadPoolExecutor(1)}
    wrapped = srv._worker(act)
    assert "resume" in inspect.signature(wrapped).parameters
    assert "resume" not in inspect.signature(srv._worker(look)).parameters
    out = asyncio.run(wrapped(resume=True))
    assert out["done"] and out["resumedWorld"]["pausedBy"] == "threat"
