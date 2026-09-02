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
if [ ! -x /home/bridge/.local/bin/kiro-cli ]; then
  runuser -l bridge -c 'curl -fsSL https://cli.kiro.dev/install | bash -s -- --force'
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

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload

echo "=== startup script done: $(date -u --iso-8601=seconds) ==="
