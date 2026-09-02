# Hosting the bridge: Google Cloud and Cloudflare

This document is the concrete "how" for [ADR-005](adr/ADR-005-bridge-hosting-and-availability.md)'s
Option B ("a small always-on host"). It covers two low-cost, self-hosted
paths — a free Google Cloud VM, and Cloudflare Tunnel for reaching it — with
runnable scripts under [`tools/deploy/`](../tools/deploy/).

**This is still self-hosting.** Both paths run *your own* bridge, signed in
to *your own* Kiro account, on infrastructure *you* control. Nothing here
stands up a shared service — ADR-005 Option D (a bridge this project
operates on other people's behalf) remains rejected, for the reasons in that
ADR.

Every cost and capability claim below was checked against current product
documentation on **2026-09-02** and is cited inline; cloud pricing and
product scope change, so re-check before relying on this months later.

---

## 1. What the bridge actually needs

From [`BridgeConfig`](../bridge/src/main/kotlin/dev/kiro/bridge/BridgeConfig.kt),
almost everything has a workable default. The one setting that is a real
secret is `KIRO_API_KEY` — the "paste one key" provisioning path
[verified in AUTHENTICATION §3b](AUTHENTICATION.md#3b-alternative-for-auth-2--api-key-provisioning-verified-2026-09-02).

| Setting | Flag | Env var | Default |
|---|---|---|---|
| Bind address | `--bind` | `KIRO_BRIDGE_BIND` | `127.0.0.1` (loopback-only) |
| Port | `--port` | `KIRO_BRIDGE_PORT` | `8765` |
| Kiro account | `--api-key` | `KIRO_API_KEY` | none — falls back to an interactive `kiro-cli login` on the host |
| `kiro-cli` location | `--kiro-cli` | `KIRO_CLI_PATH` | `kiro-cli` on `PATH` |
| TLS cert / key | `--tls-cert` / `--tls-key` | *(flags only, no env var)* | none |
| Paired-device state | `--state-dir` | *(flag only, no env var)* | `~/.kiro-bridge` |

`BridgeConfig.validate()` is a hard constraint both deployment paths below
are built around, not something they route past: **it refuses to bind
anything other than loopback without both `--tls-cert` and `--tls-key`.**
Rather than manage a self-signed or Let's-Encrypt-style certificate
ourselves, both paths keep the bridge on loopback and put a TLS-terminating
hop in front of it instead — an SSH tunnel for on-demand access, or
Cloudflare Tunnel for always-on access from the phone.

## 2. Where this repo stood before this document

Two things worth being plain about, since they motivated what got added
alongside this file:

- **The bridge's own `--help`, first-run pairing banner, and README section
  were already accurate and sufficient** for running it by hand on a
  machine with a JDK and `kiro-cli` already on it — nothing about the CLI
  surface itself was misleading or missing.
- **There was no container image anywhere in the repo**, despite
  [ADR-005 §4 Option C](adr/ADR-005-bridge-hosting-and-availability.md#option-c--a-published-container-image)
  calling for one ("Adopt as the primary distribution form"). Before this
  change, running the bridge anywhere other than a machine you'd hand-set-up
  meant installing a JDK and `kiro-cli` yourself, with no documented image
  to `docker run`. [`bridge/Dockerfile`](../bridge/Dockerfile) (added
  alongside this document) is a first cut at that gap — see its own header
  comment for what is and isn't verified about it.

## 3. Option A: a free Google Cloud VM

### Cost

Compute Engine's **Always Free** tier includes one non-preemptible
`e2-micro` instance per month in `us-west1`, `us-central1`, or `us-east1`,
30 GB of standard persistent disk, and 1 GB of monthly network egress from
North America — permanently, not a time-limited trial credit
([Compute Engine: Always Free usage limits](https://cloud.google.com/free/docs/compute-getting-started),
checked 2026-09-02). `e2-micro` itself is a shared-core machine: 2 vCPUs at
~12.5% of a physical core each (bursts higher for short periods), 1 GiB RAM
([e2-micro specs, cloudprice.net / sparecores.com](https://cloudprice.net/gcp/compute/instances/e2-micro),
checked 2026-09-02). A GCP project still needs billing enabled to create any
Compute Engine resource, even one that stays inside the free quota.

**Cloud Run was considered and rejected for this workload.** The bridge is a
long-lived WebSocket server, which needs `min-instances=1` on Cloud Run to
stay warm — and a kept-alive minimum instance is billed continuously for its
memory (and for CPU too, if `--no-cpu-throttling` is used to keep a
background thread like a WebSocket connection alive between requests,
without which Cloud Run throttles CPU to near zero between requests)
([Cloud Run: min-instances](https://docs.cloud.google.com/run/docs/configuring/min-instances),
[Cloud Run pricing](https://cloud.google.com/run/pricing), both checked
2026-09-02). That is a small but nonzero, continuously-metered cost, against
an `e2-micro` that is $0 inside its Always Free quota. For an always-on
relay like this one, the free VM is the better fit; Cloud Run's real
strength — scale-to-zero for request-driven workloads — isn't what this
workload wants.

### What it's sized for

A single-session bridge is the claim this setup supports. ADR-005's
[A16](adr/ADR-005-bridge-hosting-and-availability.md#7-assumptions-to-verify--extends-adr-001-5-and-adr-004-7)
— whether one host can supervise several concurrent cloud sessions (the
preview cap is 10) within reasonable memory — is explicitly still
unverified, on any hardware, not only small hardware. Don't read "runs on
1 GiB" as "runs *N* sessions on 1 GiB."

### Run it

```bash
export KIRO_API_KEY=...
export PROJECT_ID=your-gcp-project
cd tools/deploy/gcp && ./deploy.sh
```

See [`tools/deploy/gcp/README.md`](../tools/deploy/gcp/README.md) for
prerequisites, exactly what gets created, and how to reach a bridge that (by
design, per §1) never binds to a public address: either an on-demand
IAP-tunnelled SSH port-forward, or pair it with Option B below for
always-on reachability.

## 4. Option B: Cloudflare — Tunnel, not compute

### What Cloudflare's own compute can and can't do here

The bridge needs to spawn `kiro-cli` as a child process and hold long-lived
WebSocket connections, indefinitely. Checked against 2026 product docs:

- **Workers** cannot do this. Workers is a V8 isolate sandbox — it "does not
  allow customers to upload native-code binaries to run on the Cloudflare
  network — only JavaScript and WebAssembly"
  ([Workers security model](https://developers.cloudflare.com/workers/reference/security-model/),
  checked 2026-09-02). A `node:child_process` compatibility shim exists as
  of a 2026-03-17+ compatibility date, but it's a stub over JS/Wasm
  execution, not arbitrary binary execution — it does not let a Worker spawn
  `kiro-cli`.
- **Cloudflare Containers** is the newer product that removes that specific
  objection — it runs real Docker images, reached general availability in
  April 2026, and gained an `exec()` API for starting and controlling
  processes inside a running container in June 2026
  ([Containers GA](https://developers.cloudflare.com/changelog/post/2026-04-13-containers-sandbox-ga/),
  [exec() changelog](https://developers.cloudflare.com/changelog/post/2026-06-18-container-exec/),
  both checked 2026-09-02). It is a real option in principle. But it's
  explicitly designed for "bursty, edge-adjacent workloads that can sleep,"
  disk is ephemeral by default (a fresh disk from the container image on
  every restart, with persistence only via newer add-ons like FUSE-backed
  object storage), and lifecycle is managed by a Durable Object that starts
  and stops the container on demand. A bridge that needs to stay
  continuously signed in to a Kiro account and continuously reachable for
  notifications is a worse fit for that shape than a plain always-on VM —
  this is a judgment call, not a hard technical blocker, and worth
  revisiting if Cloudflare's persistence story matures further.

So: **Cloudflare is not where this setup runs the bridge.** It's honest to
say that plainly rather than force a Containers-based path into this
document to check a box.

### What Cloudflare is good for here: Tunnel

[Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/)
is free with no usage limits as of a 2026 pricing change that ended
bandwidth-based charges for it, and is available on the Free plan
([Cloudflare Tunnel pricing discussion, checked 2026-09-02](https://community.cloudflare.com/t/cloudflare-tunnel-cloudflared-price/846926)).
`cloudflared`, the daemon that runs the tunnel, makes only outbound
connections to Cloudflare's edge — nothing needs to be reachable on the
host's own inbound network path. WebSocket proxying over a Tunnel has been
automatic (no special flag) since 2022; an ingress rule just points a
hostname at a `ws://` or `http://` origin.

That makes the pairing genuinely useful: run the bridge on the free GCE VM
(Option A), bound to loopback as it already defaults to, and run
`cloudflared` alongside it, pointed at that loopback address. The result is
a stable `wss://your-hostname/acp` the app can always reach, with:

- **$0 compute** (Always Free `e2-micro`)
- **$0 TLS + hostname** (Cloudflare Tunnel)
- **No inbound firewall port opened for the bridge at all** — `cloudflared`
  reaches it over loopback or a private Docker network, and reaches
  Cloudflare's edge outbound only.

### Run it

```bash
export HOSTNAME=bridge.example.com   # a hostname on a domain in your Cloudflare account
cd tools/deploy/cloudflare && ./setup-tunnel.sh
```

See [`tools/deploy/cloudflare/README.md`](../tools/deploy/cloudflare/README.md)
for prerequisites, the systemd-unit and docker-compose ways to keep
`cloudflared` running, and a zero-setup "quick tunnel" fallback for trying
this without owning a domain.

## 5. Known limitations, stated plainly

- **`e2-micro` is modest hardware.** It is the tested claim for *one*
  concurrent cloud session, not several — see §3's A16 note.
- **Cloudflare Tunnel needs `cloudflared` running somewhere.** It is not
  itself a hosting location for the bridge process; it's the networking
  layer in front of wherever the bridge actually runs.
- **`bridge/Dockerfile` is unbuilt and unverified in the environment that
  wrote it** — no Docker daemon was available. It was written against
  `kiro-cli`'s real installer output (its glibc requirements were read
  directly out of the installer's `install.sh`/`BUILD-INFO`) and against
  `:bridge:installDist` succeeding locally without an Android SDK present,
  but the image itself has not been built or run. Build and smoke-test it
  before depending on it.
- **Neither `tools/deploy/gcp/deploy.sh` nor `tools/deploy/cloudflare/setup-tunnel.sh`
  was run against a real account or domain.** Both were checked line-by-line
  against real `gcloud`/`cloudflared --help` output (both CLIs happened to
  be available locally, authenticated to real accounts, which is exactly
  why no command that provisions or spends anything was actually executed
  against them). Expect to debug a first real run.
- **`kiro-cli`'s installer is large.** Its own zip is ~600 MB compressed
  (~1 GB installed across `kiro-cli`, `kiro-cli-chat`, and `kiro-cli-term`),
  found by inspecting the real installer while writing this. That's the
  floor for the container image's size and for how long the GCE startup
  script takes on first boot; it comfortably fits `e2-micro`'s 30 GB disk,
  but it is not the small, fast image `kiro-cli acp` alone would suggest.

## Sources

- [Compute Engine: Always Free usage limits](https://cloud.google.com/free/docs/compute-getting-started) — checked 2026-09-02
- [e2-micro specs](https://cloudprice.net/gcp/compute/instances/e2-micro) — checked 2026-09-02
- [Cloud Run: Set minimum instances](https://docs.cloud.google.com/run/docs/configuring/min-instances) — checked 2026-09-02
- [Cloud Run pricing](https://cloud.google.com/run/pricing) — checked 2026-09-02
- [Cloudflare Workers security model](https://developers.cloudflare.com/workers/reference/security-model/) — checked 2026-09-02
- [Cloudflare Workers compatibility flags (`node:child_process`)](https://developers.cloudflare.com/workers/configuration/compatibility-flags/) — checked 2026-09-02
- [Cloudflare Containers and Sandboxes GA (2026-04-13)](https://developers.cloudflare.com/changelog/post/2026-04-13-containers-sandbox-ga/) — checked 2026-09-02
- [`exec()` for Containers (2026-06-18)](https://developers.cloudflare.com/changelog/post/2026-06-18-container-exec/) — checked 2026-09-02
- [Cloudflare Containers overview](https://developers.cloudflare.com/containers/) — checked 2026-09-02
- [Cloudflare Tunnel overview](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/) — checked 2026-09-02
- [Cloudflare Tunnel pricing discussion](https://community.cloudflare.com/t/cloudflare-tunnel-cloudflared-price/846926) — checked 2026-09-02
- [`kiro-cli` installation](https://kiro.dev/docs/cli/installation/) and its live installer at `https://cli.kiro.dev/install` — fetched and read directly, 2026-09-02
