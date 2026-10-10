# Build, install, run

Everything below has been exercised on Windows 11. Linux and macOS should work
for the Gradle build and the Python server; the managed Prism runtime has only
been run on Windows.
Commands are written for a POSIX shell; on Windows use Git Bash.

## Prerequisites

| Need | Version | Why |
| --- | --- | --- |
| JDK | 25 (`JAVA_HOME` pointing at it) | RetroFuturaGradle 2.0.2 requires Java 25 to run Gradle. Gradle provisions the older JDKs it needs (21 and 8) itself; the mods are compiled to Java 17 bytecode. JDK 17 and 21 start Gradle but the build does not support them. |
| Gradle | wrapper, 9.2.0 | `gradlew` / `gradlew.bat` in the repository root; nothing to install. |
| Python | 3.11 or newer | `pip install -r harness/mcp/requirements.txt` (`mcp`, `websockets`). |
| GT New Horizons 2.8.4 | client and server archives, Java 17-25 builds | Not redistributed. Download `GT_New_Horizons_2.8.4_Java_17-25.zip` and `GT_New_Horizons_2.8.4_Server_Java_17-25.zip` from the official GTNH downloads page (https://www.gtnewhorizons.com/downloads/); about 1 GB together. `pack.lock.json` records the exact file names, sizes and SHA-256 the launcher verifies. |
| Prism Launcher | any recent | Only for the managed local client instance. Any launcher that can run the pack with extra jars in `mods/` works for manual installs. |
| Minecraft account | a Microsoft account that owns Minecraft: Java Edition | Signed in to Prism Launcher before `prepare`; Prism uses it once to download the game's assets. |

The first build needs network access to fetch Forge, the GTNH Nexus
dependencies and the Gradle plugin. Later builds run `--offline`.

## Build and test

From the repository root (`mods/baritone` is a git submodule: clone with
`--recurse-submodules`, or run `git submodule update --init` in an existing
clone):

```bash
./gradlew build
```

```bash
python harness/mcp/server.py --check
```

```bash
python -m unittest discover -s harness/tests -p "test_*.py"
```

`build` compiles every module, runs the JUnit suites and writes the jars under
`mods/<module>/build/libs/`. What the three commands check is under
[Testing](#testing). Install the reobfuscated jars, not the `-dev` or
`-sources` variants:

| Jar | Install where | Role |
| --- | --- | --- |
| `mods/core/build/libs/modbench-core-<v>.jar` | client `mods/` and server `mods/` | The only coremod: class transformers for the simulation clock and GUI input, the input arbiter and control leases, the websocket JSON-RPC transport, the API classes. Required by every other jar. |
| `mods/client/build/libs/modbench-client-<v>.jar` | client `mods/` | The observation, action, GUI, inventory, NEI and Better Questing RPC surface on port 47223. |
| `mods/server/build/libs/modbench-server-<v>.jar` | dedicated server `mods/` | Authoritative tile, NBT and Waila observations, the simulation-tick gate and its guards, on port 47224. |
| `mods/baritone/build/libs/modbench-baritone-<v>.jar` | client `mods/` | Navigation, mining, construction and work journals. A plain Forge mod that depends only on the API; optional, but the `nav.*` methods and `obs.scan`/`terrain`/`fluid`/`tools` need it. |

The server jar is not needed in a client-only install, and the client works
without a dedicated server when the game hosts its own world, but time control
and authoritative observations need the server jar on a dedicated server.

Development fixtures (privileged world-editing helpers used by the tests that
need a game) are compiled from `mods/server/src/dev` and left out of the jar. Build
with `-PdevFixtures` to include them, and start the server with
`--dev-fixtures` to register their `dev.*` methods. Never ship such a jar.

## Bridge connection

Both bridges bind to loopback and require a token that the mod generates at
startup and writes to `~/.moddedbench/bridge-<port>.token`. The Python kernel
reads that file itself; do not paste tokens anywhere. The file is readable
by its owner only, and an upgrade request that carries an `Origin` header (a
web page) is refused with 403 before the token is asked for. `-Dmodbench.port=` and
`-Dmodbench.tokenFile=` on the game's JVM override the defaults.
`MB_BRIDGE_URL` (default `ws://127.0.0.1:47223/ws`) tells the Python side where
the client bridge is.

## MCP server

`.mcp.json` in the repository root registers the server for MCP clients that
read that file (Claude Code does). For other clients, run:

```bash
python harness/mcp/server.py
```

as a stdio MCP server, with the repository root as the working directory. Give
the client a generous tool timeout (20 minutes is a good default) because
mining and building calls stay open until the job reports a terminal receipt.

Tool modules under `harness/tools/` are re-imported when any file there
changes; a module that fails to import leaves the previously registered tools
in place and reports the error through `mb_reload_tools` / `mb_status`.
Changes under `harness/mcp/` need a server restart.

## Managed local runtime (Windows)

`harness/launcher/runtime.py` imports the official client archive into a Prism
instance named `Modbench-GTNH-Dev`, extracts the server archive to the ignored
`.runtime/server`, verifies both against `pack.lock.json`, and supervises the
processes. It never reads or stores account credentials; it uses Prism's own
signed-in account once to download vanilla assets, then launches offline as
`ModbenchDev`.

Close Prism before `prepare`; a running launcher caches its instance list.

```bash
python harness/launcher/runtime.py prepare --client-zip <client.zip> --server-zip <server.zip> --prism <prismlauncher.exe> --prism-data <PrismLauncher data dir> --java <jdk25 java.exe>
```

`--prism` and `--prism-data` are normally
`%LOCALAPPDATA%\Programs\PrismLauncher\prismlauncher.exe` and
`%APPDATA%\PrismLauncher`. `prepare` needs both archives, also when the server
will run in a container, and writes its settings to `.runtime/config.json`,
which every other `runtime.py` command reads.

`--java` must be a JDK 17 to 25; `prepare` refuses anything older. Add
`--window 1920x1080` to set the client window size (Prism otherwise opens
854x480); re-run `prepare` with only that flag to change it later.

```bash
python harness/launcher/runtime.py build
```

Then install and start:

```bash
python harness/launcher/runtime.py install-core
```

`install-core` copies the coremod into both the managed client instance's
`mods/` and the managed server's `mods/`, so both must be stopped; the other
`install-*` commands touch one side each.

```bash
python harness/launcher/runtime.py install-client
```

```bash
python harness/launcher/runtime.py install-baritone
```

```bash
python harness/launcher/runtime.py install-server
```

```bash
python harness/launcher/runtime.py start-server --accept-eula
```

The first client launch must be `provision-client` (downloads libraries and
assets through the signed-in account); wait for the main menu, `stop-client`,
then use `launch-client` for every later start. `launch-client` waits for the
player to join the local server (up to 300 seconds). `status` prints what is
running; each `install-*` of a changed jar keeps the previous one (per side, under
`.runtime/backups/<client|server>/`) for `rollback-*`, and `rollback-core`
restores both sides or neither. `launch-client` refuses to start unless the
installed `client` and `core` jars match the local build or a set that joined
before.

The managed server is bound to `127.0.0.1:25575`, offline mode, no RCON, 4 GiB
heap; the client gets 6 GiB, pause-on-focus-loss disabled. Full pack startup
takes a few minutes.

## Java change cycle

```bash
python harness/launcher/runtime.py stop-client
```

```bash
python harness/launcher/runtime.py build
```

Install the jars that changed (`install-client`, `install-baritone`;
`stop-server`, then `install-server` and/or `install-core`, then
`start-server`, because the core jar also lives on the server), then:

```bash
python harness/launcher/runtime.py launch-client
```

Work journals, world memory, world notes and quest progress all survive the
restart. Only the running job's execution is lost; resume it from its job id.

## Testing

### Without a game

Run these before every commit. None of them needs Minecraft running.

| Command | What it checks |
| --- | --- |
| `./gradlew build` | The JUnit suites of every module: transport, simulation clock and pause ordering, class transformers, JSON, path search, build order, click search, work specifications, quest access. Seam tests scan the built jars: the Baritone jar refers to ModdedBench only through `dev.modbench.api`, and the server jar carries no development fixtures. |
| `python -m unittest discover -s harness/tests -p "test_*.py"` | The Python side with a fake bridge: transport, tool reload and state survival, lanes, the interrupt supervisor, world notes, scripts and background tasks, the launcher, deploy and backup, the console, the agent loop and both runtimes' streams (Claude Code's from recordings), the spectator mirror. |
| `python harness/mcp/server.py --check` | Every tool module imports and registers; prints the tool list. |
| `python harness/mcp/tool_table.py --check` | The tool table in `PROMPT.md` matches the code. Without `--check` it rewrites the table. |

**Game-thread budget.** The game has 50 ms a tick for everything.
`TickBudgetTest` (in `mods/baritone`, part of `./gradlew build`) runs the
paths that work on the game thread without a player (block scans, queued asks,
the copy of blocks around clicks, a build's reads of its plan) at the sizes of
a real base, and fails the build when one takes over 20 ms of a tick
(`TickBudget.LIMIT_MS`). Paths found slower are kept there as named findings
with their own limits. The job step and the walker need a game: every job's
result carries `cost`, and says `cost.overBudget` when a tick took over 100 ms
or the mean was over 5 ms across 40 ticks or more.

### With a game: the test stack

Most tests that need a game run against a throwaway stack named `mbtest`: its
own world and agent checkout, and a server built with the development
fixtures, which build an arena, set blocks and place the player. Stop the real
server first, since both publish port 25575. From the repository root:

```bash
OUTBOX=../.runtime/test/outbox BRIEF=../.runtime/test/brief docker compose -p mbtest -f docker/compose.yaml -f docker/compose.test.yaml --env-file docker/.env up -d --build
```

Join the host client to that server, then run a script through
`harness/smoke/mbtest.sh` (bash; Git Bash on Windows). It runs the script in a
container on the server's network, so the script reaches both bridges and no
token appears on a command line.

```bash
bash harness/smoke/mbtest.sh harness/smoke/movement_course.py --case pit_loop --trials 3
```

Each script calls the model's own tools and then reads the result back from
the server. Results print as a table; evidence files go under the ignored
`.runtime/evidence/`.

| Script | What it proves |
| --- | --- |
| `movement_course.py` | Walking, from fixtures rebuilt for every trial: corners, pits, stairs, doors and gates, parkour gaps, shafts, lava edges, swimming, currents, dives, mining beside liquids. `--list` names the cases. The obsidian cases are in `movement_obsidian.py`. |
| `build_suite.py` | Every build scenario in one run, one PASS/FAIL row each: closed shells, stages, missing materials and resume, occupied cells, timeout and resume, clearing, hidden and buried cells, flowing water, doors, building on terrain. |
| `builder_shell.py` | The closed 7x7 shells on their own (also part of the build suite). |
| `faceclick_course.py` | Click cells and uses in a build: a hopper clicked against a chosen face, facings by look, access through a block that is put back, the stop reasons when no stance exists. |
| `door_course.py` | Walking through a doorway one block up, past the open door's leaf. |
| `bridge_course.py` | Bridging a gap from a block lower than a full one (a chest's top). |
| `ladder_course.py` | Going up, stopping on and coming down ladders, on each side of a block and through a roof. |
| `scaffold_course.py` | A mining job climbs to a target on placed blocks, and with `cleanup_scaffold` removes them before it ends. |
| `glide_course.py` | The added glide move ([MOVEMENTS.md](https://github.com/JohnHeibel/Modatone/blob/main/docs/MOVEMENTS.md)): a body with wings crosses a drop and arrives unhurt; without them it stays. |
| `explore_course.py` | `get_to_block` finds a block by id without a whole-world sweep, sees one in a chunk that loads later, and stays inside the tick budget. |
| `step_course.py` | A time step never cuts a click, a slot selection or a held input short, and a guard still ends them ([TIME_CONTROL.md](TIME_CONTROL.md)). |
| `notes_course.py` | World notes as files: written with what was observed, surfaced when the block is looked at, found by place, following edits made by hand. |
| `pathfix_replay.py` | Replays recorded path-search failures in a clone of a played world. Its cases name positions in one particular world, so it is of use only with a snapshot of that world. |
| `challenge.py`, `challenge_tasks.py` | A fresh model in the agent container gets one prepared task and a minute cap; the grade is read from blocks on the server. `challenge.py` runs on the host, not through `mbtest.sh`. |

`tick_cost.py` is not a test by itself. The movement, replay, explore, step,
shell and build scripts use it to fail a case whose worst or mean game-thread
tick is over a limit; `--warm-up` leaves the first case after a client start
unjudged. Its limits are marked provisional in the file. `build_order.py`
reads a finished build job's files and prints the placement order as numbers;
it needs no game.

### With a game: the managed runtime

Four older probes talk to both bridges directly, so they need the native
server of the managed runtime (not a container), built with `-PdevFixtures`
and started with `start-server --dev-fixtures`, with the client joined and its
GUI closed.

| Script | What it checks |
| --- | --- |
| `python harness/smoke/smoke.py --mcp-reload-proof` | Both bridges, player identity, observations, aiming, finite input, cancellation, screenshots, and tool reload with a syntax error kept out safely. |
| `python harness/smoke/interaction_smoke.py` | Block, entity and item use, eating, hotbar selection, bounded combat, interrupt reactions. |
| `python harness/smoke/gui_smoke.py` | Container observation, slot clicks, transfers, cursor return, text fields, buttons, hit tests. |
| `python harness/smoke/primitive_regression_smoke.py` | Regressions from an early machine-building trial: grass clearing, attack-hold bounds, food budgets. |

These four predate the test stack. Every bridge method and fixture they call
is still registered, and the reload proof in `smoke.py` passes without a game;
whether they pass in a game was not checked for this release.

Contained runs, the console and backups are in [CONTAINERS.md](CONTAINERS.md).
