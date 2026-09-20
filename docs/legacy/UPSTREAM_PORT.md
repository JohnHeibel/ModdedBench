# Source port of the Baritone engine

> **Changed in phase 4a (2026-09-19).** The experimental graph and executor this
> port replaced are now deleted rather than retained: `BaritoneNavigation.Run`,
> the `baritone-core` A* search, goals, `WorkWorld` and `WorldView` are gone, and
> the `baritone-core` Gradle project is folded into `mods/baritone`
> (`baritone.gtnh.pathing` keeps `WorkSpec`, `ConstructionSettings`,
> `ConstructionMask`, `DeferredClearance`, `TerrainGrid` observation,
> `CollisionBox`, `LadderFacing`, `FluidPolicy`, `Corridor`, `GoalRange`).
> Both construction modes run through upstream `BuilderProcess`; the custom
> strict blueprint process no longer exists. The port's own code uses
> `baritone.compat.BlockPos`, and `baritone.compat.Registry` is its one
> registry-name helper. Six pinned imports that nothing referenced were removed
> from `src/upstream/java` and `UPSTREAM_SOURCES.json`, which now lists 156
> files: `GoalStrictDirection`, `LinkedListOpenSet`, `Overrideable`,
> `IElytraProcess`, `CylinderMask`, `SphereMask`. Test and import counts below
> are historical.

Current checkpoint: [native runtime/API acceptance](../NATIVE_RUNTIME_ACCEPTANCE.md).
The build passes 183 tests and verifies 162 pinned imports. Native event/API
acceptance passes 16 checks. Source Explore has observed and persisted a new
natural frontier; its survival return failed after a hostile attack. The
[single-job scaffold clearance](../FOLLOWUP_CLEARANCE.md) follow-up supersedes
the older cleanup failures below. Detailed historical results are not a claim
of complete modern Baritone binary, CLI or modded-fluid parity.

This replaces the experimental navigation graph/executor with source from
Baritone **v1.2.19**, commit `d9cb2d91a06501c5bcba2181509d0df80361f413`.
`UPSTREAM_SOURCES.json` identifies each original file and its SHA-256 hash.
The imported files retain their LGPL headers under `src/upstream/java` and
are compiled into the navigation jar; 162 pinned imports are verified and 172
Gradle tests pass. Eleven Python GTNH profile tests pass and 52 model tools load,
including source settings, process, follow and cache tools. The retained root `src` is still the DJ2 reference and is not a GTNH build input.

## Implemented boundary

Coordinate navigation, ordinary work-process travel, and saved route legs now
use `CustomGoalProcess`, `PathingControlManager`, `PathingBehavior`,
`AStarPathFinder`, `Path`, and `PathExecutor`. The graph and execution use the
actual traverse, ascend, descend, fall, downward, pillar, diagonal, and parkour
movement classes. Goal families, action costs, current/next paths, lookahead,
cost revalidation, movement timeouts, sprint continuity, and path splicing come
from these sources. A saved corridor restricts candidate destinations while
keeping the same movement graph.

`ReferenceNavigationJob` adapts action lifetime and status to the existing
transport. The shared input lease still controls preemption, GUI takeover, and
world changes. Input changes are staged until a movement finishes its update;
clearing that draft does not physically release keys between movement updates.
Protected-region snapshots participate in placement/breaking costs, and the
existing native input guard remains authoritative when actions execute.

`MineProcess` now supplies multi-target goal coalescing, upward mining, pruning,
failure blacklisting, movement-driven excavation, and drop collection. Its
Modbench wrapper retains bounds, durable job identity and net inventory gain.
Native ore/pick-stack/NBT selectors are evaluated incrementally on the client
thread (up to 2 ms/2,048 cells per tick); immutable position/state matches are
supplied to searches. `WorldScanner` and the persistent location cache are
imported and wired through the native cache adapter. Rescans prune captured
bounded observations on the client thread, so a cancelled discovery worker
cannot mutate a newer process; persistent and natural-world discovery still
need broader live acceptance.

Explicitly requested mining targets are exceptions to incidental `allowBreak`,
scoped to position, block and metadata. Region protection still applies. Bounded
mining disables exploration outside the observation scope. A stopped miner's
receipt allows in-flight drops/inventory updates to settle, and newly observed
drops still use upstream collection goals. It does not wait between blocks.

`mode: builder` uses upstream `BuilderProcess.onTick`, `recalc`, `assemble`, and
its calculation context; the custom strict blueprint process remains available
for explicit-cell contracts. The adapter freezes canonical cells, exact material
selectors and observed state on the client thread before source calculation uses
them. It records a durable placement intent before native click, bounds repeated
placement attempts, and re-observes on resume. Original `InventoryBehavior`,
`InventoryPauserProcess`, `BlockBreakHelper`, `BlockPlaceHelper`, and
`InputOverrideHandler` are connected. Main-inventory swaps use the native player
container and a transaction acknowledgement, retaining exact item ID, metadata
and NBT. These implementation boundaries still need complete live acceptance.

