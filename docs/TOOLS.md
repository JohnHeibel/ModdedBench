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
| `mb_obs` | read | Observations: `player`, `players`, `world`, `block`, `entities`, `entity`, `inventory`, `container`, `gui`, `tooltip`, `find`, `keys`, `tile`, `nbt`, `waila`, `batch`, and the engine's world reads `scan`, `terrain`, `fluid`, `tools`. Block, tile and entity reads surface attached notes. |
| `mb_act` | action | Native actions: raw `input`, `look`, `use_block`, `use_entity`, `attack_entity`, `use_item`, `eat`, `select_hotbar`, bounded `combat`, `status`, `stop`. |
| `mb_keys` | read/action | `list` reads the key bindings (`obs.keys`); `press` holds one for a number of ticks (`act.press_key`). |
| `mb_stop` | control | Stops the active action and releases input. |
| `mb_time` | control | Server clock: `status`, `pause`, `resume`, `configure` guards, `report_failure`. |
| `mb_memory` | action | Waypoints, corridor routes, protected regions, recording; `status`/`get` are reads. |
| `mb_screenshot` | read | PNG of the client view, works while paused. |

Actions (`mb_act`) need running time, so resume first:

- `use_block {x,y,z,face,hit?,sneak?,expected?,expectedHeld?}` checks reach,
  face and obstruction by ray and never falls back to plain item use.
  `use_entity` and `attack_entity` take `entityId` plus the `expectedHandle`
  from `obs.entities`; attack range is 3 blocks and players need
  `allowPlayers: true`.
- `eat` succeeds only after the held stack or hunger changes, and fails at
  once with `native_use_duration_exceeds_budget` when the pack's food-history
  penalty makes the item slower than the budget (default 400 ticks).
- `combat` is a stationary, bounded attack loop on visible hostiles.
  `pursue` is rejected: compose movement separately. A vanished entity is not
  a confirmed kill.
- A raw attack hold locks block edits to the block first under the crosshair
  and ends with `attack_target_changed`; `allowRetarget: true` lifts that.
- Receipts carry `nativeReturn` (not a success flag) and `serverAcknowledged`
  (only eating has one). An item may change NBT, metadata or slot, or drop an
  entity, so check the whole inventory. `overrideProtection` is per operation.

Tile reads (`mb_obs` with `tile`, `nbt`, `waila`) come from the server:

- Loaded blocks within 128 blocks in the current dimension; no position means
  the crosshair. A plain block returns `hasTile: false`; unloaded is an error.
- `nbt.handle` names an immutable snapshot that `obs.nbt {handle, path,
  offset, limit, budget, depth}` drills into. Handles expire after ten
  minutes, so put values worth keeping in a note.
- `fluids.views` has one view per side plus `UNKNOWN`. **Do not add side views
  together**; they may describe the same tank. `energy` covers GregTech EU,
  IC2 and RF; an unsupported system is absent, not zero.
- Pass `hwyla: false` to skip the Waila text when polling.

## inventory.py: containers and items

| Tool | Effect | What it does |
| --- | --- | --- |
| `mb_inventory` | read | Player inventory with identities, NBT, cursor and slot ownership. |
| `mb_find` | read | Finds stacks by exact `{id, meta?, nbt_hash?, nbt?}` in the player or open container (`obs.find`). |
| `mb_gui` | action | GUI primitives: open inventory, close, `click_slot`, `transfer`, `return_cursor`, `click_at`, `drag`, `scroll`, `key`, `type`, `button`, `text_field`, `hit_test`, `hover`. |
| `mb_click_slot` | action | One guarded slot click with expected stack and cursor. |
| `mb_transfer` | action | Moves up to 64 items to explicit ordinary slots and verifies the postcondition. |

`ContainerSession` in the same file is the helper for model-written
procedures: observe, act, and poll a postcondition with a bounded timeout.

- Inventory indices and container slot indices are different namespaces.
  Stacks keep id, metadata, count and full SNBT; `nbt_hash` fingerprints the
  observed SNBT. Pass an explicit `null` to guard an empty slot or cursor.
- Guarded clicks take `windowId`, `epoch`, `slot`, `expected`,
  `expectedCursor`. `transfer` needs an empty cursor and returns the remainder
  to its source. Virtual, ghost and ME slots need native clicks instead.
  `mb_obs("container", {"probeSlot": n})` reports which slots would accept the
  stack in slot `n`.
