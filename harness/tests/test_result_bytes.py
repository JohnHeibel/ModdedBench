# SPDX-License-Identifier: LGPL-3.0-or-later
"""What a result costs the model: every tool answers in one compact shape, and says a thing once."""
import asyncio
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "mcp"))
import mbtool  # noqa: E402
import server  # noqa: E402
from mcp.types import CallToolResult, ImageContent, TextContent  # noqa: E402


class FakeKernel:
    connected = True

    def __init__(self, reply): self.reply, self.calls = reply, []

    def call(self, method, **params): self.calls.append((method, params)); return self.reply(method, params)

    def close(self): pass


def use(case, reply):
    k = FakeKernel(reply); ctx = patch.dict(mbtool.state, {"kernel": k}); ctx.start(); case.addCleanup(ctx.stop)
    return k


class CompactResultTests(unittest.TestCase):
    def setUp(self):
        tasks = tempfile.TemporaryDirectory(); self.addCleanup(tasks.cleanup)  # never the live run's background tasks
        env = patch.dict(os.environ, {"MB_TASKS_DIR": tasks.name}); env.start(); self.addCleanup(env.stop)
        self.srv = server.Server(); self.addCleanup(self.srv.close); self.addCleanup(mbtool.install_package)
        self.assertFalse(any(m.startswith("ERROR") for m in self.srv.check_reload(force=True)), self.srv.error)

    def test_every_result_is_json_without_whitespace_whatever_built_it(self):
        reply = {"pos": [1.5, 64.0, -2.5], "held": {"id": "minecraft:stone", "name": "Stein §7ä"}, "effects": []}
        tight = json.dumps(reply, separators=(",", ":"), ensure_ascii=False)
        use(self, lambda method, params: [reply, reply] if method == "sys.methods" else {"png": "cA=="} if method == "sys.screenshot" else reply)
        text = lambda name, args: [c.text for c in asyncio.run(self.srv.call_tool(name, args)).content if isinstance(c, TextContent)]
        self.assertEqual(text("mb_obs", {"method": "player"}), [tight])                    # a tool's dict, through FastMCP
        self.assertEqual(text("mb_call", {"method": "sys.methods"}), [tight, tight])       # a list: one block each
        self.assertEqual(text("mb_recipe_inspect", {"x": 1, "y": 2}), [tight])             # a tool's own CallToolResult
        failed = asyncio.run(self.srv.call_tool("mb_keys", {"method": "no such"}))
        self.assertTrue(failed.isError); self.assertEqual(failed.content[0].text, '{"ok":false,"error":{"code":"bad_request","msg":"method must be list or press"}}')
        one = CallToolResult(content=[TextContent(type="text", text=tight)])
        self.assertEqual(server._with_fields(one, {"bodyBusy": True}).content[0].text, tight[:-1] + ',"bodyBusy":true}')  # the same with a task running

    def test_text_that_is_not_json_and_images_pass_untouched(self):
        blocks = [TextContent(type="text", text="{ not json"), TextContent(type="text", text="plain\n  text"), ImageContent(type="image", data="cA==", mimeType="image/png")]
        self.assertEqual(server._compact(blocks), blocks)


if __name__ == "__main__": unittest.main()