`SettingsUtil` backs a structured source-settings endpoint. It applies typed
edits atomically while the source engine is idle, and atomic persistence is
verified. Native packet/event, rendering, free-look and direct process API surfaces are
now connected; see the current checkpoint for their timing and compatibility boundaries.

## Deliberate version adaptations

- Coordinates/vectors and block-state metadata use `baritone.compat`; they do
  not introduce fake `net.minecraft` classes. Known vanilla metadata encodings
  apply to their native block classes. Registry identities are retained.
- In 1.7 the client player's `posY` is eye-relative. Movement comparisons and
  distances use `boundingBox.minY`; ray origins use native `getPosition(1)`.
  A source placement that will force sneak projects the next native `ySize`
  transition, preserving a residual sneak offset instead of subtracting a second
  fixed 0.08 eye-height offset. Public bottom-slab feet goals are translated to
  upstream's cell-above-slab convention. Native `isBlockNormalCube` is distinct
  from the opacity-based `isNormalCube`; using the latter incorrectly rejects
  glowstone as support.
- Loaded-chunk indexes are copied on the client thread. Vanilla's list and
  GTNH Hodgepodge's `FastUtilLongHashMap.valuesIterator` are supported. The latter
  deliberately nulls the vanilla list. Search does not request chunk loads.
- Native selection boxes stay on the client thread. Search uses the upstream
  semantic movement rules, with native metadata/passability access.
- Tool snapshots retain stack NBT and metadata, use Forge dig speed/harvest
  hooks, and apply native 1.7 fatigue. Unknown position-dependent hardness is
  currently impassable to excavation; no made-up hardness value is substituted.
  Native stack harvest fallback and the previous mod-tool eligibility checks
  are retained. Source-engine tool estimates are included in `baritone.tools`.
- State caches key by block identity and metadata, including unregistered
  states, rather than indexing a nonexistent 1.12 block-state ID array.
- Native 1.7 has no auto-jump, Frost Walker, Depth Strider, elytra, or moving
  world border. Those version-specific capabilities are not synthesized.
- Water-clutch capability is an explicit provider boundary, disabled until a
  native fluid-container provider verifies placement/recovery. No bucket ID or
  container-volume assumption is embedded in that boundary. Unverified modded
  fluids remain excluded from swimming, regardless of temperature.
- A cancelled queued search stays cancelled. A superseded search cannot install
  its result into a newer action. These are concurrency corrections around the
  upstream algorithm, required by the external control lease.
- Desktop notifications/toasts currently route to logs/chat. Visible aiming
  uses the upstream aim processor; silent packet/render hooks are not migrated.
- Anticipated-drop expiry counts active simulation ticks so a harness pause
  does not exhaust it. The default grace is 1,000 ms of simulation rather than
  upstream's 250 ms, to cover native item pickup and packet handoff.

## Earlier migration gap register (see current checkpoint)

This is **not a declaration of full Baritone parity**. In particular:

- Source cache/scanner and Follow, Explore, GetToBlock, Farm, and Backfill are
  compiled and wired. Cache exact-metadata, asynchronous save, and loaded-world
  precedence have bounded acceptance. Farm has bounded harvest/replant/wheat-gain
  evidence and Backfill has sealed-corridor excavation/refill evidence; Explore
  has only a blocked-arena outcome and cancellation, not terrain expansion.
  Persistent/natural-world discovery needs broader coverage.
- Strict explicit-cell blueprint scheduling remains custom; its travel uses the source navigation job.
  Ordinary builder mode has an 81-check bounded construction batch, but wider
  native callbacks/layouts and the complete adversarial batch still need live
  evidence. A two-phase procedure has source-built the beam, used source
  navigation to leave it, observed eight new cobblestone supports in
  original-air cells, then used a harness-selected approach at their mean
  coordinates before source-building the beam plus eight explicit-air cells.
  It removed all eight supports while preserving the beam and arena. Source
  Builder does not select that cleanup approach, so this is not autonomous
  single-call cleanup. The restricted single-plan beam still
  times out after 2,599 ticks with zero placements and three removals.
- The 26-check `reference_fluid_smoke.py` now synchronizes the fixture's
  server/client fluid state. Still-water swimming to a dry bank and submerged
  dry-bank arrival pass without damage. The flowing-water target routes around
  the flow then fails `path_calculation_failed` after 44 ticks; the
  ownership-cancellation case only records its scoped outcome. MineProcess rejects the water-capped obsidian
  before excavation because inherited upstream/DJ2 `MovementHelper` refuses a
  target with directly-above `BlockLiquid`; its three required mining/removal/
  cooling checks therefore fail. This is a source safety limit, not a missing
  MineProcess port.
