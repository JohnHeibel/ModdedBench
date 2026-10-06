# Security

ModdedBench gives a language model control of a game client and lets it write
and deploy code. Read this before running it.

## What is exposed

- **The bridge.** Each bridge (client on port 47223, dedicated server on
  47224) binds to `127.0.0.1` and requires a token that the mod generates at
  startup and writes to `~/.moddedbench/bridge-<port>.token`. Anyone who can
  connect with the token can drive the player, and on the server bridge can
  pause the world. Never bind these ports to another interface, forward them
  off the machine, or publish the token files.
- **The operator console** listens on loopback (port 47300) and each request
  carries a token minted at start. Do not put it behind a proxy.
- **The game server** runs in offline mode (no account check). The container
  publishes its port 25575 on the host's loopback only. Do not publish it
  wider.
- **The spectator mirror** ([docs/MIRROR.md](docs/MIRROR.md)) accepts viewers
  without authentication and listens on loopback by default. Its capture
  files contain private payloads.

## What the agent can do

In a contained run ([docs/CONTAINERS.md](docs/CONTAINERS.md)) the agent has
full access inside its container: it edits, builds and runs any code in its
checkout. Its network is internal. The gateway lets it reach the model
provider's domains and the client bridge, and nothing else. The only host
folder it can write is the outbox, from which the host installs three named
client jars.

That last step matters: **the game client on the host runs Java the agent
wrote, as your user.** The containers bound the agent process, not the client
JVM. Run the client under a separate operating-system account, or on a
machine you can afford to lose, if that is a concern. Every deploy is kept
with its source patch under `.runtime/deploys/` for review.

Outside a contained run (an MCP client on the host using
`harness/mcp/server.py`) there is no isolation at all: the model's tools
include running Python scripts it wrote (`mb_run`), with your permissions.

## Reporting a vulnerability

Open a private security advisory on GitHub (the repository's Security tab,
"Report a vulnerability"). Do not open a public issue for a vulnerability.
Include the commit, how to reproduce it, and what it lets an attacker do.
