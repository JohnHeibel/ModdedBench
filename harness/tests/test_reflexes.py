# SPDX-License-Identifier: MIT
# Copyright (c) 2026 ModdedBench contributors
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

os.environ["MODBENCH_REFLEX_WATCH"] = "0"  # no watcher thread here: the tests call what it calls
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'mcp'))
import mbtool
from kernel import BridgeError, Kernel, Reply
from mbtools_gtnh import reflexes, scripts, tasks

READS = ("obs.player", "time.status", "interrupt.status", "sys.methods")
GUARD = "world paused by a guard (health_threshold): read mb_time status, decide, resume"


class World(Kernel):
    """A clock, one job and a log of what was sent: enough of the game for a reflex to act on."""
    connected = True

    def __init__(self):
        self.sent, self.seq, self.latched = [], 0, []
        self.clock = {"paused": False, "reason": "requested_resume", "simulationTicks": 100, "events": [], "threats": [],
                      "stepping": False, "conditions": {"healthBelow": 8}}
        self.on = {}  # method -> what it answers; default {"done": method}

    def close(self): pass

    def pause(self, reason):
        self.seq += 1
        self.clock.update(paused=True, stepping=False, reason=reason)
        self.clock["events"].append({"sequence": self.seq, "wallMs": self.seq * 10, "reason": reason})

    def held(self, by, **more):
        self.pause(by)
        return {"state": "suspended", "pausedBy": by, "suspendedJobId": "j1", **more}

    def call_reply(self, method, timeout=None, **params):
        params.pop("_timeout_ms", None); self.sent.append((method, dict(params)))
        ok = lambda data, raw=None: Reply(True, 0, 0, 0, data, raw=raw or {})
        if method == "sys.methods": return ok([{"name": m, "effect": "read"} for m in READS])
        if method == "time.status": return ok({"state": {**self.clock, "events": list(self.clock["events"])}})
        if method == "interrupt.status": return ok({"latched": list(self.latched)})
        if method == "interrupt.ack": self.latched.remove(params["eventId"]); return ok({})
        if method == "time.resume": self.clock.update(paused=False, stepping=False, reason="requested_resume"); return ok({})
        if method == "time.pause": self.pause(params.get("reason", "requested_pause")); return ok({})
        raw = {}
        if method not in READS:
            if self.clock["paused"] and not params.get("_resume"):
                return Reply(False, 0, 0, 0, error={"code": "time_paused", "msg": GUARD})
            if params.get("_resume"):
                raw = {"resumedWorld": {"pausedBy": self.clock["reason"]}}
                self.clock.update(paused=False, reason="requested_resume", stepping=params["_resume"] is not True)
            self.clock["simulationTicks"] += 10
        try: return ok(self.on.get(method, lambda p: {"done": method})(params), raw)
        except BridgeError as e: return Reply(False, 0, 0, 0, error={"code": e.code, "msg": e.msg})

    def acts(self): return [(m, p) for m, p in self.sent if m not in READS]


class ReflexTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.w = World()
        k = mbtool.kernel
        tools = {"act": lambda method="act.input", **p: k().call(method, **p), "look": lambda: k().call("obs.player"),
                 "mb_time": lambda method, params=None: k().call(f"time.{method}", **(params or {}))}
        tools = {n: scripts._resuming(mbtool.tool()(f)) for n, f in tools.items()}
        # around_action is pinned to this copy of the module: a test that reloads the tool package leaves a newer one there
        for it in (mock.patch.dict(os.environ, {"MODBENCH_REFLEXES_FILE": str(Path(self.tmp.name) / "reflexes.json")}), mock.patch.object(tasks, "held", lambda folder=None: False),
                   mock.patch.object(scripts, "_tools", lambda: tools), mock.patch.object(Kernel, "around_action", reflexes.around)):
            it.start(); self.addCleanup(it.stop)
        mbtool.state.pop("reflexes", None); self.addCleanup(mbtool.state.pop, "reflexes", None)
        mbtool.set_kernel_factory(lambda: self.w); self.addCleanup(mbtool.set_kernel_factory, None)

    def arm(self, do="def main(event):\n return look()", when="health_threshold", **more):
        return reflexes.mb_reflex("r", when=when, do=do, **more)

    def walk(self):
        """nav.goto: held by a guard the first time it is sent, done the second; nav.resume finishes the held job."""
        sends = []

        def goto(p):
            sends.append(p)
            return self.w.held("health_threshold") if len(sends) == 1 else {"state": "succeeded", "sends": len(sends)}
        self.w.on.update({"nav.goto": goto, "nav.resume": lambda p: {"state": "succeeded", "resumed": p["jobId"]}})
        return mbtool.kernel().call("nav.goto", x=5)

    def test_nothing_armed_changes_nothing(self):
        self.assertEqual(self.walk()["state"], "suspended")
        self.assertEqual(self.w.acts(), [("nav.goto", {"x": 5})])
        self.assertEqual(reflexes.deliver(), [])

    def test_a_reflex_that_only_looks_carries_the_held_job_on(self):
        self.arm()
        self.assertEqual(self.walk(), {"state": "succeeded", "resumed": "j1"})
        self.assertEqual(self.w.acts(), [("nav.goto", {"x": 5}), ("nav.resume", {"jobId": "j1", "_resume": True})])
        fired = reflexes.deliver()
        self.assertEqual([(f["reflex"], f["when"], f["work"], f["world"], f["result"]) for f in fired],
                         [("r", "health_threshold", "carried_on", "running", {"done": "obs.player"})])
        self.assertEqual(reflexes.deliver(), [])  # handed over once

    def test_a_reflex_that_acts_steps_the_world_and_the_call_is_sent_again(self):
        self.arm("def main(event):\n act('act.eat')\n return event['work']", max_ticks=60)
        self.assertEqual(self.walk(), {"state": "succeeded", "sends": 2})
        self.assertEqual(self.w.acts(), [("nav.goto", {"x": 5}), ("act.eat", {"_resume": 60}), ("time.resume", {}), ("nav.goto", {"x": 5})])
        fired = reflexes.deliver()[0]
        self.assertEqual((fired["work"], fired["result"]), ("restarted", {"call": "nav.goto", "how": "held", "jobId": "j1"}))

    def test_a_checkpointed_job_goes_on_from_its_journal_after_the_reflex_acted(self):
        self.arm("def main(event):\n act('act.eat')")
        self.w.on.update({"nav.mine": lambda p: self.w.held("health_threshold", jobId="j1"), "nav.resume": lambda p: {"state": "succeeded"}})
        self.assertEqual(mbtool.kernel().call("nav.mine", quantity=8), {"state": "succeeded"})
        self.assertEqual(self.w.acts()[-1], ("nav.resume", {"jobId": "j1"}))
        self.assertEqual(reflexes.deliver()[0]["work"], "carried_on")

    def test_a_refused_action_is_sent_after_the_reflex(self):
        self.arm(); self.w.pause("health_threshold")
        self.assertEqual(mbtool.kernel().call("act.input", key="jump"), {"done": "act.input"})
        self.assertEqual(self.w.acts(), [("act.input", {"key": "jump"}), ("act.input", {"key": "jump", "_resume": True})])
        self.assertEqual(reflexes.deliver()[0]["work"], "sent")

    def test_a_failing_script_leaves_the_pause_and_the_call_as_they_were(self):
        self.arm("def main(event):\n log('before')\n raise KeyError('nope')")
        self.assertEqual(self.walk()["state"], "suspended")
        self.assertEqual((self.w.clock["paused"], self.w.clock["reason"]), (True, "health_threshold"))
        fired = reflexes.deliver()[0]
        self.assertEqual((fired["work"], fired["world"], fired["line"], fired["log"]), ("held", "paused", 3, ["before"]))
        self.assertIn("KeyError", fired["stopped"])

    def test_a_script_that_fails_after_acting_pauses_the_world_in_its_own_name(self):
        self.arm("def main(event):\n act('act.eat')\n raise KeyError('nope')")
        self.assertEqual(self.walk()["state"], "suspended")
        self.assertEqual((self.w.clock["paused"], self.w.clock["reason"]), (True, "reflex:r"))
        self.assertEqual(reflexes.deliver()[0]["work"], "ended")

    def test_a_pause_inside_the_script_stands(self):
        self.arm("def main(event):\n act('act.eat')\n mb_time('pause', {'reason': 'too_many'})\n return 'handed back'")
        self.assertEqual(self.walk()["state"], "suspended")
        fired = reflexes.deliver()[0]
        self.assertEqual((self.w.clock["reason"], fired["stopped"], fired["result"]), ("too_many", "the world paused inside it (too_many)", "handed back"))

    def test_running_out_of_ticks_leaves_the_world_paused(self):
        self.arm("def main(event):\n act('act.eat')\n return 1")
        self.w.on["act.eat"] = lambda p: self.w.pause("step") or {"state": "suspended"}
        self.assertEqual(self.walk()["state"], "suspended")
        self.assertEqual((self.w.clock["reason"], reflexes.deliver()[0]["stopped"]), ("reflex:r", "out of ticks (max_ticks 200)"))

    def test_then_pause_runs_the_script_and_stops(self):
        self.arm("def main(event):\n act('act.eat')", then="pause")
        self.assertEqual(self.walk()["state"], "suspended")
        self.assertEqual((self.w.clock["reason"], self.w.acts()[-1][0], reflexes.deliver()[0]["work"]), ("reflex:r", "time.pause", "ended"))

    def test_resume_and_step_are_refused_inside(self):
        for body in ("act('act.eat', resume=True)", "mb_time('resume')"):
            self.setUp(); self.arm(f"def main(event):\n {body}")
            self.assertEqual(self.walk()["state"], "suspended")
            self.assertIn("tick budget", reflexes.deliver()[0]["stopped"])
            self.doCleanups()

    def test_limits_let_a_pause_stand_and_say_so(self):
        self.arm(every_ticks=1000)
        self.assertEqual(self.walk()["state"], "succeeded")
        self.w.on["nav.goto"] = lambda p: self.w.held("health_threshold")
        self.assertEqual(mbtool.kernel().call("nav.goto", x=5)["state"], "suspended")
        self.assertIn("every_ticks 1000", reflexes.deliver()[-1]["skipped"])
        reflexes.mb_reflex("r", times=1, every_ticks=0)
        self.w.on["nav.resume"] = lambda p: self.w.held("health_threshold")  # the carried-on job trips the guard again
        self.w.clock["paused"] = False
        self.assertEqual(mbtool.kernel().call("nav.goto", x=5)["state"], "suspended")
        self.assertEqual([f.get("work") or f.get("skipped") for f in reflexes.deliver()], ["carried_on", "its times are spent"])

    def test_a_pause_with_no_call_in_flight_is_answered_once_and_time_resumes(self):
        self.arm("def main(event):\n return event['work']"); self.w.pause("health_threshold")
        box = reflexes._box()
        self.assertIsNone(reflexes._answer(self.w, box, {"how": "none"}))
        self.assertEqual((self.w.clock["paused"], self.w.acts()), (False, [("time.resume", {})]))
        self.assertEqual([(f["world"], f.get("result"), f.get("work")) for f in reflexes.deliver()], [("running", None, None)])
        self.w.clock["paused"] = True  # the same pause, seen again
        reflexes._answer(self.w, box, {"how": "none"})
        self.assertEqual(reflexes.deliver(), [])

    def test_a_pause_that_ended_a_background_task_is_answered_and_left(self):
        self.arm("def main(event):\n look()"); self.w.pause("health_threshold")
        reflexes._answer(self.w, reflexes._box(), {"how": "task_ended"})
        fired = reflexes.deliver()[0]
        self.assertEqual((self.w.clock["paused"], fired["work"], fired["world"]), (True, "task_ended", "paused"))

    def test_a_cut_call_is_not_sent_again(self):
        self.arm("def main(event):\n act('act.eat')")

        def click(p):
            self.w.pause("health_threshold"); raise BridgeError("cancelled", GUARD)
        self.w.on["gui.click"] = click
        with self.assertRaisesRegex(BridgeError, "world paused by a guard"):
            mbtool.kernel().call("gui.click", slot=3)
        self.assertEqual((self.w.clock["reason"], [m for m, _ in self.w.acts()], reflexes.deliver()[0]["work"]), ("reflex:r", ["gui.click", "act.eat", "time.pause"], "cut"))

    def test_a_watch_of_the_models_own_is_a_reason_and_its_latch_is_released(self):
        self.arm("def main(event):\n act('act.eat')", when=["interrupt:lava_near", "burning"])
        self.w.latched = ["e1"]
        self.w.on["nav.goto"] = lambda p: self.w.held("interrupt:lava_near") if len(self.w.acts()) == 1 else {"state": "succeeded"}
        self.assertEqual(mbtool.kernel().call("nav.goto", x=5), {"state": "succeeded"})
        self.assertEqual((self.w.latched, reflexes.deliver()[0]["when"]), ([], "interrupt:lava_near"))

    def test_arming_checks_what_it_is_given_and_reflexes_are_kept(self):
        for bad, why in ((dict(when="healthBelow"), "guard's setting"), (dict(when="step"), "not a guard's pause"), (dict(when="operator_hold"), "not a guard's pause"),
                         (dict(do="def main():\n pass"), "main.event."), (dict(do="def main(event:\n"), "does not parse"), (dict(then="go"), "resume"), (dict(max_ticks=0), "max_ticks")):
            with self.assertRaisesRegex(ValueError, why): self.arm(**bad)
        with self.assertRaisesRegex(ValueError, "needs when"): reflexes.mb_reflex("r", do="def main(event):\n pass")
        self.arm()
        with self.assertRaisesRegex(ValueError, "already has the reflex r"): reflexes.mb_reflex("other", when="health_threshold", do="def main(event):\n pass")
        self.assertEqual(reflexes.mb_reflex("r", then="pause")["armed"]["then"], "pause")
        mbtool.state.pop("reflexes")  # a restart
        self.assertEqual([(r["name"], r["then"], r["lines"]) for r in reflexes.mb_reflex()["armed"]], [("r", "pause", 2)])
        self.assertIn("def main(event)", reflexes.mb_reflex("r")["do"])
        self.assertEqual(reflexes.armed(), [{"name": "r", "when": ["health_threshold"], "then": "pause"}])
        self.assertEqual(reflexes.mb_reflex("r", remove=True), {"removed": True, "armed": []})

    def test_a_script_is_not_run_inside_a_background_task(self):
        self.arm(); mbtool.state["task"] = "t1"; self.addCleanup(mbtool.state.pop, "task", None)
        self.assertEqual(self.walk()["state"], "suspended")
        self.assertEqual(self.w.acts(), [("nav.goto", {"x": 5})])


if __name__ == '__main__':
    unittest.main()
