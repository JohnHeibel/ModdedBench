# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""codex_loop against a fake Codex command; no model is ever called."""
import contextlib, io, json, sys, tempfile, unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "runner"))
import codex_loop

# Replays plan.json[n] for the n-th call: {"events": [...], "exit": 0, "stop": false}; records argv and stdin.
FAKE = """import json, sys
from pathlib import Path
here = Path(__file__).parent; calls = here / "calls.json"
seen = json.loads(calls.read_text()) if calls.exists() else []
plan = json.loads((here / "plan.json").read_text()); step = plan[min(len(seen), len(plan) - 1)]
seen.append({"argv": sys.argv[1:], "stdin": sys.stdin.read()}); calls.write_text(json.dumps(seen))
print("not json")
for event in step.get("events", []): print(json.dumps(event))
if step.get("stop"): (here / ".state" / "STOP").write_text("")
sys.exit(step.get("exit", 0))
"""
def message(text): return {"type": "item.completed", "item": {"id": "item_1", "type": "agent_message", "text": text}}

class CodexLoopTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.repo = Path(self.tmp.name)
        (self.repo / "fake.py").write_text(FAKE); (self.repo / "PROMPT.md").write_text("the mission")
    def tearDown(self): self.tmp.cleanup()
    def loop(self, plan, **kw):
        (self.repo / "plan.json").write_text(json.dumps(plan))
        with contextlib.redirect_stdout(io.StringIO()):
            reason = codex_loop.run(self.repo, codex=[sys.executable, str(self.repo / "fake.py")], backoff_s=0, **kw)
        return reason, json.loads((self.repo / "calls.json").read_text())

    def test_captures_thread_id_resumes_and_stops_on_mission_complete_line(self):
        reason, calls = self.loop([
            {"events": [{"type": "thread.started", "thread_id": "T-1"}, message("not MISSION COMPLETE yet")]},
            {"events": [{"type": "thread.started", "thread_id": "T-2"}, message("claimed and verified\nMISSION COMPLETE\n")]}], extra=["-m", "x"])
        self.assertEqual("complete", reason); self.assertEqual(2, len(calls))
        self.assertEqual(["exec", "--json", "-C", str(self.repo), "-m", "x", "-"], calls[0]["argv"]); self.assertEqual("the mission", calls[0]["stdin"])
        self.assertEqual(["resume", "T-1", "-"], calls[1]["argv"][-3:]); self.assertEqual(codex_loop.CONTINUE, calls[1]["stdin"])
        self.assertEqual({"thread": "T-1"}, json.loads((self.repo / ".state" / "codex-loop.json").read_text()))
        self.assertIn("MISSION COMPLETE", (self.repo / ".state" / "codex-loop.log").read_text())
        # A restarted loop resumes the saved thread; session_id nested in a payload is also accepted.
        self.assertEqual("T-9", codex_loop._find_id({"type": "session_meta", "payload": {"session_id": "T-9"}}))
        (self.repo / "calls.json").unlink(); reason, calls = self.loop([{}], max_turns=1)
        self.assertEqual(("max_turns", ["resume", "T-1", "-"]), (reason, calls[0]["argv"][-3:]))

    def test_stop_file_ends_the_loop(self):
        reason, calls = self.loop([{"events": [{"type": "thread.started", "thread_id": "T-1"}], "stop": True}])
        self.assertEqual(("stop_file", 1), (reason, len(calls)))

    def test_three_consecutive_failures_stop_and_a_success_resets_the_count(self):
        reason, calls = self.loop([{"exit": 1}, {"exit": 1}, {"exit": 0}, {"exit": 1}])
        self.assertEqual(("failed", 6), (reason, len(calls)))
        self.assertFalse((self.repo / ".state" / "codex-loop.json").exists())  # no id was ever reported

if __name__ == "__main__": unittest.main()
