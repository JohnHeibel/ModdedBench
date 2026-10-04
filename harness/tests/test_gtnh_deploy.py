# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Offline tests for the constrained client deploy path."""
from __future__ import annotations
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "launcher"))
import deploy, runtime


def jar(path: Path) -> None:
    with zipfile.ZipFile(path, "w") as z: z.writestr("mcmod.info", "[]")


class DeployTests(unittest.TestCase):
    def test_a_deploy_ends_a_background_task_after_its_wait(self):
        sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "mcp"))
        import mbtool  # noqa: F401
        from mbtools_gtnh import tasks
        ended = {"task": "t1", "state": "cancelled", "ended": "deploy"}
        with patch.object(tasks, "end_all", return_value=ended) as end_all, patch("builtins.print"):
            self.assertEqual(deploy.end_task(wait=7)["ended"], "deploy")
        end_all.assert_called_once_with("deploy", 7)

    def test_only_the_three_client_jars_are_accepted(self):
        with tempfile.TemporaryDirectory() as tmp:
            box = Path(tmp) / "box"; box.mkdir(); jar(box / "modbench-client.jar"); (box / "modbench-core.jar").write_bytes(b"not a zip")
            for components in (["server"], ["client", "client"], [], "client", ["../evil"]):
                with self.assertRaises(runtime.RuntimeError_): deploy.accept({"components": components}, box, Path(tmp) / "a")
            with self.assertRaises(runtime.RuntimeError_): deploy.accept({"components": ["core"]}, box, Path(tmp) / "b")
            jars = deploy.accept({"components": ["client"], "id": "x"}, box, Path(tmp) / "c")
            self.assertEqual(list(jars), ["client"]); self.assertTrue((Path(tmp) / "c" / "request.json").is_file())

    def test_a_client_that_does_not_join_is_rolled_back_and_the_server_side_is_never_named(self):
        calls = []
        def launch(args):
            calls.append("launch")
            if calls.count("launch") == 1: raise runtime.RuntimeError_("did not join")
        with patch.object(runtime, "load_config", return_value={}), patch.object(runtime, "instance_dir", return_value=Path(".")), \
             patch.object(runtime, "client_instance_is_running", return_value=False), patch.object(runtime, "launch_client", launch), \
             patch.object(runtime, "install_jar", lambda kind, cfg, root, only, source: calls.append(("install", kind, only))), \
             patch.object(runtime, "rollback_jar", lambda kind, root, only: calls.append(("rollback", kind, only))):
            result = deploy.deploy({"client": Path("c.jar"), "core": Path("k.jar")}, Path("."), 1)
        self.assertEqual(result, {"ok": False, "error": "did not join", "rolledBack": ["core", "client"]})
        self.assertEqual(calls, [("install", "core", "client"), ("install", "client", "client"), "launch",
                                 ("rollback", "client", "client"), ("rollback", "core", "client"), "launch"])

    def test_a_client_that_does_not_stop_in_time_is_stopped_again_and_relaunched_as_it_was(self):
        calls = []
        def stop(args):
            calls.append("stop")
            if calls.count("stop") == 1: raise runtime.RuntimeError_("shutdown requested but client is still live")
        with patch.object(runtime, "load_config", return_value={}), patch.object(runtime, "instance_dir", return_value=Path(".")), \
             patch.object(runtime, "client_instance_is_running", return_value=True), patch.object(runtime, "stop_client", stop), \
             patch.object(runtime, "launch_client", lambda args: calls.append("launch")), patch.object(runtime, "install_jar", lambda *a: calls.append("install")):
            result = deploy.deploy({"client": Path("c.jar")}, Path("."), 1)
        self.assertEqual((result, calls), ({"ok": False, "error": "shutdown requested but client is still live", "rolledBack": []}, ["stop", "stop", "launch"]))

    def test_a_request_is_claimed_by_one_supervisor_and_a_second_supervisor_does_not_start(self):
        import json, os
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, {"MODBENCH_OUTBOX": str(Path(tmp) / "box")}), patch("builtins.print"), \
             patch.object(deploy, "accept", return_value={}), patch.object(deploy, "deploy", return_value={"ok": True}) as deployed:
            box = Path(tmp) / "box"; box.mkdir(); args = SimpleNamespace(runtime=tmp, timeout=1, once=True)
            runtime.save_json(box / "request.json", {"id": "a", "components": ["client"]})
            self.assertTrue(deploy.claim(box)); self.assertFalse(deploy.claim(box))  # the second supervisor finds nothing to deploy
            with self.assertRaisesRegex(runtime.RuntimeError_, "already waiting"): deploy.request(SimpleNamespace(components=["client"], reason="", timeout=1))
            self.assertEqual(deploy.serve(args), 0)  # a claim left by a supervisor that died is served by the next one
            self.assertEqual((json.loads((box / "result.json").read_text())["id"], deployed.call_count, sorted(p.name for p in box.iterdir())), ("a", 1, ["result.json"]))
            held = runtime.only_one("deploy-supervisor", Path(tmp))
            with self.assertRaisesRegex(runtime.RuntimeError_, "another deploy supervisor"): deploy.serve(args)
            held.close()

    def test_core_can_be_installed_on_the_client_alone(self):
        self.assertEqual(runtime.component_sides("core"), ["client", "server"])
        self.assertEqual(runtime.component_sides("core", "client"), ["client"])
        with self.assertRaises(runtime.RuntimeError_): runtime.component_sides("server", "client")


if __name__ == "__main__":
    unittest.main()
