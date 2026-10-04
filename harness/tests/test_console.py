# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Offline tests for the operator console: the prompt it writes and who may press its buttons."""
from __future__ import annotations
import json, sys, threading, unittest, urllib.error, urllib.request
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
    def test_start_keeps_what_the_loop_writes_as_it_dies(self):
        _, loop, _ = start({})
        self.assertIn("mkdir -p .state", loop); self.assertTrue(loop.endswith(">/dev/null 2>>.state/codex-loop.err"))

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
