# History

A short account of how the project got here. The record of every change is
`git log`: commit messages say what failed in the game and what the change
fixes. The dated entries this file used to hold are in its own history
(`git log -p -- docs/HISTORY.md`).

## How it got here

- **Before 2026-09-12.** The harness targeted a different, 1.12.2 modpack. Its
  structure informed this one; none of its code is here.
- **2026-09-12 to 09-18.** First GTNH bridge on Windows: client and server
  bridges, tokens, observations, input, screenshots, and the whole-tick pause
  with its GregTech and OpenComputers barriers. Upstream Baritone (v1.2.19)
  was ported to 1.7.10 for navigation, mining and construction. A supervised
  agent built and ran an Electric Blast Furnace line from supplied materials.
- **2026-09-19.** Restructured into this repository: one coremod (`core`), a
  plain Baritone mod, the whole `harness/tools/` directory reloaded on change,
  world notes, and `PROMPT.md` for quest-book runs. Earlier commits are not in
  this repository.
- **2026-09-20.** Contained runs: server and agent containers, a gateway, a
  constrained client deploy path, the operator console and the stream overlay.
  First runs with the Codex CLI.
- **2026-09-29 to 10-05.** Time stepping and resume-and-act; the spectator
  mirror; background tasks; swimming and currents; one build behaviour with
  click cells; notes as plain files; the movement registry; the game-thread
  budget tests. Unattended runs of 2, 4, 8 and 24 hours were made in this
  period, and their failures drove most of these changes. Many fixes were
  written by the playing agent during a run and merged afterwards.

## Notes for future sessions

`PROMPT.md` asks the playing agent to add a line here for anything a later
session should know exists. One line each, newest first, with the date.
