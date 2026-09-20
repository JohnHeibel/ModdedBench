# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 Modbench contributors
import asyncio
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "harness" / "launcher"))
sys.path.insert(0, str(ROOT / "harness" / "runner"))
sys.path.insert(0, str(ROOT / "harness" / "mcp"))
from autonomous_runner import AutonomousRunner, Journal, ManagedDeploy, ModuleAdapter, RunnerState
import mbtool  # noqa: E402,F401
from mbtools_gtnh.interrupts import race_interrupt  # noqa: E402


class EventSupervisor:
    def __init__(self):
        self.payload = {"gap": False, "cursor": 0, "events": []}
    def events(self, cursor, wait_s=0):
        return self.payload


class RunnerTests(unittest.TestCase):
    def test_recovered_prompt_reaches_next_model_request_with_observations(self):
        requests = []
        class Adapter:
            async def infer(self, request):
                requests.append(request)
                return {"calls": []}
        with tempfile.TemporaryDirectory() as directory:
            journal = Journal(Path(directory))
            journal.save(RunnerState(objective="produce", pending_interrupts=[{
                "kind": "triggered", "name": "blocked", "data": {
                    "eventId": "e1", "payload": {"modelPrompt": "Choose a repair.", "count": 3}}}]))
            runner = AutonomousRunner(Adapter(), journal, "produce", {})
            asyncio.run(runner._decision(EventSupervisor()))
            self.assertEqual([{"eventId": "e1", "watch": "blocked", "prompt": "Choose a repair.",
                               "observations": {"count": 3}}], requests[0]["modelPrompts"])
            self.assertEqual("e1", runner.state.pending_interrupts[0]["data"]["eventId"])

    def test_interrupt_discards_provider_that_suppresses_cancellation(self):
        supervisor = EventSupervisor()
        returned = []
        cancelled = []

        async def stubborn():
            try:
                await asyncio.sleep(1)
            except asyncio.CancelledError:
                returned.append("obsolete")
                return {"calls": [{"method": "act.input"}]}

        async def exercise():
            task = asyncio.create_task(race_interrupt(supervisor, stubborn(), cancel=lambda: cancelled.append(True), poll_s=.001))
            await asyncio.sleep(.01)
            supervisor.payload = {"gap": False, "cursor": 7, "events": [
                {"id": 7, "kind": "triggered", "name": "health", "data": {"eventId": "e7"}}]}
            return await task

        result = asyncio.run(exercise())
        self.assertTrue(result["interrupted"])
        self.assertEqual(result["cursor"], 7)
        self.assertEqual(returned, ["obsolete"])
        self.assertEqual(cancelled, [True])
        self.assertNotIn("result", result)

    def test_checkpoint_recovery_preserves_interrupt_and_budget_state(self):
        with tempfile.TemporaryDirectory() as directory:
            journal = Journal(Path(directory))
            state = RunnerState(objective="survive", iteration=4, cursor=19,
                                pending_interrupts=[{"id": 19, "kind": "fault"}],
                                deploys=1, calls=12, wakeups=3, reconnects=2,
                                connection_generation=2)
            journal.save(state)
            journal.append("test", value=3)
            recovered = Journal(Path(directory)).load()
            self.assertEqual(recovered, state)
            line = json.loads((Path(directory) / "events.jsonl").read_text().strip())
            self.assertEqual((line["kind"], line["value"]), ("test", 3))
            self.assertFalse(any(Path(directory).glob("*.tmp")))

    def test_failed_launch_rolls_back_every_installed_component_and_relaunches(self):
        calls = []
        failed = False
        def command(args):
            nonlocal failed
            calls.append(args)
            if args[0] == "launch-client" and not failed:
                failed = True
                raise RuntimeError("new client did not join")
        deploy = ManagedDeploy(Path("runtime"), command=command, launch_timeout=9)
        with self.assertRaisesRegex(RuntimeError, "prior jars were restored"):
            deploy.deploy(["client", "control", "baritone"])
        self.assertEqual(calls, [
            ["stop-client"], ["build"], ["install-control"], ["install-baritone"],
            ["install-client"], ["launch-client", "--timeout", "9"], ["stop-client"],
            ["rollback-client"], ["rollback-baritone"], ["rollback-control"],
            ["launch-client", "--timeout", "9"]])

    def test_build_failure_does_not_attempt_rollback(self):
        calls = []
        def command(args):
            calls.append(args)
            if args == ["build"]: raise RuntimeError("compile")
        with self.assertRaisesRegex(RuntimeError, "before jar replacement"):
            ManagedDeploy(Path("runtime"), command=command).deploy(["client"])
        self.assertEqual(calls, [["stop-client"], ["build"],
                                 ["launch-client", "--timeout", "300"]])

    def test_crash_window_recovers_as_uncertain_effect(self):
        with tempfile.TemporaryDirectory() as directory:
            journal = Journal(Path(directory))
            state = RunnerState(objective="survive", uncertain_effect={
                "method": "act.use_item", "params": {"hand": "main"}})
            journal.save(state)
            recovered = journal.load()
            self.assertEqual(recovered.uncertain_effect["method"], "act.use_item")

    def test_adapter_reload_ignores_same_timestamp_and_size_pyc(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "adapter.py"
            v1 = "async def infer(request):\n    return {'value': 'one'}\n"
            v2 = v1.replace("one", "two")
            path.write_text(v1); stamp = path.stat().st_mtime_ns
            adapter = ModuleAdapter(path)
            self.assertEqual(asyncio.run(adapter.infer({})), {"value": "one"})
            path.write_text(v2); path.touch(); path_stat = path.stat()
            import os
            os.utime(path, ns=(path_stat.st_atime_ns, stamp))
            self.assertEqual(asyncio.run(adapter.infer({})), {"value": "two"})

    def test_pending_state_allows_only_advertised_reads_and_blocks_resume(self):
        class Kernel:
            def __init__(self): self.calls=[]
            def call(self, method, **params):
                self.calls.append((method, params))
                if method == "sys.methods": return {"methods": {
                    "obs.player": {"effect":"read"}, "act.input": {"effect":"interaction"}}}
                return {"method":method}
        with tempfile.TemporaryDirectory() as directory:
            runner = AutonomousRunner(None, Journal(Path(directory)), "x", {}, max_iterations=2)
            runner.state.pending_interrupts = [{"kind":"fault", "data":{}}]
            kernel = Kernel()
            runner._apply(kernel, {"calls":[{"method":"obs.player","params":{}}]})
            self.assertEqual(runner.state.calls, 1)
            with self.assertRaisesRegex(ValueError, "advertised read"):
                runner._apply(kernel, {"calls":[{"method":"act.input","params":{}}]})
            runner.state.uncertain_effect = {"method":"act.input","params":{}}
            with self.assertRaisesRegex(ValueError, "uncertain effects"):
                runner._apply(kernel, {"resume":True})
            original = dict(runner.state.uncertain_effect)
            runner._apply(kernel, {"calls":[{"method":"obs.player","params":{}}]})
            self.assertEqual(original,runner.state.uncertain_effect)

    def test_native_failure_returns_receipt_and_stops_remaining_calls(self):
        from kernel import BridgeError
        class Kernel:
            calls=[]
            def call(self,method,**params):
                self.calls.append(method)
                raise BridgeError('path_failed','preflight_missingItems',method,{'error':{'receipt':{'jobId':'abc'}}})
        with tempfile.TemporaryDirectory() as directory:
            runner=AutonomousRunner(None,Journal(Path(directory)),'x',{})
            kernel=Kernel()
            runner._apply(kernel,{'calls':[{'method':'baritone.build'},{'method':'act.use_item'}]})
            self.assertEqual(['baritone.build'],kernel.calls)
            self.assertEqual('path_failed',runner.state.last_result[0]['error'])
            self.assertIsNone(runner.state.uncertain_effect)

    def test_call_and_deploy_budgets_are_reserved_before_failure(self):
        class Kernel:
            def call(self, method, **params): raise ConnectionError("lost reply")
        class Deploy:
            def deploy(self, components): raise RuntimeError("failed")
        with tempfile.TemporaryDirectory() as directory:
            journal = Journal(Path(directory)); runner = AutonomousRunner(None, journal, "x", {}, deployer=Deploy())
            with self.assertRaises(ConnectionError):
                runner._apply(Kernel(), {"calls":[{"method":"act.use_item","params":{}}]})
            self.assertEqual(journal.load().calls, 1)
            self.assertIsNotNone(journal.load().uncertain_effect)
            runner.state.uncertain_effect = None
            with self.assertRaises(ConnectionError):
                runner._apply(Kernel(), {"deploy":{"components":["client"]}})
            self.assertEqual(journal.load().deploys, 1)
            self.assertTrue(journal.load().last_result[0]['deploy']['reviewRequired'])

    @staticmethod
    def _trigger(event_id, context):
        return {"kind":"triggered", "name":"health", "data":{"eventId":event_id,
            "receipt":{"eventId":event_id,"context":context,"latched":True}}}

    def test_old_jvm_interrupt_requires_review_and_preserves_current_latch(self):
        old={"worldId":"w","dimension":0,"bridgeId":"old","worldEpoch":1}
        new={"worldId":"w","dimension":0,"bridgeId":"new","worldEpoch":1}
        current=self._trigger("new-event",new)["data"]["receipt"]
        class Kernel:
            def __init__(self): self.calls=[]
            def call(self,method,**params):
                self.calls.append((method,params))
                if method=="interrupt.status": return {"context":new,"latched":["new-event"],"receipts":[current]}
                return {"ok":True}
        with tempfile.TemporaryDirectory() as directory:
            journal=Journal(Path(directory)); runner=AutonomousRunner(None,journal,"x",{})
            runner.state.pending_interrupts=[self._trigger("old-event",old)]
            kernel=Kernel()
            with self.assertRaisesRegex(ValueError,"all triggering"):
                runner._apply(kernel,{"ack":["old-event"],"reviewed":True})
            recovered=journal.load()
            self.assertEqual(["new-event"],[e["data"]["eventId"] for e in recovered.pending_interrupts])
            self.assertFalse(any(method=="interrupt.ack" for method,_ in kernel.calls))
            runner._apply(kernel,{"ack":["new-event"],"reviewed":True,"resume":True})
            self.assertEqual([],journal.load().pending_interrupts)
            self.assertIsNone(journal.load().uncertain_effect)
            self.assertIn(("interrupt.ack",{"eventId":"new-event"}),kernel.calls)
            self.assertIn(("time.resume",{}),kernel.calls)

    def test_same_context_unknown_event_is_not_treated_as_stale(self):
        context={"worldId":"w","dimension":0,"bridgeId":"same","worldEpoch":2}
        class Kernel:
            def call(self,method,**params):
                if method=="interrupt.status": return {"context":context,"latched":[],"receipts":[]}
                if method=="interrupt.ack": raise ValueError("unknown eventId")
        with tempfile.TemporaryDirectory() as directory:
            runner=AutonomousRunner(None,Journal(Path(directory)),"x",{})
            runner.state.pending_interrupts=[self._trigger("missing",context)]
            with self.assertRaisesRegex(ValueError,"unknown eventId"):
                runner._apply(Kernel(),{"ack":["missing"],"reviewed":True})
            self.assertEqual("missing",runner.state.pending_interrupts[0]["data"]["eventId"])

    def test_each_native_ack_is_checkpointed_before_next_ack(self):
        context={"worldId":"w","dimension":0,"bridgeId":"b","worldEpoch":1}
        class Kernel:
            def call(self,method,**params):
                if method=="interrupt.status": return {"context":context,"latched":["one","two"],"receipts":[]}
                if method=="interrupt.ack" and params["eventId"]=="two": raise ConnectionError("lost")
                return {"ok":True}
        with tempfile.TemporaryDirectory() as directory:
            journal=Journal(Path(directory)); runner=AutonomousRunner(None,journal,"x",{})
            runner.state.pending_interrupts=[self._trigger("one",context),self._trigger("two",context)]
            with self.assertRaises(ConnectionError):
                runner._apply(Kernel(),{"ack":["one","two"],"reviewed":True})
            self.assertEqual(["two"],[e["data"]["eventId"] for e in journal.load().pending_interrupts])

    def test_resume_dispatch_crash_is_durably_uncertain(self):
        class Kernel:
            def call(self,method,**params): raise ConnectionError("lost resume reply")
        with tempfile.TemporaryDirectory() as directory:
            journal=Journal(Path(directory)); runner=AutonomousRunner(None,journal,"x",{})
            with self.assertRaises(ConnectionError): runner._apply(Kernel(),{"resume":True})
            self.assertEqual("time.resume",journal.load().uncertain_effect["method"])

    def test_complete_rejects_pending_but_stop_reason_preserves_it(self):
        with tempfile.TemporaryDirectory() as directory:
            journal=Journal(Path(directory)); runner=AutonomousRunner(None,journal,"x",{})
            runner.state.pending_interrupts=[{"kind":"fault","data":{}}]
            with self.assertRaisesRegex(ValueError,"objective completion"):
                runner._apply(object(),{"complete":True})
            with self.assertRaisesRegex(ValueError,"standalone"):
                runner._apply(object(),{"stopReason":"stop","ack":["anything"]})
            runner._apply(object(),{"stopReason":"health interrupt ended pilot"})
            recovered=journal.load()
            self.assertFalse(recovered.complete)
            self.assertEqual("health interrupt ended pilot",recovered.stop_reason)
            self.assertEqual(1,len(recovered.pending_interrupts))

    def test_reconnect_discards_cached_method_effects(self):
        class Supervisor:
            def add(self,*args): pass
        with tempfile.TemporaryDirectory() as directory:
            runner=AutonomousRunner(None,Journal(Path(directory)),"x",{},kernel_factory=lambda:object(),
                                    supervisor_factory=lambda kernel:Supervisor())
            runner._method_effects={"act.input":"read"}
            runner._connect()
            self.assertIsNone(runner._method_effects)


if __name__ == "__main__":
    unittest.main()
