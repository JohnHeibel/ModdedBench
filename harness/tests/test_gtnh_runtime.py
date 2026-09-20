# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 Modbench contributors
"""Offline safety tests for the GTNH runtime supervisor."""
from __future__ import annotations
import sys
import tempfile
import unittest
import zipfile
import hashlib
from argparse import Namespace
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "launcher"))
import runtime


class RuntimeTests(unittest.TestCase):
    def _client_build_paths(self, root: Path) -> tuple[dict[str, str], Path]:
        instance = root / "instances" / runtime.INSTANCE_NAME
        mods = instance / ".minecraft" / "mods"
        mods.mkdir(parents=True)
        return {"prismData": str(root)}, mods

    def _write_client_build(self, root: Path, mods: Path, kind: str, source: bytes, target: bytes | None = None) -> None:
        libs = root / "gtnh" / kind / "build" / "libs"
        libs.mkdir(parents=True, exist_ok=True)
        (libs / f"modbench-{kind}-0.1.0.jar").write_bytes(source)
        if target is not None:
            (mods / f"modbench-{kind}.jar").write_bytes(target)

    def test_verify_client_build_rejects_stale_control_jar_without_writing(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); cfg, mods = self._client_build_paths(root)
            self._write_client_build(root, mods, "client", b"client", b"client")
            self._write_client_build(root, mods, "control", b"new-control", b"old-control")
            before = {path.name: path.read_bytes() for path in mods.iterdir()}
            old_repo, runtime.REPO = runtime.REPO, root
            try:
                with self.assertRaisesRegex(runtime.RuntimeError_, "install-control"):
                    runtime.verify_client_build(cfg, root)
            finally:
                runtime.REPO = old_repo
            self.assertEqual(before, {path.name: path.read_bytes() for path in mods.iterdir()})

    def test_verify_client_build_accepts_matching_mandatory_modules(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); cfg, mods = self._client_build_paths(root)
            self._write_client_build(root, mods, "client", b"client", b"client")
            self._write_client_build(root, mods, "control", b"control", b"control")
            old_repo, runtime.REPO = runtime.REPO, root
            try:
                runtime.verify_client_build(cfg, root)
            finally:
                runtime.REPO = old_repo

    def test_verify_client_build_allows_absent_optional_baritone(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); cfg, mods = self._client_build_paths(root)
            self._write_client_build(root, mods, "client", b"client", b"client")
            self._write_client_build(root, mods, "control", b"control", b"control")
            old_repo, runtime.REPO = runtime.REPO, root
            try:
                runtime.verify_client_build(cfg, root)
            finally:
                runtime.REPO = old_repo

    def test_verify_client_build_rejects_present_stale_baritone(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); cfg, mods = self._client_build_paths(root)
            self._write_client_build(root, mods, "client", b"client", b"client")
            self._write_client_build(root, mods, "control", b"control", b"control")
            self._write_client_build(root, mods, "baritone", b"new-baritone", b"old-baritone")
            old_repo, runtime.REPO = runtime.REPO, root
            try:
                with self.assertRaisesRegex(runtime.RuntimeError_, "install-baritone"):
                    runtime.verify_client_build(cfg, root)
            finally:
                runtime.REPO = old_repo

    def test_verify_client_build_accepts_a_complete_previously_joined_set(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); cfg, mods = self._client_build_paths(root)
            self._write_client_build(root, mods, "client", b"current-client", b"previous-client")
            self._write_client_build(root, mods, "control", b"current-control", b"previous-control")
            prior = {"client": runtime.sha256_file(mods / "modbench-client.jar"),
                     "control": runtime.sha256_file(mods / "modbench-control.jar")}
            runtime.save_json(root / "client-build-history.json", {"sets": [prior]})
            old_repo, runtime.REPO = runtime.REPO, root
            try:
                runtime.verify_client_build(cfg, root)
            finally:
                runtime.REPO = old_repo

    def test_verify_client_build_rejects_mix_of_previously_joined_sets(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); cfg, mods = self._client_build_paths(root)
            self._write_client_build(root, mods, "client", b"current-client", b"previous-client")
            self._write_client_build(root, mods, "control", b"current-control", b"current-control")
            runtime.save_json(root / "client-build-history.json", {"sets": [{
                "client": runtime.sha256_file(mods / "modbench-client.jar"),
                "control": hashlib.sha256(b"previous-control").hexdigest(),
            }]})
            old_repo, runtime.REPO = runtime.REPO, root
            try:
                with self.assertRaisesRegex(runtime.RuntimeError_, "install-client"):
                    runtime.verify_client_build(cfg, root)
            finally:
                runtime.REPO = old_repo

    def test_verify_client_build_rejects_missing_mandatory_component_even_if_history_has_set(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); cfg, mods = self._client_build_paths(root)
            self._write_client_build(root, mods, "client", b"client", b"client")
            self._write_client_build(root, mods, "control", b"control")
            runtime.save_json(root / "client-build-history.json", {"sets": [{
                "client": runtime.sha256_file(mods / "modbench-client.jar"), "control": "0" * 64,
            }]})
            old_repo, runtime.REPO = runtime.REPO, root
            try:
                with self.assertRaisesRegex(runtime.RuntimeError_, "install-control"):
                    runtime.verify_client_build(cfg, root)
            finally:
                runtime.REPO = old_repo

    def test_zip_member_rejects_traversal_and_strips_client_root(self):
        with tempfile.TemporaryDirectory() as tmp:
            archive = Path(tmp) / "pack.zip"
            with zipfile.ZipFile(archive, "w") as z:
                z.writestr("GT New Horizons 2.8.4/mods/ok.jar", b"ok")
                z.writestr("../escape.txt", b"no")
            with zipfile.ZipFile(archive) as z:
                with self.assertRaises(runtime.RuntimeError_):
                    runtime.safe_zip_members(z, strip_root=True)
            with zipfile.ZipFile(archive, "w") as z: z.writestr("GT New Horizons 2.8.4/mods/ok.jar", b"ok")
            with zipfile.ZipFile(archive) as z:
                members = runtime.safe_zip_members(z, strip_root=True)
            self.assertEqual(members[0][1].as_posix(), "mods/ok.jar")

    def test_zip_member_rejects_windows_escape_forms(self):
        with tempfile.TemporaryDirectory() as tmp:
            for name in ("root\\..\\escape.txt", "C:/escape.txt"):
                archive = Path(tmp) / "pack.zip"
                with zipfile.ZipFile(archive, "w") as z: z.writestr(name, b"no")
                with zipfile.ZipFile(archive) as z:
                    with self.assertRaises(runtime.RuntimeError_): runtime.safe_zip_members(z)

    def test_pack_archive_must_match_locked_name_size_and_hash(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); archive = root / "client.zip"; archive.write_bytes(b"known pack")
            lock = root / "pack.lock.json"
            lock.write_text('{"archives":[{"side":"client","file":"client.zip","bytes":10,"sha256":"' + hashlib.sha256(b"known pack").hexdigest() + '"}]}')
            runtime.verify_pack_archive(archive, "client", lock)
            archive.write_bytes(b"tampered")
            with self.assertRaises(runtime.RuntimeError_): runtime.verify_pack_archive(archive, "client", lock)

    def test_unrelated_prism_instance_is_never_accepted(self):
        with tempfile.TemporaryDirectory() as tmp:
            instance = Path(tmp) / runtime.INSTANCE_NAME; instance.mkdir(); (instance / "instance.cfg").write_text("name=Someone else's pack\n")
            with self.assertRaises(runtime.RuntimeError_): runtime.assert_managed_instance(instance)
            runtime.save_json(instance / runtime.MARKER, {"managedBy": "modbench"})
            runtime.assert_managed_instance(instance)

    def test_instance_configuration_preserves_pack_metadata(self):
        with tempfile.TemporaryDirectory() as tmp:
            instance = Path(tmp)
            (instance / "instance.cfg").write_text("name=Pack name\nComponentOverrides=forge:1.7.10\nOverrideMemory=false\n")
            runtime.write_instance_config(instance, r"C:\Program Files\Java\jdk-25\bin\java.exe", 6144)
            text = (instance / "instance.cfg").read_text()
            self.assertIn("ComponentOverrides=forge:1.7.10", text)
            self.assertIn("OverrideMemory=true", text)
            self.assertIn("JavaPath=C:/Program Files/Java/jdk-25/bin/java.exe", text)

    def test_client_game_directory_and_pause_option_use_dot_minecraft(self):
        with tempfile.TemporaryDirectory() as tmp:
            instance = Path(tmp); (instance / ".minecraft").mkdir()
            self.assertEqual(runtime.client_game_dir(instance), instance / ".minecraft")
            (instance / ".minecraft/options.txt").write_text("fov:0.5\npauseOnLostFocus:true\n")
            dream = instance / ".minecraft/config/DreamCoreMod.properties"
            dream.parent.mkdir(); dream.write_text("savedVersion=2.7.268\nshowConfirmExitWindow=true\n")
            runtime.set_client_options(runtime.client_game_dir(instance))
            text = (instance / ".minecraft/options.txt").read_text()
            self.assertIn("fov:0.5", text)
            self.assertIn("pauseOnLostFocus:false", text)
            text = dream.read_text()
            self.assertIn("savedVersion=2.7.268", text)
            self.assertIn("showConfirmExitWindow=false", text)

    def test_client_install_uses_game_mods_and_process_guard(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); instance = root / "instance"; (instance / ".minecraft/mods").mkdir(parents=True)
            runtime.save_json(instance / runtime.MARKER, {"managedBy": "modbench"})
            old_repo, runtime.REPO = runtime.REPO, root
            try:
                artifacts = root / "gtnh/client/build/libs"; artifacts.mkdir(parents=True)
                (artifacts / "modbench-client-0.1.0.jar").write_bytes(b"new")
                with patch.object(runtime, "instance_dir", return_value=instance), patch.object(runtime, "bridge_is_live", return_value=False), patch.object(runtime, "client_instance_is_running", return_value=False):
                    target = runtime.install_jar("client", {}, root)
                self.assertEqual(target, instance / ".minecraft/mods/modbench-client.jar")
                with patch.object(runtime, "instance_dir", return_value=instance), patch.object(runtime, "bridge_is_live", return_value=False), patch.object(runtime, "client_instance_is_running", return_value=True):
                    with self.assertRaises(runtime.RuntimeError_): runtime.install_jar("client", {}, root)
            finally: runtime.REPO = old_repo

    def test_control_and_baritone_use_the_same_managed_client_guard(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); instance = root / "instance"; (instance / ".minecraft/mods").mkdir(parents=True)
            runtime.save_json(instance / runtime.MARKER, {"managedBy": "modbench"})
            old_repo, runtime.REPO = runtime.REPO, root
            try:
                for kind in ("control", "baritone"):
                    libs = root / "gtnh" / kind / "build/libs"; libs.mkdir(parents=True, exist_ok=True)
                    (libs / f"modbench-{kind}-0.1.0.jar").write_bytes(kind.encode())
                with patch.object(runtime, "instance_dir", return_value=instance), patch.object(runtime, "bridge_is_live", return_value=False), patch.object(runtime, "client_instance_is_running", return_value=False):
                    for kind in ("control", "baritone"):
                        target = runtime.install_jar(kind, {}, root)
                        self.assertEqual(target, instance / f".minecraft/mods/modbench-{kind}.jar")
                with patch.object(runtime, "instance_dir", return_value=instance), patch.object(runtime, "bridge_is_live", return_value=False), patch.object(runtime, "client_instance_is_running", return_value=True):
                    with self.assertRaises(runtime.RuntimeError_): runtime.install_jar("control", {}, root)
            finally: runtime.REPO = old_repo

    def test_server_install_is_blocked_by_recorded_startup_process(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); server = root / "server"; server.mkdir()
            runtime.save_json(server / runtime.MARKER, {"managedBy": "modbench"})
            runtime.save_json(root / "server-process.json", {"pid": 1, "identity": "start"})
            with patch.object(runtime, "bridge_is_live", return_value=False), patch.object(runtime, "recorded_process_is_running", return_value=True):
                with self.assertRaises(runtime.RuntimeError_): runtime.install_jar("server", {}, root)

    def test_install_preserves_other_component_transaction(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); server = root / "server"; (server / "mods").mkdir(parents=True)
            runtime.save_json(server / runtime.MARKER, {"managedBy": "modbench"})
            runtime.save_json(root / "installed.json", {"client": {"target": "kept"}})
            old_repo, runtime.REPO = runtime.REPO, root
            try:
                artifacts = root / "gtnh/server/build/libs"; artifacts.mkdir(parents=True)
                (artifacts / "modbench-server-0.1.0.jar").write_bytes(b"new")
                with patch.object(runtime, "bridge_is_live", return_value=False), patch.object(runtime, "recorded_process_is_running", return_value=False):
                    runtime.install_jar("server", {}, root)
                self.assertEqual(runtime.load_json(root / "installed.json")["client"]["target"], "kept")
            finally: runtime.REPO = old_repo

    def test_rollback_refuses_tampered_transaction_target(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); (root / "backups").mkdir(); backup = root / "backups/modbench-server.previous.jar"; backup.write_bytes(b"old")
            runtime.save_json(root / "installed.json", {"server": {"target": str(root / "outside/modbench-server.jar"), "backup": str(backup)}})
            with patch.object(runtime, "bridge_is_live", return_value=False), patch.object(runtime, "recorded_process_is_running", return_value=False):
                with self.assertRaises(runtime.RuntimeError_): runtime.rollback_jar("server", root)

    def test_provision_launch_omits_offline_and_server(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); instance = root / "instances" / runtime.INSTANCE_NAME; instance.mkdir(parents=True)
            runtime.save_json(instance / runtime.MARKER, {"managedBy": "modbench"})
            runtime.save_json(root / "config.json", {"prism": sys.executable, "prismData": str(root), "java": sys.executable})
            with patch.object(runtime, "instance_dir", return_value=instance), patch.object(runtime.subprocess, "Popen") as popen:
                runtime.provision_client(Namespace(runtime=str(root)))
            command = popen.call_args.args[0]
            self.assertIn("-l", command)
            self.assertNotIn("--offline", command)
            self.assertNotIn("-s", command)

    def test_client_wait_connects_only_after_main_menu_and_reports_identity(self):
        class Kernel:
            def __init__(self): self.calls = []; self.gui_calls = 0
            def call(self, method, **params):
                self.calls.append((method, params))
                if method == "obs.gui":
                    self.gui_calls += 1
                    return {"class": "net.minecraft.client.gui.GuiMainMenu"}
                if method == "obs.world": return {"inWorld": True}
                if method == "obs.player": return {"name": "ModbenchDev", "uuid": "1234"}
                return {"connecting": True}
        kernel = Kernel()
        with patch.object(runtime, "open_client_kernel", return_value=kernel):
            joined = runtime.wait_for_client_join(1)
        self.assertEqual(joined["name"], "ModbenchDev")
        self.assertIn(("sys.connect", {"host": "127.0.0.1", "port": 25575}), kernel.calls)

    def test_client_wait_recognizes_observed_gtnh_custom_main_menu(self):
        class Kernel:
            def call(self, method, **params):
                if method == "obs.gui": return {"class": "lumien.custommainmenu.gui.GuiCustom"}
                if method == "obs.world": return {"inWorld": True}
                if method == "obs.player": return {"name": "ModbenchDev", "uuid": "1234"}
                return {"connecting": True}
        with patch.object(runtime, "open_client_kernel", return_value=Kernel()):
            self.assertTrue(runtime.wait_for_client_join(1)["joined"])

    def test_client_process_inspector_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = Namespace(returncode=1, stdout="false")
            with patch.object(runtime.subprocess, "run", return_value=result):
                self.assertTrue(runtime.client_instance_is_running(Path(tmp)))

    def test_invalid_explicit_java_does_not_fall_back_to_candidate(self):
        with self.assertRaises(runtime.RuntimeError_):
            runtime.resolve_executable("C:/definitely/not/java.exe", [sys.executable], "Java executable")

    def test_jar_install_creates_backup_and_rollback_restores_it(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); server = root / "server"; (server / "mods").mkdir(parents=True)
            runtime.save_json(server / runtime.MARKER, {"managedBy": "modbench"})
            target = server / "mods/modbench-server.jar"; target.write_bytes(b"old")
            old_repo, runtime.REPO = runtime.REPO, root
            try:
                artifact = root / "gtnh/server/build/libs"; artifact.mkdir(parents=True); (artifact / "modbench-server-0.1.0.jar").write_bytes(b"new")
                with patch.object(runtime, "bridge_is_live", return_value=False):
                    self.assertEqual(runtime.install_jar("server", {}, root).read_bytes(), b"new")
                with patch.object(runtime, "bridge_is_live", return_value=False):
                    self.assertEqual(runtime.rollback_jar("server", root).read_bytes(), b"old")
            finally: runtime.REPO = old_repo


if __name__ == "__main__": unittest.main()
