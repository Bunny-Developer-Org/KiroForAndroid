#!/usr/bin/env bash
# Provisions a GCE VM (free-tier-eligible machine and disk) and runs the
# bridge on it. Read tools/deploy/gcp/README.md and docs/HOSTING.md first.
#
# Run against a real project for the first time on 2026-09-03. That run
# provisioned the VM correctly and then failed at `systemctl enable`, because
# this script used to wait only for SSH to answer — see the comment above the
# startup-script wait below. Fixed. Everything past "the bridge process
# starts" is still unverified.
#
# THIS IS NOT A $0 DEPLOYMENT. The machine type and disk sit inside Compute
# Engine's Always Free allowance, but the VM also needs an outbound internet
# path: startup-script.sh installs a JRE from packages.adoptium.net and
# kiro-cli from cli.kiro.dev, and cloudflared (the Option B path) has to
# reach Cloudflare's edge. A VM with neither an external address nor a Cloud
# NAT gateway has no such path and cannot boot into a working state. So this
# script attaches an ephemeral external IPv4 (~$3/month, NOT covered by the
# free tier) rather than creating a Cloud NAT gateway (~$32/month for the
# same outcome). Set EXTERNAL_IP=none if your subnet already has NAT.
# docs/HOSTING.md §3 has the cost table and how approximate those rates are.
#
# This script no longer provisions a KIRO_API_KEY: that mode is rejected by
# Kiro's cloud-session surface (two keys from a Pro+ account, 2026-09-03; see
# docs/AUTHENTICATION.md §3b). It provisions an unauthenticated bridge and
# tells you to run `kiro-cli login` on the VM afterwards. That path is verified
# end to end — a bridge signed in this way answers session/list with the
# account's real sessions. Headless sign-in has three separate traps; the
# working procedure is in AUTHENTICATION.md.
#
# What this deliberately does NOT do: bind the bridge to a public address.
# BridgeConfig.validate() refuses a non-loopback bind without a TLS
# certificate, so the bridge stays on 127.0.0.1 here too — the external
# address exists for outbound traffic only, and no ingress rule is created
# for the bridge's port. Reach it either through an IAP-tunnelled SSH
# port-forward (this script sets up the firewall rule for that) or, for
# reachability that doesn't need an open SSH session, pair this VM with
# tools/deploy/cloudflare/ — the combination docs/HOSTING.md recommends.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# --- Configuration (all overridable by environment variable) ---------------
PROJECT_ID="${PROJECT_ID:-$(gcloud config get-value project 2>/dev/null || true)}"
ZONE="${ZONE:-us-central1-a}"                 # us-central1/us-west1/us-east1 for Always Free
INSTANCE_NAME="${INSTANCE_NAME:-kiro-bridge}"
MACHINE_TYPE="${MACHINE_TYPE:-e2-micro}"      # changing this opts out of the free tier
EXTERNAL_IP="${EXTERNAL_IP:-ephemeral}"       # 'none' only if the subnet has Cloud NAT
BRIDGE_PORT="${KIRO_BRIDGE_PORT:-8765}"
FIREWALL_RULE="${FIREWALL_RULE:-allow-iap-ssh-kiro-bridge}"

if [ -z "$PROJECT_ID" ]; then
  echo "No project set. Pass PROJECT_ID=... or run: gcloud config set project <id>" >&2
  exit 1
fi

# KIRO_API_KEY is now OPT-IN and discouraged. It used to be required here, on
# the strength of AUTHENTICATION.md §3b's claim that the mode reaches cloud
# sessions. That was refuted on 2026-09-03: two keys from a Kiro Pro+ account
# were both refused (`UnauthorizedException`) for session/list, session/new and
# sourceProviders/list, while the same account's interactive login worked
# against the same server. A key-provisioned bridge pairs with the phone and
# then cannot create a session — which is the entire point of the app.
#
# So the default is now no key at all, and `kiro-cli login` on the VM after
# this script finishes. That step needs a browser and cannot be automated
# (A6: the provider picker is a TUI), so it stays manual and this script tells
# you exactly what to run.
if [ -n "${KIRO_API_KEY:-}" ]; then
  echo "WARNING: KIRO_API_KEY is set, so this bridge will authenticate as that key." >&2
  echo "         Cloud sessions were refused for API-key identities on 2026-09-03" >&2
  echo "         (docs/AUTHENTICATION.md §3b). The variable also overrides an" >&2
  echo "         interactive login, so a later 'kiro-cli login' will NOT take" >&2
  echo "         effect while it is set. Unset it unless you know you want this." >&2
  echo >&2
