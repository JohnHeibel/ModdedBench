# Time control

An opt-in whole-simulation-tick gate on the dedicated server. Real time is the
default. `time.pause` stops complete server ticks, `time.resume` returns to
real time, and `time.step` runs exactly N server ticks, then pauses again. The
tool is `mb_time` (`status`, `pause`, `resume`, `step`, `configure`,
`report_failure`).

Two rules sit above the agent. Time control belongs to the bridge session that
last used it, and losing that session pauses the world (`agent_disconnected`).
A hold (the file `modbench-hold` in the server directory, status `held`)
pauses the world and refuses `time.resume` until the file is removed. The
file's one word says whose hold it is (`operator`, `backup`, `compaction`);
the status carries it as `heldBy`, and the refusal says what to expect of that
holder (`PauseCoordinator.heldRefusal`), so an agent that meets the half-hourly
backup reads that it is a backup and short. The pause a hold makes is named
for the holder too (`operator_hold`, `backup_hold`), and any reason ending in
`_hold` is waited out by a running job and resumed only by the hold's release.
A hold that interrupts a step gives the step its remaining ticks back on release.

## Contract

| While paused | Behaviour |
| --- | --- |
| Server simulation | Stopped: Forge tick events, all loaded dimensions, entities, fluids, tile entities. |
| Client simulation | The client gates its own simulation ticks while the server is paused. |
| Still live | Networking, keepalives, bridge requests, reconnect negotiation, chunk delivery, observations, screenshots, NEI inspection. |
| Gameplay packets | Deferred, then released in per-connection order at the network stage of the first resumed tick, after world updates. |
| Actions | `act.*` and `gui.*` actions are rejected before any input unless they carry `_resume`; the refusal (`time_paused`) names the pause's reason, and says so when a guard made it. A running action can still be stopped. |

## Stepping

`time.step {ticks:N}` (1..72000) resumes, counts N complete server ticks and
pauses with reason `step`. It replies once that pause has settled, like
`time.pause`. `time.resume {ticks:N}` does the same but replies at once. The
state carries `step {id, ticks, remaining}` while stepping and `lastStep {id,
ticks, ran, endedBy, clientTicks}` after: `endedBy` is `step`, a guard's reason,
or `superseded` when another pause or resume ended it. The fixture windows are
steps that end with `fixture_checkpoint`.

The client is not in lockstep. It receives the step with the state broadcast and
runs at most `remaining` ticks of its own, then waits for the pause
(`clientTicks` in `lastStep` is what it ran). A navigation job is suspended at
the step's pause (below). A single action (a click, a selection, a held input)
is never cut short by a step: a click is sent on its first tick and answers a
few ticks later, so a short step used to report a click that had landed as
cancelled. At the step's settled pause the client runs one more tick and asks
the server for the same, as for the first tick of a resume-and-act, and again
until the action answers (`ClientClock.extendStep`; `harness/smoke/step_course.py`
is its test in the game); the action's own
tick budget bounds it, and `resumedWorld.extendedTicks` says how many were
added. Only a step's own pause is extended. A guard pause ends all of them and
reports why; a pause the agent asked for leaves them waiting instead.

## Resume and act in one request

An acting request may carry `_resume`: `true` to resume, `N` to step N ticks.
Reads ignore it. When the world is paused, the client admits the action, runs
its first tick while the server is still paused, and only then sends
`time.resume` on the same connection. The server defers that tick's gameplay
packets (they arrive before the resume) and replays them first in its first
resumed tick, so the action starts on that tick, and the client counts its
early tick as the first of a step. The reply carries `resumedWorld` (the pause
it lifted). The client refuses up front when the pause is unsettled or held; if
the server still refuses, the action ends with `resume_refused` and the world
stays paused. Tools expose this as `resume=True` or `resume=N`: only the tool
call's first acting request carries the directive, so a pause that comes later
in the same call (a guard) stands, and a call whose resume lifted nothing says
`resumeUnused`. A request the game thread refuses resumes nothing.

A navigation job still running when its step ends is suspended, not cancelled:
the request is answered with state `suspended` and a `suspendedJobId`, and the
job keeps its controls, break progress and path, and runs on whenever the
world runs. `nav.resume {jobId: suspendedJobId}` waits on it again (or returns
its outcome if it finished meanwhile). A guard pause or any new action ends it.

Guards are set with `time.configure` and pause at a tick boundary. They never
act.

| Guard | Pauses when |
| --- | --- |
| `healthDrop` (boolean) | the player loses health |
| `healthBelow`, `airBelow`, `foodBelow` (number, -1 off) | the value crosses the threshold |
| `burning` (boolean) | the player is on fire |
| `threatWithin` (blocks, at most 32, -1 off) | a mob takes the player as its target within N blocks (2N with line of sight) or a creeper starts to swell; reason `threat`, once per mob |
| `actionFailed` (boolean) | a caller sends `time.report_failure` |
| `pauseOnDisconnect` (boolean, on by default) | the client disconnects |

A guard's
reason stays the pause reason until the resume, whatever pauses after it; one
that fires on a step's last tick ends the step and is its `endedBy`; and each
threshold is reported by its own pause, once, until the value recovers. While a
fight job runs, `healthDrop` and `threat` from mobs within its range (4 blocks,
8 standing) stay quiet; the rest stay armed.

A snapshot read while paused is responsive, not a transactionally frozen view
of the client. The gate is a bounded development control: it does not claim
that every thread, timer or external service in the pack has stopped.
Integrated-server play and multi-client coordination are outside the contract.

The paused maintenance path follows the pause-when-empty behaviour of the
ServerUtilities build shipped with GTNH (chunk I/O completion, networking,
pending server commands). ModdedBench adds explicit pause ownership, client
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

## Limits

The gate was checked with development fixtures (a furnace, fluids, entities and
a small GregTech line): state did not change while paused, and a GregTech line
ended in the same state whether it ran uninterrupted or in paused windows.
That check was made in September 2026 on an earlier layout of this code and
has not been repeated as a whole since. Stepping and resume-and-act have a
test in the game (`harness/smoke/step_course.py`), and `PauseCoordinator` and
`SimulationClock` are unit tested.

Not verified: that every timer in the full pack is quiet while paused, Applied
Energistics, GT multiblocks under pause, live OpenComputers workloads,
arbitrary restart and recovery scenarios. Do not generalise the contract above
to the whole pack.

The launcher checks installed jar hashes before launch because a stale core
jar next to a newer client jar fails at runtime with `NoSuchMethodError`.
