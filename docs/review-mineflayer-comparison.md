# Mineflayer / Mindcraft vs ModdedBench

A read-only review (2026-09-21) of how Mineflayer and Mindcraft expose Minecraft to an LLM,
compared with ModdedBench, and what ModdedBench should take. Sources: Mineflayer
`docs/api.md`, `lib/plugins/*`, `mineflayer-pathfinder` (`lib/astar.js`, `lib/goto.js`,
`lib/goals.js`, `index.js`); Mindcraft `main` (`src/agent/` and `src/models/prompter.js`).
ModdedBench citations are file:line in this repo.

The stacks are not peers. Mineflayer is a **headless protocol client** holding a local mirror
of the world: `bot.blockAt(point)` and `bot.findBlocks({matching, maxDistance, count})` are
synchronous scans at memory speed. Mindcraft is a thin LLM wrapper on top, for vanilla
survival and chat. ModdedBench drives a **real client over RPC** into a pack with ~30k item
variants, multi-minute machines and mod GUIs. Much of the gap follows from that — but the
token discipline and the fail-fast machinery do not, and both map straight onto the run data.

## 1. Side-by-side

### Observation model

| | Mindcraft / Mineflayer | ModdedBench |
| --- | --- | --- |
| Mechanism | Local mirror; reads are free and take arbitrary JS predicates | JSON-RPC round trip per read (`harness/tools/core.py:93`) |
| Push vs pull | **Push**: `physicsTick`, `health`, `death`, `entityHurt`, `blockUpdate` (and per-coordinate `blockUpdate:(x,y,z)`), `diggingCompleted`, `playerCollect`, `windowOpen`, plus `path_update`/`path_reset` | **Pull.** Push exists only as interrupt watches (`interrupts.py:427`), which the supervisor implements by polling bridge reads |
| What the model sees | Digested text. `!nearbyBlocks` collapses a scan of up to 10 000 blocks into a deduped `Set` of type names plus three positional facts; `!entities` aggregates to `- entities: 3 zombie(s)` | Raw structured JSON: `obs.player`, `obs.inventory`, `obs.container`, `obs.scan`, `obs.tile`, `obs.nbt`, `obs.waila`, `obs.light` (`ClientRuntime.java:113-160`) |
| State injection | `$STATS`+`$INVENTORY`+`$COMMAND_DOCS` rebuilt into the system prompt **every call** — Mindcraft's largest fixed cost, but it means the model never polls | `mb_status` is the heartbeat; otherwise the model must ask |
| Batching | n/a | `obs.batch`, 16 reads sharing one tick (`ClientRuntime.java:76`) — exists, and **no tool in `harness/tools/` composes it** |
| Size discipline | Hard caps everywhere (see below) | **None.** `mbtool.compact()` (`mbtool.py:165`) was written for this and is called by no tool |

Mindcraft's caps, all verified: **500 chars per action result** (`ActionManager.getBotOutputSummary`,
head-250 + `"...skipping many lines."` + tail-250), 500 chars of behaviour log per turn,
500 chars of long-term memory, `max_messages: 15` rolling history, `num_examples: 2` few-shot
picked from 29 by embedding, `relevant_docs_count: 5` skill docs picked from 36+ by embedding,
and **an interrupted action returns the empty string** — a preempted action costs zero tokens.

### Action vocabulary

