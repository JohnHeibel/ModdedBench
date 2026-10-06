# Tools

How the `mb_*` tools work, the contracts that are easy to get wrong, and how
to add a tool or a bridge method. This file does not list the tools, because a
hand-kept list goes stale. The list comes from the code:

| Where | What it gives |
| --- | --- |
| [PROMPT.md](../PROMPT.md), section 8 | One line per tool, written from the `@tool` docstrings by `python harness/mcp/tool_table.py` |
| `python harness/mcp/server.py --check` | The tools as loaded now, without a game |
| `mb_tools_status` (in a session) | Modules, tools, lanes, live state keys and the last reload error |
| `mb_methods` (in a session) | The raw Java bridge methods the tools compose, each with its description, effect and thread |

## Where tools live

Every `.py` file under `harness/tools/` (files and folders starting with `_`
are skipped) is a tool module, re-imported when any file there changes.

| File | Domain |
| --- | --- |
| `core.py` | Status, raw method calls, observations, actions, keys, time, world memory, screenshots, the map |
| `inventory.py` | Containers, slots, item moves, crafting; `ContainerSession`, the helper for model-written procedures |
| `work.py` | Navigation, mining, building, fighting, schematics and copy, scans, engine settings |
| `recipes_quests.py` | NEI recipes and Better Questing |
| `interrupts.py` | Watches that wake the model, `mb_wait` |
| `notes.py` | World notes and the goal stack |
| `plan.py` | `mb_view`: the drawing, one format to look at a place, plan in it and build from |
| `scripts.py` | `mb_run`: a model-written script that chains tool calls as one call |
| `tasks.py` | `mb_task`: background tasks started with `mb_run(background=True)` |
| `wiki.py` | Search and read of the offline GTNH wiki snapshot (`harness/wiki/fetch.py`) |

`mb_reload_tools` and `mb_tools_status` are defined in `harness/mcp/server.py`.

## Conventions

- **Effect.** Every Java bridge method declares an effect: `read`,
  `interaction` or `privileged`. A read never changes the game. A Python tool
  has an effect too (`read` when its lane is `read`, else `interaction`,
  unless the decorator names one).
- **Lane.** A tool's lane is its thread pool in the MCP server: `read` (never
  starved by long actions), `act` (the default) or `control` (stop, pause,
  interrupts, tasks; a small dedicated pool, so they get through while actions
  are blocked). Dispatchers that take a `method` argument, such as `mb_call`,
  `mb_act` and `mb_memory`, pick the lane per call from the raw method's
  declared effect.
- **Receipts, not acknowledgements.** Actions return what was sent and what
  was observed immediately afterwards. Verify by observing again.
- **Notes ride along.** A `notes` key appears on results when a note is
  relevant to the transition (see World notes).
- **Long calls.** Mining, building, routing and following stay open until a
  terminal receipt. Their one timeout is `timeout_ticks`, in game ticks; the
  wall-clock wait is derived from it. Every way a job ends, a lost wait
  included, answers with its receipt: keep the returned `jobId`.
- **Resume in the call.** An acting tool takes `resume=True` or `resume=N` to
  resume a paused world, or step it N ticks, with the action starting on the
  first tick ([TIME_CONTROL.md](TIME_CONTROL.md)).

## Actions (`mb_act`)

Actions need running time, so resume first or pass `resume`.

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
  `attackTarget: [x,y,z]` is checked by the client against that lock in the
  same tick as the attack: if the lock is on another block, or there is none,
  the call fails with `attack_target_mismatch` and no attack is sent. It
  requires `attack` and disallows `allowRetarget`; observe the block again after digging.
- Receipts carry `nativeReturn` (not a success flag) and `serverAcknowledged`
  (only eating has one). An item may change NBT, metadata or slot, or drop an
  entity, so check the whole inventory.

## Tile reads (`mb_obs` with `tile`, `nbt`, `waila`)

These come from the server.

- Loaded blocks within 128 blocks in the current dimension; no position means
  the crosshair. A plain block returns `hasTile: false`; unloaded is an error.
- `nbt.handle` names an immutable snapshot that `obs.nbt {handle, path,
  offset, limit, budget, depth}` drills into. Handles expire after ten
  minutes, so put values worth keeping in a note.
