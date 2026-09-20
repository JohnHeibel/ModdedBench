# Tools

The tools an MCP client sees, grouped by the file in `harness/tools/` that
defines them. Every file there is re-imported when it changes, so this table
is a snapshot: `python harness/mcp/server.py --check` prints the live list, and
`mb_tools_status` shows it from inside a session with lanes, rungs and the last
reload error. `mb_methods` lists the raw Java RPC methods the tools compose,
with the effect (`read`, `interaction`, `control`) each one declares.

Conventions that hold across all tools:

- **Effect and lane.** `read` tools run on their own thread pool and are never
  starved by long actions; `control` tools (stop, pause, interrupts, time) have
  a small dedicated pool so they get through while actions are blocked;
  everything else is an action. Dispatchers such as `mb_call` and `mb_obs` pick
  the lane per call from the raw method's declared effect.
- **Receipts, not acknowledgements.** Actions return what was sent and what
  was observed immediately afterwards. Verify by observing again.
- **Notes ride along.** A `notes` key appears on results when a note is
  relevant to the transition (see the notes section).
- **Long calls.** Mining, building, routing and following stay open until a
  terminal receipt; pass `timeout_s` generously and keep the returned `jobId`.

## core.py: status, raw access, observation, action, time, memory

| Tool | Effect | What it does |
| --- | --- | --- |
| `mb_status` | read | Bridge capabilities and connection state; surfaces notes near the player (session start). |
| `mb_methods` | read | Lists the raw bridge methods with descriptions and effects. |
| `mb_call` | any | Calls any raw method with JSON params; the escape hatch when no wrapper fits. |
| `mb_obs` | read | Observations: `player`, `players`, `world`, `block`, `entities`, `entity`, `inventory`, `container`, `gui`, `tooltip`, `tile`, `nbt`, `waila`, `batch`. Block, tile and entity reads surface attached notes. |
| `mb_act` | action | Native actions: raw `input`, `look`, `use_block`, `use_entity`, `attack_entity`, `use_item`, `eat`, `select_hotbar`, bounded `combat`, `status`, `stop`. |
| `mb_keys` | action | Lists key bindings or presses one for a number of ticks. |
| `mb_stop` | control | Stops the active action and releases input. |
| `mb_time` | control | Server clock: `status`, `pause`, `resume`, `configure` guards, `report_failure`. |
| `mb_memory` | action | Waypoints, corridor routes, protected regions, recording; `status`/`get` are reads. |
| `mb_screenshot` | read | PNG of the client view, works while paused. |

## inventory.py: containers and items

| Tool | Effect | What it does |
| --- | --- | --- |
| `mb_inventory` | read | Player inventory with identities, NBT, cursor and slot ownership. |
| `mb_find` | read | Finds stacks by exact `{id, meta?, nbt_hash?, nbt?}` in the player or open container. |
| `mb_gui` | action | GUI primitives: open inventory, close, `click_slot`, `transfer`, `return_cursor`, `click_at`, `drag`, `scroll`, `key`, `type`, `button`, `text_field`, `hit_test`, `hover`. |
| `mb_click_slot` | action | One guarded slot click with expected stack and cursor. |
| `mb_transfer` | action | Moves up to 64 items to explicit ordinary slots and verifies the postcondition. |

`ContainerSession` in the same file is the helper for model-written
procedures: observe, act, and poll a postcondition with a bounded timeout.

## work.py: navigation, mining, construction

| Tool | Effect | What it does |
| --- | --- | --- |
| `mb_route` | action | Travels a saved corridor route, forward or reverse. |
| `mb_follow` | action | Follows loaded entities for a bounded time. |
| `mb_process` | action | Runs one upstream process: `goal`, `explore`, `get_to_block`, `farm`. |
| `mb_mine` | action | Quantity mining by block/item selectors in bounds or a radius; success is measured inventory gain. |
| `mb_scan` | read | Paged scan of loaded blocks by selector. |
| `mb_build_preview` | read | Fresh diff of a plan against the world plus material allocation. |
| `mb_build` | action | Executes explicit cells or a selection with the strict per-cell contract. |
| `mb_builder_pause` | control | Pauses active builder work. |
| `mb_builder_materials` | read | Placeable states currently in inventory. |
| `mb_schematic_import` | read | Reads a schematic file inside the game's `schematics/` directory into a plan. |
| `mb_schematic_build` | read/action | Imports and previews (default) or builds a schematic. |
| `mb_copy` | read/action | Copies loaded blocks in inclusive bounds into a plan, optionally rebuilding it at another origin. |
| `mb_work_status` | read | Durable job summary, progress and last receipt by `jobId`. |
| `mb_work_resume` | action | Resumes a stopped job after the cause is corrected; permissions are re-supplied each time. |
| `mb_settings` | action | Reads, sets or resets the pinned engine settings while idle. |
| `mb_cache` | action | Inspects or administers the terrain cache. |

