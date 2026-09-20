# Architecture

Modbench lets a language model play GT New Horizons (Minecraft 1.7.10) through
an MCP server. Three layers, two seams, and the rule that decides where code
goes: **Java touches Minecraft; Python composes; the model decides.**

```
 model (any MCP client)
   │  MCP tools mb_*                       harness/tools/*.py   hot-reloaded on every change
   ▼
 Python MCP server                         harness/mcp/         restart to change
   │  JSON-RPC over a loopback websocket   token in ~/.moddedbench/bridge-<port>.token
   ▼
 Forge mods                                mods/                rebuild + restart to change
   core ── client ── baritone   (client JVM, port 47223)
   core ── server               (dedicated server JVM, port 47224)
```

## Layer ownership

| Responsibility | Owner | Surface |
| --- | --- | --- |
| Class transformers, input arbitration and leases, GUI input hooks, websocket transport, simulation clock hooks | `mods/core` (the only coremod) | `dev.modbench.api.*` interfaces, `dev.modbench.control.*`, `dev.modbench.bridge.*` |
| Game state, actions, GUI and inventory operations, NEI and Better Questing adapters, world memory | `mods/client` | `obs.*`, `act.*`, `gui.*`, `inv.*`, `keys.*`, `nei.*`, `quest.*`, `memory.*`, `interrupt.*`, `sys.*` |
| Authoritative tile, NBT, fluid, energy and Waila reads; whole-tick pause and guards; world identity | `mods/server` | `obs.tile`, `obs.nbt`, `obs.waila`, `time.*` |
| Pathfinding, mining, construction, following, scanning, durable work journals | `mods/baritone` | `baritone.*` (registered through the `Navigation` interface in the API) |
| Tool registration, hot reload, request lanes, cancellation | `harness/mcp` | `server.py`, `kernel.py`, `mbtool.py` |
| Compositions over RPCs, observation predicates that wake the model, world notes | `harness/tools` | `mb_*` tools |
| Goals, recipes, machine-specific procedures, recovery decisions, harness improvements | the model | `PROMPT.md` |

Python must not duplicate path search, physics, recipe semantics, inventory
acknowledgement or block placement. Java must not contain model-specific
procedures. When a tool is missing, first look for a raw method (`mb_methods`)
and compose it in Python; add Java only when the game's internals are needed.

## Java modules

| Module | Jar | Depends on | Contents |
| --- | --- | --- | --- |
| `api` | bundled into core | nothing | Interfaces and value types: `InputArbiter` leases, `Navigation` and `NavigationRegistry`, `GameEvents`, `Controls`, `Targeting`, `PlacementInfo`, `MemoryAccess`, `ControlRegistry`, `UiInput`, `ClickReceipt`, `WorldMemory`. No Minecraft mutation. |
| `core` | `modbench-core` | api | `ClockPlugin` and every class transformer; hook classes that fan out through the api listener types; `UiHooks`; the control implementations; the JSON-RPC transport (`BridgeTransport`, `BridgeRuntime`, `Session`, `Request`); the simulation clock and pause barriers. |
| `client` | `modbench-client` | api, core | `ClientRuntime` registers the RPC methods; observation, interaction, GUI, inventory, NEI, quest, memory and interrupt implementations. |
| `server` | `modbench-server` | api, core | `ServerRuntime`, `ServerClock`, tile and NBT observations, Waila; development fixtures live in `src/dev` and are excluded from the jar. |
| `baritone` | `modbench-baritone` | api | `src/upstream/java`: pinned upstream Baritone files (LGPL-3.0, `UPSTREAM_SOURCES.json`); `src/main/java`: the 1.7.10 port, the work processes and journals. Plain `@Mod`; it subscribes to `GameEvents` and looks up controls in the `ControlRegistry` at init. |

The compiler enforces the seam: client and baritone compile against `api`
only (client also against core for the transport); nothing outside core may
reference `dev.modbench.control` or `dev.modbench.bridge` implementation
classes, and a test scans the built jars to prove it.

## Bridge protocol

