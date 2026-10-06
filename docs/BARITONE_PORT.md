# Baritone port

`mods/baritone` is a source port of [Baritone](https://github.com/cabaletta/baritone)
v1.2.19 (commit `d9cb2d91a06501c5bcba2181509d0df80361f413`) to Minecraft
1.7.10 / Forge, packaged as a plain `@Mod`. It is not binary compatible with
any Baritone release and makes no claim of full parity. The bridge works
without it; only `nav.*` and `obs.scan`/`terrain`/`fluid`/`tools` need it.

## Source layout and provenance

| Location | Contents |
| --- | --- |
| `src/upstream/java` | 156 upstream files, adapted in place. `UPSTREAM_SOURCES.json` records each file's original path and SHA-256; the Gradle build verifies them. Modified files carry a notice. The hash pins provenance, not text identity after adaptation. |
| `src/main/java/baritone/compat` | Version boundary: coordinates, vectors, block state as registry id plus metadata, loaded-chunk index, native placement, inventory swaps, events, rendering. No fake `net.minecraft` classes. |
| `src/main/java/baritone/gtnh` | ModdedBench side: `BaritoneNavigation` (the `Navigation` implementation the client registers), job wrappers (`Reference*Job`, `MiningProcess`, `ReferenceConstructionProcess`), the validated build plan (`ConstructionPlan`), `PlanImport`, `WorkJournal`, tool and placement adapters. |
| package `baritone.gtnh.pathing` | Minecraft-free code written for this project and unit tested without a game: work and construction spec validation (`WorkSpec`, `ConstructionMask`), the build order and break rule (`BuildSteps`, `PlanBreaks`), `DeferredClearance`, the corridor constraint (`Corridor`), `GoalRange`, and terrain observation helpers (`TerrainGrid`, `CollisionBox`, `LadderFacing`, `FluidPolicy`). `Move` and `MoveRegistry` are how a kind of movement is added to the search, with the added ones in `baritone.gtnh.moves` ([MOVEMENTS.md](MOVEMENTS.md)). There is no second path search; all routing is upstream's. |

The GUI input transformer and widget inspection in `mods/core` and
`mods/client` are this project's own code, not Baritone's; the client has no
dependency on the Baritone jar.

## What runs the upstream engine

| Surface | Upstream code in use |
| --- | --- |
| `nav.goto`, route legs, travel inside work jobs | `CustomGoalProcess`, `PathingControlManager`, `PathingBehavior`, `AStarPathFinder`, `Path`, `PathExecutor`, and the real movement classes (traverse, ascend, descend, fall, downward, pillar, diagonal, parkour) with their costs, lookahead, splicing, revalidation and timeouts. |
| `nav.mine` | `MineProcess`: multi-target goals, upward mining, pruning, blacklisting, drop collection. `WorldScanner` and the persistent location cache back discovery. |
| `nav.build` | `BuilderProcess` (`onTick`, `recalc`, `assemble`) with `InventoryBehavior`, `InventoryPauserProcess`, `BlockBreakHelper`, `BlockPlaceHelper`, `InputOverrideHandler`. |
| `nav.process` | `goal` (the upstream goal family), `explore`, `get_to_block`, `farm`. Backfill is compiled in. |
| `nav.follow`, `nav.cache`, `nav.settings` | `FollowProcess`; cached world and regions; `SettingsUtil` behind a structured endpoint that applies typed edits atomically while the engine is idle. |
| In game | Path, goal and selection rendering, the event bus, free-look (visible aim is the default), a local `/baritone` command. |

ModdedBench wraps every job in the shared input lease, protected-region checks,
a tick budget and, for mining and building, a durable journal. Input changes
are staged until a movement finishes its update. A cancelled search stays
cancelled and a superseded search cannot install its result into a newer job.

## Version adaptations

- The 1.7 client `posY` is eye-relative: movement uses `boundingBox.minY`, and
  placement prediction projects the sneak eye-height transition and queries
  the native crouch state (GregTech widens pipe targeting while sneaking).
- Bottom-slab feet goals are translated to upstream's cell-above convention
  and renormalised when the slab loads. `isBlockNormalCube` is used for
  support, not the opacity-based `isNormalCube`.
- Loaded chunks are indexed on the client thread (vanilla list and
  Hodgepodge's fast map). Search never requests chunk loads.
- Tool snapshots keep stack NBT and metadata and use Forge dig-speed and
  harvest hooks. Positive infinite break strength is a one-tick break; unknown
  position-dependent hardness is impassable rather than guessed.
- State caches key by block identity and metadata.
- Anticipated-drop expiry counts simulation ticks, so a pause does not exhaust
  it; the grace is 1,000 ms of simulation (upstream: 250 ms).
- Not synthesised because 1.7.10 lacks them: auto-jump, Frost Walker, Depth
  Strider, upstream's elytra flight, moving world border.

## Not ported or unsupported

| Item | State |
| --- | --- |
| Upstream chat command framework, `GuiClick`, multiple bots, `baritone.api` binary compatibility | Not implemented. MCP tools cover the operations. |
| Flight | Gliding on worn wings is an added move, and other moves that take off and land are added the same way ([MOVEMENTS.md](MOVEMENTS.md)). Resting in the air (hover, creative flight) is not built. |
| Water-bucket fall recovery | Disabled: the clutch is a provider boundary with no verified fluid-container provider. |
| Fluids other than vanilla water | Not swum or waded: lava, any fluid at 500 K or hotter, and every modded fluid (`FluidPolicy`: `unverified_fluid`), whatever its temperature. Vanilla water, still and flowing, is swum, with breath as a limit of the search; `harness/smoke/movement_course.py` has the cases (currents, streams, a waterfall, dives). |
| Breaking a block with liquid directly above | A walk does not (upstream's `MovementHelper.avoidBreaking`). A mining job with `besideFluid` (`mb_mine` turns it on whenever `allow_place` is on; it needs a throwaway block in the hotbar) breaks blocks beside and under a liquid other than lava and plugs the hole; lava is still refused. |
| Snow of three or more layers | Treated as not walkable, as upstream does. |
| Silent (packet-only) look | Not migrated. Desktop notifications go to the log and chat. |
| Explore | No survival handling inside the process: the time guards pause, nothing fights or flees. |

## Mining contract (`nav.mine`, `mb_mine`)

- 1 to 64 block selectors (`id`, optional `meta`, optional `item`/`ore` tested
  against the native pick-block stack), 1 to 64 output item selectors
  (`id`, `meta`, exact SNBT, ore name), a `quantity`, and either scan bounds or
  a radius. A scan is at most 262,144 cells; bounded mining does not explore
  outside it.
- Success is **net inventory gain** of matching items reaching `quantity`,
  across resumes. Breaking a block or seeing a drop is not a receipt. A full
  inventory is a failure.
- Requested targets are exempt from `allowBreak: false`, scoped to position,
  block and metadata. `allowBreak`/`allowPlace` authorise incidental route
  edits, which may fall outside the scan bounds. Region protection applies to
  both the route and the targets; `overrideProtection` is per job.
- The job does not return the player to where it started. Mining soil-like
  targets can leave the player at the bottom of a 1x1 shaft it dug under its
  own feet (seen live 2026-09-20); leaving needs `nav.goto` with `allowBreak`
  or `allowPlace`, which pillars out with whatever blocks are held.
- `nav.mine_block` (one block) reports `actualAim` and stops after 20 ticks of
  native target mismatch instead of breaking whatever is in the way.

## Construction contract (`nav.build`, `nav.build_preview`)

Exactly one of explicit `cells` or a `selection` (`fill`, `replace`, `walls`,
`shell`, `clear`, `sphere`, `hsphere`, `cylinder`, `hcylinder` with an `axis`);
`uses` (Clicks, below) may come with either or stand alone.
A cell is `{pos, id, meta?}` or `{pos, clear: true}`, with optional `item`
(the inventory selector used to place it: `id`, `meta`, `nbt`, `ore`),
`verify: {pickedItem}`, `replace`, `stage`, `click` and `expect`. A cell, or a selection's
`block`, that names no `meta` accepts any variant of its block (the facing a
furnace, chest or machine takes from how it is placed); one that names it is
exact. Block state and placement item are separate because a machine's item
metadata is often not its block metadata. `tileNbt` and `nbt` on a cell are
rejected, never dropped silently, and so is every field the job would ignore:
there is no `mode`, no `settings` and no per-cell `placement`.

A build runs one way (`ReferenceConstructionProcess` around upstream
`BuilderProcess` scheduling):

| | |
| --- | --- |
| Engine settings | Upstream's builder defaults for the length of the job, whatever `nav.settings` holds (saved and restored around it); the whole inventory is usable |
| Cell limit | 4,096 cells a job (`WorkSpec.CELLS`). A selection's box may span up to 262,144 cells as long as its shape keeps no more than the limit |
| Edits outside the plan | Placing (scaffold, bridge, pillar) needs `allowPlace`; breaking needs `allowBreak`. Inside the plan neither is needed |
| Wrong block in a plan cell | Dug out only with `replaceExisting` (or when a placement would replace it anyway, see Occupied cells). A cell that already matches is never dug through |
| Materials | Nothing is checked up front. The job builds what it can; when nothing carried goes into any cell it is shown, it pauses as `missing_materials` with the list |
| Attempts | Eight clicks the game took into one cell without the block being seen there |
| Completion | Fresh comparison of every cell: block identity, metadata where the cell named one, and `verify.pickedItem` |

The cell limit is the game thread's budget. The job walks every plan cell once
a tick (`survey()`: block and metadata of each, the step counts, the receipt's
counts), and that walk is its standing cost. At an estimated 200 ns a cell the
limit is about 0.8 ms a tick; that figure is an estimate, not a measurement.
The measurement is the receipt's `cost` (`tickNsMax`, `tickNsMean`: Baritone's
game-thread time per client tick over the job, the survey included). When the
worst tick is over 100 ms, or the mean over 5 ms across at least 40 ticks, the
same map says so in `overBudget {tickMsMax, tickMsMean}` (`Cost.status()`, the
limits beside it), and says nothing otherwise.

Placement goes through native right-click handling and never writes blocks.
Preview is a fresh loaded-world diff judged as the job judges it: counts
(`total`, `correct`, `mismatched`, `unloaded`, `conflicts`,
`unsupported`, `missingItems`), a shared-stack material allocation, the first
8 differences and the steps; lists are short and the counts say how much there
is. A `replace` selection is filtered once at job creation and journaled.
Requested air that starts empty is deferred while temporary supports are
needed, then cleared in a final phase; status exposes `buildPhase` and
`deferredAirCells`.

Build order (`BuildSteps`, always on). Every cell that must hold a block has a
step: its `stage` (a cell field, 0 first; a drawing's `stages` list assigns it
by legend character), then its height. Each tick the job counts the wrong
cells of every step in the walk over the plan it already makes, takes the
first step with one left as current, and shows upstream `BuilderProcess` only
the cells of steps up to it: later cells are out of its schematic and refused
to movement placement, so neither a plan block nor a throwaway lands in one
early. When the step moves on the source pass is restarted with the larger
schematic. The step is never stored: a resumed job reads it from the world,
and within a job it only moves forward (a cell of an earlier step that breaks
stays shown and is repaired). Cells that must be empty have no step, and
removal is not delayed: the walk may still dig a wrong block out of a later
cell, but the builder replaces it only in its step. Upstream's own layering
(`buildInLayers` and its companions) is held off for the job. Within a step,
a cell whose filling would close the player's last walk out of the plan's box
(`BuildSteps.shuts`: level, down, or up one with headroom, through cells the
game gives no collision box) is kept out of the pass while anything else is
left to fill, so the builder leaves before it closes a box around itself;
when such cells are all that is left in the step they go in: from inside when
the finished plan leaves a body room there (`BuildSteps.room`: a hut), and
otherwise (an oven, whose top goes where the builder stands) only after the
player has walked out of the plan's box, from a standing spot that the cell
does not shut (`BuildSteps.shut`). Half a stall ends the wait of the others.
Upstream `assemble` is edited in one place: a cell of flowing liquid whose
block is carried gets a goal as a source does. Preview lists
`steps` as `{stage, y, cells}`.

Stops. A job that does not succeed ends with `stopped {reason, pos, step}`:
one reason, the one cell it is about (absent only when no cell is to blame)
and that cell's `{stage, y}`. `left {count, first}` is every cell still wrong
(the first 8) and `step {stage, y, index, of, left, first}` where the order
stands. The reasons a build has of its own:

| Reason | Meaning | State |
| --- | --- | --- |
| `occupied` | `pos` wants a block and holds a different one the job may not remove (`replaceExisting` false). At begin: before any input, with `occupied {count, first}`. Mid-run: once nothing else of the steps so far can be placed or cleared | failed at begin, else paused if the session did something |
| `missing_materials` | Upstream has nothing it can do for the cells it is shown and `pos` is one no carried item places; `missing` lists up to 16 `{selector, needed, allocated, missing}` | paused |
| `attempt_limit` | The eighth click the game took into `pos` still did not make the block appear | paused |
| `mismatch` | The click about to be taken would make another variant at `pos` than the plan's (nothing is placed), or a block this job placed came out as another variant, or upstream holds every shown cell done and the plan's own comparison does not | paused |
| `no_stance` | The stall watchdog fired and every cell still workable has no standing spot from which a face to place it against (or, for a block to remove, that block) is in view; `pos` is the first, `blockedBy` what the view of a block to remove ends on | paused if the session did something, else failed |
| `no_route` | The watchdog fired and the last path search covered everything reachable without finding a place to work from | as above |
| `stalled` | The watchdog fired (`stallTicks`, 200) and neither of the two above explains it, or upstream paused with material in hand | as above |
| `timeout` | `timeoutTicks` ran out | as above |
| `requested` | `nav.build_pause` | paused |

Any job can also end `player_died` (failed), cancelled (`superseded`,
`interrupted`, `request_deadline_elapsed`, `gui_opened`,
`world_or_player_changed`, `start_failed`), or failed with the game's exception
as the reason. Success is `schematic_verified` (or `empty_selected_schematic`).
Every stop leaves the job resumable through `nav.resume`.

Occupied cells. One meaning of an empty cell is used everywhere
(`LegacyPlacement.empty`): air, or a block the game replaces when another is
placed into it (tall grass, a snow layer, water). Such a cell needs no
`replaceExisting`: the job may break what is in it, and a click on it is
counted for the cell itself, where the block lands. A cell that wants a block
and holds a different solid one is `occupied`. Cells to be cleared are never a
conflict. Preview counts conflicts with the job's own `replaceExisting`.

Attempts. A cell is charged for a click only when the game took it
(`onPlayerRightClick` returned true). The count lives for the session and is
dropped when the cell is seen to match, so a resume or a later repair starts
from none.

Clicks (`ClickRun`, inside the same job). A cell with `click {face?, hit?,
look?, sneak?}` (or only `expect`) is left out of upstream's schematic and
placed by the click executor; `uses [{pos, item | {empty: true}, click?,
expect?, id?, stage?, name?}]` are right clicks on blocks that stand. Each
stage is three steps of the build order: its plain cells by height
(`BuilderProcess`), its click cells, its uses in the order given. The job is
one: one jobId, journal, tick budget, receipt and stop shape; a resume reads
the step from the world and the journal's per-click results.

| | |
| --- | --- |
| A click | Made with the use key through the input lease, from a stance the pure search (`ClickSearch`, `Vantages`) found in a copy of the blocks around it: a place to stand, the face in line of sight and reach, the look the cell asks for. Checked against the game's own ray before the press |
| Order of click cells | `StepPlan.next`: what a click lands on stands before it, and a cell whose block would hide every way of making another goes after it. Complete at the cap |
| Limits | 256 clicks a job (`StepPlan.CLICKS`); the click cells of one stage inside 200,000 copied blocks, else refused at begin as `clicks_too_spread` |
| `expect` | Up to 4 `{method: obs.*, params?, pos?, path, equals \| contains \| changed: true}` a click, read through `Observations` after it (and before, for `changed`). Compared, never interpreted |
| Walks | `ReferenceNavigationJob` under the job's lease with `Baritone.editAllowed` set: a walk neither breaks nor places in a plan cell or a cell that is out for access |
| Access | Only with `allowBreak`: up to 3 cells in the way of a click are taken out and the same blocks put back (`Access`). Never a tile entity, a fluid, a plan or protected cell, a cell within 4 of a tile entity, or a block that could not come back (none carried, and it does not drop itself) |
| Scaffolds | Blocks the job placed outside the plan (builder, walks) are recorded by the `placed` consumer and taken away when the job ends |
| Closing | A job that ends by itself or is paused with cells out or scaffolds standing runs on in state `closing` (budget 1,200 ticks and 100 a cell, at most 6,000) and then ends with the reason it had. What it could not put right is `accessLeft` / `scaffoldLeft {count, first}`, journaled, and a resume starts with it. A hard cancel (death, world change, supersede) does not close; the journal rows remain for the resume |
| A use | Journaled `taking` before the key goes down and never pressed again: cut off in between, the resume stops as `unknown_after_restart`. One that opens a screen has it closed and stops the job as `gui_opened` |

A cell that names `meta` and has no `click` is placed from wherever the walk
stands, and stops as `mismatch` when that click would make another variant.

Doors and other non-block items. A plan cell whose item is not a block item
(a door, a bed) is made a click cell by `ConstructionPlan` and clicked in by
`ClickRun`; the item is the one the game picks for the block when the cell
names none.

`PlacementStateAdapters` predicts the placed metadata for known callbacks,
including GregTech's machine item registry; anything else is verified after
the real placement. Tile inventories, tile NBT, machine configuration and
multiblock formation are outside construction: do them with interaction and
GUI tools and verify with `obs.tile`.

## Durable jobs

Each mining or building job writes `modbench/work/<jobId>.json` (checkpoint
and last receipt), `<jobId>.spec.json` (the frozen specification) and
`<jobId>.attempts.jsonl` (one row per click the game took, a measurement log
appended with each checkpoint and never read back) and, for a build with
clicks, `<jobId>.clicks.jsonl` (one row per click made: stance, aim, result,
what `expect` read) under the game directory,
scoped to world and dimension. `nav.work_status` reads the
bounded checkpoint; collections over 128 entries appear as
`{omitted: true, count}`. `nav.resume {jobId}` accepts a new timeout and edit
permissions, never a different plan, and re-observes the world before acting.
`nav.build_pause` releases controls and leaves the job resumable (after its
closing, when it has something to put back); server time keeps running.
A job beginning deletes the journals beyond the newest twenty that ended and
the newest twenty paused of each kind, with their spec and ledger, so an
older job can no longer be resumed.
A checkpoint (every 20 ticks of a build, and at each change of phase) is
made into bytes on the game thread and written and synced by one writer
thread, in order; a newer one replaces one not yet written. A crash can lose
the last checkpoint handed over. The save that ends a job, any read of a
journal and the game's shutdown wait for the writer.

## Schematic import and copy

`nav.schematic_import {path, origin?, includeAir?}` reads a file inside the
game's `schematics/` directory:

- **MCEdit `.schematic`** (gzip NBT, `Blocks`/`Data`/`AddBlocks`). Numeric ids
  are resolved against the running game's block registry, so the file must
  come from a world with the same id map; unresolved ids are counted in
  `skipped.unknown`. Tile entities are counted in `tileEntities`, not imported.
- **Canonical JSON plan** (`.json`): `{origin?, cells: [...]}` in the
  `nav.build` cell shape.

Sponge `.schem` and Litematica files are not read. `nav.copy {bounds, origin?,
includeAir?}` reads loaded blocks in inclusive bounds and also reports
`skipped.unloaded`. Both return `{plan: {cells, origin, size}, size, count,
skipped, tileEntities}` and reject more than 1,048,576 cells. Rotation and
mirroring are not offered, because metadata would not be remapped.
