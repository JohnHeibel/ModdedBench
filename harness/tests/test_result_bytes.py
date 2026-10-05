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

    def test_a_summary_gives_a_recipe_on_the_slot_grid_as_the_pattern_a_craft_takes(self):
        recipes = summaries(SHAPED, ["Red"])
        plate, hammers, ingot = recipes[0]["inputs"]
        for position, (x, y) in zip((plate, hammers, ingot), ((25, 6), (61, 6), (43, 24))): position.update(x=x, y=y)   # plate . hammer / . ingot .
        got = self.run_tool(recipes, handler="crafting", limit=20)["recipes"][0]
        self.assertNotIn("inputs", got)
        self.assertEqual(got["pattern"], [[{"id": "gt:plate", "meta": 0, "name": "Red Plate", "count": 1}, None, {k: v for k, v in hammers.items() if k not in "xy"}],
                                          [None, {"id": "gt:ingot", "meta": 0, "name": "Red Ingot", "count": 1}, None]])
        ingot.update(x=50)   # a view that is not a slot grid keeps its list
        got = self.run_tool(recipes, handler="crafting", limit=20)["recipes"][0]
        self.assertEqual(([c.get("name") for c in got["inputs"]], "pattern" in got, "x" in got["inputs"][2]), (["Red Plate", None, "Red Ingot"], False, False))

    def test_a_page_says_once_what_its_recipes_share_and_one_item_is_that_item(self):
        recipes = summaries(SHAPED, self.MATERIALS)
        page = self.run_tool(recipes, handler="crafting", limit=20)
        self.assertEqual(page["shared"], {"handlerKey": SHAPED, "handler": "codechicken.nei.recipe.ShapedRecipeHandler", "name": "Shaped Crafting", "nativeRecipesPerPage": 2,
                                          "structuredCoverage": recipes[0]["structuredCoverage"], "detailsRequired": True, "summaryNote": NOTE})
        self.assertEqual(page["stations"], {SHAPED: ["Crafting Table"] * 6 + ["+3 more (detail='full', stations='all' lists them)"]})
        self.assertEqual(page["recipes"][1], {"index": 1, "inputs": [{"id": "gt:plate", "meta": 0, "name": "Blue Plate", "count": 1}, recipes[1]["inputs"][1], {"id": "gt:ingot", "meta": 0, "name": "Blue Ingot", "count": 1}],
                                              "result": {"id": "gt:tool", "meta": 0, "name": "Wrench", "count": 1}, "other": []})
        sent = json.dumps(nei(recipes)("nei.recipes", self.k.calls[0][1]))
        self.assertLess(len(json.dumps(page)), len(sent) // 3)
        mixed = self.run_tool(recipes[:2] + summaries(LOOT, ["Loot"]), limit=20)  # two handlers on a page: each recipe keeps its own, the note is still said once
        self.assertEqual((mixed["shared"], [r["handlerKey"] for r in mixed["recipes"]]), ({"nativeRecipesPerPage": 2, "structuredCoverage": recipes[0]["structuredCoverage"], "detailsRequired": True, "summaryNote": NOTE}, [SHAPED, SHAPED, LOOT]))
        self.assertNotIn("shared", self.run_tool(recipes))  # the overview has no recipes

    def test_a_full_recipe_names_one_station_for_the_grid_and_every_station_for_a_machine(self):
        item = lambda id, name, **more: {"id": id, "meta": 0, "count": 1, "name": name, "maxStackSize": 64, "oreNames": [], **more}
        at = lambda x, y, *items, **more: {"x": x, "y": y, "alternatives": list(items), "alternativeCount": len(items), "alternativesOffset": 0, **more}
        tables = [at(0, 0, item("minecraft:crafting_table", "Crafting Table"))] + [at(0, 0, item(f"mod:table{n}", f"Table {n}")) for n in range(35)]
        plank = at(25, 6, item("minecraft:planks", "Oak Planks", oreNames=["plankWood"]), item("mod:planks", "Fir Planks", oreNames=["plankWood"]), alternativeCount=40, nextAlternativesOffset=2)
        full = lambda key, catalysts: [{"handler": key.split("|")[0], "handlerKey": key, "name": key.split("|")[2], "index": 0, "catalysts": catalysts,
                                        "inputs": [plank, at(43, 24, item("minecraft:ender_pearl", "Ender Pearl", maxStackSize=16))], "result": at(119, 24, item("gt:tool", "Wrench", maxStackSize=1)), "other": []}]
        got = self.run_tool(full(SHAPED, tables), handler=SHAPED, detail="full", index=0)["recipes"][0]
        self.assertEqual((got["catalysts"], got["otherCatalysts"]), ([{"id": "minecraft:crafting_table", "meta": 0, "count": 1, "name": "Crafting Table"}], 35))
        self.assertEqual(got["inputs"], [{"x": 25, "y": 6, "alternatives": [{"id": "minecraft:planks", "meta": 0, "count": 1, "name": "Oak Planks", "oreNames": ["plankWood"]}, {"id": "mod:planks", "meta": 0, "count": 1, "name": "Fir Planks", "oreNames": ["plankWood"]}],
                                          "alternativeCount": 40, "nextAlternativesOffset": 2},                      # a paged slot still says how many there are
                                         {"x": 43, "y": 24, "alternatives": [{"id": "minecraft:ender_pearl", "meta": 0, "count": 1, "name": "Ender Pearl", "maxStackSize": 16}]}])
        self.assertEqual(got["result"]["alternatives"][0]["maxStackSize"], 1)
        every = self.run_tool(full(SHAPED, tables), handler=SHAPED, detail="full", index=0, stations="all")["recipes"][0]
        self.assertEqual(([c["name"] for c in every["catalysts"]], "otherCatalysts" in every), (["Crafting Table"] + [f"Table {n}" for n in range(35)], False))
        for key in ("codechicken.nei.recipe.FurnaceRecipeHandler|smelting|Smelting", "gregtech.nei.GTNEIDefaultHandler|gt.recipe.alloysmelter|Alloy Smelter"):
            got = self.run_tool(full(key, tables), handler=key, detail="full", index=0)["recipes"][0]
            self.assertEqual(len(got["catalysts"]), 1 if "smelting" in key else 36, key)  # a machine's tiers are not one another
        placed = self.run_tool(full(SHAPED, [at(4, 0, item("mod:table", "Table")), at(0, 0, item("mod:a", "A"), item("mod:b", "B"))]), handler=SHAPED, detail="full", index=0, stations="all")["recipes"][0]
        self.assertEqual([sorted(c) for c in placed["catalysts"]], [["alternatives", "x", "y"]] * 2)  # a catalyst with a place or a choice stays a slot

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


class QuestTests(unittest.TestCase):
    def setUp(self):
        from mbtools_gtnh import recipes_quests
        self.quests = recipes_quests; mbtool.state.pop("quest_titles", None); self.addCleanup(mbtool.state.pop, "quest_titles", None)
        self.book = {f"q{n:03d}": f"Quest {n}" for n in range(250)}

    def reply(self, method, p):
        if method == "quest.search":
            ids = sorted(self.book)[p["offset"]:p["offset"] + p["limit"]]; end = p["offset"] + len(ids)
            return {"quests": [{"id": i, "name": self.book[i], "description": "long " * 50} for i in ids], "nextOffset": end if end < len(self.book) else None}
        if method == "quest.lines":
            return {"lines": [{"id": "line", "name": "Tier 1", "quests": 2, "locked": 1, "entries": [{"questId": i, "x": 540, "y": 516, "sizeX": 24, "sizeY": 24, "state": s} for i, s in self.entries]}], "total": 1}
        return {"id": "q001", "tasks": [{"id": 0, "config": "{requiredItems:[0:{id:\"minecraft:stick\",Damage:0s}],consume:1b}", "items": [{"name": "Stick", "need": 1, "submitted": 0, "have": 0}]}],
                "rewards": [{"id": 0, "type": "bq_standard:item", "config": "{rewards:[0:{id:\"minecraft:apple\"}]}"},
                            {"id": 1, "type": "bq_standard:choice", "config": "{choices:[0:{id:\"minecraft:diamond\"}]}", "choice": {"selectedIndex": -1, "options": [{"choiceIndex": 0, "stack": {"id": "minecraft:diamond", "meta": 0, "count": 9}}]}}]}

    def test_line_entries_carry_titles_not_layout_and_the_book_is_read_once(self):
        self.entries = [("q007", "UNLOCKED"), ("q249", "LOCKED")]
        k = FakeKernel(self.reply)
        with patch.object(self.quests, "kernel", lambda: k):
            line = self.quests.mb_quest_lines("Tier 1")["lines"][0]
            self.assertEqual(line["entries"], [{"questId": "q007", "name": "Quest 7", "state": "UNLOCKED"}, {"questId": "q249", "name": "Quest 249", "state": "LOCKED"}])
            self.assertEqual((line["name"], line["locked"]), ("Tier 1", 1))
            self.assertEqual([c[1]["offset"] for c in k.calls if c[0] == "quest.search"], [0, 100, 200])
            self.quests.mb_quest_lines("Tier 1")
            self.assertEqual(len(k.calls), 5)  # titles are kept: the second read is the one bridge call
            self.book["q250"] = "A quest synced later"; self.entries.append(("q250", "LOCKED"))
            self.assertEqual(self.quests.mb_quest_lines()["lines"][0]["entries"][2]["name"], "A quest synced later")

    def test_several_lines_are_listed_with_totals_and_without_their_entries(self):
        self.entries = [("q007", "UNLOCKED")]
        def two(method, p):
            found = self.reply(method, p)
            return {**found, "lines": found["lines"] * 2} if method == "quest.lines" else found
        k = FakeKernel(two)
        with patch.object(self.quests, "kernel", lambda: k):
            lines = self.quests.mb_quest_lines()["lines"]
            self.assertEqual([(line["name"], line["quests"], "entries" in line) for line in lines], [("Tier 1", 2, False)] * 2)
            self.assertEqual([c[0] for c in k.calls], ["quest.lines"])  # no titles are read for a list of lines

    count = 17

    def chapters(self, method, p):
        """quest.lines as the bridge pages it: 12 lines with a quest open, then 5 with none, each with its entries."""
        if method != "quest.lines": return self.reply(method, p)
        book = [{"id": f"l{n}", "name": f"Chapter {n}", "quests": 3, "completed": n % 2, "unlocked": 0, "unclaimed": 1 - n % 2, "locked": 2 if n < 12 else 3,
                 "entries": [{"questId": "q007", "x": 0, "y": 0, "state": "LOCKED"}]} for n in range(self.count)]
        found = [line for line in book if p["query"].lower() in line["name"].lower()]; end = min(p["offset"] + p["limit"], len(found))
        return {"lines": found[p["offset"]:end], "offset": p["offset"], "limit": p["limit"], "total": len(found), "nextOffset": end if end < len(found) else None, "query": p["query"]}

    def test_the_default_list_is_every_line_with_a_quest_open_on_one_page(self):
        k = FakeKernel(self.chapters)
        with patch.object(self.quests, "kernel", lambda: k):
            found = self.quests.mb_quest_lines()
            self.assertEqual(([line["name"] for line in found["lines"]], found["lockedLinesNotListed"], found["total"]), ([f"Chapter {n}" for n in range(12)], 5, 17))
            self.assertFalse(any("entries" in line for line in found["lines"]) or "nextOffset" in found)
            every = self.quests.mb_quest_lines(locked=True)
            self.assertEqual((len(every["lines"]), every["lines"][16]["locked"], "lockedLinesNotListed" in every), (17, 3, False))
            self.assertEqual([c[0] for c in k.calls], ["quest.lines"] * 2)
            self.count = 120  # more lines than the bridge gives in a call
            self.assertEqual((lambda far: (far["total"], far["lockedLinesNotListed"]))(self.quests.mb_quest_lines()), (120, 108))
            self.assertEqual([c[1]["offset"] for c in k.calls[2:]], [0, 100])

    def test_a_query_finds_a_line_locked_or_not_and_pages_as_the_bridge_does(self):
        k = FakeKernel(self.chapters)
        with patch.object(self.quests, "kernel", lambda: k):
            line, = self.quests.mb_quest_lines("chapter 16")["lines"]
            self.assertEqual((line["locked"], line["entries"]), (3, [{"questId": "q007", "name": "Quest 7", "state": "LOCKED"}]))
            found = self.quests.mb_quest_lines("chapter")
            self.assertEqual((len(found["lines"]), found["total"], found["nextOffset"], "lockedLinesNotListed" in found), (10, 17, 10, False))
            self.assertEqual([line["name"] for line in self.quests.mb_quest_lines("chapter", offset=10)["lines"]], [f"Chapter {n}" for n in range(10, 17)])

    def test_observe_drops_raw_config_only_where_the_structured_form_says_as_much(self):
        with patch.object(self.quests, "kernel", lambda: FakeKernel(self.reply)): quest = self.quests.mb_quest_observe("q001")
        self.assertEqual(["config" in r for r in quest["rewards"]], [True, False])  # an item reward has no other description; a choice's options are its config
        self.assertIn("consume:1b", quest["tasks"][0]["config"])  # items name the stacks; their id, meta and the consume rule are only here


class InventoryDefaultTests(unittest.TestCase):
    def test_inventory_asks_the_bridge_for_the_compact_form_unless_told_otherwise(self):
        from mbtools_gtnh import inventory
        k = FakeKernel(lambda method, params: {"slots": []})
        with patch.object(inventory, "kernel", lambda: k), patch.object(inventory.notes, "with_item_notes", lambda result: result):
            inventory.mb_inventory(); inventory.mb_inventory(container=True); inventory.mb_inventory("full", container=True)
        self.assertEqual(k.calls, [("obs.inventory", {"detail": "compact"}), ("obs.container", {"detail": "compact"}), ("obs.container", {"detail": "full"})])


class MethodListTests(unittest.TestCase):
    def test_methods_are_listed_by_one_line_and_read_whole_by_name(self):
        from mbtools_gtnh import core
        fight = {"name": "nav.fight", "desc": "One fight as a job: {entityId or target:{...}, hold:false}. " + "Paths to the mob. " * 40, "effect": "interaction", "thread": "client", "watchable": False}
        click = {"name": "gui.click_slot", "desc": "Click " + "{windowId,epoch,slot,button} " * 6, "effect": "interaction", "thread": "client", "watchable": False}
        ping = {"name": "sys.ping", "effect": "read", "thread": "transport"}
        k = FakeKernel(lambda method, params: [fight, click, ping])
        with patch.object(core, "kernel", lambda: k), patch.dict(mbtool.state, {}):
            listed, one = core.mb_methods(), core.mb_methods("fight")
            self.assertEqual(mbtool.state["methods"]["sys.ping"]["effect"], "read")  # lane routing still reads the whole list
        self.assertEqual(listed["methods"]["nav.fight"], "One fight as a job: {entityId or target:{...}, hold:false}")
        self.assertEqual(len(listed["methods"]["gui.click_slot"]), 100); self.assertTrue(listed["methods"]["gui.click_slot"].endswith("..."))
        self.assertEqual(listed["methods"]["sys.ping"], "")
        self.assertEqual(one, {"methods": {"nav.fight": fight}})
        self.assertLess(len(json.dumps(listed)), len(json.dumps([fight, click, ping])) // 4)


if __name__ == "__main__": unittest.main()
