# Bounded work-process contracts

The GTNH mining and building processes are dev-only, native-client work jobs.
This document records their bounded contracts; live evidence and remaining
scope are in [VALIDATION.md](VALIDATION.md). Their provenance is in
[PORT_ORIGIN.md](baritone-core/PORT_ORIGIN.md).

## Mining

`baritone.mine` accepts 1--64 block selectors, 1--64 picked-item selectors,
a quantity and either a bounded scan or radius. A scan may contain at most
262,144 cells. A block selector's `id` and optional `meta` describe the actual
world block. Its optional `item` selector and `ore` test the block's native
pick-block stack. The requested output selectors instead match inventory
`id`, optional item `meta`, exact SNBT and Forge ore-dictionary name.

Success means `current matching inventory count - initial matching inventory
count >= quantity`, including an explicit resume. Breaking a matching block,
seeing a drop or reaching a target is not a receipt. The job rescans loaded
candidates, rejects protected or unsafe/unreachable targets, travels by the
normal navigation permission set, then loiters for matching drops. An inventory
full result is a failure, not success.

Mining currently tries bounded approach positions and follows matching drops
within a three-block margin of the scan. A just-mined block starts a bounded
anticipated-drop window; other available work does not incur a fixed per-block
loiter delay. Full upstream mining goal assembly is still a separate parity item.

`allowBreak` and `allowPlace` explicitly permit incidental excavation/scaffolding
along travel routes. Such route edits may be outside the target scan bounds;
leave both false when only selected target blocks should change. Live region
protection still applies and `overrideProtection` is per job.

## Building

`baritone.build` and `baritone.build_preview` require exactly one of explicit
`cells` or a `selection`. Both are capped at 16,384 translated cells. Selection
shapes are `fill`, `replace`, `walls`, `shell` and `clear`; `replace` requires a
block selector. `origin` translation must remain inside the world Y range and
world coordinate bounds.

Target block `id` and `meta` are the desired placed state. The material selector
is separate: it matches the inventory item `id`/`meta`/SNBT/ore used by native
placement. This distinction matters for blocks whose pick item or dropped meta
does not equal the placed block metadata. Preview is a fresh loaded-world diff:
it reports conflicts, protection, unsupported mappings and a shared-stack
material allocation, so overlapping selectors cannot claim the same stack twice.

A `replace` selection is filtered once at job creation and that selected cell
set is journaled. It does not drift when the live world changes. Each placement
uses normal client interaction and persists a per-cell attempt ledger. At most
two input attempts are allowed; an attempted placement that later differs is
inspected and fails before destructive retry. Completion is a fresh comparison
of every selected block registry ID and metadata.

Within the current clear/build layer, the executor first performs work reachable
from its current pose. It retains Baritone's placement-height rule: place at or
below the feet, allowing one level above only when another block caps that cell.
This makes it climb the growing structure while lower layers still provide
access. Native ray reach alone can finish high walls from below and strand the
roof. Inventory selection uses empty hotbar slots when available,
so alternating materials can stay ready instead of swapping through one slot.
Otherwise it builds a bounded composite of approach poses,
travels to whichever pose the retained search selects, and re-observes the cells
there. Building generates poses using navigation's collision-derived footing,
including fractional surfaces such as slabs, and the player's native reach
(search radius capped at eight blocks). Native visibility and placement checks
filter candidates before at most 40 poses per cell and 256 poses per composite
are admitted; larger cell sets are paged. Clearing retains mining's stricter
full-support requirement. Composite travel does not
permit an unrelated corridor constraint, excavation, or scaffolding.

Reachability uses the native selectable-block ray, including noncolliding
vegetation and modded blocks. An adjacent placement support must be nonreplaceable; otherwise native item
use can replace the support instead of the requested cell. This is checked
again before input. Placement candidates must also satisfy native
placement rules and avoid the player's body at the proposed pose; feasibility
is rechecked before input. Mining reports `actualAim` and `aimMismatchTicks`;
twenty consecutive native target mismatches stop that approach without breaking
the intervening block or entity.

Tile NBT, schematic NBT, multiblock formation/configuration, machine GUI
configuration and special placement behavior without a native adapter are
deliberately unsupported. `tileNbt` and `nbt` inputs are rejected rather than
silently discarded. Metadata equality is not a claim that arbitrary tile state
or a formed multiblock is correct.

## Durable jobs and resume

Each job writes an atomic journal at `mcDataDir/modbench/work/<jobId>.json` with
world/dimension scope, original spec, progress and latest receipt. It is bounded
to 8 MiB. `baritone.resume` accepts the existing job ID and execution options;
it does not replace the plan. Resume reloads the scoped journal and re-observes
the world before any native action. Mismatches, changed protection, missing
materials, unloaded cells and exhausted placement attempts remain visible as
job status rather than being inferred away.

## Provenance boundary

[MineProcess](../src/main/java/baritone/process/MineProcess.java) informs the
rescan/rejection/drop loop and multi-target scheduling. [BuilderProcess](../src/main/java/baritone/process/BuilderProcess.java)
informs desired-state recovery, exhausting reachable work before travel, and
assembling useful work goals for path-cost selection. The GTNH executor uses the
retained search core's `GoalComposite`; it does not port upstream's full builder
scheduler or its specialized placement and break goals. [MovementDescend](../src/main/java/baritone/pathing/movement/movements/MovementDescend.java)
informs safe descent clearing; [WallsSchematic](../src/api/java/baritone/api/schematic/WallsSchematic.java)
and [ShellSchematic](../src/api/java/baritone/api/schematic/ShellSchematic.java)
inform selection shape semantics. DJ2's [BuildPlacementGuard](../src/main/java/baritone/moddedbench/bridge/BuildPlacementGuard.java),
[BuildInspection](../src/main/java/baritone/moddedbench/bridge/BuildInspection.java),
[build tool](../tools/mcp/tools/build.py), [mining tool](../tools/mcp/tools/mining.py)
and [quest tool](../tools/mcp/tools/mods/betterquesting.py) inform bounded native
actions, inspection and receipts. GTNH's selectors, journal, Forge callbacks
and native placement/mining execution are newly adapted; they are not copied
DJ2 or upstream implementations.
