# Baritone search-core port origin

> **Phase 4a (2026-09-19).** The standalone search core this file describes
> (`AStarPathFinder`, `PathNode`, open set, `Goal*`, `ActionCosts`, `Move`,
> `WorldView`, `SearchResult`, `WorkWorld`, `RouteProgress`) and the native
> executor and work processes built on it are deleted; navigation and
> construction run on the pinned upstream sources under `src/upstream/java`.
> What remains of this package is `WorkSpec`, `ConstructionSettings`,
> `ConstructionMask`, `DeferredClearance`, `TerrainGrid` observation,
> `CollisionBox`, `LadderFacing`, `FluidPolicy`, `Corridor` and `GoalRange`.
> The text below is kept as provenance for those files and for the behavioural
> rules carried into the adapters.

This describes the implementation before the source-faithful migration. The
[2026-09-13 implementation audit](../BARITONE_PARITY_AUDIT.md) is the active gap
register and acceptance plan. The complete movement/process/builder port is now
in scope; earlier scope exclusions below are historical implementation limits.

This directory ports the game-independent portion of upstream Baritone's A* search to a small Java 17 API.

Algorithms retained mechanically from upstream:

* `src/main/java/baritone/pathing/calc/PathNode.java`
* `src/main/java/baritone/pathing/calc/openset/IOpenSet.java`
* `src/main/java/baritone/pathing/calc/openset/BinaryHeapOpenSet.java`
* the search loop, relaxation test, partial-path coefficients, and timeout policy in
  `src/main/java/baritone/pathing/calc/AStarPathFinder.java`
* `GoalBlock` and `GoalXZ` distance formulas in
  `src/api/java/baritone/api/pathing/goals/GoalBlock.java` and `GoalXZ.java`
* `GoalComposite` any-member satisfaction and minimum-member heuristic from
  `src/api/java/baritone/api/pathing/goals/GoalComposite.java`
* the vertical estimate from `GoalYLevel.java` and constants/formulas from
  `src/api/java/baritone/api/pathing/movement/ActionCosts.java`

The copied algorithm is covered by Baritone's LGPL-3.0-or-later license; source files preserve that header.

Adapter seams introduced here:

* `BlockPos` replaces Minecraft's `BlockPos` / `BetterBlockPos`.
* `WorldView` supplies an immutable, thread-safe snapshot and legal outgoing moves.
* `Move` replaces `Moves`, `CalculationContext`, movement classes, world-border checks, and favoring.
* `SearchResult` replaces `IPath`, path reconstruction, execution movement assembly, loaded-chunk cutoffs,
  and Baritone's settings/result APIs.

`GoalXZ` uses the upstream `Settings#costHeuristic` default (`3.563`) because this standalone API has no
settings object yet. It is deliberately documented in `Heuristics`, and is separate from `ActionCosts` just
as it is upstream; the Forge integration should make it a snapshot setting before exposing user configuration.

The search core has no Minecraft block-state reads or execution controls. The separate Forge adapter
currently implements a conservative walk/ascent/bounded-descent executor, collision-derived fractional
footing, attached ladders, surface swimming with flow costs and submerged upward recovery. Graph nodes
retain integer feet-cell identities while copied collision boxes provide exact surface heights and
swept step/headroom checks. Ladder attachment comes from Forge's `isLadder` hook and collision geometry.
These geometry and ladder adapters are new implementations. `FluidPolicy` and the Forge fluid/execution
adapters are new implementations using the 1.7.10 Forge API, not copies of the DJ2 temperature-based
fluid inference. The bounded single-block mining job is also a new client-input adapter. The upstream
movement classes, inventory/tool costs, parkour, full falling/swimming behavior, path post-processing,
dynamic world-border logic and path favoring are not yet ported. The adapter snapshots the world before
constructing `WorldView` and rejects steps into unloaded cells.

Long journeys continue across bounded snapshots, with separate continuation and
recovery counters and repeated-endpoint detection (`RouteProgress`). Straight,
level execution segments may retain forward motion between waypoints. Client
chunk checks reject `EmptyChunk`; vanilla 1.7.10's client `chunkExists` method is
unconditional and cannot establish that terrain has arrived over the network.

## Bounded work-process provenance

The GTNH mining and building processes are new native 1.7.10 callbacks. They do
not copy upstream process code or synthesize block changes. They use normal
client break/place input, live Forge harvest and inventory observations, and a
durable work journal. The active pilot covers bounded target-set mining and
explicit cells or fill/replace/walls/shell selections. Accepted cases and limits
are recorded in [VALIDATION.md](../VALIDATION.md).

The following local sources informed retained *behavioral* rules, rather than
being transplanted implementations:

* [MineProcess](../../src/main/java/baritone/process/MineProcess.java): rescan,
  candidate rejection/blacklisting, safe approach selection and drop-loitering.
  GTNH's selectors, net-inventory-gain completion rule, tool checks and native
  break callback are new.
