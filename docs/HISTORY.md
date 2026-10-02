# History

Dated milestones, newest first, one to three lines each.

The 25 detailed records they summarise lived in `docs/legacy/`, last present
at commit `01ab0b6` (`git show 01ab0b6:docs/legacy/<file>`): old README,
ROADMAP, LAYERS, VALIDATION, Baritone parity audit and port notes, time-control
audit, runtime acceptance, EBF trial and follow-ups, and the per-subsystem
contract notes. What was still true is now in the current docs.

- **2026-10-02** Scripts saw bare tool functions and rejected `resume`, so a chore
  could not step the world between actions. Script tool calls now take resume=True|N
  through the same kernel routine as direct calls (found by the agent overnight).
- **2026-10-02** A farm with nothing ready searched for an empty goal, failed and
  dropped the job. FarmProcess now waits instead; the job's own limits still end it
  (found by the agent overnight).
- **2026-10-02** Building a subset of a drawing ignored its listed heights and
  placed walls at the floor. Drawing conversion now preserves absolute layer
  heights and rejects nonnumeric survey heights and overlapping layers.
- **2026-10-02** Resuming mining reread the original forced-tool slot after
  crafting had rearranged it. The journal now retains the chosen tool kind
  across resumes and client restarts; missing tools cannot become other items.
- **2026-10-02** Farming rejected empty selector lists, preventing pickup-only jobs.
  Explicit empty lists now disable individual farm phases; omitted fields retain
  their defaults, and nonempty selectors keep their normal validation.
- **2026-10-01** Container clicks aimed at an obstructed block centre could miss
  its exposed rim. Crafting and item moves now accept optional native face and
  hit coordinates; verified opening a buffered input hopper from ground level.
- **2026-10-01** Opening machine GUIs with a held tool toggled machines instead.
  Container compositions now prepare and verify an empty hand, restoring parked
  stacks after the GUI opens; full inventories require explicit interaction.
- **2026-10-01** A malformed machine input moved an unrelated tool before failing.
  Crafting now validates every item selector and count before opening a GUI or
  transferring anything; fake-kernel tests cover invalid later inputs too.
- **2026-10-01** Ranged calibration averaged impact velocities into flight drag.
  It now fits consistent velocity samples and undoes gravity as well as drag
  when estimating launch speed; recorded throwing-weapon impacts reproduced it.
- **2026-10-01** Construction stopped with `invalid pitch` after a native vertical
  aiming nudge exceeded 90 degrees. Placement now clamps generated fallback pitch
  while continuing to reject out-of-range explicit angles.
- **2026-10-01** Reached-goal quantity mining reset its swing by publishing
  input twice per tick. Adapter aiming now runs before the single publication;
  direct mining proved the same tool and target could break normally.
- **2026-10-01** Quantity mining could reach an exposed target's approach goal
  and stall without swinging. The adapter now uses native tool selection,
  reachable targeting and attack input at a reached goal, retaining break safety.
- **2026-10-01** Single-block mining and placement could immediately lose input
  focus after closing a container. They now restore logical game focus before
  starting, matching the other navigation actions.
- **2026-09-30** Tool probes returned `game_did_not_answer` for every stack,
  blocking mining. Native observations now initialize the game-thread answers,
  and navigation ticks service queued path-search tool and block-identity probes.
- **2026-09-29** Stepping: `mb_time` `step {ticks:N}` runs exactly N server
  ticks and pauses again, and acting tools take `resume=N`. `resume=True` now
  starts the action on the first resumed tick: the client runs that tick before
  asking the server to resume, and the server replays its packets first. Live in
  a test world: the server position matched the client's after one-tick steps.
- **2026-09-29** Spectator mirror (`harness/mirror`, [MIRROR.md](MIRROR.md)): a host-side byte pipe between the
  client and the server whose copy feeds a read-only, compacted world for stock GTNH clients on a separate port.
  A stock client joined it live; that found GTNH's extra item varint, the need to pace the snapshot on the
  viewer's FML handshake, and a crash on an avatar spawn with no metadata. Join by Direct Connect.
- **2026-09-29** Builder block goals could stop at a boundary overlapping the
  next placement, oscillating between stances. Construction now centres on its
  verified footing when native prediction permits the centred pose only;
  recovery cancels the movement segment without cancelling the builder and
  considers only its active layer, so future cells cannot interrupt traversal.
- **2026-09-29** Construction egress accepted low neighbouring stances beneath
  open floor cells, repeatedly reaching a goal with no actionable placement.
  Egress now applies the source placement scan's height restrictions.
- **2026-09-29** Short sneak+mouse chords still withdrew single drawer items on
  the contained server. `mb_act` now composes a bounded native pose hold before
  modifier clicks, reports it, and sends no click if that hold is interrupted.
- **2026-09-29** Default detection of an unfinished quest sent an empty task
  list rejected by the native adapter. `mb_quest_detect` now supplies observed
  task IDs and preserves explicit selections, including checkbox quests.
- **2026-09-22** Detecting an already completed Better Questing quest sent an
  empty task request that the native adapter rejected. `mb_quest_detect` now
  returns the observed completion without sending a detect packet.
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