| | Mindcraft | ModdedBench |
| --- | --- | --- |
| Count | 55 commands (41 actions + 14 queries); 51 visible after `blocked_actions`. Behind `!newAction`: 36 documented skill functions | 61 MCP tools over ~74 raw RPC methods |
| Abstraction | Task-shaped: `!collectBlocks(type, num)`, `!craftRecipe`, `!smeltItem`, `!digDown(distance)`, `!goToBed` | Two tiers: composite verbs (`mb_craft`, `mb_move_items`, `mb_mine`) and raw dispatchers (`mb_call`, `mb_obs`, `mb_act`, `mb_gui`) that hide a second vocabulary |
| Parameters | Typed with enforced domains: `{type:'float', domain:[10,512]}`, `BlockName`/`ItemName` validated against mcdata. Errors name the fault: `"Command !x was given 2 args, but requires 3 args."`, `"Invalid block type: foo."` | Python signatures plus long prose docstrings (`mb_act`'s is 25 lines, `core.py:109`); bounds re-checked by hand in each function |
| What the model is shown | Domains are **deliberately hidden** — `getCommandDocs` downcasts `float`/`int`/`BlockName` to `number`/`string` "to keep the prompt the same as before type checks were implemented". Enforcement teaches; the docs stay short | Everything is in the prose |
| Parsing | Regex over free text; only numbers, booleans and quoted strings. Everything after the first command is discarded | Real MCP structured calls |
| Identity | Flat `ItemName` strings | `{id, meta, nbt}` — correct, and unavoidable for GTNH |

### Completion, failure and how fast it fails

| | Mindcraft / Mineflayer | ModdedBench |
| --- | --- | --- |
| Return shape | One sentence, from `skills.log(bot, ...)`: `"Collected 3 oak_log."`, `"Unable to reach 100, 64, 200, you are 50 blocks away."`, `"Don't have right tools to harvest iron_ore."` | Structured receipt plus surfaced notes (`notes.py:568`). Explicitly not proof (`docs/TOOLS.md:17`) |
| Search failure | A* returns `{status, cost, time, visitedNodes, generatedNodes, path}`; `'noPath'` the instant the open set is exhausted — **milliseconds**, not a wall clock. `goto()` rejects with a named error: `NoPath`, `Timeout`, `GoalChanged`, `PathStopped` | `nav.goto` reports `path_calculation_failed` or `timeout` (`ReferenceNavigationJob.java:71,86`) |
| Stall detection | Two independent watchdogs: pathfinder's **3500 ms per-node futility timer** → `path_reset('stuck')`; Mindcraft's `unstuck` mode → **20 s** within 2 blocks with the same `targetDigBlock` (40 s for obsidian). Plus a fast-action-loop detector (actions <20 ms apart: cancel resume at 3, kill at 5) | **Absent.** `MiningProcess` has three structural checks — `pathlessTicks>100`, four rejections with no gain, `inventory_full` (`MiningProcess.java:64,116,124`) — none of which fire while the engine is actively pathing and gaining nothing. The only backstop is `timeoutTicks`, default 12000 = **10 minutes** (`BulkJob.java:31,43`) |
| Replan triggers | Wired to world events: `blockUpdate` near the path, `chunkColumnLoad` adjacent to a visited chunk | Engine-internal |

This is exactly the 75%-`mb_mine`-failure shape, and the recorded pond-bobbing stall is its
purest case: `MineProcess` stays active and keeps planning paths, so `pathlessTicks` resets
every tick, no rejection is logged, and the job burns ten minutes producing nothing.

### The code-writing path

| | Mindcraft `coder.js` | ModdedBench `mb_run` (`scripts.py:27`) |
| --- | --- | --- |
| Trigger | `!newAction(prompt)` — the model asks the harness to write the code, in a second LLM call | The model writes it directly in the tool call |
| Loop | `MAX_ATTEMPTS = 5`, `MAX_NO_CODE = 3`; regex check that every `skills.X` exists (`"These functions do not exist:\n"`), then ESLint with line/column, then run | One attempt |
| Failure to the model | `"Code Output:\n${code_output}\nCODE EXECUTION THREW ERROR: ${e.toString()}"` — **no stack trace** (an `err = err.toString()` bug makes `err.stack` undefined on the action path) | `{stopped, line, source, log}` — exception, line number, the source line itself, last 40 log lines |
| Scope | SES compartment with exactly `{skills, log, world, Vec3}` + `Math`, `Date`; off by default (`allow_insecure_coding: false`) | Every `mb_*` tool plus `log()`; optional persistence to `harness/scripts/<name>.py` |
| Abort | Source rewriting: `code.replaceAll(';\n', '; if(bot.interrupt_code) {...return;}\n')` — every statement boundary becomes a checkpoint | First tool error stops it; 20-minute wall clock |
| Doc supply | Top 5 of 36 JSDoc blocks, embedding-ranked on the `!newAction` text, plus 3 always shown | All tools in scope, described once in the MCP table |

ModdedBench's is the better design for a frontier model — no retry ladder, no second prompt,
better diagnostics. The one missing piece is Mindcraft's **pre-flight name check**.

### Memory

Mindcraft: `memory_bank.js` is 27 lines of `{name: [x,y,z]}`; long-term memory is a single
500-char LLM summary regenerated from 5-turn chunks; there is no vector store over episodic
memory at all. ModdedBench: per-world SQLite notes on blocks, entities, locations, regions,
item types and topics, with revisions and **automatic surfacing** on arrival/observation/session
start (`notes.py`), a goal stack with a staleness signal, and Java-side waypoints, corridor
routes and protected regions. Not close. Nothing here should change.

### Interrupts and self-preservation

Mindcraft's `modes.js` runs a 300 ms tick loop of ten prioritised modes that **act**:
`self_preservation` (ON, interrupts all), `unstuck` (ON), `self_defense` (ON, fights within 8),
`cowardice` (`on: true` in source but `false` in all four shipped profiles), `hunting`,
`item_collecting`, `torch_placing`, `elbow_room`, `idle_staring`, `cheat` (OFF). When a mode
preempts an action with no resume handler it auto-reprompts:
`"(AUTO MESSAGE)Your previous action '${x}' was interrupted by ${mode.name}."`

ModdedBench's guards (`healthDrop`, `healthBelow`, `airBelow`, `foodBelow`, `burning`,
`threatWithin`, `pauseOnDisconnect`, `core.py:198`) **pause the whole simulation and never
act**, with an admission latch until `interrupt.ack` and durable replayable events. More
honest and more capable. The only thing Mindcraft has that ModdedBench lacks is detection
that the bot is stuck at all.

