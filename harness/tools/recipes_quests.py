# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Better Questing progression and native NEI catalogue/recipe tools."""

from __future__ import annotations

import json
import time
from typing import Any

from mcp.types import CallToolResult, TextContent, ImageContent

from mbtool import kernel, state, tool
from mbtools_gtnh import notes


@tool(lane="read", coverage=["progression"])
def mb_quest_status() -> Any:
    """Report native Better Questing availability and catalogue counts."""
    return kernel().call("quest.status")


@tool(lane="read", coverage=["progression"])
def mb_quest_sync() -> Any:
    """Queue Better Questing's native full quest/progress and chapter synchronization query.

    This does not open a GUI, load an item, detect tasks, claim rewards, or edit quest
    state. The response is pending until the running server processes the packets and
    the client database updates.
    """
    return kernel().call("quest.sync")


@tool(lane="read", coverage=["progression"])
def mb_quest_search(query: str = "", offset: int = 0, limit: int = 20) -> Any:
    """Search localized quest UUIDs, titles and descriptions with pagination."""
    return kernel().call("quest.search", query=query, offset=offset, limit=limit)


@tool(lane="read", coverage=["progression"])
def mb_quest_lines(query: str = "", offset: int = 0, limit: int = 10) -> Any:
    """Read quest lines in book order with per-player state totals. A result of one line (a query only it
    matches, or limit=1 at its offset) also lists every quest's id, title and state."""
    k = kernel(); found = k.call("quest.lines", query=query, offset=offset, limit=limit)
    if len(found["lines"]) != 1:  # the entries of ten lines are a hundred thousand characters
        for line in found["lines"]: line.pop("entries", None)
        return found
    titles = state.setdefault("quest_titles", {})  # the bridge's entries are an id and pixel layout: the book's titles are read once, 100 a call
    at = None if all(e["questId"] in titles for line in found["lines"] for e in line["entries"]) else 0
    while at is not None:
        page = k.call("quest.search", query="", offset=at, limit=100); at = page.get("nextOffset")
        titles.update((quest["id"], quest["name"]) for quest in page["quests"])
    for line in found["lines"]:
        line["entries"] = [{"questId": e["questId"], "name": titles.get(e["questId"]), "state": e["state"]} for e in line["entries"]]
    return found


@tool(lane="read", coverage=["progression"])
def mb_quest_observe(quest_id: str) -> Any:
    """Observe one quest UUID: prerequisites, task progress/config and rewards.

    A retrieval task lists items: each required item with need, submitted (already
    handed in) and have (what you carry that the task's own matcher accepts, ore
    dictionary and NBT rules included). Reward choices expose native indices, and
    their options stand for that reward's config.
    Task/reward NBT is descriptive SNBT and never authorizes direct state mutation.
    """
    quest = kernel().call("quest.observe", questId=quest_id)
    for reward in quest.get("rewards") or []:
        if "choice" in reward: reward.pop("config", None)  # the same stacks as the options, as raw SNBT
    return quest


