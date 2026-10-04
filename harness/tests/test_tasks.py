# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Background tasks (harness/tools/tasks.py) against fake kernels and fake processes; no bridge, no game."""
import importlib
import itertools
import json
import os
import signal
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'mcp'))
import mbtool  # noqa: E402
from kernel import BridgeError, Kernel, Reply, resume_once  # noqa: E402
from mbtools_gtnh import core, interrupts, scripts, tasks, work  # noqa: E402


class FakeKernel:
    """The calls a task's bookkeeping makes, recorded; the clock and the open screen are plain dicts."""
    def __init__(self, paused=False, conditions=None, views=None):
        self.calls, self.clock = [], {"paused": paused, "conditions": dict(conditions or {"healthDrop": 4})}
        self.views = list(views or [{"open": False}])

    def call(self, method, timeout=None, **params):
        self.calls.append((method, params))
        if method == "time.status": return {"state": json.loads(json.dumps(self.clock))}
        if method == "time.pause": self.clock["paused"] = True
        if method == "time.configure": self.clock["conditions"].update(params)
        if method == "obs.container": return self.views.pop(0) if len(self.views) > 1 else self.views[0]
        return {}

    def methods(self):
        return [m for m, _ in self.calls]


def dead_pid():
    proc = subprocess.Popen([sys.executable, "-c", "pass"]); proc.wait()
    return proc.pid


class TaskTestCase(unittest.TestCase):
    def setUp(self):
        global core, interrupts, scripts, tasks, work  # other tests re-import the tool package: use the modules the tools now see
        core, interrupts, scripts, tasks, work = (importlib.import_module(f"{mbtool.PACKAGE}.{m}") for m in ("core", "interrupts", "scripts", "tasks", "work"))
        token = resume_once.set(None); self.addCleanup(resume_once.reset, token)
        tmp = tempfile.TemporaryDirectory(); self.addCleanup(tmp.cleanup)
        self.dir = Path(tmp.name) / "tasks"; self.dir.mkdir()
        self.k = FakeKernel()
        log = Path(tmp.name) / "calls.jsonl"; self.log = log
        for patch in (mock.patch.object(tasks, "TASKS", self.dir), mock.patch.object(tasks, "kernel", lambda: self.k),
                      mock.patch.object(mbtool, "CALL_LOG", str(log)), mock.patch.object(work, "LEFT", Path(tmp.name) / "left.json")):
            patch.start(); self.addCleanup(patch.stop)
        for key in ("task", "task_procs"): mbtool.state.pop(key, None)
        self.addCleanup(lambda: [mbtool.state.pop(key, None) for key in ("task", "task_procs")])
        tasks.TASK.clear(); tasks._halt.clear()
        self.addCleanup(tasks.TASK.clear); self.addCleanup(tasks._halt.clear)

    def put(self, task, live=True, **st):
        """A status file; a running one is alive (its body lock held, as the task process would) unless live=False."""
        st = {"task": task, "name": None, "started": time.time(), "state": "running", "pid": os.getpid(), "delivered": False, **st}
        if st["state"] == "running" and live and not getattr(self, "lock", None):
            self.lock = tasks._lock(self.dir); self.addCleanup(self.free)
        tasks.save(st); return st

    def free(self):
        if getattr(self, "lock", None): self.lock.close(); self.lock = None


class Methods:
    """A connection whose game lists one read and two acting methods."""
    def __init__(self):
        self.asked = 0

    def call(self, method, timeout=None, **p):
        assert method == "sys.methods"; self.asked += 1
        return [{"name": "obs.inventory", "effect": "read"}, {"name": "act.input", "effect": "interaction"},
                {"name": "act.stop", "effect": "interaction"}]


class Game(Kernel):
    """The model's connection with a fake game behind it: every call meets the body gate, as on the real one."""
    CONTEXT = {"worldId": "w", "dimension": 0, "bridgeId": "b", "worldEpoch": 1}

    def __init__(self):
        self.timeout, self._ids, self.sent = 5.0, itertools.count(1), []

    def _request(self, req, timeout):
        method = req["method"]; self.sent.append(method)
        if method == "sys.methods":
            return Reply(True, 0, 0, 0, [{"name": "obs.player", "effect": "read", "watchable": True}, {"name": "obs.batch", "effect": "read"},
                                         {"name": "interrupt.status", "effect": "read"}, {"name": "interrupt.fire", "effect": "interaction"},
                                         {"name": "interrupt.ack", "effect": "interaction"}, {"name": "act.input", "effect": "interaction"}])
        if method == "obs.batch": return Reply(True, 0, 0, 0, {"values": {"me": {"health": 6}}, "errors": {}, "context": self.CONTEXT})
        return Reply(True, 0, 0, 0, {"context": self.CONTEXT, "operationId": 1})


