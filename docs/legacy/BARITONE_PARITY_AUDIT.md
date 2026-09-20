# Baritone implementation audit

Current checkpoint: [native runtime/API acceptance](NATIVE_RUNTIME_ACCEPTANCE.md).
The build passes 183 tests and verifies 162 pinned imports. Native event/API
acceptance passes 16 checks. Source Explore has observed and persisted a new
natural frontier; its survival return failed after a hostile attack. The
[single-job scaffold clearance](FOLLOWUP_CLEARANCE.md) follow-up supersedes
the older cleanup failures below. Detailed historical results are not a claim
of complete modern Baritone binary, CLI or modded-fluid parity.

Initial audit: 2026-09-13, before migration. The original findings below describe
the experimental replacement at `471a6782`, not the subsequent source port.
Passing fixtures establishes their scenarios, not full subsystem parity or
readiness for a long survival run.

## Earlier source migration checkpoint (superseded above)

The runtime now compiles 162 verified pinned upstream source files; 183 Gradle
tests pass. Eleven Python GTNH profile tests pass and 52 model tools load,
including source settings, process, follow and cache tools. Coordinate
navigation, ordinary work travel and saved-route legs execute the original
movement graph, `PathExecutor` and `PathingBehavior`. Mining uses upstream
`MineProcess`, including composite goals and vertical coalescing. `mode: builder`
now selects the adapted upstream `BuilderProcess`; strict explicit-cell
blueprint scheduling remains custom while its travel uses the source navigation job. The original `InventoryBehavior`,
`InventoryPauserProcess`, `BlockBreakHelper`, `BlockPlaceHelper`,
`InputOverrideHandler` and `SettingsUtil` are imported behind native
acknowledged swaps and the structured settings endpoint; atomic persistence is
verified. Source Follow, Explore, GetToBlock, Farm, Backfill, cache and scanner
are compiled and wired. The latest serial batch passes construction (81),
machine configuration (76), and the two-phase beam procedure (12); inventory
(21), goals/follow/cache (25), and fresh geometry (65) remain bounded passing
evidence. Farm has bounded harvest/replant/wheat-gain evidence followed by its
upstream empty `GoalComposite` stop, and Backfill has sealed-corridor
excavation/refill evidence. Explore is limited to a blocked-arena outcome and
cancellation, not terrain expansion. The adversarial suite validates the
42-cell shell/three-air case, partial blocks, Malisis trapdoor and exact GT tile;
ordinary source `ItemDoor` remains unsupported. The two-phase beam procedure
observed eight original-air cobblestone supports after source exit navigation,
then used a harness-selected approach at their mean coordinates before the
source beam-plus-explicit-air build removed all eight while preserving the beam
and arena. Source Builder does not select that cleanup approach. The restricted single-plan beam still exposes
retained source cleanup ordering, timing out after 2,599 ticks with zero
placements and three removals; it is not an autonomous single-call cleanup
pass. A same-client machine batch stalled after completing three of five placements. It was
reproduced with source placement cancellation while the predicted placement ray
and native crosshair differed. The 1.7 residual sneak-eye projection correction
and its regression pass. The post-correction sequence passes machine (76),
construction (81), then machine again (76); native five-block placements take
75 and 72 ticks. These reruns support the correction; wider layout coverage
remains open. The 26-check `reference_fluid_smoke.py` now has
reliable handoff and passing still-water/swimming and submerged dry-bank
arrivals. Its flowing-water target fails `path_calculation_failed` after 44
ticks after routing around the flow; its three required MineProcess assertions
fail before excavation because the target
has directly-above flowing water, rejected by inherited upstream/DJ2
`MovementHelper.avoidAdjacentBreaking`. Flowing/source-fluid and DJ2 recovery
acceptance remain open.
See the reviewed
version adaptations, remaining limitations and validation in
[UPSTREAM_PORT.md](baritone/UPSTREAM_PORT.md).