@tool(rung=1, coverage=["progression"])
def mb_quest_detect(quest_id: str, task_ids: list[int] | None = None, wait_s: float = 10.0) -> Any:
    """Hand a quest its tasks: the quest book's detect button, checkboxes included.

    Clicks every unfinished checkbox task (task_ids narrows which; default all), then
    sends the normal quest-wide detect, which counts what you carry (and consumes it
    where the task says so). Then watches the quest for up to wait_s seconds (0 disables, at most 60)
    and returns complete true/false with each task's state, so one call usually settles
    it; false is not a failure while time is paused, the server has not answered yet.
    A quest that is already complete is returned without sending an empty detect
    request. A quest that stays incomplete is missing something: read its tasks' config.
    """
    k = kernel()
    state = k.call("quest.observe", questId=quest_id)
    if state.get("complete"):
        tasks = [{key: task.get(key) for key in ("id", "name", "complete")}
                 for task in state.get("tasks") or []]
        return {"receipt": {"accepted": False, "reason": "already_complete"},
                "complete": True, "canClaim": state.get("canClaim"), "tasks": tasks}
    task_ids = task_ids or [task["id"] for task in state.get("tasks") or []]
    if not task_ids:
        return {"receipt": {"accepted": False, "reason": "no_tasks"}, "complete": False}
    receipt = k.call("quest.detect", questId=quest_id, taskIds=task_ids)
    deadline, state, clamped = time.monotonic() + max(0.0, min(wait_s, 60.0)), None, _clamped(wait_s)
    while wait_s > 0:
        state = k.call("quest.observe", questId=quest_id)
        if state.get("complete") or time.monotonic() >= deadline: break
        time.sleep(0.5)
    if state is None: return receipt
    tasks = [{k: t.get(k) for k in ("id", "name", "complete")} for t in state.get("tasks") or []]
    return {"receipt": receipt, "complete": bool(state.get("complete")), "canClaim": state.get("canClaim"), "tasks": tasks, **clamped}


@tool(rung=1, coverage=["progression"])
def mb_quest_select_choice(quest_id: str, reward_id: int, choice_index: int) -> Any:
    """Select one observed native reward option through the normal BQ packet.

    mb_quest_claim's choices does this as part of the claim; this is for choosing
    without claiming.
    """
    return kernel().call("quest.select_choice", questId=quest_id, rewardId=reward_id,
                         choiceIndex=choice_index)


@tool(rung=1, coverage=["progression"])
def mb_quest_claim(quest_id: str, reward_ids: list[int] | None = None,
                   choices: dict[str, int] | None = None, wait_s: float = 10.0) -> Any:
    """Claim a quest's rewards, all of them at once, as the book's claim button does.

    choices maps rewardId strings to observed choice indices and must cover every
    choice reward; the claim selects them. reward_ids is not needed and changes
    nothing: the game claims a quest whole. The claim itself is only queued: this then watches the quest for up
    to wait_s seconds (0 disables, at most 60) and returns claimed true/false with the observed
    state, so one call usually settles it. claimed false is not a failure: the server
    has not answered yet (always the case while time is paused). Observe again later;
    never blindly retry a claim. received lists what your inventory gained and lost
    across the claim, by name: that is the rewards arriving, no separate check needed.
    """
    k = kernel(); before = _held(k)
    rewards = [reward["id"] for reward in k.call("quest.observe", questId=quest_id).get("rewards") or []]  # the bridge has them named; the game takes the quest
    receipt = k.call("quest.claim", questId=quest_id, rewardIds=rewards, choices=choices or {})
    deadline, state, clamped = time.monotonic() + max(0.0, min(wait_s, 60.0)), None, _clamped(wait_s)
    while wait_s > 0:
        state = k.call("quest.observe", questId=quest_id)
        if _claimed(state) or time.monotonic() >= deadline: break
        time.sleep(0.5)
    received = {}
    for _ in range(4 if state is not None and _claimed(state) else 1):  # the items can land a tick after the claim is recorded
        received = _delta(before, _held(k))
        if received: break
        time.sleep(0.5)
    if state is None: return {**receipt, "received": received} if isinstance(receipt, dict) else receipt
    return {"receipt": receipt, "claimed": _claimed(state), "received": received, "quest": state, **clamped}


def _clamped(wait_s: float) -> dict:
    """The quest watches wait at most 60 s: a longer ask is said back, not silently shortened."""
    return {"clamped": {"wait_s": {"asked": wait_s, "used": 60.0}}} if wait_s > 60 else {}


def _held(k) -> dict:
    """Inventory totals by identity: {(id, meta, nbt hash): (name, count)}."""
    try: totals = k.call("obs.inventory", detail="counts").get("totals") or []
    except Exception: return {}
    return {(t["identity"].get("id"), t["identity"].get("meta"), t["identity"].get("nbt_hash")): (t["identity"].get("name"), t["count"]) for t in totals if t.get("identity")}


