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

Tailscale: `python -m harness.mirror --viewers <tailscale-ip>:25580`. Viewers join with Direct Connect (a Prism
`-s` quick-join breaks the FML handshake). Windows Firewall may need an inbound rule for 25580 scoped to
`100.64.0.0/10`.

## Public relay

For an audience on the internet, viewers connect to a relay on a cloud machine instead, and nothing on this PC
listens to the outside. The client connects straight to the server; a handler at the socket end of its
connection appends a copy of the bytes to a file, which a forwarder reads, keeping the same mirror state, and
dials *out* to the relay:

```
agent's client ─────────────────────────────► 127.0.0.1:25575 server
      │ copy (SpectatorTap, one way)
      ▼
~/.moddedbench/tap/*.tap ──► forwarder ──TLS, outbound only──► relay :25591   relay :25565 ◄── viewers
```

```bash
# on the cloud machine (MB_RELAY_TOKEN: a shared secret, 16+ characters, never in the repo)
python -m harness.mirror.relay --cert relay.pem --key relay.key    # --viewers 0.0.0.0:25565 --link 0.0.0.0:25591
# on this PC
python -m harness.mirror.forwarder --relay <relay-host>:25591 --relay-cert relay.pem   # --tap ~/.moddedbench/tap
echo ~/.moddedbench/tap > ~/.moddedbench/spectator-tap    # the client tap's directory; or -Dmodbench.spectatorTap=...
```

A self-signed certificate is fine: the forwarder pins it (`openssl req -x509 -newkey rsa:2048 -nodes -days 3650
-subj /CN=relay -keyout relay.key -out relay.pem`).

| | |
| --- | --- |
| The tap | Off unless configured; read again at each new connection while off. Copies queue on one low-priority thread that appends them to one file per connection (about 2 GiB a day of play; 8 GiB at most, and it leaves 2 GiB of disk free). A full 64 MiB queue, a full disk or any error stops the copy for that connection, never the connection. A new connection deletes the files no running client is writing. It sees the bytes on the socket, so it needs a server without encryption (offline mode). |
| Forwarder restarts | The forwarder reads the newest file from its first byte (about 18 MiB/s, so under two minutes for a full day) and only then shows the session, so it can restart or start late without the client reconnecting. The relay works out what to empty from the frames it sent, since the old session never ended, and viewers stay connected. |
| The link | The relay sends a nonce, the forwarder answers with an HMAC of it under the token, then only the forwarder sends. It never reads past the nonce, and the tap has no return path, so nothing a viewer or the relay does can reach the game. `MB|` payloads are filtered again before anything is sent. |
| Late viewers | The relay keeps the forwarder's latest snapshot and the live frames since; the forwarder sends a fresh snapshot whenever the frames since outgrow the last one (4 MiB at least), so the relay holds at most about twice a snapshot. |
| Agent reconnects | Viewers stay in the frozen world with a chat notice. The forwarder sends the frames that empty it (chunk unloads, entity removals, scoreboard and tab list entries); with the next session the relay sends those and the new world without its login prelude, so viewers stay connected and see no loading screen. A Respawn is used only when the dimension differs. A dropped link is handled the same way. |
| Relay restarts | The relay mirrors the feed from its latest snapshot to `--state/feed.bin` and comes back with the last world. |
| Viewers | Chat goes to the other viewers only (one line per 1.5 s, 100 characters, formatting codes removed). `/invsee` (or `/inv`) opens the agent's inventory as a read-only chest that updates live: main inventory, hotbar, then armor. Clicks are undone. No other inventory can be opened. `/help` lists the commands, and tab completion offers them. Nothing a viewer sends goes past the relay. |

Not yet: online-mode viewer login, and the overlay/quest views.