| Audit IDs | Current implementation status |
| --- | --- |
| M01, M02, P01ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã…â€œP04 | Actual movement/search/execution/lifecycle sources connected; offline algorithm and live acceptance evidence are separate. Native interaction helper parity and wider geometry/fluid acceptance remain open. |
| M03ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã…â€œM05, P05 | Original semantic rules, cost/settings snapshot, favoring and avoidance compiled; native state/tool adapters still need broader modded coverage. |
| N01, N03 | Approach scheduler replaced by `MineProcess`; the bounds, net-gain, ownership and journal wrapper remain intentional differences. |
| N02, W01, W02 | Source cache/scanner are wired with exact metadata and asynchronous save evidence; persistent behavior and broader natural-world discovery remain open. |
| C01ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã…â€œC03 | Process control, visible aim processing, source `SettingsUtil`, atomic persistence, and a structured idle-only settings endpoint are connected. Full event/packet hooks, rendering, silent-look and API coverage remain open. |
| B01ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬ÃƒÂ¢Ã¢â€šÂ¬Ã…â€œB04, I02, O01 | The source builder is selected for ordinary builder mode, original inventory scheduling/native acknowledged swaps are connected, and Follow/Explore/GetToBlock/Farm/Backfill are wired. Farm and Backfill have the bounded evidence in this checkpoint; Explore terrain expansion remains open. The historical rows below remain the baseline: wider placement/state coverage and strict-blueprint scheduling remain open. |

The gap register below is retained as the historical baseline; this checkpoint
and the port record govern current status. No row is closed merely because an
interface or setting has been imported.

Current live acceptance is bounded: source mining passes seven checks, inventory
passes 21, construction passes 81, `reference_process_smoke.py` passes 25, and
the latest serial machine configuration run passes 76.
The process/cache case covers goals, get-to-block, native-item follow, exact
cached metadata, asynchronous save, and a stale cached solid versus loaded-air
path. Exact stack identity, client-thread snapshots, shared `NativeTargeting`,
fresh interaction diagnostics, bounded placement attempts, and pre-send
unsubmitted-swap replanning are implementation facts. S32 rejection handling
fails closed on an actual rejection; no negative live S32 fixture has exercised
that path. A same-client machine batch stalled after completing three of five placements. It was
reproduced with source placement cancellation while the predicted placement ray
and native crosshair differed. The 1.7 residual sneak-eye projection correction
and its regression pass. The post-correction sequence passes machine (76),
construction (81), then machine again (76); native five-block placements take
75 and 72 ticks. These reruns support the correction; wider layout coverage
remains open. The latest adversarial evidence
covers the 42-cell shell plus three authorized air clears, partial blocks,
Malisis trapdoor and exact GT tile. The unrestricted beam did not prove cleanup;
the restricted single-plan beam exposes retained source cleanup ordering rather
than a completed scaffold test. The two-phase source build/navigate/observe/
procedure uses a harness-selected mean-support approach before the source
beam-plus-eight-air build removes all eight observed supports while preserving
the beam and arena. `ItemDoor` remains unsupported by the source builder,
although the Python native-interaction adapter can place and toggle it. No historical gap row is closed by these results.

The original natural-world log request has now been reproduced successfully:
eight collected in 698 ticks, no damage, with the same bounds and incidental
edit options. This closes that specific zero-progress reproduction. It does
not close every mining/world-adapter gap in the historical register.

## Baselines and method

| Baseline | Pinned revision | Purpose |
| --- | --- | --- |
| Upstream Baritone, local `v1.2.19` tag | `d9cb2d91a06501c5bcba2181509d0df80361f413` | Original 1.12 implementation, inspected with `git show`; not a claim about the newest upstream release. |
| Preserved DJ2 checkpoint | `f7659dd77602f199e6acecae77edffb2a39703d7` | Identify changes we deliberately added for modded Minecraft and the harness. |
| GTNH implementation audited | `471a6782ec6123a5e2de4a130448ad85662371b1` | Current modules and MCP adapters; no navigation fixes were made during this audit. |

The retained root `src` tree is the DJ2 reference; it is **not compiled into the
GTNH navigation jar**. [GTNH build wiring](build.gradle) includes its own
`baritone` and `baritone-core` modules. The audit follows the behaviors/processes
registered by [Baritone](../src/main/java/baritone/Baritone.java), its movement,
goal, cache, selection/schematic, settings and command families, and compares
their decisions with the actual GTNH call paths. Source references below point
at the retained local files; upstream differences were checked against the tag.

This is a source and existing-evidence audit. It does not claim a new live test
of every gap, an exhaustive bug inventory, or a percentage-complete estimate.
`Retained` means an identified algorithm survives; `replacement` means code
implements a related function but does not preserve the original implementation;
`missing` means the GTNH modules do not provide that subsystem. An API with the
same operation name is not evidence of algorithm parity.

