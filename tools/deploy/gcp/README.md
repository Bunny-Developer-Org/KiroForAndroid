# Deploying the bridge to a free GCE VM

What [`deploy.sh`](deploy.sh) does, concretely: builds the bridge locally with
`./gradlew :bridge:installDist` (nothing is compiled on the VM — an e2-micro
has 1 GiB RAM and would struggle with a Gradle/Kotlin build), provisions an
`e2-micro` VM in the Always Free tier, and copies the build output onto it as
a systemd service. Full cost and capability discussion: [docs/HOSTING.md](../../../docs/HOSTING.md).

**Run for real on 2026-09-03, and fixed four times as a result.** The first
real run provisioned the VM correctly and then hit, in order: `runuser -l`
against a `nologin` user (killed the startup script before it wrote the
systemd unit); this script racing that startup script by waiting only for
SSH; `ProtectSystem=strict` leaving `/tmp` read-only so the bridge could not
create its own working directory (which surfaces as a misleading "Cannot run
program kiro-cli"); and a leaked `umask 077` writing the unit file `0600`.
All four are fixed, and the bridge now starts, stays up and prints its
pairing banner. Still unverified: a phone completing a real session through
it, and `../cloudflare/setup-tunnel.sh`, which has never been run.

**It is also not $0.** The VM and disk are free, but the instance needs an
outbound internet path to install anything, and the cheap way to give it one
— an ephemeral external IPv4, which `deploy.sh` attaches by default — costs
about **$3/month** and is not part of the free tier. See
["Outbound internet"](#outbound-internet-the-vm-needs-a-path-out-3month)
below.

## Signing the bridge in

`deploy.sh` no longer provisions a `KIRO_API_KEY`; that mode is refuted for
cloud sessions ([docs/AUTHENTICATION.md §3b](../../../docs/AUTHENTICATION.md)).
The bridge instead expects an interactive `kiro-cli login` on the VM, which the
script prints instructions for when it finishes. A bridge signed in this way was
verified on 2026-09-03 to reach the account's real cloud sessions.

If `KIRO_API_KEY` happens to be set in your shell, the script warns and carries
on — but be aware the variable overrides an interactive login, so a later
`kiro-cli login` will not take effect while it is set.

Headless sign-in is more awkward than it looks: the device flow silently picks
the wrong identity, and the browser flow needs an `xdg-open` shim plus a
forwarded callback port. AUTHENTICATION.md has the working procedure.

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
`INSTANCE_NAME` (default `kiro-bridge`), `KIRO_BRIDGE_PORT` (default `8765`),
`EXTERNAL_IP` (default `ephemeral`; set it to `none` **only** if the subnet
already has a Cloud NAT gateway — see below). Do not change `MACHINE_TYPE`
from `e2-micro` unless you intend to leave the free tier.

The script is safe to re-run: it skips the firewall rule and instance
creation if they already exist. To re-provision, delete the instance first.

## What gets created

| Resource | Why |
|---|---|
| One `e2-micro` VM, `debian-12`, 30 GB standard persistent disk | The free-tier shape — the VM and disk really are $0. |
| An **ephemeral external IPv4** on that VM (**~$3/month, not free-tier**) | The VM has to reach the internet outbound to install anything, and this repo creates no Cloud NAT gateway. It is not there for inbound traffic: the bridge stays bound to loopback and no rule opens its port. Set `EXTERNAL_IP=none` to skip it if the subnet already has Cloud NAT. See below. |
| One firewall rule, `tcp:22` from `35.235.240.0/20` only | Google's documented [Identity-Aware Proxy](https://cloud.google.com/iap/docs/using-tcp-forwarding) range, so you can SSH in without exposing port 22 to the internet. **No rule is created for the bridge's own port** — it stays bound to loopback. |
| A systemd unit (`kiro-bridge.service`) running the copied `installDist` output | Restarts on failure, reads `KIRO_API_KEY` from a root-owned `0640` env file rather than the unit file itself. |
| A `cloudflared` user and the `cloudflared` binary at `/usr/local/bin` | So the all-cloud path (Cloudflare Tunnel running **on this VM**, not on your laptop) is a short step away. Installed, not configured and not started — [`../cloudflare/setup-tunnel.sh`](../cloudflare/) does that, run here over SSH. The download is unverified and non-fatal; if it fails the VM still boots. |

## Outbound internet: the VM needs a path out (~$3/month)

[`startup-script.sh`](startup-script.sh) installs everything from the
network: Adoptium's GPG key and apt repo from `packages.adoptium.net` for the
JRE, and `https://cli.kiro.dev/install` piped to bash for `kiro-cli`.
`cloudflared` (the Option B path below) also needs outbound reach to
Cloudflare's edge. A GCE VM with neither an external address nor a Cloud NAT
gateway cannot do any of that — it has no internet path at all, in either
direction.

This repo does not create a Cloud NAT gateway, so `deploy.sh` gives the
instance an **ephemeral external IPv4** instead: roughly $0.004/hour, about
**$3/month**, and explicitly *not* part of the Always Free tier. The two ways
to close the gap, and why this one was picked:

| Option | Approx. cost | |
|---|---|---|
| Ephemeral external IPv4 (`EXTERNAL_IP=ephemeral`, the default) | **~$3/month** | What `deploy.sh` does. |
| Cloud NAT gateway for the subnet (`EXTERNAL_IP=none`) | **~$32/month** per gateway + ~$0.045/GB processed | Same outcome here for about 10x the price. Worth it only if you already run one — the script will not create it for you. |

**What the external address does and doesn't expose.** It exists for
outbound traffic. The bridge is unaffected: it stays bound to `127.0.0.1`,
and the only ingress rule *this script* creates is `tcp:22` from the IAP
range. But if the VM lands on the `default` VPC network, GCP's own
pre-existing `default-allow-ssh` rule permits `tcp:22` from `0.0.0.0/0` to
every instance with an external address — so SSH, not the bridge, becomes
reachable from the internet. Narrow or delete that rule (it is project-wide
and affects your other VMs, which is why `deploy.sh` does not touch it), or
use `EXTERNAL_IP=none` with Cloud NAT, if that isn't acceptable.

These cost figures are approximate and were not re-fetched from GCP's
pricing pages while writing this. The `default-allow-ssh` behaviour *was*
checked, on 2026-09-03, on a project with no resources in it: turning on
`compute.googleapis.com` created the `default` network and, with it,
`default-allow-ssh` as INGRESS `0.0.0.0/0` `tcp:22`. Expect it on a fresh
project rather than treating it as something you opted into. See
[docs/HOSTING.md §3](../../../docs/HOSTING.md) for the full cost table and
its verification caveats.

## Reaching the bridge

The bridge binds to `127.0.0.1` by default and `BridgeConfig.validate()`
**refuses** to bind anything else without a TLS certificate — this script
doesn't fight that, it works with it. The VM's external address does not
change this — nothing listens on it for the bridge. Two ways to reach a
loopback-only bridge:

1. **On demand** (docs/HOSTING.md calls this **shape B**), from your own
   machine, via an IAP-tunnelled SSH port forward — `deploy.sh` prints the
   exact command at the end. The bridge is reachable only while you keep that
   SSH session open, so this is cloud-hosted but locally-accessed.
2. **Always on, from your phone anywhere** (**shape A**, the recommendation)
   — run [`tools/deploy/cloudflare/setup-tunnel.sh`](../cloudflare/) **on
   this VM**, over the SSH session from (1). `cloudflared` then runs on the
   VM as a systemd service, so this stays fully cloud-hosted: nothing runs on
   your computer afterwards, and the phone reaches the bridge whether your
   laptop is on or not. `startup-script.sh` has already installed the
   `cloudflared` binary here for that purpose. All-in cost ~$3/month for the
   VM's external IP plus ~$10/year for a domain — see the section above and
   docs/HOSTING.md §3 — without ever opening an inbound port on this VM for
   the bridge itself.

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