class BodyLockTests(TaskTestCase):
    def test_a_watch_fires_and_is_acknowledged_while_a_task_has_the_body(self):
        k = Game()
        sup = interrupts.InterruptSupervisor(k, self.dir / "watches", poll_s=10, fire_backoff_s=.001); self.addCleanup(sup.close)
        sup.add("low", {"queries": {"me": {"method": "obs.player"}}, "condition": {"lt": ["me.health", 8]}, "effects": ["notify", "pause"]})
        self.put("t1", name="vein")
        sup.poll()
        until = time.monotonic() + 2
        while time.monotonic() < until and not [e for e in sup.events(0)["events"] if e["kind"] in ("triggered", "reaction_error")]: time.sleep(.01)
        self.assertEqual([e["kind"] for e in sup.events(0)["events"] if e["kind"] in ("triggered", "reaction_error")], ["triggered"])
        self.assertEqual(k.sent.count("interrupt.fire"), 1)
        k.call("interrupt.ack", eventId="e1")
        self.assertRaises(BridgeError, k.call, "act.input")  # the body itself stays the task's

    def test_game_calls_that_move_the_body_are_refused_with_the_task_named_and_reads_are_marked(self):
        self.put("t1", name="vein", now="mb_mine")
        k = Methods()
        with self.assertRaises(BridgeError) as refused:
            tasks.gate(k, "act.input")
        self.assertEqual(refused.exception.code, "body_busy")
        for words in ("t1", "(vein)", "mb_mine", "mb_task(wait=", "mb_task(cancel=True)"):
            self.assertIn(words, refused.exception.msg)
        self.assertRaises(BridgeError, tasks.gate, k, "nav.new_thing")  # what the game does not call a read is refused
        for method in ("obs.inventory", "time.pause", "act.stop", "memory.waypoint"):
            tasks.gate(k, method)  # reads, time control, stopping and bookkeeping still run
        self.assertRaises(BridgeError, tasks.gate, k, "memory.protect")  # it cancels the running job
        self.assertEqual(k.asked, 1)  # the game's method list is read once per connection
        read, acted = tasks.fields(read=True), tasks.fields(read=False)
        self.assertEqual((read["body"]["task"], read["body"]["now"], read["bodyBusy"]), ("t1", "mb_mine", True))
        self.assertNotIn("bodyBusy", acted)

    def test_a_live_task_in_a_paused_world_says_why_it_is_paused(self):
        self.put("t1", name="vein", now="mb_mine")
        self.assertNotIn("paused", tasks.fields()["body"])  # a running world: the task is working
        self.k.clock.update(paused=True, reason="health_dropped")
        self.assertEqual(tasks.fields(read=True)["body"]["paused"], "health_dropped")  # no tick runs, so neither does the task
        self.k.call = mock.Mock(side_effect=TimeoutError("no bridge"))
        self.assertEqual(tasks.fields()["body"]["task"], "t1")  # no clock to read: the body fields still come

    def test_a_free_body_adds_nothing_and_a_held_lock_refuses_a_start(self):
        k = Methods()
        tasks.gate(k, "act.input")
        self.assertEqual(k.asked, 0)  # nothing holds the body: no question to the game
        self.assertEqual(tasks.fields(read=True), {})
        lock = tasks._lock(self.dir); self.addCleanup(lock.close)
        self.assertTrue(tasks.held())
        with self.assertRaises(BridgeError) as refused:
            tasks.start("def main(): pass", None, None, None, 20)
        self.assertEqual(refused.exception.code, "body_busy")

    def test_start_checks_its_arguments_and_refuses_inside_a_task(self):
        self.assertRaises(ValueError, tasks.start, "x", None, None, "stop", 20)
        self.assertRaises(ValueError, tasks.start, "x", None, None, None, 61)
        self.assertRaises(ValueError, tasks.start, "x", None, None, None, 0)
        mbtool.state["task"] = "t0"
        self.assertRaisesRegex(ValueError, "cannot start another", tasks.start, "x", None, None, None, 20)
        self.assertRaisesRegex(ValueError, "background task", interrupts.get_supervisor)
        self.assertEqual(tasks.deliver(), [])  # only the model's own process hands results over

    def test_start_resumes_a_paused_world_and_spawns_the_runner(self):
        self.k.clock["paused"] = True
        resumed = []
        self.k._resume_for = lambda record: (resumed.append(1), record.update(resumed=True))
        with mock.patch.object(tasks.subprocess, "Popen") as popen:
            out = tasks.start("def main(): pass", {"n": 1}, "chore", "pause", 5)
        self.assertEqual((resumed, out["resumedWorld"]), ([1], {"resumed": True}))
        argv = popen.call_args.args[0]
        self.assertEqual(argv[-2:], [str(tasks.RUNNER), out["task"]])
        st = tasks.load(out["task"])
        self.assertEqual((st["state"], st["on_fail"], st["minutes"], st["args"]), ("running", "pause", 5, {"n": 1}))
        self.assertIs(mbtool.state["task_procs"][out["task"]], popen.return_value)