def _delta(before: dict, after: dict) -> dict:
    """{name: +n or -n} for every identity whose count changed."""
    out = {}
    for key in before.keys() | after.keys():
        n = after.get(key, (None, 0))[1] - before.get(key, (None, 0))[1]
        if n: name = (after.get(key) or before.get(key))[0] or key[0]; out[name] = out.get(name, 0) + n
    return {name: n for name, n in out.items() if n}


def _claimed(value) -> bool:
    if isinstance(value, dict): return value.get("claimed") is True or any(_claimed(v) for v in value.values())
    return isinstance(value, list) and any(_claimed(v) for v in value)


@tool(lane="read", coverage=["recipes"])
def mb_recipe_status() -> Any:
    """Check whether GTNH NEI's full item catalogue and recipe handlers are ready."""
    return kernel().call("nei.status")


@tool(lane="read", coverage=["recipes"])
def mb_item_search(query: str = "", mod: str = "", ore: str = "", id: str = "", meta: int = -1,
              offset: int = 0, limit: int = 30, timeout_s: float = 60.0) -> Any:
    """Search the full native NEI catalogue, with pagination and exact variant identities.

    query uses the installed NEI search syntax, including its mod/ore/tooltip search
    providers. Structured mod, ore and id filters are exact; meta=-1 includes all
    variants. Results include display names, id, metadata, NBT and oreNames. Preserve
    id/meta/nbt when requesting recipes or selecting items: GT uses metadata heavily.
    nextOffset continues a result set. Repeated pages use a cached native search.
    """
    return kernel().call("nei.search", query=query, mod=mod, ore=ore, id=id, meta=meta,
                         offset=offset, limit=limit, timeout=timeout_s)


@tool(lane="read", coverage=["recipes"])
def mb_item_info(id: str, meta: int, nbt: str | None = None) -> Any:
    """Inspect an exact item variant, including tooltips, ore/fluid data and ItemBlock placement metadata.

    placement.initialBlockMeta is the native initial world-block metadata, not
    the item variant meta; face, pose and callbacks can still alter final state.
    """
    return notes.attach(kernel().call("nei.item", id=id, meta=meta, nbt=nbt), notes.surface(kernel(), subjects=[f"{id}:{meta}".casefold(), id.casefold()], reason="item"))


