# Durable world notes

> **Changed in the Python-seam phase (2026-09-19).** The store now lives in the hot-reloaded
> module `harness/tools/notes.py` (`mbtools_gtnh.notes`); open SQLite handles survive reloads in
> `mbtool.state["notes"]`. Notes also surface **as a side effect**: `mb_status` (session start),
> `mb_obs` on a block/entity/player observation, and every work tool on arrival/completion attach
> at most 5 compact notes under a `"notes"` key, nearest first, only when there are any, and never
> the same (id, revision) twice within 10 minutes unless the player moved 48+ blocks. Work outcomes
> (build/mine/process/resume done; any work failure) are journaled automatically as notes tagged
> `auto`, keyed by kind and 4-block location cell, so repeats update rather than duplicate.
> `.state/notes` remains the default directory (`MODBENCH_NOTES_DIR`).

`mb_notes` reads, searches and captures attachments. `mb_note_write` saves guarded
edits. These are primitives for model-written plans and adapters: notes do not
execute code, control machines, or create region protection.

## Attach and retrieve

Start with `mb_notes("capture", {"kind":"block","pos":[10,64,20]})`.
The reply contains the current server `worldId` and an `attachment`. Save them:

```python
mb_note_write(
    world_id=captured["worldId"], id="base.me-terminal", expected_revision=0,
    operation_id="run-42-note-terminal-1",
    patch={
        "title": "ME terminal adapter",
        "text": "When this system is built, add a routine for requesting batches.",
        "tags": ["adapter", "ae2", "todo"],
        "attachments": [captured["attachment"]],
        "data": {"adapter_source": "tools/mcp/profiles/gtnh/my_ae_adapter.py"},
    },
)
```

Capture kinds:

| Kind | Capture parameters | Meaning |
| --- | --- | --- |
| Block | `pos:[x,y,z]` | Coordinate plus last observed block ID/metadata. |
| Entity | `entityId` from `obs.entities`, or known `uuid` | Durable **server** UUID and last observed position/type/name. |
| Location | Optional `pos:[x,y,z]` | A place; defaults to current player feet. |
| Region | `min:[x,y,z], max:[x,y,z]` | Inclusive cuboid, e.g. a planned processing area. |

Each attachment includes a dimension. A note may have up to 32 attachments.
Regions do not reserve space mechanically: use `memory.protect` separately if
an edit guard is desired. Block annotations remain at their coordinates when
blocks change. Entity notes retain their UUID when an entity moves/unloads.

Useful calls:

```python
mb_notes("search", {"near":"player", "radius":32, "tags":["todo"]})
mb_notes("search", {"query":"terminal adapter", "dimension":None})
mb_notes("search", {"region":{"min":[0,60,0],"max":[30,90,30]}})
mb_notes("search", {"entity_uuid":entity_uuid, "dimension":None})
mb_notes("get", {"id":"base.me-terminal"})
mb_notes("resolve", {"id":"base.me-terminal"})
```

Search terms and tags are case-insensitive and AND-combined. Default search is
the current dimension, excluding archived notes; `dimension:null` searches all
dimensions. Spatial searches require one dimension and match region intersection
or distance to the nearest point of an attachment. An entity's spatial match
uses its **stored lastSeen**, not a live position. Filters apply to the same
attachment. Pagination returns a `nextCursor`; pass it back with the same filters.
Later pages retain the original database snapshot even if notes are edited.
Search returns short excerpts and attachments by default; use `get` for complete
text/custom data, or explicitly request `detail:full`. Long notebooks need not
fill the model's context with every matching note's complete body.

`resolve` only inspects currently loaded/current-dimension attachments and does
not save anything. Entity lookup is limited to 128 blocks of the player and does
not load chunks. `not_observed` is not a declaration of death/destruction. Matching
block ID/metadata cannot detect replacement by an identical block or every internal
machine variant. Notes describe recorded knowledge, not guaranteed current facts.
An adapter can inspect further, capture new observations and explicitly update.

Minecraft 1.7 clients do not synchronize ordinary mob UUIDs reliably. `obs.entity`
therefore gets the persistent UUID from the server over the existing control
connection. A transient entity ID/client UUID is rejected as a durable attachment.
Queries across dimensions can find old entity notes before updating their lastSeen.

## Edits, retries and history

Create with `expected_revision:0`. For edits, read the note and pass its revision.
Only fields in `patch` change; text and arrays replace their fields. For appending,
read and combine before the guarded write. Concurrent/stale edits fail without
changing the database. The supplied world ID prevents reconnecting to another
world from accidentally retargeting an edit.

Use one unique `operation_id` per logical edit. If the response is lost, retry
with exactly the same ID and arguments. The reply replays the **original receipt**,
even if newer edits exist; read again to get current content. Reusing an operation
ID for different arguments fails. No implicit retries or silent last-writer-wins.

Use `patch:{"status":"done"}` to complete a plan, `status:archived` to hide it
from default search, and `status:open` to restore it. There is no hard delete.
`mb_notes("history", {"id":id,"limit":20})` returns previous full revisions and
`nextBeforeRevision`. Old content can be copied into a new guarded revision.

Title limit: 256 characters; text: 32,768; tags: 32; custom JSON `data`: 16 KiB;
whole note: 128 KiB. Read/search pages are bounded to 100 notes (default 20).

## Persistence and deployment

The default directory is **`gtnh/.state/notes`**, outside the disposable `.runtime`.
Set `MODBENCH_NOTES_DIR` to the runner's persistent data volume for deployment.
Each server world UUID gets one SQLite database spanning all its dimensions.
Keep this directory when moving the harness or rebuilding containers; changing
the server world creates a distinct notebook. Notes are local harness data, not
automatically replicated between machines.

SQLite uses WAL and `synchronous=FULL`. Current content, immutable history and
the operation receipt commit in one transaction. A crash cannot commit only part
of that edit. Corruption/unknown versions fail explicitly; they are not replaced
with an empty notebook. `mb_notes("status")` reports the path and integrity check.

Create a consistent standalone backup, including committed WAL contents:

```powershell
python tools/mcp/world_notes.py WORLD-UUID C:/backups/world-notes.sqlite3
```

Use a new destination each time. Do not copy just the main `.sqlite3` file while
it is open; SQLite may have committed data in its WAL. The backup command works
without a running game. For restoration, stop note writers and restore a verified
backup into the configured notes directory under the world UUID filename; preserve
the old database and its WAL/SHM files together before replacement. There is no
automatic off-machine backup service.

The model can compose these APIs from reloadable profile tools. Changes to the
shared `world_notes.py` helper require re-importing it or restarting MCP, like the
shared inventory composition helper. Back up the notebook before schema changes.

## Acceptance

`tools/tests/test_world_notes.py` covers process restart, process death during a
transaction, injected write failure, competing edits, idempotent receipts,
history/archive/restore, corruption refusal, scope checks, spatial retrieval,
stable pagination, and consistent backups. `tools/gtnh/notes_smoke.py --create`
and `--verify` bracket a client restart using a journaled native entity/block
fixture and a separate evidence notebook. Ordinary user notes are untouched.
