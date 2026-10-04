# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Offline coverage for the reloadable GTNH tool set: tool table, lanes, reload atomicity, wrappers."""
from __future__ import annotations

import base64
import inspect
import asyncio
import json
import os
from pathlib import Path
import sys
import shutil
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch


MCP = Path(__file__).resolve().parents[1] / "mcp"
sys.path[:0] = [str(MCP)]
import mbtool
import server
from kernel import bridge_url
from mcp.types import ImageContent

TOOLS = {
    "mb_interrupt", "mb_interrupt_events", "mb_wait", "mb_wiki_search", "mb_wiki_read", "mb_goal", "mb_move_items", "mb_hold", "mb_craft", "mb_run",
    "mb_act", "mb_call", "mb_cost", "mb_gui", "mb_keys", "mb_map", "mb_methods", "mb_obs", "mb_screenshot", "mb_status", "mb_stop", "mb_time",
    "mb_recipe_status", "mb_item_search", "mb_item_info", "mb_recipes", "mb_fluid_search", "mb_recipe_handlers", "mb_recipe_view", "mb_recipe_inspect",
    "mb_memory", "mb_route", "mb_inventory", "mb_find", "mb_transfer", "mb_click_slot", "mb_notes", "mb_note_write",
    "mb_follow", "mb_fight", "mb_view", "mb_process", "mb_settings", "mb_cache",
    "mb_mine", "mb_build_preview", "mb_build", "mb_copy",
    "mb_schematic_import", "mb_schematic_build", "mb_scan", "mb_work_status",
    "mb_work_resume", "mb_build_pause", "mb_build_materials", "mb_quest_status", "mb_quest_sync", "mb_quest_search", "mb_quest_lines",
    "mb_quest_observe", "mb_quest_detect", "mb_quest_select_choice", "mb_quest_claim", "mb_task",
}


class FakeKernel:
    """Stands in for the live kernel in mbtool.state; every tool module resolves kernel() to it."""
    connected = True

    def __init__(self, reply=None):
        self.calls = []
        self.reply = reply or (lambda method, params: {"method": method})

    def call(self, method, **params):
        self.calls.append((method, params))
        return self.reply(method, params)

    def close(self):
        pass

    def last(self, method):
        return next(c for c in reversed(self.calls) if c[0] == method)


def module_with(srv, attr):
    return next(tm.module for tm in srv.modules.values() if hasattr(tm.module, attr))