### Agent loop and prompting

Mindcraft: `self_prompter.js` loops while not interrupted, sending each iteration
`"You are self-prompting with the goal: '${this.prompt}'. Your next response MUST contain a
command with this syntax: !commandName. Respond:"`, stopping after `MAX_NO_COMMAND = 3`
command-less replies, 2000 ms cooldown, auto-restart after 2 s idle. Within one iteration
command chaining is unbounded (`max_commands: -1`). A supervisor process restarts a crashed
agent and injects `'Agent process restarted.'`; `!smeltItem` deliberately restarts the whole
process to refresh inventory.

ModdedBench: `codex_loop.py` runs one `codex exec` then `resume` per turn; a turn can last
hours; inside a turn the model stays alive by blocking in `mb_wait` rather than ending
(`interrupts.py:465`). Restart text is one sentence (`codex_loop.py:23`). `PROMPT.md` is
~9,000 words of doctrine against Mindcraft's paragraph. `feed.py:112` states the cost model:
every call is billed for the whole context again, so **cost ≈ context × calls** — both call
count and result size matter, and a long one-off system prompt is cheap by comparison.

## 2. What to adopt, ranked by value/effort

### 1. Cap and truncate every tool result (very high / very low)

**Them:** a hard 500-char cap on every action result, head/tail split with an explicit
`"...skipping many lines."` marker; `!nearbyBlocks` dedupes to a type set; `!entities`
aggregates to counts; empty sections collapse to `'INVENTORY: Nothing'`.
**Us:** `mbtool.compact()` (`mbtool.py:165`) is dead code.
**Change:** apply it in the tool dispatcher with a per-tool budget; make `mb_inventory`
default `detail="counts"`; give `mb_recipes` (`recipes_quests.py:135`) a one-line-per-row
summary with a hard cap; group `obs.entities` by type. Keep an explicit `"…truncated, N more"`
marker so the model knows to page rather than assuming it saw everything.
**Effect:** directly attacks the 16k `mb_recipes` and 7–11k `mb_inventory` averages.

### 2. A state digest on every action receipt (very high / low)

**Them:** every skill ends in `log(bot, ...)` and that string *is* the return value, so the
model learns outcome and state together; Mindcraft additionally re-injects `$STATS`/`$INVENTORY`
into the system prompt each call, so it never polls.
**Us:** `notes.tracked` (`notes.py:568`) attaches notes and nothing else; no act-lane tool
returns player state. Hence the constant `obs.player`/`obs.inventory` polling.
**Change:** in the `@tool` decorator for act-lane tools, append a `you` key from one
`obs.batch` (`ClientRuntime.java:76`): pos, health/food/air, held item, free slots, time of
day, `threats`. ~150 chars. Fairness-neutral — it is what the HUD and F3 already show.
**Effect:** removes most polling. At 1,300–3,300 calls per run and cost = context × calls,
a 15% call reduction pays for the extra batched read many times over.

### 3. A no-progress watchdog on `BulkJob` (very high / medium, Java)

