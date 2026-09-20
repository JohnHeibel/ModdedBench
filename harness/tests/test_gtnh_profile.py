# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 Modbench contributors
"""Offline coverage for the reloadable, generic GTNH MCP profile."""
from __future__ import annotations

import base64
import asyncio
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


MCP = Path(__file__).resolve().parents[1] / "mcp"
sys.path[:0] = [str(MCP)]
import server
from mcp.types import ImageContent


class GTNHProfileTests(unittest.TestCase):
    def _close(self, srv):
        srv._workers.shutdown(wait=False, cancel_futures=True)
        srv._control_workers.shutdown(wait=False, cancel_futures=True)

    def test_composition_partial_receipts_survive_mcp_error_wrapping(self):
        from gtnh_ui import ProcedureStopped
        from kernel import BridgeError
        srv = server.Server(profile="gtnh")
        def failed() -> dict:
            try:
                raise BridgeError("ack_timeout", "inspect", "gui.click_slot", {"error":{"receipt":{"state":"failed"}}})
            except BridgeError as error:
                raise ProcedureStopped("partial procedure", [{"state":"completed","transfer":{"moved":2}}]) from error
        try:
            srv.add_tool(failed, name="mb_partial_procedure")
            result = asyncio.run(srv.call_tool("mb_partial_procedure", {}))
            self.assertTrue(result.isError)
            error = result.structuredContent["error"]
            self.assertEqual(error["code"], "ack_timeout")
            self.assertEqual(error["procedureReceipts"][0]["transfer"]["moved"], 2)
            self.assertEqual(error["reply"]["error"]["receipt"]["state"], "failed")
        finally:
            self._close(srv)

    def test_default_profile_loads_the_gtnh_tool_set(self):
        gtnh = server.Server()
        try:
            self.assertEqual(gtnh.profile, "gtnh")
            self.assertFalse(any(message.startswith("ERROR") for message in gtnh.check_reload(force=True)))
            self.assertEqual(set(gtnh.name_owner), {
                "mb_interrupt", "mb_interrupt_events",
                "mb_act", "mb_call", "mb_gui", "mb_keys", "mb_methods", "mb_obs", "mb_screenshot", "mb_status", "mb_stop", "mb_time", "mb_nei_status", "mb_search", "mb_item", "mb_recipes", "mb_fluids", "mb_recipe_handlers", "mb_recipe_view", "mb_recipe_inspect", "mb_memory", "mb_route", "mb_inventory", "mb_find", "mb_transfer", "mb_click_slot", "mb_notes", "mb_note_write",
                "mb_follow", "mb_process", "mb_settings", "mb_cache",
                "mb_mine", "mb_build_preview", "mb_build", "mb_selection_build", "mb_selection",
                "mb_schematic_import", "mb_schematic_build", "mb_scan", "mb_work_status",
                "mb_work_resume", "mb_builder_pause", "mb_builder_materials", "mb_quest_status", "mb_quest_sync", "mb_quest_search", "mb_quest_lines",
                "mb_quest_observe", "mb_quest_detect", "mb_quest_select_choice", "mb_quest_claim",
            })
            self.assertNotIn("mb_baritone", gtnh.name_owner)
            self.assertNotIn("mb_tick", gtnh.name_owner)
            self.assertNotIn("jei_show", gtnh.name_owner)
            self.assertNotIn("bq_open", gtnh.name_owner)
        finally:
            self._close(gtnh)

    def test_failed_gtnh_reload_retains_last_good_tools(self):
        srv = server.Server(profile="gtnh")
        try:
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
                self.assertFalse(any(message.startswith("ERROR") for message in srv.check_reload(force=True)))
                original = srv._tool_manager._tools["mb_profile_probe"]
                tool_path.write_text("this is not valid Python!\n")
                results = srv.check_reload(force=True)
                self.assertTrue(any(message.startswith("ERROR profile_tool.py") for message in results))
                self.assertIs(srv._tool_manager._tools["mb_profile_probe"], original)
                self.assertTrue(srv.modules[str(tool_path)].error)
        finally:
            self._close(srv)

    def test_reload_executes_same_size_source_with_preserved_mtime(self):
        srv = server.Server(profile="gtnh")
        try:
            with tempfile.TemporaryDirectory() as tmp:
                tool_path = Path(tmp) / "profile_tool.py"
                v1 = ('from mbtool import tool\n@tool(rung=0)\ndef mb_profile_probe() -> str:\n'
                      '    """A temporary profile tool."""\n    return "v1"\n')
                v2 = v1.replace('"v1"', '"v2"')
                self.assertEqual(len(v1), len(v2))
                tool_path.write_text(v1)
                original_mtime = os.stat(tool_path).st_mtime_ns
                srv.tools_dir = tmp
                self.assertFalse(any(message.startswith("ERROR") for message in srv.check_reload(force=True)))
                self.assertEqual(srv.modules[str(tool_path)].module.mb_profile_probe(), "v1")
                tool_path.write_text(v2)
                os.utime(tool_path, ns=(os.stat(tool_path).st_atime_ns, original_mtime))
                self.assertFalse(any(message.startswith("ERROR") for message in srv.check_reload(force=True)))
                self.assertEqual(srv.modules[str(tool_path)].module.mb_profile_probe(), "v2")
        finally:
            self._close(srv)

    def test_gtnh_uses_client_default_and_honors_explicit_url_override(self):
        with patch.dict(os.environ, {}, clear=True):
            default = server.Server(profile="gtnh")
        with patch.dict(os.environ, {"MB_BRIDGE_URL": "ws://example.test:49999/ws"}, clear=True):
            overridden = server.Server(profile="gtnh")
        try:
            self.assertEqual(default.bridge_url, "ws://127.0.0.1:47223/ws")
            self.assertEqual(overridden.bridge_url, "ws://example.test:49999/ws")
        finally:
            self._close(default)
            self._close(overridden)

    def test_generic_tools_use_advertised_namespaces_and_screenshot_payload(self):
        srv = server.Server(profile="gtnh")
        try:
            srv.check_reload(force=True)
            tools = next(iter(srv.modules.values())).module

            class FakeKernel:
                def __init__(self):
                    self.calls = []

                def call(self, method, **params):
                    self.calls.append((method, params))
                    if method == "sys.screenshot":
                        return {"png": base64.b64encode(b"png").decode(), "width": 1, "height": 1}
                    return {"method": method}

            fake = FakeKernel()
            with patch.object(tools, "kernel", return_value=fake):
                self.assertEqual(tools.mb_methods()["method"], "sys.methods")
                self.assertEqual(tools.mb_status()["method"], "sys.capabilities")
                self.assertEqual(tools.mb_obs("player", {"detail": "full"})["method"], "obs.player")
                self.assertEqual(tools.mb_stop()["method"], "act.stop")
                self.assertEqual(tools.mb_screenshot().data, b"png")
                with self.assertRaises(ValueError):
                    tools.mb_gui("obs.player")
                tools.mb_click_slot(7, 123, 41, None, {'id':'minecraft:paper'}, click_type='pickup')
                self.assertEqual(fake.calls[-1], ('gui.click_slot', dict(windowId=7, epoch=123, slot=41,
                    expected=None, expectedCursor={'id':'minecraft:paper'}, type='pickup', button=0)))
                tools.mb_transfer(7,123,61,{'id':'minecraft:coal','count':8},[11],3,'consuming')
                self.assertEqual(fake.calls[-1][1]['destinationPolicy'],'consuming')
                self.assertIsNone(fake.calls[-1][1]['expectedCursor'])
                self.assertEqual(tools.mb_time("pause", timeout_s=120)["method"], "time.pause")
                self.assertEqual(fake.calls[-1], ("time.pause", {"timeout": 120}))
                with self.assertRaises(ValueError):
                    tools.mb_time("dev.time_fixture.hurt")
                with self.assertRaises(ValueError):
                    tools.mb_time("step")
                tools.mb_memory("protect", {"name": "base", "min": [1,2,3], "max": [4,5,6]})
                self.assertEqual(fake.calls[-1], ("memory.protect", {"name": "base", "min": [1,2,3], "max": [4,5,6]}))
                with self.assertRaises(ValueError):
                    tools.mb_memory("dev.fluid_fixture.restore")
                tools.mb_route("base to cave", reverse=True, start_index=2, timeout_s=900, timeout_ticks=16000)
                self.assertEqual(fake.calls[-1], ("baritone.route", {"name": "base to cave", "reverse": True,
                    "startIndex": 2, "allowBreak": False, "allowPlace": False, "overrideProtection": False,
                    "timeoutTicks": 16000, "timeout": 900}))
                tools.mb_route("approved work", allow_break=True, override_protection=True)
                self.assertTrue(fake.calls[-1][1]["overrideProtection"])
                tools.mb_route("next journey")
                self.assertFalse(fake.calls[-1][1]["overrideProtection"])
                tools.mb_mine([{"id":"ore:block"}], [{"id":"ore:item"}], quantity=8,
                              bounds={"min":[0,1,0],"max":[3,4,3]}, timeout_s=700)
                self.assertEqual(fake.calls[-1], ("baritone.mine", {
                    "blocks":[{"id":"ore:block"}], "items":[{"id":"ore:item"}], "quantity":8,
                    "radius":24, "allowBreak":False, "allowPlace":False,
                    "overrideProtection":False, "timeoutTicks":12000,
                    "bounds":{"min":[0,1,0],"max":[3,4,3]}, "timeout":700}))
                cells=[{"pos":[0,0,0],"id":"minecraft:stone","meta":0}]
                tools.mb_build_preview(cells=cells, origin=[10,70,10])
                self.assertEqual(fake.calls[-1][0], "baritone.build_preview")
                tools.mb_build(cells=cells, timeout_ticks=500, timeout_s=45)
                self.assertEqual(fake.calls[-1], ("baritone.build", {"replaceExisting":False,
                    "overrideProtection":False,"allowBreak":False,"allowPlace":False,"mode":"blueprint",
                    "timeoutTicks":500,"cells":cells,"timeout":45}))
                selection={"min":[1,2,3],"max":[4,5,6],"shape":"walls",
                           "block":{"id":"minecraft:stone"}}
                tools.mb_selection_build(selection, preview=True)
                self.assertEqual(fake.calls[-1][0], "baritone.build_preview")
                tools.mb_scan([{"ore":"oreIron"}], {"min":[0,1,0],"max":[2,3,2]}, limit=9)
                self.assertEqual(fake.calls[-1][0], "baritone.scan")
                tools.mb_work_status("job-7")
                self.assertEqual(fake.calls[-1], ("baritone.work_status", {"jobId":"job-7"}))
                tools.mb_work_resume("job-7", {"timeoutTicks":400,"overrideProtection":True}, timeout_s=88)
                self.assertEqual(fake.calls[-1], ("baritone.resume", {"timeout":88,"jobId":"job-7",
                    "timeoutTicks":400,"overrideProtection":True}))
                tools.mb_quest_observe("00000000-0000-0000-0000-000000000001")
                self.assertEqual(fake.calls[-1][0], "quest.observe")
                tools.mb_quest_claim("00000000-0000-0000-0000-000000000001", [2], {"2":1})
                self.assertEqual(fake.calls[-1][1]["choices"], {"2":1})
                with self.assertRaises(ValueError): tools.mb_build_preview()
            self.assertEqual(fake.calls[2], ("obs.player", {"detail": "full"}))
        finally:
            self._close(srv)

    def test_source_process_settings_follow_and_cache_wrappers_validate_and_forward(self):
        srv = server.Server(profile="gtnh")
        try:
            srv.check_reload(force=True)
            tools = next(state.module for state in srv.modules.values() if hasattr(state.module, "mb_follow"))
            class Fake:
                def __init__(self): self.calls=[]
                def call(self, method, **params): self.calls.append((method, params)); return {"method":method, **params}
            fake=Fake()
            with patch.object(tools,"kernel",return_value=fake):
                tools.mb_settings("set", values={"allowInventory":True}, save=True)
                self.assertEqual(fake.calls[-1], ("baritone.settings", {"operation":"set", "query":"", "save":True, "values":{"allowInventory":True}}))
                tools.mb_follow({"entityId":7,"type":"Item"}, duration_ticks=40, radius=3, offset_distance=2.5,
                                offset_direction=90, timeout_s=12)
                self.assertEqual(fake.calls[-1], ("baritone.follow", {"timeout":12, "target":{"entityId":7,"type":"Item"},
                    "durationTicks":40,"radius":3,"offsetDistance":2.5,"offsetDirection":90,
                    "allowBreak":False,"allowPlace":False,"overrideProtection":False}))
                tools.mb_process("goal", goal={"type":"near","pos":[1,64,2],"radius":2}, duration_ticks=80, timeout_s=14)
                self.assertEqual(fake.calls[-1][0], "baritone.process")
                self.assertEqual(fake.calls[-1][1]["goal"]["type"], "near")
                tools.mb_cache("locations", block="minecraft:diamond_ore", meta=0, limit=12, region_distance_squared=4)
                self.assertEqual(fake.calls[-1], ("baritone.cache", {"operation":"locations", "block":"minecraft:diamond_ore",
                    "limit":12,"regionDistanceSquared":4,"meta":0}))
                tools.mb_cache("result", task_id="cache-task")
                self.assertEqual(fake.calls[-1], ("baritone.cache", {"operation":"result","id":"cache-task"}))
                with self.assertRaises(ValueError): tools.mb_follow({}, duration_ticks=10)
                with self.assertRaises(ValueError): tools.mb_process("goal")
                with self.assertRaises(ValueError): tools.mb_cache("locations")
                with self.assertRaises(ValueError): tools.mb_settings("set")
        finally:
            self._close(srv)

    def test_recipe_view_and_inspect_keep_images_and_query_metadata_through_server(self):
        srv = server.Server(profile="gtnh")
        try:
            srv.check_reload(force=True)
            tools = next(state.module for state in srv.modules.values() if hasattr(state.module, "mb_recipe_view"))

            class FakeKernel:
                connected = True

                def __init__(self):
                    self.calls = []

                def call(self, method, **params):
                    self.calls.append((method, params))
                    server.reply_trace.get().append({"method": method})
                    if method == "sys.screenshot":
                        return {"png": base64.b64encode(b"minimal-png").decode()}
                    if method == "nei.view":
                        return {"handlerKey": params["handlerKey"], "index": params["index"],
                                "id": params["id"], "meta": params["meta"], "nbt": params["nbt"]}
                    if method == "nei.inspect":
                        return {"hover": "dustIron", "x": params["x"], "y": params["y"],
                                "scroll": params["scroll"]}
                    raise AssertionError(method)

            fake = FakeKernel()
            with patch.object(tools, "kernel", return_value=fake), patch.object(tools.time, "sleep"):
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
            self.assertEqual(view.content[1].type, "image")
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
        finally:
            self._close(srv)

    def test_large_build_staging_uses_guarded_offsets_without_truncation(self):
        srv = server.Server(profile="gtnh")
        try:
            srv.check_reload(force=True)
            tools = next(state.module for state in srv.modules.values() if hasattr(state.module, "mb_build"))
            class Fake:
                def __init__(self): self.calls=[]; self.count=0
                def call(self, method, **params):
                    self.calls.append((method, params))
                    if method == "baritone.build_stage" and params["operation"] == "begin": return {"stageId":"s", "count":0}
                    if method == "baritone.build_stage" and params["operation"] == "append":
                        self.count += len(params["cells"]); return {"stageId":"s", "count":self.count}
                    if method == "baritone.build_stage": return {"stageId":"s", "count":self.count, "planId":"p"}
                    return {"state":"completed"}
            fake=Fake(); cells=[{"pos":[i,0,0],"id":"minecraft:stone"} for i in range(4097)]
            with patch.object(tools,"kernel",return_value=fake):
                tools.mb_build(cells=cells, origin=[0,1,0], mode="builder", allow_break=True)
            appends=[p for m,p in fake.calls if m=="baritone.build_stage" and p["operation"]=="append"]
            self.assertEqual([x["offset"] for x in appends],[0,4096])
            self.assertEqual(sum(len(x["cells"]) for x in appends),4097)
            begin=fake.calls[0][1]; self.assertNotIn("cells",begin["spec"]); self.assertEqual(begin["spec"]["mode"],"builder")
            self.assertEqual(fake.calls[-1][1]["planId"],"p")
            self.assertTrue(fake.calls[-1][1]["allowBreak"])
        finally: self._close(srv)

    def test_schematic_build_overrides_are_explicit_and_omissions_preserve_file(self):
        srv=server.Server(profile="gtnh")
        try:
            srv.check_reload(force=True)
            tools=next(state.module for state in srv.modules.values() if hasattr(state.module,"mb_schematic_build"))
            imported={"build":{"origin":[0,1,0],"cells":[{"pos":[0,0,0],"id":"a:b"}],
                "replaceExisting":True,"allowBreak":True,"allowPlace":False,
                "settings":{"restricted":True}},"requirements":[]}
            class Fake:
                def __init__(self): self.calls=[]
                def call(self,method,**params): self.calls.append((method,params)); return {"ok":True}
            fake=Fake()
            with patch.object(tools,"kernel",return_value=fake),patch.object(tools,"mb_schematic_import",return_value=imported):
                result=tools.mb_schematic_build("x",preview=True)
                self.assertTrue(result["request"]["replaceExisting"]);self.assertTrue(result["request"]["allowBreak"])
                result=tools.mb_schematic_build("x",preview=True,replace_existing=False,allow_break=False,
                                                allow_place=True,settings={"restricted":False})
                self.assertFalse(result["request"]["replaceExisting"]);self.assertTrue(result["request"]["allowPlace"])
                self.assertEqual(result["request"]["settings"],{"restricted":False})
        finally:self._close(srv)

    def test_selection_copy_pages_all_states_and_paste_preserves_hole(self):
        srv=server.Server(profile="gtnh")
        try:
            srv.check_reload(force=True)
            tools=next(state.module for state in srv.modules.values() if hasattr(state.module,"mb_selection"))
            class Fake:
                def __init__(self): self.calls=[]
                def call(self,method,**params):
                    self.calls.append((method,params))
                    if method=="memory.context": return {"worldId":"00000000-0000-0000-0000-000000000099","dimension":0,"pos":[9,5,9]}
                    if method=="baritone.scan":
                        self.assert_no_filter(params)
                        low,high=params["bounds"]["min"],params["bounds"]["max"]
                        rows=[]
                        for y in range(low[1],high[1]+1):
                            for z in range(low[2],high[2]+1):
                                for x in range(low[0],high[0]+1): rows.append({"pos":[x,y,z],"loaded":True,"id":"minecraft:air" if x==11 else "minecraft:stone","meta":0,"pickedItem":{"empty":True},"tileClass":None,
                                    **({"placementItem":{"id":"minecraft:stone","meta":4,"count":1,"name":"Stone"}} if x==10 else {})})
                        cursor=params["cursor"]; page=rows[cursor:cursor+params["limit"]]
                        return {"matches":page,"cursor":cursor+len(page),"done":cursor+len(page)==len(rows),"unloaded":0,"volume":len(rows)}
                    if method=="baritone.build_preview": return {"matches":False}
                    raise AssertionError(method)
                def assert_no_filter(self,params):
                    if "blocks" in params: raise AssertionError("copy scan must omit blocks")
            fake=Fake()
            with patch.object(tools,"kernel",return_value=fake):
                tools.mb_selection("clear")
                tools.mb_selection("add",{"pos1":[10,5,10],"pos2":[11,5,10]})
                tools.mb_selection("add",{"pos1":[13,5,10],"pos2":[13,5,10]})
                tools.mb_selection("copy",{"anchor":[9,5,9]})
                pasted=tools.mb_selection("paste",{"anchor":[20,7,20]})
            request=pasted["request"]
            self.assertEqual(request["origin"],[21,7,21])
            self.assertEqual([x["pos"] for x in request["cells"]],[[0,0,0],[1,0,0],[3,0,0]])
            self.assertEqual(request["cells"][0]["item"]["meta"],4)
            self.assertTrue(request["cells"][1]["clear"])
        finally: self._close(srv)


if __name__ == "__main__":
    unittest.main()