- A same-client machine batch stalled after completing three of five placements. It was
  reproduced with source placement cancellation while the predicted placement ray
  and native crosshair differed. The 1.7 residual sneak-eye projection correction
  and its regression pass. The post-correction sequence passes machine (76),
  construction (81), then machine again (76); native five-block placements take
  75 and 72 ticks. These reruns support the correction; wider layout coverage
  remains open.
- Event/packet/render/API and silent-look coverage, strict-blueprint scheduling,
  deep snow, native-fluid clutch behavior, general modded collision/geometry,
  and selected DJ2 behavioral fixes remain tracked in the parity audit.
- The historical geometry suite requires walking onto half-block-deep snow
  without excavation. Both pinned upstream and DJ2 reject snow with three or
  more layers as walk-through; that richer collision behavior is still open.
  Loaded-goal normalization now retains the physical destination and updates it when a bottom slab becomes observable; this transition has offline coverage, with a live streaming/slab boundary case still open.

The presence of an imported API interface or setting is not a runtime
capability claim. Flight remains outside the agreed scope.

## Validation

`ReferencePathingTest` boots real Forge block/item registries in an isolated
LaunchClassLoader. It executes the imported search and movement graph over a
bounded block-access fixture, then checks the resulting movement classes and
paths. It also exercises real path assembly/splicing, metadata decoding, and
cancellation before the worker starts. It does not replace those algorithms
with formula copies. Live GTNH fixture evidence is recorded separately under
`gtnh/.runtime/evidence`.

Validated on 2026-09-13:

- Complete Gradle build: 172 tests passed with no failures, including source
  movement, path assembly, cancellation and native version-boundary coverage.
- A 416-block streaming route in both directions, including a 48-block descent
  and return climb: server arrival, cancellation, released inputs and no damage.
- Slabs, stairs, carpet, fractional starts, four ladder orientations up/down,
  and rerouting around a missing rung: 65 fresh live checks passed.
- Native work-process batch: GT drill harvesting, exact metadata selection,
  protected target, cancellation/resume, durable scope and outside-edit checks.
- Composite tree fixture: eight logs collected in 630 job ticks, with no damage,
  the isolated high log left in place, and no out-of-bounds edits.
- Original natural-world reproduction: job
  `df5c08e8-39f3-4601-89b0-c6212446e8b9` collected **eight logWood items in
  698 job ticks (about 35 seconds)** from an empty inventory, with health 20
  throughout. It used the original bounds and `allowBreak:false/allowPlace:false`
  for incidental work. The previous approach scheduler collected zero in
  1,352 ticks. Evidence: `natural-source-mining.json`.
- Source mining acceptance: seven checks passed with the source engine.
- Source inventory acceptance: 21 checks passed, including the exact tool-stack
  boundary and acknowledged main-inventory promotion.
- Source construction acceptance: 81 checks passed.
- Source process/cache acceptance: 25 checks passed for goals, get-to-block,
  native-item follow, exact cached metadata, asynchronous save, and a stale
  cached solid versus loaded-air path.
- Latest serial machine placement and full configuration: 76 checks passed, including exact
  NBT variants. The latest adversarial evidence validates a 42-cell shell plus
  three authorized air clears, lower/upper slabs, inverted stairs, fences/panes,
  a Malisis trapdoor and an exact GT tile. Ordinary `ItemDoor` remains unsupported
  by source builder placement; a Python native-interaction adapter can place and
  toggle one using native bounds and observed Malisis tile state. A two-phase
  source build/navigate/observe procedure then uses a harness-selected
  mean-support approach before source-building the beam plus eight explicit-air
  cells, removing all eight observed original-air supports while preserving the
  beam and arena. It does not establish autonomous single-call
  cleanup: the restricted single-plan beam still times out after 2,599 ticks
  with zero placements and three removals. A same-client machine batch stalled after completing three of five placements. It was
  reproduced with source placement cancellation while the predicted placement ray
  and native crosshair differed. The 1.7 residual sneak-eye projection correction
  and its regression pass. The post-correction sequence passes machine (76),
  construction (81), then machine again (76); native five-block placements take
  75 and 72 ticks. These reruns support the correction; wider layout coverage
  remains open.
- Source Farm harvests, replants and gains wheat before its upstream empty
  `GoalComposite` stop. Sealed-corridor Backfill excavates and refills with
  cobblestone. Explore has only blocked-arena/cancellation evidence, not terrain
  expansion. The 26-check fluid smoke now has reliable handoff plus passing still-water
  swimming/dry-bank and submerged dry-bank arrivals. Its flowing-water target
  fails `path_calculation_failed` after 44 ticks after routing around the flow;
  its three required source mine assertions fail before excavation: inherited upstream/DJ2
  `MovementHelper.avoidAdjacentBreaking` rejects the target's directly-above
  flowing water. Flowing/source-fluid behavior and DJ2 recovery remain open.

The natural player retains the eight collected logs and the game is paused.
These checks establish this milestone, not full construction, cache, inventory,
fluid, Java API or command parity. The broader historical geometry suite still
stops at the deep-snow case described above; it is not reported as passing.
