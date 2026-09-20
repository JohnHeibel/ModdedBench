# Modbench for GT New Horizons

Architecture and current ownership: [native mod, Python, and model layers](LAYERS.md).
Includes conditional self-prompts and the current survival/production/terrain boundaries.

Latest agent trial: [supplied-resource Electric Blast Furnace line](EBF_AGENT_TRIAL_2026-09-18.md)
produced and automatically exported two aluminium ingots; recovery limitations are recorded.
The [primitive follow-up](PRIMITIVE_REGRESSIONS.md) fixes grass clearing, bounds
attack holds, and improves native food handling; 13 live regression checks pass.

Current checkpoint: [native runtime/API acceptance](NATIVE_RUNTIME_ACCEPTANCE.md).
The current build passes 189 tests and verifies 162 pinned imports. Native event/API
acceptance passes 16 checks. Source Explore has observed and persisted a new
natural frontier; its survival return failed after a hostile attack. The
[single-job scaffold clearance](FOLLOWUP_CLEARANCE.md) follow-up supersedes
the older cleanup failures below. Detailed historical results are not a claim
of complete modern Baritone binary, CLI or modded-fluid parity.

GTNH 2.8.4 / Minecraft 1.7.10 development lives here. The repository's original
root build remains the working DJ2 / Minecraft 1.12.2 reference. The starting
DJ2 source and validation evidence are checkpointed at `f7659dd7`.

The current model-facing foundation includes [general inventory/UI primitives](INVENTORY_UI.md),
[authoritative machine/NBT/Waila observations](TILE_OBSERVATIONS.md),
and [durable world notes](WORLD_NOTES.md). Higher-level machine routines remain
editable Python procedures built on these primitives.

Navigation, mining, builder mode, inventory scheduling, process arbitration,
follow/explore/farm/backfill and the persistent cache use the pinned source port.
Strict explicit-cell blueprint scheduling remains a Modbench process whose travel
uses the same source navigation engine. The source event bus, rendering, free-look
and Java process API now operate through native 1.7/Forge adapters and shared
control ownership. Eleven Python profile tests pass and 52 model tools load.

Use the current checkpoint above for validation and remaining limits. The
[implementation record](baritone/UPSTREAM_PORT.md) explains version boundaries;
the [original audit](BARITONE_PARITY_AUDIT.md) preserves the pre-port comparison
against upstream and DJ2. Earlier fixture results below remain historical evidence.

## Connect Codex to this runtime

Register the stdio server with absolute paths to your Python executable and this
checkout's `tools/mcp/server.py`:

```powershell
codex mcp add modbench-gtnh --env MB_BRIDGE_URL=ws://127.0.0.1:47223/ws -- <python.exe> -u <absolute-path-to-server.py> --profile gtnh
```

