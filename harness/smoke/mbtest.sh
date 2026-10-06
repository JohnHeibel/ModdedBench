#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 ModdedBench contributors
# Run a smoke script against the throwaway `mbtest` stack, from a container that shares the server's network.
# The server bridge (47224) listens only on the server container's loopback and is never published; the host
# client's bridge is reached through host.docker.internal. Tokens go in through an env file, never argv.
# Scripts open the server bridge with Kernel(url=bridge_url('server'), token=os.environ['MB_SERVER_BRIDGE_TOKEN']).
#   harness/smoke/mbtest.sh harness/smoke/movement_course.py --case pit_loop --trials 3
set -euo pipefail
export PATH="/c/Program Files/Docker/Docker/resources/bin:$PATH"
export MSYS_NO_PATHCONV=1
repo="$(cd "$(dirname "$0")/../.." && (pwd -W 2>/dev/null || pwd))"
server="${MBTEST_SERVER:-mbtest-server-1}"
image="${MBTEST_IMAGE:-mbtest-agent:latest}"
instance="${MB_CLIENT_INSTANCE:-$APPDATA/PrismLauncher/instances/Modbench-GTNH-Dev/.minecraft}"
envfile="$(mktemp)"; trap 'rm -f "$envfile"' EXIT
{
  echo "MB_BRIDGE_URL=ws://host.docker.internal:47223/ws"
  echo "MB_BRIDGE_TOKEN=$(tr -d '\r\n' < "$HOME/.moddedbench/bridge-47223.token")"
  echo "MB_SERVER_BRIDGE_URL=ws://127.0.0.1:47224/ws"
  echo "MB_SERVER_BRIDGE_TOKEN=$(docker exec "$server" cat /home/mc/.moddedbench/bridge-47224.token | tr -d '\r\n')"
  echo "MB_CLIENT_LOG=/clientlogs/fml-client-latest.log"
  echo "MB_CLIENT_WORK=/clientwork"
  echo "MB_COMMIT=$(git -C "$repo" rev-parse --short HEAD)$(git -C "$repo" diff --quiet HEAD -- mods harness/tools || echo +)"
  echo "MODBENCH_NOTES_DIR=/tmp/mbtest-notes"
  echo "MODBENCH_INTERRUPTS_DIR=/tmp/mbtest-interrupts"
  echo "NO_PROXY=127.0.0.1,localhost,host.docker.internal"
  echo "HTTP_PROXY="; echo "HTTPS_PROXY="; echo "PYTHONUNBUFFERED=1"
} > "$envfile"
docker run --rm --network "container:$server" --env-file "$(cygpath -w "$envfile" 2>/dev/null || echo "$envfile")" \
  -v "$repo:/repo" -v "$(cygpath -w "$instance/logs" 2>/dev/null || echo "$instance/logs"):/clientlogs:ro" -v "$(cygpath -w "$instance/modbench/work" 2>/dev/null || echo "$instance/modbench/work"):/clientwork:ro" -w /repo --entrypoint python3 "$image" "$@"
