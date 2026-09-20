# First standalone bridge milestone

Validated locally on Windows on 2026-09-12 using the two GTNH 2.8.4 Java 17â€“25
archives pinned in `pack.lock.json`, Prism 11.1.0 and Java 25.0.1. The client
loaded the full pack with 311 mods; the dedicated server used its matching
server archive. These are development checks, not a progression benchmark run.

## Build and offline checks

- Gradle 9.2.0 / RetroFuturaGradle 2.0.2: independent client and server artifacts
  build and reobfuscate successfully. Modbench classes target Java 17 bytecode.
- Eight Java contract tests pass: deadlines, cancellation ownership,
  disconnect cleanup, stop ordering, world changes, one terminal response, and
  strict numeric validation.
- 74 Python tests pass, including existing DJ2 tests, GTNH profile isolation,
  reload failures, same-size rapid source edits, runtime archive handling,
  installation/rollback, process checks, and launcher readiness.
- GTNH MCP profile loads nine tools in addition to its shared built-ins.

## Full pack checks

`tools/gtnh/smoke.py --mcp-reload-proof` passes all 19 checks. Both endpoints
authenticate, advertise their actual methods, and report the same connected
player entity. Observations, aiming, finite sneak input, concurrent stop,
released controls, PNG capture and Python reload all pass. A deliberately invalid
Python edit produces the expected syntax-error log and preserves the previous
working tool.

A separate live test exercised the actual MCP stdio connection:

- Five ticks of forward input moved the player 1.072750931 blocks on inspected
  ground. The dedicated server confirmed the resulting feet position
  `[-161.5, 90.0, 356.5727509310332]`.
- Open inventory, pick up the quest book through its real container slot,
  replace it, and close using the Escape GUI event all succeeded.
- The MCP screenshot tool returned a valid image content block.

The complete `stop-client â†’ build â†’ install-client â†’ launch-client` cycle also
passed. The dedicated server's PID and process creation identity stayed the same,
its world time advanced, and the reconnecting player retained the authoritative
UUID, position and inventory. The new connection received a different entity ID,
as expected. `restart-before.json` and `restart-after.json` record the comparison.

Raw JSON, screenshots and logs are kept locally under ignored
`gtnh/.runtime/evidence/`. `first-smoke.json` and `mcp-live.json` record these
checks. The server's authoritative development-player UUID is
`3ba1bc1f-38db-3d85-86ff-07184d30b7a9`; the client-profile UUID is separately
labeled and is not used as the persistence identity.

## Runtime details verified by the live run

The runtime preserves Prism's pack patches and libraries, imports the actual
`.minecraft` directory, uses Qt-compatible Java paths, provisions missing
libraries before offline play, and disables DreamCoreMod's native exit dialog
for unattended shutdown. Connections initialize through Forge after the title
screen is ready. The bridge uses Minecraft's actual Netty 4.0.10 runtime API.
Client positions normalize the 1.7.10 stance offset to feet coordinates.

## Scope remaining

This establishes the standalone primitives, client/server boundary, Python
reload and external Java lifecycle. Baritone, combat routines, complete raw
input emulation, NEI/BQ adapters, synchronized tick control, agent-facing
lifecycle integration and Docker packaging remain to be implemented. Linux
runtime validation and broad gameplay/cancellation stress tests remain open.

# Initial navigation port milestone

The following work extends the first bridge milestone above. The client now
loads separate control and Baritone jars. Jar inspection confirms that the
Baritone artifact contains no bridge classes and the bridge contains no
Baritone classes; the control API is provided by the control jar. All Modbench
classes still target Java 17 bytecode.

## Offline verification

- 26 Java tests pass: 14 for the retained search core and terrain rules, four for
  input leases, and the original eight bridge tests. Search checks include a
  cheaper route reopening an already-expanded node, unknown start/destination
  rejection, partial paths, node budgets and cancellation. Terrain checks cover
  obstacle detours, ascent, descent, headroom, hazards and maximum fall distance.
- 75 Python tests pass, including per-component install/rollback handling.
- All four Forge artifacts build and reobfuscate successfully. The Baritone jar
  includes its LGPL license and source provenance.

## Live verification

- A six-block navigation route and return passed on the dedicated GTNH server.
  The nine-check `navigation_smoke.py` probe confirms arrival on the server,
  emergency stop, direct-input takeover, GUI interruption, connection loss and
  return navigation.
- The player climbed from `[-162,90,356]` to `[-164,92,356]` using two ASCEND
  movements, descended both, then completed another one-block descent/ascent.
  Every destination was confirmed by the server and health remained 20.
- A forced one-tick navigation deadline failed cleanly and released controls.
- The local `/baritone goto -162 90 360` command was entered through normal chat
  GUI events and completed successfully. It dispatches through the client
  command handler; no bridge request owns that navigation job.
- All 20 bridge/primitive/reload checks pass with shared controls. The final
  navigation probe also passes a route combining WALK and ASCEND, then returns
  with WALK and DESCEND, including all nine cancellation/arrival checks.

Local evidence: `navigation-smoke.json`, `navigation-vertical.json`,
`navigation-command.json`, and `control-smoke.json` under
`gtnh/.runtime/evidence/`.

This remains a limited navigation slice. Swimming/modded-fluid movement,
ladders, partial-height surfaces, mining, building, parkour, inventory/tool
costs, full upstream path execution and broader recovery tests remain open.

## Fluid movement and obsidian mining â€” 2026-09-12

Full GTNH 2.8.4 client/dedicated-server validation of the fluid-aware navigation
and bounded mining extension:

- **41 fluid checks passed** (`tools/gtnh/fluid_smoke.py`). A contained development
  fixture was created in a previously empty, loaded sky volume and restored in
  the probe's `finally` block. Player inventory, health, food and location were
  journaled before setup; the fixture and its dropped items were removed afterward.