Work completions and failures write a small `auto`-tagged note at the job's
location, so the next session finds where things stopped.

## recipes_quests.py: NEI and Better Questing

| Tool | Effect | What it does |
| --- | --- | --- |
| `mb_nei_status` | read | Whether the NEI catalogue and handlers are ready. |
| `mb_search` | read | Searches the item catalogue with pagination and exact variants. |
| `mb_item` | read | Tooltip, ore and fluid data, ItemBlock placement metadata for one variant. |
| `mb_fluids` | read | Fluid ids, names and properties. |
| `mb_recipes` | read | Every way to make an item (or `mode="uses"`), per handler, with voltage and duration. |
| `mb_recipe_handlers` | read | All NEI categories and their machine catalysts. |
| `mb_recipe_view` | action | Opens the native recipe page and returns it as an image. |
| `mb_recipe_inspect` | action | Tooltip text at GUI coordinates on the open recipe page. |
| `mb_quest_status` | read | Better Questing availability and counts. |
| `mb_quest_sync` | read | Requests a full quest and chapter sync from the server. |
| `mb_quest_lines` | read | Chapters in book order with layout and per-player totals. |
| `mb_quest_search` | read | Quest ids, titles and descriptions. |
| `mb_quest_observe` | read | One quest: prerequisites, tasks with progress, rewards and choices. |
| `mb_quest_detect` | action | Asks the server to detect task completion. |
| `mb_quest_select_choice` | action | Selects a reward option. |
| `mb_quest_claim` | action | Claims rewards; verify by re-observing the quest and inventory. |

## interrupts.py: watches that wake the model

| Tool | Effect | What it does |
| --- | --- | --- |
| `mb_interrupt` | control | `add`, `remove`, `reload`, `status`, `ack` watches. A watch is declarative (`queries`, `conditions`, `effects`, `prompt`) or a Python file with `evaluate(context)`; effects are `notify`, `cancel`, `pause`. |
| `mb_interrupt_events` | read | Replays the durable event journal after a cursor; a host uses it to give the model a turn. |

Fires are retried with the same event id and the watch is re-armed if the
bridge cannot be reached; watches survive reconnects. `_examples/` holds a
custom-predicate example.

## notes.py: durable world notes

| Tool | Effect | What it does |
| --- | --- | --- |
| `mb_notes` | read | `capture` an attachment (block, entity, location, region), `search`, `get`, `history`, `status`, `resolve`. |
| `mb_note_write` | action | Creates or updates a note with a revision guard and an operation id. |

Notes surface as a side effect, under a `notes` key, with at most five compact
entries `{id, kind, title, revision, status, at, distance, excerpt?, tags?,
why}`: on `mb_status` (session start), when a block, tile or entity with a
note is observed, when a position read enters a noted region or comes near a
note, and when a work call arrives somewhere. A note is not repeated within
ten minutes unless the player has moved far away.

## Server-side tools

`mb_reload_tools` re-imports the tool directory on demand and reports errors;
`mb_tools_status` lists modules, tools, lanes, live state keys and the last
reload error. Both are defined in `harness/mcp/server.py`.

## Adding a tool

```python
from mbtool import tool, kernel

@tool(lane="read", effect="read", coverage=["obs"])
def mb_nearby_chests(radius: int = 16) -> dict:
    """Chests within radius of the player, with their observed contents."""
    ...
```

Put it in any file under `harness/tools/` (files starting with `_` are
skipped). The next tool call loads it. A duplicate name or an import error
is reported and the previous tools stay registered. Keep live state in
`mbtool.state[...]` so it survives the next reload.