fi

echo "==> project=$PROJECT_ID zone=$ZONE instance=$INSTANCE_NAME machine=$MACHINE_TYPE"

echo "==> enabling required APIs (no-op if already enabled)"
gcloud services enable compute.googleapis.com iap.googleapis.com \
  --project="$PROJECT_ID"

echo "==> building the bridge locally (nothing is compiled on the VM)"
"$ROOT/gradlew" -p "$ROOT" :bridge:installDist -q
DIST_DIR="$ROOT/bridge/build/install/bridge"
[ -d "$DIST_DIR" ] || { echo "build output missing at $DIST_DIR" >&2; exit 1; }

echo "==> firewall: IAP-range SSH only (no port is opened for the bridge itself)"
if ! gcloud compute firewall-rules describe "$FIREWALL_RULE" --project="$PROJECT_ID" >/dev/null 2>&1; then
  gcloud compute firewall-rules create "$FIREWALL_RULE" \
    --project="$PROJECT_ID" \
    --direction=INGRESS \
    --action=ALLOW \
    --rules=tcp:22 \
    --source-ranges=35.235.240.0/20 \
    --target-tags=kiro-bridge \
    --description="SSH via Identity-Aware Proxy only, for kiro-bridge VMs"
else
  echo "    $FIREWALL_RULE already exists, skipping"
fi

echo "==> outbound internet path"
if [ "$EXTERNAL_IP" = "none" ]; then
  ADDRESS_ARGS=(--no-address)
  echo "    EXTERNAL_IP=none — creating the VM with no external address."
  echo "    This only works if the subnet already has a Cloud NAT gateway."
  echo "    Without one the VM cannot reach packages.adoptium.net or"
  echo "    cli.kiro.dev and startup-script.sh will fail on first boot."
else
  # No --no-address: gcloud's default is an ephemeral external IPv4.
  ADDRESS_ARGS=()
  echo "    attaching an ephemeral external IPv4 (~\$3/month, not free-tier)."
  echo "    It is there so the VM can reach the internet outbound; no ingress"
  echo "    rule is created for the bridge, which stays bound to loopback."
  echo "    NOTE: on the 'default' VPC, GCP's own default-allow-ssh rule"
  echo "    permits tcp:22 from 0.0.0.0/0 to any instance with an external"
  echo "    address. Narrow or remove that rule if you don't want it."
  echo "    Set EXTERNAL_IP=none instead if the subnet already has Cloud NAT."
fi

echo "==> instance"
if gcloud compute instances describe "$INSTANCE_NAME" --zone="$ZONE" --project="$PROJECT_ID" >/dev/null 2>&1; then
  echo "    $INSTANCE_NAME already exists, skipping create (delete it first to re-provision)"
else
  KEY_FILE="$(mktemp)"
  trap 'rm -f "$KEY_FILE"' EXIT
  printf '%s' "${KIRO_API_KEY:-}" > "$KEY_FILE"

  gcloud compute instances create "$INSTANCE_NAME" \
    --project="$PROJECT_ID" \
    --zone="$ZONE" \
    --machine-type="$MACHINE_TYPE" \
    --image-family=debian-12 \
    --image-project=debian-cloud \
    --boot-disk-size=30GB \
    --boot-disk-type=pd-standard \
    ${ADDRESS_ARGS[@]+"${ADDRESS_ARGS[@]}"} \
    --tags=kiro-bridge \
    --metadata-from-file="startup-script=$SCRIPT_DIR/startup-script.sh,kiro-api-key=$KEY_FILE"

  rm -f "$KEY_FILE"
  trap - EXIT
fi

echo "==> waiting for SSH (via IAP tunnel) to come up — this can take a minute or two on first boot"
for _ in $(seq 1 30); do
  if gcloud compute ssh "$INSTANCE_NAME" --zone="$ZONE" --project="$PROJECT_ID" \
       --tunnel-through-iap --command="true" >/dev/null 2>&1; then
    break
  fi
  sleep 10
