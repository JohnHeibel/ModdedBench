# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Offline interrupt supervisor regressions; no bridge or game is required."""
from __future__ import annotations
import asyncio, sys, tempfile, threading, time, unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "mcp"))
import importlib
import mbtool
from mbtools_gtnh.interrupts import InterruptSupervisor, race_interrupt, get_supervisor, close_supervisor, mb_wait

class FakeKernel:
    def __init__(self): self.value=0; self.context={"worldId":"w","dimension":0,"bridgeId":"b","worldEpoch":1}; self.fires=[]; self.errors={}; self.methods={"obs.x":{"effect":"read","watchable":True}}
    def call(self, method, **kw):
        if method=="sys.methods": return self.methods
        if method=="obs.batch": return {"values":{"x":{"n":self.value}},"errors":self.errors,"context":dict(self.context),"tick":1}
        if method=="interrupt.status": return {"context":dict(self.context),"operationId":9,"latched":[]}
        if method=="interrupt.fire": self.fires.append(kw); return {"ok":True,"pauseConfirmed":"pause" in kw["effects"]}
        if method=="obs.x": return {"n":self.value}
        raise AssertionError(method)

class InterruptTests(unittest.TestCase):
    def test_conditional_model_prompt_is_delivered_only_after_match(self):
        source = Path(self.tmp.name) / "decision.py"
        source.write_text("def evaluate(context):\n if context.values['x']['n'] < 2: return False\n return context.prompt('Choose the next action from current observations.', count=context.values['x']['n'])\n")
        self.s.add("decision", {"file": str(source), "queries": {"x": {"method": "obs.x"}}, "effects": ["notify", "cancel", "pause"]})
        self.s.poll()
        time.sleep(.03)
        self.assertEqual([], self.k.fires)
        self.k.value = 2
        self.s.poll()
        self.wait_fires(1)
        sent = self.k.fires[0]
        self.assertEqual(2, sent["payload"]["count"])
        self.assertIn("Choose", sent["payload"]["modelPrompt"])
        self.assertTrue(sent["latch"])
        self.s.poll()
        self.assertEqual(1, len(self.k.fires))

    def test_static_prompt_survives_uncertain_reaction(self):
        old = self.k.call
        def failed(method, **kw):
            if method == "interrupt.fire": raise TimeoutError("lost receipt")
            return old(method, **kw)
        self.k.call = failed
        self.s.add("decision", {"condition": {"exists": "x.n"}, "queries": {"x": {"method": "obs.x"}}, "prompt": "Inspect effects before deciding."})
        self.s.poll()
        deadline = time.monotonic() + 1
        events = []
        while not events and time.monotonic() < deadline:
            events = [e for e in self.s.events()["events"] if e["kind"] == "reaction_error"]
            time.sleep(.005)
        self.assertEqual("Inspect effects before deciding.", events[0]["data"]["payload"]["modelPrompt"])
        self.assertEqual((5, True), (events[0]["data"]["attempts"], events[0]["data"]["rearmed"]))
        status = self.s.status("decision")
        self.assertTrue(status["armed"]); self.assertEqual(events[0]["data"]["eventId"], status["pendingEvent"])
        for prompt in ("", "x" * 8193, {}):
            with self.assertRaises(ValueError):
                self.s.add("bad_prompt", {"condition": {"exists": "x.n"}, "prompt": prompt})

    def setUp(self): self.tmp=tempfile.TemporaryDirectory(); self.k=FakeKernel(); self.s=InterruptSupervisor(self.k,self.tmp.name,retained=3,poll_s=10,fire_backoff_s=.001)
    def tearDown(self): self.s.close(); self.tmp.cleanup()
    def wait_fires(self,n):
        end=time.monotonic()+.5
        while len(self.k.fires)<n and time.monotonic()<end: time.sleep(.005)
        self.assertEqual(n,len(self.k.fires))
    def wait_kind(self,sup,kind):
        end=time.monotonic()+2
        while time.monotonic()<end and kind not in [e["kind"] for e in sup.events(0)["events"]]: time.sleep(.005)
    def test_combinations_edge_debounce_and_receipt(self):
        self.s.add("danger",{"queries":{"x":{"method":"obs.x"}},"condition":{"all":[{"gt":["x.n",2]},{"changed":"x.n"}]},"consecutive":2,"effects":["notify","cancel"]})
        for n in (1,3,4): self.k.value=n; self.s.poll()
        self.wait_fires(1); self.assertEqual(["notify","cancel"],self.k.fires[0]["effects"])
        self.assertFalse(self.s.status("danger")["armed"])
    def test_faults_context_and_journal_gap(self):
        self.s.add("watch",{"queries":{"x":{"method":"obs.x"}},"condition":{"exists":"x.n"},"oneShot":False})
        self.k.errors={"x":{"code":"bad"}}; self.s.poll(); self.assertFalse(self.k.fires)
        self.k.errors={}; self.s.poll(); self.k.context["worldEpoch"]=2; self.s.poll()
        self.assertFalse(self.s.status("watch")["armed"])
        for i in range(6): self.s._event("noise",n=i)
        got=self.s.events(1); self.assertTrue(got["gap"]); self.assertLessEqual(len(got["events"]),3)
    def test_reload_failure_retains_callable_and_late_worker_has_no_effect(self):
        source=Path(self.tmp.name)/"watch.py"; source.write_text("def evaluate(context):\n import time; time.sleep(.15); return True\n")
        self.s.add("custom",{"file":str(source),"queries":{},"effects":["cancel"]})
        self.s.poll(); self.s.remove("custom"); time.sleep(.22); self.assertFalse(self.k.fires)
        self.s.add("reload",{"file":str(source),"queries":{}}); old=self.s.watches["reload"].callable; source.write_text("broken(")
        with self.assertRaises(Exception): self.s.reload("reload")
        self.assertIs(old,self.s.watches["reload"].callable)
    def test_read_is_checked_and_runner_wakes(self):
        source=Path(self.tmp.name)/"bad.py"; source.write_text("def evaluate(context):\n return context.read('act.move')\n")
        self.s.add("bad",{"file":str(source),"queries":{}}); self.s.poll(); time.sleep(.05)
        self.assertIn("fault",[x["kind"] for x in self.s.events(0)["events"]])
        async def case():
            async def slow(): await asyncio.sleep(1)
            self.s._event("triggered","x")
            return await race_interrupt(self.s,slow())
        self.assertTrue(asyncio.run(case())["interrupted"])
    def test_scheduler_stall_replace_and_reopen(self):
        # The scheduler observes independently of tools/model calls.
        self.s.close(); self.s=InterruptSupervisor(self.k,self.tmp.name,retained=3,poll_s=.01,fire_backoff_s=.001)
        self.s.add("auto",{"queries":{"x":{"method":"obs.x"}},"condition":{"gte":["x.n",0]}})
        time.sleep(.05); self.wait_fires(1)
        slow=Path(self.tmp.name)/"slow.py"; slow.write_text("def evaluate(context):\n import time; time.sleep(.2); return True\n")
        self.s.add("slow",{"file":str(slow),"queries":{},"timeout_s":.02})
        time.sleep(.07); self.assertFalse(self.s.status("slow")["armed"]); time.sleep(.2)
        self.assertEqual(1,len(self.k.fires)) # late worker was discarded
        # Replacing an executing watch also invalidates its generation.
        self.s.add("same",{"file":str(slow),"queries":{},"timeout_s":1}); time.sleep(.02)
        self.s.add("same",{"queries":{"x":{"method":"obs.x"}},"condition":{"gt":["x.n",99]}},replace=True)
        time.sleep(.25); self.assertEqual(1,len(self.k.fires))
        cursor=self.s.events(0)["cursor"]; self.s.close()
        self.s=InterruptSupervisor(self.k,self.tmp.name,retained=3,poll_s=10,fire_backoff_s=.001)
        replay=self.s.events(max(0,cursor-2)); self.assertTrue(replay["events"])
    def test_validation_edge_cooldown_and_missing_is_fault(self):
        bad=[{"wat":"x"},{"all":{"path":"x","where":{"eq":["$",1,2]}}},{"any_of":{"path":3,"where":{"eq":["$",1]}}}]
        for c in bad:
            with self.assertRaises(ValueError): self.s.add("bad"+str(bad.index(c)),{"condition":c})
        self.s.add("edge",{"queries":{"x":{"method":"obs.x"}},"condition":{"gt":["x.n",0]},"oneShot":False,"edge":True,"cooldown":.05})
        self.k.value=1; self.s.poll(); self.s.poll(); self.wait_fires(1)
        self.k.value=0; self.s.poll(); self.k.value=1; self.s.poll(); time.sleep(.02); self.assertEqual(1,len(self.k.fires)); time.sleep(.06); self.k.value=0; self.s.poll(); self.k.value=1; self.s.poll(); self.wait_fires(2)
        self.k.errors={}; old=self.k.call
        def missing(method,**kw):
            result=old(method,**kw)
            if method=="obs.batch": result["values"]={}
            return result
        self.k.call=missing; self.s.add("missing",{"queries":{"x":{"method":"obs.x"}},"condition":{"exists":"x.n"}}); self.s.poll()
        self.assertFalse(any(f["reason"]=="missing" for f in self.k.fires)); self.assertIn("fault",[e["kind"] for e in self.s.events(0)["events"]])
        self.assertFalse(self.s.status("missing")["armed"])
    def test_method_array_context_mismatch_and_remove_read_race(self):
        self.k.methods=[{"name":"obs.x","effect":"read","watchable":True}]
        self.s.add("array",{"queries":{"x":{"method":"obs.x"}},"condition":{"gte":["x.n",0]}}); self.s.poll()
        self.wait_fires(1)
        self.k.context["dimension"]=1
        self.s.add("context",{"queries":{"x":{"method":"obs.x"}},"condition":{"gte":["x.n",0]}}); old=self.k.call
        def wrong_context(method,**kw):
            out=old(method,**kw)
            if method=="interrupt.status": out["context"]["dimension"]=99
            return out
        self.k.call=wrong_context; self.s.poll(); self.assertFalse(self.s.status("context")["armed"])
        self.k.call=old; entered=threading.Event(); release=threading.Event()
        def delayed(method,**kw):
            if method=="obs.batch": entered.set(); release.wait(1)
            return old(method,**kw)
        self.k.call=delayed; self.s.add("race",{"queries":{"x":{"method":"obs.x"}},"condition":{"gte":["x.n",0]}})
        t=threading.Thread(target=self.s.poll); t.start(); entered.wait(1); self.s.remove("race"); release.set(); t.join(1)
        self.assertFalse(any(f["reason"]=="race" for f in self.k.fires))
    def test_successful_reload_invalidates_old_worker_state(self):
        source=Path(self.tmp.name)/"reload_ok.py"; source.write_text("def evaluate(context):\n import time; time.sleep(.12); context.state['old']=1; return True\n")
        self.s.add("reload",{"file":str(source),"queries":{}}); self.s.poll(); time.sleep(.02)
        source.write_text("def evaluate(context):\n return False\n")
        self.s.reload("reload"); time.sleep(.16)
        self.assertNotIn("old",self.s.status("reload")["state"]); self.assertFalse(self.k.fires)
    def test_edge_after_debounce_fires_once(self):
        self.s.add("debounced",{"queries":{"x":{"method":"obs.x"}},"condition":{"gt":["x.n",0]},"consecutive":2,"edge":True,"oneShot":False})
        self.k.value=1; self.s.poll(); self.s.poll(); self.wait_fires(1)
        self.s.poll(); time.sleep(.02); self.assertEqual(1,len(self.k.fires))
    def test_limits_copy_cache_and_missing_current_fault(self):
        with self.assertRaises(ValueError): self.s.add("too_many",{"queries":{str(i):{"method":"obs.x"} for i in range(17)},"condition":{"exists":"x"}})
        spec={"queries":{"x":{"method":"obs.x","params":{"n":1}}},"condition":{"exists":"x.n"}}
        self.s.add("copy",spec); spec["queries"]["x"]["params"]["n"]=2
        self.assertEqual(1,self.s.watches["copy"].spec["queries"]["x"]["params"]["n"])
        calls=[]; old=self.k.call
        def counted(method,**kw): calls.append(method); return old(method,**kw)
        self.k.call=counted; self.s.poll(); self.s.poll()
        self.assertEqual(1,calls.count("sys.methods"))
        self.s.remove("copy"); before=len(calls); self.s.poll(); self.assertEqual(before,len(calls))
        self.s.add("current",{"queries":{"x":{"method":"obs.x"}},"condition":{"changed":"x.absent"}}); self.s.poll()
        self.assertFalse(self.s.status("current")["armed"])
    def test_repeating_watch_bounds_pending_reactions(self):
        entered=threading.Event();release=threading.Event();old=self.k.call;attempts=[]
        def slow(method,**kw):
            if method=="interrupt.fire": attempts.append(kw);entered.set();release.wait(1)
            return old(method,**kw)
        self.k.call=slow
        self.s.add("bounded",{"queries":{"x":{"method":"obs.x"}},"condition":{"gte":["x.n",0]},"oneShot":False})
        self.s.poll();self.assertTrue(entered.wait(1))
        for _ in range(10):self.s.poll()
        self.assertEqual(1,len(attempts));release.set();self.wait_fires(1)

    def test_fire_retries_same_event_id_then_rearms_until_delivered(self):
        old=self.k.call; failures=[]
        def flaky(method,**kw):
            if method=="interrupt.fire" and len(failures)<2: failures.append(kw["eventId"]); raise ConnectionError("socket closed")
            return old(method,**kw)
        self.k.call=flaky
        self.s.add("retry",{"queries":{"x":{"method":"obs.x"}},"condition":{"gte":["x.n",0]}}); self.s.poll(); self.wait_fires(1)
        self.assertEqual(set(failures),{self.k.fires[0]["eventId"]}); self.wait_kind(self.s,"triggered")  # retried with the same eventId
        triggered=[e for e in self.s.events(0)["events"] if e["kind"]=="triggered"]
        self.assertEqual(3,triggered[0]["data"]["attempts"]); self.assertIsNone(self.s.status("retry")["pendingEvent"])
        self.assertFalse(self.s.status("retry")["armed"])
        # All retries exhausted: the watch re-arms with the undelivered eventId and the next poll re-sends exactly it.
        self.k.fires.clear(); down=[True]
        def dead(method,**kw):
            if method=="interrupt.fire" and down[0]: raise TimeoutError("lost")
            return old(method,**kw)
        self.k.call=dead
        self.s.add("rearm",{"queries":{"x":{"method":"obs.x"}},"condition":{"gte":["x.n",0]},"reason":"kept"}); self.s.poll()
        end=time.monotonic()+1
        while self.s.status("rearm")["pendingEvent"] is None and time.monotonic()<end: time.sleep(.005)
        pending=self.s.status("rearm")["pendingEvent"]; self.assertTrue(pending and self.s.status("rearm")["armed"])
        self.assertEqual({"rearm"},set(self.s.specs())); self.assertFalse(self.k.fires)
        down[0]=False; self.s.poll(); self.wait_fires(1)
        self.assertEqual(pending,self.k.fires[0]["eventId"]); self.assertEqual("kept",self.k.fires[0]["reason"])
        self.assertIsNone(self.s.status("rearm")["pendingEvent"]); self.assertFalse(self.s.status("rearm")["armed"])
        with self.assertRaises(ValueError): InterruptSupervisor(self.k,self.tmp.name,fire_retries=0)

    def test_transport_outage_keeps_watches_and_follows_kernel_factory(self):
        self.s.close(); live={"k":self.k}
        self.s=InterruptSupervisor(lambda: live["k"],self.tmp.name,retained=20,poll_s=10,fire_backoff_s=.001)
        self.s.add("keep",{"queries":{"x":{"method":"obs.x"}},"condition":{"gte":["x.n",0]}})
        class Down:
            def call(self,method,**kw): raise ConnectionError("bridge gone")
        live["k"]=Down(); self.s.poll(); self.s.poll()
        self.assertTrue(self.s.status("keep")["armed"]); self.assertIn("bridge gone",self.s.status()["transport"])
        self.assertEqual(["armed","transport"],[e["kind"] for e in self.s.events(0)["events"]]); self.assertFalse(self.k.fires)
        # A replacement kernel (game restarted) is picked up through the factory; backoff is skipped here for speed.
        fresh=FakeKernel(); fresh.methods={"obs.x":{"effect":"read","watchable":True}}; live["k"]=fresh; self.s._retry_at=0
        self.s.poll(); end=time.monotonic()+.5
        while len(fresh.fires)<1 and time.monotonic()<end: time.sleep(.005)
        self.assertEqual(1,len(fresh.fires)); self.assertIsNone(self.s.status()["transport"])
        self.assertEqual("reconnected",[e for e in self.s.events(0)["events"] if e["kind"]=="transport"][-1]["data"]["state"])

    def test_get_supervisor_survives_module_reload_with_watches(self):
        self.s.close(); self.k.close=lambda: None; self.k.connected=True
        mbtool.drop_kernel(); mbtool.state["kernel"]=self.k
        try:
            sup=get_supervisor(self.tmp.name); self.assertIs(sup,get_supervisor(self.tmp.name))
            sup.add("kept",{"queries":{"x":{"method":"obs.x"}},"condition":{"gt":["x.n",5]},"oneShot":False})
            sup.add("gone",{"queries":{"x":{"method":"obs.x"}},"condition":{"gte":["x.n",0]}}); sup.poll(); self.wait_fires(1); self.wait_kind(sup,"triggered")
            mbtool.install_package(); mbtool.evict_package(); reloaded=importlib.import_module("mbtools_gtnh.interrupts")
            fresh=reloaded.get_supervisor(self.tmp.name)
            self.assertIsNot(fresh,sup); self.assertTrue(sup._closed); self.assertIs(mbtool.state["interrupts"],fresh)
            self.assertEqual({"kept"},set(fresh.specs())); self.assertEqual(fresh.watches["kept"].spec["condition"],{"gt":["x.n",5]})
            self.k.value=6; fresh.poll(); self.wait_fires(2); self.assertEqual("kept",self.k.fires[1]["reason"])
            self.assertIs(fresh.kernel,self.k)
        finally:
            close_supervisor(); mbtool.state.pop("kernel",None)
        self.assertNotIn("interrupts",mbtool.state); self.s=InterruptSupervisor(self.k,self.tmp.name,retained=3,poll_s=10)

    def test_fire_does_not_hold_lock_and_race_cancellation_cleans_model(self):
        entered=threading.Event(); release=threading.Event(); old=self.k.call
        def slow_fire(method,**kw):
            if method=="interrupt.fire": entered.set(); release.wait(1)
            return old(method,**kw)
        self.k.call=slow_fire; self.s.add("send",{"queries":{"x":{"method":"obs.x"}},"condition":{"gte":["x.n",0]}}); self.s.poll(); entered.wait(1)
        start=time.monotonic(); self.s.remove("send"); self.assertLess(time.monotonic()-start,.5); release.set()  # the fire is held for 1 s; a journal write alone can take 100 ms
        cancelled=[]
        async def case():
            async def blocked():
                try: await asyncio.sleep(1)
                finally: cancelled.append(True)
            task=asyncio.create_task(blocked()); await asyncio.sleep(0)
            self.s._event("triggered","wake")
            return await race_interrupt(self.s,task)
        self.assertTrue(asyncio.run(case())["interrupted"]); self.assertTrue(cancelled)

    def test_watches_persist_across_supervisor_restart(self):
        old=self.k.call; down=[True]
        def dead(method,**kw):
            if method=="interrupt.fire" and down[0]: raise TimeoutError("lost")
            return old(method,**kw)
        self.k.call=dead; q={"x":{"method":"obs.x"}}
        self.s.add("kept",{"queries":q,"condition":{"gt":["x.n",5]},"oneShot":False})
        self.s.add("gone",{"queries":q,"condition":{"gt":["x.n",5]}}); self.s.remove("gone")
        self.s.add("bad",{"queries":q,"condition":{"changed":"x.absent"}})
        self.s.add("undelivered",{"queries":q,"condition":{"gte":["x.n",0]}}); self.s.poll()
        end=time.monotonic()+2
        while self.s.status("undelivered")["pendingEvent"] is None and time.monotonic()<end: time.sleep(.005)
        pending=self.s.status("undelivered")["pendingEvent"]; self.assertTrue(pending); self.assertFalse(self.s.status("bad")["armed"])
        self.s.close(); self.s=InterruptSupervisor(self.k,self.tmp.name,retained=50,poll_s=10,fire_backoff_s=.001)
        self.assertEqual([],self.s.list()); self.s.restore()  # only get_supervisor restores; the runner arms its own specs
        self.assertEqual({"kept","undelivered"},{w["name"] for w in self.s.list() if w["armed"]}); self.assertEqual(2,len(self.s.list()))
        self.assertEqual(pending,self.s.status("undelivered")["pendingEvent"])
        down[0]=False; self.s.poll(); self.wait_fires(1); self.assertEqual(pending,self.k.fires[0]["eventId"])
        self.wait_kind(self.s,"triggered"); self.s.close(); self.s=InterruptSupervisor(self.k,self.tmp.name,poll_s=10); self.s.restore()
        self.assertEqual({"kept"},set(self.s.specs()))  # a delivered one-shot stays disarmed

    def test_mb_wait_wakes_skips_noise_times_out_and_validates(self):
        self.s.close(); self.k.close=lambda: None; self.k.connected=True
        mbtool.drop_kernel(); mbtool.state["kernel"]=self.k
        try:
            sup=get_supervisor(self.tmp.name)
            for bad in ({"after":-1},{"timeout_s":.5},{"timeout_s":901}):
                with self.assertRaises(ValueError): mb_wait(**bad)
            sup._event("armed","noise"); sup._event("transport",state="down")
            got=mb_wait(0,1)
            self.assertEqual((False,[],False,sup.events(0)["cursor"]),(got["woke"],got["events"],got["gap"],got["cursor"])); self.assertGreater(got["cursor"],0)
            timer=threading.Timer(.3,lambda: (sup._event("disarmed","noise",reason="oneShot"),sup._event("triggered","w",eventId="e1",payload={"modelPrompt":"look"})))
            start=time.monotonic(); timer.start(); woke=mb_wait(got["cursor"],60); timer.join()
            self.assertTrue(woke["woke"]); self.assertLess(time.monotonic()-start,30)
            self.assertEqual(["triggered"],[e["kind"] for e in woke["events"]]); self.assertEqual("look",woke["events"][0]["data"]["payload"]["modelPrompt"])
            self.assertEqual(got["cursor"]+2,woke["cursor"])
        finally:
            close_supervisor(); mbtool.state.pop("kernel",None)
        self.s=InterruptSupervisor(self.k,self.tmp.name,retained=3,poll_s=10)

if __name__ == "__main__": unittest.main()
