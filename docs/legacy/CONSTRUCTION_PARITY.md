# Construction port

**Implementation parity is incomplete.** This document describes the current
construction surface and fixture coverage. Its scheduler, work goals and native
execution are custom replacements, not the actual upstream BuilderProcess.
The [2026-09-13 audit](BARITONE_PARITY_AUDIT.md) records the remaining gaps and
supersedes any interpretation of these settings/formats as full builder parity.

The comparison baseline is this repository's retained 1.12.2
`BuilderProcess`, `SelCommand`, schematic implementations and DJ2 blueprint
guard. This is functional construction support through Modbench; the 1.12 Java
API and chat command classes are not binary compatible with 1.7.10.

## Two construction contracts

`mode: "blueprint"` preserves the DJ2 contract: explicit selected cells,
complete initial material allocation, no incidental access excavation or
scaffolding, a durable attempt ledger, and exact final registry/metadata checks.
This is the default for existing calls.

`mode: "builder"` uses a live schematic frontier, local re-observation,
construction-aware path costs, material replenishment pauses, layers, repeats,
substitutions and predicate-based completion. `allowBreak` and `allowPlace`
authorize route excavation and support placement; `replaceExisting` authorizes
clearing occupied schematic cells. Region protection applies to all effects.
`settings.restricted: true` limits route edits to the active schematic cells.
Omitted cells remain unconstrained in an unrestricted builder. Desired air is
explicit and will be restored after temporary work inside it.

The builder prices usable desired structure supports at zero, temporary supports
in desired air at twice normal placement cost, and available throwaway substitutes
for unavailable desired blocks at three times normal cost. Correct-block breaking
has a configurable multiplier (10 by default). The path graph can ascend while
clearing headroom or placing a support, and can jump-place a pillar through native
input. It still respects native collision, reach, harvest and fluid constraints.

Throwaway materials are explicit item selectors in
`settings.acceptableThrowawayItems`, with registry ID, metadata, NBT and ore
dictionary matching. There is no implicit vanilla throwaway list in builder mode.
Desired structure materials come from the native block-item mapping or the
cell's explicit `item` selector.

Cells can add `verify.pickedItem` as a separate item selector. Completion then
requires both the world block ID/metadata and the observed native pick-block
stack to match. This supports machine and pipe variants whose configuration is
identified by their picked item while keeping the placement `item` selector
independent.

## Settings

Settings are validated and frozen into each durable job. Resume accepts a new
timeout and edit/protection permissions, not a different schematic or settings.

| Settings | Meaning |
| --- | --- |
| `buildInLayers`, `layerHeight`, `startAtLayer`, `layerOrder` | Cumulative bottom-up layers; `layerOrder:true` reverses them. |
| `skipFailedLayers` | Advance an inaccessible layer when later layers remain. |
| `buildRepeat`, `buildRepeatCount`, `buildRepeatSneaky` | Translate each completed copy; -1 repeats until stopped or world limits intervene. Sneaky repeats retain the frozen replace mask. |
| `schematicOrientationX/Y/Z` | Apply the retained builder's source-dimension origin shift on the selected axes. These are origin shifts, not rotations. |
| `mapArtMode` | Keep the highest desired non-air cell and desired air above it in each XZ column. Empty columns are ignored. |
| `buildIgnoreExisting`, `buildIgnoreBlocks`, `buildSkipBlocks`, `okIfAir`, `okIfWater` | Completion predicates. As in retained BuilderProcess, `okIfWater` accepts liquid blocks; it does not authorize entering a hazardous fluid. |
| `buildSubstitutes` | Ordered material alternatives. Use explicit `{id,meta,item?,placement?,verify?}` for metadata-sensitive states. |
| `buildValidSubstitutes` | Accept already placed alternate block IDs. |
| `metadataMasks` | Per-block masks of significant metadata bits, replacing named property-ignore rules that do not exist in 1.7.10. |
| `allowInventory` | Permit normal container transactions to bring materials from main inventory into the hotbar. |
| `restricted`, `repairPlaced` | Restrict access edits; allow re-observation and correction of previously attempted cells. Strict blueprints retain their stronger no-destructive-retry rule. |
| `breakFromAbove`, `goalBreakFromAbove` | Permit lower local mining targets and approach goals above targets. The player's own support is always excluded. |
| `distanceTrim`, `incorrectSize`, `builderTickScanRadius` | Nearby work preference, full-scan frontier size and local re-observation radius. |
| `breakCorrectBlockPenaltyMultiplier`, `acceptableThrowawayItems` | Route damage cost and explicit temporary construction stock. |

The native 1.7.10 bridge intentionally defaults `buildRepeatCount` to `1` and
`buildRepeatSneaky` to `false`, rather than retained upstream's unbounded `-1`
and sneaky default. Repetition therefore requires an explicit durable request and
does not silently retain a frozen replace mask.

## Files, selections and lifecycle

See [SCHEMATICS.md](SCHEMATICS.md) for MCEdit, Sponge, Litematica, palette
mapping, filename fallback, and source dimensions. Modern properties must be
mapped explicitly into native registry/metadata/item/placement states.

`mb_selection` provides world/dimension-scoped pos1/pos2, multiple selections,
undo/clear, expand/contract/shift for all/newest/oldest, and exact union copy/paste
with anchor offsets. `mb_selection_build` supports fill, replace, walls, shell,
clear, sphere/hsphere and cylinder/hcylinder with an axis. Copy includes selected
air; holes between selections remain unselected.

`mb_builder_pause` releases player controls and returns a paused receipt. The
server continues ticking. `mb_work_resume(job_id)` re-observes world and stock
before resuming, including after a client restart. Missing stock produces the
same durable paused state. `mb_builder_materials` exposes native item/block
mappings, initial item metadata and native item-placement base metadata (also
exposed by `nei.item.placement`); these are approximate states because side,
hit, player pose, and mod callbacks determine the final state.

Explicit builder plans support up to 1,048,576 cells. The harness stages batches
of 4,096 with checked offsets, then finalizes an immutable world-scoped plan.
The Java journal stores the frozen specification separately from progress and
fsyncs an append-only placement-intent ledger before each native click.
`mb_work_status` reads the bounded checkpoint and specification summary without
rehydrating the full plan or ledger; collections over 128 entries are represented
by an explicit `{omitted:true,count:N}` marker. Resume still loads the full data.

## Native state and limits

Placement calls native Forge/Minecraft right-click handling. It does not write
world blocks directly. Initial state prediction calls native `onBlockPlaced`.
`NativePlacement` maps native item placement metadata, including the GT prototype
registry used by all `ItemMachines` variants. Pure class adapters account for known
`onBlockPlacedBy` orientation behavior;
mod adapters can register their own predictor in `PlacementStateAdapters`.
Unknown effects must be verified after the real action; the model can edit the
adapter rather than fabricate a world update. Final block IDs and metadata are
observed independently of the controller's return value.

Tile inventories, arbitrary tile NBT, machine configuration and multiblock
formation are separate normal-interaction adapters. Retained upstream building
does not guarantee those effects either. There is no 1.7.10 Litematica GUI to
attach to; its file format is imported externally. Construction tests establish
the listed native cases, not every GTNH machine's placement callback.

Native validation is recorded in [VALIDATION.md](VALIDATION.md). Core route
tests compare actual path choices, and mask tests use the retained source's
geometry, including its inset cylinder radius.
