# Electric Blast Furnace agent trial — 2026-09-18

**PASS for supplied-resource assembly and operation.** A supervised subagent
built and commissioned an EBF line in GTNH 2.8.4 survival, then processed two
separate aluminium recipes with automatic input and output transport.
This is not a resource-progression or unattended long-duration benchmark.

## Setup and constraints

The parent installed the previously built matching mod jars, backed up the
world, enabled native `/give` permission for ModbenchDev, and started the local
dedicated server with development fixtures disabled. The subagent owned all
gameplay. The parent supplied read-only diagnostic observations during a stall
and independently checked the final state. No bridge code was changed during
the trial. Short JavaScript compositions called existing native tools.

Materials, equipment, tools and finite fuel were supplied through `/give`.
The recorded command audit contains only `/give`, no output ingot grants, and
no development-fixture calls. No creative mode, direct world edits, injected
machine energy, or direct machine NBT edits were used.

## Result

- Controller: `[-137,75,361]`; formed structure, native Waila `Running Fine`.
- Kanthal coils; six maintenance flags repaired with normal duct-tape use.
- HV combustion generator `[-139,75,362]` burns supplied Light Fuel and powers
  the adjacent west-facing HV energy hatch `[-138,75,362]`.
- Inlet chest `[-138,76,364]` -> hopper `[-138,75,364]` -> input bus `[-138,75,363]`.
- Output bus `[-136,75,363]` -> output chest `[-136,75,364]`.
- Live NEI recipe: one aluminium dust, retained circuit 1, 2,054 K minimum,
  480 EU/t, 1,500 ticks, no fluid input; one ordinary aluminium ingot output.
- Two distinct cycles completed with batch mode disabled. Two fed dust were
  consumed; circuit 1 remained; exactly two ingots reached the output chest.
- Independent same-server-tick observations at tick 9,010: ingots
  `gregtech:gt.metaitem.01:11019` x2; inlet and hopper empty; input bus contains
  only the circuit; generator retains 10,218 mB Light Fuel and 41,386 EU;
  energy hatch has 4,608 EU; controller healthy/idle; player health/food 20/20.
- Runtime left settled paused, with the completed line intact.

## Recoveries and remaining issues

These describe the original trial. The subsequent [primitive fixes and live
regressions](PRIMITIVE_REGRESSIONS.md) address grass clearing, attack retargeting,
and food-action handling without rerunning the entire EBF assembly.

1. `act.eat` failed twice for baked potato; ordinary held-use input consumed it.
2. The source builder stalled twice on zero-hardness tall grass. Native
   look/attack clearing resolved it; the subsequent 35-cell build completed in
   375 client job ticks. Tool inspection reported `cannot_break`; source
   `MiningTools.best` rejects non-finite strength, which merits a separate
   instant-break regression/fix. The trial did not patch that behavior.
3. A misoriented energy hatch prevented power transfer. During normal wrench
   removal the agent also removed the adjacent hatch; both dropped machines
   were recovered and replaced. Lost fuel was replaced with 16 further supplied
   Light Fuel cells. No replacement machine grant was needed.

Only this no-fluid aluminium recipe was tested. Fluid recipes, higher tiers,
cooling, resource acquisition and ongoing fuel logistics remain unvalidated.

## Evidence

Local, ignored artifacts are under `.runtime/evidence/ebf-agent-2026-09-18/`:
`actions.json`, `trial-summary.json`, `final-native-state.json`,
`parent-final-observations.json`, `parent-command-audit.json`,
`parent-running-controller.json`, installed-jar hashes and screenshots.
The pretrial world backup is `.runtime/backups/ebf-before-2026-09-18/`.