JSON-RPC 2.0 over a websocket. Each request is `{id, method, params}`; replies
carry `result` or `error {code, message, data}`. Notifications from the game
(`event` frames) are bounded per session. Requests are queued, run on the
client thread in order, and answered once; a deadline produces a `timeout`
error only for a request that never started running, so an action can never
run twice because the model retried a timeout. Long jobs (mining, building,
routing) keep their request open until a terminal receipt and expose a
`jobId` that survives a client restart. Cancellation is a separate
`act.stop` / `baritone.stop`-style call, and MCP cancellation reaches the
bridge.

Every method is registered with a name, a description and an *effect*
(`read`, `act`, `control`), which `mb_methods` reports and the Python lanes
use.

## Time control

An opt-in whole-simulation-tick gate on the dedicated server. `time.pause`
stops complete server ticks; `time.resume` returns to real time; there is no
stepping. Guards (`healthDrop`, `healthBelow`, `airBelow`, `foodBelow`,
`burning`, `pauseOnDisconnect`) pause at the tick boundary and never act. While
paused, networking, keepalives, chunk delivery, observations and screenshots
stay live; gameplay packets are deferred and released in order at the network
stage of the first resumed tick. GregTech's background structure jobs pass
through a barrier so a pause settles only after admitted jobs finish;
OpenComputers machines are paused cooperatively. Details and the accepted
evidence are in `docs/legacy/TIME_CONTROL_AUDIT.md`.

## Control ownership

One `InputArbiter` in core hands out leases for keyboard, mouse and
"automated edits". Navigation, GUI takeover, interactions and the human all
go through it, so a job is preempted cleanly rather than fighting over keys.
Protected regions (world memory) are checked at the placement and breaking
cost level in Baritone and at the interaction guard in core; `automation`
mode blocks incidental edits, `all_edits` mode also blocks deliberate ones,
and every override is scoped to a single operation.

## Python layer

`harness/mcp/server.py` is a FastMCP server. It imports every `.py` under
`harness/tools/` as `mbtools_gtnh.<name>`, registers the functions decorated
with `@tool(rung, coverage, name, effect, lane)`, and on each tool call
re-imports the whole directory if any file changed. Import failures and tool
name conflicts leave the previous registrations untouched. Live state that
must outlive a reload (the kernel, the interrupt supervisor, note stores,
container sessions, the note-surfacing cache) lives in `mbtool.state`.

`kernel.py` is the transport: one websocket, concurrent requests, bounded
event buffer, cancellation scopes. `lane` on a tool chooses its thread pool:
`read` (never starved by actions), `act` (default), `control` (stop, pause,
interrupts, time).

`harness/tools/` is the model-editable surface, split by domain: `core.py`
(status, raw calls, observations, actions, keys, time, memory), `inventory.py`
(GUI and inventory operations with postcondition polling), `work.py`
(navigation, mining, construction, scanning, copy and schematic import, work
journals), `recipes_quests.py` (NEI and Better Questing), `interrupts.py`
(watches and the supervisor), `notes.py` (world notes and their surfacing).

### Interrupts

A watch is a declarative condition or a small Python file with
`evaluate(context)`. The supervisor polls the bridge's read methods, fires
`interrupt.fire` with native effects (`notify`, `cancel`, `pause`) and an
optional prompt for the model, retries with the same event id if the fire is
not confirmed, and re-arms the watch rather than dropping it. Watch specs
survive reconnects. `mb_interrupt_events` is how a host (or the autonomous
runner) learns it should give the model a new turn.

### World notes

Notes are model-authored records attached to blocks, entities (server UUID),
locations or regions, stored in SQLite under `.state/notes` per world, with
revision-guarded writes and operation ids. They are surfaced as a side effect:
tools that arrive somewhere, observe a block or entity, enter a noted region,
or start a session attach up to five relevant notes under a `notes` key, with
a per-session cache so the same note is not repeated while nothing changed.
A few automatic notes are written for important outcomes (a build completing,
a job failing at a location), tagged `auto`.

## Provenance

`mods/baritone/src/upstream/java` contains files from Baritone v1.2.19
(commit `d9cb2d91`) under LGPL-3.0-or-later, listed with their original path
and SHA-256 in `mods/baritone/UPSTREAM_SOURCES.json`; modified files carry a
notice. `mods/baritone-core/PORT_ORIGIN.md` describes the pathing package that
was written for this project. Everything else is original and released under
the same licence. See `NOTICE.md`.
