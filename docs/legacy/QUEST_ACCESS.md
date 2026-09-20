# Better Questing access

`QuestAccess` reflects GTNH 2.8.4's bundled `BetterQuesting-3.7.15-GTNH.jar`; it adds no Better Questing compile dependency. All reads use `QuestingAPI.getAPI(ApiReference.QUEST_DB/LINE_DB)`, so the adapter follows the API database currently registered by the pack.

Register these client bridge methods from `ClientRuntime`:

* `quest.status` → `status()`
* `quest.sync` → `sync()`
* `quest.search` with `query`, `offset`, `limit` → `search(player, query, offset, limit)`
* `quest.lines` with `query`, `offset`, `limit` → `lines(player, query, offset, limit)`
* `quest.observe` with `questId` → `observe(player, questId)`
* `quest.detect` with UUID `questId` and non-empty integer `taskIds` → `detect(player, questId, taskIds)`
* `quest.select_choice` with UUID `questId`, integer `rewardId`, integer `choiceIndex` → `selectChoice(player, questId, rewardId, choiceIndex)`
* `quest.claim` with UUID `questId`, non-empty integer `rewardIds`, and `Map<Integer,Integer>` choices → `claim(player, questId, rewardIds, choices)`; the compatible string form is `rewardId:choiceIndex`.

Search and lines are paginated at 100 entries. Line results retain Better Questing's native book order and expose localized names/descriptions, quest-line UUIDs, entry layout, and per-player state totals. A quest observation includes line positions, localized metadata, prerequisite state, task config/progress NBT, reward config NBT, and `RewardChoice` option IDs. Each option exposes its native choice index plus exact item registry ID, metadata, NBT and count. NBT is exposed as the mod's SNBT text.

Actions only enqueue `NetQuestAction.requestDetect(Collection<UUID>)`, `NetRewardChoice.requestChoice(UUID,rewardId,choiceIndex)`, and `NetQuestAction.requestClaim(Collection<UUID>)`. The adapter never invokes forced claim, completion setters, choice setters, reward claim methods, or NBT setters. Detect and claim packets are quest-scoped; supplied IDs are validated intent, not a per-task/reward mutation. A claim requires explicit valid selection for every native choice reward.

`quest.sync` queues the normal `NetQuestSync.requestSync(null, true, true)` and
`NetChapterSync.requestSync(null)` query packets. They ask the running server for all quest definitions,
per-player progress, and quest-line definitions. The call does not open the book or change quest state. Its
receipt remains pending while the server is paused or until normal client packet synchronization has completed.

Packet queueing is not acknowledgement. Methods return `serverAcknowledged:false` and a pending receipt. Re-observe after ordinary client synchronization and separately compare inventory before presenting a successful reward receipt.

Owner-run live checks: load a world; call status, paginate a known line and quest title, inspect an unlocked quest, and compare task/reward data with the book. On a disposable normal quest, select the discovered returned choice, re-observe after sync, claim once, then re-observe quest and inventory. Do not use forced claim.