- Crossed a three-block-deep water basin, exited onto a bank, and recovered from
  a submerged start. A separate low-air case waited underwater until air was at
  most 75, then verified the executor prioritized surfacing before continuing.
- Traversed flowing water downstream and upstream and exited to dry ground.
  Water arrivals braked before releasing input. Arrival is an event, not a promise
  to hold position after the request completes; server checks sample it promptly.
- Observed actual flowing `lifeessence`, `fluidmilk` and `fluidmushroom` cells from
  Blood Magic and Automagy, including Forge flow vectors. All remained excluded
  from traversal. Source/drainability and water/lava classification checks passed.
- Cancelled swimming while the player was in water; controls released. Cancelled
  mining, verified the target remained, and successfully retried. Mining the
  supporting block and navigating to a lava goal were refused.
- Mined an obsidian cap from a raised dry bank using a normal Tinkers' cobalt
  pickaxe built through the installed pack's `ToolBuilder`. The server confirmed
  flowing water occupied the mined cell and the exposed lava below became
  obsidian. This single-block operation took 152 client ticks in the final
  regression run.
- Health remained **20/20** in the movement and mining cases. No deaths occurred.
- Existing **20 bridge/reload checks** and **9 navigation/cancellation checks**
  passed. A separate live ascent/descent round trip also passed at full health.
- Build and automated tests: **75 Python tests, 33 Java tests** (21 pathfinding,
  4 input ownership, 8 bridge). Client and server shut down gracefully afterward.

Ignored evidence is in `gtnh/.runtime/evidence/`: `fluid-smoke.json`,
`fluid-smoke-output.log`, `fluid-bridge-smoke.log`, `fluid-land-smoke.log`,
`fluid-vertical.json`, and the build/test logs.

The fixture is a regression environment, not a GTNH progression run. It supplies
the tool and test terrain only when launched with `--dev-fixtures`. Vanilla tools
are disabled by this pack, and a fresh steel Tinkers' pickaxe was below obsidian's
actual harvest requirement; those tools were correctly refused during development.
The test does not establish general underwater mining, automatic tool selection,
drop collection, mining while standing on the cap, or safe swimming through
arbitrary modded fluids. These remain explicit limitations.

## Collision geometry and ladder milestone â€” 2026-09-12

The navigation adapter now captures actual Forge collision boxes, derives exact
standing heights, sweeps intermediate stair risers using the player's step
height, and executes attached ladder routes using ordinary movement input.

- **97 full-pack geometry checks passed** (`tools/gtnh/geometry_smoke.py`) on the
  GTNH client connected to its dedicated server. Each completed route was checked
  against the server's position; health stayed **20/20**, with no deaths.
- Lower and upper slabs, ascending/descending stairs, layered snow, carpet, and
  the actual pack block `Botania:biomeStoneA0Slab` passed. Geometry inspection
  reported fractional standing heights, including 176.5 and 179.5. Minecraft
  1.7.10 carpet correctly reported zero collision height despite its render bounds.
- Routes entered water from a modded slab, crossed a pool, exited onto a bank,
  and returned to the fractional surface. Starting directly on a lower slab also
  worked. A slab ceiling that would clip the player's head was rejected.
- Six-rung ladders passed ascent, platform exit, descent and ground exit in all
  four attachment directions. Each ordinary ladder round trip used 111 ticks up
  and 103 ticks down in the final run, including capture and planning.
- Cancellation during an actual climb released the control lease. Removing a
  rung before planning caused a detour to the opposite ladder. Removing it during
  ascent caused one replan and a successful detour, without damage.
- Live testing exposed ground inertia carrying a player past a snow waypoint
  onto an adjacent upper slab. Ground steering now counters drift and slows on
  the arrival surface before advancing. The complete fixture passed with that fix.
- **44 Java tests pass**: 32 search/terrain tests, four input-ownership tests and
  eight bridge tests. The 11 added geometry tests include stair orientation,
  ceilings, partial support, immutable snapshots, step capability, continuous and
  broken ladder routes, and movement-cost consistency. **75 Python tests pass.**
- Client, server, control and Baritone artifacts build and reobfuscate. The
  geometry fixture uses the existing recovery journal and restored the player's
  inventory, health, food and location and removed its blocks afterward.

Local evidence is under ignored `gtnh/.runtime/evidence/`: `geometry-smoke.json`,
`geometry-smoke-output.log`, `geometry-build.log`, and `geometry-python-tests.log`.

This establishes the tested surfaces and attached ladder shapes, not every GTNH
movement mechanic. Vines, arbitrary custom climbing geometry, parkour, route
excavation, placing, tool selection and longer survival tasks remain open.
Mining still deliberately requires stable full-block footing. The inspected host
has no Docker executable on PATH and no registered WSL distribution, so Linux
container launch has not been validated.

## Long-distance continuation â€” 2026-09-12

Long-route validation exposed and removed the original 128-block goal limit and
16-continuation cutoff. Goals now permit up to 4096 horizontal blocks, with
normal partial-route completion counted separately from recovery attempts.
Repeated endpoint detection and tick/wall-clock deadlines still bound failure.
The maximum navigation budget is 72,000 ticks; bridge deadlines accept up to one
hour. Callers must raise both budgets explicitly for a sustained trip.

The streaming test also exposed a 1.7.10 client API trap: `chunkExists` always
returns true, so `World.blockExists` alone treats absent chunks as observed air.
Observations, terrain snapshots, fluid checks and mining now reject the client's
`EmptyChunk` placeholder. The long-route probe asserts that its distant goal is
initially unavailable through block, terrain and fluid inspection.

