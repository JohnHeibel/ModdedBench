# GTNH MCP work and quest tools

The GTNH profile exposes the native durable work layer directly:

- `mb_mine` runs quantity mining and measures matching inventory gain.
- `mb_scan` pages through loaded blocks using native selectors.
- `mb_build_preview` validates cells or generated selections and reports fresh
  differences, conflicts and shared inventory allocation.
- `mb_build` executes explicit cells or selections through ordinary player input.
- `mb_selection_build` wraps fill/replace/walls/shell/clear selections.
- `mb_work_status` reads durable intent, progress and the last receipt.
- `mb_work_resume` restarts a stopped attempt from fresh world/inventory state.

Mine/build calls remain open until a terminal receipt. Their `jobId` persists in
the native WorkJournal across a client JVM restart, although active execution does
not. Keep the ID, inspect status after reconnect, correct access/materials, and
resume explicitly. Each resume supplies fresh timeout and protection permissions;
an earlier override is not silently inherited.

`mb_schematic_import` converts canonical JSON and legacy MCEdit files without
Minecraft mutation. `mb_schematic_build` defaults to preview. When imported tile
entity NBT requires an adapter, it returns `executable:false`, the preserved
requirements, and performs no bridge call. Block placement never claims to restore
machine inventory, energy, ownership, configuration or multiblock formation.

Quest wrappers are `mb_quest_status`, `mb_quest_search`, `mb_quest_lines`,
`mb_quest_observe`, `mb_quest_detect`, `mb_quest_select_choice`, and
`mb_quest_claim`. Reads expose stable UUIDs, localized book layout, prerequisites,
task progress/config, rewards and native choice indices. Actions only send Better
Questing's normal packets. Their receipts say `serverAcknowledged:false`; re-observe
choice/progress/claim state and compare inventory deltas before reporting success.
Never retry a claim only because its first response was lost.

These wrappers preserve the bridge's interrupt admission rules. Read-only preview,
scan, work status and quest observations remain suitable for recovery inspection.
Mining, building, resume and quest actions are interactions and remain subject to
pause and interrupt latches.
