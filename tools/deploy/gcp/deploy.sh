#!/usr/bin/env bash
# Provisions a free-tier-eligible GCE VM and runs the bridge on it.
# See tools/deploy/gcp/README.md and docs/HOSTING.md before running this.
#
# NOT RUN AGAINST A REAL PROJECT while writing this: verified against
# `gcloud ... --help` output only. Read it before trusting it with a real
# project, and expect to fix the first typo yourself.
#
# What this deliberately does NOT do: bind the bridge to a public address.
# BridgeConfig.validate() refuses a non-loopback bind without a TLS
# certificate, so the bridge stays on 127.0.0.1 here too. Reach it either
# through an IAP-tunnelled SSH port-forward (this script sets up the
# firewall rule for that) or, for reachability that doesn't need an open SSH
# session, pair this VM with tools/deploy/cloudflare/ — that combination is
# the one docs/HOSTING.md recommends.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# --- Configuration (all overridable by environment variable) ---------------
PROJECT_ID="${PROJECT_ID:-$(gcloud config get-value project 2>/dev/null || true)}"
ZONE="${ZONE:-us-central1-a}"                 # us-central1/us-west1/us-east1 for Always Free
INSTANCE_NAME="${INSTANCE_NAME:-kiro-bridge}"
MACHINE_TYPE="${MACHINE_TYPE:-e2-micro}"      # changing this opts out of the free tier
BRIDGE_PORT="${KIRO_BRIDGE_PORT:-8765}"
FIREWALL_RULE="${FIREWALL_RULE:-allow-iap-ssh-kiro-bridge}"

if [ -z "$PROJECT_ID" ]; then
  echo "No project set. Pass PROJECT_ID=... or run: gcloud config set project <id>" >&2
  exit 1
fi

if [ -z "${KIRO_API_KEY:-}" ]; then
  if [ -t 0 ]; then
    read -rsp "KIRO_API_KEY (from your Kiro account settings, see docs/AUTHENTICATION.md §3b): " KIRO_API_KEY
    echo
  else
    echo "KIRO_API_KEY is not set and this shell has no TTY to prompt on." >&2
    echo "Export it first: export KIRO_API_KEY=..." >&2
    exit 1
  fi
fi
if [ -z "$KIRO_API_KEY" ]; then
  echo "Empty KIRO_API_KEY — refusing to provision a bridge with no way to sign in." >&2
  exit 1
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

echo "==> instance"
if gcloud compute instances describe "$INSTANCE_NAME" --zone="$ZONE" --project="$PROJECT_ID" >/dev/null 2>&1; then
  echo "    $INSTANCE_NAME already exists, skipping create (delete it first to re-provision)"
else
  KEY_FILE="$(mktemp)"
  trap 'rm -f "$KEY_FILE"' EXIT
  printf '%s' "$KIRO_API_KEY" > "$KEY_FILE"

  gcloud compute instances create "$INSTANCE_NAME" \
    --project="$PROJECT_ID" \
    --zone="$ZONE" \
    --machine-type="$MACHINE_TYPE" \
    --image-family=debian-12 \
    --image-project=debian-cloud \
    --boot-disk-size=30GB \
    --boot-disk-type=pd-standard \
    --no-address \
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

The pairing code was printed on the bridge's first start — see it with:
  gcloud compute ssh $INSTANCE_NAME --zone=$ZONE --project=$PROJECT_ID \\
    --tunnel-through-iap --command="sudo journalctl -u kiro-bridge --no-pager | grep -A4 'Pair this bridge'"
EOF