Straight, level waypoints now retain forward motion. Turns, height changes and
destinations keep the ground braking verified by the geometry fixture. Automated
checks pass with **48 Java tests** (35 search/terrain, four input-ownership and nine
bridge tests) and **75 Python tests**. New tests cover continuation beyond 16
segments, endpoint cycles, distant-coordinate bounds and cancellable long deadlines.

**20 full-pack long-route checks passed** (`tools/gtnh/long_route_smoke.py`):

- A 416-block outbound route descended 48 blocks and reached the server-confirmed
  goal in **157.48 seconds**, using 20 searches and 19 partial-route continuations.
- The 416-block return climbed the same 48 blocks and reached the starting block
  in **160.30 seconds**, using 19 searches and 18 continuations. Both directions
  exceeded the former 16-continuation limit and crossed at least 25 chunks.
- A separate action was cancelled after 100 blocks; its keys released immediately.
  All movement samples retained **20/20 health**, with no deaths.
- The fixture restored player state and removed its course. Its setup explicitly
  loaded/generated server chunks to build a lit sky corridor; client chunk delivery
  remained normal. Generated world chunks remain after fixture cleanup.

This validates sustained travel, chunk streaming and vertical continuation on a
controlled route. Natural cave exploration, large detours outside a snapshot,
structure discovery and route excavation still need their own acceptance cases.
Evidence: `long-route-smoke.json`, `long-route-smoke-output.log`,
`long-route-build.log`, `long-route-tests.log`, and `long-route-python-tests.log`
under ignored `gtnh/.runtime/evidence/`.

Final regression on the long-route build also passed **97 geometry checks**,
**41 fluid/obsidian checks**, **20 bridge/reload checks** and **nine navigation/
cancellation checks**. Obsidian mining completed in 147 ticks at full health;
the server confirmed its removal and water cooling the exposed lava. All fixtures
were restored, the player returned to the original area at full health, and the
managed client and server shut down gracefully. Additional evidence:
`geometry-fluid-smoke.log`, `geometry-bridge-smoke.log`,
`geometry-navigation-smoke.log`, and `geometry-final-player.json`.

## Survival tools, flat excavation and placement

The work adapter adds automatic tool selection, opt-in `allowBreak`/`allowPlace`
navigation and standalone `baritone.place_block`. It uses the existing shared
control lease and ordinary survival input/container transactions. The planner
adds estimated break time and placement cost; no movement is permitted through
an edited cell until the live collision checks pass.

**54 Java tests pass** (40 search/terrain, four control ownership, ten bridge),
alongside **75 Python tests**. New search cases cover opt-in excavation, headroom
ordering, preferring a cheap detour, fluid/unknown exclusions, bridging materials
and refusal to replace liquid. JSON tests require real booleans for editing flags.

**71 full-pack work checks passed** (`tools/gtnh/work_smoke.py`):

- Tool inspection preserved inventory state, selected the shovel for dirt and
  the main-inventory cobalt pickaxe for obsidian, and excluded a broken pickaxe.
  Mining dirt, stone and obsidian took 28, 35 and 154 client ticks respectively;
  the server confirmed each removal. The displaced item survived the swap.
- A seven-block tunnel route removed six dirt/stone/log obstacles, clearing their
  head blocks before their feet blocks and reaching the server-confirmed goal
  in 253 client ticks. Default navigation left those obstacles intact.
- A route crossed three consecutive missing floor blocks in 171 ticks, placed
  exactly three cobblestone blocks, consumed exactly three inventory items and
  reached the server-confirmed goal. A separate `place_block` action also passed.
  Without building material, the route failed and left the gap untouched.
- Cancellation during the owned inventory screen and during route excavation
  released controls. The inventory cancellation closed the screen with no cursor
  item. Placement cancelled while edging toward a gap also released controls and
  left the player grounded at the original height, without damage.
  A lava plug was refused because it would expose a hazard at foot level;
  the gravity-plug case was refused because its starting pocket lacked a dry
  escape step. These refusals left the plugs intact.
- Every sampled work action retained 20/20 health. The fixture restored the
  original player inventory/location and removed the course afterward.

Live testing found two relevant 1.7.10/pack integration requirements: inventory
clicks needed an initialized inventory screen, and focus reacquisition needed a
tick with attack released before mining could resume. The owned inventory session
allows initialization and stack verification while preserving route ownership;
foreign GUI changes still cancel. Fixture loadout resets/restoration now send the
normal held-slot synchronization packet, preventing client/server tool disagreement.

The fluid, geometry and work courses now use illuminating full-cube floor blocks
to reduce interference from nighttime mob spawning without changing collision
shapes. An initial geometry regression aborted on damage at night, and a repeat
began at reduced health after an idle interval in the natural world. The damage
source was not identified; those runs do not establish full-health acceptance.
Before the final repeat, the managed player's recorded starting position/health
were restored with a backup of its previous player-data file. The final regression
runner disconnects immediately after fixture restoration to avoid another idle
interval in the natural world.

Scope remains flat excavation and common full-cube placement, not excavation
staircases or the upstream schematic builder. Automatic tool selection supports
verified vanilla behavior and Tinkers pickaxes/shovels/hatchets; GregTech tool
callbacks require their own adapters. Resource-constrained alternative-route search,
natural cave navigation and structure discovery remain unverified. Evidence:
`work-smoke.json`, `work-smoke.log`, `work-build.log`, `work-server-build.log`,
and `work-python-tests.log` under ignored `gtnh/.runtime/evidence/`.

