# Contained runs

For long unattended runs the agent and the server each live in a container
and the game client stays on the host, where the GPU and the screen recorder
are. The agent can edit and build anything in its own checkout, but it reaches
the world through exactly two doors.

```
 agent container ──(internal network)── gateway ──► model provider (allowlist)
   Codex, repo, JDK, Gradle cache,         │
   Python MCP server                       └──────► host 127.0.0.1:47223  client bridge
        │ /outbox (the only host folder it can write)
        ▼
 host: deploy.py serve ──► client mods folder (three jars) ──► Prism ──► client ──► 127.0.0.1:25575
                                                                                      │
 server container: pinned pack server, pinned ModdedBench jars, world volume ◄────────┘
```

| Boundary | How it is held |
| --- | --- |
| Agent to server | No shared network, volume or console. `time.*` and the authoritative observations already travel through the client's game connection. |
| Agent to internet | The agent's network is `internal`. The gateway proxies `*.openai.com` and `*.chatgpt.com` and refuses the rest (`docker/squid.conf`). |
| Agent to game | The client bridge, forwarded by the gateway; the token folder is mounted read-only and re-read after each client restart. |
| Agent to host files | `/outbox` only. `harness/launcher/deploy.py serve` on the host accepts `modbench-client.jar`, `modbench-core.jar` and `modbench-baritone.jar`, nothing else, installs them on the client only, restarts it and rolls back if it does not join. |
| Server code | `modbench-server.jar` and `modbench-core.jar` are copied from the image on every start, built from the commit the image was built from. |

What this does not do: the client runs model-written Java on the host as your
user, so the containers bound the agent process, not the client JVM (run the
client under a separate Windows account if that matters). A modified client
can still send any packet a hacked client could, and a duplication bug that
already exists in the pack can be triggered by a plain client. Those are
forbidden in `PROMPT.md` and reviewable afterwards: every deploy is kept under
`.runtime/deploys/<time>/` with its jars, `source.patch` against the image's
`modbench-base` tag, the commit and the result.

## Setup

1. Install Docker Desktop (WSL 2 backend). Copy `docker/.env.example` to
   `docker/.env`; set `PACK_DIR`, `BRIDGE_TOKENS`, and `EULA=true` once you
   have read the Minecraft EULA.
2. Prepare the host client as in [BUILD.md](BUILD.md) (`prepare`,
   `provision-client`). Do not start the native server.
3. Build and start. The first build downloads and decompiles Minecraft inside
   the `dev` stage and takes a while.

```bash
docker compose -f docker/compose.yaml --env-file docker/.env up -d --build
```

4. Log Codex in once; the login is kept in the `agent-home` volume.

```bash
docker compose -f docker/compose.yaml exec agent codex login --device-auth
```

5. On the host, build and install the same commit on the client, launch it,
   and leave the supervisor running.

```bash
python harness/launcher/runtime.py build
python harness/launcher/runtime.py install-core --side client
python harness/launcher/runtime.py install-client
python harness/launcher/runtime.py install-baritone
python harness/launcher/runtime.py launch-client
python harness/launcher/deploy.py serve
```

6. Start the run. `PROMPT.md` placeholders are filled in the agent's checkout.

```bash
docker compose -f docker/compose.yaml exec agent python3 harness/runner/codex_loop.py --max-turns 200
```

## Operator console

```bash
python harness/console/console.py
```

opens a page at `http://127.0.0.1:47300` with the state of all of it (containers,
client bridge, world, player, clock, quest counts, the agent's loop, its log
tail, its deploy requests) and a button for each routine step: start and stop
the server, pause and resume time, launch and stop the client, build and
install the client jars, run the deploy supervisor, start, stop or kill the
agent's loop, and initialize a run (target quest and chapter, optionally a new
world and a fresh agent checkout). Every button runs a command from this page
and shows it in the job log. The console listens on loopback only and each
request carries a token minted at start, so neither a web page nor the agent
can drive it.

## Operating by hand

| Task | Command |
| --- | --- |
| Server console | `docker attach moddedbench-server-1` (detach with Ctrl-P Ctrl-Q) |
| Stop the server cleanly | `docker compose -f docker/compose.yaml stop server` |
| Stop the agent after its current turn | create `.state/STOP` in the agent's checkout: `docker compose ... exec agent touch .state/STOP` |
| See what the agent asked the network for | `docker compose ... logs gateway` |
| Take the agent's commits out | `docker compose ... exec agent git bundle create /outbox/run.bundle modbench-base..HEAD`, then `git fetch .runtime/outbox/run.bundle` on the host |
| New world | `docker compose ... down`, `docker volume rm moddedbench_server-data` |

Recording: OBS window capture matched on the window title picks the client up
again after a deploy restarts it; record to `.mkv` and split by time. With
the `pauseOnDisconnect` guard set (`PROMPT.md` asks for it at session start)
the world is held while the client is down.
