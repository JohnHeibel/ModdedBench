# History

Dated milestones, newest first, one to three lines each.

The 25 detailed records they summarise lived in `docs/legacy/`, last present
at commit `01ab0b6` (`git show 01ab0b6:docs/legacy/<file>`): old README,
ROADMAP, LAYERS, VALIDATION, Baritone parity audit and port notes, time-control
audit, runtime acceptance, EBF trial and follow-ups, and the per-subsystem
contract notes. What was still true is now in the current docs.

- **2026-09-30** Builder block goals could stop at a boundary overlapping the
  next placement, oscillating between stances. Construction now centres on its
  verified footing when native prediction permits the centred pose only;
  recovery cancels the movement segment without cancelling the builder and
  considers only its active layer, so future cells cannot interrupt traversal.
- **2026-09-30** Construction egress accepted low neighbouring stances beneath
  open floor cells, repeatedly reaching a goal with no actionable placement.
  Egress now applies the source placement scan's height restrictions.
- **2026-09-30** Short sneak+mouse chords still withdrew single drawer items on
  the contained server. `mb_act` now composes a bounded native pose hold before
  modifier clicks, reports it, and sends no click if that hold is interrupted.
- **2026-09-30** Default detection of an unfinished quest sent an empty task
  list rejected by the native adapter. `mb_quest_detect` now supplies observed
  task IDs and preserves explicit selections, including checkbox quests.
- **2026-09-21** Python build tools confine builder-mode edits to plan cells by
  default, including staged plans. Unrestricted access excavation uprooted a
  workshop station; explicit `settings.restricted: false` remains available.
- **2026-09-21** Builder source-fluid replacement now uses its native reachable-face
  placement adapter. The upstream BuilderProcess previously required standing
  directly above water, stalling safe side placement beneath an overhang.
- **2026-09-21** Direct sneak+attack/use chords prime native sneaking for two
  ticks before the mouse press; simultaneous input previously withdrew single
  drawer items because the pose packet followed the click.
- **2026-09-21** Named key presses now dispatch native FML input events with
  synthetic event state. Better Questing's key previously queued a binding but
  never opened its screen; a GUI opened by the press now completes that input.
- **2026-09-22** Detecting an already completed Better Questing quest sent an
  empty task request that the native adapter rejected. `mb_quest_detect` now
  returns the observed completion without sending a detect packet.
- **2026-09-21** Saving a survival camp waypoint exposed `mb_view` assuming array
  coordinates. It now accepts the native world-memory coordinate objects for
  waypoints and protected regions, retaining spatial filtering.
- **2026-09-21** Stone Age mortar batches exposed a crafting composition gap.
  `mb_craft` now accepts explicit per-cell counts for retained tools and returns
  remaining ingredients after crafting; verified with a 19-clay batch in survival.
- **2026-09-19** Restructured into this repository from the ModdedBench
  workspace (Baritone fork branch `modbench-gtnh`, commit `a1c43ef4`).
  LGPL-3.0-or-later throughout; one coremod (`core`), plain Baritone jar, the
  whole `harness/tools/` directory hot-reloaded, world notes surfacing as a
  side effect of observations, `PROMPT.md` for quest-book runs.
- **2026-09-18** Electric Blast Furnace agent trial: a supervised agent built
  and ran an EBF line from supplied materials and exported two aluminium
  ingots. Follow-up fixed grass clearing, attack-hold bounds and food handling
  (13 live regression checks). Layer ownership doc updated with conditional
  self-prompts and survival guards (`foodBelow`, `burning`).
- **2026-09-14** Native runtime and API acceptance: event bus, rendering,
  free-look and the Java process API run through Forge 1.7.10 adapters; 16
  live checks. Roadmap refreshed.
- **2026-09-13** Baritone parity audit against upstream, then the source port of the upstream engine (pinned upstream
  revision `d9cb2d91`, 162 files) validated. Natural-world log mining attempt
  recorded as a failure (nothing collected in 1,352 ticks) and fixed later.
- **2026-09-12** First standalone GTNH bridge milestone on Windows: client and
  server bridges, tokens, observations, aiming, finite input, cancellation,
  screenshots. Time-control audit accepted the whole-tick gate contract with
  GregTech and OpenComputers background-work barriers.
- **Before 2026-09-12** The harness targeted a different, 1.12.2 modpack. Its
  structure informed this one; none of its code is here.
