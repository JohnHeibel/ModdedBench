# Contributing

## Build and test

[docs/BUILD.md](docs/BUILD.md) has the prerequisites (JDK 25, Python 3.11 or
newer). Before every commit, from the repository root:

```bash
./gradlew build
python -m unittest discover -s harness/tests -p "test_*.py"
python harness/mcp/server.py --check
python harness/mcp/tool_table.py --check
```

None of these needs Minecraft. If you added, removed or re-described a tool,
run `python harness/mcp/tool_table.py` (without `--check`) to rewrite the tool
table in `PROMPT.md`. A change to movement, mining, building or time control
should also be run in a game: the scripts in `harness/smoke/` and the test
stack they use are described in [docs/BUILD.md](docs/BUILD.md#testing). Say in
the pull request which of them you ran.

## The game-thread budget

Minecraft has 50 ms a tick for everything, and bridge handlers and jobs run on
the game thread. Code there must do a bounded, small amount of work each tick.

- `TickBudgetTest` fails the build when a path it runs takes over 20 ms of one
  tick. If you add a path that reads many blocks or cells, add it to that test.
- In the game, a job whose worst tick is over 100 ms, or whose mean is over
  5 ms a tick, says so in its result as `cost.overBudget`. Treat that as a bug.
- A slow path is not fixed by raising a limit. Slice the work across ticks.

## Where code goes

Java touches Minecraft, Python composes, the model decides
([docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)). Before adding a tool, look for
one to generalise. Before adding Java, check whether Python can compose the
result from existing bridge methods ([docs/TOOLS.md](docs/TOOLS.md)).

Do not edit `mods/baritone/src/upstream` without saying so in the pull
request. Those files come from Baritone and are tracked in
`mods/baritone/UPSTREAM_SOURCES.json`.

## Style

Most edits to this repository are made by the playing agent during a run and
by coding agents between runs. The code is deliberately dense: long lines,
few blank lines, comments that say why and not what. Keep changes minimal and
in the style of the file you touch. Do not reformat code you are not changing.

Commit messages say what failed and what the change fixes. A fix that came
from a run should say what was seen in the game.

## Reporting issues

Open an issue on GitHub. Include the commit, the operating system, the pack
version, what you called (tool and arguments), the result or receipt, and the
relevant part of the client or server log. For a job, include its `jobId` and
`stopped` reason. Do not post bridge tokens or the contents of `docker/.env`.

Security problems are reported differently: see [SECURITY.md](SECURITY.md).
