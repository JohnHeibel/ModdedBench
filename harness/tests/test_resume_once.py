# SPDX-License-Identifier: MIT
# Copyright (c) 2026 ModdedBench contributors
"""resume=True: an action a paused world refuses resumes the world and runs, once per tool call."""
import asyncio, concurrent.futures, inspect, os, sys, unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "mcp"))
try: import pytest
except ImportError: raise unittest.SkipTest("needs pytest")
import server
from kernel import BridgeError, Kernel, Reply, call_resuming, resume_once

METHODS = Reply(True, 0, 0, 0, [{"name": "obs.player", "effect": "read"}, {"name": "act.input", "effect": "interaction"}])  # sys.methods


class FakeWorld(Kernel):
    """The clock and one action; the world pauses again after `repause` actions ran."""
    refusal = "time_paused: paused by requested_pause; resume before starting simulation actions"
    def __init__(self, held=False, repause=None):
        self.paused, self.held, self.repause, self.sent, self.ran = True, held, repause, [], 0

    def call_reply(self, method, timeout=None, **params):
        if method == "sys.methods": return METHODS
        self.sent.append(method)
        if method == "time.status":
            return Reply(True, 0, 0, 0, {"state": {"reason": "threat", "threats": [{"entityId": 7}]}, "clientPaused": self.paused})
        if method == "time.resume":
            if self.held: return Reply(False, 0, 0, 0, error={"code": "clock_error", "msg": "held by the operator"})
            self.paused = False; return Reply(True, 0, 0, 0, {"paused": False})
        if self.paused: return Reply(False, 0, 0, 0, error={"code": "bad_request", "msg": self.refusal})
        self.ran += 1
        if self.repause and self.ran >= self.repause: self.paused = True
        return Reply(True, 0, 0, 0, {"done": True})


def test_without_resume_the_paused_refusal_stands():
    k = FakeWorld()
    with pytest.raises(BridgeError, match="time_paused"): k.call("act.input")
    assert "time.resume" not in k.sent


def test_a_guard_pause_refusal_does_not_offer_to_resume_through_it():
    k = FakeWorld()
    with pytest.raises(BridgeError, match="requested_pause.*resume=True"): k.call("act.input")
    k.refusal = "time_paused: world paused by a guard (health_dropped): read mb_time status, decide, resume"
    with pytest.raises(BridgeError, match="health_dropped") as e: k.call("act.input")
    assert "resume=True" not in e.value.msg


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


class ResumingClient(FakeWorld):
    """A client with resume-and-act: an action carrying _resume resumes the paused world itself and runs on its first tick."""
    def call_reply(self, method, timeout=None, **params):
        if method == "sys.methods": return METHODS
        self.sent.append((method, params.get("_resume")))
        if method.startswith("obs."): return Reply(True, 0, 0, 0, {"seen": True})
        if self.paused and params.get("_resume"):
            self.paused = False; self.ran += 1
            record = {"pausedBy": "threat", "threats": []} | ({"ticks": params["_resume"]} if params["_resume"] is not True else {})
            return Reply(True, 0, 0, 0, {"done": True}, raw={"resumedWorld": record})
        if self.paused: return Reply(False, 0, 0, 0, error={"code": "bad_request", "msg": self.refusal})
        return Reply(True, 0, 0, 0, {"done": True})


def test_resume_and_act_is_one_request_and_reads_do_not_resume():
    k = ResumingClient(); record = {}; resume_once.set(record)
    k.call("obs.player"); k.call("act.input"); k.call("act.input")
    assert k.sent == [("obs.player", None), ("act.input", True), ("act.input", None)]  # the first action carries it, and only that one
    assert record == {"pausedBy": "threat", "threats": [], "resumed": True}


def test_the_directive_is_spent_on_the_first_action_and_a_later_pause_stands():
    k = ResumingClient(); k.paused = False; record = {}; resume_once.set(record)
    k.call("obs.player"); k.call("act.input")  # a running world: nothing to resume
    k.paused = True  # a guard pauses it mid-tool
    with pytest.raises(BridgeError, match="time_paused"): k.call("act.input")
    assert k.sent == [("obs.player", None), ("act.input", True), ("act.input", None)] and not record["resumed"] and k.ran == 0


def test_an_unused_directive_is_reported():
    k = ResumingClient(); resume_once.set(None)
    assert call_resuming(lambda: k.call("act.input"), True)["resumedWorld"] == {"pausedBy": "threat", "threats": [], "resumed": True}
    out = call_resuming(lambda: k.call("act.input"), True)  # the world runs now
    assert "not paused" in out["resumeUnused"] and "resumedWorld" not in out
    k.paused = True
    out = call_resuming(lambda: k.call("obs.player"), 5)
    assert "no action" in out["resumeUnused"] and k.paused and "resumeUnused" not in call_resuming(lambda: k.call("obs.player"), False)


def test_resume_with_ticks_steps_instead():
    k = ResumingClient(); record = {"ticks": 5}; resume_once.set(record)
    k.call("act.input")
    assert k.sent == [("act.input", 5)] and record["resumed"] and record["ticks"] == 5
    with pytest.raises(ValueError): server._resume_arg(-1)
    assert server._resume_arg(True) is True and server._resume_arg(3) == 3 and server._resume_arg(0) is False


def test_an_action_that_fails_at_once_leaves_the_world_paused():
    class Refusing(ResumingClient):
        def call_reply(self, method, timeout=None, **params):
            if params.get("_resume") and self.ran == 0:
                self.ran += 1; self.sent.append((method, params["_resume"]))
                return Reply(False, 0, 0, 0, error={"code": "path_failed", "msg": "goal_not_standable"},
                             raw={"resumedWorld": {"pausedBy": "step", "threats": [], "ticks": 40, "stayedPaused": True}})
            return super().call_reply(method, timeout, **params)
    k = Refusing(); record = {"ticks": 40}; resume_once.set(record)
    with pytest.raises(BridgeError, match="goal_not_standable .*still paused"): k.call("nav.process")
    assert "resumed" not in record and k.paused
    k.call("nav.process")  # the directive was not used up: the next action carries it
    assert k.sent[-1] == ("nav.process", 40) and record["resumed"] and not k.paused


class SettlingWorld(Kernel):
    """A step's pause that reports 'pausing' for `polls` status reads, then settles."""
    def __init__(self, polls, end="paused"):
        self.polls, self.end, self.sent = polls, end, []

    def call_reply(self, method, timeout=None, **params):
        self.sent.append(method)
        if method == "time.status":
            self.polls -= 1
            return Reply(True, 0, 0, 0, {"state": {"mode": "pausing" if self.polls >= 0 else self.end}})
        if self.polls >= 0: return Reply(False, 0, 0, 0, error={"code": "bad_request", "msg": "pause has not settled; inspect time.status before resuming"})
        return Reply(True, 0, 0, 0, {"done": True})


def test_an_unsettled_pause_is_waited_out_and_the_request_sent_again():
    k = SettlingWorld(polls=3)
    assert k.call("time.step", ticks=20) == {"done": True}
    assert k.sent.count("time.step") == 2


def test_a_pause_that_fails_to_settle_keeps_its_refusal():
    k = SettlingWorld(polls=1, end="pause_error")
    with pytest.raises(BridgeError, match="not settled"): k.call("time.step", ticks=20)
    assert k.sent.count("time.step") == 1