class GTNHProfileTests(unittest.TestCase):
    def setUp(self):
        mbtool.state.pop("notes", None)
        tasks = tempfile.TemporaryDirectory(); self.addCleanup(tasks.cleanup)  # never the live run's background tasks
        env = patch.dict(os.environ, {"MB_TASKS_DIR": tasks.name}); env.start(); self.addCleanup(env.stop)
        self.srv = server.Server()
        self.addCleanup(self.srv.close)
        self.addCleanup(mbtool.install_package)   # tests below re-point the package at temp dirs

    def loaded(self):
        self.assertFalse(any(m.startswith("ERROR") for m in self.srv.check_reload(force=True)), self.srv.error)
        return self.srv

    def use(self, fake):
        ctx = patch.dict(mbtool.state, {"kernel": fake})
        ctx.start()
        self.addCleanup(ctx.stop)
        return fake

    def test_composition_partial_receipts_survive_mcp_error_wrapping(self):
        srv = self.loaded()
        from mbtools_gtnh.inventory import ProcedureStopped
        from kernel import BridgeError
        def failed() -> dict:
            try:
                raise BridgeError("ack_timeout", "inspect", "gui.click_slot", {"error":{"receipt":{"state":"failed"}}})
            except BridgeError as error:
                raise ProcedureStopped("partial procedure", [{"state":"completed","transfer":{"moved":2}}]) from error
        srv.add_tool(failed, name="mb_partial_procedure")
        result = asyncio.run(srv.call_tool("mb_partial_procedure", {}))
        self.assertTrue(result.isError)
        error = result.structuredContent["error"]
        self.assertEqual(error["code"], "ack_timeout")
        self.assertEqual(error["procedureReceipts"][0]["transfer"]["moved"], 2)
        self.assertEqual(error["reply"]["error"]["receipt"]["state"], "failed")

    def test_an_argument_the_tool_does_not_take_is_refused_not_dropped(self):
        srv = self.loaded()
        ran = []
        def clock(method: str = "status") -> dict:
            """reads or sets the clock"""
            ran.append(method); return {"did": method}
        srv.add_tool(clock, name="mb_clock_probe")
        result = asyncio.run(srv.call_tool("mb_clock_probe", {"action": "pause"}))  # would otherwise run as status
        self.assertTrue(result.isError)
        self.assertEqual(result.structuredContent["error"]["code"], "bad_request")
        self.assertIn("action", result.structuredContent["error"]["msg"]); self.assertIn("method", result.structuredContent["error"]["msg"])
        self.assertEqual(ran, [])
        self.assertFalse(asyncio.run(srv.call_tool("mb_clock_probe", {"method": "pause"})).isError); self.assertEqual(ran, ["pause"])

    def test_modified_input_primes_pose_and_preserves_click_parameters(self):
        core = module_with(self.loaded(), "mb_act")
        fake = self.use(FakeKernel(lambda method, params: {"completed": True}))
        params = {"keys": ["sneak", "attack", "forward"], "ticks": 1, "overrideProtection": True}
        out = core.mb_act("input", params)
        self.assertEqual(fake.calls, [
            ("act.input", {"timeout": 60.0, "keys": ["sneak"], "ticks": 15, "overrideProtection": True}),
            ("act.input", {"timeout": 60.0, **params}),
        ])
        self.assertTrue(out["posePrelude"]["completed"])
        self.assertNotIn("poseTicks", params)
        fake.calls.clear()
        core.mb_act("input", {"keys": ["sneak", "use"], "poseTicks": 0, "ticks": 1})
        self.assertEqual(len(fake.calls), 1)
        self.assertNotIn("poseTicks", fake.calls[0][1])

    def test_modified_input_does_not_click_after_interrupted_pose_hold(self):
        core = module_with(self.loaded(), "mb_act")
        fake = self.use(FakeKernel(lambda method, params: {"completed": False, "outcome": "guard_pause"}))
        out = core.mb_act("act.input", {"keys": ["sneak", "use"], "ticks": 1})
        self.assertFalse(out["actionSent"])
        self.assertEqual(len(fake.calls), 1)
        fake.calls.clear()
        with self.assertRaises(ValueError):
            core.mb_act("input", {"keys": ["sneak", "attack"], "poseTicks": -1})
        self.assertEqual(fake.calls, [])

    def test_attack_target_goes_to_the_attack_call_for_the_client_to_check(self):
        core = module_with(self.loaded(), "mb_act")
        fake = self.use(FakeKernel(lambda method, params: {"completed": True}))
        params = {"keys": ["sneak", "attack"], "ticks": 20, "attackTarget": [4, 5, 6]}
        core.mb_act("input", params)
        self.assertEqual([m for m, p in fake.calls], ["act.input", "act.input"])
        self.assertNotIn("attackTarget", fake.calls[0][1])
        self.assertEqual(fake.calls[1][1]["attackTarget"], [4, 5, 6])
        fake.calls.clear()
        for bad in ({"keys": ["use"]}, {"keys": ["attack"], "allowRetarget": True},
                    {"keys": ["attack"], "attackTarget": [4, True, 6]}, {"keys": ["attack"], "attackTarget": [4, 5]}):
            with self.assertRaises(ValueError):
                core.mb_act("input", {"attackTarget": [4, 5, 6], **bad})
        self.assertEqual(fake.calls, [])

    def test_tool_set_lanes_and_metadata(self):
        srv = self.loaded()
        self.assertEqual(set(srv.name_owner), TOOLS)
        self.assertEqual(len(srv.modules), 10)
        self.assertEqual({os.path.basename(p) for p in srv.modules},
                         {"core.py", "inventory.py", "work.py", "recipes_quests.py", "interrupts.py", "notes.py", "wiki.py", "scripts.py", "plan.py", "tasks.py"})
        for name in ("mb_selection", "mb_selection_build", "mb_coverage", "mb_load_inputs"):
            self.assertNotIn(name, srv.name_owner)
        lanes = {n: tm.tools[n]["lane"] for tm in srv.modules.values() for n in tm.tools}
        self.assertEqual({lanes[n] for n in ("mb_obs", "mb_inventory", "mb_notes", "mb_item_search", "mb_status", "mb_build_preview")}, {"read"})
        self.assertEqual({lanes[n] for n in ("mb_stop", "mb_build_pause", "mb_interrupt")}, {"control"})
        self.assertEqual({lanes[n] for n in ("mb_build", "mb_mine", "mb_route", "mb_transfer", "mb_note_write")}, {"act"})
        self.assertTrue(all(callable(lanes[n]) for n in ("mb_call", "mb_time", "mb_gui", "mb_copy", "mb_settings")))
        registered = srv._tool_manager._tools["mb_obs"]
        self.assertEqual(registered.meta["moddedbench"]["lane"], "read")
        self.assertTrue(registered.annotations.readOnlyHint)
        self.assertFalse(srv._tool_manager._tools["mb_build"].annotations.readOnlyHint)
        status = srv._tool_manager._tools["mb_tools_status"].fn()
        self.assertEqual(sum(len(m["tools"]) for m in status["modules"]), 64)
        json.dumps(status)

    def test_worker_picks_pool_from_lane_metadata(self):
        srv = self.loaded()
        chosen = []
        class Recording(ThreadPoolExecutor):
            def __init__(self, lane): super().__init__(max_workers=1); self.lane = lane
            def submit(self, fn, *a, **kw): chosen.append(self.lane); return super().submit(fn, *a, **kw)
        for pool in srv._pools.values(): pool.shutdown(wait=False)
        srv._pools = {lane: Recording(lane) for lane in mbtool.LANES}
        self.use(FakeKernel(lambda method, params: {"plan": {"origin": [0,0,0], "cells": []}} if method == "nav.copy" else {"method": method}))
        mbtool.state["methods"] = {"obs.thing": {"effect": "read"}, "act.move": {"effect": "interaction"}}
        for name, args in (("mb_stop", {}), ("mb_inventory", {}), ("mb_build", {"cells": [{"pos": [0,0,0], "id": "a:b"}]}),
                           ("mb_time", {"method": "status"}), ("mb_time", {"method": "pause"}),
                           ("mb_call", {"method": "obs.thing"}), ("mb_call", {"method": "act.move"}), ("mb_call", {"method": "act.stop"}),
                           ("mb_copy", {"bounds": {"min": [0,0,0], "max": [1,1,1]}}), ("mb_copy", {"bounds": {"min": [0,0,0], "max": [1,1,1]}, "build": True})):
            result = asyncio.run(srv.call_tool(name, args))
            self.assertFalse(result.isError, (name, result.content))
        self.assertEqual(chosen, ["control", "read", "act", "read", "control", "read", "act", "control", "read", "act"])

    def test_a_background_task_refuses_game_calls_that_move_the_body_and_lets_the_rest_through(self):
        srv = self.loaded()
        import sys, time
        tasks = sys.modules[mbtool.PACKAGE + ".tasks"]
        listed = [{"name": "obs.inventory", "effect": "read"}, {"name": "memory.context", "effect": "read"},
                  {"name": "act.input", "effect": "interaction"}]
        notes_dir = tempfile.mkdtemp(); self.addCleanup(shutil.rmtree, notes_dir, True)
        self.enterContext(patch.dict(os.environ, {"MODBENCH_NOTES_DIR": notes_dir}))
        k = FakeKernel(lambda method, params: listed if method == "sys.methods" else {"method": method, "worldId": "00000000-0000-4000-8000-000000000001"})
        k.call = lambda method, **params: (tasks.gate(k, method), FakeKernel.call(k, method, **params))[1]  # as Kernel.body_gate does
        self.use(k)
        lock = tasks._lock(tasks.TASKS); self.addCleanup(lock.close)  # held, as the task process holds it while it runs
        tasks.save({"task": "t1", "name": "vein", "started": time.time(), "state": "running", "pid": os.getpid(), "now": "mb_mine"})
        refused = asyncio.run(srv.call_tool("mb_act", {"method": "input", "params": {"keys": ["forward"], "ticks": 1}}))
        self.assertTrue(refused.isError)
        self.assertEqual(refused.structuredContent["error"]["code"], "body_busy")
        self.assertIn("mb_task(cancel=True)", refused.structuredContent["error"]["msg"])
        read = asyncio.run(srv.call_tool("mb_inventory", {}))
        self.assertFalse(read.isError)
        fields = json.loads(read.content[0].text)
        self.assertEqual((fields["bodyBusy"], fields["body"]["task"]), (True, "t1"))
        stop = asyncio.run(srv.call_tool("mb_stop", {}))
        self.assertFalse(stop.isError); self.assertNotIn("bodyBusy", json.loads(stop.content[0].text))
        goal = asyncio.run(srv.call_tool("mb_goal", {"subgoal": "plan the coke ovens while the vein is mined"}))
        self.assertFalse(goal.isError, goal.content)  # no game call, so nothing to refuse
        tasks.save({"task": "t1", "name": "vein", "started": time.time(), "state": "done", "endedAt": time.time(), "result": 3})
        first, second = (json.loads(asyncio.run(srv.call_tool("mb_stop", {})).content[0].text) for _ in range(2))
        self.assertEqual([f["task"] for f in first["finished"]], ["t1"]); self.assertNotIn("finished", second)

    def test_task_fields_join_the_result_object_or_come_as_their_own_block(self):
        from mcp.types import CallToolResult, TextContent
        one = CallToolResult(content=[TextContent(type="text", text='{"a": 1}')], structuredContent={"a": 1})
        merged = server._with_fields(one, {"body": {"task": "t1"}})
        self.assertEqual((json.loads(merged.content[0].text), merged.structuredContent), ({"a": 1, "body": {"task": "t1"}},) * 2)
        plain = server._with_fields(CallToolResult(content=[TextContent(type="text", text="ok")]), {"bodyBusy": True})
        self.assertEqual([c.text for c in plain.content], ["ok", '{"bodyBusy": true}'])
        self.assertIs(server._with_fields(one, {}), one)

    def test_failed_reload_retains_last_good_tools(self):
        srv = self.srv
        with tempfile.TemporaryDirectory() as tmp:
            tool_path = Path(tmp) / "profile_tool.py"
            tool_path.write_text(
                'from mbtool import tool\n'
                '@tool(rung=0)\n'
                'def mb_profile_probe() -> str:\n'
                '    """A temporary profile tool."""\n'
                '    return "good"\n'
            )
            srv.tools_dir = tmp
            self.loaded()
            original = srv._tool_manager._tools["mb_profile_probe"]
            tool_path.write_text("this is not valid Python!\n")
            results = srv.check_reload(force=True)
            self.assertTrue(any(message.startswith("ERROR profile_tool.py") for message in results))
            self.assertIs(srv._tool_manager._tools["mb_profile_probe"], original)
            self.assertTrue(srv.modules[str(tool_path)].error)
            self.assertTrue(srv.error)
            self.assertEqual(srv.check_reload(), [])  # a broken file is not re-tried until it changes again
            self.assertEqual(sys.modules["mbtools_gtnh.profile_tool"].mb_profile_probe(), "good")  # restored module

    def test_reload_evicts_package_atomically_and_state_survives(self):
        srv = self.srv
        with tempfile.TemporaryDirectory() as tmp:
            a, b = Path(tmp) / "a.py", Path(tmp) / "nested" / "b.py"
            b.parent.mkdir()
            (b.parent / "_private.py").write_text("raise RuntimeError('underscore files are never imported')\n")
            a.write_text('from mbtool import tool, state\nVERSION = 1\nstate.setdefault("probe", object())\n'
                         '@tool(lane="read")\ndef mb_a() -> int:\n    """a"""\n    return VERSION\n')
            b.write_text('from mbtool import tool\nfrom mbtools_gtnh.a import VERSION\n'
                         '@tool()\ndef mb_b() -> int:\n    """b"""\n    return VERSION * 10\n')
            srv.tools_dir = tmp
            self.loaded()
            self.assertEqual(set(srv.name_owner), {"mb_a", "mb_b"})
            self.assertEqual(srv.modules[str(b)].modname, "mbtools_gtnh.nested.b")
            first_module, probe = sys.modules["mbtools_gtnh.a"], mbtool.state["probe"]
            self.assertEqual(sys.modules["mbtools_gtnh.nested.b"].mb_b(), 10)
            a.write_text(a.read_text().replace("VERSION = 1", "VERSION = 2"))
            os.utime(a, ns=(a.stat().st_atime_ns, a.stat().st_mtime_ns + 10**9))  # an edit seen as an edit: Linux stamps writes at a coarse tick, so one this quick can keep its mtime
            self.assertEqual(srv.check_reload(), ["loaded 2 modules: 2 tools"])
            self.assertIsNot(sys.modules["mbtools_gtnh.a"], first_module)
            self.assertEqual(sys.modules["mbtools_gtnh.nested.b"].mb_b(), 20)  # sibling re-imported against the new module
            self.assertIs(mbtool.state["probe"], probe)
            good_b = srv._tool_manager._tools["mb_b"]
            b.write_text('from mbtool import tool\n@tool(name="mb_a")\ndef steal() -> int:\n    """dup"""\n    return 0\n')
            message, = srv.check_reload()
            self.assertTrue(message.startswith("ERROR b.py") and "duplicate tool name: mb_a" in message, message)
            self.assertIs(srv._tool_manager._tools["mb_b"], good_b)
            self.assertEqual(sys.modules["mbtools_gtnh.nested.b"].mb_b(), 20)  # previous modules restored
            self.assertIs(mbtool.state["probe"], probe)
            b.unlink()
            self.assertEqual(srv.check_reload(), ["loaded 1 modules: 1 tools"])
            self.assertNotIn("mb_b", srv._tool_manager._tools)
        mbtool.state.pop("probe", None)

    def test_reload_executes_same_size_source_with_preserved_mtime(self):
        srv = self.srv
        with tempfile.TemporaryDirectory() as tmp:
            tool_path = Path(tmp) / "profile_tool.py"
            v1 = ('from mbtool import tool\n@tool(rung=0)\ndef mb_profile_probe() -> str:\n'
                  '    """A temporary profile tool."""\n    return "v1"\n')
            v2 = v1.replace('"v1"', '"v2"')
            self.assertEqual(len(v1), len(v2))
            tool_path.write_text(v1)
            original_mtime = os.stat(tool_path).st_mtime_ns
            srv.tools_dir = tmp
            self.loaded()
            self.assertEqual(srv.modules[str(tool_path)].module.mb_profile_probe(), "v1")
            tool_path.write_text(v2)
            os.utime(tool_path, ns=(os.stat(tool_path).st_atime_ns, original_mtime))
            self.loaded()
            self.assertEqual(srv.modules[str(tool_path)].module.mb_profile_probe(), "v2")

    def test_bridge_url_comes_from_one_environment_variable(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(bridge_url(), "ws://127.0.0.1:47223/ws")
            self.assertEqual(bridge_url("server"), "ws://127.0.0.1:47224/ws")
            default = server.Server()
        self.addCleanup(default.close)
        self.assertEqual(default.bridge_url, "ws://127.0.0.1:47223/ws")
        with patch.dict(os.environ, {"MB_BRIDGE_URL": "ws://example.test:49999/ws"}, clear=True):
            self.assertEqual(bridge_url("server"), "ws://example.test:50000/ws")
            overridden = server.Server()
        self.addCleanup(overridden.close)
        self.assertEqual(overridden.bridge_url, "ws://example.test:49999/ws")

    def test_generic_tools_use_advertised_namespaces_and_screenshot_payload(self):
        srv = self.loaded()
        core, inv, work, quests = (module_with(srv, a) for a in ("mb_status", "mb_gui", "mb_route", "mb_quest_observe"))
        fake = self.use(FakeKernel(lambda method, params: {"png": base64.b64encode(b"png").decode(), "width": 1, "height": 1}
                                   if method == "sys.screenshot" else {"method": method}))
        self.assertEqual(core.mb_methods()["method"], "sys.methods")
        self.assertEqual(core.mb_status()["method"], "sys.capabilities")
        self.assertEqual(core.mb_obs("player", {"detail": "full"})["method"], "obs.player")
        self.assertIn(("obs.player", {"detail": "full"}), fake.calls)
        self.assertEqual(core.mb_stop()["method"], "act.stop")
        self.assertEqual(core.mb_screenshot().data, b"png")
        with self.assertRaises(ValueError):
            inv.mb_gui("obs.player")
        core.mb_keys("list"); self.assertEqual(fake.calls[-1], ("obs.keys", {}))
        core.mb_keys("press", {"name": "key.inventory", "ticks": 2})
        self.assertEqual(fake.calls[-1], ("act.press_key", {"name": "key.inventory", "ticks": 2}))
        self.assertEqual(core.mb_keys("act.press_key", {"name": "k"})["method"], "act.press_key")
        with self.assertRaises(ValueError): core.mb_keys("act.stop")
        keys_lane = srv.modules[core.__file__].tools["mb_keys"]["lane"]
        self.assertEqual([keys_lane({"method": m}) for m in ("list", "press", "obs.keys")], ["read", "act", "read"])
        inv.mb_find({"id": "minecraft:paper"}, scope="container")
        self.assertEqual(fake.calls[-1], ("obs.find", {"selector": {"id": "minecraft:paper"}, "scope": "container"}))
        inv.mb_click_slot(7, 123, 41, None, {'id':'minecraft:paper'}, click_type='pickup')
        self.assertEqual(fake.calls[-1], ('gui.click_slot', dict(windowId=7, epoch=123, slot=41,
            expected=None, expectedCursor={'id':'minecraft:paper'}, type='pickup', button=0)))
        inv.mb_transfer(7,123,61,{'id':'minecraft:coal','count':8},[11],3,'consuming')
        self.assertEqual(fake.calls[-1][1]['destinationPolicy'],'consuming')
        self.assertIsNone(fake.calls[-1][1]['expectedCursor'])
        self.assertEqual(core.mb_time("pause", timeout_s=120)["method"], "time.pause")
        self.assertEqual(fake.calls[-1], ("time.pause", {"timeout": 120}))
        with self.assertRaises(ValueError):
            core.mb_time("dev.time_fixture.hurt")
        core.mb_time("step", {"ticks": 1200})
        self.assertEqual(fake.calls[-1], ("time.step", {"ticks": 1200, "timeout": 150}))  # the reply waits out the step
        core.mb_memory("protect", {"name": "base", "min": [1,2,3], "max": [4,5,6]})
        self.assertEqual(fake.calls[-1], ("memory.protect", {"name": "base", "min": [1,2,3], "max": [4,5,6]}))
        with self.assertRaises(ValueError):
            core.mb_memory("dev.fluid_fixture.restore")
        # One timeout, in game ticks: the wall-clock wait is derived from it (that budget at two thirds speed, plus 30 s).
        work.mb_route("base to cave", reverse=True, start_index=2, timeout_ticks=16000)
        self.assertEqual(fake.last("nav.route"), ("nav.route", {"name": "base to cave", "reverse": True,
            "startIndex": 2, "allowBreak": False, "allowPlace": False, "overrideProtection": False,
            "timeoutTicks": 16000, "timeout": 1230.0}))
        work.mb_route("approved work", allow_break=True, override_protection=True)
        self.assertTrue(fake.last("nav.route")[1]["overrideProtection"])
        work.mb_route("next journey")
        self.assertFalse(fake.last("nav.route")[1]["overrideProtection"])
        work.mb_mine([{"id":"ore:block"}], [{"id":"ore:item"}], quantity=8,
                     bounds={"min":[0,1,0],"max":[3,4,3]})
        self.assertEqual(fake.last("nav.mine"), ("nav.mine", {
            "blocks":[{"id":"ore:block"}], "items":[{"id":"ore:item"}], "quantity":8,
            "radius":24, "allowBreak":False, "allowPlace":False,
            "overrideProtection":False, "timeoutTicks":12000,
            "bounds":{"min":[0,1,0],"max":[3,4,3]}, "timeout":930.0}))
        cells=[{"pos":[0,0,0],"id":"minecraft:stone","meta":0}]
        work.mb_build_preview(cells=cells, origin=[10,70,10])
        self.assertEqual(fake.calls[-1][0], "nav.build_preview")
        work.mb_build(cells=cells, timeout_ticks=500)
        self.assertEqual(fake.last("nav.build"), ("nav.build", {"replaceExisting":False,
            "overrideProtection":False,"allowBreak":False,"allowPlace":False,
            "timeoutTicks":500,"cells":cells,"timeout":67.5}))
        # One mb_scan covers a volume above the bridge's per-scan cap: layers of <=262144 cells, each paged to its end.
        def scan(method, params):
            volume = 1
            for a, b in zip(params["bounds"]["min"], params["bounds"]["max"]): volume *= b - a + 1
            end = min(params["cursor"] + params["budget"], volume)
            return {"matches": [{"at": end}] if end == volume else [], "cursor": end, "done": end == volume, "scanned": end - params["cursor"], "unloaded": 0}
        scanning = self.use(FakeKernel(scan))
        found = work.mb_scan([{"ore":"oreIron"}], {"min":[0,0,0],"max":[63,199,63]}, limit=9, detail="full")
        self.assertEqual((found["done"], len(found["matches"]), found["scanned"], found["volume"]), (True, 4, 819200, 819200))
        self.assertTrue(all(c[1]["bounds"]["max"][1] - c[1]["bounds"]["min"][1] + 1 <= 64 for c in scanning.calls))
        part = work.mb_scan(None, {"min":[0,0,0],"max":[63,199,63]}, limit=1, detail="full")
        self.assertEqual((part["done"], part["cursor"]), (False, [1, 0]))
        with self.assertRaisesRegex(ValueError, "512x512"): work.mb_scan(None, {"min":[0,0,0],"max":[600,1,600]})
        # A block the client cannot name yet (buried GregTech ore) is reported as not revealed, not as a lang key.
        ores = [{"pos": [0, 0, 0], "id": "gregtech:gt.blockores", "meta": 0, "pickedItem": {"name": "gt.blockores.0.name"}},
                {"pos": [1, 0, 0], "id": "gregtech:gt.blockores", "meta": 0, "pickedItem": {"name": "Pyrite Ore"}}]
        self.use(FakeKernel(lambda method, params: {"pos": [0, 0, 0]} if method == "obs.player" else
                            {"matches": ores, "cursor": 2, "done": True, "scanned": 2, "unloaded": 0}))
        rows = work.mb_scan(None, {"min": [0, 0, 0], "max": [1, 0, 0]}, detail="rows")
        self.assertEqual([r["name"] for r in rows["matches"]], [None, "Pyrite Ore"])
        self.assertEqual(rows["notRevealed"]["count"], 1)
        self.assertEqual(sorted(k["name"] or "" for k in work.mb_scan(None, {"min": [0, 0, 0], "max": [1, 0, 0]})["kinds"]), ["", "Pyrite Ore"])
        self.use(fake)
        work.mb_work_status("job-7")
        self.assertEqual(fake.calls[-1], ("nav.work_status", {"jobId":"job-7"}))
        work.mb_work_resume("job-7", {"timeoutTicks":400,"overrideProtection":True})
        self.assertEqual(fake.last("nav.resume"), ("nav.resume", {"timeout":510.0,"jobId":"job-7",
            "timeoutTicks":400,"overrideProtection":True}))
        work.mb_work_resume("job-7")  # the job keeps its own budget, unknown here: the wait covers the longest one
        self.assertEqual(fake.last("nav.resume")[1]["timeout"], 5880.0)  # and a build's closing
        for tool in (work.mb_route, work.mb_mine, work.mb_build, work.mb_copy, work.mb_schematic_build, work.mb_work_resume):
            self.assertNotIn("timeout_s", inspect.signature(tool).parameters)
        quests.mb_quest_observe("00000000-0000-0000-0000-000000000001")
        self.assertEqual(fake.calls[-1][0], "quest.observe")
        quests.mb_quest_claim("00000000-0000-0000-0000-000000000001", [2], {"2":1}, wait_s=0)
        self.assertEqual(fake.last("quest.claim")[1]["choices"], {"2":1})
        stock = [[{"identity": {"id": "minecraft:apple", "meta": 0, "name": "Apple"}, "count": 2}]]
        def claiming(method, params):
            if method == "quest.claim": stock.append([{"identity": {"id": "minecraft:apple", "meta": 0, "name": "Apple"}, "count": 5}, {"identity": {"id": "bq:lootchest", "meta": 1, "name": "Loot Chest"}, "count": 1}])
            if method == "obs.inventory": return {"totals": stock[-1]}
            if method == "quest.observe": return {"claimed": True}
            return {"method": method}
        self.use(FakeKernel(claiming))
        claimed = quests.mb_quest_claim("00000000-0000-0000-0000-000000000001", [0])
        self.assertEqual((claimed["claimed"], claimed["received"]), (True, {"Apple": 3, "Loot Chest": 1}))
        fake = self.use(FakeKernel(lambda method, params: {"method": method, **params}))
        quest = {"complete": True, "canClaim": True, "tasks": [{"id": 0, "name": "Tick", "type": "bq_standard:checkbox", "complete": True, "config": "{}"}]}
        detected = False
        def detecting(method, params):
            nonlocal detected
            if method == "quest.detect":
                if not params["taskIds"]: raise ValueError("taskIds must be non-empty")
                detected = True
                return {"method": method, **params, "checkboxesClicked": [0]}
            if method == "quest.observe":
                return quest if detected else {**quest, "complete": False}
            raise RuntimeError(method)
        fake = self.use(FakeKernel(detecting))
        settled = quests.mb_quest_detect("00000000-0000-0000-0000-000000000001")  # task_ids default to every task; the checkbox is clicked by Java
        self.assertEqual(fake.last("quest.detect")[1], {"questId": "00000000-0000-0000-0000-000000000001", "taskIds": [0]})
        self.assertEqual((settled["complete"], settled["canClaim"], settled["tasks"], settled["receipt"]["checkboxesClicked"]), (True, True, [{"id": 0, "name": "Tick", "complete": True}], [0]))
        detected = False
        quests.mb_quest_detect("quest", task_ids=[7], wait_s=0)
        self.assertEqual(fake.last("quest.detect")[1]["taskIds"], [7])
        def already_complete(method, params):
            if method == "quest.observe": return quest
            raise RuntimeError(method)
        fake = self.use(FakeKernel(already_complete))
        settled = quests.mb_quest_detect("00000000-0000-0000-0000-000000000001")
        self.assertEqual([call[0] for call in fake.calls], ["quest.observe"])
        self.assertEqual((settled["complete"], settled["receipt"]["reason"]), (True, "already_complete"))
        with self.assertRaises(ValueError): work.mb_build_preview()

    def test_source_process_settings_follow_and_cache_wrappers_validate_and_forward(self):
        tools = module_with(self.loaded(), "mb_follow")
        fake = self.use(FakeKernel(lambda method, params: {"method": method, **params}))
        tools.mb_settings("set", values={"allowInventory":True}, save=True)
        self.assertEqual(fake.calls[-1], ("nav.settings", {"operation":"set", "query":"", "save":True, "values":{"allowInventory":True}}))
        tools.mb_follow({"entityId":7,"type":"Item"}, duration_ticks=40, radius=3, offset_distance=2.5,
                        offset_direction=90, timeout_s=12)
        self.assertEqual(fake.last("nav.follow"), ("nav.follow", {"timeout":12, "target":{"entityId":7,"type":"Item"},
            "durationTicks":40,"radius":3,"offsetDistance":2.5,"offsetDirection":90,
            "allowBreak":False,"allowPlace":False,"overrideProtection":False}))
        tools.mb_fight(7, weapon_slot=0, timeout_s=20)
        self.assertEqual(fake.last("nav.fight"), ("nav.fight", {"timeout":20, "hold":False, "leash":16, "bailHealth":8,
            "maxAttackers":2, "maxHealthLoss":10, "maxGrowth":3, "durationTicks":600, "crit":True, "block":True, "entityId":7, "weaponSlot":0}))
        with self.assertRaises(ValueError): tools.mb_fight()
        tools.mb_fight(7, override_protection=True, timeout_s=20)
        sent = fake.last("nav.fight")[1]
        self.assertTrue(sent["overrideProtection"])
        self.assertNotIn("allowBreak", sent)
        self.assertNotIn("allowPlace", sent)
        tools.mb_fight(swarm=True, timeout_s=20)  # swarm: stands, takes no target, swings at the full rate unless asked otherwise
        sent = fake.last("nav.fight")[1]
        self.assertEqual((sent["swarm"], sent["crit"], sent["block"], "entityId" in sent), (True, False, False, False))
        with self.assertRaises(ValueError): tools.mb_fight(7, swarm=True)
        import mbtools_gtnh.plan as plan
        def world(method, params):
            if method == "obs.player": return {"pos": [10.5, 64.0, 20.5]}
            if method == "nav.copy": return {"plan": {"cells": [{"pos": [x, 0, z], "id": "minecraft:stone"} for x in range(3) for z in range(3)] + [{"pos": [1, 1, 1], "id": "minecraft:chest", "meta": 2}]}}
            if method == "memory.status": return {"waypoints": {"home": {"x": 10, "y": 64, "z": 20}, "outside": {"x": 50, "y": 64, "z": 20}},
                "regions": {"home": {"min": {"x": 9, "y": 63, "z": 19}, "max": {"x": 11, "y": 65, "z": 21}, "mode": "automation"}}}
            raise RuntimeError(method)
        self.use(FakeKernel(world))
        seen = plan.mb_view(bounds={"min": [9, 63, 19], "max": [11, 64, 21]})
        self.assertEqual([layer["rows"] for layer in seen["layers"]], [["###", "###", "###"], ["...", ".@.", "..."]])  # the player stands where the chest is drawn
        self.assertEqual(seen["legend"]["#"], {"id": "minecraft:stone", "meta": 0, "count": 9})
        self.assertIn({"what": "waypoint", "name": "home", "pos": [10, 64, 20]}, seen["things"])
        self.assertIn({"what": "protected region", "name": "home", "box": {"min": [9, 63, 19], "max": [11, 65, 21]}, "mode": "automation"}, seen["things"])
        self.assertFalse(any(t.get("name") == "outside" for t in seen["things"]))
        built, origin = plan.from_drawing({"origin": [9, 63, 19], "layers": [["#.", "+ "], {"y": 64, "rows": ["c."]}], "legend": {"#": "minecraft:stone", "c": {"id": "minecraft:chest", "meta": 2}}})
        self.assertEqual((built, origin), ([{"pos": [0, 0, 0], "id": "minecraft:stone"}, {"pos": [0, 1, 0], "id": "minecraft:chest", "meta": 2}], [9, 63, 19]))
        with self.assertRaises(ValueError): plan.from_drawing({"origin": [0, 0, 0], "layers": [["x"]], "legend": {}})
        kind = {"id": "mod:machines", "item": {"id": "mod:machines", "meta": 41}, "verify": {"pickedItem": {"id": "mod:machines", "meta": 41}}, "count": 1, "name": "seen"}
        self.assertEqual(plan.from_drawing({"origin": [0, 0, 0], "layers": [["h"]], "legend": {"h": kind}})[0], [{"pos": [0, 0, 0], **{k: kind[k] for k in ("id", "item", "verify")}}])
        with self.assertRaisesRegex(ValueError, "clik"):  # a misspelt key is said, not built as a plain block
            plan.from_drawing({"origin": [0, 0, 0], "layers": [["h"]], "legend": {"h": {"id": "mod:machines", "clik": {"face": "up"}}}})
        fake = self.use(FakeKernel(lambda method, params: {"method": method, **params}))
        h, v, shot = 2.9, .6, []  # a vanilla arrow: drag .99, gravity .05
        for _ in range(6): shot.append([h, v]); h, v = h * .99, v * .99 - .05
        import mbtools_gtnh.work as work
        learned = work.fit_ballistics({}, {"shots":1, "ballistics":{"drawTicks":30, "clickAfterLoad":True}, "tracks":[shot]})
        self.assertAlmostEqual(learned["drag"], .99, 3); self.assertAlmostEqual(learned["gravity"], .05, 3)
        self.assertEqual((learned["drawTicks"], learned["clickAfterLoad"], learned["shotsMeasured"]), (30, True, 1))
        self.assertIsNone(work.fit_ballistics({}, {"shots":0}))
        h, v, flight = 2.2 * .99, -.05, []
        for _ in range(7): flight.append([h, v]); h, v = h * .99, v * .99 - .05
        flight.append([h * .5, v * .5])  # impact velocity remains visible for one tick
        measured = work.fit_ballistics({}, {"shots": 1, "tracks": [flight]})
        self.assertAlmostEqual(measured["speed"], 2.2, 4)
        self.assertAlmostEqual(measured["drag"], .99, 4)
        self.assertAlmostEqual(measured["gravity"], .05, 4)
        tools.mb_mine([{"id":"gregtech:gt.blockores"}], vein=[33,56,-70], quantity=128, allow_break=True, allow_place=True)
        mine = fake.last("nav.mine")[1]  # chunk 1 is the ore chunk nearest x=33, chunk -4 nearest z=-70
        self.assertEqual((mine["bounds"], mine["besideFluid"], mine["items"]),
            ({"min":[0,48,-80],"max":[47,64,-33]}, True, [{"id":"gregtech:gt.metaitem.03"}]))
        with self.assertRaises(ValueError): tools.mb_mine()
        tools.mb_process("goal", goal={"type":"near","pos":[1,64,2],"radius":2}, duration_ticks=80, timeout_s=14)
        self.assertEqual(fake.last("nav.process")[1]["goal"]["type"], "near")
        tools.mb_cache("locations", block="minecraft:diamond_ore", meta=0, limit=12, region_distance_squared=4)
        self.assertEqual(fake.calls[-1], ("nav.cache", {"operation":"locations", "block":"minecraft:diamond_ore",
            "limit":12,"regionDistanceSquared":4,"meta":0}))
        tools.mb_cache("result", task_id="cache-task")
        self.assertEqual(fake.calls[-1], ("nav.cache", {"operation":"result","id":"cache-task"}))
        with self.assertRaises(ValueError): tools.mb_follow({}, duration_ticks=10)
        with self.assertRaises(ValueError): tools.mb_process("goal")
        with self.assertRaises(ValueError): tools.mb_cache("locations")
        with self.assertRaises(ValueError): tools.mb_settings("set")

    def test_recipe_view_and_inspect_keep_images_and_query_metadata_through_server(self):
        srv = self.loaded()
        tools = module_with(srv, "mb_recipe_view")

        def reply(method, params):
            server.reply_trace.get().append({"method": method})
            if method == "sys.screenshot":
                return {"png": base64.b64encode(b"minimal-png").decode()}
            if method == "nei.view":
                return {"handlerKey": params["handlerKey"], "index": params["index"],
                        "id": params["id"], "meta": params["meta"], "nbt": params["nbt"]}
            if method == "nei.inspect":
                return {"hover": "dustIron", "x": params["x"], "y": params["y"], "scroll": params["scroll"]}
            raise AssertionError(method)

        fake = self.use(FakeKernel(reply))
        with patch.object(tools.time, "sleep"):
            view = asyncio.run(srv.call_tool("mb_recipe_view", {
                "handler_key": "gt.recipe.macerator", "index": 7,
                "id": "gregtech:gt.metaitem.01", "meta": 42, "nbt": "{foo:1}",
                "mode": "uses", "fluid": "water", "amount": 2000,
            }))
            inspect = asyncio.run(srv.call_tool("mb_recipe_inspect", {"x": 31, "y": 47, "scroll": -1}))

        self.assertFalse(view.isError)
        self.assertEqual(view.meta["bridge"], [{"method": "nei.view"}, {"method": "sys.screenshot"}])
        self.assertEqual(json.loads(view.content[0].text), {
            "handlerKey": "gt.recipe.macerator", "index": 7,
            "id": "gregtech:gt.metaitem.01", "meta": 42, "nbt": "{foo:1}",
        })
        self.assertIsInstance(view.content[1], ImageContent)
        self.assertEqual(view.content[1].data, base64.b64encode(b"minimal-png").decode())
        self.assertEqual(fake.calls[0], ("nei.view", {
            "handlerKey": "gt.recipe.macerator", "index": 7,
            "id": "gregtech:gt.metaitem.01", "meta": 42, "nbt": "{foo:1}",
            "mode": "uses", "fluid": "water", "amount": 2000, "timeout": 60,
        }))
        self.assertFalse(inspect.isError)
        self.assertEqual(json.loads(inspect.content[0].text), {"hover": "dustIron", "x": 31, "y": 47, "scroll": -1})
        self.assertIsInstance(inspect.content[1], ImageContent)
        self.assertEqual(fake.calls[2], ("nei.inspect", {"x": 31, "y": 47, "scroll": -1}))

    def test_a_build_has_one_behaviour_and_no_knobs_that_select_another(self):
        tools = module_with(self.loaded(), "mb_build")
        for name in ("mb_build", "mb_build_preview", "mb_schematic_build", "mb_copy"):
            self.assertFalse({"mode", "settings", "stall_ticks"} & set(inspect.signature(getattr(tools, name)).parameters), name)
        self.assertEqual(list(inspect.signature(tools.mb_build).parameters), ["cells", "selection", "origin", "replace_existing",
                         "override_protection", "timeout_ticks", "allow_break", "allow_place", "size", "drawing", "uses"])
        # Clicks ride on the one build: no second way to call it.
        for name in ("mb_build", "mb_build_preview"):
            self.assertFalse({"steps", "pattern", "access"} & set(inspect.signature(getattr(tools, name)).parameters), name)
        self.assertFalse(hasattr(tools, "mb_pattern"))
        fake = self.use(FakeKernel(lambda method, params: {"state":"completed"}))
        cells = [{"pos":[i,0,0], "id":"minecraft:stone", "meta":0} for i in range(4097)]
        for call, method in ((tools.mb_build, "nav.build"), (tools.mb_build_preview, "nav.build_preview")):
            call(cells=cells, origin=[0,1,0], allow_break=True)
            # One request, whatever the size: the cell cap is the game's to state, and nothing is uploaded in pieces.
            self.assertEqual([m for m, _ in fake.calls if m.startswith("nav.")], [method]); sent = fake.last(method)[1]; fake.calls.clear()
            self.assertEqual(len(sent["cells"]), 4097)
            self.assertFalse({"mode", "settings", "stallTicks", "planId"} & set(sent))

    def test_drawing_subsets_keep_world_layer_heights(self):
        import mbtools_gtnh.work as work
        import mbtools_gtnh.plan as plan
        fake = self.use(FakeKernel(lambda method, params: {}))
        drawing = {"origin": [10, 60, 20], "layers": [{"y": 65, "rows": ["s"]},
                   {"y": 62, "rows": ["s"]}], "legend": {"s": {"id": "minecraft:stone", "meta": 0}}}
        work.mb_build_preview(drawing=drawing)
        request = fake.last("nav.build_preview")[1]
        self.assertEqual(request["origin"], [10, 60, 20])
        self.assertEqual([c["pos"] for c in request["cells"]], [[0, 5, 0], [0, 2, 0]])
        with self.assertRaises(ValueError):
            plan.from_drawing({**drawing, "layers": [{"y": "highest block per column", "rows": ["s"]}]})
        with self.assertRaises(ValueError):
            plan.from_drawing({**drawing, "layers": [{"y": 65, "rows": ["s"]}] * 2})

    def test_drawing_stages_give_each_block_the_stage_of_its_character(self):
        import mbtools_gtnh.work as work
        import mbtools_gtnh.plan as plan
        fake = self.use(FakeKernel(lambda method, params: {}))
        drawing = {"origin": [100, 64, 200], "stages": ["#", "MC", "p"], "layers": [["#####"], ["MpppC"]],
                   "legend": {"#": {"id": "pack:base"}, "M": {"id": "pack:machine"}, "C": {"id": "pack:chest"}, "p": {"id": "pack:pipe"}}}
        work.mb_build(drawing=drawing)
        cells = fake.last("nav.build")[1]["cells"]
        self.assertEqual([c.get("stage", 0) for c in cells], [0] * 5 + [1, 2, 2, 2, 1])
        self.assertNotIn("stage", cells[0])  # the first stage is the default: a drawing without stages sends the same cells as before
        # A character no stage names is built in the first, with whatever else is there.
        loose = plan.from_drawing({**drawing, "stages": ["p"]})[0]
        self.assertEqual({c["id"]: c.get("stage", 0) for c in loose}, {"pack:base": 0, "pack:machine": 0, "pack:chest": 0, "pack:pipe": 0})
        late = plan.from_drawing({**drawing, "stages": ["#", "p"]})[0]
        self.assertEqual({c["id"]: c.get("stage", 0) for c in late}, {"pack:base": 0, "pack:machine": 0, "pack:chest": 0, "pack:pipe": 1})
        for bad in (["#", "x"], ["#p", "p"], "#p", [], ["#", ""], ["#", 1]):
            with self.assertRaises(ValueError): plan.from_drawing({**drawing, "stages": bad})

    def test_a_legend_entry_carries_its_click_to_every_cell_drawn_with_it_and_uses_ride_along(self):
        import mbtools_gtnh.work as work
        fake = self.use(FakeKernel(lambda method, params: {}))
        expect = [{"method": "obs.block", "path": "meta", "equals": 2}]
        drawing = {"origin": [100, 64, 200], "stages": ["#", "M", "p"], "layers": [["#####"], ["Mppp."]],
                   "legend": {"#": {"id": "pack:base"}, "M": {"id": "pack:machine", "click": {"look": {"toward": "south"}}, "expect": expect},
                              "p": {"id": "pack:pipe", "click": {"face": "east"}}}}
        uses = [{"pos": [0, 1, 0], "item": {"empty": True}, "click": {"face": "up"}}]
        for call, method in ((work.mb_build, "nav.build"), (work.mb_build_preview, "nav.build_preview")):
            call(drawing=drawing, uses=uses)
            sent = fake.last(method)[1]
            self.assertEqual([c.get("click") for c in sent["cells"]], [None] * 5 + [{"look": {"toward": "south"}}] + [{"face": "east"}] * 3)
            self.assertEqual([c.get("expect") for c in sent["cells"]], [None] * 5 + [expect] + [None] * 3)
            self.assertNotIn("click", sent["cells"][0])  # a plain cell is sent as it always was
            self.assertEqual((sent["uses"], sent["origin"]), (uses, [100, 64, 200]))
            self.assertFalse({"steps", "pattern", "access"} & set(sent))
            # Uses alone are a call: clicks on what stands, and nothing to place.
            call(uses=uses, origin=[100, 64, 200])
            sent = fake.last(method)[1]
            self.assertEqual((sent["uses"], "cells" in sent, "selection" in sent), (uses, False, False))
            with self.assertRaises(ValueError): call()
            with self.assertRaises(ValueError): call(uses=[])
            with self.assertRaises(ValueError): call(cells=[{"pos": [0, 0, 0], "id": "a:b"}], selection={"min": [0, 0, 0], "max": [0, 0, 0]}, uses=uses)
            call(cells=[{"pos": [0, 0, 0], "id": "a:b"}])
            self.assertNotIn("uses", fake.last(method)[1])
        # A build that may dig or scaffold is waited on for its budget and for the putting back that may follow it.
        for allowed, ticks in (({}, 1000), ({"allow_break": True}, 1000 + work.CLOSING), ({"allow_place": True}, 1000 + work.CLOSING)):
            work.mb_build(uses=uses, timeout_ticks=1000, **allowed)
            self.assertEqual(fake.last("nav.build")[1]["timeout"], work._wait(ticks))

    def test_schematic_import_and_build_use_java_plan_and_explicit_overrides(self):
        tools = module_with(self.loaded(), "mb_schematic_build")
        plan = {"plan":{"cells":[{"pos":[0,0,0],"id":"a:b"}],"origin":[0,0,0],"size":[1,1,1]},
                "size":[1,1,1],"count":1,"skipped":{"air":3,"unknown":1},"tileEntities":0}
        fake = self.use(FakeKernel(lambda method, params: dict(plan) if method == "nav.schematic_import" else {"state":"succeeded"}))
        self.assertEqual(tools.mb_schematic_import("x", origin=[5,6,7], include_air=True), plan)
        self.assertEqual(fake.calls[-1], ("nav.schematic_import", {"path":"x","origin":[5,6,7],"includeAir":True}))
        result = tools.mb_schematic_build("x", preview=True)
        self.assertEqual(fake.calls[-2], ("nav.schematic_import", {"path":"x","includeAir":False}))
        request = fake.calls[-1]
        self.assertEqual(request[0], "nav.build_preview")
        self.assertEqual(request[1], {"cells":[{"pos":[0,0,0],"id":"a:b"}],"origin":[0,0,0],"size":[1,1,1]})   # the nested plan, Java defaults kept
        self.assertEqual(result["imported"], {"size":[1,1,1],"count":1,"skipped":{"air":3,"unknown":1},"tileEntities":0})
        self.assertEqual(result["request"]["cells"], 1)
        self.assertIn("preview", result)
        result = tools.mb_schematic_build("x", preview=False, replace_existing=False, allow_break=False, allow_place=True, timeout_ticks=77)
        request = fake.last("nav.build")[1]
        self.assertFalse(request["replaceExisting"]); self.assertFalse(request["allowBreak"]); self.assertTrue(request["allowPlace"])
        self.assertEqual((request.get("settings"), request["timeoutTicks"], request["timeout"]), (None, 77, tools._wait(77 + tools.CLOSING)))  # allow_place: the budget, and the closing that may follow it
        self.assertEqual(result["result"], {"state":"succeeded"})
        fake.reply = lambda method, params: {"count": 0, "cells": []}   # cells only count inside the nested plan
        with self.assertRaises(ValueError): tools.mb_schematic_build("x")

    def test_copy_returns_java_plan_and_chains_into_preview_or_build(self):
        tools = module_with(self.loaded(), "mb_copy")
        plan = {"plan":{"cells":[{"pos":[0,0,0],"id":"minecraft:stone","meta":4},{"pos":[1,0,0],"clear":True}],"origin":[0,0,0],"size":[2,1,1]},
                "size":[2,1,1],"count":2,"skipped":{"air":0,"unknown":0,"unloaded":0},"tileEntities":1}
        fake = self.use(FakeKernel(lambda method, params: dict(plan) if method == "nav.copy" else {"state":"succeeded"}))
        bounds = {"min":[10,5,10],"max":[11,5,10]}
        self.assertEqual(tools.mb_copy(bounds), plan)
        self.assertEqual(fake.calls, [("nav.copy", {"bounds":bounds, "includeAir":False})])
        tools.mb_copy(bounds, origin=[9,5,9], include_air=True)
        self.assertEqual(fake.calls[-1], ("nav.copy", {"bounds":bounds, "origin":[9,5,9], "includeAir":True}))
        previewed = tools.mb_copy(bounds, at=[20,7,20], preview=True)
        method, request = fake.calls[-1]
        self.assertEqual(method, "nav.build_preview")
        self.assertEqual((request["origin"], request["cells"], request["size"]), ([20,7,20], plan["plan"]["cells"], [2,1,1]))
        self.assertFalse({"count","skipped","tileEntities","timeoutTicks","timeout"} & set(request))
        self.assertEqual(previewed["copied"], {"size":[2,1,1],"count":2,"skipped":{"air":0,"unknown":0,"unloaded":0},"tileEntities":1})
        self.assertEqual(previewed["request"]["cells"], 2); self.assertIn("preview", previewed)
        built = tools.mb_copy(bounds, build=True, allow_break=True, timeout_ticks=300)
        method, request = fake.last("nav.build")
        self.assertEqual((request["origin"], request["allowBreak"], request["timeoutTicks"], request["timeout"]), ([0,0,0], True, 300, 502.5))  # the budget, and the closing a build may add to it
        self.assertEqual(built["result"], {"state":"succeeded"})
        for bad in ({}, {"min":[0,0,0]}, {"min":1,"max":2}):
            with self.assertRaises(ValueError): tools.mb_copy(bad)

    def test_craft_rejects_malformed_selectors_before_opening_or_moving(self):
        tools = module_with(self.loaded(), "mb_craft")
        fake = self.use(FakeKernel(lambda method, params: {}))
        for kwargs in (
            {"inputs": [{"id": "example:valid"}, {"item": {"id": "example:fuel"}, "count": 4}]},
            {"pattern": [[{"id": "example:valid"}, {}]]},
            {"inputs": [{"id": "example:fuel", "count": -1}]},
            {"pattern": [[{"id": "example:valid", "count": 1.5}]]},
        ):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                tools.mb_craft(at=[1, 64, 1], **kwargs)
        self.assertEqual(fake.calls, [])

    def test_craft_runs_a_machine_in_one_call_and_cells_without_meta_accept_any_facing(self):
        tools = module_with(self.loaded(), "mb_craft")
        cobble, coal, stone = ({"id": f"minecraft:{n}", "meta": 0, "count": c} for n, c in (("cobblestone", 3), ("coal", 1), ("stone", 2)))
        gui = {"open": False, "stacks": {2: stone, 3: cobble, 4: coal}}  # furnace: 0 input, 1 fuel, 2 output (holds an earlier job); 3.. player

        def slots(probe):
            want = gui["stacks"].get(probe)
            for i in range(6):
                held = gui["stacks"].get(i)
                fits = i != 2 and (held is None or want is not None and held["id"] == want["id"])
                yield {"i": i, "kind": "container" if i < 3 else "main", "inventory": int(i >= 3), "slotClass": "net.minecraft.inventory.Slot", "ordinary": True,
                       "canTake": True, "stack": held, **({"acceptsProbe": fits, "spaceForProbe": 64 if fits else 0} if want else {})}

        def reply(method, params):
            if method == "obs.container": return {"open": gui["open"], "windowId": 1, "epoch": 1, "cursor": None, "slots": list(slots(params.get("probeSlot")))}
            if method in ("act.use_block", "gui.close"): gui["open"] = method == "act.use_block"
            if method == "gui.transfer": gui["stacks"][params["destinations"][0]] = gui["stacks"].pop(params["source"]); return {"state": "completed", "transfer": {"moved": params["count"]}}
            if method == "gui.click_slot": gui["stacks"][5] = gui["stacks"].pop(params["slot"])
            return {"state": "completed"}
        fake = self.use(FakeKernel(reply))
        done = tools.mb_craft(at=[1, 64, 1], inputs=[cobble, coal])
        self.assertEqual(done["loaded"], [{"slot": 0, "id": "minecraft:cobblestone", "count": 3}, {"slot": 1, "id": "minecraft:coal", "count": 1}])
        self.assertEqual(done["collected"], [{"id": "minecraft:stone", "meta": 0, "count": 2}])  # only the slot that rejects its own contents is an output
        self.assertEqual([s["slot"] for s in done["inside"]], [0, 1]); self.assertFalse(gui["open"])
        self.assertEqual(fake.last("act.use_block")[1], {"x": 1, "y": 64, "z": 1})  # no face: the bridge clicks the visible one
        with self.assertRaises(ValueError): tools.mb_craft(pattern=[[cobble]], inputs=[cobble])
        build = module_with(self.srv, "mb_build")
        cells = [{"pos": [0, 0, 0], "id": "minecraft:furnace"}, {"pos": [1, 0, 0], "id": "minecraft:wool", "meta": 3}]
        seen = build.mb_build_preview(cells=cells)
        # A cell without meta goes to the game as written (the job reads it as any variant); the receipt says which ids.
        self.assertEqual((fake.last("nav.build_preview")[1]["cells"], seen["anyMeta"]["ids"]), (cells, ["minecraft:furnace"]))
        self.assertNotIn("settings", fake.last("nav.build_preview")[1])

    def test_harness_defaults_are_said_back_and_overridable(self):
        work = module_with(self.loaded(), "mb_mine")
        stock = [[{"identity": {"id": "minecraft:cobblestone", "meta": 0, "name": "Cobblestone"}, "count": 10}]]
        def reply(method, params):
            if method == "obs.inventory": return {"totals": stock[-1]}
            if method == "obs.block": return {"id": "gregtech:gt.blockores"}
            if method == "nav.mine":
                stock.append([{"identity": {"id": "minecraft:cobblestone", "meta": 0, "name": "Cobblestone"}, "count": 7},
                              {"identity": {"id": "gregtech:gt.metaitem.03", "meta": 5, "name": "Crushed Tin"}, "count": 4}])
                return {"state": "succeeded"}
            if method == "obs.scan": return {"matches": [], "cursor": 0, "done": True, "scanned": 1, "unloaded": 0}
            return {"method": method, **params}
        fake = self.use(FakeKernel(reply))
        mined = work.mb_mine([{"id": "minecraft:stone"}])  # no items: the job counts any gain; what arrived is measured
        self.assertNotIn("items", fake.last("nav.mine")[1])
        self.assertEqual((mined["dropsObserved"], mined["spent"]), ({"Crushed Tin": 4}, {"Cobblestone": 3}))
        mined = work.mb_mine(vein=[33, 56, -70], bounds={"min": [0, 1, 0], "max": [3, 4, 3]})
        self.assertEqual(fake.last("nav.mine")[1]["bounds"], {"min": [0, 1, 0], "max": [3, 4, 3]})  # given bounds are kept
        self.assertEqual((mined["veinDefaults"]["bounds"], mined["veinDefaults"]["blocks"]["used"], mined["veinDefaults"]["items"]["used"]),
                         ("your bounds", [{"id": "gregtech:gt.blockores"}], [{"id": "gregtech:gt.metaitem.03"}]))
        work.mb_mine(vein=[33, 56, -70], vein_grid={"height": 2})
        self.assertEqual(fake.last("nav.mine")[1]["bounds"], {"min": [0, 54, -80], "max": [47, 58, -33]})
        self.assertEqual(work.vein_bounds([33, 56, -70]), {"min": [0, 48, -80], "max": [47, 64, -33]})
        built = work.mb_build_preview(cells=[{"pos": [0, 0, 0], "id": "minecraft:furnace"}, {"pos": [1, 0, 0], "id": "minecraft:chest", "meta": 3}])
        self.assertEqual(built["anyMeta"]["ids"], ["minecraft:furnace"])  # the chest named its meta: that one is exact
        self.assertEqual(work.mb_build_preview(selection={"min": [0, 0, 0], "max": [1, 0, 0], "block": {"id": "minecraft:log"}})["anyMeta"]["ids"], ["minecraft:log"])
        self.assertNotIn("anyMeta", work.mb_build_preview(cells=[{"pos": [0, 0, 0], "id": "minecraft:stone", "meta": 0}]))
        scanned = work.mb_scan(None, {"min": [0, 0, 0], "max": [1, 1, 1]}, limit=999, max_s=0.5, detail="full")
        self.assertEqual(scanned["clamped"], {"limit": {"asked": 999, "used": 256}, "max_s": {"asked": 0.5, "used": 1.0}})
        self.assertNotIn("clamped", work.mb_scan(None, {"min": [0, 0, 0], "max": [1, 1, 1]}, detail="full"))
        up = [[0.0, 1.0], [0.0, .9], [0.0, .8]]  # straight up: no horizontal speed; one sample: nothing to fit
        self.assertEqual(work.fit_ballistics({"drag": .98}, {"shots": 2, "tracks": [up, [[1.0, 0.0]]]})["drag"], .98)
        import mbtools_gtnh.plan as plan
        def room(method, params):
            if method == "obs.player": return {"pos": [0.5, 64.0, 0.5]}
            if method == "nav.copy": return {"plan": {"cells": [{"pos": [x, 0, 0], "id": "gregtech:gt.blockmachines"} for x in range(8)]}}
            if method == "obs.block": return {"name": "Macerator"}
            return {}
        self.use(FakeKernel(room))
        box = {"min": [0, 64, 0], "max": [7, 64, 0]}
        self.assertFalse([t for t in plan.mb_view(bounds=box)["things"] if t["what"] == "block"])  # 8 of one id is over rare=6
        seen = plan.mb_view(bounds=box, rare=8, lookups=3)["things"]
        self.assertEqual(([t["what"] for t in seen].count("block"), next(t["count"] for t in seen if t["what"] == "unnamed")), (3, 5))
        names = ["Steam Macerator", "Steam Macerator", "Steam Compressor", "Bronze Boiler", "Steam Macerator", "Steam Compressor", "Steam Forge Hammer", "Steam Alloy Smelter"]
        machines = [{"pos": [x, 0, 0], "id": "gregtech:gt.blockmachines", "meta": 0, "tile": True, "name": n} for x, n in enumerate(names)]
        self.use(FakeKernel(lambda method, params: {"obs.player": {"pos": [0.5, 64.0, 0.5]}, "nav.copy": {"plan": {"cells": machines}}}.get(method, {})))
        seen = plan.mb_view(bounds=box)  # one id, five machines: five characters, named, and nothing looked up one by one
        self.assertEqual(seen["legend"]["#"], {"id": "gregtech:gt.blockmachines", "meta": 0, "name": "Steam Macerator", "count": 3, "tile": True})
        self.assertEqual((len(seen["legend"]), seen["layers"][0]["rows"][0][1:], [t for t in seen["things"] if t["what"] in ("block", "unnamed")]), (5, "#=%#=*&", []))
        built, _ = plan.from_drawing({"origin": seen["origin"], "layers": [layer["rows"] for layer in seen["layers"]], "legend": seen["legend"]})
        self.assertEqual(built[0], {"pos": [1, 0, 0], "id": "gregtech:gt.blockmachines", "meta": 0})  # the builder takes id and meta, not the name
        crowd = [{"pos": [x % 90, 0, x // 90], "id": "pack:cable", "meta": 0, "tile": True, "name": f"Cable {x}"} for x in range(70)]
        crowd += [{"pos": [x, 1, 0], "id": "pack:pipe", "meta": 0, "tile": True, "name": f"Pipe {x}"} for x in range(4)] + [{"pos": [0, 2, 0], "id": "minecraft:stone"}]
        self.use(FakeKernel(lambda method, params: {"obs.player": {"pos": [0.5, 70.0, 0.5]}, "nav.copy": {"plan": {"cells": crowd}}}.get(method, {})))
        seen = plan.mb_view(bounds={"min": [0, 64, 0], "max": [89, 66, 0]}, lookups=0)  # 75 kinds do not fit 70 characters: the cables give their names up
        self.assertEqual([t for t in seen["things"] if t["what"] == "names merged"], [{"what": "names merged", "id": "pack:cable", "meta": 0, "kinds": 70, "why": f"more named kinds than {len(plan.CHARS)} characters: these share one, unnamed"}])
        self.assertEqual(seen["legend"]["#"], {"id": "pack:cable", "meta": 0, "count": 70, "tile": True})
        self.assertEqual(sorted(v["name"] for v in seen["legend"].values() if "name" in v), ["Pipe 0", "Pipe 1", "Pipe 2", "Pipe 3"])

    def test_run_chains_tools_in_one_call_and_reports_where_a_script_stopped(self):
        tools = module_with(self.loaded(), "mb_run")
        with tempfile.TemporaryDirectory() as folder, patch.object(tools, "SCRIPTS", Path(folder) / "scripts"):
            fake = self.use(FakeKernel(lambda method, params: {"id": "minecraft:dirt"} if method == "obs.block" else {"stopped": True}))
            code = "def main(n=1):\n    for i in range(n):\n        log(mb_obs('block', {'x': i, 'y': 0, 'z': 0})['id'])\n    mb_stop()\n    return n\n"
            self.assertEqual(tools.mb_run(code, {"n": 2}), {"result": 2, "log": ["minecraft:dirt"] * 2})
            self.assertEqual([c[0] for c in fake.calls if c[0] != "memory.context"], ["obs.block", "obs.block", "act.stop"])
            self.assertFalse((Path(folder) / "scripts").exists())  # a script runs once and is gone unless it is given a name
            tools.mb_run(code, name="probe")
            self.assertEqual(tools.mb_run(name="probe", args={"n": 3})["result"], 3)
            stopped = tools.mb_run("def main():\n    log('one')\n    mb_time('nonsense')\n")
            self.assertEqual((stopped["stopped"], stopped["line"], stopped["source"], stopped["log"]), ("ValueError: unknown time method", 3, "mb_time('nonsense')", ["one"]))
            for bad in ({"name": "Bad"}, {"name": "../x"}, {"name": "missing"}, {}):
                with self.assertRaises(ValueError): tools.mb_run(**bad)


if __name__ == "__main__":
    unittest.main()
