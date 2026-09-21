# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""codex_loop against a fake Codex command; no model is ever called."""
import contextlib, io, json, os, sys, tempfile, unittest
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
        # The agent's own container sets MODBENCH_OUTBOX to the folder a live run's overlay is written to:
        # a test run there would overwrite the feed a viewer is watching, and then not find its own.
        self.outbox = os.environ.pop("MODBENCH_OUTBOX", None)
    def tearDown(self):
        if self.outbox is not None: os.environ["MODBENCH_OUTBOX"] = self.outbox
        self.tmp.cleanup()
    def loop(self, plan, **kw):
        (self.repo / "plan.json").write_text(json.dumps(plan))
        with contextlib.redirect_stdout(io.StringIO()):
            reason = codex_loop.run(self.repo, codex=[sys.executable, str(self.repo / "fake.py")], backoff_s=0, **{"ready": lambda: True, **kw})
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

    def test_twelve_consecutive_failures_stop_and_a_success_resets_the_count(self):
        reason, calls = self.loop([{"exit": 1}, {"exit": 1}, {"exit": 0}, {"exit": 1}])
        self.assertEqual(("failed", 15), (reason, len(calls)))
        self.assertFalse((self.repo / ".state" / "codex-loop.json").exists())  # no id was ever reported

    def test_no_turn_starts_while_the_game_is_down(self):
        answers = iter([False, False, True])
        reason, calls = self.loop([{}], max_turns=1, ready=lambda: next(answers))
        self.assertEqual(("max_turns", 1), (reason, len(calls)))
        self.assertEqual(1, (self.repo / ".state" / "codex-loop.log").read_text().count("waiting for the game"))
        (self.repo / ".state" / "STOP").write_text(""); (self.repo / "calls.json").unlink()
        self.assertEqual("stop_file", codex_loop.run(self.repo, codex=["never-run"], backoff_s=0, ready=lambda: False))

    def test_the_loop_leaves_a_feed_and_totals_for_the_overlay(self):
        def call(tool, args, result, status="completed"):
            return {"type": "item.completed", "item": {"type": "mcp_tool_call", "tool": tool, "arguments": args, "status": status, "error": None,
                                                       "result": {"content": [{"type": "text", "text": json.dumps(result)}]}}}
        goal = {"chapter": "Stone Age", "quest": "Your First Night", "subgoal": "get eight dirt", "serves": ""}
        self.loop([{"events": [{"type": "turn.started"}, message("I will start with dirt."), call("mb_goal", {"subgoal": "get eight dirt"}, goal),
                               call("mb_craft", {"times": 2}, {"crafted": {"id": "minecraft:stick", "name": "Stick"}, "gained": 8}),
                               call("mb_scan", {"budget": 9}, {"ok": False}, "failed"), call("mb_quest_claim", {"quest_id": "q"}, {"accepted": True}),
                               call("mb_made_up", {"x": 1}, "not a dict"), {"type": "item.started", "item": {"type": "mcp_tool_call", "tool": "mb_wait", "arguments": {}}},
                               {"type": "turn.completed", "usage": {"input_tokens": 100, "cached_input_tokens": 60, "output_tokens": 7}}]}], max_turns=1)
        folder = self.repo / ".state" / "overlay"; live = json.loads((folder / "live.json").read_text())
        feed = [(x["kind"], x["text"]) for x in map(json.loads, (folder / "feed.jsonl").read_text(encoding="utf-8").splitlines())]
        self.assertEqual([("say", "I will start with dirt."), ("tool", "goal: get eight dirt"), ("tool", "crafted 8 × Stick"), ("fail", "looking for blocks: failed"),
                          ("mark", "quest claimed: Your First Night"), ("tool", "made up 1")], feed)
        self.assertEqual((goal, "ended", 1, 5, 1, 1, 107), (live["goal"], live["status"]["state"], live["stats"]["turns"], live["stats"]["calls"], live["stats"]["failed"],
                                                           live["stats"]["claims"], live["stats"]["tokens"]["input"] + live["stats"]["tokens"]["output"]))

if __name__ == "__main__": unittest.main()