## Immediate failure

Job `6850c19c-40a6-406c-ba76-6228b221c372` requested eight `logWood` items in a
loaded natural-world area. At cancellation it recorded **1,352 client job ticks,
zero blocks mined and zero inventory gain**. It first walked among positions
near `[-163,85,345]`, then repeatedly searched for approaches around high acacia
logs, including `[-137,80,357]`. The terminal `gui_open` cancellation happened
after the fruitless navigation; it does not explain the preceding failure.

[MiningProcess](baritone/src/main/java/baritone/gtnh/MiningProcess.java) chooses
one target and exhausts up to 40 separately pathed approaches. Its
[approach generator](baritone/src/main/java/baritone/gtnh/WorkAccess.java) does
not filter candidates by target visibility and only considers Y from target-2
through target+1, plus the current feet as a special case. Thus reaching an
approach does not establish that mining is possible. High logs can have useful
lower ground positions outside the generated range. This is unlike upstream
`MineProcess.updateGoal/coalesce`, which forms composite mining goals and relies
on the movement/breaking machinery, including upward mining.

The request also retained our default `allowBreak:false` and `allowPlace:false`
for incidental route work. Those differ from upstream defaults and restrict
access, but enabling them does not repair the candidate loop or movement graph.
One further mining receipt reported a matching native ray followed by loss of
reach after three ticks. Its precise stance/eye-transition cause is **unresolved**;
do not report a crouching fix without reproducing it.

Raw observations and the job history were saved locally in
`gtnh/.runtime/evidence/quest-run-mining-failure.json`. The game remains paused.

## Gap register

IDs remain stable for migration tracking. Every row is open unless explicitly
marked retained, extra, or version-specific. P0 blocks trustworthy basic work;
P1 is required for the requested ordinary Baritone/long-horizon behavior; P2 is
integration or diagnostic completeness. Priority is not permission to silently
drop a feature from the agreed scope.

### Movement, costs and world representation

Reference: [Moves](../src/main/java/baritone/pathing/movement/Moves.java),
[movement classes](../src/main/java/baritone/pathing/movement/movements),
[MovementHelper](../src/main/java/baritone/pathing/movement/MovementHelper.java),
[CalculationContext](../src/main/java/baritone/pathing/movement/CalculationContext.java).
Current: [TerrainGrid](baritone-core/src/main/java/baritone/gtnh/pathing/TerrainGrid.java),
[ForgeSnapshot](baritone/src/main/java/baritone/gtnh/ForgeSnapshot.java),
[WorkWorld](baritone-core/src/main/java/baritone/gtnh/pathing/WorkWorld.java).

| ID | Priority/status | Concrete gap and consequence |
| --- | --- | --- |
| M01 | P0 / replacement | `MovementTraverse`, `MovementAscend`, `MovementDescend`, `MovementFall`, `MovementDownward`, `MovementPillar`, `MovementDiagonal` and `MovementParkour` have not been transplanted. Some names have analogous graph edges, but their feasibility, costs, preparation and execution are different. |
| M02 | P0 / missing repertoire | No actual diagonal movement family, sprint/parkour family or general direct-downward digging. Ordinary ground descent is capped at three blocks. Construction-only support ascent/pillaring does not supply general upstream pillar/fall behavior. These omissions change which routes exist, not just their speed. |
| M03 | P0 / replacement terrain policy | Collision sampling is useful, but it replaces semantic movement rules. Soul sand is categorically a hazard rather than slower walkable ground; there is no door/gate opening movement. Ladder direction depends on copied collision boxes, leaving unsupported attachments/vines outside the tested case. Tall/overhanging collision shapes are obstacles rather than candidate footing. |
| M04 | P0 / incomplete excavation graph | Break costs are captured only for cells classified as full `SUPPORT`. Partial/collidable obstacles can lack a break edge. Non-colliding plants are `CLEAR` to navigation but can obstruct a mining ray. `automaticBlock` excludes tiles, falling blocks, and blocks beneath falling blocks. Non-construction work only clears flat/one-down destination columns; it cannot reproduce general upward or downward excavation. |
| M05 | P1 / replacement costs | No full `CalculationContext` settings snapshot. Work costs use new constants (`ticks+12`, placement 30, custom ascent/descent penalties); movement costs omit the original complete sprint, inventory, avoidance, potion/equipment and player-speed policies. DJ2's movement-attribute speed correction is absent. A geometrically possible route may therefore be ranked differently. |
| M06 | P0 / partial fluid behavior | Native Forge fluid observations exist, but navigation only admits verified vanilla water. Surface swimming/upward recovery and new flow costs do not equal the original movement-level swim/fall behavior or the DJ2 fixes. There is no bucket-assisted fall recovery or complete underwater traversal. Fluid-adjacent route excavation is rejected wholesale. Handling a held fluid container through an interaction primitive does not close this gap. |

