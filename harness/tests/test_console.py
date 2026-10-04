# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Offline tests for the operator console: the prompt it writes and who may press its buttons."""
from __future__ import annotations
import json, shutil, sys, threading, unittest, urllib.error, urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "harness" / "console"))
import console


def start(args, job=None):
    """The steps the console's Start would run, with nothing started: (steps, the shell line of the loop, its arguments)."""
    import types
    from unittest import mock
    c = object.__new__(console.Console); c.job = job or {"name": "", "running": False}
    c.supervisor = c.backups = types.SimpleNamespace(poll=lambda: None)  # both count as running: Start starts neither
    steps = []; c.run_job = lambda name, s: steps.extend(s)
    with mock.patch.object(console.subprocess, "Popen"), mock.patch.object(console, "sh"): c.act("agent.start", args)
    return steps, steps[-1][steps[-1].index("-c") + 1], steps[-1][steps[-1].index("-c") + 3:]


class ConsoleTests(unittest.TestCase):
    def test_start_asks_for_the_loop_lock_first_and_starts_the_loop_under_it(self):
        steps, loop, _ = start({})
        self.assertEqual(([console.LOOP_FREE], console.LOOP), (steps[-2][-1:], loop))
        self.assertLess(loop.index("flock -n .state/loop.lock"), loop.index("rm -f .state/STOP"))  # a start that is refused leaves the running loop's stop request

    @unittest.skipUnless(shutil.which("sh"), "runs the loop's shell line")
    def test_the_loop_line_passes_the_prompt_and_arguments_and_keeps_what_the_loop_writes_as_it_dies(self):
        import os, subprocess, tempfile
        with tempfile.TemporaryDirectory() as tmp:
            bin = Path(tmp, "bin"); bin.mkdir(); Path(tmp, ".state").mkdir(); Path(tmp, ".state", "STOP").write_text("")
            (bin / "flock").write_text('#!/bin/sh\necho "$1 $2" > flock.txt; shift 2; exec "$@"\n', newline="\n")  # the lock itself is flock's: only what it is asked is checked
            (bin / "python3").write_text('#!/bin/sh\nfor a in "$@"; do echo "$a"; done > argv.txt; ls .state > state.txt; echo trace >&2; exit 3\n', newline="\n")
            env = {**os.environ, "PATH": str(bin) + os.pathsep + os.environ["PATH"]}
            done = subprocess.run(["sh", "-c", console.LOOP, "sh", "--max-minutes", "5", "--", "-c", "two words"], cwd=tmp, env=env, capture_output=True, text=True)
            self.assertEqual((3, "", ""), (done.returncode, done.stdout, done.stderr))
            self.assertEqual("-n .state/loop.lock\n", Path(tmp, "flock.txt").read_text())
            self.assertEqual(["harness/runner/codex_loop.py", "--prompt", "PROMPT.md", "--max-minutes", "5", "--", "-c", "two words"], Path(tmp, "argv.txt").read_text().splitlines())
            self.assertNotIn("STOP", Path(tmp, "state.txt").read_text()); self.assertEqual("trace\n", Path(tmp, ".state", "codex-loop.err").read_text())

    @unittest.skipUnless(shutil.which("sh"), "runs the hold's shell lines")
    def test_pause_takes_the_hold_over_and_resume_ends_only_the_operators(self):
        import subprocess, tempfile
        from unittest import mock
        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(console.runtime, "HOLD", (Path(tmp) / "modbench-hold").as_posix()):
            hold = Path(tmp) / "modbench-hold"; c = object.__new__(console.Console)
            c.hold_file = lambda cmd: subprocess.run(["sh", "-c", cmd], capture_output=True, text=True)
            c.act("time.resume", {})  # nothing held: nothing to say
            c.act("time.pause", {}); self.assertEqual(hold.read_text().strip(), "operator")
            c.act("time.resume", {}); self.assertFalse(hold.exists())
            hold.write_text("backup")
            with self.assertRaisesRegex(RuntimeError, "still held by 'backup'"): c.act("time.resume", {})
            self.assertTrue(hold.exists())  # a snapshot in progress keeps its world still
            c.act("time.pause", {}); c.act("time.resume", {}); self.assertFalse(hold.exists())  # the operator's way out of a hold nobody ends

    def test_the_shipped_prompt_has_every_placeholder_the_console_fills(self):
        text = console.fill_prompt((REPO / "PROMPT.md").read_text(encoding="utf-8"), "Steam Macerator", "Tier 0.5 - Steam Age")
        self.assertIn('TARGET_QUEST      = "Steam Macerator"', text); self.assertIn('REPO              = "/work/modbench"', text)
        self.assertNotRegex(text, r'(?m)^(TARGET_|WORLD|REPO)\w*\s*=\s*"<')
        for bad in ("", 'a"b', "a\nb"):
            with self.assertRaises(ValueError): console.fill_prompt("x", bad, "chapter")

    def test_run_tokens_are_exact_and_count_an_older_thread_only_from_the_run_start(self):
        import subprocess, tempfile
        from datetime import datetime, timezone
        def total(ts, i, c, o): return json.dumps({"timestamp": ts, "type": "event_msg", "payload": {"type": "token_count",
                                                   "info": {"total_token_usage": {"input_tokens": i, "cached_input_tokens": c, "output_tokens": o}}}})
        with tempfile.TemporaryDirectory() as d:
            day = Path(d, "s", "2026", "10", "03"); day.mkdir(parents=True)
            (day / "rollout-a.jsonl").write_text("\n".join([json.dumps({"timestamp": "2026-10-03T10:00:00Z"}), total("2026-10-03T11:00:00Z", 100, 50, 10), total("2026-10-03T12:30:00Z", 300, 200, 40)]) + "\n")
            (day / "rollout-b.jsonl").write_text("\n".join([json.dumps({"timestamp": "2026-10-03T12:40:00Z"}), total("2026-10-03T13:00:00Z", 2000, 1800, 9)]) + "\n")
            Path(d, "run.json").write_text(json.dumps({"startedAt": datetime(2026, 10, 3, 12, tzinfo=timezone.utc).timestamp()}))
            out = subprocess.run([sys.executable, "-c", console.USAGE, str(Path(d, "s")), str(Path(d, "run.json"))], capture_output=True, text=True)
        self.assertEqual(json.loads(out.stdout), {"input": 2200, "cached": 1950, "output": 39})

    def test_actions_need_the_page_token_and_a_loopback_host(self):
        class Fake:
            def act(self, name, args): return {"accepted": name}
        console.Handler.console = Fake()
        server = ThreadingHTTPServer(("127.0.0.1", 0), console.Handler); threading.Thread(target=server.serve_forever, daemon=True).start()
        def post(headers):
            req = urllib.request.Request(f"http://127.0.0.1:{server.server_port}/api/action", data=b'{"name":"server.stop"}', headers=headers)
            try: return urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=5).status  # never through an environment proxy
            except urllib.error.HTTPError as e: return e.code
        try:
            self.assertEqual(post({}), 403)
            self.assertEqual(post({"X-Console-Token": console.TOKEN, "Host": "evil.example"}), 403)
            self.assertEqual(post({"X-Console-Token": console.TOKEN}), 200)
        finally: server.shutdown(); server.server_close()

    def test_long_goals_are_shortened_off_the_request_path_and_only_with_a_key(self):
        import io, os, tempfile, time
        from unittest import mock
        asked = []
        def fake(request, timeout):
            asked.append(json.loads(request.data)); return io.BytesIO(json.dumps({"choices": [{"message": {"content": '"Mine copper for the smelter"'}}]}).encode())
        long = "Mine thirty-two copper ore from the vein north of the base so that the second alloy smelter can be built beside the first"
        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(console.urllib.request, "urlopen", fake):
            with mock.patch.dict(os.environ, {"OPENROUTER_API_KEY": ""}):
                self.assertIsNone(console.Shortener(Path(tmp) / "s.json").get("goal", long)); self.assertEqual([], asked)
            with mock.patch.dict(os.environ, {"OPENROUTER_API_KEY": "k"}):
                short = console.Shortener(Path(tmp) / "s.json")
                self.assertIsNone(short.get("goal", "Mine copper")); self.assertIsNone(short.get("goal", long))  # short enough; then asked, not waited for
                for _ in range(100):
                    if short.get("goal", long): break
                    time.sleep(0.02)
                self.assertEqual("Mine copper for the smelter", short.get("goal", long)); self.assertEqual(1, len(asked)); self.assertEqual(long, asked[0]["messages"][1]["content"])
                self.assertEqual("Mine copper for the smelter", console.Shortener(Path(tmp) / "s.json").get("goal", long))  # kept on disk

    def test_the_compaction_guard_does_not_hold_while_a_background_task_has_the_body(self):
        import tempfile, types
        from unittest import mock
        class Done(BaseException): pass
        def guard(live):
            c = object.__new__(console.Console); c.guard, c.guard_done = None, None
            held = []
            c.hold_file = lambda cmd: held.append(cmd) or types.SimpleNamespace(returncode=0 if "echo" in cmd else 1)
            c.agent_sh = lambda cmd: types.SimpleNamespace(stdout="3")
            c.call = lambda method, **kw: {"state": {"paused": False, "held": False}}
            c.compacting = lambda status: True
            ticks = iter(range(2))
            stop = lambda s: next(ticks, None) is None and (_ for _ in ()).throw(Done())
            with tempfile.TemporaryDirectory() as tmp, mock.patch.object(console, "OVERLAY", Path(tmp)), mock.patch.object(console.time, "sleep", stop), mock.patch("builtins.print"):
                (Path(tmp) / "live.json").write_text(json.dumps(live), encoding="utf-8")
                with self.assertRaises(Done): c.compaction_guard()
            return [cmd for cmd in held if "echo" in cmd], c.guard
        status = {"state": "thinking", "since": 1.0}
        self.assertEqual(guard({"status": status, "body": {"task": "t1"}}), ([], None))  # the task works through the compaction
        holds, state = guard({"status": status, "body": None})  # once it has ended, the guard holds
        self.assertEqual((len(holds), state[0]), (1, 3))


if __name__ == "__main__":
    unittest.main()
