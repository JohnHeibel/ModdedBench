# AGENTS.md

ModdedBench is an MCP harness that lets an LLM agent play GT New Horizons (Minecraft 1.7.10): Java
mods in `mods/` expose the game over a bridge, and hot-reloaded Python tools in `harness/tools`
compose it into the `mb_*` tools. You both play through those tools and maintain them.

Read first: `PROMPT.md` (the mission and rules), `docs/TOOLS.md`, `docs/ARCHITECTURE.md`.

Build and test (details in `docs/BUILD.md`; `JAVA_HOME` must point at the JDK it names, JDK 25 works):

```bash
./gradlew.bat build --offline --console=plain -q    # then ./gradlew.bat --stop
python -m unittest discover -s harness/tests -p "test_*.py"
python harness/mcp/server.py --check
```

Rules:

- Never edit `mods/baritone/src/upstream` without saying so in your report.
- Never end a turn to wait for a machine, a job or a watch: call `mb_wait` and keep its cursor.
- `harness/tools/*.py` reload on the next tool call; `harness/mcp` needs an MCP server restart;
  Java needs a build and a redeploy.
- Keep code minimal and in the style of the file you touch.