class DeliveryTests(TaskTestCase):
    def test_a_finished_task_is_handed_over_exactly_once(self):
        self.put("t1", state="done", ended="returned", endedAt=time.time(), result={"mined": 40})
        first = tasks.fields()["finished"]
        self.assertEqual([(f["task"], f["state"], f["result"]) for f in first], [("t1", "done", {"mined": 40})])
        self.assertNotIn("finished", tasks.fields())
        self.assertEqual(tasks.deliver(), [])

    def test_the_list_is_capped_and_says_how_many_more(self):
        for n in range(7):
            self.put(f"t{n}", state="failed", started=1000 + n, endedAt=2000, error="boom")
        out = tasks.deliver()
        self.assertEqual([f.get("task") for f in out[:5]], ["t6", "t5", "t4", "t3", "t2"])
        self.assertEqual(out[5], {"older": 2})
        self.assertEqual(tasks.deliver(), [])

    def test_a_long_result_is_cut_in_the_hand_over(self):
        self.put("t1", state="done", endedAt=time.time(), result="x" * 5000)
        [f] = tasks.deliver()
        self.assertIn("mb_task shows it whole", f["result"])

    def test_old_delivered_tasks_are_pruned(self):
        for n in range(tasks.KEEP + 3):
            self.put(f"t{n:02d}", state="done", started=1000 + n, endedAt=2000, delivered=True)
        tasks.deliver()
        self.assertEqual(len(list(self.dir.glob("*.json"))), tasks.KEEP)
        self.assertTrue((self.dir / f"t{tasks.KEEP + 2:02d}.json").exists())