- `destinationPolicy: "consuming"` lets a machine take the moved items,
  reported as `consumed_routed_or_corrected_unproven`, never as processed.
  Custom-packet GUIs report `client_event_delivery_only`. Nothing retries
  because a slot looks unchanged; do not replay a multi-step procedure after
  an uncertain step.
- `close` refuses an occupied cursor unless `allowCursorDrop: true`.

## work.py: navigation, mining, construction

These wrap the `nav.*` methods (the Baritone engine); its read-only world
queries are `obs.scan`, `obs.terrain`, `obs.fluid` and `obs.tools`.

| Tool | Effect | What it does |
| --- | --- | --- |
| `mb_route` | action | Travels a saved corridor route, forward or reverse. |
| `mb_follow` | action | Follows loaded entities for a bounded time. |
| `mb_process` | action | Runs one upstream process: `goal`, `explore`, `get_to_block`, `farm`. |
| `mb_mine` | action | Quantity mining by block/item selectors in bounds or a radius; success is measured inventory gain. |
| `mb_scan` | read | Paged scan of loaded blocks by selector (`obs.scan`). |
| `mb_build_preview` | read | Fresh diff of a plan against the world plus material allocation. |
| `mb_build` | action | Executes explicit cells or a selection with the strict per-cell contract. |
| `mb_build_pause` | control | Pauses active build work; the `jobId` stays resumable. |
| `mb_build_materials` | read | Placeable states currently in inventory. |
| `mb_schematic_import` | read | Reads an MCEdit `.schematic` or a canonical JSON plan inside the game's `schematics/` directory into `{plan:{cells,origin,size},size,count,skipped,tileEntities}`. Sponge `.schem` and Litematica are not read. |
| `mb_schematic_build` | read/action | Imports, then previews (default) or builds the nested `plan`. |
| `mb_copy` | read/action | Copies loaded blocks in inclusive bounds into the same result shape, optionally rebuilding the nested `plan` at another origin. |
| `mb_work_status` | read | Durable job summary, progress and last receipt by `jobId`. |
| `mb_work_resume` | action | Resumes a stopped job after the cause is corrected; permissions are re-supplied each time. |
| `mb_settings` | action | Reads, sets or resets the pinned engine settings while idle. |
| `mb_cache` | action | Inspects or administers the terrain cache. |

Work completions and failures write a small `auto`-tagged note at the job's
location, so the next session finds where things stopped. Selectors,
net-gain completion, the two build modes, their settings and limits are in
[BARITONE_PORT.md](BARITONE_PORT.md).

`mb_memory` (defined in `core.py`):

- `protect {name, min, max, mode?}`: `automation` (default) keeps navigation
  and automatic excavation out but allows deliberate work and machine use;
  `all_edits` also rejects deliberate edits. Changing a region needs
  `overrideProtection: true`. It guards against accidents, not explosions,
  other players or mod area effects.
- `waypoint`, `route {name, points, radius}` (2 to 4,096 anchors, radius 1 to
  16, coordinates copied at save time), `record`; `replace: true` overwrites.
  Put anchors at turns and height changes. Per world: 1,024 waypoints, 128
  routes, 256 regions.
- For long `mb_route` trips raise both `timeout_s` and `timeout_ticks`.

## recipes_quests.py: NEI and Better Questing

| Tool | Effect | What it does |
| --- | --- | --- |
| `mb_recipe_status` | read | Whether the NEI catalogue and handlers are ready. |
| `mb_item_search` | read | Searches the item catalogue with pagination and exact variants. |
| `mb_item_info` | read | Tooltip, ore and fluid data, ItemBlock placement metadata for one variant. |
| `mb_fluid_search` | read | Fluid ids, names and properties. |
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

Recipe workflow: `mb_item_search` (keep `id`, `meta`, `nbt` together), then
`mb_recipes` with the default `limit=0` for the per-category overview, again
with `handler=<handlerKey>` and a small `limit` to compare options, then
`detail="full", index=<n>, limit=1` for the chosen one, then `mb_recipe_view`
and `mb_recipe_inspect` for whatever the handler only draws.

- Many GT categories share one handler id, so reuse the complete `handlerKey`.
  NEI order is not progression order; nothing is declared craftable for you.
- Ingredient positions keep all alternatives (`alternatives_offset`). Compact
  previews omit NBT and are not item identities.
- GT power and duration are base values before overclocking; some entries are
  informational `fakeRecipe` records; a zero-count input (circuit, tool) is
  retained. An empty ingredient list is not proof of no requirement: aspects,
  research and mutation conditions are only drawn, so open the page.
- `mb_item_info` gives `placement.blockId` and `placement.initialBlockMeta`,
  which for GT machines differs from the item metadata.

