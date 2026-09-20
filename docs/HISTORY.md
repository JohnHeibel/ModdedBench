# History

Dated milestones, newest first. One to three lines each; the detailed records
they summarise are in `docs/legacy/` until they are folded into the current
docs or dropped.

- **2026-09-19** Restructured into this repository from the ModdedBench
  workspace (Baritone fork branch `modbench-gtnh`, commit `a1c43ef4`).
  LGPL-3.0-or-later throughout; one coremod (`core`), plain Baritone jar, the
  whole `harness/tools/` directory hot-reloaded, world notes surfacing as a
  side effect of observations, `PROMPT.md` for quest-book runs.
- **2026-09-18** Electric Blast Furnace agent trial: a supervised agent built
  and ran an EBF line from supplied materials and exported two aluminium
  ingots. Follow-up fixed grass clearing, attack-hold bounds and food handling
  (13 live regression checks). Layer ownership doc updated with conditional
  self-prompts and survival guards (`foodBelow`, `burning`).
- **2026-09-14** Native runtime and API acceptance: event bus, rendering,
  free-look and the Java process API run through Forge 1.7.10 adapters; 16
  live checks. Roadmap refreshed.
- **2026-09-13** Baritone parity audit against upstream and the 1.12.2
  generation, then the source port of the upstream engine (pinned upstream
  revision `d9cb2d91`, 162 files) validated. Natural-world log mining attempt
  recorded as a failure (nothing collected in 1,352 ticks) and fixed later.
- **2026-09-12** First standalone GTNH bridge milestone on Windows: client and
  server bridges, tokens, observations, aiming, finite input, cancellation,
  screenshots. Time-control audit accepted the whole-tick gate contract with
  GregTech and OpenComputers background-work barriers.
- **Before 2026-09-12** The harness targeted a 1.12.2 pack (the "DJ2"
  generation). Its structure informed this one; none of its code is here.