In the resulting `[mcp_servers.modbench-gtnh]` table in Codex's `config.toml`,
set `startup_timeout_sec = 30` and `tool_timeout_sec = 1200` to accommodate
bounded long-distance jobs. Python packages must be visible in the launch
environment; Windows user-site installs may require an explicit `APPDATA` in
the server's `env` table. The bridge token is discovered locally by the Kernel;
do not paste it into prompts. Restart the Codex client to load the native named
tools. See [official MCP setup](https://learn.chatgpt.com/docs/extend/mcp?surface=cli).

An already-running coding task with a persistent JavaScript execution tool can
use `tools/gtnh/codex_mcp.mjs` immediately. Import its `connectModbench` function
by absolute file URL, then keep the returned connection alive:

```javascript
const modbench = await connectModbench({python: "<python executable>", userSite: "<optional observed Python user-site path>"});
const catalogue = await modbench.listTools();
const observation = await modbench.call("mb_obs", {method: "player"});
// Inspect MCP content/isError; arguments come from catalogue inputSchema.
```

This uses the actual MCP server and its tool loader, including the persistent
interrupt supervisor. It does not inject new top-level tool names into a running
conversation. For long actions, raise both the tool's `timeout_s` argument and
the helper's third argument (milliseconds). `modbench.close()` closes the MCP
session; normal pause-on-disconnect behavior applies when that session owns time.

## Build

Install a JDK 25 and set `JAVA_HOME` to it. RetroFuturaGradle 2.0.2 itself requires
Java 25. Gradle provisions the older JDKs it needs for Minecraft decompilation;
the Modbench classes target Java 17 bytecode. The wrapper pins Gradle 9.2.0 and
its distribution SHA-256.

From the repository root:

```powershell
.\gtnh\gradlew.bat -p gtnh build
python tools/mcp/server.py --profile gtnh --check
python -m unittest discover -s tools/tests -p "test_*.py"
```

On Linux/macOS use `./gtnh/gradlew -p gtnh build`.

Install these reobfuscated artifacts (not the `-dev` or `-sources` jars):

- `control/build/libs/modbench-control-0.1.0.jar` in the client mods directory.
- `baritone/build/libs/modbench-baritone-0.1.0.jar` in the client mods directory for navigation.
- `client/build/libs/modbench-client-0.1.0.jar` in the client instance's mods directory.
- `server/build/libs/modbench-server-0.1.0.jar` in the dedicated server's mods directory.

The control mod owns shared input leases. The client bridge and Baritone depend
on that mod and are separate jars; Baritone has no MCP or server-bridge dependency.
The client and server bridge jars each include the small `bridge-core` library.
The server mod is not required in the client instance. Baritone is optional for
the client bridge and can also be used through local `/baritone` commands.

## Long-lived route memory and base protection

Use `mb_memory` to save waypoints, ordered corridor routes and protected regions,
and `mb_route` to travel them in either direction. Default protection prevents
incidental navigation/bulk edits while ordinary machine use and deliberate
single-block work stay normal. Strict `all_edits` regions are optional, with
explicit overrides scoped to one operation. See [world memory](WORLD_MEMORY.md)
for recording, persistence, examples and limitations.

## Managed local development runtime

The runtime tool imports the official client archive into a marked Prism instance
named `Modbench-GTNH-Dev` and extracts the matching server to ignored
`gtnh/.runtime/server`. It preserves the supplied pack patches and libraries.
Use the two GTNH 2.8.4 Java 17ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€¦Ã¢â‚¬Å“25 archives. Paths and process records are saved
only in the ignored runtime directory. Close Prism before `prepare`; an already
running launcher caches the instance list and can overwrite external settings.

```powershell
python tools/gtnh/runtime.py prepare --client-zip <client.zip> --server-zip <server.zip> --prism <prism-executable> --prism-data <prism-data-directory> --java <jdk25-java-executable>
python tools/gtnh/runtime.py install-server
python tools/gtnh/runtime.py install-control
python tools/gtnh/runtime.py install-baritone
python tools/gtnh/runtime.py install-client
python tools/gtnh/runtime.py start-server --accept-eula
python tools/gtnh/runtime.py provision-client
# Wait for the main menu, then close this first client:
python tools/gtnh/runtime.py stop-client
python tools/gtnh/runtime.py launch-client
python tools/gtnh/runtime.py status
```

`provision-client` uses Prism's existing signed-in account to download missing
vanilla/Forge/LWJGL libraries and assets. Use it once for a fresh Prism cache;
offline launches cannot download them. Complete any account sign-in in Prism
yourself. Subsequent `launch-client` runs use the fixed offline name `ModbenchDev`
and connect through Forge after the main menu is ready. The command waits for
the player to join (up to 300 seconds, adjustable with `--timeout`). Its server-side offline UUID is
`3ba1bc1f-38db-3d85-86ff-07184d30b7a9`.

That UUID belongs to the server's saved player. Minecraft 1.7.10 may keep a
different client-profile UUID during offline play. Observations label each UUID's
scope; correlate connected players by name, dimension and entity ID, and use the
server UUID for persistence. Both bridges report positions at the player's feet.

Managed setup allocates 6 GiB to the client and 4 GiB to the server. It disables
pause on focus loss and DreamCoreMod's native exit confirmation for unattended
development restarts. Full pack startup still takes a few minutes.
The managed launcher workflow has been validated on Windows; Linux/container
startup remains a later milestone.

`--accept-eula` explicitly accepts Minecraft's server EULA. This development
server is bound to `127.0.0.1:25575`, with offline authentication and no RCON.
It is a disposable local validation environment. The release/benchmark deployment
and its authentication policy are not implemented yet.

The client bridge listens on `ws://127.0.0.1:47223/ws`; the dedicated-server bridge
listens on port 47224. Both require a generated token written to
`~/.moddedbench/bridge-PORT.token`, which the existing Python Kernel reads for
loopback connections. `-Dmodbench.port=...` and `-Dmodbench.tokenFile=...` override
these defaults. Tokens and model/account credentials must remain outside source
control.

```powershell
python tools/mcp/server.py --profile gtnh
```

Set `MB_BRIDGE_URL` to use a different endpoint. The GTNH MCP profile loads only
its own tools from `tools/mcp/profiles/gtnh/`. Modules, including agent-created
modules in its `mods/` and `composed/` subdirectories, reload on subsequent calls.
A failed import leaves the previous working tools registered. `mb_methods` and
`mb_status` describe the actual connected bridge.

With the client joined to the server and its GUI closed, run:

```powershell
python tools/gtnh/smoke.py --mcp-reload-proof
```

The probe checks both bridges, player identity, observations, aiming, finite
input, cancellation and screenshots. It reports incomplete checks as failures
and saves screenshots under ignored `gtnh/.runtime/evidence/`. Reload validation
includes replacing a tool and retaining the last good tool after a syntax error.

Java updates use the external lifecycle:

```powershell
python tools/gtnh/runtime.py stop-client
python tools/gtnh/runtime.py build
python tools/gtnh/runtime.py install-control
python tools/gtnh/runtime.py install-baritone
python tools/gtnh/runtime.py install-client
python tools/gtnh/runtime.py launch-client
# If a replacement fails:
python tools/gtnh/runtime.py rollback-client
python tools/gtnh/runtime.py launch-client
```

Jar installation retains one previous build. Stop a running client before
installation or rollback. The dedicated server and its saved player state survive
client restarts. Use `stop-server` to shut down the managed server gracefully.
`rollback-control` and `rollback-baritone` restore those individual components.
Keep the control API and its consumers on compatible builds when rolling back.
After a client has joined successfully, `launch-client` also accepts that complete
previous managed client/control (and optional Baritone) jar set; it rejects mixed,
partial, or unrecognized sets.

## Current contract

- Shared core: authenticated WebSocket transport, request IDs, deadlines,
  bounded game-thread queues, cancellation scoped to the connection, response
  tick/sequence/side/world-epoch provenance.
- Client: basic player/world/inventory/block/container/GUI observations,
  screenshots, finite key-binding input, aiming, normal container and GUI events,
  connect/disconnect/shutdown. Inputs release on completion, cancellation,
  timeout, connection loss, or world change. `act.stop` has a separate urgent
  queue and cancels waiting interactions when processed, preserving observations.
  Completion of input means client ticks elapsed, not a server acknowledgment.
- Server: dedicated-world state, authoritative player positions/health, loaded
  block inspection, graceful shutdown. Chunk queries do not generate terrain.

Both sides default to real time and expose opt-in time control (`tickControl:true`). The client advertises
`baritone:true` when the standalone navigation provider is installed and
`inputOwnership:true`. It has no combat routine, Better Questing adapter, or
complete mod-state inspection yet. NEI search and recipe coverage are documented
in [NEI_COVERAGE.md](NEI_COVERAGE.md).
Native GUI mouse/keyboard events, drag, wheel and modifier polling, exact-count
transfers, cursor recovery, item identity selectors and transaction receipts are
implemented. [Inventory/UI contracts and composition](INVENTORY_UI.md) document
ordinary versus virtual slots, consuming machines and reloadable higher-level tools.

This milestone provides Python source reload and Java build/install/restart.
It does not unload and replace Java classes inside the running game. The future
runner must expose that external lifecycle to the agent, since the agent's MCP
connection alone cannot restart the JVM that hosts its game bridge.

## Optional simulation time control

The dedicated-server control defaults to real time and exposes `time.status`,
`time.pause`, `time.resume`, `time.configure`, and `time.report_failure`. It has
no public step command. `time.pause` is a whole simulation-tick gate; `time.resume`
returns to ordinary real-time simulation. The client and server bridges remain
available for observations, screenshots, authenticated control traffic, keepalives,
and reconnect synchronization while paused. Gameplay mutations are deferred.

The client presents a cached frame with **Paused: reason** across the top while
the server is paused. This avoids interpolation jitter without changing the
simulation gate. The image includes native HUD/achievement overlays. Deliberate
GUI changes and NEI inspection refresh the image once; ordinary paused frames
stay static. Resuming returns immediately to live rendering. `time.status`
includes client `presentation` diagnostics for this display.

The server does not report a pause settled until its client state and Modbench's
admission barrier for native GregTech background jobs have settled. OpenComputers uses a
cooperative native `Machine.pause(0)` transition when machines are present. This
is a bounded pause/resume contract, not an all-pack execution freezer. See
[TIME_CONTROL_AUDIT.md](TIME_CONTROL_AUDIT.md) for scope and evidence.

Run the reversible acceptance scripts against the managed development fixture:

```powershell
python tools/gtnh/time_smoke.py --restart-client
python tools/gtnh/progression_smoke.py --restart-client
```

## Initial Baritone navigation port

The Baritone search loop, heap, path nodes, goal heuristics and movement-cost
constants are retained in a game-independent module. [Port provenance](baritone-core/PORT_ORIGIN.md)
records the source and changes. Forge collision geometry is captured incrementally
on the game thread; A* runs against that immutable snapshot on a worker thread.
The executor rechecks the next route segment against the live world.

This is an incremental port of Baritone. It supports cardinal walking, one-block
ascents and descents of at most three blocks, partial collision surfaces,
attached ladders, surface swimming in vanilla water, water entry/exit and upward
submerged recovery, automatic tool selection, opt-in flat route excavation and
native block placement. The construction process adds costed access excavation,
scaffolding/pillaring, layered/repeated schematics and native state verification;
see [construction contracts](CONSTRUCTION_PARITY.md). Parkour remains unported. Unknown chunks
are blocked. Snapshots extend 24 blocks horizontally from the player; partial
paths trigger bounded recapture/replanning. Goals are limited to 4096 horizontal
blocks from the starting player.

Use `mb_call("nav.goto", {"x": X, "y": Y, "z": Z})` to navigate to a feet
block. The request remains active until arrival, cancellation or failure.
`baritone.status` reports state, path, exact `pathFeetY` heights, movement kinds,
cost and replan count.
`segmentsCompleted` counts successful partial-route continuations separately from
`recoveryReplans`. Unloaded client chunks are rejected as unknown, including
1.7.10's `EmptyChunk` placeholders; a goal may be beyond the currently loaded area.
The client must receive each next chunk normally before Baritone can traverse it.
Repeated segment endpoints and action deadlines bound attempts that make no progress.

Long routes need both a sufficient game-tick budget and caller deadline. For example:

```python
mb_call("nav.goto", {"x": X, "y": Y, "z": Z, "timeoutTicks": 16000}, timeout_s=900)
```

Navigation accepts up to 72,000 ticks and the bridge accepts deadlines up to one
hour; existing defaults remain short. Straight, level waypoints permit continuous
walking, while turns, height changes and destinations retain arrival braking.
`act.stop`, direct input, GUI opening and connection loss release navigation's
shared input lease. New input owners invalidate old leases immediately.

In-game commands are `/baritone goto X Y Z`, `/baritone stop`, and
`/baritone status`. These are client commands and do not require server operator
permissions. Opening a GUI while navigating interrupts the route.

### Collision surfaces and ladders

`baritone.terrain {x,y,z}` reports copied Forge collision boxes, the center
footprint's `standingY`, traversability and ladder attachment. A goal's integer Y
is the block containing the player's feet: the top of a lower slab at Y=176 is
176.5, so its goal Y is 176. Stairs may require the cell above the stair block.
Missing `standingY` means there is no valid dry standing surface at that cell.

The planner sweeps the player's footprint through intermediate stair risers,
uses the player's step-height capability, and checks actual head clearance.
It uses collision geometry rather than render height or block names; for example,
1.7.10 carpet has zero collision height. Tall/overhanging obstacles, unsupported
shapes and unknown cells remain excluded as footing. Ground arrivals brake with
ordinary movement input before releasing the shared control lease.

Ladders use Forge's `isLadder` hook and a recognized thin attachment shape.
Continuous rungs with a consistent facing support upward and downward routes,
including ground entry and platform exit. Climbing presses toward the attached
face; descending uses normal ladder gravity. Vines, unsupported ladder geometry,
and arbitrary mod-specific climbing mechanics remain unverified. Cancellation
releases all keys; an idle player on a ladder can then slide down. Prefer a dry
platform destination when the harness needs to pause.

### Fluids and bounded mining

`baritone.fluid {x,y,z}` inspects one loaded cell without draining it. It reports
the block and fluid IDs, metadata, source/drainability, signed fill fraction,
temperature, density/viscosity when available, flow contribution and traversal
policy. Vanilla source status comes from metadata, not the still/flowing block ID.
For arbitrary Forge fluids, `source` is unknown; `canDrain` comes from `IFluidBlock`
and does not promise a vanilla bucket-sized source. Negative fill means top-down
filling. A null flow means unknown, not still.

Only vanilla water is verified for traversal. Lava, hot fluids and unverified
modded fluids are excluded. Room temperature and `Material.water` do not prove a
fluid harmless. These exclusions apply to path planning; normal key-level bucket
and block interactions remain available. New fluid adapters must establish actual
movement/collision effects before granting traversal permission.

Swimming costs account for upstream and cross-current travel. The executor holds
buoyancy during snapshot/search, counters observed drift, checks live terrain and
the projected body volume, and prioritizes surfacing when air is low. Water routes
require breathing headroom and a buffer from hazardous or unknown neighboring
cells. Unsupported deep waterfalls and deliberate underwater routes are excluded.
Water goals brake before completion. Arrival releases input ownership, so currents
and gravity continue afterward; `arrival` and `obs.player.velocity` expose this.
Prefer dry destinations when the harness needs to pause.

`baritone.mine_block {x,y,z,timeoutTicks,autoTool:true}` mines a single reachable block
using ordinary client attack input. It requires harvest capability,
stable full-block footing and an adjacent dry escape step. It refuses to mine the
player's supporting footprint or expose nearby hazards at foot level, and stops
on drift, insufficient air, damage, an obstruction or tool breakage. This supports
mining an obsidian cap over lava from a raised dry bank while water flows above
the cap. It does not plan an entire obsidian-mining operation, collect drops,
guarantee protection against arbitrary mod effects. Completion
reports a client-observed target change; authoritative block checks use the server
bridge. Input cancellation and takeover use the same shared leases as navigation.

### Tools, excavation and placement

`baritone.tools {x,y,z}` estimates break times using the installed pack's harvest
and break-speed hooks, including item metadata and NBT. It does not change the
inventory. Automatic mining selects an eligible tool from the 36 main slots;
`autoTool:false` retains the caller's selected tool. Broken tools and a one-use
durability reserve are excluded. Vanilla single-block behavior and the Tinkers
pickaxe, shovel and hatchet are supported; tools with unverified custom break
callbacks (including GregTech tool classes) are excluded from automatic selection.
Explicit manual tool use remains available. Estimates are rechecked at execution.

Main-inventory selection resolves the actual player-container slots and uses normal
clicks in a brief, owned inventory screen operation, then verifies the complete
selected stack before attacking. A foreign screen still interrupts the action.
If a transaction leaves a cursor stack, the inventory stays open for recovery.

`baritone.goto` accepts `allowBreak:true` and `allowPlace:true`; both default to
false. Flat excavation weighs tool break time against walking and clears headroom
before the feet cell. It excludes tile entities, falling blocks, and blocks beside
unknown terrain, water or hazards. The executor rechecks each block and the live
corridor. This does not yet support digging staircases or arbitrary mod mechanics.

`baritone.place_block {x,y,z,timeoutTicks}` places one inventory-funded cube through
normal right-click input. Placement uses untagged metadata-zero cobblestone, stone,
dirt or netherrack. Adjacent floor bridging sneaks onto a supported edge to see an
attachment face; it does not write player position or velocity. Liquids are never
replaced. The current route segment must fit the available material count before
work starts; the planner does not yet search again for a smaller material budget.

`baritone.status` reports `blocksMined`, `blocksPlaced`, `completedWork` and an active
child `work` operation. Work borrows the route's input lease, so cancellation stops
both the work and movement. Placement success means the expected block remained
visible in the client world; use the server bridge for authoritative confirmation.

Run `python tools/gtnh/work_smoke.py` with the development fixture server to test
tools, a blocked tunnel, a three-block gap, cancellation and hazard refusals. Like
the fluid course, it journals and restores the original terrain and player state.

The full-pack regression uses an explicit development-only fixture:

```powershell
python tools/gtnh/runtime.py start-server --dev-fixtures
python tools/gtnh/runtime.py launch-client
python tools/gtnh/reference_fluid_smoke.py
python tools/gtnh/geometry_smoke.py
python tools/gtnh/long_route_smoke.py
```

The source-engine fluid acceptance is `reference_fluid_smoke.py`. It records the
known flowing-water route failure and inherited water-capped-obsidian mining
limit as failures; they are not passing fluid coverage. `fluid_smoke.py` remains
an historical custom-executor probe. The fixture requires a loaded, empty volume
above the development player. It journals player state before building contained
test basins and providing a normal Tinkers' cobalt pickaxe. The probe restores that state and removes the basins in
`finally`. The geometry probe uses the same journal for a separate course of
slabs, stairs, snow, carpet, four ladder orientations and a water transition;
it also removes a rung during a climb to test replanning. Run these probes
sequentially. The long-route fixture builds a lit sky corridor with 416 horizontal
blocks of travel and a 48-block descent. Its setup explicitly loads/generates the
necessary server chunks; the client streams them as the player advances. Cleanup
removes the fixture and restores player state, but generated world chunks remain.
This checks movement and continuation, not structure discovery or cave exploration.
After an interrupted debug run, call `dev.fluid_fixture.restore` on the
server bridge before normal play. Its recovery journal survives server restarts.
The fixture methods are absent unless the server starts with `--dev-fixtures`;
normal benchmark runs must omit that flag.

For live acceptance on inspected terrain:

```powershell
python tools/gtnh/smoke.py --expect-baritone --mcp-reload-proof
python tools/gtnh/navigation_smoke.py --goal X Y Z
```

## Next milestones

The active development order is:

1. Entity observations and bounded combat with shared control ownership.
2. Targeted interactions, inventory/GUI coverage and conditional interruption.
3. Broader mining: target discovery, quantity goals, excavation and drop collection.
4. A bounded natural-world survival run using the model's own routines.

The [development plan and Baritone parity matrix](ROADMAP.md) tracks construction
acceptance, remaining exploration/world memory, wider mod coverage, agent-operated
Java deployment and Docker packaging. The survival
milestone does not complete the Baritone port. See [validation](VALIDATION.md)
for implemented behavior and acceptance evidence.

The agent may develop and replace its harness. Its source edits, builds,
restarts and interventions should be recorded alongside progression. The world
rules and success criteria belong to the benchmark environment.
