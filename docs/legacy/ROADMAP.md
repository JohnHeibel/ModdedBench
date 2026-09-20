# GTNH development plan and Baritone parity

Current checkpoint: [native runtime/API acceptance](NATIVE_RUNTIME_ACCEPTANCE.md).
The build passes 183 tests and verifies 162 pinned imports. Native event/API
acceptance passes 16 checks. Source Explore has observed and persisted a new
natural frontier; its survival return failed after a hostile attack. The
[single-job scaffold clearance](FOLLOWUP_CLEARANCE.md) follow-up supersedes
the older cleanup failures below. Detailed historical results are not a claim
of complete modern Baritone binary, CLI or modded-fluid parity.

Updated 2026-09-14. This is the active GTNH plan. The older workspace-level
BARITONE_PLAN.md and BRIDGE_PLAN.md describe the DJ2 design.

## Source migration plan and checkpoint history

The 2026-09-13 natural-world log-mining attempt collected nothing in 1,352
client job ticks. The follow-up [implementation audit](BARITONE_PARITY_AUDIT.md)
compares pinned upstream v1.2.19, the preserved DJ2 checkpoint and current GTNH
source. It supersedes any implication below that fixture coverage establishes
full navigation, mining or construction parity. The game is paused; the bed run
is suspended while basic navigation/resource gathering is corrected.

The agreed approach is now to port the actual upstream movement, path execution,
process and settings implementations, preserving their control flow through
narrow 1.7/Forge adapters. Do not keep extending the custom approach loops and
waypoint executor as the primary solution. Track algorithm retention, explicit
version/Modbench adaptations and live acceptance separately using the audit IDs.

1. Port goal/settings/context/control foundations and movement/cost types.
2. Port actual movement execution, Path/PathExecutor/PathingBehavior, inventory
   coordination, safe cancellation, path lookahead and splicing.
3. Port MineProcess plus world scanning/cache and get-to/exploration behavior;
   reproduce the failed natural-world tree case before resuming the bed run.
4. Port BuilderProcess's actual scheduler and specialized goals, preserving the
   existing schematic inputs, strict blueprint contract and native verification.
5. Close follow/farm/backfill, general goals, settings, diagnostics and operational
   command/API gaps. Flight remains deferred as already agreed.

Keep the independent bridge, UI/NEI/quest/observation tools, notes, routes,
protection, interrupts, optional pause and harness-edit lifecycle. They remain
useful while the navigation backend changes. Full details and acceptance gates
are in [BARITONE_PARITY_AUDIT.md](BARITONE_PARITY_AUDIT.md).

The source migration now compiles 162 verified pinned imports and 183 Gradle
tests pass; eleven Python GTNH profile tests pass and 52 model tools load,
including source settings, process, follow and cache tools: original A*, movement/path lifecycle, composite mining goals,
process arbitration, look processing, `SettingsUtil`, `BuilderProcess`, source
inventory/pause/break/place/input helpers, cache/scanner, and Follow, Explore,
GetToBlock, Farm, and Backfill. Coordinate navigation, saved-route legs and
ordinary work travel use that engine. `mode: builder` selects the adapted
upstream `BuilderProcess`; strict explicit-cell blueprint scheduling remains custom while its travel uses the source navigation job.
Inventory moves use the original `InventoryBehavior` and
`InventoryPauserProcess` with native acknowledged swaps. Cache/scanner and the
other process endpoints are compiled and wired. Farm and Backfill now have
bounded behavior evidence; Explore has only a blocked-arena outcome and
cancellation, not terrain expansion. [Port boundaries and validation](baritone/UPSTREAM_PORT.md)
distinguish this from full parity.

