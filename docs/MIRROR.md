# Spectator mirror

A read-only copy of what the agent's client sees, for people with a stock GTNH 2.8.4 client. Viewers join a
separate port and fly around; they never reach the game server. `harness/mirror` is a Python proxy on the host:

```
agent's client ──► 127.0.0.1:25576 ──(byte pipe)──► 127.0.0.1:25575 server
                         │ copy
                         ▼
                   mirror state ──► 127.0.0.1:25580 ◄── viewers
```

## Running it

```bash
python -m harness.mirror    # defaults: --listen 127.0.0.1:25576 --upstream 127.0.0.1:25575 --viewers 127.0.0.1:25580
```

Start it before the client joins, and give the launcher (`runtime.py`, or the console that runs it)
`MB_GAME_PORT=25576` so the client's `sys.connect` goes through the mirror; unset, the client bypasses it.
Viewers add `127.0.0.1:25580` as a server in a GTNH 2.8.4 client and join with any name except the agent's. `.runtime/mirror/stats.json` (every 10 s) counts frames and bytes per packet
id and per custom-payload channel in each direction, and reports the mirror's state, caps and last errors.
`--capture FILE` appends every host frame (private `MB|` payloads included; keep it local) and `--replay FILE`
serves viewers from such a capture without a host.

## Contract

| | |
| --- | --- |
| Agent path | Two threads per connection forward bytes as they arrive, then hand a copy to the mirror. A parse error, or a copy more than 64 MiB behind, turns mirroring off for that session (viewers are told, the error is in stats); the pipe is untouched. Only the mirror process itself dying drops the agent's connection. |
| Viewers | Offline login, adventure mode, invulnerable and flying, at the agent's position on join and after dimension changes. Everything they send is discarded. Keepalives every 5 s. Slow viewers (64 MiB queued) are dropped; at most `--max-viewers` (8). |
| Hidden from viewers | Keepalives, health, XP, held-item, windows, sign editor, stats, abilities, tab completion, gamemode/credits game states, FML `OpenGui`, every `MB|` payload. |
| The agent | An ordinary player entity (the agent's name and id) driven from the client's movement, swing and inventory packets. |
| Late viewers | Login prelude (FML handshake through JoinGame), then chunks and block updates for chunks loaded now, entities at their current positions, latest time, weather, player list, scoreboard, other mod payloads in order (8 MiB cap, oldest dropped first, login-time ones kept), the last 20 chat lines. |

Chunk and block packets are relayed as received, never re-encoded (NotEnoughIDs changes their payloads). Payloads
that refer to blocks (GregTech ore materials) cannot be tied to a chunk, so they share the capped log.

## Security

Offline mode has no authentication: anyone who reaches the viewer port can join under any name. Viewers bind to
`127.0.0.1` by default. Exposing the port beyond this machine needs the owner's explicit OK and a private network
such as Tailscale, never a public tunnel.