class CrashTests(TaskTestCase):
    def test_a_running_task_whose_process_died_is_crashed_and_on_fail_pauses(self):
        self.put("t1", live=False, pid=dead_pid(), on_fail="pause", guards={"healthDrop": 8})
        [f] = tasks.fields()["finished"]
        self.assertEqual((f["state"], f["ended"], f["paused"], f["guardsRestored"]), ("crashed", "crash", True, True))
        self.assertIn(("time.pause", {"reason": "background_task_crashed"}), self.k.calls)
        self.assertIn(("time.configure", {"healthDrop": 8}), self.k.calls)

    def test_a_crash_without_on_fail_never_pauses(self):
        self.put("t1", live=False, pid=dead_pid())
        [f] = tasks.deliver()
        self.assertEqual(f["state"], "crashed")
        self.assertNotIn("time.pause", self.k.methods())

    def test_a_runner_that_exits_before_writing_its_pid_is_crashed(self):
        self.put("t1", live=False, pid=None)  # as start leaves it
        proc = mock.Mock(); proc.poll.return_value = 2; proc.returncode = 2
        mbtool.state["task_procs"] = {"t1": proc}
        [f] = tasks.deliver()
        self.assertEqual(f["state"], "crashed"); self.assertIn("(2)", f["error"])
        self.assertEqual(mbtool.state["task_procs"], {})

    def test_liveness_is_the_body_lock_not_the_pid(self):
        self.put("t1", pid=os.getpid())  # this process is alive, but what matters is the lock
        self.assertEqual(tasks.load("t1")["state"], "running")
        self.free()
        st = tasks.load("t1")
        self.assertEqual((st["state"], st["ended"]), ("crashed", "crash"))
        self.assertLessEqual(st["lastSeen"], st["endedAt"])

    def test_the_kernel_asks_the_gate_before_sending_and_the_task_kernel_never_does(self):
        self.put("t1")
        self.assertIs(Kernel.body_gate, tasks.gate)
        self.assertIsNone(tasks.TaskKernel.body_gate)
        k = Kernel.__new__(Kernel); k.timeout, k._effects = 5.0, {"act.input": "interaction"}  # no connection: nothing is sent
        with mock.patch.object(k, "_request") as sent:
            self.assertRaises(BridgeError, k.call, "act.input")
        sent.assert_not_called()

    def test_handing_the_body_back_passes_the_gate(self):
        self.put("t1")
        k = mock.Mock(); k.__dict__["_effects"] = {}
        k.call.side_effect = lambda method, **p: (tasks.gate(k, method), {"open": True, "cursor": None})[1]
        tasks.release(k)
        self.assertEqual([c.args[0] for c in k.call.call_args_list], ["act.stop", "obs.container", "gui.close"])

    def test_a_zombie_is_dead_even_while_the_lock_looks_held(self):
        self.put("t1", pid=4242)
        with mock.patch.object(tasks, "_zombie", lambda pid: pid == 4242):
            self.assertEqual(tasks.load("t1")["state"], "crashed")
        self.assertIsNone(tasks.live())

    def test_a_task_just_spawned_has_a_grace_to_take_the_lock(self):
        st = self.put("t1", live=False, pid=None)
        self.assertEqual(tasks.live()["task"], "t1")
        k = Methods()
        with mock.patch.dict(mbtool.state, {"task_spawned": st["started"]}):
            tasks.gate(k, "obs.inventory")
            self.assertRaises(BridgeError, tasks.gate, k, "act.input")  # the refusal uses the same answer
        self.put("t2", live=False, pid=None, started=time.time() - 31)
        self.assertEqual(tasks.load("t2")["state"], "crashed")

    def test_zombie_reads_proc_where_there_is_one(self):
        self.assertFalse(tasks._zombie(None))
        stat = mock.mock_open(read_data="123 (python3 (x)) Z 1 1 1")
        with mock.patch("builtins.open", stat):
            self.assertTrue(tasks._zombie(123))
        with mock.patch("builtins.open", mock.mock_open(read_data="123 (python3) S 1 1 1")):
            self.assertFalse(tasks._zombie(123))


class CancelTests(TaskTestCase):
    def test_a_task_that_will_not_stop_is_killed_and_the_body_given_back_without_a_pause(self):
        proc = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"])
        self.addCleanup(lambda: (proc.kill(), proc.wait()))
        self.put("t1", pid=proc.pid, on_fail="pause", guards={"healthDrop": 8})
        self.k.views = [{"open": True, "cursor": {"id": "x", "count": 3}, "windowId": 4, "epoch": 2,
                         "slots": [{"i": 9, "kind": "main", "ordinary": True}, {"i": 0, "kind": "machine", "ordinary": True}]},
                        {"open": True, "cursor": None}]
        st = tasks.cancel_task("t1", grace=0.5)
        self.assertEqual((st["state"], st["ended"]), ("cancelled", "cancelled"))
        self.assertIn("killed", st["error"])
        proc.wait(10)
        names = self.k.methods()
        self.assertEqual([m for m in names if m.startswith(("act.", "gui."))], ["act.stop", "gui.return_cursor", "gui.close"])
        self.assertEqual(dict(self.k.calls)["gui.return_cursor"]["destinations"], [9])
        self.assertIn(("time.configure", {"healthDrop": 8}), self.k.calls)  # guards back
        self.assertNotIn("time.pause", names)  # a cancel never pauses

    def test_a_held_stack_is_never_dropped_by_closing(self):
        self.k.views = [{"open": True, "cursor": {"id": "x"}, "windowId": 1, "epoch": 1, "slots": []}]
        tasks.release(self.k)
        self.assertNotIn("gui.close", self.k.methods())

    def test_end_all_waits_then_cancels_and_cancel_of_an_ended_task_is_its_status(self):
        self.assertIsNone(tasks.end_all("deploy"))
        self.put("t1", state="done", endedAt=time.time())
        self.assertEqual(tasks.cancel_task("t1")["state"], "done")
        self.assertFalse((self.dir / "t1.cancel").exists())

    def test_mb_task_returns_the_status_and_marks_it_handed_over(self):
        self.put("t1", state="failed", ended="error", endedAt=time.time(), error="boom", line=3)
        self.put("t0", state="done", started=1, endedAt=2)
        out = tasks.mb_task()
        self.assertEqual((out["task"], out["state"], out["error"], out["line"]), ("t1", "failed", "boom", 3))
        self.assertNotIn("pid", out)
        self.assertEqual(out["recent"], [{"task": "t0", "name": None, "state": "done"}])
        self.assertNotIn("t1", [f.get("task") for f in tasks.deliver()])
        self.assertRaises(ValueError, tasks.mb_task, wait=901)
        self.assertEqual(tasks.mb_task(task="nope"), {"task": None, "recent": []})