- `fluids.views` has one view per side plus `UNKNOWN`. **Do not add side views
  together**; they may describe the same tank. `energy` covers GregTech EU,
  IC2 and RF; an unsupported system is absent, not zero.
- Pass `hwyla: false` to skip the Waila text when polling.

## Containers and items

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

## Navigation, mining, construction

The tools in `work.py` wrap the `nav.*` methods (the Baritone engine); its
read-only world queries are `obs.scan`, `obs.terrain`, `obs.fluid` and
`obs.tools`. Selectors, net-gain completion, the build contract and its limits
are in [BARITONE_PORT.md](BARITONE_PORT.md).

Work completions and failures write a small `auto`-tagged note at the job's
location, in the `auto/` subfolder of the notes. `mb_notes` `find` leaves them
out unless it asks for them (`auto: true`).

A build receipt has `placed`, `removed`, `left {count, first}` (cells still
wrong, the first 8), `step {stage, y, index, of, left, first}` (where the build
order stands) and `cost`. Any job's `cost` has `overBudget {tickMsMax, tickMsMean}`
only when it went over (`Cost`: a tick over 100 ms, or a mean over 5 ms across at
least 40 ticks), each figure there only if it is the one over. A plan with clicks adds `clicks {of, done, verified,
unverified, alreadyPresent, unverifiedFirst}` (the per-click list is the job's
`.clicks.jsonl`), and a finished job `accessLeft` and `scaffoldLeft
{count, first}` for what it took out or put up and could not put right. One
that did not succeed adds `stopped {reason, pos, step}`; `pos` is absent only
when no cell is to blame, and a stopped click is there in full as
`stopped.click`. The reasons:

| `stopped.reason` | What it says | Comes with |
| --- | --- | --- |
| `occupied` | `pos` wants a block and holds a different one, and `replace_existing` is false. A plan that starts that way is refused before any input. | `occupied {count, first}` |
| `missing_materials` | Nothing carried goes into any cell the order allows now. Nothing is checked up front: the job builds what it can first. | `missing [{selector, needed, allocated, missing}]` |
| `attempt_limit` | Eight clicks the game took into `pos` without the block appearing. | |
| `mismatch` | What is at `pos`, or what the click would make there, is another variant than the plan's (a facing). | |
| `no_stance` | No standing spot from which a face to place `pos` against is in view, or, where `pos` holds a block to remove, from which that block is. | `blockedBy` {pos, id}: what the view of a block to remove ends on |
| `no_route` | Everything reachable was searched and none of it is a place to work `pos` from. | `walk`, as for `stalled` |
| `stalled` | The stall watchdog (`stallTicks`, 200) fired and neither of the two above explains it. | `walk` {at, feet, onGround, movement, keys, touching, goal, inGoal, searching, searches, lastSearch}: where the body is, the step of its route it is on, the keys held, and each block whose collision boxes it touches with those boxes |
| `timeout` | `timeout_ticks` ran out. | |
| `requested` | `mb_build_pause`. | |
| `no_vantage` | A click: places to stand exist, and from none is the face in view and reach. | `stopped.click.blocking` |
| `look_unreachable` | A click: the face can be clicked, but not while facing the way `look` asks. | |
| `support_missing` | A click: no block stands where it would have to land. | |
| `hit_not_on_face` | A click: the `hit` point is not on the face named. | |
| `no_route_from_here` | A click: a stance exists and no walk reaches it. | |
| `aim_mismatch` | A click: the game's own ray hit something else from 16 stances. | `stopped.click.aimedAt`, `nativeHit` |
| `placement_rejected` | A click: the game took 3 clicks and no block appeared. | |
| `expect_failed`, `expect_timeout` | The click was made and the read did not show what `expect` asks, or did not answer. Not made again. | `stopped.click.expect` |
| `gui_opened` | A use opened a screen: closed, counted as made, and the job stopped. | |
| `unknown_after_restart` | A use was cut off between the press and its result; it is never pressed twice. | |
| `use_target_changed` | A use: the block at `pos` is not the `id` it names. | |
| `access_failed` | A block in the way of a click could not be taken out. | |
| `no_empty_hand` | A use with `{empty: true}` and no empty hotbar slot. | |