done

# SSH answering does NOT mean the VM is ready: sshd comes up long before
# google-startup-scripts.service has finished installing the JRE, kiro-cli
# (~1 GB) and — the part that matters here — /etc/systemd/system/
# kiro-bridge.service. Racing it produces exactly one symptom, seen for real
# on 2026-09-03: "Failed to enable unit: Unit file kiro-bridge.service does
# not exist." So wait for the unit itself, and surface the startup log if it
# never shows up rather than failing with that riddle.
echo "==> waiting for the startup script to finish (installs a JRE and ~1 GB of kiro-cli)"
STARTUP_OK=0
for _ in $(seq 1 60); do
  if gcloud compute ssh "$INSTANCE_NAME" --zone="$ZONE" --project="$PROJECT_ID" \
       --tunnel-through-iap --command="test -f /etc/systemd/system/kiro-bridge.service" \
       >/dev/null 2>&1; then
    STARTUP_OK=1
    break
  fi
  sleep 15
done
if [ "$STARTUP_OK" -ne 1 ]; then
  echo "The startup script never produced kiro-bridge.service. Its log:" >&2
  gcloud compute ssh "$INSTANCE_NAME" --zone="$ZONE" --project="$PROJECT_ID" \
    --tunnel-through-iap --command="sudo tail -30 /var/log/kiro-bridge-startup.log" >&2 || true
  exit 1
fi

echo "==> copying the built bridge onto the VM"
gcloud compute scp --recurse --zone="$ZONE" --project="$PROJECT_ID" --tunnel-through-iap \
  "$DIST_DIR" "$INSTANCE_NAME":/tmp/kiro-bridge-dist

echo "==> installing and starting the kiro-bridge service"
gcloud compute ssh "$INSTANCE_NAME" --zone="$ZONE" --project="$PROJECT_ID" --tunnel-through-iap --command="
  sudo rm -rf /opt/kiro-bridge/bin /opt/kiro-bridge/lib &&
  sudo cp -r /tmp/kiro-bridge-dist/* /opt/kiro-bridge/ &&
  sudo chown -R bridge:bridge /opt/kiro-bridge &&
  rm -rf /tmp/kiro-bridge-dist &&
  sudo systemctl enable --now kiro-bridge &&
  sleep 2 &&
  sudo journalctl -u kiro-bridge --no-pager -n 20
"

cat <<EOF

==> Done. The bridge is running on $INSTANCE_NAME, bound to loopback only.

Reach it one of two ways:

  1. On-demand, from this machine:
       gcloud compute ssh $INSTANCE_NAME --zone=$ZONE --project=$PROJECT_ID \\
         --tunnel-through-iap -- -L $BRIDGE_PORT:localhost:$BRIDGE_PORT -N
     then point the app at ws://127.0.0.1:$BRIDGE_PORT/acp on a device that can
     reach this machine (same trick as tools/run-on-device.sh's adb reverse).

  2. Always reachable, from anywhere: run tools/deploy/cloudflare/setup-tunnel.sh
     against this same VM. See docs/HOSTING.md for the combined recipe.

FIRST, SIGN THE BRIDGE IN. Until you do, it can pair with the phone but every
cloud call will come back "Authentication required or access denied":

  gcloud compute ssh $INSTANCE_NAME --zone=$ZONE --project=$PROJECT_ID \\
    --tunnel-through-iap --command="sudo runuser -u bridge -- env HOME=/home/bridge \\
      /home/bridge/.local/bin/kiro-cli login"

It prints a URL to open in your own browser. Then restart the bridge so it
picks the credential up:

  gcloud compute ssh $INSTANCE_NAME --zone=$ZONE --project=$PROJECT_ID \\
    --tunnel-through-iap --command="sudo systemctl restart kiro-bridge"

The pairing code was printed on the bridge's first start — see it with:
  gcloud compute ssh $INSTANCE_NAME --zone=$ZONE --project=$PROJECT_ID \\
    --tunnel-through-iap --command="sudo journalctl -u kiro-bridge --no-pager | grep -A4 'Pair this bridge'"
EOF