The final build also passed **20 long-route checks**: 416 blocks outbound with a
48-block descent in **157.49 seconds**, and 416 blocks back with the return climb
in **160.31 seconds**, plus cancellation after 100 blocks. Normal client chunk
streaming and partial-route continuation remained intact. Final regressions passed
**41 fluid/obsidian**, **97 geometry/ladder**, **19 bridge/reload** and **nine
navigation/control-handoff** checks. The work, fluid, geometry and long-route runs
all began at 20 health and retained 20 in every sampled movement/action. The
bridge and handoff checks ran in the illuminated work fixture with its tunnel
opened, without changing any production navigation settings.

All fixture journals were cleared; the authoritative server confirmed the player
back at `(-161.5, 90, 356.5)` with 20 health and 20 food. The client disconnected
immediately after restoration and both managed processes stopped gracefully.
Additional evidence: `work-long-route.log`, `work-fluid.log`, `work-geometry.log`,
`work-bridge.log`, `work-navigation.log` and `work-final-player.json`.

## Time control and native GT progression â€” 2026-09-12

The accepted time-control contract is a dedicated-server whole-tick pause/resume
gate, with real time as the default. It has no public step or client/server
lockstep mode. Network keepalives, bridge requests, reconnect negotiation, chunk
synchronization, screenshots, and observations remain live while simulation is
paused; gameplay mutations wait for resume.

`time-smoke.json` records a successful **35-check** live run. It covered frozen
dimension time and fixture furnace/fluid/pig state, client physics/ticks,
screenshot and GUI observation, deferred/cancelled actions, pause conditions,
keepalives, controlling-agent disconnect, planned disconnect, reconnect, chunk
delivery, full client JVM restart while paused, and resume. Its final clock was paused at 5,391 simulation ticks and
reported 50 completed admitted GregTech structural jobs. Fixture restoration
completed.

`progression-smoke.json` records a successful native GregTech comparison. A
continuous **800-tick** workload and four pause-separated **200-tick** windows
reached the same final native chest inventory, machine EU state, and tank/pipe
fluid state. The controlled fixture exercised a diesel generator, cable,
macerator, extractor, item export, and fluid export; it also checked that
production and admitted structural workers remained stable while paused.

The run does not validate AE2, GT multiblocks, arbitrary recipes, all asynchronous
pack work, a live OpenComputers workload, or arbitrary restart/recovery scenarios. Full client JVM restart while paused,
reconnect and subsequent resume passed.
OpenComputers has a cooperative `Machine.pause(0)` lifecycle integration, but no
machine fixture was registered in this evidence run.

An initial post-install crash was a stale control jar: it lacked
`ClientControls.focusForInput` and threw `NoSuchMethodError`. Matching-artifact
installation fixed it, and runtime preflight now verifies the installed jar hash
before launch. Evidence is under ignored `gtnh/.runtime/evidence/`.

## Native NEI and recipe choice — 2026-09-12

The NEI smoke suite passed **81 checks** across **19 native recipe/usage views**
while server dimension times remained unchanged. It used the running catalogue
of **56,053 entries** and inventoried **335 native categories**. Coverage includes
native query syntax and paging, exact metadata and bee-genome NBT, ore alternatives,
fluids, GT recipe quantities/power/heat/circuits/chances, and exact category/index
selection. The default category overview selected no recipe; compact options and
explicit full-detail retrieval preserved the selected native variant.

Native views exercised crafting and smelting, GT maceration/blast furnace/fluid
extraction, Tinkers casting, Forestry centrifuging, bee breeding trees and late-page
mutations, Mutatron, Thaumcraft crucible/arcane/infusion, mob information, material
and ore-processing diagrams, ore veins and small-ore distributions. Representative
screenshots were visually reviewed, including mutation probabilities, infusion
layout, ore heights/dimensions and blast-furnace heat requirements. The native
50% output tooltip was asserted through the handler callback. Diagram scrolling
was dispatched through native NEI and its resulting image was visually checked.

The GUI checks caught and fixed missing paused-GUI presentation updates and
native paging for diagrams without ingredient-derived recipe IDs. GUI pointer
handling now accounts for lwjgl3ify's GLFW origin, DPI conversion and polling;
this enabled exact native hover and scroll dispatch in the running pack.

This does not mean every recipe in all 335 categories is semantically validated.
Arbitrary drawn requirements remain available through the native view; modifier
behavior and third-party widgets are not exhaustive. The API reports its data
coverage instead of treating absent structured fields as absent requirements.
See [NEI coverage and model workflow](NEI_COVERAGE.md).

The final Gradle build passed **63 Java tests**, and the Python suite passed
**83 tests**, including MCP image/text preservation and stale/rollback deployment
checks. The reloadable GTNH profile loads **18 tools**. NEI evidence is saved under
ignored `gtnh/.runtime/evidence/nei/`.


## Route memory and protection — 2026-09-12

The complete memory batch passed **76 live checks**, including named route travel
in both directions, recorded-route replay, protected excavation, deliberate work
without an override in default regions, strict mining/placement overrides,
cancellation on policy change, and normal container access with a held item.
A full client JVM restart preserved records, scope and enforcement. A separate
server restart also preserved the saved world UUID. Fixtures and test records
were restored. Evidence: `memory-smoke.json` and the restart log.

A focused native-input batch passed **9 checks**: right-click denial and its
same-tick item-use fallback left server terrain/inventory unchanged; an explicit
raw-input override placed and consumed exactly one block; the next raw attack
remained protected. Evidence: `protection-input-smoke.json`.

Offline validation passed **76 Java tests** and **83 Python tests**; the MCP
profile loads **20 tools**. A saved-route variant of the 416-block streaming
fixture is prepared, but its long run was deferred when inventory/UI became the
next priority. The earlier coordinate-based long-distance evidence still applies;
this batch does not establish arbitrary saved-route cave traversal.