* [BuilderProcess](../../src/main/java/baritone/process/BuilderProcess.java):
  desired-state comparison, preflight and recovery intent, exhausting work
  reachable at the current pose, and using several useful work goals so path
  cost selects the destination. GTNH reuses the retained `GoalComposite` rule
  over newly generated exact approach poses. It does not port upstream's
  `GoalAdjacent`, `GoalPlace`, `GoalBreak`, `JankyGoalComposite`, or builder
  scheduler. GTNH's bounded cell expansion and paging, metadata-only
  verification, selected-cell freeze, inventory allocation and normal placement
  callback are new.
  Its `searchForPlaceables` / `GoalAdjacent` height rule is retained: placement
  is at or below the feet, except one level above when capped by another block.
  Both immediate work and candidate poses apply this rule so the builder climbs
  the structure before its lower access disappears.
* [RayTraceUtils](../../src/api/java/baritone/api/utils/RayTraceUtils.java):
  target rays include selectable blocks without collision boxes. The GTNH ray
  flags match both this source and native 1.7.10 `EntityLivingBase.rayTrace`.
  Mining refreshes native targeting after aiming, reports the actual hit and
  stops after a bounded mismatch instead of attacking an intervening object.
  Builder placement also retains the native feasibility check from
  `BuilderProcess`: 1.7.10 `World.canPlaceEntityOnSide` supplies mod rules and
  entity collision, with the player's body translated when testing future poses.
* [InventoryBehavior](../../src/main/java/baritone/behavior/InventoryBehavior.java):
  prefer an empty hotbar slot for a newly selected material, retaining other
  materials for quick reuse. GTNH uses deterministic free-slot selection and
  its existing guarded native container clicks rather than upstream settings
  or its reserved-slot policy.
* [MovementAscend](../../src/main/java/baritone/pathing/movement/movements/MovementAscend.java):
  source jump clearance and the destination's two body cells. GTNH checks the
  swept collision volume through the landing height and permits native upward
  collision to cap the jump. Requiring an unobstructed full jump apex throughout
  the move had rejected a normal raised two-high doorway by five centimetres.
  The exact fractional geometry checks and native executor remain GTNH adapters.
* [MovementDescend](../../src/main/java/baritone/pathing/movement/movements/MovementDescend.java):
  the need to clear descent cells and preserve an escape path. GTNH's bounded
  descent work and dry-escape checks are new native callbacks.
* [WallsSchematic](../../src/api/java/baritone/api/schematic/WallsSchematic.java)
  and [ShellSchematic](../../src/api/java/baritone/api/schematic/ShellSchematic.java):
  selection-shape meaning. The 1.7.10 bounded expansion is new.
* DJ2 [BuildPlacementGuard](../../src/main/java/baritone/moddedbench/bridge/BuildPlacementGuard.java),
  [BuildInspection](../../src/main/java/baritone/moddedbench/bridge/BuildInspection.java),
  [build tool](../../tools/mcp/tools/build.py), [mining tool](../../tools/mcp/tools/mining.py),
  and [Better Questing tool](../../tools/mcp/tools/mods/betterquesting.py):
  preflight, receipt, exact identity, bounded-action and native-network safety
  principles. Their 1.12/DJ2 APIs are not reused as GTNH callbacks.

Building pose generation reuses the GTNH navigation adapter's collision-derived
fractional footing, including slabs, with native reach and body-clearance checks.
This is a shared GTNH geometry adapter, not upstream's specialized placement
goal implementation. The search radius is capped at eight blocks; native
visibility and placement checks filter poses before up to 40 per cell enter a
composite. One work-goal search accepts at most 256 approach poses, and larger
cell sets are paged after bounded fresh replanning. Clearing keeps the native
mining adapter's stricter full-support rule. Multi-goal searches reject corridor constraints because a corridor
toward one member would not represent the composite. These are GTNH execution
limits, not behavior inherited from upstream Baritone or DJ2.

`WorkWorld` remains a new optional graph adapter: it adds estimated tool time
and placement cost to ordinary walking. Falling blocks, tile entities and
fluid-adjacent excavation remain excluded from this adapter. Resource-constrained
global search and the full upstream builder remain out of scope.

Persistent `WorldMemory` and `CorridorWorld` are new GTNH/Modbench implementations.
The retained `WaypointCollection` informed the world-scoped waypoint workflow;
its binary storage and Minecraft dependencies were not copied. Named routes store
ordered anchors/corridors and run the existing search/execution pipeline per leg.
Protection contributes an immutable edit predicate to `WorkWorld`, with live
checks in block jobs and native input handling. Shared leases distinguish
incidental automatic work from targeted work and carry per-operation overrides.
These additions do not supply upstream persistent chunk caching or path splicing.
