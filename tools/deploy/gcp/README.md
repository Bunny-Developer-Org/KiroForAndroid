# Deploying the bridge to a free GCE VM

What [`deploy.sh`](deploy.sh) does, concretely: builds the bridge locally with
`./gradlew :bridge:installDist` (nothing is compiled on the VM — an e2-micro
has 1 GiB RAM and would struggle with a Gradle/Kotlin build), provisions an
`e2-micro` VM in the Always Free tier, and copies the build output onto it as
a systemd service. Full cost and capability discussion: [docs/HOSTING.md](../../../docs/HOSTING.md).

**Not run against a real project while writing this.** Verified against
`gcloud ... --help` output, not a live deploy — read it before trusting it.

## Prerequisites

- `gcloud` CLI installed and authenticated: `gcloud auth login`
- A GCP project with billing enabled (required to create any Compute Engine
  resource, even a free-tier one) — `gcloud config set project <PROJECT_ID>`
  or pass `PROJECT_ID=<id>` below
- `./gradlew` runnable locally (JDK 17 or 21 — same requirement as the rest
  of this repo; no Android SDK needed for this target)
- A `KIRO_API_KEY` — see [docs/AUTHENTICATION.md §3b](../../../docs/AUTHENTICATION.md#3b-alternative-for-auth-2--api-key-provisioning-verified-2026-09-02).
  This is the only secret this script asks for.

## Run it

```bash
export KIRO_API_KEY=...            # or let the script prompt you (no echo)
export PROJECT_ID=your-gcp-project # or `gcloud config set project ...` first
./deploy.sh
```

Optional overrides (all have defaults): `ZONE` (default `us-central1-a` —
`us-west1`/`us-central1`/`us-east1` are the Always Free–eligible regions),
`INSTANCE_NAME` (default `kiro-bridge`), `KIRO_BRIDGE_PORT` (default `8765`).
Do not change `MACHINE_TYPE` from `e2-micro` unless you intend to leave the
free tier.

The script is safe to re-run: it skips the firewall rule and instance
creation if they already exist. To re-provision, delete the instance first.

## What gets created

| Resource | Why |
|---|---|
| One `e2-micro` VM, `debian-12`, 30 GB standard persistent disk, **no external IP** | The free-tier shape. No public IP is needed because the bridge is never reached by its own public address — see below. |
| One firewall rule, `tcp:22` from `35.235.240.0/20` only | Google's documented [Identity-Aware Proxy](https://cloud.google.com/iap/docs/using-tcp-forwarding) range, so you can SSH in without exposing port 22 to the internet. **No rule is created for the bridge's own port** — it stays bound to loopback. |
| A systemd unit (`kiro-bridge.service`) running the copied `installDist` output | Restarts on failure, reads `KIRO_API_KEY` from a root-owned `0640` env file rather than the unit file itself. |

## Reaching the bridge

The bridge binds to `127.0.0.1` by default and `BridgeConfig.validate()`
**refuses** to bind anything else without a TLS certificate — this script
doesn't fight that, it works with it. Two ways to reach a loopback-only
bridge on a VM with no public IP:

1. **On demand**, from your own machine, via an IAP-tunnelled SSH port
   forward (`deploy.sh` prints the exact command at the end).
2. **Always on, from your phone anywhere** — run
   [`tools/deploy/cloudflare/setup-tunnel.sh`](../cloudflare/) against this
   same VM. This is the pairing [docs/HOSTING.md](../../../docs/HOSTING.md)
   recommends: free compute here, free TLS + a stable hostname there,
   without ever opening an inbound port on this VM for the bridge itself.

## Known limits

- `e2-micro` is a shared-core, 1 GiB machine. It is sized for **one** cloud
  session through the bridge at a time — [ADR-005 A16](../../../docs/adr/ADR-005-bridge-hosting-and-availability.md#7-assumptions-to-verify--extends-adr-001-5-and-adr-004-7)
  (concurrent-session memory) is still unverified, so don't assume headroom
  for several at once.
- `kiro-api-key` is stored as GCE instance metadata, which is readable by
  anyone with Compute viewer access on the project — the same exposure any
  instance metadata has. For a tighter boundary, adapt the startup script to
  pull the key from Secret Manager instead; that's a deliberate scope cut
  here to keep the required setup to "one gcloud project, one API key."
- Deleting and re-running `deploy.sh` does not migrate the previous VM's
  paired-device list or `kiro-cli` credential store — those live on the old
  boot disk. If continuity matters, snapshot `/home/bridge` before deleting.
