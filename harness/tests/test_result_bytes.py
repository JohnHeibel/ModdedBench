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


NOTE = "Alternative examples are previews. Fetch detail=full with this exact handlerKey and index before choosing ingredients; NBT is omitted from previews."
SHAPED, LOOT = "codechicken.nei.recipe.ShapedRecipeHandler|crafting|Shaped Crafting", "enhancedlootbags|enhancedlootbags|LootBag"


def summaries(key, materials):
    """nei.recipes summaries as NeiAccess sends them: a wrench per material, one handler."""
    one = lambda id, name: {"alternativeCount": 1, "exampleOffset": 0, "examples": [{"id": id, "meta": 0, "name": name, "count": 1}]}
    hammers = {"alternativeCount": 2, "exampleOffset": 0, "examples": [{"id": "gt:tool", "meta": 12, "name": "Hammer", "count": 1}, {"id": "ggfab:d1", "meta": 4, "name": "Single Use Hard Hammer", "count": 1}]}
    return [{"handler": key.split("|")[0], "handlerKey": key, "name": key.split("|")[2], "index": i, "nativeRecipesPerPage": 2,
             "catalysts": [one("minecraft:crafting_table", "Crafting Table")] * 9, "inputs": [one("gt:plate", f"{m} Plate"), hammers, one("gt:ingot", f"{m} Ingot")],
             "result": one("gt:tool", "Wrench"), "other": [], "structuredCoverage": "native ingredient layout; use nei.view for custom drawn requirements",
             "detailsRequired": True, "summaryNote": NOTE} for i, m in enumerate(materials)]


def nei(recipes):
    """The bridge's side of nei.recipes: pages of at most 20, nextOffset while more remain."""
    def reply(method, p):
        if method != "nei.recipes": return {}
        chosen = [r for r in recipes if p["handler"].lower() in r["handlerKey"].lower()]; shown = chosen[p["offset"]:p["offset"] + min(p["limit"], 20)]
        more = {"nextOffset": p["offset"] + len(shown)} if p["limit"] and p["offset"] + len(shown) < len(chosen) else {}
        return {"handlers": [{"key": k, "count": sum(r["handlerKey"] == k for r in recipes)} for k in dict.fromkeys(r["handlerKey"] for r in recipes)],
                "total": len(chosen), "offset": p["offset"], "recipes": json.loads(json.dumps(shown)), **more}
    return reply


class RecipeSummaryTests(unittest.TestCase):
    MATERIALS = [f"{m}{n}" for n in ("", " Steel", " Bronze") for m in ("Iron", "Blue", "Black", "Red", "Stainless", "Damascus", "Vanadium", "Tungsten", "Cobalt", "Knightmetal", "Dark", "Hot", "Cold", "Old", "New")]

    def run_tool(self, recipes, **kw):
        from mbtools_gtnh import recipes_quests
        self.k = FakeKernel(nei(recipes))
        with patch.object(recipes_quests, "kernel", lambda: self.k), patch.object(recipes_quests.notes, "with_item_notes", lambda result: result):
            return recipes_quests.mb_recipes("gt:tool", 16, **kw)

    def test_a_page_says_once_what_its_recipes_share_and_one_item_is_that_item(self):
        recipes = summaries(SHAPED, self.MATERIALS)
        page = self.run_tool(recipes, handler="crafting", limit=20)
        self.assertEqual(page["shared"], {"handlerKey": SHAPED, "handler": "codechicken.nei.recipe.ShapedRecipeHandler", "name": "Shaped Crafting", "nativeRecipesPerPage": 2,
                                          "structuredCoverage": recipes[0]["structuredCoverage"], "detailsRequired": True, "summaryNote": NOTE})
        self.assertEqual(page["stations"], {SHAPED: ["Crafting Table"] * 6 + ["+3 more (detail='full' lists them)"]})
        self.assertEqual(page["recipes"][1], {"index": 1, "inputs": [{"id": "gt:plate", "meta": 0, "name": "Blue Plate", "count": 1}, recipes[1]["inputs"][1], {"id": "gt:ingot", "meta": 0, "name": "Blue Ingot", "count": 1}],
                                              "result": {"id": "gt:tool", "meta": 0, "name": "Wrench", "count": 1}, "other": []})
        sent = json.dumps(nei(recipes)("nei.recipes", self.k.calls[0][1]))
        self.assertLess(len(json.dumps(page)), len(sent) // 3)
        mixed = self.run_tool(recipes[:2] + summaries(LOOT, ["Loot"]), limit=20)  # two handlers on a page: each recipe keeps its own, the note is still said once
        self.assertEqual((mixed["shared"], [r["handlerKey"] for r in mixed["recipes"]]), ({"nativeRecipesPerPage": 2, "structuredCoverage": recipes[0]["structuredCoverage"], "detailsRequired": True, "summaryNote": NOTE}, [SHAPED, SHAPED, LOOT]))
        self.assertNotIn("shared", self.run_tool(recipes))  # the overview has no recipes

    def test_query_narrows_a_handler_by_shown_names_before_paging(self):
        recipes = summaries(SHAPED, self.MATERIALS) + summaries(LOOT, ["Steel"])
        found = self.run_tool(recipes, handler="crafting", query="steel WRENCH")
        self.assertEqual([c[1]["offset"] for c in self.k.calls], [0, 20, 40])  # the handler read through, 20 a call
        self.assertEqual((found["total"], found["matched"], found["query"], found.get("nextOffset")), (45, 15, "steel WRENCH", None))
        self.assertEqual([r["index"] for r in found["recipes"]], list(range(15, 30)))  # the native index, for detail='full'
        second = self.run_tool(recipes, handler="crafting", query="steel", offset=4, limit=4)
        self.assertEqual(([r["index"] for r in second["recipes"]], second["offset"], second["nextOffset"]), ([19, 20, 21, 22], 4, 8))
        self.assertEqual(self.run_tool(recipes, handler="crafting", query="hard hammer blue bronze")["matched"], 1)  # every word, any shown name
        self.assertEqual((lambda none: (none["matched"], none["recipes"]))(self.run_tool(recipes, query="netherite")), (0, []))
        with self.assertRaisesRegex(ValueError, "narrows summaries"): self.run_tool(recipes, handler=SHAPED, query="steel", detail="full", index=3)
        from mbtools_gtnh import recipes_quests
        with patch.object(recipes_quests, "QUERY_SCAN", 40), self.assertRaisesRegex(ValueError, "at most 40 recipes and this selects 46.*LootBag"): self.run_tool(recipes, query="steel")
        self.assertEqual(len(self.k.calls), 1)


if __name__ == "__main__": unittest.main()
