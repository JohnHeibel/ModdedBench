# Time-control audit — 2026-09-12

## Accepted contract

Time control is an opt-in dedicated-server **whole simulation-tick gate**. Real
time is the default. `time.pause` stops complete server ticks; `time.resume`
returns to real time. There is no public stepping or client/server lockstep
contract. The gate covers the normal work reached from `MinecraftServer.tick`:
Forge tick events, loaded dimensions, entities, fluids, and tile entities.

The client gates its simulation ticks while the server is paused. Networking,
bridge servicing, keepalives, reconnect negotiation and chunk synchronization
stay live. Gameplay requests are deferred while paused. Observations and
screenshots can therefore remain responsive, but an incoming snapshot is not a
claim of a transactionally frozen client view.

This is a bounded development control, not a claim that every pack thread,
timer, or external service has stopped. Integrated-server operation and
multi-client coordination are outside the contract.

The paused maintenance path follows the installed GTNH ServerUtilities 2.2.2
pause-when-empty implementation: chunk I/O completion, native networking and
pending dedicated-server commands. Modbench adds explicit pause ownership,
client coordination, deferred gameplay packets and the background-work barriers
below. Deferred gameplay returns at the native network stage of a resumed tick,
after world updates, preserving per-connection FIFO order.

## Background-work boundaries

GregTech's whole background structural jobs are admitted through Modbench's
`AsyncPause` barrier. A pause does not settle until active admitted jobs finish;
new jobs wait until resume. The server exposes this state in clock status.

OpenComputers is handled cooperatively: registered native machines receive
`Machine.pause(0)` at a pause transition, and the clock waits for those requests
to settle. This prevents queued machine execution after the native pause takes
effect while retaining the machine's normal tick-side resume behavior. No live
OpenComputers fixture or CPU-state equivalence run has been accepted.

Other asynchronous mods have not been audited exhaustively. Background work is
not treated as proof of a world mutation unless its gameplay handoff is shown.

## Accepted live evidence

`gtnh/.runtime/evidence/time-smoke.json` records a successful 35-check live
acceptance run. It verified frozen all-dimension time, fixture furnace/fluid/pig
observations, client physics/ticks, responsive screenshot and GUI observation,
deferred actions, pause conditions, keepalives, disconnect/reconnect, chunk
delivery, full client JVM restart while paused, and resume. The final clock recorded
5,391 simulation ticks and 50 completed admitted GregTech jobs; no OpenComputers machines were registered.

`gtnh/.runtime/evidence/progression-smoke.json` records a successful native GT
progression comparison: one uninterrupted 800-tick workload and four interrupted
200-tick windows ended with the same native chest inventory, machine EU state,
and tank/pipe fluid state. The fixture uses a diesel generator, cable, macerator,
extractor, item export, and fluid export. It is evidence for that controlled
path, not for AE2, multiblocks, arbitrary recipes, or all pack automation.

## Restart/install note

An earlier crash was a stale control jar whose missing
`ClientControls.focusForInput` caused `NoSuchMethodError`. Reinstalling matching
artifacts fixed the run. Runtime preflight now checks the installed jar hash
before launch to reject stale installations earlier.

## Remaining scope

The suite does not establish full-pack timer quiescence, AE2 behavior, GT
multiblocks, a live OpenComputers workload, or arbitrary restart/recovery scenarios.
The full client JVM restart while paused, reconnect and subsequent resume passed.
Run evidence should state that it used this pause/resume contract and the
controlled fixture rather than generalizing to the pack.
