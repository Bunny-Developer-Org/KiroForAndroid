# syntax=docker/dockerfile:1
#
# Container image for the bridge (ADR-005 Option C — "adopt as the primary
# distribution form"). Two stages: compile with Gradle, then run on a slim
# JRE that also carries `kiro-cli`, since the bridge spawns it as a child
# process (BridgeConfig.kiroCliPath) rather than talking to it over a
# network.
#
# UNVERIFIED IN THIS REPO: no Docker daemon is available in the environment
# that authored this file, so this image has not been built or run here.
# Build and smoke-test it (`docker build -f bridge/Dockerfile .` from the repo
# root, then `docker run --rm -e KIRO_API_KEY=... -p 127.0.0.1:8765:8765
# <tag>`) before relying on it. See docs/HOSTING.md.

# ---------------------------------------------------------------------------
# Stage 1: build `:bridge:installDist` with Gradle.
#
# This only needs :core (pure Kotlin/JVM, no android.* imports) and the root
# Gradle wrapper/version catalog. It does NOT need an Android SDK: building
# this exact target with ANDROID_HOME unset was verified locally while
# writing this file. Gradle still configures every module in
# settings.gradle.kts (including :app) because configure-on-demand is off,
# but configuration alone does not require the SDK to be present.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon :bridge:installDist

# ---------------------------------------------------------------------------
# Stage 2: slim runtime image with the installed bridge and kiro-cli.
#
# Base is Ubuntu 24.04 "noble" rather than jammy (22.04) or an Alpine/musl
# image: kiro-cli's own installer requires glibc >= 2.34 on x86_64 and
# >= 2.39 on aarch64 (read out of the installer's BUILD-INFO/install.sh while
# writing this file). noble ships glibc 2.39, which is the first Ubuntu LTS
# that clears the aarch64 bar; jammy's 2.35 would silently fail an arm64
# build. musl-based images are not supported by kiro-cli's default (gnu)
# build at all.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-noble AS runtime

# curl/unzip/ca-certificates are what kiro-cli's published installer itself
# needs (see https://cli.kiro.dev/install); they are not required by the
# bridge JVM process at runtime and could be removed after install, but are
# left in place since the same installer is also how you'd upgrade kiro-cli
# in a running container without a full image rebuild.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl unzip \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --create-home --uid 10001 --shell /usr/sbin/nologin bridge
USER bridge
WORKDIR /home/bridge

# Installs kiro-cli (plus its sibling kiro-cli-chat/kiro-cli-term binaries —
# the installer, not a hand-picked single file, because it is not documented
# which of the three the `acp` subcommand shells out to) under
# $HOME/.local/bin, matching the CLI's own published one-line install
# (https://kiro.dev/docs/cli/installation/). --force skips the "replace
# existing install?" prompt, which reads from /dev/tty and would otherwise
# need one during an unattended image build.
#
# This downloads roughly 600 MB compressed / ~1 GB installed — kiro-cli-chat
# alone is ~840 MB. That is the floor for this image's size; see
# docs/HOSTING.md for what that costs on e2-micro's 30 GB disk.
RUN curl -fsSL https://cli.kiro.dev/install | bash -s -- --force
ENV PATH="/home/bridge/.local/bin:${PATH}"

COPY --from=build --chown=bridge:bridge /src/bridge/build/install/bridge /opt/kiro-bridge
ENV PATH="/opt/kiro-bridge/bin:${PATH}"

# BridgeConfig's default state directory (paired-device list + replay log)
# and kiro-cli's own credential store both live under $HOME. Mount a volume
# (or bind mount) over these two so pairing and sign-in survive a container
# restart or image upgrade — losing them means every phone has to re-pair,
# and an OAuth-signed-in host has to sign in again (an API-key-provisioned
# host does not, since KIRO_API_KEY is supplied fresh each run).
VOLUME ["/home/bridge/.kiro-bridge", "/home/bridge/.local/share/kiro-cli"]

EXPOSE 8765

# No CMD: the bridge's own defaults (loopback bind, no TLS) are the safe
# starting point, and BridgeConfig.validate() refuses a non-loopback bind
# without --tls-cert/--tls-key anyway. Pass --api-key or set KIRO_API_KEY at
# `docker run` time; see docs/HOSTING.md for the two hosted deployment paths
# that use this image.
ENTRYPOINT ["bridge"]