## General inventory/UI and durable notes — 2026-09-12

The generalized inventory/UI batch passed **68 live checks** across chests,
vanilla and Tinkers crafting, furnaces, anvils, GregTech ModularUI, Forestry and
the AE2 cell workbench. Checks include native server acknowledgements, metadata
and NBT identity, exact/partial transfers, consuming destinations, cursor
recovery, stale screen/stack refusal, modifiers, dragging, native text listeners,
ghost slots, cancellation and actual ingredient/output conservation.

The powered ME crafting terminal passed **18 live checks** covering native
search, large stored quantities, virtual slots, NBT-specific selection, deposits,
stack/partial-stack withdrawals, sort controls, crafting and shift crafting with
server-side resource accounting. This establishes useful primitives for a later
model-written adapter. Autocrafting request/confirmation/status workflows and
full AE2 configuration are deferred. Evidence: `gui-smoke.json` and
`ae-terminal-smoke.json` in the ignored runtime directory.

Durable world notes passed **26 live checks**, using the shipped MCP functions
with a real block and pig fixture, including server UUID resolution for both
mobs and players. An earlier **25-check batch** bracketed a full client restart:
all four attachment types survived and the entity UUID still resolved. Removing
the fixture entity retained its note as unobserved. The evidence notebooks are
separate from the normal persistent notebook. Evidence:
`evidence/notes/result.json` and `result-jvm-restart.json`.

The final offline suites pass **81 Java tests** and **101 Python tests**. Notes
tests cover competing/stale edits, transaction rollback, abrupt process death,
new-process reopen, retry receipts, revision history, archival/restoration,
cross-world rejection, spatial retrieval, consistent pagination and SQLite backup.
The GTNH MCP profile loads **26 tools**. See [inventory contracts](INVENTORY_UI.md)
and [world-note contracts](WORLD_NOTES.md) for bounds and limitations.

The development player died while the server was left running between earlier
UI checks. Its inventory was recovered from the OpenBlocks snapshot, translated
through the saved Forge registry, and verified in the native client (all eight
stacks and the named NBT variant). Recovery backups remain under
`.runtime/backups/ui-idle-death-20260912`. Subsequent launch checks pause the server
before connecting; fixture creation refuses a dead player. This was development
fixture recovery, not autonomous survival-run recovery.

## Frozen pause display — 2026-09-12

The client presentation batch passed **11 live checks**. Repeated paused
screenshots are pixel-identical, including after changing the underlying camera.
Changing the pause reason changes only the banner area. Explicit NEI inspection
refreshes the cached image once and remains static afterward; resuming removes
the frozen display and returns to live rendering.

The framebuffer capture/presentation hook runs after native achievement/debug
overlays and before framebuffer presentation, separately from the complete-tick
simulation gate. The banner remains readable above native overlays. Visual
inspection confirmed the resulting frame. Evidence and screenshots are under
`evidence/paused-frame/`; rerun with `tools/gtnh/paused_frame_smoke.py`.

## Native interactions, combat and generic interrupts — 2026-09-12

The final interaction/interrupt batch passed **36 live checks**. It exercises
native block-face use and sneak placement, exact held/entity guards, server-
verified water/lava collection and placement, food consumption, entity use,
held-use cancellation, bounded hostile combat with server-observed damage,
passive exclusion and obstruction. Interrupt checks cover autonomous observation
polling, notify without pausing, urgent action cancellation and coordinated
pause, reason propagation, admission latches, acknowledgement without resuming,
idempotent reactions, stale bridge rejection, custom Python read composition,
non-consuming journal replay and the inference-race adapter. The inference test
uses a pending awaitable; it is not integration with the Codex host's inference.

A separate **19-check live batch** passed with NEI-discovered non-vanilla fluid
containers: IC2 Universal Fluid Cell mutates NBT, IC2 Empty Cell produces a new
metadata variant in another inventory slot, and the clay bucket replaces its
item identity. All three removed the source on the authoritative server. Strict
protection blocked them; default automation protection permitted deliberate use
without an override. An additional **9-check raw-input protection regression**
passed, including fallback suppression, server placement and non-inherited overrides.

The development fixture now synchronizes its selected hotbar slot with the
native server packet. Earlier unsynchronized fixture runs produced misleading
client-only container changes; those were not accepted as server success.
The fixture also uses common-side food NBT access and explicitly synchronizes
restored health/food while paused. All final batches restored the journalled
player/world and left simulation paused.

Offline verification: **83 Java tests**, **113 Python tests**, and the GTNH MCP
profile loads **28 tools**. Java tests cover watchable-read isolation and an urgent
queue cancellation barrier while simulation is stopped. Python tests cover
condition composition, temporal/debounce behavior, faults, persistence, reload
failure, stale worker generations, bounded concurrent reactions and inference
cancellation. Build and deployed client/control/server jars use Java 25.

Evidence: `.runtime/evidence/interactions/interaction-smoke.json`,
`.runtime/evidence/interactions/fluid-container-smoke.json`, and
`.runtime/evidence/protection-input-smoke.json`. Rerun the corresponding scripts
under `tools/gtnh/`. Contracts/examples: [INTERACTIONS_INTERRUPTS.md](INTERACTIONS_INTERRUPTS.md).

Limits: native return values and client receipts do not acknowledge arbitrary
machine effects. Inspect inventory/world postconditions. Combat is stationary;
compose navigation separately. Custom predicates use bounded external polling,
not a tick-exact guarantee, and arbitrary Python cannot be forcibly killed in a
thread. The subsequent update adds the packaged runner and bounded mining/schematic work.
Broader natural-world coverage remains a separate milestone.


## Work processes, construction goals and runner (2026-09-12)