M06 must preserve actual Forge fluid identity, collision and effects. Upstream's
vanilla bucket assumptions and DJ2 temperature heuristics must not become a
claim that every cool modded fluid is safe. A generic, explicit capability/policy
adapter is required; lava, flowing-water obsidian work and multiple fluid types
remain mandatory live acceptance scenarios.

### Search, execution and recovery

Reference: [AStarPathFinder](../src/main/java/baritone/pathing/calc/AStarPathFinder.java),
[Path](../src/main/java/baritone/pathing/calc/Path.java),
[PathExecutor](../src/main/java/baritone/pathing/path/PathExecutor.java),
[PathingBehavior](../src/main/java/baritone/behavior/PathingBehavior.java),
[Favoring](../src/main/java/baritone/utils/pathing/Favoring.java).
Current: [search core](baritone-core/src/main/java/baritone/gtnh/pathing/AStarPathFinder.java),
[BaritoneNavigation.Run](baritone/src/main/java/baritone/gtnh/BaritoneNavigation.java).

| ID | Priority/status | Concrete gap and consequence |
| --- | --- | --- |
| P01 | Partial retention | Heap/node relaxation, best-so-far partial-path coefficients, several goal formulas and action constants are retained. The complete search pipeline is not: `Moves`, calculation context, favoring, chunk-border policy, path reconstruction into movement objects, post-processing and loaded-chunk cutoffs are replaced or missing. Even A* parity cannot be inferred from similar core equations. |
| P02 | P0 / replacement executor | `Run` follows position/string-kind lists with center thresholds, velocity checks and sneak braking. Upstream uses movement state machines and next-movement-aware sprint/transition decisions. Our rolling-flat optimization does not replace that execution logic. It affects movement, mining access, placement and schematics together. |
| P03 | P1 / missing lookahead | One search/result at a time. At a partial endpoint the client stabilizes, captures and searches again. No current/next executor pair, early segment splice, path history cutoff or upstream backtracking/splice logic. The 416-block fixture demonstrated continuation, not these features. |
| P04 | P0 / replacement recovery | Immediate corridor checks and fixed stall/replan limits replace future-movement cost verification, movement-specific timeouts, off-path reconciliation and `safeToCancel` decisions. A successful endpoint receipt is weaker than successful useful work. Tests must cover terrain changes and interrupted mid-movement actions. |
| P05 | P1 / missing favoring | No previous-path cost favoring or upstream configurable mob/spawner avoidance field in A*. Saved route corridors are a different feature. Entity observations and combat availability do not make routes account for hostile entities. |

### Goals, mining, discovery and other processes

Reference: [goal family](../src/api/java/baritone/api/pathing/goals),
[MineProcess](../src/main/java/baritone/process/MineProcess.java),
[process family](../src/main/java/baritone/process),
[cache family](../src/main/java/baritone/cache),
[BlockStateInterface](../src/main/java/baritone/utils/BlockStateInterface.java).
Current: [Navigation API](api/src/main/java/dev/modbench/api/Navigation.java),
[MiningProcess](baritone/src/main/java/baritone/gtnh/MiningProcess.java),
[WorkAccess](baritone/src/main/java/baritone/gtnh/WorkAccess.java).

