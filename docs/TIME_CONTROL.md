# Time control

An opt-in whole-simulation-tick gate on the dedicated server. Real time is the
default. `time.pause` stops complete server ticks, `time.resume` returns to
real time, and there is no stepping or client/server lockstep. The tool is
`mb_time` (`status`, `pause`, `resume`, `configure`, `report_failure`).

## Contract

| While paused | Behaviour |
| --- | --- |
| Server simulation | Stopped: Forge tick events, all loaded dimensions, entities, fluids, tile entities. |
| Client simulation | The client gates its own simulation ticks while the server is paused. |
| Still live | Networking, keepalives, bridge requests, reconnect negotiation, chunk delivery, observations, screenshots, NEI inspection. |
| Gameplay packets | Deferred, then released in per-connection order at the network stage of the first resumed tick, after world updates. |
| Actions | `act.*` and `gui.*` actions are rejected before any input; resume first. A running action can still be stopped. |

Guards (`healthDrop`, `healthBelow`, `airBelow`, `foodBelow`, `burning`,
`pauseOnDisconnect`, and `actionFailed`, which pauses when a caller sends
`time.report_failure`) pause at a tick boundary. They never act.

A snapshot read while paused is responsive, not a transactionally frozen view
of the client. The gate is a bounded development control: it does not claim
that every thread, timer or external service in the pack has stopped.
Integrated-server play and multi-client coordination are outside the contract.

The paused maintenance path follows the pause-when-empty behaviour of the
ServerUtilities build shipped with GTNH (chunk I/O completion, networking,
pending server commands). Modbench adds explicit pause ownership, client
coordination, deferred gameplay packets and the barriers below.

## Background-work barriers

**GregTech.** GregTech's background structure jobs are admitted through the
`AsyncPause` barrier in `mods/core`. A pause settles only after admitted jobs
finish; new jobs wait until resume. Clock status reports the barrier state.

The barrier has one invariant: **it is only begun while the tick gate is
closed.** GregTech's server tick handler takes one semaphore permit per posted
machine-update task, and a task blocked in `AsyncPause` never returns its
permit, so a barrier requested during a running tick would hang the server
thread inside that tick with no way to deliver `time.resume`.
`PauseCoordinator` (Minecraft-free, in `mods/core`, unit tested with fake
barriers) owns the ordering:

1. Begin the barriers only after `SimulationClock.pause`.
2. Close the gate on the next `before()`.
3. On `time.resume`, resume the barriers before the clock.
4. If the GregTech barrier is ever found requested while the clock is not
   paused, resume the barrier and log a warning instead of deadlocking.

**Shutdown ordering.** FML delivers `FMLServerStoppingEvent` in sorted mod
order, and GregTech's stopping handler waits up to 60 s + 60 s for its update
executor. `modbenchserver` therefore declares `before:gregtech`, so its own
stopping handler resumes the barrier first and blocked tasks finish normally.
`after:gregtech` would run too late: FML sorts event delivery, not only
loading. `ModOrderingTest` pins this.

**OpenComputers.** Registered machines receive `Machine.pause(0)` at a pause
transition and the clock waits for those requests to settle. No live
OpenComputers workload has been tested.

Other asynchronous mods have not been audited.

## Evidence and limits

Accepted on 2026-09-12 with the development fixtures:

- Frozen time in all dimensions; fixture furnace, fluid and entity state
  unchanged; responsive screenshots and GUI observation; deferred actions;
  guard pauses; keepalives; disconnect and reconnect; chunk delivery; a full
  client JVM restart while paused; resume.
- GT progression equivalence: one uninterrupted 800-tick run and four
  interrupted 200-tick windows ended with the same chest inventory, machine EU
  and tank/pipe fluid state (diesel generator, cable, macerator, extractor,
  item and fluid export).

Not verified: full-pack timer quiescence, Applied Energistics, GT multiblocks
under pause, live OpenComputers workloads, arbitrary restart and recovery
scenarios. State this contract and the controlled fixture when citing a run;
do not generalise it to the pack.

The launcher checks installed jar hashes before launch because a stale core
jar next to a newer client jar fails at runtime with `NoSuchMethodError`.
