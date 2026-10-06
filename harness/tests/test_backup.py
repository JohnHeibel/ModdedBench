# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Offline tests for the operator's snapshots: docker is never run, its command lines are only looked at."""
from __future__ import annotations
import contextlib, gzip, io, shutil, subprocess, sys, tarfile, tempfile, unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "launcher"))
import backup


class BackupTests(unittest.TestCase):
    def test_one_failed_snapshot_does_not_end_the_loop(self):
        class Done(BaseException): pass
        rounds = iter([OSError("docker is not answering"), None, Done()])
        def snapshot():
            step = next(rounds)
            if step: raise step
        with patch.object(backup, "snapshot", snapshot), patch.object(backup.time, "sleep"), contextlib.redirect_stderr(io.StringIO()) as err:
            with self.assertRaises(Done): backup.loop(30)
        self.assertIn("no snapshot: OSError: docker is not answering", err.getvalue())

    def test_a_snapshot_holds_the_world_as_its_own_and_leaves_another_hold_alone(self):
        def snapshot(free):
            ran = []
            def sh(*args, to=None):
                ran.append(args[-1])
                if to: to.write_bytes(b"x")
                return free or args[-1] != backup.runtime.hold_cmd("backup", True)
            with tempfile.TemporaryDirectory() as tmp, patch.object(backup, "OUT", Path(tmp)), patch.object(backup, "sh", sh), patch.object(backup.time, "sleep"), patch("builtins.print"):
                self.assertIsNotNone(backup.snapshot())
            return ran
        take, release = backup.runtime.hold_cmd("backup", True), backup.runtime.hold_cmd("backup", False)
        work = [backup.BUNDLE, backup.BARITONE, backup.STATE]  # the agent's commits (the submodule's are apart) and loop state, read once the world runs again
        self.assertEqual(snapshot(free=True), [take, backup.WORLD, backup.NOTES, release, *work])
        self.assertEqual(snapshot(free=False), [take, backup.WORLD, backup.NOTES, *work])  # the operator's or the guard's hold was in force: it is not this snapshot's to end

    def test_a_snapshot_that_fails_says_what_docker_said_and_leaves_no_folder(self):
        ran = []
        def run(cmd, **kw):
            ran.append(cmd[cmd.index("-T") + 1:])
            return SimpleNamespace(returncode=1, stderr=b'service "server" is not running')
        with tempfile.TemporaryDirectory() as tmp, patch.object(backup, "OUT", Path(tmp)), patch.object(backup.subprocess, "run", run), \
             patch.object(backup.time, "sleep"), contextlib.redirect_stderr(io.StringIO()) as err:
            self.assertIsNone(backup.snapshot()); self.assertEqual([], list(Path(tmp).iterdir()))
            with patch.object(backup, "sh", side_effect=OSError("no docker")), self.assertRaises(OSError): backup.snapshot()
            self.assertEqual([], list(Path(tmp).iterdir()))  # an empty folder would count as a snapshot when the old ones are pruned
        self.assertIn('world.tar.gz: service "server" is not running', err.getvalue())

    @unittest.skipUnless(shutil.which("sh") and shutil.which("tar"), "runs the archive's tar line")
    def test_the_world_archive_leaves_out_the_pack_and_the_hold_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            for name in ("world/level.dat", "mods/pack.jar", "logs/latest.log", "forge.jar", "modbench-hold", "server.properties"):
                Path(tmp, name).parent.mkdir(exist_ok=True); Path(tmp, name).write_text("x")
            done = subprocess.run(["sh", "-c", backup.WORLD.replace("/data", Path(tmp).as_posix())], capture_output=True)
            with tarfile.open(fileobj=io.BytesIO(done.stdout)) as tar: names = {n for n in tar.getnames() if n != "."}
        self.assertEqual(names, {"./world", "./world/level.dat", "./server.properties"})

    @unittest.skipUnless(shutil.which("sh") and shutil.which("tar") and shutil.which("git"), "runs the bundle and state lines")
    def test_a_snapshot_holds_the_agents_commits_and_its_state_without_the_logs(self):
        with tempfile.TemporaryDirectory() as tmp:
            git = lambda *a, cwd=tmp: subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t.invalid", *a], cwd=cwd, capture_output=True, text=True, check=True).stdout
            git("init", "-q"); Path(tmp, "tool.py").write_text("x"); git("add", "tool.py"); git("commit", "-qm", "the agent's fix"); git("branch", "side")
            for name in ("codex-loop.json", "run.json", "codex-loop.log", "codex-loop.err", "loop.lock", "tasks/t1.json", "tasks/t1.log", "notes/w.lock", "notes/w/camp.md", "notes/w/camp.json", "notes/w/auto/auto-mine.md", "notes/w/.git/HEAD", "notes/old.sqlite3"):
                Path(tmp, ".state", name).parent.mkdir(parents=True, exist_ok=True); Path(tmp, ".state", name).write_text("x")
            out = lambda line: subprocess.run(["sh", "-c", line], cwd=tmp, capture_output=True, check=True).stdout
            Path(tmp, "agent.bundle").write_bytes(out(backup.BUNDLE)); heads = git("bundle", "list-heads", "agent.bundle")
            self.assertIn("refs/heads/side", heads); self.assertIn(git("rev-parse", "HEAD").strip(), heads)
            with tarfile.open(fileobj=io.BytesIO(out(backup.STATE))) as tar: names = {n for n in tar.getnames() if n != "."}
            with tarfile.open(fileobj=io.BytesIO(out(backup.NOTES))) as tar: notes = {m.name for m in tar.getmembers() if m.isfile()}
        self.assertEqual(names, {"./codex-loop.json", "./run.json", "./tasks", "./tasks/t1.json"})
        self.assertEqual(notes, {"notes/w/camp.md", "notes/w/camp.json", "notes/w/auto/auto-mine.md", "notes/w/.git/HEAD", "notes/old.sqlite3"})  # the note files, their history, and a database not yet exported

    def test_a_restore_checks_the_archives_before_it_stops_or_deletes_anything(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(backup, "OUT", Path(tmp)), patch.object(backup.subprocess, "run") as run, patch("builtins.print"):
            folder = Path(tmp) / "20261003-120000"; folder.mkdir()
            (folder / "world.tar.gz").write_bytes(gzip.compress(b"world" * 1000)); (folder / "notes.tar.gz").write_bytes(gzip.compress(b"notes" * 1000)[:-20])
            with self.assertRaisesRegex(SystemExit, "notes.tar.gz is damaged"): backup.restore(folder, "disk fault")
            run.assert_not_called()
            (folder / "notes.tar.gz").write_bytes(gzip.compress(b"notes"))
            backup.restore(Path(folder.name), "disk fault")
            stop, world, notes = [c.args[0] for c in run.call_args_list]
            self.assertEqual(stop[-3:], ["stop", "server", "agent"])
            for cmd in (world, notes): self.assertIn("alpine:3.20", cmd); self.assertNotIn("alpine", cmd)  # the image the build pinned
            self.assertTrue(world[-1].endswith("tar -xzf /b/world.tar.gz && rm -f modbench-hold"))  # an older archive's hold file is not restored
            self.assertIn('"snapshot": "20261003-120000"', (Path(tmp) / "restores.jsonl").read_text())


if __name__ == "__main__":
    unittest.main()
