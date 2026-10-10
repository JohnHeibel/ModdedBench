# Contained runs

For long unattended runs the agent and the server each live in a container
and the game client stays on the host, where the GPU and the screen recorder
are. The agent can edit and build anything in its own checkout, but it reaches
the world through exactly two doors. The agent is one of two CLIs, chosen per
run: the Codex CLI or Claude Code (`--runtime codex|claude`; the loop in
`harness/runner/agent_loop.py` drives either, through `rt_codex.py` and
`rt_claude.py`). This setup has been run on Windows
11 with Docker Desktop only. On Linux with plain Docker Engine it is expected
not to work as it stands: the gateway reaches the client bridge through
`host.docker.internal`, and the bridge listens on the host's loopback only.
Commands are written for Git Bash.

```
 agent container ──(internal network)── gateway ──► model provider (allowlist)
   Codex or Claude Code, repo, JDK,        │
   Gradle cache,                           │
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
| Agent to internet | The agent's network is `internal`. The gateway proxies `*.openai.com` and `*.chatgpt.com` (Codex) and `api.anthropic.com`, `platform.claude.com` and `claude.ai` (Claude Code) and refuses the rest (`docker/squid.conf`). Neither CLI has a web tool: Codex's web search is off in `docker/codex-config.toml`, and Claude Code is started with `WebSearch`, `WebFetch` and sub-agents disallowed and its automatic memory off (`harness/runner/rt_claude.py`). |
| Agent to game | The client bridge, forwarded by the gateway; the token folder is mounted read-only and re-read after each client restart. |
| Agent to host files | `/outbox` only. `harness/launcher/deploy.py serve` on the host accepts `modbench-client.jar`, `modbench-core.jar` and `modbench-baritone.jar`, nothing else, installs them on the client only, restarts it and rolls back if it does not join (a client that will not shut down for the rollback is terminated: the java process of the managed instance, nothing else). One supervisor per checkout: a second `serve` exits. |
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
   have read the Minecraft EULA. The other settings are in the table below.
   Create the `BRIDGE_TOKENS` folder if it does not exist yet; the token file
   appears in it when the modded client first starts (step 5).
2. Prepare the host client as in [BUILD.md](BUILD.md) (`prepare`,
   `provision-client`). Do not start the native server. No ModdedBench jar is
   installed at this point, so `stop-client` cannot reach the game: quit it
   from its own menu.
3. Build and start. The first build downloads and decompiles Minecraft inside
   the `dev` stage and takes a while.

```bash
docker compose -f docker/compose.yaml --env-file docker/.env up -d --build
```

   Snapshot the GTNH wiki for the agent's offline `mb_wiki_search` and
   `mb_wiki_read` (about 640 pages, a minute, CC BY-SA 4.0, kept out of git in
   `.runtime/wiki`). The agent has no route to the wiki, so every run reads the
   same frozen copy; rerun to refresh it.

```bash
python harness/wiki/fetch.py
```

4. Log in the CLI you will run, once; both logins are kept in the `agent-home`
   volume and survive a rebuilt or recreated container. Neither needs a browser
   in the container. Codex (an OpenAI account with Codex access) prints a URL
   and a code to enter in a browser on any machine. Claude Code (a Claude
   subscription, or `--console` for API billing) prints a URL to open in a
   browser on any machine; paste the code that page shows back into the
   terminal.

```bash
docker compose -f docker/compose.yaml exec agent codex login --device-auth
docker compose -f docker/compose.yaml exec agent claude auth login
```

   A key or token works instead of a login: uncomment `CODEX_API_KEY`,
   `CLAUDE_CODE_OAUTH_TOKEN` (what `claude setup-token` prints, run on any
   machine) or `ANTHROPIC_API_KEY` in `docker/.env`, fill it in, and run
   `docker compose ... up -d` again. A key that is set is used in place of the
   login; one left out, or empty, leaves the login in charge. The agent can
   read either inside its container, as it can the login file. `codex login
   status` and `claude auth status` in the container say what is in use, and
   the console shows both.

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

6. Start the run: fill the four placeholders in a copy of `PROMPT.md` and save
   it as `.runtime/brief/PROMPT.md` (mounted read-only at `/brief`; the console's
   Initialize does both). The loop runs under the checkout's lock, so a second
   one cannot start beside it. `--runtime` is `codex` (the default) or `claude`;
   `--model` and `--effort` are passed to that CLI in its own spelling, and
   left out they are the CLI's defaults.

```bash
docker compose -f docker/compose.yaml exec agent sh -c 'mkdir -p .state && exec flock -n .state/loop.lock python3 harness/runner/agent_loop.py --runtime claude --model sonnet --prompt /brief/PROMPT.md --max-turns 200 --max-minutes 120'
```

Settings in `docker/.env`:

| Variable | Default | Meaning |
| --- | --- | --- |
| `EULA` | `false` | `true` accepts the Minecraft EULA; the server does not start without it |
| `PACK_DIR` | none, required | Host folder (not the zip itself) holding the GTNH server zip named in `pack.lock.json` |
| `BRIDGE_TOKENS` | none, required | Host folder holding `bridge-47223.token`, normally `.moddedbench` in your user folder |
| `SERVER_MEMORY_MIB` | `6144` | Server heap |
| `BRIGHT_NIGHTS` | `false` | `true` turns off the pack's near-black night rendering, for recording; light levels in the game are unchanged |
| `MB_DIFFICULTY` | `1` | The world's difficulty, written to `server.properties` at every server start: 0 peaceful, 1 easy, 2 normal, 3 hard. The pack ships 3; runs are played on easy unless this says otherwise. |

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

Start gives the loop a budget only for the fields that are filled in: minutes
and millions of tokens, counted from that start. The run's end time and token
cap are kept in `.state/run.json`, so a later Start with a field left blank
continues to the same end and cap (the blank field shows what is left) instead
of handing the run a fresh budget; a run with nothing stored gets 120 minutes
and 50 M. Initialize clears them. Start is refused while a loop is running on
the checkout (`.state/loop.lock`, held by `flock` for as long as the loop
lives). A loop that dies leaves its last words in `.state/agent-loop.err`, and
the page shows them above the log while no loop is running.

Pause is an operator hold, not a bridge call: the console writes
`modbench-hold` in the server's directory, the server pauses within a tick and
refuses every `time.resume` until the file is gone. The agent has no path to
that directory, so it can neither block the hold nor undo it. The file names
its holder (`operator`, `backup`, `compaction`; the server reads the word and
tells the agent whose hold it met), and each holder removes only its own: Resume ends the operator's hold,
and says so when a snapshot or the compaction guard still holds the world.
Pause takes any hold over, so Pause then Resume ends one that nobody is
ending. A release resumes only the pause the hold itself made: a pause that
the agent or a disconnect left behind stays until the agent resumes it.

## Stream overlay

`http://127.0.0.1:47300/overlay` is the whole stream frame as one read-only page,
1920x1080 with a transparent hole for the game: the game shows through the top-left
1440x810, the feed (what the agent said and did, and what it is doing now) runs down
the right, and the goal stack and run totals sit along the bottom.