**Them:** two calibrated watchdogs — pathfinder's 3500 ms per-node futility timer
(`path_reset('stuck')`) and Mindcraft's `unstuck` mode at 20 s within 2 blocks. Both are
*position*-based, not goal-based, which is why they catch bobbing.
**Us:** `BulkJob.tick()` (`BulkJob.java:37-50`) checks lease, world, health and `--remaining<=0`;
`MiningProcess`'s three checks all miss an actively-pathing no-gain loop.
**Change:** track a progress tuple in `BulkJob` (job-specific: `gained()` for mining, goal
distance for navigation) plus player position variance. If neither moves for K ticks —
**300 ticks (15 s) is the right order given the two upstream numbers** — `finish("failed",
"no_progress")` with the last goal, the last path and the position samples in the receipt.
Expose K as `stallTicks` so the model can raise it for a legitimate machine wait.
**Effect:** the dominant `mb_mine` failure becomes a 15-second failure with a diagnosis
instead of a 10-minute timeout without one. Over long runs this is hours.

### 4. `mb_craft_plan`: recursive recipe resolution in one call (high / medium)

**Them:** `!getCraftingPlan(targetItem, quantity)` — "a breakdown of required ingredients,
the exact quantities needed, and an analysis of missing ingredients or extra items needed
based on the bot's current inventory" — one call for what the model would otherwise walk by
hand.
**Us:** `PROMPT.md` §4 tells the model to "look up its ingredients the same way until you
reach things you hold", and it does, one 16k `mb_recipes` call per node.
**Change:** a Python composition over `nei.recipes` + `obs.inventory` that expands the tree to
a depth/breadth bound, prunes at items already held, and returns the shortfall. It cannot
choose the route in GTNH (voltage, circuits, fluids), so return the *cheapest-looking* chain
plus the branch points rather than a decision.
**Effect:** the largest per-call sink becomes one call per quest task instead of five to ten.

### 5. Batch and digest the quest reads (high / low)

**Us:** `quest.observe` takes one `questId` (`recipes_quests.py:47`); the run called it ~200
times singly.
**Change:** `mb_quest_observe(quest_ids=[...])` plus `detail="digest"` (title, task types,
progress fractions, reward count) with `detail="full"` for the active quest only.
**Effect:** ~200 calls to 10–20, each far smaller.

### 6. Downscale the screenshot; sell `mb_view` as the eye (high / low)

**Them:** Mindcraft is text-first; vision is off by default (`allow_vision: false`) and never
ambient — only explicit `!lookAtPlayer` / `!lookAtPosition`.
**Us:** `mb_screenshot` (`core.py:161`) returns the raw framebuffer, 133k chars, the single
largest sink.
**Change:** add `scale` (default ~0.5) and `crop`; in the docstring and `PROMPT.md` §8 point at
`mb_view` (`plan.py:43`) and `mb_map` first. The run used `mb_view` 30 times and never planned
a build with it, which suggests it is sold as a *build planner* rather than as *how you look
at a room*.
**Effect:** 4× on the biggest line item with no loss when pixels are genuinely needed.

### 7. Enforce domains, shorten the prose (medium / low)

**Them:** domains are machine-enforced and errors name the fault, but are **deliberately kept
out of the prompt** to save tokens. The model learns from rejections, not from documentation.
**Us:** `mb_act`, `mb_mine`, `mb_time` docstrings run 15–25 lines, re-stating bounds the code
re-checks anyway; the whole table is in the model's context every turn.
**Change:** put ranges and enums in the schema, cut the docstrings to what is *not* inferable
— what a tool verifies, and what its receipt does not prove. The generated table in
`PROMPT.md` §8 shrinks with it.

### 8. Pre-flight name check in `mb_run` (medium / low)

**Them:** `coder.js` regex-extracts called `skills.X`/`world.X` names and rejects unknown ones
before executing.
**Us:** `mb_run` execs immediately (`scripts.py:59`).
**Change:** compile, walk the AST for called names, reject unknown `mb_*` with near-miss
suggestions before the first game action. Stops a typo leaving a half-done chore in the world.

### 9. Per-job movement config, and protection as cost (medium / medium)

**Them:** `Movements` is declarative per goal — `canDig`, `allowParkour`, `maxDropDown`,
`blocksToAvoid`, `dontCreateFlow` — plus cost hooks `exclusionAreasStep/Break/Place:
Array<(block) => number>` where "100 means it is impossible".
**Us:** `mb_settings` (`work.py:227`) is global and pinned; per call the model gets only
`allow_break`, `allow_place`, `beside_fluid`, `override_protection`.
**Change:** accept a `movements` dict on `mb_mine`/`mb_process`/`mb_route`, applied scoped and
restored on release — `MiningProcess` already does this with `scopedSettings`
(`MiningProcess.java:48,132`). Separately, consider expressing `mb_memory` protected regions as
a **high cost** rather than a hard refusal, so navigation routes around the base instead of
failing at it. (Note `beside_fluid` is already a better `dontCreateFlow`.)