class RunnerTests(TaskTestCase):
    """tasks.run, the task process's main, in this process: a fake kernel, fake tools, a short or expired limit."""
    def run_task(self, code, tools, **st):
        self.put("t1", live=False, **{"pid": None, "args": {}, "minutes": 20, **st})  # as start leaves it
        stop = signal.getsignal(signal.SIGTERM) if os.name != "nt" else None
        with mock.patch.object(scripts, "_tools", return_value=tools), mock.patch.object(mbtool, "set_kernel_factory"):
            (self.dir / "t1.py").write_text(code, encoding="utf-8")
            self.assertEqual(tasks.run("t1"), 0)
        if stop is not None: signal.signal(signal.SIGTERM, stop)
        return json.loads((self.dir / "t1.json").read_text(encoding="utf-8"))

    def calls(self):
        return [json.loads(l) for l in self.log.read_text(encoding="utf-8").splitlines()]

    def test_a_script_that_returns_is_done_with_its_calls_logged_as_the_task(self):
        st = self.run_task("def main():\n log('hi')\n return spin() + 1", {"spin": lambda: 41}, on_fail="pause")
        self.assertEqual((st["state"], st["ended"], st["result"], st["log"]), ("done", "returned", 42, ["hi"]))
        self.assertNotIn("time.pause", self.k.methods())  # success never pauses
        spin, summary = self.calls()
        self.assertEqual((spin["tool"], spin["caller"], summary["tool"], summary["error"]), ("spin", "t1", "task", None))
        self.assertFalse(tasks.held(self.dir))  # the lock went with it

    def test_a_cancel_stops_the_script_between_calls_and_never_pauses(self):
        (self.dir / "t1.cancel").write_text("cancelled", encoding="utf-8")
        st = self.run_task("def main():\n while True:\n  try: spin()\n  except Exception: pass",
                           {"spin": lambda: time.sleep(0.05)}, on_fail="pause")
        self.assertEqual((st["state"], st["ended"]), ("cancelled", "cancelled"))
        self.assertIn("act.stop", self.k.methods())
        self.assertNotIn("time.pause", self.k.methods())

    def test_the_time_limit_fails_the_task_and_on_fail_pauses(self):
        st = self.run_task("def main():\n while True: spin()", {"spin": lambda: time.sleep(0.05)},
                           on_fail="pause", minutes=1, started=time.time() - 61)
        self.assertEqual((st["state"], st["ended"], st["paused"]), ("failed", "time_limit", True))
        self.assertIn(("time.pause", {"reason": "background_task_failed"}), self.k.calls)

    def test_an_error_fails_the_task_with_its_line_and_on_fail_pauses(self):
        def boom(): raise ValueError("no ore here")
        st = self.run_task("def main():\n x = 1\n boom()", {"boom": boom}, on_fail="pause")
        self.assertEqual((st["state"], st["ended"], st["line"]), ("failed", "error", 3))
        self.assertIn("no ore here", st["error"])
        self.assertTrue(st["paused"])

    def test_a_guard_stop_ends_the_task_whatever_it_catches_and_on_fail_pauses(self):
        def act(): raise BridgeError("bad_request", "interrupt_latched: 1 delivered", "act.input")
        st = self.run_task("def main():\n for _ in range(3):\n  try: act()\n  except BaseException: pass\n return 'carried on'",
                           {"act": act}, on_fail="pause")
        self.assertEqual((st["state"], st["ended"], st["interrupted"]["tool"]), ("interrupted", "guard", "act"))
        self.assertEqual(sum(c["tool"] == "act" for c in self.calls()), 1)  # no second call after the stop
        self.assertTrue(st["paused"])

    def test_without_on_fail_a_failure_does_not_pause(self):
        def boom(): raise ValueError("x")
        st = self.run_task("def main(): boom()", {"boom": boom})
        self.assertEqual(st["state"], "failed")
        self.assertNotIn("time.pause", self.k.methods())