| ID | Priority/status | Concrete gap and consequence |
| --- | --- | --- |
| G01 | P0 / partial goals | `GoalBlock`, `GoalXZ` and `GoalComposite` formulas exist, but ordinary public navigation is exact XYZ. No general exposed near/get-to-block/two-block/Y-level/inverted/run-away/direction/axis goal system. An internal `GoalXZ` class is not an operational XZ goal tool. Mining and construction need the actual goal semantics before their processes can be ported faithfully. |
| N01 | P0 / replacement miner | Sequential nearest-target/approach exhaustion replaces multi-target goals, vertical goal coalescing, upward mining, and goal/path revalidation. This is the observed tree failure. Implement upstream `MineProcess` over the ported movement stack, not another approach-loop patch. |
| N02 | P1 / partial discovery | Each mining scan traverses a bounded volume, caps candidates, and sorts by geometric distance. No upstream cached-location search plus loaded-chunk scanning, periodic asynchronous discovery, legit-mine behavior or exploration fallback. The 262,144-cell cap can reject a radius accepted by the public parameter validator. |
| N03 | Intentional wrapper difference | Quantity completion is **net inventory gain**, rather than upstream total matching count. Explicit bounds, durable IDs and restoration-aware resume are Modbench contracts. Preserve and document them around `MineProcess`; they do not justify replacing its navigation logic. Drop pickup exists but uses separate coordinate jobs and custom loiter/rejection rules. |
| W01 | P1 / missing cache | No `CachedChunk`, `CachedRegion`, `CachedWorld`, `WorldProvider`, `ChunkPacker` or block-location index. The larger routine snapshot is 49x49 horizontally and 20 blocks vertically, captured anew. Known corridors, notebooks and work journals do not remember traversable terrain or ore locations when chunks unload. |
| W02 | P1 / partial world lifecycle | Loaded-chunk safety, server/world/dimension identity and persistent route/waypoint records exist. Upstream cache packing, persistence, repack/invalidation and automatic waypoint behavior do not. DJ2's configurable modded-block tracking also has no counterpart. |
| O01 | P1 / missing processes | No upstream `GetToBlockProcess`, `ExploreProcess`/filters, `FollowProcess`, `FarmProcess`, or `BackfillProcess`. A model can compose some behavior from primitives, but that is not implemented Baritone parity. Ordinary crop support must be distinguished from arbitrary GTNH crop genetics. |

### Construction, inventory and native actions

Reference: [BuilderProcess](../src/main/java/baritone/process/BuilderProcess.java),
[InventoryBehavior](../src/main/java/baritone/behavior/InventoryBehavior.java),
[ToolSet](../src/main/java/baritone/utils/ToolSet.java),
[schematic API](../src/api/java/baritone/api/schematic).
Current: [ConstructionProcess](baritone/src/main/java/baritone/gtnh/ConstructionProcess.java),
[BuildingProcess](baritone/src/main/java/baritone/gtnh/BuildingProcess.java),
[ConstructionGoal](baritone-core/src/main/java/baritone/gtnh/pathing/ConstructionGoal.java),
[MiningTools](baritone/src/main/java/baritone/gtnh/MiningTools.java),
[InventorySelection](baritone/src/main/java/baritone/gtnh/InventorySelection.java),
[ExactPlacementJob](baritone/src/main/java/baritone/gtnh/ExactPlacementJob.java).