@tool(lane="read", coverage=["recipes"])
def mb_recipes(id: str = "", meta: int | None = None, nbt: str | None = None, mode: str = "recipes",
               handler: str = "", offset: int = 0, limit: int = 0,
               alternatives_offset: int = 0, alternatives_limit: int = 32,
               timeout_s: float = 60.0, fluid: str = "", amount: int = 1000,
               detail: str = "summary", index: int = -1, query: str = "", stations: str = "") -> Any:
    """Browse every way to make an item, or mode='uses' for what consumes it.

    Default limit=0 returns a category overview with counts and known GT base EU/t
    ranges, without choosing a recipe. NEI order is NOT a progression recommendation.
    Pick an exact handlerKey as handler, then limit=5 (or up to 20) for compact
    comparable options. Follow nextOffset to see all variants. A summary recipe gives its
    inputs as "pattern": rows of cells in the recipe's own layout, null for an empty slot,
    which is the shape of a shaped recipe and what mb_craft takes as pattern (a recipe
    whose view is not a slot grid lists "inputs" instead). A cell with several possible
    items shows two examples and alternativeCount. Use detail='full', handler=that exact
    key, index=the option's native index for what a summary leaves out: every
    alternative, ore names, NBT (nbtPresent marks a summary item that has some). Compare machines, power, ingredients and research against
    observations; no route is selected or declared craftable automatically.

    Pass exact id AND meta (meta is required with id: 0 for plain items, the variant
    number from mb_item_search otherwise) and nbt when the item has it, or fluid="water" for a canonical fluid ID
    returned by mb_fluid_search. Uses all NEI handlers, including modded machines and fluid-container
    recipes. handler filters by handler id or machine/category name. Returned handler
    summaries show available categories and counts; nextOffset pages recipes.
    Ingredient positions contain alternative item identities and counts; use
    alternatives_offset/alternatives_limit if nextAlternativesOffset is present.
    GregTech recipes include exact item/fluid quantities, output chances, duration,
    base EU/t, special items, metadata requirements and fakeRecipe/nbtSensitive flags.
    An input count of zero denotes a retained catalyst/configuration item. Output
    chances are resolved out of 10000, including default guaranteed outputs.
    Base power/time is before overclocking. Fake recipes may describe information
    rather than an executable process; inspect their native page.
    Custom non-GT handlers may expose additional requirements only through their GUI.
    A summary page says once what its recipes share: under "shared" the handlerKey, coverage and preview note (a key that
    differs within the page stays on its recipes), under "stations" the stations per handler. A position with one possible
    item is that item; one with several is {alternativeCount, exampleOffset, examples}.
    query="steel plate" keeps the summaries whose shown output and input names hold every word (the recipe view's
    search box), before offset and limit (default 20) page them; matched says how many of total, and at most 1000
    are searched, so pick a handler first.
    detail='full' returns ONE recipe whatever limit says: pick it with index. Its catalysts are the blocks that can
    run it, each as one item. Plain crafting and smelting have dozens that all do the same: there catalysts holds the
    first (the crafting table, the furnace) and otherCatalysts counts the rest; stations='all' lists every one. Every
    other handler lists all of its catalysts. A full recipe leaves out what says nothing: maxStackSize when it is 64,
    oreNames when there are none, alternativesOffset when it is 0, alternativeCount when every alternative is listed.
    x and y are the slot's place in the recipe's own grid: in shaped crafting they are the shape.
    This observes recipes; it does not craft, spawn items or alter the current GUI.
    Your notes on the item and on any ingredient shown are named under "notes": read them (mb_notes get) before making an ingredient by hand.
    """
    if detail == "full": limit = min(limit, 1) if limit else limit  # a full recipe is thousands of tokens: one at a time, by index
    if index >= 0: limit = 1  # an index names one recipe; the bridge refuses the lookup with limit=0
    if index >= 0 and "|" not in handler:  # an index counts within ONE handler: a name that fits exactly one is completed, else the error lists the keys
        found = kernel().call("nei.recipes", id=id, meta=meta, nbt=nbt, mode=mode, handler="", offset=0, limit=0, timeout=timeout_s, fluid=fluid, amount=amount, detail="summary", index=-1)
        keys = [h["key"] for h in found.get("handlers", []) if handler.lower() in h["key"].lower()]
        if len(keys) != 1: raise ValueError(f"index needs ONE handler; pass one of these as handler, exactly: {keys or [h['key'] for h in found.get('handlers', [])]}")
        handler = keys[0]
    page = lambda offset, limit: kernel().call("nei.recipes", id=id, meta=meta, nbt=nbt, mode=mode, handler=handler,
                                               offset=offset, limit=limit, alternativesOffset=alternatives_offset,
                                               alternativesLimit=alternatives_limit, timeout=timeout_s, fluid=fluid, amount=amount, detail=detail, index=index)
    words = query.casefold().split()
    if words and (detail == "full" or index >= 0): raise ValueError("query narrows summaries: find the recipe's index with it, then ask for detail='full' with that index")
    result = page(0, 20) if words else page(offset, limit)
    if words:  # the bridge pages 20 at a time and knows no text filter: read the handler through, keep what matches, page that
        if result["total"] > QUERY_SCAN: raise ValueError(f"query searches at most {QUERY_SCAN} recipes and this selects {result['total']}: narrow it with handler, one of {[h['key'] for h in result['handlers']]}")
        found, more = result["recipes"], result
        while more.get("nextOffset") is not None: more = page(more["nextOffset"], 20); found += more["recipes"]
        found = [r for r in found for shown in [_names([r.get(part) for part in ("inputs", "result", "other", "gregtech")]).casefold()] if all(w in shown for w in words)]
        end = offset + (limit or 20)
        result.update(query=query, matched=len(found), offset=offset, recipes=found[offset:end], nextOffset=end if end < len(found) else None)
    if detail != "full" and isinstance(result.get("recipes"), list):
        # Every recipe of a handler repeats that handler's station list (dozens of crafting-table variants): say it once, by name.
        named, recipes = result.setdefault("stations", {}), result["recipes"]
        for recipe in recipes:
            names = [e["name"] for c in recipe.pop("catalysts", None) or [] for e in c.get("examples", [])[:1]]
            named.setdefault(recipe.get("handlerKey"), names[:6] + ([f"+{len(names) - 6} more (detail='full', stations='all' lists them)"] if len(names) > 6 else []))
            grid = _grid(recipe.get("inputs") or [])
            for part in ("inputs", "other"): recipe[part] = [_only(position) for position in recipe.get(part) or []]
            if "result" in recipe: recipe["result"] = _only(recipe["result"])
            if grid: recipe["pattern"] = [[cell and _only(cell) for cell in row] for row in grid]; del recipe["inputs"]
        # So does what the handler and the summary form fix (its key, coverage, the preview note): once for the page, where the page agrees.
        shared = {key: recipes[0][key] for key in SHARED if recipes and key in recipes[0] and all(r.get(key) == recipes[0][key] for r in recipes)}
        if shared: result.update(shared=shared, recipes=[{key: value for key, value in r.items() if key not in shared} for r in recipes])
    if detail == "full" and isinstance(result.get("recipes"), list):
        for recipe in result["recipes"]:
            found = [_station(c) for c in recipe.get("catalysts") or []]
            if stations != "all" and len(found) > 1 and str(recipe.get("handlerKey")).split("|")[1:2] in (["crafting"], ["smelting"]):
                recipe.update(catalysts=found[:1], otherCatalysts=len(found) - 1)  # the grid and the furnace: one name stands for the lot
            elif found: recipe["catalysts"] = found
        result = _lean(result)
    # Notes on the ingredients matter as much as notes on the target: "the base already makes this" belongs to the ingredient.
    return notes.with_item_notes(result)


