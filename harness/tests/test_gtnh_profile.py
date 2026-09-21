# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Offline coverage for the reloadable GTNH tool set: tool table, lanes, reload atomicity, wrappers."""
from __future__ import annotations

import base64
import asyncio
import json
import os
from pathlib import Path
import sys
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
    "mb_interrupt", "mb_interrupt_events", "mb_wait", "mb_wiki_search", "mb_wiki_read", "mb_goal", "mb_craft", "mb_run",
    "mb_act", "mb_call", "mb_gui", "mb_keys", "mb_map", "mb_methods", "mb_obs", "mb_screenshot", "mb_status", "mb_stop", "mb_time",
    "mb_recipe_status", "mb_item_search", "mb_item_info", "mb_recipes", "mb_fluid_search", "mb_recipe_handlers", "mb_recipe_view", "mb_recipe_inspect",
    "mb_memory", "mb_route", "mb_inventory", "mb_find", "mb_transfer", "mb_click_slot", "mb_notes", "mb_note_write",
    "mb_follow", "mb_process", "mb_settings", "mb_cache",
    "mb_mine", "mb_build_preview", "mb_build", "mb_copy",
    "mb_schematic_import", "mb_schematic_build", "mb_scan", "mb_work_status",
    "mb_work_resume", "mb_build_pause", "mb_build_materials", "mb_quest_status", "mb_quest_sync", "mb_quest_search", "mb_quest_lines",
    "mb_quest_observe", "mb_quest_detect", "mb_quest_select_choice", "mb_quest_claim",
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

    def test_tool_set_lanes_and_metadata(self):
        srv = self.loaded()
        self.assertEqual(set(srv.name_owner), TOOLS)
        self.assertEqual(len(srv.modules), 8)
        self.assertEqual({os.path.basename(p) for p in srv.modules},
                         {"core.py", "inventory.py", "work.py", "recipes_quests.py", "interrupts.py", "notes.py", "wiki.py", "scripts.py"})
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
        self.assertEqual(sum(len(m["tools"]) for m in status["modules"]), 58)
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
        with self.assertRaises(ValueError):
            core.mb_time("step")
        core.mb_memory("protect", {"name": "base", "min": [1,2,3], "max": [4,5,6]})
        self.assertEqual(fake.calls[-1], ("memory.protect", {"name": "base", "min": [1,2,3], "max": [4,5,6]}))
        with self.assertRaises(ValueError):
            core.mb_memory("dev.fluid_fixture.restore")
        work.mb_route("base to cave", reverse=True, start_index=2, timeout_s=900, timeout_ticks=16000)
        self.assertEqual(fake.last("nav.route"), ("nav.route", {"name": "base to cave", "reverse": True,
            "startIndex": 2, "allowBreak": False, "allowPlace": False, "overrideProtection": False,
            "timeoutTicks": 16000, "timeout": 900}))
        work.mb_route("approved work", allow_break=True, override_protection=True)
        self.assertTrue(fake.last("nav.route")[1]["overrideProtection"])
        work.mb_route("next journey")
        self.assertFalse(fake.last("nav.route")[1]["overrideProtection"])
        work.mb_mine([{"id":"ore:block"}], [{"id":"ore:item"}], quantity=8,
                     bounds={"min":[0,1,0],"max":[3,4,3]}, timeout_s=700)
        self.assertEqual(fake.last("nav.mine"), ("nav.mine", {
            "blocks":[{"id":"ore:block"}], "items":[{"id":"ore:item"}], "quantity":8,
            "radius":24, "allowBreak":False, "allowPlace":False,
            "overrideProtection":False, "timeoutTicks":12000,
            "bounds":{"min":[0,1,0],"max":[3,4,3]}, "timeout":700}))
        cells=[{"pos":[0,0,0],"id":"minecraft:stone","meta":0}]
        work.mb_build_preview(cells=cells, origin=[10,70,10])
        self.assertEqual(fake.calls[-1][0], "nav.build_preview")
        work.mb_build(cells=cells, timeout_ticks=500, timeout_s=45)
        self.assertEqual(fake.last("nav.build"), ("nav.build", {"replaceExisting":False,
            "overrideProtection":False,"allowBreak":False,"allowPlace":False,"mode":"blueprint",
            "timeoutTicks":500,"cells":cells,"timeout":45}))
        # One mb_scan covers a volume above the bridge's per-scan cap: layers of <=262144 cells, each paged to its end.
        def scan(method, params):
            volume = 1
            for a, b in zip(params["bounds"]["min"], params["bounds"]["max"]): volume *= b - a + 1
            end = min(params["cursor"] + params["budget"], volume)
            return {"matches": [{"at": end}] if end == volume else [], "cursor": end, "done": end == volume, "scanned": end - params["cursor"], "unloaded": 0}
        scanning = self.use(FakeKernel(scan))
        found = work.mb_scan([{"ore":"oreIron"}], {"min":[0,0,0],"max":[63,199,63]}, limit=9)
        self.assertEqual((found["done"], len(found["matches"]), found["scanned"], found["volume"]), (True, 4, 819200, 819200))
        self.assertTrue(all(c[1]["bounds"]["max"][1] - c[1]["bounds"]["min"][1] + 1 <= 64 for c in scanning.calls))
        part = work.mb_scan(None, {"min":[0,0,0],"max":[63,199,63]}, limit=1)
        self.assertEqual((part["done"], part["cursor"]), (False, [1, 0]))
        with self.assertRaisesRegex(ValueError, "512x512"): work.mb_scan(None, {"min":[0,0,0],"max":[600,1,600]})
        self.use(fake)
        work.mb_work_status("job-7")
        self.assertEqual(fake.calls[-1], ("nav.work_status", {"jobId":"job-7"}))
        work.mb_work_resume("job-7", {"timeoutTicks":400,"overrideProtection":True}, timeout_s=88)
        self.assertEqual(fake.last("nav.resume"), ("nav.resume", {"timeout":88,"jobId":"job-7",
            "timeoutTicks":400,"overrideProtection":True}))
        quests.mb_quest_observe("00000000-0000-0000-0000-000000000001")
        self.assertEqual(fake.calls[-1][0], "quest.observe")
        quests.mb_quest_claim("00000000-0000-0000-0000-000000000001", [2], {"2":1}, wait_s=0)
        self.assertEqual(fake.calls[-1][1]["choices"], {"2":1})
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

    def test_large_build_staging_uses_guarded_offsets_without_truncation(self):
        tools = module_with(self.loaded(), "mb_build")
        count = [0]
        def reply(method, params):
            if method == "nav.build_stage" and params["operation"] == "begin": return {"stageId":"s", "count":0}
            if method == "nav.build_stage" and params["operation"] == "append":
                count[0] += len(params["cells"]); return {"stageId":"s", "count":count[0]}
            if method == "nav.build_stage": return {"stageId":"s", "count":count[0], "planId":"p"}
            return {"state":"completed"}
        fake = self.use(FakeKernel(reply)); cells=[{"pos":[i,0,0],"id":"minecraft:stone"} for i in range(4097)]
        tools.mb_build(cells=cells, origin=[0,1,0], mode="builder", allow_break=True)
        appends=[p for m,p in fake.calls if m=="nav.build_stage" and p["operation"]=="append"]
        self.assertEqual([x["offset"] for x in appends],[0,4096])
        self.assertEqual(sum(len(x["cells"]) for x in appends),4097)
        begin=fake.calls[0][1]; self.assertNotIn("cells",begin["spec"]); self.assertEqual(begin["spec"]["mode"],"builder")
        build = fake.last("nav.build")[1]
        self.assertEqual(build["planId"],"p"); self.assertTrue(build["allowBreak"])

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
        result = tools.mb_schematic_build("x", preview=False, replace_existing=False, allow_break=False, allow_place=True,
                                          settings={"restricted":False}, timeout_ticks=77, timeout_s=9)
        request = fake.last("nav.build")[1]
        self.assertFalse(request["replaceExisting"]); self.assertFalse(request["allowBreak"]); self.assertTrue(request["allowPlace"])
        self.assertEqual((request["settings"], request["timeoutTicks"], request["timeout"]), ({"restricted":False}, 77, 9))
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
        built = tools.mb_copy(bounds, build=True, allow_break=True, timeout_ticks=300, timeout_s=30)
        method, request = fake.last("nav.build")
        self.assertEqual((request["origin"], request["allowBreak"], request["timeoutTicks"], request["timeout"]), ([0,0,0], True, 300, 30))
        self.assertEqual(built["result"], {"state":"succeeded"})
        for bad in ({}, {"min":[0,0,0]}, {"min":1,"max":2}):
            with self.assertRaises(ValueError): tools.mb_copy(bad)

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
        build.mb_build_preview(cells=[{"pos": [0, 0, 0], "id": "minecraft:furnace"}, {"pos": [1, 0, 0], "id": "minecraft:wool", "meta": 3}])
        self.assertEqual(fake.last("nav.build_preview")[1]["settings"], {"metadataMasks": {"minecraft:furnace": 0}})

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
