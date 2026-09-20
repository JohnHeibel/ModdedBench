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

    def test_core_can_be_installed_on_the_client_alone(self):
        self.assertEqual(runtime.component_sides("core"), ["client", "server"])
        self.assertEqual(runtime.component_sides("core", "client"), ["client"])
        with self.assertRaises(runtime.RuntimeError_): runtime.component_sides("server", "client")


if __name__ == "__main__":
    unittest.main()
