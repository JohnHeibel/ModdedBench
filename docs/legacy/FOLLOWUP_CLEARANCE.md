# Single-job clearance and loaded destination follow-up

This checkpoint supersedes the scaffold-cleanup limitation in `bc6ea0fb`.
It does not close the full Baritone parity audit.

Ordinary source-builder jobs now distinguish construction, clearance egress,
and final clearance. Initially empty, explicitly requested air cells are deferred
while construction needs temporary supports. Initially occupied or unobserved
clearance cells remain part of construction. Clear-only jobs and jobs without
temporary placement retain their existing behavior.

Before clearance, the adapter supplies observed safe exit goals to the original
BuilderProcess calculation context. Its original A*, movement classes, native
placement helpers, exact throwaway selection, and edit permissions build the exit.
Final clearance disables new temporary placement and supplements original break
goals with observed reachable ground poses. These are version-adapter additions,
not algorithms present in the pinned upstream source. The job's deferred cells,
repeat and phase are checkpointed; resume re-observes the world. Receipts expose
`buildPhase` and `deferredAirCells`. Clearance-phase resume has not had a separate
live interruption test.

The single-plan beam fixture now authorizes observed air through beam height
and the required exit area, excluding all existing blocks and the five finished
beam cells. The old envelope ended below the beam, excluded its exit bridge,
and cannot be reported as a successful regression with identical constraints.
Temporary edits remain inside the explicitly authorized cells.

Validation:

- 172 Gradle tests pass, including construction/clearance snapshots and loaded
  destination transitions. All 159 pinned source imports verify.
- Seven single-job beam checks pass: five mixed-material beam blocks, autonomous
  pillars and exit, eight support removals, complete final-air verification,
  preservation of pre-existing blocks, and released controls. Job: 325 ticks,
  no damage. Evidence: `.runtime/evidence/adversarial-beam-autonomous-clearance.json`.
- The 81-check construction suite passed with initial deferral enabled before
  the final egress adapter was added. It was not rerun after that final addition.

Coordinate navigation now keeps the original physical destination, updates its
source feet goal when a bottom slab becomes loaded, and retains the last observed
normalization across unload. Original CustomGoalProcess performs revalidation;
status counts `goalRenormalizations`. The transition is covered offline, not yet
by a live streaming/slab-boundary fixture.

Remaining priorities: broader exploration/cache acceptance, fluid and geometry
coverage, and full API/event/render integration. Flowing-water navigation and
water-capped obsidian mining still have the recorded limitations. No fluid
safety rule was relaxed in this checkpoint.
