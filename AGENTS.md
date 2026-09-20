# Heartbeat

ModdedBench is an MCP harness that lets an LLM agent play GT New Horizons (Minecraft 1.7.10): Java
mods in `mods/` expose the game over a bridge, and hot-reloaded Python tools in `harness/tools`
compose it into the `mb_*` tools.

You are reading this because a session has just started or your context was rebuilt. Work out
which of two situations you are in.

## You have `mb_*` tools and a mission (a target quest)

You are the player in a run that lasts far longer than your context. You have probably been here
before and do not remember it. Do this, in order, before anything else:

1. Read your standing brief in full: `/brief/PROMPT.md` if it exists (a contained run; it is
   read-only so that it is always there to come back to), otherwise `PROMPT.md` at the root of
   this repository. It holds your mission, the rules, how to play well, and the tool reference.
2. Call `mb_status`. It returns the connection, the clock, and your **goal stack**: chapter,
   current quest, working sub-goal, and how long the game has run without visible progress.
3. Read the notes that matter now: search `mb_notes` for the current chapter and quest, and read
   whatever surfaced under `notes` in step 2.
4. Continue from the sub-goal. If the goal stack is empty or wrong, fix it with `mb_goal` first.

Your memory is the notes, the goal stack, the quest book and this repository's git history, not
your context. Anything you learn that you will need in ten hours goes into a note now.

Never end a turn to wait for a machine, a job or a watch: call `mb_wait` and keep its cursor.

## Otherwise you are developing the harness

`PROMPT.md` is then a document you maintain, not your instructions. Read `README.md`,
`docs/TOOLS.md` and `docs/ARCHITECTURE.md`.

Build and test (details in `docs/BUILD.md`; `JAVA_HOME` must point at the JDK it names, JDK 25 works):

```bash
./gradlew.bat build --offline --console=plain -q    # then ./gradlew.bat --stop
python -m unittest discover -s harness/tests -p "test_*.py"
python harness/mcp/server.py --check
python harness/mcp/tool_table.py                    # after adding or re-describing a tool
```

## Either way

- Never edit `mods/baritone/src/upstream` without saying so in your report.
- `harness/tools/*.py` reload on the next tool call; `harness/mcp` needs an MCP server restart;
  Java needs a build and a redeploy.
- Keep code minimal and in the style of the file you touch.