class FinishTests(TaskTestCase):
    def test_guards_the_script_changed_are_put_back(self):
        tasks.TASK.update(self.put("t1", guards={"healthDrop": 4}))
        self.k.clock["conditions"]["healthDrop"] = 10
        st = tasks.finish(self.k, {"result": 1, "log": []}, None)
        self.assertEqual((st["state"], st["guardsChanged"], st["guardsRestored"]), ("done", {"healthDrop": [4, 10]}, True))
        self.assertEqual(self.k.clock["conditions"]["healthDrop"], 4)

    def test_guards_the_script_put_back_itself_are_left_alone(self):
        tasks.TASK.update(self.put("t1", guards={"healthDrop": 4}))
        st = tasks.finish(self.k, {"result": 1, "log": []}, None)
        self.assertNotIn("guards", st); self.assertIsNone(st.get("guardsChanged"))
        self.assertNotIn("time.configure", self.k.methods())

    def test_a_script_pause_ends_the_task_as_paused_by_script(self):
        tasks.TASK.update(self.put("t1", on_fail="pause", pausedByScript=True))
        st = tasks.finish(self.k, {"stopped": "ScriptPaused: the script paused the world", "line": 2, "log": []}, None)
        self.assertEqual((st["state"], st["ended"]), ("paused_by_script", "pause"))
        self.assertNotIn("time.pause", self.k.methods())  # not a failure: on_fail has nothing to do


