# ModdedBench

A harness that lets a language model play **GT New Horizons** (Minecraft
1.7.10) in survival, and improve its own tools while it plays.

Four Forge mods expose the running game over a local JSON-RPC bridge:
observations, player input, GUI and inventory operations, NEI recipes, Better
Questing, navigation, mining and construction, and a whole-tick pause with
survival guards. A small Python MCP server turns that bridge into tools for
any MCP client, and every tool module is hot-reloaded when the model edits it.

```
 model ──MCP──▶ harness/tools/*.py ──websocket──▶ modbench-client.jar ─┐
                (edit while playing)               modbench-baritone.jar │ Forge 1.7.10
                                                   modbench-core.jar     │ (the only coremod)
                                                   modbench-server.jar ─┘
```

## Demo

1. Build the mods and install them into a GTNH 2.8.4 client (and dedicated
   server, for time control and authoritative machine reads). See
   [docs/BUILD.md](docs/BUILD.md); on Windows the launcher script sets up an
   isolated Prism instance and local server for you.
2. Point an MCP client at `harness/mcp/server.py` (Claude Code reads the
   included `.mcp.json`; any stdio MCP client works).
3. Join a world, close the GUI, and ask for `mb_status`. Then try:

```
mb_obs("player")                          where am I, health, food, held item
mb_quest_status()                         what the quest book wants next
mb_recipes(id="minecraft:furnace", meta=0)  how to make it, per NEI handler
mb_mine(blocks=[{"id":"minecraft:log"}], items=[{"id":"minecraft:log"}], quantity=8, radius=48)
mb_build(cells=[{"pos":[0,0,0],"id":"minecraft:cobblestone"}], origin=[100,64,100])
mb_notes("search", {"query": "furnace"})  what past sessions left behind
```

4. For an autonomous run, give the model [PROMPT.md](PROMPT.md) with a target
   quest filled in. It explains the tools, their limits, and the model's right
   to edit the harness.

## What is where

| Path | Role | Change cycle |
| --- | --- | --- |
| `mods/core` | Coremod: class transformers, input arbiter, websocket transport, simulation clock | rebuild, reinstall, restart |
| `mods/client` | Observation, action, GUI, inventory, NEI, quest, memory RPCs | rebuild, reinstall, restart |
| `mods/server` | Authoritative tile/NBT/Waila reads, tick gate and guards | rebuild, reinstall, restart |
| `mods/baritone` | Navigation, mining, construction; a port of Baritone to 1.7.10 as a plain mod | rebuild, reinstall, restart |
| `harness/mcp` | MCP server, transport kernel, `@tool` contract | restart the server |
| `harness/tools` | The tools the model sees and edits | hot-reloaded on the next call |
| `harness/launcher` | Managed local runtime (Prism instance, server, jar install/rollback) | |
| `harness/runner` | Provider-neutral autonomous loop with a file adapter | |
| `harness/smoke` | Live-game probes | |
| `docs/` | [ARCHITECTURE](docs/ARCHITECTURE.md), [BUILD](docs/BUILD.md), [TOOLS](docs/TOOLS.md), [TIME_CONTROL](docs/TIME_CONTROL.md), [BARITONE_PORT](docs/BARITONE_PORT.md), [RUNNER](docs/RUNNER.md), [VALIDATION](docs/VALIDATION.md), [HISTORY](docs/HISTORY.md) | |

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

Demo quality. Verified pieces and known gaps are listed in
[docs/VALIDATION.md](docs/VALIDATION.md); the short version is that the early
game, a few steam and LV machines, construction and one electric blast furnace
line have been exercised live, and everything past that is expected to need
tool work by the model.

## Licence

LGPL-3.0-or-later for the whole repository. `mods/baritone/src/upstream`
contains files from [Baritone](https://github.com/cabaletta/baritone)
(v1.2.19), pinned by hash in `UPSTREAM_SOURCES.json`; see `NOTICE.md` and
`LICENSES/`. GT New Horizons itself is not redistributed.
