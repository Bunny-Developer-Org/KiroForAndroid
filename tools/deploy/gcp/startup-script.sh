#!/bin/bash
# GCE guest startup script for the kiro-bridge VM (tools/deploy/gcp/deploy.sh
# passes this via --metadata-from-file=startup-script=...). It prepares the
# OS — swap, a JRE, kiro-cli, a systemd unit — but does NOT start the bridge:
# the compiled bridge (bridge/build/install/bridge, built locally with
# `./gradlew :bridge:installDist` so nothing is compiled on a 1 GiB VM) is
# copied up and the service started by deploy.sh itself, after this script
# has finished and SSH is reachable. See docs/HOSTING.md.
#
# Runs as root, on every boot (GCE re-runs startup-script each start), so
# every step is written to be safe to repeat.
set -euo pipefail
exec > >(tee -a /var/log/kiro-bridge-startup.log) 2>&1
echo "=== kiro-bridge startup script: $(date -u --iso-8601=seconds) ==="

# --- 1. Swap ---------------------------------------------------------------
# e2-micro has 1 GiB RAM (ADR-005 A16 flags multi-session memory as
# unverified even on bigger hardware) and kiro-cli's installer alone pulls in
# a ~1 GB payload. A small swapfile is cheap insurance against an OOM kill,
# not a performance plan.
if [ ! -f /swapfile ]; then
  fallocate -l 1G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

# --- 2. A JRE ----------------------------------------------------------------
# Debian 12 "bookworm" (this VM's image) does not reliably carry a
# temurin/openjdk-21 package across its whole support lifecycle, so this uses
# Eclipse Adoptium's own apt repository rather than guessing a Debian package
# name — the documented install method at https://adoptium.net/installation/linux/.
if ! command -v java >/dev/null 2>&1 || ! java -version 2>&1 | grep -q '"21'; then
  apt-get update
  apt-get install -y --no-install-recommends wget gpg apt-transport-https ca-certificates unzip curl
  mkdir -p /etc/apt/keyrings
  wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public \
    | gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg
  echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(awk -F= '/^VERSION_CODENAME/{print $2}' /etc/os-release) main" \
    > /etc/apt/sources.list.d/adoptium.list
  apt-get update
  apt-get install -y --no-install-recommends temurin-21-jre
fi

# --- 3. The bridge user and directories ------------------------------------
if ! id bridge >/dev/null 2>&1; then
  useradd --create-home --shell /usr/sbin/nologin bridge
fi
install -d -o bridge -g bridge -m 0755 /opt/kiro-bridge
install -d -o bridge -g bridge -m 0700 /home/bridge/.kiro-bridge

# --- 4. kiro-cli -------------------------------------------------------------
# Same installer the Dockerfile uses, run as the `bridge` user so it lands
# under /home/bridge/.local/bin — where BridgeConfig's default
# `kiro-cli` (resolved via PATH) will find it once the systemd unit sets
# PATH accordingly. --force skips the "replace existing install?" prompt.
#
# NOT `runuser -l bridge`: -l starts a *login shell*, and the bridge user's
# shell is deliberately /usr/sbin/nologin, so that form dies with "This
# account is currently not available" — and, under `set -e`, takes the whole
# startup script down with it before the systemd unit is ever written.
# Verified the hard way on a real VM, 2026-09-03. `runuser -u ... --` runs
# the command directly instead of through the account's shell, so nologin
# stays intact; HOME is set explicitly because that is what -l was providing.
if [ ! -x /home/bridge/.local/bin/kiro-cli ]; then
  runuser -u bridge -- env HOME=/home/bridge \
    bash -c 'curl -fsSL https://cli.kiro.dev/install | bash -s -- --force'
fi

