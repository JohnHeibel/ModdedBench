# SPDX-License-Identifier: MIT
# Copyright (c) 2026 ModdedBench contributors
import json
import os
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'mcp'))
import mbtool
from kernel import BridgeError, Kernel, Reply
from mbtools_gtnh import core, interrupts, reflexes, scripts, tasks

READS = ("obs.player", "time.status", "sys.methods", "obs.batch", "obs.x")
GUARD = "world paused by a rule (health_below:r): read mb_time status, decide, resume"
# What SimulationClock.defaults() gives a new world.
DEFAULTS = [{"name": "health_drop", "kind": "health_drop"}, {"name": "health_below", "kind": "health_below", "level": 8},
            {"name": "air_below", "kind": "air_below", "level": 180}, {"name": "food_below", "kind": "food_below", "level": 6},
            {"name": "burning", "kind": "burning"}, {"name": "hostile_within", "kind": "hostile_within", "level": 12}]
CHECK = {"queries": {"x": {"method": "obs.x"}}, "condition": {"gt": ["x.n", 0]}}


class World(Kernel):
    """A clock with its rules, one job, a read to check, the client's interrupt latch and a log of what was sent: enough of the game for the rules."""
    connected = True
    CONTEXT = {"worldId": "w", "dimension": 0, "bridgeId": "b", "worldEpoch": 1}

    def __init__(self):
        self.sent, self.seq, self.told, self.value, self.fires, self.latched = [], 0, [], 0, [], []
        self.table = [dict(r) for r in DEFAULTS]
        self.clock = {"paused": False, "reason": "requested_resume", "simulationTicks": 100, "events": [], "threats": [], "stepping": False}
        self.on = {}  # method -> what it answers; default {"done": method}

    def close(self): pass

    def status(self):
        rows = [{"name": r["name"], "kind": r["kind"], "do": r.get("do", "ask") if r.get("do", "ask") in ("ask", "tell") else "script",
                 **({"level": r["level"]} if "level" in r else {})} for r in self.table]
        return {**self.clock, "events": [dict(e) for e in self.clock["events"]], "rules": rows, "told": list(self.told)}

    def rules(self, p):
        table = {r["name"]: r for r in p.get("replace", self.table)}
        for name in p.get("remove", []): table.pop(name, None)
        for r in p.get("set", []): table[r["name"]] = dict(r)  # a replaced rule keeps its place
        self.table = list(table.values())
        return {"rules": [dict(r) for r in self.table]}

    def pause(self, reason, joined=None):
        self.seq += 1
        self.clock.update(paused=True, stepping=False, reason=reason)
        self.clock["events"].append({"sequence": self.seq, "wallMs": self.seq * 10, "reason": reason, **({"with": joined} if joined else {})})

    def tell(self, reason):
        self.seq += 1
        self.told.append({"sequence": self.seq, "tick": self.clock["simulationTicks"], "rule": reason, "wallMs": self.seq * 10})

    def held(self, by, **more):
        self.pause(by)
        return {"state": "suspended", "pausedBy": by, "suspendedJobId": "j1", **more}

    def call_reply(self, method, timeout=None, **params):
        params.pop("_timeout_ms", None); self.sent.append((method, dict(params)))
        ok = lambda data, raw=None: Reply(True, 0, 0, 0, data, raw=raw or {})
        if method == "sys.methods": return ok([{"name": m, "effect": "read", "watchable": True} for m in READS])
        if method == "time.status": return ok({"state": self.status()})
        if method == "time.rules": return ok(self.rules(params))
        if method == "obs.batch": return ok({"values": {a: {"n": self.value} for a in params["queries"]}, "errors": {}, "context": dict(self.CONTEXT)})
        if method == "interrupt.status": return ok({"context": dict(self.CONTEXT), "operationId": 1, "latched": list(self.latched)})
        if method == "interrupt.fire":  # as ClientInterrupts: latch, then the pause under the watch's reason
            self.fires.append(params)
            if params["latch"]: self.latched.append(params["eventId"])
            if "pause" in params["effects"]: self.pause("interrupt:" + params["reason"])
            return ok({"eventId": params["eventId"], "latched": params["latch"]})
        if method == "interrupt.ack": self.latched.remove(params["eventId"]); return ok({"latched": list(self.latched)})
        if method == "time.configure": return ok({})
        if method == "time.resume": self.clock.update(paused=False, stepping=False, reason="requested_resume"); return ok({})
        if method == "time.pause": self.pause(params.get("reason", "requested_pause")); return ok({})
        raw = {}
        if self.latched and method not in READS and not method.startswith("time."):  # the refusal delivers the events and lets go
            self.latched.clear(); return Reply(False, 0, 0, 0, error={"code": "interrupt_latched", "msg": "delivered, this action was not started"})
        if method not in READS:
            if self.clock["paused"] and not params.get("_resume"):
                return Reply(False, 0, 0, 0, error={"code": "time_paused", "msg": GUARD})
            if params.get("_resume"):
                raw = {"resumedWorld": {"pausedBy": self.clock["reason"]}}
                self.clock.update(paused=False, reason="requested_resume", stepping=params["_resume"] is not True)
            self.clock["simulationTicks"] += 10
        try: return ok(self.on.get(method, lambda p: {"done": method})(params), raw)
        except BridgeError as e: return Reply(False, 0, 0, 0, error={"code": e.code, "msg": e.msg})

    def acts(self): return [(m, p) for m, p in self.sent if m not in READS and m != "time.rules" and not m.startswith("interrupt.")]


class RuleTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.w = World()
        k = mbtool.kernel
        tools = {"act": lambda method="act.input", **p: k().call(method, **p), "look": lambda: k().call("obs.player"),
                 "mb_time": lambda method, params=None: k().call(f"time.{method}", **(params or {})), "mb_reflex": reflexes.mb_reflex}
        tools = {n: scripts._resuming(mbtool.tool()(f)) for n, f in tools.items()}
        # around_action is pinned to this copy of the module: a test that reloads the tool package leaves a newer one there
        for it in (mock.patch.dict(os.environ, {"MODBENCH_REFLEXES_FILE": str(Path(self.tmp.name) / "reflexes.json"), "MODBENCH_REFLEX_WATCH": "0",
                                                "MODBENCH_INTERRUPTS_DIR": str(Path(self.tmp.name) / "interrupts")}),
                   mock.patch.object(tasks, "held", lambda folder=None: False), mock.patch.object(scripts, "_tools", lambda: tools),
                   mock.patch.object(Kernel, "around_action", reflexes.around)):
            it.start(); self.addCleanup(it.stop)
        mbtool.state.pop("reflexes", None); self.addCleanup(mbtool.state.pop, "reflexes", None)
        self.addCleanup(interrupts.close_supervisor); self.addCleanup(reflexes._stop)
        mbtool.set_kernel_factory(lambda: self.w); self.addCleanup(mbtool.set_kernel_factory, None)

    def arm(self, do="def main(event):\n return look()", when="health_below 8", **more):
        return reflexes.mb_reflex("r", when=when, do=do, **more)

    def walk(self, by="health_below:r"):
        """nav.goto: held by a rule the first time it is sent, done the second; nav.resume finishes the held job."""
        sends = []

        def goto(p):
            sends.append(p)
            return self.w.held(by) if len(sends) == 1 else {"state": "succeeded", "sends": len(sends)}
        self.w.on.update({"nav.goto": goto, "nav.resume": lambda p: {"state": "succeeded", "resumed": p["jobId"]}})
        return mbtool.kernel().call("nav.goto", x=5)

    def until(self, what, seconds=3):
        end = time.monotonic() + seconds
        while not what() and time.monotonic() < end: time.sleep(.01)
        self.assertTrue(what())

    def test_a_rule_that_asks_changes_nothing(self):
        self.assertEqual(self.walk("health_below")["state"], "suspended")
        self.assertEqual(self.w.acts(), [("nav.goto", {"x": 5})])
        self.assertEqual(reflexes.deliver(), [])

    def test_a_script_that_only_looks_carries_the_held_job_on(self):
        self.arm()
        self.assertEqual(self.walk(), {"state": "succeeded", "resumed": "j1"})
        self.assertEqual(self.w.acts(), [("nav.goto", {"x": 5}), ("nav.resume", {"jobId": "j1", "_resume": True})])
        fired = reflexes.deliver()
        self.assertEqual([(f["rule"], f["when"], f["work"], f["world"], f["result"]) for f in fired],
                         [("r", "health_below:r", "carried_on", "running", {"done": "obs.player"})])
        self.assertEqual(reflexes.deliver(), [])  # handed over once

    def test_a_script_that_acts_steps_the_world_and_the_call_is_sent_again(self):
        self.arm("def main(event):\n act('act.eat')\n return event['work']", max_ticks=60)
        self.assertEqual(self.walk(), {"state": "succeeded", "sends": 2})
        self.assertEqual(self.w.acts(), [("nav.goto", {"x": 5}), ("act.eat", {"_resume": 60}), ("time.resume", {}), ("nav.goto", {"x": 5})])
        fired = reflexes.deliver()[0]
        self.assertEqual((fired["work"], fired["result"]), ("restarted", {"call": "nav.goto", "how": "held", "jobId": "j1"}))

    def test_a_checkpointed_job_goes_on_from_its_journal_after_the_script_acted(self):
        self.arm("def main(event):\n act('act.eat')")
        self.w.on.update({"nav.mine": lambda p: self.w.held("health_below:r", jobId="j1"), "nav.resume": lambda p: {"state": "succeeded"}})
        self.assertEqual(mbtool.kernel().call("nav.mine", quantity=8), {"state": "succeeded"})
        self.assertEqual(self.w.acts()[-1], ("nav.resume", {"jobId": "j1"}))
        self.assertEqual(reflexes.deliver()[0]["work"], "carried_on")

    def test_a_refused_action_is_sent_after_the_script(self):
        self.arm(); self.w.pause("health_below:r")
        self.assertEqual(mbtool.kernel().call("act.input", key="jump"), {"done": "act.input"})
        self.assertEqual(self.w.acts(), [("act.input", {"key": "jump"}), ("act.input", {"key": "jump", "_resume": True})])
        self.assertEqual(reflexes.deliver()[0]["work"], "sent")

    def test_a_failing_script_falls_back_to_the_model(self):
        self.arm("def main(event):\n log('before')\n raise KeyError('nope')")
        self.assertEqual(self.walk()["state"], "suspended")
        self.assertEqual((self.w.clock["paused"], self.w.clock["reason"]), (True, "health_below:r"))  # frozen as with no script
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

    def test_resume_and_step_are_refused_inside_and_a_script_may_change_the_rules(self):
        for body in ("act('act.eat', resume=True)", "mb_time('resume')"):
            self.setUp(); self.arm(f"def main(event):\n {body}")
            self.assertEqual(self.walk()["state"], "suspended")
            self.assertIn("tick budget", reflexes.deliver()[0]["stopped"])
            self.doCleanups()
        self.setUp(); self.arm("def main(event):\n mb_reflex('r', remove=True)\n mb_reflex('air_below', when='air_below 120')")
        self.assertEqual(self.walk()["state"], "succeeded")
        self.assertEqual([(r["name"], r.get("level")) for r in self.w.table if r["kind"] == "air_below"], [("air_below", 120)])
        self.assertNotIn("r", [r["name"] for r in self.w.table])

    def test_limits_let_a_pause_stand_and_say_so(self):
        self.arm(every_ticks=1000)
        self.assertEqual(self.walk()["state"], "succeeded")
        self.w.on["nav.goto"] = lambda p: self.w.held("health_below:r")
        self.assertEqual(mbtool.kernel().call("nav.goto", x=5)["state"], "suspended")
        self.assertIn("every_ticks 1000", reflexes.deliver()[-1]["skipped"])
        reflexes.mb_reflex("r", times=1, every_ticks=0)  # set afresh: its firings count from zero
        self.w.on["nav.resume"] = lambda p: self.w.held("health_below:r")  # the carried-on job trips the rule again
        self.w.clock["paused"] = False
        self.assertEqual(mbtool.kernel().call("nav.goto", x=5)["state"], "suspended")
        self.assertEqual([f.get("work") or f.get("skipped") for f in reflexes.deliver()], ["carried_on", "its times are spent"])

    def test_rules_crossing_together_wait_for_the_model(self):
        self.arm()
        self.w.on["nav.goto"] = lambda p: self.w.pause("health_below:r", ["food_below"]) or {"state": "suspended", "pausedBy": "health_below:r", "suspendedJobId": "j1"}
        self.assertEqual(mbtool.kernel().call("nav.goto", x=5)["state"], "suspended")
        self.assertEqual((self.w.clock["paused"], [m for m, _ in self.w.acts()]), (True, ["nav.goto"]))
        self.assertEqual(reflexes.deliver(), [{"rule": "r", "when": "health_below:r", "tick": 110,
                                               "skipped": "rules crossed together (health_below:r, food_below): they wait for you"}])

    def test_a_pause_with_no_call_in_flight_is_answered_once_and_time_resumes(self):
        self.arm("def main(event):\n return event['work']"); self.w.pause("health_below:r")
        box = reflexes._box()
        self.assertIsNone(reflexes._answer(self.w, box, {"how": "none"}))
        self.assertEqual((self.w.clock["paused"], self.w.acts()), (False, [("time.resume", {})]))
        self.assertEqual([(f["world"], f.get("result"), f.get("work")) for f in reflexes.deliver()], [("running", None, None)])
        self.w.clock["paused"] = True  # the same pause, seen again
        reflexes._answer(self.w, box, {"how": "none"})
        self.assertEqual(reflexes.deliver(), [])

    def test_the_watcher_answers_while_the_model_waits(self):
        self.arm("def main(event):\n return 'ate'"); self.w.pause("health_below:r")
        with mock.patch.dict(os.environ, {"MODBENCH_REFLEX_WATCH": "1"}):
            woke = reflexes.mb_wait(timeout_s=30)  # a pause a script answers is waited through, and its firing wakes the wait
        self.assertEqual((woke["woke"], woke["events"], self.w.clock["paused"]), (True, [], False))
        self.assertEqual([(f["rule"], f["result"]) for f in reflexes.deliver()], [("r", "ate")])
        self.w.pause("health_below")  # a rule that asks: the wait returns at once
        woke = reflexes.mb_wait(timeout_s=30)
        self.assertEqual((woke["woke"], woke["paused"]), (False, "health_below"))

    def test_a_pause_that_ended_a_background_task_is_answered_and_left(self):
        self.arm("def main(event):\n look()"); self.w.pause("health_below:r")
        reflexes._answer(self.w, reflexes._box(), {"how": "task_ended"})
        fired = reflexes.deliver()[0]
        self.assertEqual((self.w.clock["paused"], fired["work"], fired["world"]), (True, "task_ended", "paused"))

    def test_a_cut_call_is_not_sent_again(self):
        self.arm("def main(event):\n act('act.eat')")

        def click(p):
            self.w.pause("health_below:r"); raise BridgeError("cancelled", GUARD)
        self.w.on["gui.click"] = click
        with self.assertRaisesRegex(BridgeError, "world paused by a rule"):
            mbtool.kernel().call("gui.click", slot=3)
        self.assertEqual((self.w.clock["reason"], [m for m, _ in self.w.acts()], reflexes.deliver()[0]["work"]), ("reflex:r", ["gui.click", "act.eat", "time.pause"], "cut"))

    def test_a_check_of_the_models_own_freezes_and_its_script_answers(self):
        reflexes.mb_reflex("lava", when=CHECK)
        sup = mbtool.state["interrupts"]
        self.assertEqual({k: sup.watches["lava"].spec[k] for k in ("effects", "oneShot", "edge")}, {"effects": ["pause"], "oneShot": False, "edge": True})
        self.w.value = 1; sup.poll()
        self.until(lambda: self.w.clock["paused"])
        self.assertEqual((self.w.clock["reason"], self.w.latched), ("interrupt:lava", [self.w.fires[0]["eventId"]]))  # a freeze latches, as mb_interrupt's did
        self.w.clock["paused"] = False; self.w.latched.clear()
        reflexes.mb_reflex("lava", do="def main(event):\n return event['reason']")  # the same check, now answered by a script
        self.assertEqual(self.walk("interrupt:lava")["state"], "succeeded")
        self.assertEqual(reflexes.deliver()[0]["result"], "interrupt:lava")
        reflexes.mb_reflex("lava", remove=True)
        self.assertEqual(sup.list(), [])

    def test_a_latched_check_is_acknowledged_before_its_script_acts(self):
        reflexes.mb_reflex("lava", when={**CHECK, "effects": ["notify", "cancel", "pause"]}, do="def main(event):\n act('act.eat')\n return 'out'")
        mbtool.state["interrupts"].poll(); self.assertEqual(self.w.fires, [])
        self.w.value = 1; mbtool.state["interrupts"].poll()
        self.until(lambda: self.w.clock["paused"])
        self.assertEqual((self.w.fires[0]["effects"], self.w.fires[0]["latch"]), (["notify", "cancel", "pause"], True))
        self.assertIsNone(reflexes._answer(self.w, reflexes._box(), {"how": "none"}))  # no call in flight: the watcher's way
        self.assertEqual((self.w.latched, self.w.acts()), ([], [("act.eat", {"_resume": 200}), ("time.resume", {})]))
        self.assertEqual([m for m, _ in self.w.sent if m.startswith("interrupt.")][-1], "interrupt.ack")
        self.assertEqual(reflexes.deliver()[0]["result"], "out")

    def test_the_old_names_work_on_the_same_checks(self):
        spec = {**CHECK, "effects": ["notify", "cancel", "pause"], "oneShot": True, "latch": True}
        interrupts.mb_interrupt("add", "Ender_scout_watch", spec)
        with self.assertRaisesRegex(ValueError, "already exists"): interrupts.mb_interrupt("add", "Ender_scout_watch", spec)
        interrupts.mb_interrupt("add", "Ender_scout_watch", spec, replace=True)
        self.assertEqual([(r["when"], r["do"]) for r in reflexes.mb_reflex()["rules"] if r["name"] == "Ender_scout_watch"], [("check", "ask")])
        self.w.value = 1; mbtool.state["interrupts"].poll()
        self.until(lambda: self.w.clock["paused"])
        eid = self.w.fires[0]["eventId"]
        status = interrupts.mb_interrupt("status")
        self.assertEqual((status["client"]["latched"], [w["armed"] for w in status["supervisor"]["watches"]]), ([eid], [False]))  # oneShot
        self.assertEqual(reflexes.mb_reflex("Ender_scout_watch")["armed"], False)
        interrupts.mb_interrupt("ack", event_id=eid); self.assertEqual(self.w.latched, [])
        got = reflexes.mb_wait(after=0, timeout_s=1)
        self.assertEqual((got["woke"], "paused" in got, [e["kind"] for e in got["events"]]), (True, False, ["triggered"]))  # what woke it comes first
        self.assertEqual(got["events"][0]["data"]["eventId"], eid)
        self.assertEqual(reflexes.mb_wait(after=got["cursor"], timeout_s=1)["events"], [])
        self.assertEqual([e["kind"] for e in interrupts.mb_interrupt_events(after=0)["events"]][:2], ["armed", "armed"])
        self.assertEqual(interrupts.mb_interrupt("remove", "Ender_scout_watch"), {"removed": True})
        self.assertEqual([r["name"] for r in reflexes.mb_reflex()["rules"]], [r["name"] for r in DEFAULTS])

    def test_old_guard_settings_set_the_rules_named_for_their_kinds(self):
        reflexes.mb_reflex("health_below", do="def main(event):\n pass")
        core.mb_time("configure", {"threatWithin": -1, "healthBelow": 10, "burning": False, "pauseOnDisconnect": False})
        self.assertEqual(self.w.sent[-1], ("time.configure", {"pauseOnDisconnect": False}))
        rules = {r["name"]: r for r in self.w.table}
        self.assertEqual((sorted(set(r["name"] for r in DEFAULTS) - set(rules)), rules["health_below"]["level"]), (["burning", "hostile_within"], 10))
        self.assertIn("def main", rules["health_below"]["do"])  # its script stays
        core.mb_time("configure", {"threatWithin": 12, "actionFailed": True})
        conditions = core.mb_time("status")["state"]["conditions"]
        self.assertEqual({k: conditions[k] for k in ("threatWithin", "healthBelow", "burning", "actionFailed", "airBelow")},
                         {"threatWithin": 12, "healthBelow": 10, "burning": False, "actionFailed": True, "airBelow": 180})
        with self.assertRaisesRegex(ValueError, "healthDrop"): core.mb_time("configure", {"healthDrop": 3})

    def test_a_tell_rule_tells_once_and_wakes_the_wait(self):
        self.w.tell("burning")  # heard before the first look: history, not news
        reflexes.mb_reflex("eat", when="food_below 16", do="tell")
        self.assertEqual([(r["name"], r["when"], r["do"]) for r in reflexes.mb_reflex()["rules"] if r["when"].startswith("food_below")],
                         [("food_below", "food_below 6", "ask"), ("eat", "food_below 16", "tell")])  # one kind at two levels
        self.assertFalse(reflexes.mb_wait(timeout_s=1)["woke"])
        threading.Timer(.2, self.w.tell, ("food_below:eat",)).start()
        with mock.patch.object(reflexes, "WAIT_SLICE", .05):
            self.assertTrue(reflexes.mb_wait(timeout_s=30)["woke"])
        self.assertEqual(reflexes.deliver(), [{"told": "food_below:eat", "tick": 100}])
        reflexes._news(reflexes._box(), self.w.status())
        self.assertEqual(reflexes.deliver(), [])  # once
        self.assertFalse(self.w.clock["paused"])

    def test_a_tell_check_and_a_faulting_check_come_back_once(self):
        reflexes.mb_reflex("ore", when=CHECK, do="tell")
        reflexes.mb_reflex("bad", when={"queries": CHECK["queries"], "condition": {"changed": "x.absent"}})
        sup = mbtool.state["interrupts"]; self.w.value = 1; sup.poll()
        self.until(lambda: len(reflexes._box()["fired"]) == 2)
        got = sorted(reflexes.deliver(), key=lambda f: "told" in f)
        self.assertEqual(got[1], {"told": "ore"}); self.assertEqual(got[0]["rule"], "bad"); self.assertIn("x.absent", got[0]["fault"])
        self.assertEqual((self.w.fires[0]["effects"], self.w.clock["paused"]), (["notify"], False))
        self.assertIn("x.absent", [r for r in reflexes.mb_reflex()["rules"] if r["name"] == "bad"][0]["fault"])
        reflexes.mb_reflex("bad", when={"queries": CHECK["queries"], "condition": {"exists": "x.n"}})  # set again: armed again
        self.assertNotIn("fault", reflexes.mb_reflex("bad"))

    def test_mb_reflex_lists_shows_edits_and_checks_what_it_is_given(self):
        self.assertEqual([r["name"] for r in reflexes.mb_reflex()["rules"]], [r["name"] for r in DEFAULTS])
        for bad, why in ((dict(when="food_below 6 7"), "level after it"), (dict(when="food_below x"), "not a level"),
                         (dict(when={"condition": {"wat": 1}}), "unknown condition"), (dict(do="def main():\n pass"), "main.event."),
                         (dict(when={**CHECK, "effects": ["notify"]}), "disagree"),
                         (dict(do="def main(event:\n"), "does not parse"), (dict(then="go"), "resume"), (dict(max_ticks=0), "max_ticks")):
            with self.assertRaisesRegex(ValueError, why): self.arm(**bad)
        with self.assertRaisesRegex(ValueError, "add it with when"): reflexes.mb_reflex("r", do="def main(event):\n pass")
        with self.assertRaisesRegex(ValueError, "a script's"): reflexes.mb_reflex("health_below", then="pause")
        with self.assertRaisesRegex(ValueError, "no rule is named"): reflexes.mb_reflex("r", remove=True)
        self.arm()
        self.assertEqual(reflexes.mb_reflex("r", then="pause")["rule"], {"name": "r", "when": "health_below 8", "do": "script"})
        self.assertEqual(reflexes.mb_reflex("r", when="health_below 12.5")["rule"]["when"], "health_below 12.5")  # the script stays
        shown = reflexes.mb_reflex("r")
        self.assertEqual((shown["then"], shown["level"], shown["kind"]), ("pause", 12.5, "health_below")); self.assertIn("def main(event)", shown["do"])
        self.assertEqual(list(self.w.table[-1]), ["name", "kind", "level", "do", "then"])
        self.assertNotIn("then", reflexes.mb_reflex("r", do="ask") and self.w.table[-1])  # ask drops the script's options
        self.assertEqual(reflexes.mb_reflex("r", remove=True)["removed"], "r")
        self.assertEqual(self.w.table, DEFAULTS)

    def test_a_script_is_not_run_inside_a_background_task(self):
        self.arm(); mbtool.state["task"] = "t1"; self.addCleanup(mbtool.state.pop, "task", None)
        self.assertEqual(self.walk()["state"], "suspended")
        self.assertEqual(self.w.acts(), [("nav.goto", {"x": 5})])


if __name__ == '__main__':
    unittest.main()
