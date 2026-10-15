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
`_hold` is waited out by a running job and resumed only by the hold's release;
an agent's own `time.pause` or `interrupt.fire` may not use such a reason.
A hold that interrupts a step gives the step its remaining ticks back on release.
A `backup` or `compaction` hold whose file is older than 20 minutes
(`PauseCoordinator.STALE_HOLD_MINUTES`) was left by a holder that died: the
server removes the file and the world runs again. The operator's hold never
expires.

The pause, its reason and the rules (below) are kept in `modbench-clock.json`
in the world's folder and written when they change. A server that restarts reads
it: the rules watch at once and the pause is applied, under its saved reason,
after the 20 warm-up ticks every start runs. A hold's pause is not saved; the
hold file restores it. Not tested in the game.

## Contract

| While paused | Behaviour |
| --- | --- |
| Server simulation | Stopped: Forge tick events, all loaded dimensions, entities, fluids, tile entities. |
| Client simulation | The client gates its own simulation ticks while the server is paused. |
| Still live | Networking, keepalives, bridge requests, reconnect negotiation, chunk delivery, observations, screenshots, NEI inspection. |
| Gameplay packets | Deferred, then released in per-connection order at the network stage of the first resumed tick, after world updates. |
| Actions | `act.*` and `gui.*` actions are rejected before any input unless they carry `_resume`; the refusal (`time_paused`) names the pause's reason, and says so when a rule made it. A running action can still be stopped. |

## Stepping

