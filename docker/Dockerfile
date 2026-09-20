# SPDX-License-Identifier: LGPL-3.0-or-later
# Build context is the repository root: docker compose -f docker/compose.yaml build
# No Mojang or GTNH file is baked into an image you could publish by accident: the pack archive is
# mounted at run time, and the Gradle cache (which holds decompiled Minecraft) exists only in `dev`.

# dev: the agent's whole world. Repository, JDK, a warm Gradle cache, Python, Codex.
FROM eclipse-temurin:21-jdk-noble AS dev
RUN apt-get update && apt-get install -y --no-install-recommends git python3 python3-pip curl ca-certificates \
 && curl -fsSL https://deb.nodesource.com/setup_22.x | bash - && apt-get install -y --no-install-recommends nodejs \
 && npm install -g @openai/codex && rm -rf /var/lib/apt/lists/*
RUN useradd -m agent && mkdir -p /work /outbox && chown agent:agent /work /outbox
USER agent
WORKDIR /work/modbench
COPY --chown=agent:agent . .
COPY --chown=agent:agent docker/codex-config.toml /home/agent/.codex/config.toml
RUN pip3 install --user --break-system-packages --no-cache-dir -r harness/mcp/requirements.txt
# The first build downloads and decompiles Minecraft and provisions the Java 8 toolchain; later builds run --offline.
RUN ./gradlew build --console=plain -q && ./gradlew --stop \
 && git config user.name agent && git config user.email agent@modbench.invalid && git tag -f modbench-base
ENV MODBENCH_OUTBOX=/outbox MB_BRIDGE_URL=ws://gateway:47223/ws MB_BRIDGE_TOKEN_FILE=/run/bridge/bridge-47223.token \
    HTTPS_PROXY=http://gateway:3128 HTTP_PROXY=http://gateway:3128 NO_PROXY=gateway PYTHONUNBUFFERED=1
CMD ["sleep", "infinity"]

# server: the pinned pack server. Its ModdedBench jars come from this image on every start, never from a volume.
FROM eclipse-temurin:21-jre-noble AS server
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
CMD ["sh", "-c", "socat TCP-LISTEN:47223,fork,reuseaddr TCP:host.docker.internal:47223 & exec squid -N"]