The original natural-world eight-log request now passes in 698 ticks (about
35 seconds), with no damage, compared with zero collected in the old 1,352-tick
attempt. The player retains those logs. Long streaming routes, four-way ladders,
slabs/stairs, bounded tree harvesting and native mining cancellation/resume have
also passed. The source-mining batch now passes seven checks, inventory passes
21, construction passes 81, and `reference_process_smoke.py` passes 25 checks.
The latter covers source goals/get-to-block/native-item follow plus exact cache
metadata, asynchronous save, and loaded-air precedence over a stale cached
solid. Settings atomicity and persistence are verified. The latest serial batch
passes construction (81), machine configuration (76), and the two-phase beam
procedure (12); fresh geometry has 65 checks. The adversarial evidence covers a
42-cell shell plus three authorized air clears, upper/lower slabs, inverted
stairs, fences/panes, a Malisis trapdoor and an exact GT tile. Ordinary source
`ItemDoor` remains unsupported; a Python native-interaction adapter can place
and toggle it. The two-phase beam uses source build and source exit navigation, observes
original-air supports, then uses a harness-selected approach at their mean
coordinates before the final source beam-plus-eight-air cleanup. All eight
supports were removed while the beam and arena remained intact. Source Builder
does not choose that cleanup approach, so this does not prove autonomous
single-call cleanup: the restricted single-plan beam
still times out after 2,599 ticks with zero placements and three removals. A
A same-client machine batch stalled after completing three of five placements. It was
reproduced with source placement cancellation while the predicted placement ray
and native crosshair differed. The 1.7 residual sneak-eye projection correction
and its regression pass. The post-correction sequence passes machine (76),
construction (81), then machine again (76); native five-block placements take
75 and 72 ticks. These reruns support the correction; wider layout coverage
remains open. The 26-check fluid smoke
now has reliable handoff, still-water/swimming dry-bank arrival, and submerged
dry-bank arrival. Its flowing-water target fails `path_calculation_failed` after
44 ticks after routing around the flow; its three required source mine assertions
fail because inherited upstream and DJ2
`MovementHelper` reject an obsidian target with directly-above flowing water.
Farm completes its crop work then reaches the upstream empty-goal stop.
Sealed-corridor Backfill passes excavation and cobblestone refill. Explore is
blocked-arena/cancellation evidence only. Remaining work includes flowing/source-fluid behavior and DJ2 recovery,
event/packet/render/API/silent-look surfaces, strict-blueprint scheduling, deep snow,
native-fluid clutch behavior, wider geometry, and DJ2 regressions.

## Foundation work and historical acceptance

1. **Inventory and GUI interaction: foundation implemented.** Reuse the substantial
   DJ2 implementation for exact stack selection, cursor ownership/recovery,
   inventory/container transfers, click modifiers and machine/crafting UI
   observations. Establish reusable native interaction and acknowledgement
   handling across container types before accumulating individual machine adapters.
   Preserve registry ID, metadata and NBT. Test related operations in batches.
   The generalized primitives and initial cross-container batch are implemented;
   powered AE2 terminal coverage establishes primitives for a later model-written
   adapter; full AE2 automation is deliberately deferred. See
   [INVENTORY_UI.md](INVENTORY_UI.md) for contracts and remaining limits.
   Durable [world notes](WORLD_NOTES.md) now support block, entity, location and
   region attachments, searchable tags and guarded edits with revision history.
   The dedicated-server [tile observation layer](TILE_OBSERVATIONS.md) now exposes
   native configuration/NBT snapshots, inventory, sided tanks, EU/RF interfaces
   and Waila providers through the normal agent tools and custom interrupts.
2. **Targeted interactions and conditional interruption: implemented.** Native
   block-face/hit-position, entity and held-item use preserve mod callbacks and
   full stack identity. Fluid behavior is defined by the held item, with no
   item-specific routing or capacity assumptions. Generic observation predicates
   and reloadable Python predicates run independently of model inference; notify,
   cancel and coordinated pause have distinct effects, durable events and an
   acknowledgement latch. See [contracts and examples](INTERACTIONS_INTERRUPTS.md).
   External runners must adopt the supplied inference-race adapter to discard
   obsolete model responses; MCP cannot interrupt host inference by itself.
3. **Entity observations and bounded combat: implemented.** Nearby entities expose
   transient handles, health, hostility, visibility and dropped stacks; durable
   notes retain server UUID resolution. Stationary kill aura uses native attacks,
   bounded range/duration and shared control ownership. Target loss, obstruction
   and cancellation are explicit. Pursuit remains a composition of navigation and
   combat rather than an autonomous chasing routine.
4. **Bounded mining/building foundation: fixture acceptance only; parity incomplete.** Target-set, quantity-based
   mining now rescans, travels, excavates, loiters for drops and completes only
   on observed net inventory gain. Explicit cells and bounded
   fill/replace/walls/shell selections now have preflight, material allocation,
   native placement and durable recovery. The native work-process batch passed
   19 checks
   and selection/schematic construction passed 75, including cancellation,
   resumption, exact metadata, missing materials and descending excavation.
   Limited GregTech LV drill use and actual collected quantities passed in the pack.
   Construction now chooses among composite work goals using retained Baritone
   search. A 96-block house with stone brick, logs, planks, glass and a slab roof
   passes exact server state/material checks and navigation through its raised
   doorway. A five-component GT machine/pipe/tank schematic also passes native
   placement, wrench/UI configuration, blocked-flow diagnosis and exact 1,000 mB
   resource transfer plus native selection copy/paste (61 checks, now using normal
   agent observations for every decision). The builder retains Baritone's placement-height rule to preserve
   access, and shares navigation's fractional footing. See
   [ACTION_EFFICIENCY.md](ACTION_EFFICIENCY.md) for measured scope.
