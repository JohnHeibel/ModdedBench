# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (c) 2026 ModdedBench contributors
"""Better Questing progression and native NEI catalogue/recipe tools."""

from __future__ import annotations

import json
import time
from typing import Any

from mcp.types import CallToolResult, TextContent, ImageContent

from mbtool import kernel, tool


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
    """Read native quest-line order, layout and per-player state totals."""
    return kernel().call("quest.lines", query=query, offset=offset, limit=limit)


@tool(lane="read", coverage=["progression"])
def mb_quest_observe(quest_id: str) -> Any:
    """Observe one quest UUID: prerequisites, task progress/config and rewards.

    Reward choices expose native indices. Task/reward NBT is descriptive SNBT and
    never authorizes direct state mutation.
    """
    return kernel().call("quest.observe", questId=quest_id)


@tool(rung=1, coverage=["progression"])
def mb_quest_detect(quest_id: str, task_ids: list[int]) -> Any:
    """Send Better Questing's normal quest-wide detection request.

    The receipt only means the packet was queued. Re-observe after synchronization;
    supplied task IDs validate intent but do not force per-task completion.
    """
    return kernel().call("quest.detect", questId=quest_id, taskIds=task_ids)


@tool(rung=1, coverage=["progression"])
def mb_quest_select_choice(quest_id: str, reward_id: int, choice_index: int) -> Any:
    """Select one observed native reward option through the normal BQ packet.

    Re-observe the selected index before claiming. Packet queueing is not a server
    acknowledgement.
    """
    return kernel().call("quest.select_choice", questId=quest_id, rewardId=reward_id,
                         choiceIndex=choice_index)


@tool(rung=1, coverage=["progression"])
def mb_quest_claim(quest_id: str, reward_ids: list[int],
                   choices: dict[str, int] | None = None) -> Any:
    """Request a normal quest-wide claim with explicit reward IDs and choices.

    choices maps rewardId strings to observed choice indices and must cover every
    choice reward. The result is pending synchronization: re-observe quest state and
    compare inventory deltas before reporting receipt. Never blindly retry a claim.
    """
    return kernel().call("quest.claim", questId=quest_id, rewardIds=reward_ids,
                         choices=choices or {})


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
    return kernel().call("nei.item", id=id, meta=meta, nbt=nbt)


@tool(lane="read", coverage=["recipes"])
def mb_recipes(id: str = "", meta: int | None = None, nbt: str | None = None, mode: str = "recipes",
               handler: str = "", offset: int = 0, limit: int = 0,
               alternatives_offset: int = 0, alternatives_limit: int = 32,
               timeout_s: float = 60.0, fluid: str = "", amount: int = 1000,
               detail: str = "summary", index: int = -1) -> Any:
    """Browse every way to make an item, or mode='uses' for what consumes it.

    Default limit=0 returns a category overview with counts and known GT base EU/t
    ranges, without choosing a recipe. NEI order is NOT a progression recommendation.
    Pick an exact handlerKey as handler, then limit=5 (or up to 20) for compact
    comparable options. Follow nextOffset to see all variants. Use detail='full',
    handler=that exact key, index=the option's native index, limit=1 for complete
    ingredients/NBT/alternatives. Summary examples omit NBT and are not actionable
    item identities. Compare machines, power, ingredients and research against
    observations; no route is selected or declared craftable automatically.

    Pass exact id/meta/nbt for an item, or fluid="water" for a canonical fluid ID
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
    This observes recipes; it does not craft, spawn items or alter the current GUI.
    """
    return kernel().call("nei.recipes", id=id, meta=meta, nbt=nbt, mode=mode, handler=handler,
                         offset=offset, limit=limit, alternativesOffset=alternatives_offset,
                         alternativesLimit=alternatives_limit, timeout=timeout_s, fluid=fluid, amount=amount, detail=detail, index=index)


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