In OBS, with a 1920x1080 canvas:

1. Add the game as a Game Capture (Window Capture does not work for this client),
   resize it to 1440x810 and put it in the top-left corner (Edit Transform: position
   0, 0; size 1440 x 810).
2. Add a Browser source above it: that URL, width 1920, height 1080, and empty the
   Custom CSS box. Tick "Refresh browser when scene becomes active" if you like.
3. The console must be running; the page polls it every second and keeps the last
   picture while it is away.

`/overlay#preview` in an ordinary browser paints a stand-in for the game and scales to
the window, `#sample` shows built-in sample data, `#tier=stone|steam|lv` forces the
accent colour that otherwise follows the agent's progress.

With `OPENROUTER_API_KEY` in the console's environment, goals over 70 characters and
the agent's remarks over 220 are shortened for the frame by a cheap model
(`MB_OVERLAY_MODEL`, default `deepseek/deepseek-v4.1-flash`), cached in
`.runtime/overlay-short.json`, and marked "in short" on screen. This runs on the host
only; the agent never has the key and never sees a summary. The client runs Java the
agent wrote, so the launcher starts the game without any environment variable named
like a credential (`KEY`, `TOKEN`, `SECRET`, `PASSWORD`). Prism Launcher must not
already be open: an open Prism starts the game with its own environment.

`/overlay/data` is the same data as JSON, for a layout of your own. The loop writes
it (`harness/runner/feed.py`) to `.runtime/outbox/overlay`: `feed.jsonl`, whose
`mark` lines are the run's milestones with timestamps, and `live.json`. One thing is
asked of the model for this: `mb_goal`'s optional `progress`, its quick guess at how
far the current quest is. The rest is what it does anyway: the goal is the one it
keeps with `mb_goal`, and action lines are templates over its tool calls. Initialize clears both files.

## Operating by hand

| Task | Command |
| --- | --- |
| Server console | `docker attach moddedbench-server-1` (detach with Ctrl-P Ctrl-Q) |
| Stop the server cleanly | `docker compose -f docker/compose.yaml stop server` |
| Stop the agent | create `.state/STOP` in the agent's checkout: `docker compose ... exec agent touch .state/STOP`. The turn is cut where it stands within seconds and the conversation resumes at the next start; the console's Start removes the file, by hand remove it first |
| See what the agent asked the network for | `docker compose ... logs gateway` |
| Take the agent's commits out | `docker compose ... exec agent git bundle create /outbox/run.bundle modbench-base..HEAD`, then `git fetch .runtime/outbox/run.bundle` on the host |
| Hold the world paused / release | `docker compose ... exec server sh -c 'echo operator > /data/modbench-hold'` / `... rm -f /data/modbench-hold` (`cat` it first: `backup` or `compaction` is a hold that ends by itself) |
| New world | `docker compose ... down`, `docker volume rm moddedbench_server-data` |
| New world that keeps the old one | a side stack, below |
| Start a run without the console | fill the four placeholders in a copy of `PROMPT.md`, save it as `.runtime/brief/PROMPT.md`, then start the loop as in step 6 above (under `flock`, or the one-loop guard does not cover it), or paste it into an interactive `codex` in that container |
| Change the agent CLI on a world | Start with the other runtime. A conversation belongs to one CLI, so this begins a new one from the brief, the notes and the goal stack; the old thread id is kept as `.state/agent-loop.json.<runtime>` |
| Snapshot the world, the notes and the agent's work | `python harness/launcher/backup.py once`, or `loop --every 30` in a terminal you leave open (one loop per stack: a second exits) |