5. **A bounded natural-world survival run: pilot ended, objective incomplete.** Exercise the
   model's own routines
   for resource gathering, crafting, food, shelter and recovery with a dedicated
   server. The current run resumed native work and collected eight dirt, detected
   and claimed its first Better Questing quest for an apple, and crafted one
   orange carpet from two wool through native operations. A 23-cell shelter then
   stalled on tallgrass ray matching. The health interrupt subsequently observed
   health 14 while burning and paused execution. The ray-semantics correction
   subsequently passed isolated vegetation and shelter regressions; the natural
   shelter was not completed. These partial results are not survival-run completion.
   Distinguish missing primitives from model choices.

Persistent named/recorded routes and default/strict region protection are now
implemented and passed their core live acceptance batch. Do not expand this
feature at the expense of inventory/UI work. Longer saved-route regression is
available for a later combined movement batch. Flight remains a model adapter.

Progression choices, recipe-route selection and higher-level procedures remain
available for the model to develop. The agent must be able to execute and inspect
those choices, edit Python tools, and eventually deploy its own Java changes.

## Retained work after the first survival milestone

- The construction surface now includes the retained format families (MCEdit,
  Sponge v1/v2 and Litematica v4), selections/copy/paste, masks, layers, repeats,
  substitutions, construction-aware route costs, temporary supports/pillaring,
  material pauses and resumable jobs. The 81-check native construction batch
  and 75-check house regression pass. See [construction contracts](CONSTRUCTION_PARITY.md).
  Broader machine configuration and arbitrary native placement callbacks still
  require explicit adapters and additional live evidence.
- The remaining Baritone parity items below, especially natural cave navigation,
  general exploration, wider geometry/fluid coverage and world memory.
- Add a live provider-backed adapter and broader long-horizon evaluation to the
  implemented autonomous runner; the current file-backed operator/model adapter
  requires no provider credentials. Native Better Questing search/observations/sync/detect/claim
  are available; wider task/reward execution coverage remains useful.
- Broader machine/container observations and consistent stack selectors across tools.
- Broaden the implemented build/install/restart/rollback runner lifecycle across
  platforms. Managed deployment/reconnect and native job restart/resume have
  live evidence; rollback failure paths currently have orchestration tests.
- Linux/Docker client and dedicated-server launch, persistent worlds and client
  state, reproducible pack provisioning, and distributable runner packaging.
- Broader pause/resume equivalence evidence for progression mods beyond the tested
  GT generator/cable/macerator/extractor/item/fluid fixture. AE2 and multiblocks
  still need live coverage; OpenComputers has an adapter but no live fixture.

## Baritone comparison baseline

The detailed [implementation audit](BARITONE_PARITY_AUDIT.md) pins upstream
v1.2.19 and the retained 1.12.2 DJ2 source separately. This is not a claim about
the latest upstream release or a percentage-complete estimate. The table below
is a feature summary, not evidence of algorithm parity. [PORT_ORIGIN.md](baritone-core/PORT_ORIGIN.md)
records retained search algorithms and new Forge adapters.

The GTNH module currently ports the A* core and supplies a smaller Navigation
interface, Forge snapshots, movement execution and bounded block work. It does
not yet implement the full upstream process, behavior, command or settings APIs.

