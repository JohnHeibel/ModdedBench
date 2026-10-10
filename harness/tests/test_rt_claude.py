# SPDX-License-Identifier: MIT
# Copyright (c) 2026 ModdedBench contributors
"""rt_claude against streams recorded from Claude Code 2.1.284 (fixtures/claude: paths, signatures and long texts cut)."""
import json, sys, tempfile, unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "runner"))
import rt_claude
from feed import Feed

def events(name, feed=None):
    """The fixture through one parser, as the loop does it: the events, and the parser for its totals."""
    p, out = rt_claude.Parser(), []
    for line in (Path(__file__).parent / "fixtures" / "claude" / f"{name}.jsonl").read_text(encoding="utf-8").splitlines():
        for ev in p.events(json.loads(line)):
            out.append(ev)
            if feed: feed.event(ev)
    return out, p

class ClaudeRuntimeTests(unittest.TestCase):
    def test_the_command_line_carries_the_brief_the_tools_and_the_fairness_rules(self):
        cmd = rt_claude.command(["claude"], "/work/modbench", "/brief/PROMPT.md", "S-1", "sonnet", "high", ["--x"])
        self.assertEqual(["claude", "-p", "--output-format", "stream-json", "--verbose", "--append-system-prompt-file", "/brief/PROMPT.md",
                          "--mcp-config", str(Path("/work/modbench/docker/claude-mcp.json")), "--strict-mcp-config", "--permission-mode", "bypassPermissions",
                          "--tools", "Bash,Read,Edit,Write,Glob,Grep", "--disallowedTools", "WebSearch,WebFetch,Task,Agent", "--disable-slash-commands",
                          "--model", "sonnet", "--effort", "high", "--x", "--resume", "S-1"], cmd)
        self.assertNotIn("--bare", cmd)  # it would ignore the subscription login
        self.assertEqual(rt_claude.command(["c"], "r", "p")[1:], rt_claude.command(["c"], "r", "p", "S")[1:-2])  # a resume repeats every flag
        self.assertGreater(int(rt_claude.ENV["MCP_TOOL_TIMEOUT"]), 900_000)  # mb_wait may take 15 minutes
        self.assertEqual(("false", "1"), (rt_claude.ENV["ENABLE_TOOL_SEARCH"], rt_claude.ENV["CLAUDE_CODE_DISABLE_AUTO_MEMORY"]))
        server = json.loads((Path(__file__).resolve().parents[2] / "docker" / "claude-mcp.json").read_text(encoding="utf-8"))["mcpServers"]
        self.assertEqual((["moddedbench"], ["-u", "/work/modbench/harness/mcp/server.py"]), (list(server), server["moddedbench"]["args"]))  # the name the parser strips: mcp__moddedbench__

    def test_tool_calls_results_and_what_it_says_become_the_loops_events(self):
        got, p = events("tools")
        self.assertEqual([("thread", "411f7c5c-b596-4caa-9133-1f41d1759fbf"), ("turn_start", None)], [(e["type"], e.get("id")) for e in got[:2]])
        self.assertEqual([("tool_start", "mb_pic"), ("tool_end", "mb_pic"), ("tool_start", "mb_nap"), ("tool_end", "mb_nap")], [(e["type"], e["tool"]) for e in got if "tool" in e])
        pic = next(e for e in got if e["type"] == "tool_end")
        self.assertEqual(({"caption": "a square", "secret": 4711}, "image", False), (json.loads(pic["content"][0]["text"]), pic["content"][1]["type"], pic["failed"]))
        self.assertTrue(pic["content"][1]["data"].startswith("iVBOR"))  # where the feed's pop-ups look for a screenshot
        self.assertEqual(["The dominant colour is red and the secret number is 4711."], [e["text"] for e in got if e["type"] == "say"])
        # One usage per model call though each content block repeats it; cached input counts as input, as the budget counts it.
        calls = [e for e in got if e["type"] == "usage" and e.get("context")]
        self.assertEqual([(7537, 0), (8060, 7528), (8220, 8052)], [(e["input"], e["cached"]) for e in calls])
        end = got[-1]; self.assertEqual(("turn_end", 0, 0), (end["type"], end["usage"]["input"], end["usage"]["cached"]))
        self.assertEqual(497, p.sum["output"] + end["usage"]["output"])  # the estimate, corrected to the result line's count
        self.assertEqual({"type": "usage", "window": 200000}, got[-2])

    def test_a_failed_call_a_result_put_in_a_file_and_a_killed_turn(self):
        self.assertEqual([True, True], [e["failed"] for e in events("timeout")[0] if e["type"] == "tool_end"])
        [big] = [e for e in events("big")[0] if e["type"] == "tool_end"]
        self.assertTrue(big["content"][0]["text"].startswith("<persisted-output>"))  # over 50k characters: Claude Code hands the model a file
        cut = events("cut")[0]
        self.assertEqual(["thread", "turn_start", "usage", "tool_start"], [e["type"] for e in cut])  # no result line: the usage so far is already counted

    def test_compaction_is_said_as_it_starts_and_ends_and_the_feed_counts_it(self):
        got, _ = events("compact_auto")
        marks = [(e.get("done", False), e.get("context")) for e in got if e["type"] == "compacting"]
        self.assertEqual([(False, None), (True, None), (True, 12361)], marks[:3]); self.assertEqual(9, len(marks))  # three automatic compactions
        self.assertEqual([84721, 83401, 83638], [e["input"] for e in got if e["type"] == "usage" and "context" not in e and "window" not in e])  # the summary calls
        self.assertEqual(0, got[-1]["usage"]["input"])  # the result line's totals leave the summaries out; the correction does not undo them
        with tempfile.TemporaryDirectory() as d:
            f = Feed(Path(d)); seen = []
            real = f.event; f.event = lambda e: (real(e), seen.append(f.live.get("compacting")))[0]
            events("compact_auto", f)
            self.assertEqual((3, False, True), (f.live["stats"]["compactions"], f.live["compacting"], True in seen))
            self.assertEqual({"context": 72216, "window": 200000}, f.live["context"])
            t = f.live["stats"]["tokens"]; self.assertEqual((533821 + 84721 + 83401 + 83638, 334885, 912, 0), (t["input"], t["cached"], t["output"], t["estimated"]))
            self.assertEqual(9, f.live["stats"]["calls"]); self.assertEqual(t["input"], f.billed())
        self.assertEqual([(False, None), (True, None), (True, 922)], [(e.get("done", False), e.get("context")) for e in events("compact_manual")[0] if e["type"] == "compacting"])

    def test_its_own_tools_show_as_shell_work(self):
        p = rt_claude.Parser()
        use = lambda i, name, args: {"type": "assistant", "session_id": "S", "message": {"id": i, "usage": {"input_tokens": 9}, "content": [{"type": "tool_use", "id": i, "name": name, "input": args}]}}
        done = lambda i: {"type": "user", "message": {"content": [{"type": "tool_result", "tool_use_id": i, "content": "ok"}]}}
        got = [e for raw in (use("a", "Bash", {"command": "python3 harness/launcher/deploy.py request client"}), done("a"), use("b", "Edit", {"file_path": "harness/tools/x.py"}), done("b")) for e in p.events(raw)]
        self.assertEqual([("python3 harness/launcher/deploy.py request client", None), ("python3 harness/launcher/deploy.py request client", True), ("Edit harness/tools/x.py", None), ("Edit harness/tools/x.py", True)],
                         [(e["command"], e.get("done")) for e in got if e["type"] == "shell"])

if __name__ == "__main__": unittest.main()