## Side stacks and the test stack

A stack is one Compose project: a server, a gateway, an agent, and their
volumes. The default project is `moddedbench`. Two overlay files make further
stacks beside it. Each has its own world and agent checkout and shares the
login volume (both CLIs') of the default stack (a copy of the login would log one of
the two out), so the default stack must have been created and logged in
first. All stacks publish port 25575: stop the others before starting one.

| Stack | For | How |
| --- | --- | --- |
| Side stack (`docker/compose.side.yaml`) | A second world for real runs, keeping the first | Start the console with `MB_COMPOSE_PROJECT=<name>` set; every button then acts on that stack |
| Test stack (`docker/compose.test.yaml`) | Tests that need a game; its server is built with the development fixtures | Project name `mbtest`; the command and the tests are in [BUILD.md](BUILD.md#testing) |

`harness/launcher/backup.py` follows `MB_COMPOSE_PROJECT` too.

## The brief and the heartbeat

A run outlives the agent's context many times over, so what it must never lose
is kept where it cannot damage it. The filled `PROMPT.md` for the run lives on
the host in `.runtime/brief` and is mounted read-only at `/brief` (the console's
Initialize writes it; by hand, copy it there). `AGENTS.md` is the heartbeat:
Codex reads `~/.codex/AGENTS.md` at the start of every session, and the agent
container restores that file from a root-owned copy in the image at every
start. It tells a freshly started or freshly compacted agent to re-read the
brief, call `mb_status` (which returns the goal stack and names the brief
again) and read its notes. Claude Code reads `CLAUDE.md` in its working
directory, the checkout, which imports the same file; and since it takes a
system prompt on its command line, the loop gives it the mounted brief there at
every launch, where a compaction cannot drop it (the prompt cache makes that
cost a cache read, not fresh input). Codex gets the brief as its first message.
The agent may edit everything else in its checkout, including the repository's
copies of these files, but not the mounted brief.

## Backups

`harness/launcher/backup.py` is for the operator only. Each snapshot holds the
world (the hold file, as `backup`: no ticks, so nothing is being saved), streams the
server's data folder without the pack's own files to
`.runtime/snapshots/<time>/world.tar.gz`, archives the agent's notes folder
(the note files and their git history) to `notes.tar.gz` beside it so the two always
match, and releases the hold unless one was already in force. The world on
disk is the last autosave, at most 45 seconds of game time old. With the world
running again it saves what else exists only in the agent's volume:
`agent.bundle` (`git bundle --all` of its checkout: every commit, no
uncommitted edits) and `state.tar.gz` (its `.state` without logs: thread id,
run budget, tasks, call log). It keeps the newest 48 snapshots and the first
of each day. A snapshot that fails says why in `.runtime/logs/backups.log`
and the loop goes on to the next.

No container mounts `.runtime/snapshots`, and the agent's brief does not mention
backups: from inside the run every mistake is permanent. The console starts the
30-minute loop with a run (`.runtime/logs/backups.log`) and stops it with
**agent down**. Restoring is an operator decision for infrastructure faults
only (a corrupted world, a lost disk, a harness bug that damaged state), never
to undo the agent's own mistakes:
`python harness/launcher/backup.py restore <snapshot> --reason "..."` checks
that the archives read to their end (before anything is touched), stops the
server and agent, replaces the world and the notes, and appends the restore to
`.runtime/snapshots/restores.jsonl` (host only). Start the stack when ready.
The commits and the loop state are not restored with it; when they are wanted,
copy the two files into `.runtime/outbox` and, in the agent container,
`git fetch /outbox/agent.bundle 'refs/heads/*:refs/remotes/snapshot/*'` and
`tar -xzf /outbox/state.tar.gz -C .state`.
Set `MB_COMPOSE_PROJECT=mbtest` to act on a test stack.

Recording: an OBS Game Capture set to a specific window, matched on the window
title, picks the client up again after a deploy restarts it; record to `.mkv`
and split by time. With
the `pauseOnDisconnect` guard set (`PROMPT.md` asks for it at session start)
the world is held while the client is down. The pack's Darkerer mod makes nights
near-black on video; it only changes rendering, and its config is synced from the server on
join, so a client-side edit does nothing: set `BRIGHT_NIGHTS=true` in
`docker/.env` and restart the server. Light levels and mob spawning are unchanged.

Restarts: the world stays paused (`client_disconnected`) after the client
comes back, until the agent resumes it (the console's Resume ends only the
operator's hold). After a server
restart press Launch and join; it reconnects the running client and keeps
trying every 10 s while the server boots. A hold survives a server restart:
the server runs a one-second warm-up (pack mods build world data on their
first tick) and then holds again.
