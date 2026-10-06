# ModdedBench

A harness in which a language model plays **GT New Horizons** (GTNH, a
Minecraft 1.7.10 modpack) in survival, and edits its own tools while it plays.

Forge mods expose the running game over a local JSON-RPC bridge: observations,
player input, GUI and inventory operations, NEI recipes, Better Questing,
navigation, mining, construction, and a whole-tick pause with survival guards.
A Python MCP server turns that bridge into `mb_*` tools. Every tool module is
reloaded when the model edits it.

```
 model ──MCP──▶ harness/tools/*.py ──websocket──▶ modbench-client.jar ─┐
                (edited while playing)             modbench-baritone.jar │ Forge 1.7.10
                                                   modbench-core.jar     │ (the only coremod)
                                                   modbench-server.jar ─┘
```

GT New Horizons is not included. You need the GTNH 2.8.4 client and server
archives named in `pack.lock.json`.

## Quick start: a contained run

This is the usual mode. The agent (the Codex CLI) and the game server each run
in a Docker container; the game client runs on the host. It has been run on
Windows 11 with Docker Desktop. [docs/CONTAINERS.md](docs/CONTAINERS.md) has
every step and what the containers do and do not isolate. In outline:

1. Install the prerequisites in [docs/BUILD.md](docs/BUILD.md) and prepare the
   host client (`harness/launcher/runtime.py prepare`, then `provision-client`).
2. Copy `docker/.env.example` to `docker/.env` and fill it in.
3. Build and start the containers:

   ```bash
   docker compose -f docker/compose.yaml --env-file docker/.env up -d --build
   ```

4. Log Codex in once:

   ```bash
   docker compose -f docker/compose.yaml exec agent codex login --device-auth
   ```

5. Start the operator console and open `http://127.0.0.1:47300`:

   ```bash
   python harness/console/console.py
   ```

   Its buttons build and install the client jars, launch the client, run the
   deploy supervisor, initialize a run (target quest and chapter) and start
   the agent's loop.

## Second option: any MCP client, by hand

1. Build the mods and install them into a GTNH 2.8.4 client and dedicated
   server ([docs/BUILD.md](docs/BUILD.md)). On Windows,
   `harness/launcher/runtime.py` sets up an isolated Prism Launcher instance
   and a local server.
2. Point an MCP client at `python harness/mcp/server.py` (stdio). The included
   `.mcp.json` registers it for clients that read that file.
3. Join the world, close any open GUI, and call `mb_status`. Then try:

```
mb_obs("player")                          position, health, food, held item
mb_quest_status()                         whether the quest book is readable, and its counts
mb_recipes(id="minecraft:furnace", meta=0)  how to make it, per NEI handler
mb_mine(blocks=[{"id":"minecraft:log"}], items=[{"id":"minecraft:log"}], quantity=8, radius=48)
mb_build(cells=[{"pos":[0,0,0],"id":"minecraft:cobblestone"}], origin=[100,64,100])
mb_notes("find", {"near": "player"})      notes written near here (the notes are files)
```

4. For an autonomous session, give the model [PROMPT.md](PROMPT.md) with its
   placeholders filled in. It states the mission, the rules, the tools and
   their limits, and the model's right to edit the harness.

## What is where

| Path | Role | Change cycle |
| --- | --- | --- |
| `mods/api` | Interfaces and value types shared by the mods; bundled into the core jar | rebuild, reinstall, restart |
| `mods/core` | Coremod: class transformers, input arbiter, websocket transport, simulation clock | rebuild, reinstall, restart |
| `mods/client` | Observation, action, GUI, inventory, NEI, quest, memory RPCs | rebuild, reinstall, restart |
| `mods/server` | Authoritative tile/NBT/Waila reads, tick gate and guards | rebuild, reinstall, restart |
| `mods/baritone` | Navigation, mining, construction: a port of Baritone to 1.7.10 as a plain mod | rebuild, reinstall, restart |
| `harness/mcp` | MCP server, transport kernel, `@tool` contract | restart the server |
| `harness/tools` | The tools the model sees and edits | reloaded on the next call |
| `harness/launcher` | Managed local runtime (Prism instance, server, jar install and rollback), the deploy supervisor and backups for contained runs | |
| `harness/runner` | The loop that keeps the Codex CLI on the mission (`codex_loop.py`) and the stream feed it writes (`feed.py`) | |
| `harness/console` | Local web console and stream overlay for contained runs | |
| `harness/mirror` | Optional read-only spectator copy of the world for stock clients ([docs/MIRROR.md](docs/MIRROR.md)) | |
| `harness/wiki` | Fetches the GTNH wiki into one SQLite file for the offline wiki tools | |
| `harness/tests` | Python unit tests; no game needed | |
| `harness/smoke` | Tests that need a running game ([docs/BUILD.md](docs/BUILD.md#testing)) | |
| `docker/` | Server, agent and gateway images for contained runs | |
| `docs/` | [ARCHITECTURE](docs/ARCHITECTURE.md), [BUILD](docs/BUILD.md), [CONTAINERS](docs/CONTAINERS.md), [TOOLS](docs/TOOLS.md), [TIME_CONTROL](docs/TIME_CONTROL.md), [BARITONE_PORT](docs/BARITONE_PORT.md), [MOVEMENTS](docs/MOVEMENTS.md), [MIRROR](docs/MIRROR.md), [HISTORY](docs/HISTORY.md) | |

## Design rules

- **Java touches Minecraft, Python composes, the model decides.** No path
  search, physics, recipe logic or inventory acknowledgement in Python; no
  model-specific procedures in Java.
- **Receipts are not acknowledgements.** Every action returns what was sent;
  completion is a fresh observation.
- **Long work is durable.** Mining, building and routing jobs have ids that
  survive a client restart and can be resumed after the cause is fixed.
- **The model may change anything.** Python changes are live immediately; Java
  changes cost a rebuild and a restart and lose nothing durable.

## Status

Research code, developed and run on one Windows 11 machine.

- The only agent runtime is the Codex CLI. The MCP server works with any
  stdio MCP client, but nothing else has been run unattended.
- Unattended contained runs of 2, 4, 8 and 24 hours have been completed
  ([docs/HISTORY.md](docs/HISTORY.md)). The furthest world finished the Stone
  Age and Steam chapters and entered the LV tier after about 64 hours of play
  and 140 quests. Later tiers have not been reached and are expected to need
  tool work by the model.
- The managed runtime and the container setup have only been used on Windows.
  The Gradle build and the Python code are not Windows-specific.
- Known limits are stated where they apply: navigation and construction in
  [docs/BARITONE_PORT.md](docs/BARITONE_PORT.md), time control in
  [docs/TIME_CONTROL.md](docs/TIME_CONTROL.md), isolation in
  [docs/CONTAINERS.md](docs/CONTAINERS.md).

## Contributing and security

[CONTRIBUTING.md](CONTRIBUTING.md) says how to build, test and report issues.
[SECURITY.md](SECURITY.md) says what the harness exposes and how to report a
vulnerability. Read it before a run: the game client executes code the model
wrote.

## Licence

LGPL-3.0-or-later for the whole repository. `mods/baritone/src/upstream`
contains files from [Baritone](https://github.com/cabaletta/baritone)
(v1.2.19), pinned by hash in `UPSTREAM_SOURCES.json`; see `NOTICE.md` and
`LICENSES/`. GT New Horizons itself is not redistributed.