class TaskKernelTests(TaskTestCase):
    def kernel(self, replies):
        """A TaskKernel whose bridge calls go to replies(method, params) and are recorded."""
        seen = []

        def bridge(k, method, timeout=None, **params):
            seen.append((method, params)); out = replies(method, params)
            if isinstance(out, Exception): raise out
            return out
        patch = mock.patch.object(Kernel, "call", bridge); patch.start(); self.addCleanup(patch.stop)
        k = object.__new__(tasks.TaskKernel); k.timeout = 5
        return k, seen

    def test_resume_and_step_are_refused(self):
        k, seen = self.kernel(lambda m, p: {})
        self.assertRaisesRegex(ValueError, "never resumes", k.call, "time.resume")
        self.assertRaisesRegex(ValueError, "never resumes", k.call, "time.step", ticks=5)
        token = resume_once.set({})
        try: self.assertRaisesRegex(ValueError, "never resumes", k.call, "act.input")
        finally: resume_once.reset(token)
        self.assertEqual(seen, [])

    def test_a_pause_ends_the_task_and_configure_remembers_the_first_values(self):
        tasks.TASK.update(self.put("t1"))
        k, seen = self.kernel(lambda m, p: {"state": {"conditions": {"healthDrop": 4, "threatWithin": 8}}} if m == "time.status" else {})
        k.call("time.configure", healthDrop=10)
        k.call("time.configure", healthDrop=12, threatWithin=0)
        self.assertEqual(tasks.TASK["guards"], {"healthDrop": 4, "threatWithin": 8})
        self.assertEqual(tasks.load("t1")["guards"], {"healthDrop": 4, "threatWithin": 8})  # saved, in case the process dies
        with self.assertRaises(tasks.ScriptPaused):
            k.call("time.pause", reason="think")
        self.assertEqual([m for m, _ in seen], ["time.status", "time.status"])  # no server up: nothing sent from this session
        self.assertTrue(tasks._halt and tasks.TASK["pausedByScript"] and tasks.TASK["relayPending"])
        traced = tasks._traced("mb_obs", lambda: 1)
        self.assertRaises(scripts.ScriptInterrupted, traced)  # nothing more after the pause

    def test_a_requested_pause_is_waited_out_and_the_call_sent_again(self):
        clock = iter([{"paused": True, "reason": "requested_pause"}, {"paused": True, "held": True, "reason": "x"}, {"paused": False}])
        sent = []

        def replies(m, p):
            if m == "time.status": return {"state": next(clock)}
            sent.append(m)
            return BridgeError("bad_request", "time_paused: the world is paused", m) if len(sent) == 1 else {"ok": 1}
        k, _ = self.kernel(replies)
        with mock.patch.object(tasks.time, "sleep"):
            self.assertEqual(k.call("act.input", keys=[]), {"ok": 1})
        self.assertEqual(sent, ["act.input", "act.input"])

    def test_a_guard_pause_is_not_waited_out(self):
        k, _ = self.kernel(lambda m, p: {"state": {"paused": True, "reason": "healthDrop"}} if m == "time.status"
                           else BridgeError("bad_request", "time_paused: the world is paused", m))
        with self.assertRaisesRegex(BridgeError, "time_paused"):
            k.call("act.input")

    def test_time_commands_go_through_the_server_relay(self):
        tasks.TASK.update(task="t1", state="done")  # the task's last word: a pause here does not end anything
        server, stop = FakeKernel(), threading.Event()
        relay = threading.Thread(target=tasks.relay, args=(self.dir, lambda: server, stop), daemon=True); relay.start()
        self.addCleanup(lambda: (stop.set(), relay.join(5)))
        for _ in range(50):
            if (self.dir / "relay.json").exists(): break
            time.sleep(0.05)
        k, direct = self.kernel(lambda m, p: {})
        k.call("time.pause", reason="background_task_failed")
        self.assertIn(("time.pause", {"reason": "background_task_failed"}), server.calls)
        self.assertEqual(direct, [])  # the task's own session never sent it
        server.call = lambda m, timeout=None, **p: (_ for _ in ()).throw(BridgeError("bad_request", "operator_hold", m))
        with self.assertRaisesRegex(BridgeError, "operator_hold"):
            k.call("time.pause")
        time.sleep(0.3)  # the relay removes its claim just after it replies
        self.assertEqual(list(self.dir.glob("*.req")) + list(self.dir.glob("*.rep")) + list(self.dir.glob("*.run")), [])

    def test_with_no_server_up_time_requests_wait_for_one_and_are_never_sent_by_the_task(self):
        tasks.TASK.update(self.put("t1", on_fail="pause", guards={"healthDrop": 4}))
        (self.dir / "relay.json").write_text(json.dumps({"pid": dead_pid()}), encoding="utf-8")
        k, direct = self.kernel(lambda m, p: {"state": {"paused": False, "conditions": {"healthDrop": 9}}} if m == "time.status" else {})
        with mock.patch.object(tasks, "kernel", lambda: k):
            st = tasks.finish(k, {"stopped": "ValueError: x", "line": 1, "log": []}, None)
        self.assertEqual((st["state"], st["paused"], st["guardsRestored"], st["relayPending"]), ("failed", "pending", "pending", True))
        self.assertNotIn("time.pause", [m for m, _ in direct]); self.assertNotIn("time.configure", [m for m, _ in direct])
        self.assertTrue(tasks.entry(st)["relayPending"])
        self.assertEqual(len(list(self.dir.glob("*.req"))), 2)
        server, stop = FakeKernel(), threading.Event()  # the server starts: it sends them, oldest first, and nobody waits for a reply
        relay = threading.Thread(target=tasks.relay, args=(self.dir, lambda: server, stop), daemon=True); relay.start()
        self.addCleanup(lambda: (stop.set(), relay.join(5)))
        for _ in range(50):
            if len(server.calls) >= 2: break
            time.sleep(0.05)
        time.sleep(0.2)
        self.assertEqual(server.calls, [("time.configure", {"healthDrop": 4}), ("time.pause", {"reason": "background_task_failed"})])
        self.assertEqual([p.suffix for p in self.dir.iterdir() if p.suffix in (".req", ".rep", ".run")], [])


