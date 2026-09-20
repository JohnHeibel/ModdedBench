# SPDX-License-Identifier: LGPL-3.0-or-later
"""The tool table in PROMPT.md is generated; this fails when someone changes a tool and forgets to regenerate it."""
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "mcp"))
import tool_table  # noqa: E402


class ToolTableTests(unittest.TestCase):
    def test_prompt_table_matches_the_tool_docstrings(self):
        prompt = tool_table.PROMPT.read_text(encoding="utf-8")
        self.assertEqual(tool_table.render(prompt), prompt, "run: python harness/mcp/tool_table.py")

    def test_summary_is_the_first_sentence_on_one_line(self):
        self.assertEqual(tool_table.summary("Read a thing: a, b.\n    More here. And more.\n\n    Body."), "Read a thing: a, b")
        self.assertEqual(tool_table.summary("a | b"), "a / b")


if __name__ == "__main__": unittest.main()