QUERY_SCAN = 1000  # recipes a query reads through, 20 a bridge call
SHARED = ("handlerKey", "handler", "name", "nativeRecipesPerPage", "structuredCoverage", "detailsRequired", "summaryNote")


def _only(position):
    """A summary position with a single possible item is that item, not a list of one alternative; its place is said by the pattern."""
    if isinstance(position, dict): position = {key: value for key, value in position.items() if key not in ("x", "y")}
    examples = position.get("examples") if isinstance(position, dict) else None
    return examples[0] if examples and position.get("alternativeCount") == 1 else position


SLOT = 18  # pixels from one slot to the next in a recipe view


def _grid(inputs):
    """The inputs as rows of cells, None where the recipe leaves a slot empty, when the recipe view lays them on its slot
    lattice: the shape a shaped recipe has, and the pattern mb_craft takes. None when the view places them otherwise."""
    if not inputs or not all(isinstance(p, dict) and type(p.get("x")) is int and type(p.get("y")) is int for p in inputs): return None
    x0, y0 = min(p["x"] for p in inputs), min(p["y"] for p in inputs)
    cells = {((p["y"] - y0) // SLOT, (p["x"] - x0) // SLOT): p for p in inputs if not (p["x"] - x0) % SLOT and not (p["y"] - y0) % SLOT}
    if len(cells) != len(inputs): return None
    return [[cells.get((row, col)) for col in range(max(c for _, c in cells) + 1)] for row in range(max(r for r, _ in cells) + 1)]


def _station(position):
    """A catalyst that is one item with no place of its own in the recipe is that item."""
    items = position.get("alternatives") if isinstance(position, dict) else None
    return items[0] if items and len(items) == 1 == position.get("alternativeCount") and not position.get("x") and not position.get("y") else position


def _lean(value):
    """A full recipe without the fields that say nothing (mb_recipes names them)."""
    if isinstance(value, list): return [_lean(v) for v in value]
    if not isinstance(value, dict): return value
    drop = {key for key, nothing in (("maxStackSize", 64), ("oreNames", []), ("alternativesOffset", 0)) if key in value and value[key] == nothing}
    if isinstance(value.get("alternatives"), list) and value.get("alternativeCount") == len(value["alternatives"]): drop.add("alternativeCount")
    return {key: _lean(v) for key, v in value.items() if key not in drop}


def _names(value) -> str:
    """Every display name of the stacks under a value, as one string."""
    if isinstance(value, dict): return " ".join([str(value.get("name") or "")] + [_names(v) for v in value.values()])
    return " ".join(_names(v) for v in value) if isinstance(value, list) else ""


@tool(lane="read", coverage=["recipes"])
def mb_fluid_search(query: str = "", offset: int = 0, limit: int = 30) -> Any:
    """Search loaded fluid IDs/names and physical properties; pass a returned ID to mb_recipes(fluid=...)."""
    return kernel().call("nei.fluids", query=query, offset=offset, limit=limit)


@tool(lane="read", coverage=["recipes"])
def mb_recipe_handlers(query: str = "", offset: int = 0, limit: int = 30) -> Any:
    """Discover all native NEI categories, including custom diagrams, magic and bee handlers, and their machine catalysts."""
    return kernel().call("nei.handlers", query=query, offset=offset, limit=limit)


def _with_screenshot(k, payload: dict) -> CallToolResult:
    time.sleep(.15)  # Allow the render loop to draw the newly opened page, including while paused.
    shot = k.call("sys.screenshot")
    return CallToolResult(content=[TextContent(type="text", text=json.dumps(payload)),
                                  ImageContent(type="image", data=shot["png"], mimeType="image/png")])


@tool(coverage=["recipes"])
def mb_recipe_view(handler_key: str, index: int, id: str = "", meta: int | None = None,
                   nbt: str | None = None, mode: str = "recipes", fluid: str = "", amount: int = 1000) -> Any:
    """Open and capture an exact native NEI recipe page from mb_recipes.

    Reuse that query's id/meta/nbt or fluid, mode, and the result's handlerKey/index.
    This preserves custom machine diagrams, Thaumcraft aspects, bee/chance displays,
    and text drawn by handlers that has no structured API. It changes the GUI and
    cancels active movement; it does not craft or spawn items. Use mb_recipe_inspect(x,y) for native hover text and scrolling; coordinates use the
    returned GUI dimensions. Close with mb_gui('close') before movement.
    """
    k = kernel()
    view = k.call("nei.view", handlerKey=handler_key, index=index, id=id, meta=meta, nbt=nbt,
                  mode=mode, fluid=fluid, amount=amount, timeout=60)
    return _with_screenshot(k, view)


@tool(coverage=["recipes"])
def mb_recipe_inspect(x: int, y: int, scroll: int = 0) -> Any:
    """Inspect the open native NEI page at logical GUI coordinates and capture it.

    Returns the hovered item, native item/handler tooltip text, hotkeys and a
    screenshot. scroll=-1 scrolls down, +1 up through native recipe/custom diagram
    handling; 0 only inspects. Use this for aspects, mutation conditions, chances,
    oversized diagrams and requirements missing from structured recipe data.
    Some handlers draw graphics without tooltip text: inspect the image too.
    Does not craft or spawn items. Requires mb_recipe_view first.
    """
    k = kernel()
    return _with_screenshot(k, k.call("nei.inspect", x=x, y=y, scroll=scroll))