| ID | Priority/status | Concrete gap and consequence |
| --- | --- | --- |
| B01 | P0 / replacement builder | `ConstructionProcess` and strict `BuildingProcess` reproduce selected rules, not `BuilderProcess.onTick/recalc/assemble`. Exact bounded standing-position sets replace `GoalPlace`, `GoalAdjacent`, `GoalBreak` and their original composition. `ConstructionGoal` retains placement/clearing heuristic intent but changes the goal membership. Layer/access decisions and recovery can differ despite matching final fixture output. |
| B02 | P0 / partial work planning | Custom paging, three route-failure cutoff, eight placement-attempt limit and construction-only work edges replace upstream scheduling. Source-liquid versus flowing-liquid handling in `assemble` is not retained as that algorithm. Existing placement-height, local-work-first, correct-block penalties and layer/repeat rules are valuable but are only parts of the builder. |
| B03 | P1 / partial placement-state adaptation | Native face/ray/placement validation and exact ID/meta/picked-item checks work in tested cases. `PlacementStateAdapters` predicts a small family of callbacks and otherwise returns initial metadata. It is not full state-for-placement/orientation compatibility. Unknown callbacks and multi-block effects need explicit handling; do not generalize machine/pipe success to all blocks. |
| B04 | P1 / partial schematic compatibility | MCEdit, Sponge and Litematica import, masks, selections, copy/paste and several transforms exist through Python/canonical cells. They do not supply the original dynamic `ISchematic` ecosystem, direct open-Schematica/Litematica integrations, full Java selection API or automatic modern-property-to-1.7 mapping. Masks have source-formula tests; that does not test the native builder scheduler. |
| I01 | P0 / restrictive tool eligibility | `MiningTools` specially accepts only three Tinkers classes and GT LV/MV/HV drill stats, while rejecting unverified overridden break callbacks. Other GT generated tools are excluded even if ordinary harvesting could work. Upstream's tool policy, silk-touch preferences, cost tie-breaking and configurable item-saving rules are not retained. Native Forge speed/harvest/NBT observations should remain the version adapter. |
| I02 | P1 / replacement inventory behavior | Main-inventory selection opens a screen, uses three pickup clicks, waits ten ticks for stack checks and closes it. There is no original inventory scheduling, reserved/disallowed hotbar selection, queued move policy or `InventoryPauserProcess`. This path is also separate from the richer transaction-aware MCP GUI driver. Preserve exact stack identity while integrating inventory coordination and observable acknowledgements. |
| I03 | P0 / inconsistent materials policy | General navigation's `PlacementItems` only accepts four hard-coded vanilla IDs with meta 0 and no NBT. Builder mode has explicit general throwaway selectors instead. Thus the same valid support stock can work in one operation and be unusable in another. Port shared inventory/throwaway policy with registry/meta/NBT-aware adapters. |
| A01 | P0 / extra hard constraints | `MiningJob` demands stable dry support/escape, avoids removing its footing, rejects falling blocks above, stops on damage and has separate arming/settling stages. Bulk/navigation jobs also hard-code damage/fire/air exits. These can veto upstream-legal movement even when global pause conditions are disabled. Keep genuine safety/protection requirements explicit; do not confuse those choices with 1.7 necessities or generic user interrupts. |

Arbitrary machine configuration, multiblock formation and writing a tile's full
NBT are not capabilities supplied by ordinary upstream schematic placement.
Their absence is not a newly discovered Baritone gap. The native interaction,
tile/Waila/NBT and reloadable procedure tools are the appropriate retained
foundation for model-written machine adapters.

### Controls, configuration, API and diagnostics

Reference: [PathingControlManager](../src/main/java/baritone/utils/PathingControlManager.java),
[LookBehavior](../src/main/java/baritone/behavior/LookBehavior.java),
[Settings](../src/api/java/baritone/api/Settings.java),
[default commands](../src/main/java/baritone/command/defaults/DefaultCommands.java).
Current: [InputArbiter](api/src/main/java/dev/modbench/api/InputArbiter.java),
[BaritoneMod](baritone/src/main/java/baritone/gtnh/BaritoneMod.java),
[BaritoneCommand](baritone/src/main/java/baritone/gtnh/BaritoneCommand.java),
[build settings](baritone-core/src/main/java/baritone/gtnh/pathing/ConstructionSettings.java).

| ID | Priority/status | Concrete gap and consequence |
| --- | --- | --- |
| C01 | P0 / different control layers | One replaceable input owner is implemented. It is not Baritone's process priority manager, temporary processes, `PathingCommand` semantics or safe movement pause/cancel. Keep the external lease arbiter and put Baritone's internal arbitration beneath it. Emergency input release and ordinary process suspension need distinct semantics. |
| C02 | P1 / replacement look/input pipeline | GTNH directly applies requested rotations/keys at its tick hooks. It has no complete aim processor, free-look/smoothing modes, upstream player-update event order or complete input override/player-context API. Native targeting refresh is useful but does not prove event-timing equivalence. |
| C03 | P1 / missing settings system | Only operation options and a frozen construction subset exist. No original runtime/persistent `Settings` system for movement, mining, costs, inventory, caching, rendering and search. Defaults differ, including route breaking/placement and inventory use. Hard-coded constants must not silently substitute for configurable upstream policy. |
| C04 | P2 / missing API and commands | GTNH registers a small `Navigation` service and `/baritone goto|stop|status`; it does not provide `baritone.api`, the process/event/provider interfaces or normal command family. MCP can provide equivalent user operations, but Java API compatibility and command compatibility need separate explicit status. |
| D01 | P1 / missing execution diagnostics | Structured receipts exist, but no original goal/path/selection renderer, search visualization, ETA/process inspection or full path/cancel event stream. Add visibility into rejected moves, targets, reach checks and chosen goals so future failures are explainable without reading jars. |
| D02 | P1 / missing DJ2 adaptation controls | DJ2's `BlockOverrides`, explain/audit/profile tools, modded cache tracking and configurable movement/geometry policies were not carried across as a coherent system. GTNH native geometry/fluid/NBT adapters cover parts of the need, but runtime extension/control and their regression cases must be mapped explicitly. |