`time.step {ticks:N}` (1..72000) resumes, counts N complete server ticks and
pauses with reason `step`. It replies once that pause has settled, like
`time.pause`. `time.resume {ticks:N}` does the same but replies at once. The
state carries `step {id, ticks, remaining}` while stepping and `lastStep {id,
ticks, ran, endedBy, clientTicks}` after: `endedBy` is `step`, a rule's reason,
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
added. Only a step's own pause is extended. A rule's pause ends a held input
and a GUI click and reports why, and holds a use in progress (eating) as it
holds a job (below); a pause the agent asked for leaves them waiting instead.

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
in the same call (a rule's) stands, and a call whose resume lifted nothing says
`resumeUnused`. A request the game thread refuses resumes nothing.

A navigation job still running when its step ends, or when a rule pauses the
world, is suspended, not cancelled: the request is answered with state
`suspended` and a `suspendedJobId` (and `pausedBy`, the rule's reason), and
the job keeps its controls, break progress and path, and runs on whenever the
world runs. `nav.resume {jobId: suspendedJobId}` waits on it again (or returns
its outcome if it finished meanwhile). Any new action ends it. A rule asks for
a decision; it does not throw away the work in hand: an eat it met at tick 29
of 32 is three ticks from done when the world resumes. A single use in
progress (`act.eat`, `act.use_item`) is held and resumed the same way. Scripts
(`mb_run`) and background tasks still stop at a rule's pause whatever they
catch: a result that carries `pausedBy` ends the script, and its report names
the held job (`harness/smoke/guard_hold.py` is the test in the game).

Danger pauses the world through the rules (below), at a tick boundary.
`time.configure` takes one setting, `pauseOnDisconnect` (on by default): pause
when the client or the agent's session goes.

A pause ends the server's tick before the client has been sent that tick's
damage, and a paused client reads no game packets. The clock's state therefore
carries the health and food its rules judged (`vitals`), and the client puts
them in place when a pause arrives, as the health packet would have:
`obs.player` at a rule's pause shows the hit that caused it.

A snapshot read while paused is responsive, not a transactionally frozen view
of the client. The gate is a bounded development control: it does not claim
that every thread, timer or external service in the pack has stopped.
Integrated-server play and multi-client coordination are outside the contract.

The paused maintenance path follows the pause-when-empty behaviour of the
ServerUtilities build shipped with GTNH (chunk I/O completion, networking,
pending server commands). ModdedBench adds explicit pause ownership, client
coordination, deferred gameplay packets and the barriers below.

## Rules

A rule is the model's own standing order: what to watch for (`when`) and what
to do when it crosses (`do`). One tool sets, lists and removes them:
`mb_reflex` (`harness/tools/reflexes.py`). The mods know no mob or item names.

**When.** One of the kinds the server checks every tick (`SimulationClock`,
`time.rules`):

| Kind | Crosses when |
| --- | --- |
| `health_drop` | the player loses health |
| `health_below`, `air_below`, `food_below` (level) | the value falls to the level |
| `burning` | the player is on fire |
| `hostile_within` (level: blocks, at most 32) | a mob takes the player as its target within the level (twice that in line of sight), or a creeper starts to swell; once per mob |
| `action_failed` | a caller sends `time.report_failure`; Baritone's terminal failures do |

Or a check of the model's own on any read: a `condition` over watchable reads,
or a Python `file` whose `evaluate(context)` decides
(`harness/tools/interrupts.py`). Its supervisor runs in the MCP server, polls
every 0.1 s and journals to SQLite under `MODBENCH_INTERRUPTS_DIR` (default
`.state/interrupts`), so checks outlive a restart too.

A new world starts with `health_drop`, `health_below 8`, `air_below 180`,
`food_below 6`, `burning` and `hostile_within 12`, each named for its kind and
each `ask` (`SimulationClock.defaults()`); the model edits or removes any of
them. A kind may have several rules at different levels (at most 64 rules).

**Do.**

- `ask` freezes the world on the crossing tick for the model. The pause reason
  is the kind, or `kind:name` when the name differs; a check's is
  `interrupt:<reason>` (its name unless it gives a `reason`).
- `tell` leaves the world running. The crossing is handed to the model once,
  on its next tool result (`reflexes`), and wakes `mb_wait`.
- A Python script (`def main(event)`) answers the freeze in the model's place
  (below).

A level rule crosses once, as the value reaches it, and again only after the
value has recovered. A rule given again unchanged keeps that; a changed one
starts afresh. Rules crossing on one tick make one freeze: the first in the
table names it and the others are in its event's `with`. A rule's reason stays
the pause reason until the resume, whatever pauses after it; one that crosses
on a step's last tick ends the step and is its `endedBy`. A value that crosses
under a pause no rule made is reported once the world runs.

Harm that lasts is one crossing. A kind of hurt that nothing dealt (by damage
type: a burn, a poison, whatever the pack has), once it has crossed
`health_drop`, crosses it again only after it has stopped for 100 ticks; a hit
by anything, or a new kind of hurt, crosses at once. While a fight job runs,
`health_drop`, `burning`, and `hostile_within` for mobs within its range (4
blocks, 8 standing) do not cross, by kind whatever the rule is named: the
fight's own limits count the health it costs, and a player still alight when
it ends crosses then. Every other rule still crosses.

**Checks.** A check also takes the supervisor's options: `effects` (`notify`,
`cancel`, `pause`), `latch`, `oneShot`, `edge`, `consecutive`, `cooldown`
(seconds), `timeout_s`, `operationScope`, `reason`, `prompt`. `ask` and a script
mean `pause` is among the effects; `tell` means it is not. `cancel` stops the
running job. A latched crossing refuses the next acting call
(`interrupt_latched`, which delivers it) or waits for `interrupt.ack`; a check
whose script answers it is acknowledged first. A check set through `mb_reflex`
is not one-shot and crosses on its edge, like the kinds, unless it says
otherwise. Setting it again re-reads its file and watches again. A check that
faults, stalls or meets another world stops and is reported once.
`mb_wait(after, timeout_s)` blocks until
the journal has an event for the model, a rule tells, a script reports, or a
freeze no script answers comes, and returns the journal's events and its
`cursor`. A background task cannot set or wait on checks.

**Scripts.**

- **When it runs.** When the freeze in force is its rule's. The call that met
  the freeze answers it, from inside `Kernel.call` (`Kernel.around_action`);
  with no call in flight a watcher thread in the MCP server does, within
  about a tenth of a second, so a script also runs while the model is
  thinking. A freeze is answered once, and one made of several rules is left
  for the model.
- **What it can do.** What the model's own scripts can: every `mb_*` tool,
  `mb_reflex` included. It calls no model. Its first action steps the world for
  at most `max_ticks` (default 200); `resume=` and `time.resume` /
  `time.step` are refused inside it.
- **Afterwards.** `then="resume"` (the default) carries on. The held job is
  resumed (`nav.resume`); if the script's own actions ended it, a journaled job
  (mine, build) goes on from its journal and any other call is sent again. The
  tool call that met the pause returns as if nothing had paused.
- **Fallback.** The world is left frozen for the model, as with `ask`, when the
  script raises, another pause begins inside it, its ticks run out, its `times`
  or `every_ticks` limit applies, the call was cut short instead of held, or
  the pause ended a background task. A pause a script leaves reads
  `reflex:<name>` unless a rule's reason is already in force.
- **Record.** Every firing is handed to the model once, as `reflexes` on its
  next tool result, written to the call log (`caller: reflex`) and shown on the
  stream overlay when that result arrives. Firing counts and undelivered reports
  are kept in `MODBENCH_REFLEXES_FILE` (default `.state/reflexes.json`).

A background task's rule changes are put back when it ends (`rulesRestored`,
`rulesChanged`). A script does not run inside a background task's process: the
pause ends the task, then the watcher answers it. A script that loops without
calling a tool cannot be stopped. A call that was cut short is not sent again.

Tests: `SimulationClockTest`, `harness/tests/test_reflexes.py` and
`test_gtnh_interrupts.py` against fakes, and in the game the `reflex_*` scenes
of `harness/smoke/guard_hold.py` (not yet run with rules).

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
transition and the clock waits for those requests to settle. A machine whose
pause throws is logged and fails that pause request (`computer_pause_failed`,
the world stays gated); the other machines are still paused, and the failure
does not outlast the request: resume and the next pause work. No live
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