Endings every job shares are not build reasons and keep their own words:
`player_died`; the cancellations `superseded`, `interrupted`,
`request_deadline_elapsed`, `gui_opened`, `world_or_player_changed`,
`start_failed`; and a game exception as `<Class>: message`. Every stop is
resumable with `mb_work_resume`.

`mb_task` says a task's `args` back whole only when they are small; a large
plan comes back as `{omitted, bytes, sha256, keys}`.

## World memory (`mb_memory`)

- `protect {name, min, max, replace?}`: a box that jobs will not dig through
  or build in on their way. It is a rule for the path search and for a mine's
  choice of targets, and for nothing else: no click is ever refused for it.
  What it binds: blocks a walk, a mine or a build would break to get through
  or place to climb and bridge, a mine's targets inside the box, and blocks a
  build would take out of a click's way. What it does not: walking, doors,
  machines, `mb_act` clicks, single-block breaks and places, a fight, and the
  cells and uses a build names. To edit inside with a job, pass
  `override_protection=True` on that call (walks, mines, builds, follows,
  routes); it is per call and never saved. `replace: true` changes a region,
  `remove {kind: region, name}` deletes it, and either stops the running job.
  A walk the region refused fails as
  `<how the search ended>; the search was refused edits in protected_region:<names>`;
  a mine lists what it left under `skipped` as `protected_region:<names>`, and
  stops if its tool breaks a protected block beside the target. It guards
  against a job's own accidents, not explosions, fluids, other players or mod
  area effects.
- `waypoint`, `route {name, points, radius}` (2 to 4,096 anchors, radius 1 to
  16, coordinates copied at save time), `record`; `replace: true` overwrites.
  Put anchors at turns and height changes. Per world: 1,024 waypoints, 128
  routes, 256 regions.
- For long `mb_route` trips raise `timeout_ticks`.

## Recipes and quests

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

## Interrupts: watches that wake the model

A watch (`mb_interrupt`) is declarative (`queries`, `condition`, `effects`,
`prompt`) or a Python file with `evaluate(context)`; effects are `notify`,
`cancel`, `pause`. `mb_wait` blocks (1 to 900 s) until an event needs the
model, and a chat-style agent calls it instead of ending its turn.
`mb_interrupt_events` replays the event journal after a cursor.

Fires are retried with the same event id and the watch is re-armed if the
bridge cannot be reached; watches survive reconnects, and armed or undelivered
watches are persisted next to the journal and re-armed after an MCP server
restart. While a latch is set, the next acting call is refused
(`interrupt_latched: ...`) with each latched event's reason and prompt; that
refusal delivers them and releases the latch, so the call after it runs.
`harness/tools/_examples/` holds a custom-predicate example.

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

## World notes

The notes are files, and the model reads, searches, edits and deletes them with
its shell: `.state/notes/<world id>/<id>.md` (`MODBENCH_NOTES_DIR` moves
`.state/notes`; `mb_status` and `find` return the folder as `notesFolder`).
`mb_notes` does what a file search cannot (`find` by place, `capture` of an
anchor), `mb_note_new` creates a note with its anchors, and `mb_note_append`
adds a dated last line under a lock.

```
title: Smelting room
tags: plan, production
status: open
created: 2026-10-04T22:40:39+00:00
anchor: {"dimension":0,"kind":"region","max":[-615,70,644],"min":[-646,62,613]}
anchor: {"item":"minecraft:furnace","kind":"item"}

The text, from the first line after the blank one.
[2026-10-04T23:10] an entry added with mb_note_append
```

- The header ends at the first blank line. `title` is required; `status` is
  `open`, `done` or `archived`; lines with other keys are ignored. An anchor is
  one JSON object: `block` and `location` have `dimension` and `pos`, `region`
  `dimension`, `min` and `max`, `entity` a server `uuid` and `lastSeen`,
  `item` and `topic` their name. `mb_note_new` and `capture` write them;
  a block or entity anchor records what was observed.
- A header that does not parse costs the note its anchors, nothing else: the
  file is named with the reason under `notesUnreadable` by `mb_status` and
  `find`, and comes back when it is mended.