## What to retain and what not to claim

- Retain the standalone bridge and control jar, MCP/NEI/quest/UI tools, authoritative
  observations, notes, protected regions, routes, interrupts, coordinated time
  control and build/restart/rollback infrastructure. They are Modbench additions,
  not reasons to replace Baritone's internal algorithms.
- Retain the mechanically adapted search/goal formulas and useful Forge adapters
  where comparison demonstrates equivalent behavior. Reuse alone does not close
  the surrounding gap IDs.
- Preserve strict blueprint bounds, item conservation checks, durable attempts,
  explicit unsupported schematic requirements and net-gain mining as documented
  wrapper contracts. Ordinary builder mode must not accidentally inherit every
  restriction of a strict blueprint operation.
- Vanilla Elytra, fireworks flight and vanilla offhand/frost-walker APIs are
  version-specific, not missing 1.7 behavior. Modded flight remains deliberately
  deferred to a future model adapter. Similar mechanics supplied by GTNH mods
  still require their own capability mapping, not a fake vanilla API.
- Upstream has vanilla assumptions too. Faithful algorithms do not require
  copying unsafe fluid guesses, item-specific capacity assumptions, hard-coded
  registry identities or incompatible 1.12 state APIs into GTNH.

## Migration order and acceptance

1. **Establish the source-faithful runtime foundation.** Bring over the actual
   goal, settings, player/world context, movement/cost and event/control types.
   Pin their source and keep a reviewed difference record per class. Introduce
   narrow 1.7/Forge adapters for state, collision, items/tools and native input.
   Keep Modbench's external lease/protection boundary. Do not extend the current
   center-following executor as the primary fix.
2. **Port movement execution and path lifecycle.** Actual movement classes,
   `Path`, `PathExecutor`, `PathingBehavior`, process control and inventory
   coordination come together. Restore preparation/break/place behavior,
   movement transitions, future checks, safe cancellation, lookahead and splicing.
   Add diagnostics during this work, not after another opaque live failure.
3. **Port mining and world discovery/cache.** Actual `MineProcess` and mining
   goals over the new stack; then cached terrain, scanner/index and get-to/explore
   behavior. Keep the net-gain/job wrapper. Reproduce this exact tree failure,
   low branches, foliage occlusion, buried resources and inaccessible candidates.
4. **Port the actual builder.** Preserve its scheduler, specialized goals,
   placement predictions, inventory integration and cost context. Reuse canonical
   schematic/selection input and independent verification without substituting
   a new scheduling algorithm. Re-run mixed-material house and pipe/machine work,
   then test obstructed interiors, scaffolding, excavation and material recovery.
5. **Close remaining process/configuration/API gaps.** Follow, farm, backfill,
   general goals, operational command equivalents and settings/cache management.
   Keep Java API and command compatibility distinct from behavioral acceptance.

For each batch, compare the actual pinned upstream decision logic with the port
using equivalent observations/settings: goal membership, legal movements,
costs, candidate rejection, action ordering and outcomes. Allow tied paths to
differ unless ordering itself is the behavior under test. An expected-value
test written from the new implementation is not independent parity evidence.
No differential native movement/process harness currently establishes this.

Then validate in GTNH with natural terrain and native server outcomes. Measure
completion, collected resources, permitted edits, deaths, replans, idle/moving/
breaking/placing ticks and inventory swaps; separate game-action efficiency from
CPU time, network waits and model thinking. Recheck cancellation, control takeover,
world changes and restart recovery. Batch scenarios to limit expensive live calls.

**The bed run resumes after basic resource gathering and navigation pass this
standard.** Existing smoke-test counts remain historical evidence for their exact
fixtures. They cannot close these implementation gaps on their own.
