# SPDX-License-Identifier: LGPL-3.0-or-later
# Build context is the repository root: docker compose -f docker/compose.yaml build
# No Mojang or GTNH file is baked into an image you could publish by accident: the pack archive is
# mounted at run time, and the Gradle cache (which holds decompiled Minecraft) exists only in `dev`.

# dev: the agent's whole world. Repository, JDK 25 (RetroFuturaGradle needs it to run; it provisions 21 and 8 itself), a warm Gradle cache, Python, Codex.
FROM eclipse-temurin:25-jdk-noble AS dev
RUN apt-get update && apt-get install -y --no-install-recommends git python3 python3-pip curl ca-certificates procps \
 && curl -fsSL https://deb.nodesource.com/setup_22.x | bash - && apt-get install -y --no-install-recommends nodejs \
 && npm install -g @openai/codex && rm -rf /var/lib/apt/lists/*
RUN useradd -m agent && mkdir -p /work /outbox && chown agent:agent /work /outbox
USER agent
WORKDIR /work/modbench
# ~/.codex is a volume (it keeps the login), so the config and the heartbeat (Codex reads ~/.codex/AGENTS.md in every session)
# are copied in from root-owned files at every start rather than frozen at first use.
COPY docker/codex-config.toml AGENTS.md /opt/
RUN mkdir -p /home/agent/.codex
COPY --chown=agent:agent harness/mcp/requirements.txt /tmp/requirements.txt
RUN pip3 install --user --break-system-packages --no-cache-dir -r /tmp/requirements.txt
# Warm layer: with the build scripts alone, Gradle provisions the toolchains and downloads and decompiles
# Minecraft. It is slow and only reruns when a build script changes, not on every commit.
COPY --chown=agent:agent gradlew settings.gradle build.gradle gradle.properties pack.lock.json LICENSE ./
COPY --chown=agent:agent gradle gradle
COPY --chown=agent:agent mods/api/build.gradle mods/api/
COPY --chown=agent:agent mods/core/build.gradle mods/core/
RUN mkdir -p mods/baritone mods/client mods/server && ./gradlew build --console=plain -q && ./gradlew --stop
COPY --chown=agent:agent . .
RUN ./gradlew build --console=plain -q && ./gradlew --stop \
 && git config user.name agent && git config user.email agent@modbench.invalid && git tag -f modbench-base
ENV MODBENCH_OUTBOX=/outbox MB_BRIDGE_URL=ws://gateway:47223/ws MB_BRIDGE_TOKEN_FILE=/run/bridge/bridge-47223.token \
    MB_WIKI_DB=/wiki/gtnh-wiki.sqlite MB_BRIEF=/brief/PROMPT.md \
    HTTPS_PROXY=http://gateway:3128 HTTP_PROXY=http://gateway:3128 NO_PROXY=gateway PYTHONUNBUFFERED=1
CMD ["sh", "-c", "cp /opt/codex-config.toml /home/agent/.codex/config.toml && cp /opt/AGENTS.md /home/agent/.codex/AGENTS.md && exec sleep infinity"]

# server: the pinned pack server. Its ModdedBench jars come from this image on every start, never from a volume.
FROM eclipse-temurin:25-jre-noble AS server
RUN apt-get update && apt-get install -y --no-install-recommends python3 && rm -rf /var/lib/apt/lists/* \
 && useradd -m mc && mkdir /data && chown mc:mc /data
COPY --from=dev /work/modbench/mods/server/build/libs/modbench-server-0.1.0.jar /opt/modbench/modbench-server.jar
COPY --from=dev /work/modbench/mods/core/build/libs/modbench-core-0.1.0.jar /opt/modbench/modbench-core.jar
COPY harness/launcher/runtime.py pack.lock.json docker/server_entry.py /opt/modbench/
USER mc
WORKDIR /data
ENTRYPOINT ["python3", "/opt/modbench/server_entry.py"]

# gateway: the agent's only route out. An allowlisting HTTP proxy and one TCP forward to the client bridge.
FROM alpine:3.20 AS gateway
RUN apk add --no-cache squid socat
COPY docker/squid.conf /etc/squid/squid.conf
# squid drops privileges and then cannot open /dev/stdout, so it logs to a file that is tailed into the container log.
CMD ["sh", "-c", "socat TCP-LISTEN:47223,fork,reuseaddr TCP:host.docker.internal:47223 & touch /var/log/squid/access.log && chown squid /var/log/squid/access.log && tail -F /var/log/squid/access.log & exec squid -N"]
