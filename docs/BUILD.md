# Build, install, run

Everything below has been exercised on Windows 11. Linux and macOS should work
for the Gradle build and the Python server; the managed Prism runtime has only
been run on Windows.

## Prerequisites

| Need | Version | Why |
| --- | --- | --- |
| JDK | 25 (`JAVA_HOME` pointing at it) | RetroFuturaGradle 2.0.2 requires Java 25 to run Gradle. Gradle provisions the older JDK it needs to decompile Minecraft; the mods are compiled to Java 17 bytecode. |
| Gradle | wrapper, 9.2.0 | `gradlew` / `gradlew.bat` in the repository root; nothing to install. |
| Python | 3.11 or newer | `pip install -r harness/mcp/requirements.txt` (`mcp`, `websockets`). |
| GT New Horizons 2.8.4 | client and server archives, Java 17-25 builds | Not redistributed. `pack.lock.json` records the exact file names, sizes and SHA-256 the launcher verifies. |
| Prism Launcher | any recent | Only for the managed local client instance. Any launcher that can run the pack with extra jars in `mods/` works for manual installs. |

The first build needs network access to fetch Forge, the GTNH Nexus
dependencies and the Gradle plugin. Later builds run `--offline`.

## Build and test

From the repository root:

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
`mods/<module>/build/libs/`. Install the reobfuscated jars, not the `-dev` or
`-sources` variants:

| Jar | Install where | Role |
| --- | --- | --- |
| `mods/core/build/libs/modbench-core-<v>.jar` | client `mods/` and server `mods/` | The only coremod: class transformers for the simulation clock and GUI input, the input arbiter and control leases, the websocket JSON-RPC transport, the API classes. Required by every other jar. |
| `mods/client/build/libs/modbench-client-<v>.jar` | client `mods/` | The observation, action, GUI, inventory, NEI and Better Questing RPC surface on port 47223. |
| `mods/server/build/libs/modbench-server-<v>.jar` | dedicated server `mods/` | Authoritative tile, NBT and Waila observations, the simulation-tick gate and its guards, on port 47224. |
| `mods/baritone/build/libs/modbench-baritone-<v>.jar` | client `mods/` | Navigation, mining, construction and work journals. A plain Forge mod that depends only on the API; optional, but the `baritone.*` methods need it. |

The server jar is not needed in a client-only install, and the client works
without a dedicated server when the game hosts its own world, but time control
and authoritative observations need the server jar on a dedicated server.

Development fixtures (privileged world-editing helpers used by the smoke
tests) are compiled from `mods/server/src/dev` and left out of the jar. Build
with `-PdevFixtures` to include them, and start the server with
`--dev-fixtures` to register their `dev.*` methods. Never ship such a jar.

## Bridge connection

Both bridges bind to loopback and require a token that the mod generates at
startup and writes to `~/.moddedbench/bridge-<port>.token`. The Python kernel
reads that file itself; do not paste tokens anywhere. `-Dmodbench.port=` and
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

```bash
python harness/launcher/runtime.py build
```

Then install and start:

```bash
python harness/launcher/runtime.py install-core
```

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
running and which jars are installed; each `install-*` keeps the previous jar
for `rollback-*`.

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

Install the jars that changed (`install-core`, `install-client`,
`install-baritone`; `stop-server`, `install-server`, `start-server` for the
server jar), then:

```bash
python harness/launcher/runtime.py launch-client
```

Work journals, world memory, world notes and quest progress all survive the
restart. Only the running job's execution is lost; resume it from its job id.

## Smoke tests

With the client joined to the server and its GUI closed:

```bash
python harness/smoke/smoke.py --mcp-reload-proof
```

`harness/smoke/` also holds the interaction, GUI and primitive regression
probes. They need a live game; some need the development fixtures. Screenshots
and evidence go under the ignored `.runtime/evidence/`.