The build passes **99 Java tests**. A selected GTNH Python regression passes
**87 tests**, covering runner, file adapter, schematic importer, profile,
interrupts, lifecycle, UI and notes. This is a selected suite; repository-wide
DJ2 live integration was not all rerun. The GTNH MCP profile loads **45 tools**.

Native work acceptance passes **17 checks**, including exact GregTech LV
drill identity/capability, actual item collection, metadata, protection, material
deficits, cancellation, resume and native quest observation. The drill collected
one coal; a resumed two-target job gained two. Selection/schematic acceptance
passes **39 checks**: fill, exact metadata replacement, legacy schematic wool/slab
placement, layered walls, top-down clearing, independent server state checks,
material deficits, cancellation/resume and three excavated descents. Completed
resume consumes no materials. Shell coverage checks its mask, not a full large
shell. Three negative probes reject empty nested selectors and ignored state fields.

The final candidate passes **20 long-route checks** covering a 416-block streamed
route, 48-block descent, return ascent, cancellation and no damage. Movement
geometry passed **97 checks** with the agent attached and time resumed. A paused
fixture had correct server blocks but deferred client updates; its failed
precondition was investigated rather than counted as a pass.

A real JVM restart preserved a cancelled building journal; resuming its ID
completed eight cells from a fresh world diff. The runner performed two actual
managed builds/installations, client restarts and reconnections. Rollback faults
have orchestration tests, not a deliberately corrupted live deployment. The
file adapter takes decisions from the active coding agent; this does not claim
a bundled provider SDK or an autonomous provider benchmark.

[Action efficiency](ACTION_EFFICIENCY.md) compares identical work cases and keeps
client action ticks separate from wall-clock/CPU performance. Construction uses
retained Baritone GoalComposite semantics, reachable work before travel, bounded
useful-pose sets and paged recovery. Smaller captures and same-tick phase handoff
remove shared artificial delays while retaining native verification/cancellation.

Evidence: `.runtime/evidence/final-candidate-regression-2026-09-12.json`,
`final-candidate-negative-validation-2026-09-12.json`, `work-processes.json`,
`building-smoke.json`, `build-restart-resume.json`, `long-route-smoke.json`,
`geometry-smoke-held-agent-2026-09-12.json`, and `building-efficiency-comparison.json`.
Runner decisions, deployment receipts and inference records are in `.runtime/pilot/`.

Limits: tile NBT, machine configuration, arbitrary multiblock placement, full
upstream movement/building behavior and long-horizon survival remain distinct.
Placement receipts identify client verification, not server acknowledgement;
fixtures query authoritative state separately. Mining completes on net inventory
gain and allows native drop motion outside block-selection bounds.


## Bounded natural pilot and targeting corrections (2026-09-13)

The file-backed runner attempt ended on its health interrupt, with the server
paused. It used the existing development inventory and decisions supplied by the
active coding agent. This was not a fresh survival benchmark or completion of
the full survival objective.

A persisted natural mining job initially stalled on its first waypoint while
standing inside a low collision shape. After the executor was changed to begin
the first movement edge as upstream PathExecutor does, a client restart and
native journal resume collected all eight requested dirt in 101 job ticks.
Native Better Questing detection completed "Your First Night"; claiming the
reward was followed by an observed apple in inventory. NEI identified the pack's
carpet recipe, and ordinary inventory transfers plus an acknowledged output
click converted two orange wool to one orange carpet.

The 23-cell shelter then stalled aiming at a grass block underneath selectable
tall grass. The initial GUI released-input-tick change alone did not fix it.
The target ray had ignored blocks without collision boxes; it now uses the same
flags as upstream RayTraceUtils and native 1.7.10 EntityLivingBase.rayTrace.
Mining refreshes native targeting after aim and bounds target mismatches.
Placement also checks native feasibility and the player's body at candidate
positions, so building does not propose a pose inside its next solid block.

A custom Python watch combining player and clock observations interrupted
pending inference, cancelled controls and confirmed a latched pause; explicit
acknowledgement/review/resume worked. Later, the survival watch caught damage
and fire (observed health 14); a nearby Spitfire Skeleton was present. The run
ended there with the shelter unbuilt. Its checkpoint marks the attempt ended,
not the survival objective achieved. Evidence is
`.runtime/evidence/natural-pilot-2026-09-13.json` and the full decision/event
journal in `.runtime/pilot/`.


Runner recovery now distinguishes `complete:true` from a standalone `stopReason`.
Safety termination preserves pending interrupts and records objective completion
as false. Acknowledgements from a prior JVM require explicit model review and a
verified bridge identity change; current JVM latches remain pending. Per-event
acknowledgements, review and resume uncertainty are checkpointed, and method
effect metadata is refreshed on reconnect. Six additional selected Python tests
cover these restart/crash boundaries and completion semantics (87 total).


During subsequent fixture validation, inherited natural-world fire caused a
player death during the handoff. Work fixtures now extinguish only after saving
full player NBT; restoration still uses that snapshot. This setup failure is not
counted as successful gameplay or a house construction failure. Recovery exposed
an unrelated GUI ownership gap: dead-player cancellation also cancelled native
screen clicks. Owned GUI input now remains usable while dead, while gameplay
input is revoked and world/player/screen changes still end ownership. No GUI or
item class is special-cased.

Native respawn then passed: `gui.button` delivered the observed Respawn button's
mouse events; a new native player entity appeared and both client and server
reported health 20 at the spawn. A transient `world_changed` read during
respawn was re-observed, not treated as permission to click again. Evidence:
`.runtime/evidence/native-respawn.json`. The natural development player's death
and respawn are recorded separately from the restored construction fixtures.

## Mixed-material house acceptance (2026-09-13)