Quest actions only send Better Questing's normal packets and return
`serverAcknowledged: false`. A claim needs a valid choice for every choice
reward.

## interrupts.py: watches that wake the model

| Tool | Effect | What it does |
| --- | --- | --- |
| `mb_interrupt` | control | `add`, `remove`, `reload`, `status`, `ack` watches. A watch is declarative (`queries`, `conditions`, `effects`, `prompt`) or a Python file with `evaluate(context)`; effects are `notify`, `cancel`, `pause`. |
| `mb_interrupt_events` | read | Replays the durable event journal after a cursor; a host uses it to give the model a turn. |

Fires are retried with the same event id and the watch is re-armed if the
bridge cannot be reached; watches survive reconnects. `_examples/` holds a
custom-predicate example.

```json
{"queries": {"player": {"method": "obs.player"},
             "nearby": {"method": "obs.entities", "params": {"radius": 8}}},
 "condition": {"all": [{"lt": ["player.health", 8]},
                       {"any": {"path": "nearby.entities", "where": {"eq": ["$.hostile", true]}}}]},
 "effects": ["notify", "cancel", "pause"],
 "prompt": "Low health with a hostile nearby. Decide how to recover."}
```

- Operators: `all`, `any`, `not`, `eq`/`ne`/`lt`/`lte`/`gt`/`gte`, `exists`,
  `changed`/`increased`/`decreased`, and `any`/`all` over a collection.
  Operands are `[path, literal]`; a missing observation is a fault, not false.
  Also `consecutive`, `edge`, `cooldown` (seconds), `oneShot` (default true).
- At most 64 watches, polled about every 100 ms through `obs.batch` (16
  watchable reads). The guards in `mb_time` are faster for fixed emergencies.
- A custom file's `evaluate(context)` has `context.values`, `context.read`,
  `context.previous`, `context.state`, and may return
  `context.prompt(text, **observations)` to hand the decision to the model
  (8,192 characters, payload 64 KiB). Watch files are trusted, not sandboxed.
- `cancel` and `pause` latch further actions until `ack`, which neither
  resumes time nor retries anything. Re-arm a one-shot watch after recovery.
- The journal is SQLite under `.state/interrupts` (`MODBENCH_INTERRUPTS_DIR`).

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

- `mb_notes("capture", {"kind": "block", "pos": [x, y, z]})` returns the
  `worldId` and `attachment` that `mb_note_write` needs. Entity attachments
  use the server UUID and match spatially on the stored last-seen position.
- `expected_revision=0` creates; otherwise pass the revision you read. Only
  fields in `patch` change. Retry a lost reply with the same `operation_id`
  (the original receipt is replayed). `status` is `open`, `done` or
  `archived`; there is no delete.
- Search is AND-combined, defaults to the current dimension and returns
  excerpts; `get` returns the full text. `resolve` re-observes loaded
  attachments; `not_observed` does not mean destroyed.
- Limits: title 256 characters, text 32,768, 32 tags, 32 attachments, `data`
  16 KiB. One SQLite file per world UUID under `.state/notes`
  (`MODBENCH_NOTES_DIR`); keep it when moving the harness. Notes protect
  nothing: use `mb_memory("protect")`.

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

## Renamed in 2026-09

For operators and notes written against the earlier names. Parameters and
result shapes did not change.

| Old | New |
| --- | --- |
| raw `baritone.goto`, `mine_block`, `place_block`, `mine`, `build`, `resume`, `build_preview`, `build_stage`, `build_pause`, `build_materials`, `follow`, `process`, `route`, `cache`, `settings`, `status`, `work_status`, `schematic_import`, `copy` | `nav.<same name>` |
| raw `baritone.scan`, `baritone.terrain`, `baritone.fluid`, `baritone.tools` | `obs.scan`, `obs.terrain`, `obs.fluid`, `obs.tools` |
| raw `inv.find` | `obs.find` |
| raw `keys.list` | `obs.keys` |
| raw `keys.press` | `act.press_key` |
| raw `obs.hwyla` | `obs.waila` (the alias was removed) |
| `mb_builder_pause` | `mb_build_pause` |
| `mb_builder_materials` | `mb_build_materials` |
| `mb_nei_status` | `mb_recipe_status` |
| `mb_search` | `mb_item_search` |
| `mb_item` | `mb_item_info` |
| `mb_fluids` | `mb_fluid_search` |
| launcher `install-control`, `rollback-control` | `install-core`, `rollback-core` (client and server `mods/`) |