| Area | GTNH currently | Remaining parity work and source reference |
| --- | --- | --- |
| Movement repertoire | Cardinal walking, one-block ascent, bounded descent, partial surfaces, attached ladders, vanilla-water surface travel and upward recovery; construction routes add support ascent and native jump-place pillars | Diagonal movement, sprint/parkour jumps, direct downward digging, wider fall handling including bucket-assisted falls, door/gate interaction, broader vine/liquid transitions. See [movement classes](../src/main/java/baritone/pathing/movement/movements). Validate Forge behavior per movement; generic fluid safety must not be inferred from render/material similarity. |
| Route execution | Asynchronous A* over immutable snapshots, live rechecks, continuation across snapshots, cancellation and recovery | Planning the next segment while following the current path, upstream path splicing/post-processing, broader cost/settings behavior and path favoring. See [PathingBehavior](../src/main/java/baritone/behavior/PathingBehavior.java). Long-distance continuation already passes a 416-block course with a 48-block descent; this does not establish arbitrary cave routing. |
| Goal system | Coordinate feet-block goto plus composite construction approach goals and the retained placement-height/clearing heuristic | Exposed XZ-only, Y-level, near, get-to-block, inverted, run-away and direction/axis goals, plus wider process use of composite goals. Retained goal formulas are not equivalent to exposed goal/process support. See [goals](../src/api/java/baritone/api/pathing/goals). |
| World memory and discovery | Source cache/scanner are compiled and wired, with exact-metadata, asynchronous save and loaded-world precedence acceptance; persistent world/dimension waypoints, recorded/named corridor routes, and protected regions remain available | Persistent/natural-world chunk/region cache coverage, block-location discovery and richer home workflows. See [cache](../src/main/java/baritone/cache). |
| Mining process | Bounded quantity/target-set mining with native selector scan, travel, rescan/rejection, drop pickup and completion on net matching inventory gain; limited GregTech LV drill and collected-quantity cases passed live | Composite multi-target mining goals, broader exploration and excavation policy, wider GregTech tool/material evidence, and efficiency/cost tuning. See [MineProcess](../src/main/java/baritone/process/MineProcess.java) and the DJ2 [mining tool](../tools/mcp/tools/mining.py). |
| Other processes | Source Follow, Explore, GetToBlock, Farm and Backfill are compiled and wired; goals/get-to-block/native-item follow have bounded acceptance. Farm has harvest/replant evidence and Backfill has sealed-corridor refill evidence; Explore has only blocked-arena/cancellation evidence. | Terrain-expansion Explore acceptance, wider process behavior and structure-specific reasoning. Arbitrary GTNH crop genetics are beyond what these upstream processes supply. |
| Building and selections | Ordinary `mode: builder` selects source `BuilderProcess`; strict DJ2 blueprint scheduling remains separate while its travel uses the source navigation job. The 81-check construction batch, latest serial 76-check machine configuration, partial-block/GT-tile adversarial cases, metadata/NBT-aware items, selections and canonical imports have bounded evidence. A two-phase source build/navigate/observe procedure uses a harness-selected mean-support approach before the source beam-plus-eight-air build, removing eight observed original-air supports while preserving the beam and arena. | The restricted single-plan beam still exposes a temporary-support cleanup timeout; Builder does not select the two-phase cleanup approach, so that result is not autonomous single-call cleanup. Complete adversarial evidence, wider native callbacks/layouts and arbitrary tile inventories/NBT or multiblock formation still need normal-interaction adapters. Ordinary source `ItemDoor` remains unsupported. |
| Inventory/tool integration | Native harvest estimates, main-inventory selection, verified vanilla/Tinkers tools, limited live GregTech LV drill use, and limited placement materials | Broader tools and placeable states, reusable materials/throwaway-block policy, inventory coordination across mining/navigation/builder jobs, and wider GT tool callback coverage. |
| Process management and API | Shared input ownership, cancellation, deadlines, status, durable mining/build IDs with re-observing resume, construction pause independent of world time, small Navigation API, and bounded navigation/work MCP operations | Upstream process priorities/arbitration, wider process pause/resume independent of freezing the server, settings surface, path/goal events, command coverage and compatibility with integrations expecting baritone.api. See [API](../src/api/java/baritone/api) and [DefaultCommands](../src/main/java/baritone/command/defaults/DefaultCommands.java). Functional parity and API/command compatibility must be tracked separately. |
| Diagnostics | Structured job, terrain, fluid, tool and route status | Upstream goal/path/selection visualization, broader ETA/process diagnostics, settings persistence and cache management commands. DJ2-specific explain/audit/profiling helpers also remain to be assessed. |

## Version-specific and Modbench features

The local source includes Elytra-related APIs/code, but vanilla Elytra does not
exist in the GTNH 1.7.10 target. Record that as version-inapplicable; GTNH jetpacks
or modded flight is intentionally left for a model-written adapter if a run reaches that point; it is not a prerequisite for this port.
Likewise, modern schematic formats need a mapping into 1.7.10 registry/metadata
semantics; retaining a parser is not sufficient compatibility evidence.

Kill aura, recipe/quest tooling, world pause and general harness interrupts are
Modbench features. The DJ2 combat routine is in
[ActMethods](../src/main/java/baritone/moddedbench/bridge/ActMethods.java), and its
reactive guard is in [SafetyGuard](../src/main/java/baritone/moddedbench/bridge/SafetyGuard.java).
They remain part of the GTNH product plan independently of upstream Baritone parity.

## Evidence required to close items

Mark a feature complete only after its intended behavior works in the real pack,
with appropriate cancellation, control handoff and failure recovery checks.
Mining acceptance must measure the requested items actually collected; building
must compare placed states to the target and report missing resources; movement
must survive relevant terrain and interruptions. Preserve bounded fixtures and
restore their journals. Record version-inapplicable features explicitly and keep
unsupported/unverified behaviors visible through capabilities and documentation.

Current accepted results and limits are in [VALIDATION.md](VALIDATION.md),
[NEI_COVERAGE.md](NEI_COVERAGE.md), and [TIME_CONTROL_AUDIT.md](TIME_CONTROL_AUDIT.md).

Bounded work-process acceptance is recorded in [VALIDATION.md](VALIDATION.md).
It establishes the listed native scenarios, not the complete upstream builder
or arbitrary modded machine placement.
