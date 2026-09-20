# Native action efficiency

This note records client-job measurements for five stable building cases:
selection fill, metadata replacement, legacy schematic import, walls, and
top-down clearing. Each version completed the same 13 placements and 7 removals.
The machine-readable comparison, source hashes, per-case receipts, and counting
rules are in `.runtime/evidence/building-efficiency-comparison.json`.

| implementation | job ticks | observed child actions | travel actions | travel path edges |
| --- | ---: | ---: | ---: | ---: |
| before efficiency work | 1,307 | 55 | 13 | 29 |
| stage 1 | 456 | 38 | 9 | 19 |
| composite candidate | 389 | 25 | 5 | 13 |
| first-node fix (earlier build) | 384 | 25 | 5 | 13 |
| mixed-house acceptance | 461 | 31 | 11 | 19 |

Stage 1 reduced reported ticks by 65.1% from the original measurement. The
composite candidate reduced them by 70.2%. The earlier first-node fix reduced
them by 70.6%, while cutting eight travel jobs and 16 executed path edges. That
build passed the then-current 39 building checks and 97 geometry checks. These
are historical measurements; later house coverage changed construction ordering
and candidate geometry. The aggregates and source hashes remain in the comparison
JSON when the original smoke evidence is replaced by a later run.
The current house-accepted build uses **64.7% fewer job ticks** than the original
measurement for those same five cases. It makes more trips than the earlier
candidate because it climbs before completing higher walls, preserving roof
access. The separate 96-cell house completed in 1,478 job ticks and passed exact
server state, material consumption, doorway entry/exit and no-damage checks.
Its history reaches the 32-entry cap and is excluded from child/travel totals.
`recentWork` is capped at 32 entries; the aggregate above uses cases whose
histories did not reach that cap.

The original delay came mainly from action lifecycle boundaries. Each approach
created a navigation child, captured and planned a new world snapshot, stopped
at the arrival pose, returned control to the building state machine, selected an
inventory slot, aimed, acted, and waited for verification. Repeating that cycle
for nearby cells produced more delay than the short paths themselves. Stage 1
bounded nearby snapshots, removed avoidable state-transition frames, retained
safe motion through more waypoints, reused already selected hotbar items, and
shortened verification waits without removing the pre-effect attempt record.
The composite candidate then exhausts work reachable from the current pose and
offers several safe approach poses to one search, reducing repeated travel.

The first-node correction covers a different stop condition found with the natural
player standing inside a low collision shape. The executor had treated the first
path node like a later waypoint and tried to center on the integer start cell,
so it could wait indefinitely before beginning the first real movement. It now
executes the first edge directly, matching original
[PathExecutor](../src/main/java/baritone/pathing/path/PathExecutor.java)'s
movement progression. Besides the geometry and building suites above, one resumed natural
job (`8f1a...`) collected eight dirt in 101 job ticks. That single job is evidence
for this movement/mining path only. The wider pilot subsequently detected and
claimed its first quest and crafted a carpet, but ended on a health/fire interrupt
with its natural shelter unbuilt.

The mixed-material house exposed additional correctness gaps. Placement now
retains `BuilderProcess.searchForPlaceables` / `GoalAdjacent`'s height rule:
build at or below the feet, with one level above allowed when capped. Ray reach
alone had let the executor finish two-high walls from below and strand the roof.
Candidate footing now uses navigation's fractional collision surfaces, so a
nearly finished slab roof still supplies work positions. Empty hotbar slots keep
different materials ready, following upstream `InventoryBehavior`'s preference.

A raised doorway exposed an ascent mismatch too. `MovementAscend` requires
source jump clearance and the destination's two body cells; the port had required
the full unimpeded jump apex across the whole path. Both planning and execution
now permit native upward collision to cap the jump while requiring clear body
space through the landing height. This correction preserves the lintel.

These choices follow specific behavior in the retained sources. Original
[BuilderProcess](../src/main/java/baritone/process/BuilderProcess.java) searches
for reachable placements before assembling a composite of useful build goals;
original [MineProcess](../src/main/java/baritone/process/MineProcess.java) also
uses a multi-target goal. The retained
[GoalComposite](baritone-core/src/main/java/baritone/gtnh/pathing/GoalComposite.java)
keeps upstream's any-member completion and minimum-member heuristic. DJ2's
[BuildPlacementGuard](../src/main/java/baritone/moddedbench/bridge/BuildPlacementGuard.java)
provides the precedent for recording an attempt before native input, waiting for
world/inventory evidence, and refusing an unsafe destructive retry. DJ2's
[BuildMethods](../src/main/java/baritone/moddedbench/bridge/BuildMethods.java)
provides the surrounding bounded polling and independent final verification
pattern. GTNH's exact approach generation, paging, journal, Forge snapshots, and
1.7.10 input execution remain new implementations.

The table reports ticks counted inside native client jobs. It does not measure
wall-clock duration, path-search CPU time within a tick, bridge transport,
provider inference, runner interrupts, deployment, reconnects, or operator
latency. “Travel path edges” is the sum of `path.length - 1` for observed
successful travel receipts; it is a movement-edge count, not elapsed time or
Euclidean distance.

This is not full upstream parity. Building uses bounded collision-derived poses,
at most 40 reachable poses per cell and 256 poses per composite, with paging and a
bounded fresh replan. It does not port upstream `GoalAdjacent`, `GoalPlace`,
`GoalBreak`, builder path splicing, inventory behavior, or the complete movement
set. Mining still tries bounded approach positions one target at a time; it does
not yet use composite mining goals. Resource-constrained global planning,
falling-block work, tile or machine NBT placement, multiblock formation, and
automatic machine configuration remain outside the supported contract.