- When a note last changed is its file's modification time. `<id>.json`
  beside a note is its `data` (`mb_view` draws a plan from `drawing`).
  `auto/` holds the harness's journal of work outcomes.
- Harness writes take a lock per world (`<world id>.lock`), so a background
  task and the main thread can append to one note, and then commit the folder
  to its own git repository when git is installed: the model's edits are
  committed with the next harness write. There is no other history.
- A world whose notes were `<world id>.sqlite3` is exported to the folder the
  first time it is looked at; the database is left in place and not read again.
- Notes surface as a side effect, under a `notes` key, with at most five
  entries `{id, title, updated}`: on `mb_status` (session start), when a block,
  tile or entity with a note is observed, when a position read enters a noted
  region or comes near a note, when a work call arrives somewhere, and for an
  item note when the item is in an inventory, item or recipe result. A note is
  not repeated within ten minutes unless the player has moved far away or the
  file has changed.
- Notes protect nothing: use `mb_memory("protect")`.

## Adding a tool

```python
from mbtool import tool, kernel

@tool(lane="read", coverage=["obs"])
def mb_nearby_chests(radius: int = 16) -> dict:
    """Chests within radius of the player, with their observed contents."""
    ...
```

- Put it in any file under `harness/tools/`. The next tool call loads it. A
  duplicate name or an import error is reported (`mb_reload_tools`,
  `mb_tools_status`) and the previous tools stay registered.
- Parameters are plain typed values (`int`, `float`, `str`, `bool`, `list`,
  `dict`, optional with a default). The docstring becomes the tool's
  description, and its first sentence becomes the line in `PROMPT.md`. Return
  JSON-serialisable values; raise `BridgeError` or `ValueError` to fail.
- `@tool(rung, coverage, name, title, effect, lane)`: `name` defaults to the
  function name, `lane` to `act`; `lane` may be a function of the call's
  arguments. `rung` and `coverage` are descriptive only.
- `kernel().call("obs.player")` or `kernel().call("act.use_block", x=1, y=64,
  z=2)` calls a raw bridge method. `mb_methods` lists them.
- Keep live state in `mbtool.state[...]` so it survives the next reload.
- Run `python harness/mcp/tool_table.py` afterwards so the table in
  `PROMPT.md` follows, and add a test under `harness/tests/` with a fake
  kernel if the tool has any logic.

`harness/mcp/mbtool.py` is the contract; changing it, or anything else under
`harness/mcp/`, needs an MCP server restart.

## Adding a Java bridge method

Add Java only when Python cannot compose the result from existing methods,
because it costs a rebuild and a client restart.

1. Register it in the constructor of `ClientRuntime` (`mods/client`):

   ```java
   register("obs.example", "What it returns {param:type, ...}", "read", r -> Json.object("answer", 42));
   ```

   The arguments are the name, the description `mb_methods` shows (put the
   parameters in it), the effect (`read`, `interaction` or `privileged`) and
   the handler. A duplicate name throws at startup.
2. The handler gets the `Request` (`r.params` is the JSON object sent) and
   runs on the game thread. Return an object to reply; it is serialised with
   Gson. Throw `IllegalArgumentException` for a bad request (`bad_request`);
   any other exception is reported as `game_error`. Return `null` only to
   reply later through the request, as the long jobs do.
3. Keep it short: it runs inside a game tick. Work that takes more than a
   tick belongs in a job, and anything that reads many blocks should be
   covered by `TickBudgetTest` ([BUILD.md](BUILD.md#testing)).
4. Declare the effect honestly. A `read` must not change the game; only reads
   can be made available to `obs.batch` and to watches (`watchable(...)`), and
   `act.stop` cancels queued methods that are not reads.
5. Python reaches the client bridge only. A method on the dedicated server
   (`ServerRuntime`, `mods/server`) is registered the same way and needs a
   client method that relays it, as `time.*` and `obs.tile` do.
6. Navigation and work methods go through the `Navigation` interface in
   `mods/api`, which `BaritoneNavigation` (`mods/baritone`) implements and the
   client registers as `nav.*`; the Baritone jar may refer to ModdedBench only
   through `dev.modbench.api`.
7. Build, install and restart ([BUILD.md](BUILD.md)). `mb_call` reaches the
   new method at once; wrap it in a tool when it is worth a name.