# --- 5. The secret: KIRO_API_KEY from instance metadata ---------------------
# Passed as its own metadata *attribute* (not baked into this script's text)
# so it never appears in `gcloud compute instances describe` output alongside
# the startup-script body, and is written straight to a root-only-readable
# env file rather than exported into this script's own environment or logged
# by the `tee` above.
umask 077
curl -sf -H "Metadata-Flavor: Google" \
  "http://metadata.google.internal/computeMetadata/v1/instance/attributes/kiro-api-key" \
  > /etc/kiro-bridge.env.tmp
{
  printf 'KIRO_API_KEY='
  cat /etc/kiro-bridge.env.tmp
  printf '\n'
} > /etc/kiro-bridge.env
rm -f /etc/kiro-bridge.env.tmp
chown root:bridge /etc/kiro-bridge.env
chmod 640 /etc/kiro-bridge.env
# Put the umask back: without this, the tight 077 above leaks into step 6 and
# the systemd unit lands 0600 root:root. systemd still reads it (it is root),
# so this is untidiness rather than breakage — but it makes the unit invisible
# to `grep` for anyone debugging without sudo, which cost time on 2026-09-03.
umask 022

# --- 6. systemd unit ---------------------------------------------------------
# Not started here — /opt/kiro-bridge/bin/bridge does not exist yet.
# deploy.sh copies the build output and runs `systemctl enable --now` once
# this script has finished and the VM is reachable over SSH.
cat > /etc/systemd/system/kiro-bridge.service <<'UNIT'
[Unit]
Description=Kiro bridge (relays a phone to kiro-cli over WebSocket)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=bridge
Group=bridge
WorkingDirectory=/home/bridge
Environment=HOME=/home/bridge
Environment=PATH=/home/bridge/.local/bin:/usr/bin:/bin
EnvironmentFile=-/etc/kiro-bridge.env
ExecStart=/opt/kiro-bridge/bin/bridge --state-dir /home/bridge/.kiro-bridge
Restart=on-failure
RestartSec=5
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/home/bridge
# PrivateTmp is load-bearing, not hardening garnish. BridgeConfig defaults
# workingDirectory to \$java.io.tmpdir/kiro-bridge-workspace and CliSupervisor
# mkdirs() it — but ProtectSystem=strict makes /tmp read-only unless it is
# listed in ReadWritePaths, so that mkdirs() silently returns false and the
# spawn of kiro-cli then dies with a thoroughly misleading
#   Cannot run program "kiro-cli" ... Exec failed, error: 2
# which reads as "the binary is missing" when the binary is fine and it is the
# *working directory* that does not exist. Seen for real on 2026-09-03.
# PrivateTmp gives the service its own writable /tmp; the workspace is meant
# to be scratch space, so losing it on restart is correct behaviour.
PrivateTmp=true

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload

# --- 7. cloudflared: installed, but neither configured nor started ----------
# The all-cloud shape in docs/HOSTING.md runs cloudflared on THIS VM, next to
# the bridge, so that nothing has to keep running on your own computer. That
# needs the binary present here. It does not need it configured — that is
# tools/deploy/cloudflare/setup-tunnel.sh, run later and interactively,
# because `cloudflared tunnel login` has to authenticate against your own
# Cloudflare account.
#
# /usr/local/bin is not a free choice: it is the path already hardcoded in
# tools/deploy/cloudflare/cloudflared.service. The URL below is Cloudflare's
# documented direct-binary download, and it was confirmed working on a real
# VM on 2026-09-03. A failure stays non-fatal on purpose: the IAP/SSH path
# does not need cloudflared at all.
if ! id cloudflared >/dev/null 2>&1; then
  useradd --create-home --shell /usr/sbin/nologin cloudflared
fi
if [ ! -x /usr/local/bin/cloudflared ]; then
  if curl -fsSL -o /usr/local/bin/cloudflared.tmp \
       https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64; then
    chmod 0755 /usr/local/bin/cloudflared.tmp
    mv /usr/local/bin/cloudflared.tmp /usr/local/bin/cloudflared
  else
    rm -f /usr/local/bin/cloudflared.tmp
    echo "WARNING: could not download cloudflared. The IAP/SSH path is unaffected;"
    echo "         install it by hand before using the Cloudflare Tunnel path."
  fi
fi

echo "=== startup script done: $(date -u --iso-8601=seconds) ==="
