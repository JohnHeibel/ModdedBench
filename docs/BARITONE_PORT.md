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
| package `baritone.gtnh.pathing` | Minecraft-free code written for this project and unit tested without a game: work and construction spec validation (`WorkSpec`, `ConstructionSettings`, `ConstructionMask`), `DeferredClearance`, the corridor constraint (`Corridor`), `GoalRange`, and terrain observation helpers (`TerrainGrid`, `CollisionBox`, `LadderFacing`, `FluidPolicy`). There is no second path search; all routing is upstream's. |

The GUI input transformer and widget inspection in `mods/core` and
`mods/client` are this project's own code, not Baritone's; the client has no
dependency on the Baritone jar.

## What runs the upstream engine

| Surface | Upstream code in use |
| --- | --- |
| `nav.goto`, route legs, travel inside work jobs | `CustomGoalProcess`, `PathingControlManager`, `PathingBehavior`, `AStarPathFinder`, `Path`, `PathExecutor`, and the real movement classes (traverse, ascend, descend, fall, downward, pillar, diagonal, parkour) with their costs, lookahead, splicing, revalidation and timeouts. |
| `nav.mine` | `MineProcess`: multi-target goals, upward mining, pruning, blacklisting, drop collection. `WorldScanner` and the persistent location cache back discovery. |
| `nav.build` with `mode: "builder"` | `BuilderProcess` (`onTick`, `recalc`, `assemble`) with `InventoryBehavior`, `InventoryPauserProcess`, `BlockBreakHelper`, `BlockPlaceHelper`, `InputOverrideHandler`. |
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
  Strider, elytra, moving world border.

## Not ported or unsupported

| Item | State |
| --- | --- |
| Upstream chat command framework, `GuiClick`, multiple bots, `baritone.api` binary compatibility | Not implemented. MCP tools cover the operations. |
| Flight (modded or otherwise) | Out of scope. |
| Water-bucket fall recovery | Disabled: the clutch is a provider boundary with no verified fluid-container provider. |
| Swimming | Verified still vanilla water only. Modded fluids are excluded regardless of temperature. Flowing-water targets fail path calculation. |
| Mining a block with liquid directly above | Rejected by the inherited `MovementHelper` safety rule (seen with water-capped obsidian). |
| Snow of three or more layers | Treated as not walkable, as upstream does. |
| Ordinary `ItemDoor` placement | Unsupported by builder placement; place doors with `act.use_block`. |
| Silent (packet-only) look | Not migrated. Desktop notifications go to the log and chat. |
| Explore | Frontier selection and cache persistence exercised on natural terrain; the round trip failed to a mob. No survival handling inside the process. |

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
  edits, which may fall outside the scan bounds. Region protection always
  applies; `overrideProtection` is per job.
- The job does not return the player to where it started. Mining soil-like
  targets can leave the player at the bottom of a 1x1 shaft it dug under its
  own feet (seen live 2026-09-20); leaving needs `nav.goto` with `allowBreak`
  or `allowPlace`, which pillars out with whatever blocks are held.
- `nav.mine_block` (one block) reports `actualAim` and stops after 20 ticks of
  native target mismatch instead of breaking whatever is in the way.

## Construction contract (`nav.build`, `nav.build_preview`)

Exactly one of explicit `cells` or a `selection` (`fill`, `replace`, `walls`,
`shell`, `clear`, `sphere`, `hsphere`, `cylinder`, `hcylinder` with an `axis`).
A cell is `{pos, id, meta}` or `{pos, clear: true}`, with optional `item`
(the inventory selector used to place it: `id`, `meta`, `nbt`, `ore`),
`placement` hints and `verify: {pickedItem}`. Block state and placement item
are separate because a machine's item metadata is often not its block
metadata. `tileNbt` and `nbt` on a cell are rejected, never dropped silently.

| | `mode: "blueprint"` (default) | `mode: "builder"` |
| --- | --- | --- |
| Engine | Upstream `BuilderProcess`, strict profile: confined to plan cells, no settings | Upstream `BuilderProcess` |
| Cell limit | 16,384 | 1,048,576 (Python stages 4,096 per `nav.build_stage` call) |
| Edits outside the plan | None | With `allowBreak`/`allowPlace`; `settings.restricted: true` confines them to plan cells |
| Settings | Rejected | Validated and frozen into the job |
| Retry | At most two placement attempts per cell; an attempted cell that later differs pauses the job for inspection, it is never destroyed and retried | Eight attempts; `repairPlaced` (default true) allows correction |
| Completion | Fresh comparison of every cell's registry id and metadata, plus `verify.pickedItem` | Predicate based (see settings) |

Both modes place through native right-click handling and never write blocks.
Preview is a fresh loaded-world diff with conflicts, protection, unsupported
mappings and a shared-stack material allocation. A `replace` selection is
filtered once at job creation and journaled. Requested air that starts empty is deferred while temporary
supports are needed, then cleared in a final phase; status exposes
`buildPhase` and `deferredAirCells`.

Builder settings: `buildInLayers`, `layerHeight`, `startAtLayer`,
`layerOrder`, `skipFailedLayers`; `buildRepeat`, `buildRepeatCount` (default 1,
not upstream's unbounded -1), `buildRepeatSneaky` (default false);
`schematicOrientationX/Y/Z` (origin shifts, not rotations); `mapArtMode`;
completion predicates `buildIgnoreExisting`, `buildIgnoreBlocks`,
`buildSkipBlocks`, `okIfAir`, `okIfWater`; `buildSubstitutes`,
`buildValidSubstitutes`; `metadataMasks` (significant metadata bits per
block, replacing upstream's property ignores); `allowInventory`; `restricted`,
`repairPlaced`; `breakFromAbove`, `goalBreakFromAbove`; `distanceTrim`,
`incorrectSize`, `builderTickScanRadius`;
`breakCorrectBlockPenaltyMultiplier` (10); `acceptableThrowawayItems`
(explicit item selectors; there is no implicit throwaway list).

`PlacementStateAdapters` predicts the placed metadata for known callbacks,
including GregTech's machine item registry; anything else is verified after
the real placement. Tile inventories, tile NBT, machine configuration and
multiblock formation are outside construction: do them with interaction and
GUI tools and verify with `obs.tile`.

## Durable jobs

Each mining or building job writes `modbench/work/<jobId>.json` (checkpoint
and last receipt), `<jobId>.spec.json` (the frozen specification) and
`<jobId>.attempts.jsonl` (placement intents, fsynced before each click) under
the game directory, scoped to world and dimension. `nav.work_status` reads the
bounded checkpoint; collections over 128 entries appear as
`{omitted: true, count}`. `nav.resume {jobId}` accepts a new timeout and edit
permissions, never a different plan, and re-observes the world before acting.
`nav.build_pause` releases controls and leaves the job resumable; server time
keeps running.

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