The final construction batch passes **75 checks**, including a native **96-block
house**: 25 stone-brick foundation blocks, 12 vertical logs, 31 planks, three
glass windows and 25 lower stone slabs. The finite materials are supplied by the
journalled fixture; every house block is placed through normal client input.
All 96 registry IDs and metadata match independent server observations. Material
consumption matches the plan exactly. Navigation enters the raised doorway and
leaves again with breaking/placement disabled, preserving the doorway and
interior. The batch takes no damage and restores the fixture. This is controlled
construction acceptance, not a natural-world resource-gathering run.

The test also retains the 23-cell shelter, current-feet-cell construction,
selected grass-support clearing, preservation of unrelated tall grass,
immediate GUI-to-building handoff, metadata replacement, legacy schematic
placement, cancellation/resume and excavation cases. The final native work
batch passes **19 checks** and restores its fixture.
The movement/geometry batch passes **99 checks**: the original 97 assertions
plus explicit resumed-time and client/server fixture synchronization checks.
It covers partial surfaces, ladders, cancellation and rerouting after a rung
changes. Its initial paused/deferred-packet setup failure was preserved and
excluded; the successful batch restores the fixture and leaves time paused.

Failures led to three shared corrections: building now uses navigation's
fractional collision footing, retains upstream BuilderProcess's placement-height
rule so it climbs before wall access disappears, and follows MovementAscend's
source/destination clearance requirements at a raised two-high doorway. Both
the search and executor use the corrected jump check. No house material is
special-cased. Empty hotbar slots retain previously selected materials.

The house completes in **1,478 client job ticks**. The five unchanged comparison
cases take 461 ticks versus 1,307 before the efficiency work (64.7% fewer), for
the same 13 placements and seven removals. These figures do not measure wall
time or path-search CPU time. The house's 32-entry history is truncated and is
excluded from travel-action totals. See [ACTION_EFFICIENCY.md](ACTION_EFFICIENCY.md).

Offline verification passes **100 Java tests** and **94 selected Python tests**.
The additional Java regression accepts a raised two-high doorway and rejects
an actual obstruction. Schematic tests check native placement/selector/coordinate
limits, CLI failure behavior, compact NBT arrays, shared allocation bounds and
the explicit unsupported tile-state contract.

Evidence: `.runtime/evidence/building-smoke.json`, `natural-house.png`,
`work-processes.json`, `building-efficiency-comparison.json` and the retained
diagnostic receipts `house-built-access-failure.json`,
`house-exterior-diagnostic.json`, and `house-doorway-fixed.json`.
Final geometry evidence is `geometry-smoke.json`; the setup failure is retained
in `geometry-client-sync-failure.json`. Installed client, control, server and
Baritone jars match the built artifacts by SHA-256 (`final-installed-jars.json`).


## Construction features and native machine pipeline (2026-09-13)

The construction process is now separate from the strict DJ2 blueprint contract.
It implements the retained builder's layers, repeats, completion predicates,
substitutions, construction route costs and native support/pillar work, alongside
multiple selections and MCEdit/Sponge/Litematica import. Modern state properties
require explicit native palettes. See [CONSTRUCTION_PARITY.md](CONSTRUCTION_PARITY.md)
for the exact surface and version-specific differences.

The machine schematic acceptance script, `tools/gtnh/machine_schematic_smoke.py`,
passes **47 checks** in GTNH 2.8.4 on the dedicated server:

- NEI discovers the Basic Fluid Extractor, Tiny Copper Fluid Pipe, Super Tank I,
  a native NBT-bearing wrench, and the snowball-to-water fluid-extractor recipe.
  `nei.item.placement` reports the native item-to-block metadata conversion.
- An actual imported JSON schematic builds the extractor, three pipes and tank
  through native placement. The five blocks complete in **150 client job ticks**,
  with five successful placements, no removals and exact material consumption.
  Both machines share block ID and block metadata; their distinct variants are
  verified using client native pick-block identity and independent server tile IDs.
  A preview intentionally expecting the other machine variant reports a mismatch.
- A live selection copy retains a separate native `placementItem` and
  `verify.pickedItem`. Default copying reports unsupported tile configuration and
  refuses paste. Explicit state-only copy then rebuilds all five native variants
  from newly supplied items in a separate row; it does not clone machine contents.
- Normal wrench face/hit/sneak interactions configure the machine and all three
  pipes. The test finds the fluid-output toggle in the native ModularUI widget
  tree, clicks it, discovers an accepting recipe slot with a live stack probe,
  and inserts four snowballs through ordinary GUI transfer transactions.
- A deliberately wrong tank facing blocks receipt. The machine still completes
  its recipes and fluid backs up in its output and all three pipes. Turning the
  tank with the wrench repairs the setup. Exactly **1,000 mB water** reaches the
  tank; the source inventory, machine output tank and three pipes end empty.
  The machine consumes **512 EU** from the finite 2,048 EU fixture input.
- Configuration leaves the schematic identity checks satisfied. Navigation uses
  breaking/placement disabled, the player takes no damage, controls release,
  and the journalled fixture restores before the server is left paused.

Fixture provisioning supplies the arena, finite player loadout and EU. It does
not set machine faces, connections, output modes, recipes or tank contents.
The initial 47-check run used the development inspector's native tile NBT,
inventory and fluid interfaces for configuration decisions as well as assertions.
It therefore did not establish a complete workflow using normal agent-facing
observations. The authoritative observation port and rerun below supersede that
limitation; the original evidence is retained as
`machine-schematic-pre-observation-port.json`.
Configuration remains a higher-level composition of the generic primitives,
not item-specific logic embedded in the builder.