class AccountingTests(TaskTestCase):
    def cost(self, entries, now):
        self.log.write_text("".join(json.dumps(e) + "\n" for e in entries), encoding="utf-8")
        with mock.patch.object(core.time, "time", return_value=now):
            return core.mb_cost(hours=1)

    def test_task_calls_are_not_the_models_and_the_body_shares_are_counted(self):
        entries = [dict(t=6400, s=100, tool="mb_obs", method=None, error=None),
                   dict(t=6500, s=10, tool="mb_mine", method=None, error=None, caller="t1"),     # the task's own call
                   dict(t=6450, s=300, tool="task", method="vein", error="failed", caller="t1", end=6750),
                   dict(t=6900, s=100, tool="mb_obs", method=None, error=None)]
        out = self.cost(entries, now=7000)
        self.assertEqual(out["calls"], 2)                      # the model's two calls only
        self.assertEqual(out["bodyBusyShare"], 0.5)            # 300 of a 600 s window
        self.assertEqual(out["bodyWhileThinkingShare"], 0.42)  # 250 s of it outside the model's calls
        self.assertEqual(out["unseenFailureSeconds"], [150])   # failed at 6750, next model call at 6900

    def test_a_running_task_counts_up_to_now(self):
        self.put("t1", started=6800)
        out = self.cost([dict(t=6400, s=100, tool="mb_obs", method=None, error=None)], now=7000)
        self.assertEqual((out["bodyBusyShare"], out["bodyWhileThinkingShare"]), (0.33, 0.33))
        self.assertNotIn("unseenFailureSeconds", out)

    def test_a_crashed_task_counts_from_its_last_sign_of_life(self):
        self.put("t1", live=False, state="crashed", started=6500, lastSeen=6700, endedAt=6950)
        out = self.cost([dict(t=6400, s=100, tool="mb_obs", method=None, error=None),
                         dict(t=6950, s=1, tool="mb_obs", method=None, error=None)], now=7000)
        self.assertEqual(out["bodyBusyShare"], 0.33)          # 6500..6700 of a 600 s window
        self.assertEqual(out["unseenFailureSeconds"], [250])  # last seen at 6700, next model call at 6950

    def test_no_tasks_no_body_fields(self):
        out = self.cost([dict(t=6400, s=100, tool="mb_obs", method=None, error=None)], now=7000)
        self.assertNotIn("bodyBusyShare", out)


class PausedMiningTests(TaskTestCase):
    def test_a_paused_vein_job_says_what_is_left_and_is_listed_until_it_ends(self):
        paused = {"action": "mine", "state": "paused", "jobId": 7, "targets": [[1, 2, 3], [1, 3, 3]], "gained": {"ore": 12}}
        out = work._left(paused, [1, 2, 3])
        self.assertEqual((out["remainingTargets"], out["vein"]), (2, [1, 2, 3]))
        resumed = work._left({**paused, "targets": [[1, 3, 3]]})  # mb_work_resume knows only the job: the vein is remembered
        self.assertEqual((resumed["remainingTargets"], resumed["vein"]), (1, [1, 2, 3]))
        [row] = work.paused_mining()
        self.assertEqual((row["job"], row["vein"], row["left"], row["gained"], row["minutesAgo"]), ("7", [1, 2, 3], 1, {"ore": 12}, 0))
        work._left({**paused, "state": "succeeded", "targets": []})
        self.assertEqual(work.paused_mining(), [])

    def test_a_region_job_is_listed_by_its_centre_and_one_with_nothing_known_is_not(self):
        work._left({"action": "mine", "state": "paused", "jobId": 1, "targets": [[0, 0, 0]], "bounds": {"min": [0, 10, 0], "max": [10, 20, 4]}})
        out = work._left({"action": "mine", "state": "paused", "jobId": 2, "targets": []})
        self.assertEqual(out["remainingTargets"], 0)
        self.assertEqual([(r["job"], r["at"]) for r in work.paused_mining()], [("1", [5, 15, 2])])
        self.assertEqual(work._left({"action": "build", "state": "paused"}), {"action": "build", "state": "paused"})

    def test_the_list_is_newest_first_and_capped(self):
        for n in range(7):
            with mock.patch.object(work.time, "time", return_value=1000 + n):
                work._left({"action": "mine", "state": "paused", "jobId": n, "targets": [[n, 0, 0]]}, [n, 0, 0])
        self.assertEqual([r["job"] for r in work.paused_mining()], ["6", "5", "4", "3", "2"])

    def test_mb_status_lists_paused_mining_and_the_body(self):
        work._left({"action": "mine", "state": "paused", "jobId": 3, "targets": [[1, 1, 1]]}, [1, 1, 1])
        self.put("t1", name="vein")
        with mock.patch.object(core, "kernel", lambda: self.k), mock.patch.object(core, "notes") as notes:
            notes.attach.side_effect = lambda out, surfaced: out
            out = core.mb_status()
        self.assertEqual(out["pausedMining"][0]["job"], "3")
        self.assertEqual(out["body"]["task"], "t1")
        self.assertNotIn("finished", out)


if __name__ == "__main__":
    unittest.main()