## 3. What ModdedBench does better, or must not copy

- **Receipts, not booleans.** `"Successfully smelted raw_iron, got 5 iron."` is a claim. In
  GTNH a machine acknowledging an insert says nothing about output 400 ticks later. The split
  between receipt and re-observation (`docs/TOOLS.md:17`) must survive everything above — a
  state digest is additional evidence, never a completion claim.
- **Guards pause, never act.** Copying `modes.js` — a bot that fights, flees, picks up items
  and places torches by itself — would break the fixed constraint that the harness never plays
  for the model, and make results uninterpretable. Take the *detection* from `unstuck`, not the
  behaviour.
- **Durable jobs.** `jobId` surviving a client restart, `WorkJournal`, `mb_work_resume`.
  Mindcraft's actions die with the process, and `!smeltItem` deliberately kills the process to
  refresh inventory. Keep ours.
- **Script diagnostics.** `mb_run` returns the failing line and its source; Mindcraft's coder
  path sends `e.toString()` and a literal `undefined` stack trace. Keep ours.
- **World notes and the goal stack** are a generation past `memory_bank.js`. Keep.
- **Do not mirror the world in Python.** Mineflayer's free reads come from owning the protocol.
  Re-implementing `findBlocks` in Python would break the seam rule (`ARCHITECTURE.md`) and the
  fairness line. Batch and digest; do not cache.
- **Flat item names do not survive GTNH.** `{id, meta, nbt}` plus NEI is right. Mineflayer has
  no modded-registry story at all — `bot.recipesFor` reads vanilla `minecraft-data`, which is
  why `!craftRecipe` can exist and why ModdedBench needs "assume no recipe" instead.
- **`cheat` mode and `placeBlock(..., dontCheat=false)`** are flatly against rule §6.
- **Free-text command parsing.** Mindcraft's regex accepts only numbers, booleans and quoted
  strings — no arrays, no nesting — which alone would make `mb_build(cells=[...])` impossible.
  Structured MCP calls are the right choice.
- **The long prompt.** `PROMPT.md`'s doctrine is doing real work (factory thinking, side-quest
  judgement, harness editing) and the runs show the model acting on it. It is paid once per
  thread, not per call. Do not shorten it for tokens.

## 4. Open questions for the owner

1. **Screenshots.** Is vision ever load-bearing, or would `mb_view` + `mb_map` + `obs.container`
   cover it? Half-scale by default, or demote it in the prompt to "when the structured view
   failed"?
2. **The digest.** On every act-lane receipt, or opt-out per call? It costs one batched read
   per action against a 15%+ call reduction.
3. **Stall window.** Upstream calibration is 3.5 s (per node) and 20 s (position). Is one
   `stallTicks` default right, or per-job-kind, given that legitimate GTNH work includes
   standing still at a machine?
4. **Truncation and honesty.** Capping results conflicts slightly with "the model may edit
   everything and should see the truth". Is an explicit `"…truncated, N more"` marker plus a
   paging parameter enough?
5. **Surface size.** 61 tools against Mindcraft's 51, but ModdedBench's tail (`mb_cache`,
   `mb_schematic_import`, `mb_recipe_inspect`, `mb_build_materials`) is rarely used. Collapse
   the tail behind `mb_call` to shorten the table the model carries every turn, or is
   discoverability worth more? Mindcraft's answer — retrieve the 5 relevant docs per call — is
   not available over MCP, but shrinking the always-on set is.
6. **`mb_run` as the default.** Mindcraft, Voyager and this harness all found that generated
   code beats step-by-step tool calls for known chores. Should `PROMPT.md` make `mb_run` the
   first choice for anything over three steps rather than the "known chore" fallback?
7. **Protection as cost vs refusal** (item 9) — is routing-around-the-base worth the change in
   semantics?
8. **Server drift.** The MCP server reachable from this session advertises tools that exist
   nowhere in the repo (`mb_sequence`, `mb_describe`, `mb_look_around`, `mb_wait_until`,
   `mb_diagnose_stuck`, `mb_coverage`). Stale server process, or a second profile that should be
   reconciled with `harness/tools/`?