This test found two shared interaction issues: GT's native mouse-picking context
was missing around direct ray casts, and sneak/held-item changes could invalidate
an aim calculated before the actual stance. Native targeting now uses GT's own
context; placement establishes sneak before choosing geometry; direct use
re-aims and verifies the actual visible hit point before delivery. Physical
collision/pathing queries keep their ordinary collision geometry.

The first server inspector incorrectly called a client-only pick-block fallback;
it now uses native serialized tile identity. A conservation assertion also
needed settled observations: five sequential reads can straddle a live fluid
transfer and count the same last few mB twice. The final check waits for the
empty-source/full-destination state instead of treating those reads as atomic.

Evidence: `.runtime/evidence/machine-schematic-smoke.json`, the imported
`machine-pipeline.json`, and `machine-pipeline.png`. Diagnostic receipts are
preserved separately as `machine-schematic-server-probe-failure.json`,
`machine-schematic-sneak-aim-failure.json`,
`machine-schematic-tank-facing-discovery.json`, and
`machine-schematic-sampling-timing.json`.

Offline checks pass **113 Java tests** and **169 Python tests plus 30 subtests**.
The native cases establish this machine pipeline and the listed construction
contracts; arbitrary GT machine callbacks, tile-NBT restoration and multiblock
formation still need normal-interaction adapters and their own evidence.

Final regression batches pass **81 construction checks**, **75 house/building
checks**, **34 interaction checks** and **18 fluid-container checks**. The
construction batch additionally stages a 5,120-cell all-air plan, refuses to
start unless its preview already matches, executes it with replacement/access
edits disabled, and verifies a compact durable work-status response with an
exact cell count instead of returning all 5,120 cells. Every fixture restores
and the handoff leaves the server paused.

Installed client, control, server and Baritone jars match the final build by SHA-256
(`.runtime/evidence/construction-installed-jars.json`).

## Dedicated-server tile/NBT/Waila port — 2026-09-13

The normal client-facing `obs.tile`, `obs.nbt`, `obs.waila`/`obs.hwyla` operations
now read authoritative server state through the existing maintenance channel.
They work while paused, with no simulation stepping, chunk loading or fixture
inspector dependency. See [the observation contract](TILE_OBSERVATIONS.md).

**61 multi-mod observation checks passed:** chest, furnace, GregTech macerator,
AE2 cell workbench, Forestry worktable and Tinkers crafting station. Each returned
native NBT and provenance, appropriate supported/unsupported inventory and fluid
interfaces, and native Waila output without provider errors. The batch also
distinguished loaded air and non-tile blocks, tested NBT path/paging and immutable
snapshots, rejected invalid radius/dimension/handle requests, and preserved
successful mixed client/server reads alongside a per-alias error while time
remained frozen. AE2's cell workbench does not implement `IInventory`; that is
reported honestly, with its native NBT and the existing GUI primitives available.
This is not a claim that all virtual AE2 network storage has become an ordinary
inventory array.

**61 machine-pipeline checks passed using normal agent observations for every
decision and postcondition.** The workflow builds and copies five native
machine/pipe/tank blocks, reads their configurations, wrenches pipe connections
and faces, enables the native GUI's fluid-output toggle, and inserts recipe
inputs. It diagnoses a deliberately blocked tank face using `obs.tile`, repairs
it through the native wrench, and receives exactly **1,000 mB water**. The source
and pipes end empty; native energy observation reports **1,536 EU remaining**
from the finite 2,048 EU fixture supply. An independent fixture inspector is used
only for final identity and fluid-conservation assertions.

The pipeline also verifies an old NBT handle retains `mMainFacing=4` after a new
read reports the wrench change to `2`. A model-editable Python interrupt combines
a live tank read with a further `context.read('obs.nbt', ...)`, then notifies,
cancels controls and confirms a coordinated pause at 1,000 mB. Repeated reads
during that pause preserve the same server tick and fluid total. All live tank
reads in each conservation sample share one server tick.

Reviewing native Waila output caught a real protocol omission: missing
`WailaX/Y/Z` caused Waila to silently reject the server provider tag and render
default DOWN facings from incomplete client NBT. The adapter now follows native
`Message0x01TERequest`'s coordinate/identity envelope. A regression assertion
requires the displayed `Machine Facing: NORTH` and `Output Facing: EAST` to
agree with server configuration, with client/server provider names present and
no provider errors. The diagnostic run is retained separately.

**10 additional native energy checks passed:** an IC2 BatBox reports the native
40,000 EU capacity and 32 EU output; an Ender IO Basic Capacitor Bank reports
1,000,000 RF capacity through its native sided receiver interface. Both also
return native NBT and Waila without errors. These fixtures validate empty-state
storage/capacity getters; actual energy consumption is validated by the GT
pipeline, not by claiming IC2/RF charging coverage.

Offline validation: **126 Java tests passed**, including immutable/scoped/bounded
native NBT snapshots and ordered, bounded Unicode network framing; **169 Python
tests plus 30 subtests passed**. The GTNH MCP profile loads its 48 tools.

Evidence in `.runtime/evidence/`:

- `tile-observation-smoke.json` (61 checks).
- `tile-energy-smoke.json` (10 checks).
- `machine-schematic-smoke.json` (61 checks and custom interrupt receipt).
- `machine-schematic-pre-observation-port.json` (historical fixture-dependent run).
- `machine-schematic-waila-envelope-diagnostic.json` (pre-fix Waila diagnosis).
- `tile-observation-final-state.json` and `tile-observation-installed-jars.json`.

The fixture is restored, player position is `[-158.5, 87, 364.5]` with health 20,
controls are released and time is paused. Installed jars match built artifacts.
Mod-specific meaning still belongs in editable adapters: unsaved transient fields
and interfaces not implemented by a mod are not fabricated as zero or empty.
