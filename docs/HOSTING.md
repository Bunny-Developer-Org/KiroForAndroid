# Hosting the bridge: Google Cloud and Cloudflare

This document is the concrete "how" for [ADR-005](adr/ADR-005-bridge-hosting-and-availability.md)'s
Option B ("a small always-on host"), with runnable scripts under
[`tools/deploy/`](../tools/deploy/). It comes to about **$3/month**, not $0;
§3 is the cost table.

**Google Cloud and Cloudflare are not two competing options here.** They are
two layers: the Google Cloud VM is *where the bridge runs*, and Cloudflare
Tunnel is *how your phone reaches it*. Combining them is the recommended
setup, and it is fully cloud-hosted — `cloudflared` runs on that same VM, not
on your computer.

**This is still self-hosting.** Every shape below runs *your own* bridge,
signed in to *your own* Kiro account, on infrastructure *you* control. Nothing here
stands up a shared service — ADR-005 Option D (a bridge this project
operates on other people's behalf) remains rejected, for the reasons in that
ADR.

Capability claims below were checked against current product documentation on
**2026-09-02** and are cited inline; cloud pricing and product scope change,
so re-check before relying on this months later. The **cost** figures are a
mix: the Always Free limits carry that same 2026-09-02 check, while the
paid-resource rates added later in §3 are approximate and were *not*
re-fetched — §3 says exactly which is which.


> **Note (2026-09-03).** These scripts used to provision the bridge with a
> `KIRO_API_KEY`, and that turned out to produce a bridge that pairs with the
> phone and then cannot create a session — the mode is refuted in
> [AUTHENTICATION.md §3b](AUTHENTICATION.md). They now default to no key and an
> interactive `kiro-cli login` on the VM instead. That path **is verified**: a
> bridge provisioned this way answers `session/list` with the account's real
> sessions. The login itself is fiddly on a headless host — see "Signing a
> headless bridge in" in AUTHENTICATION.md, which is the procedure that works
> and the three ways it fails first.

## Which shape is which: what runs where

Read this first — it is the question the rest of the document assumes you
have already answered.

| Shape | Runs on the cloud VM | Runs on your own computer | Phone reaches the bridge | Cost |
|---|---|---|---|---|
| **A — All-cloud, always on.** The recommendation: §4 and §5 together. | the bridge **and** `cloudflared` | **nothing** | from anywhere, any time, at `wss://your-hostname/acp` | ~$3/month + ~$10/year |
| **B — All-cloud host, local access.** §4 on its own. | the bridge | an SSH session, only while you want access | only while you keep an IAP-tunnelled SSH port-forward open from your computer | ~$3/month |
| **C — Mixed (local + cloud).** §5 without §4. | *(no VM)* | the bridge **and** `cloudflared`, on your laptop or a Raspberry Pi | from anywhere — but only while your own machine is awake and online | $0 + ~$10/year |

**Shape A is the all-cloud answer, and it is what these scripts are built
for**: `deploy.sh` provisions the VM and starts the bridge (that alone is
shape B), then `setup-tunnel.sh`, run *on that VM*, adds the tunnel and makes
it shape A. `cloudflared` is a daemon that makes only outbound
connections, so it runs perfectly well on the VM next to the bridge — that is
what [`cloudflared.service`](../tools/deploy/cloudflare/cloudflared.service)
is for, and why `startup-script.sh` installs the `cloudflared` binary on the
VM. Nothing keeps running on your computer afterwards.

The one moment shape A touches your own machine is **setup, once**:
`cloudflared tunnel login` authenticates against your Cloudflare account in a
browser. Run on a headless VM it prints a URL for you to open in your own
browser, then continues on the VM. That is a one-time provisioning step, not
an ongoing local dependency — the same shape as `gcloud auth login`.

Shape C stays documented because it is genuinely the right answer for trying
the app against a bridge on your laptop for ten minutes, without provisioning
anything. It is not the recommendation, and it is not what §3 prices.

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
| Paired-device state | `--state-dir` | *(flag only, no env var)* | `~/.kiro-bridge` — also holds the `bridge pair` control socket |
| Address to advertise | `--public-url` | `KIRO_BRIDGE_PUBLIC_URL` | none — falls back to the bind address, **which is wrong behind a tunnel** (see below) |
| Pairing QR | `--no-qr` | `KIRO_BRIDGE_NO_QR` | QR printed; the address and code are printed as text either way |
| Zero Trust team | `--access-team-domain` | `KIRO_BRIDGE_ACCESS_TEAM_DOMAIN` | none — with the next row, turns on `GET /qr` |
| Access application | `--access-aud` | `KIRO_BRIDGE_ACCESS_AUD` | none. Both or neither; half-configured refuses to start |

**`--public-url` is the one setting a tunnelled deployment cannot skip.** The
bridge binds `127.0.0.1` and `cloudflared` connects to it like any other local
client, so nothing in the process knows the public hostname. Without it, the QR
in the pairing banner carries `ws://127.0.0.1:8765/acp` — an address that, on a
phone, means the phone. The banner says so when it is unset, and
`setup-tunnel.sh` prints the line to add once the hostname exists.

### Pairing without SSH at all: the `/qr` page

`kiro-bridge pair` still needs a terminal on the bridge host. `GET /qr` does not:
it serves the same QR as a web page, behind Cloudflare Access, so adding a phone
is open a URL → sign in with Google → scan.

It is **opt-in and off by default**, and turning it on takes two values from your
Zero Trust dashboard — the team domain (Settings → General) and the application's
Application Audience (AUD) tag (Access → Applications → your app → Overview):

```bash
KIRO_BRIDGE_ACCESS_TEAM_DOMAIN=your-team.cloudflareaccess.com
KIRO_BRIDGE_ACCESS_AUD=<the AUD tag>
```

**Scope the Access application to the `/qr` path, not to the hostname.** The phone
cannot complete a browser sign-in, so an application covering the whole hostname
breaks `POST /pair` and the `/acp` WebSocket: the app stops pairing *and* stops
connecting, and its error will not point at Cloudflare. There is a test
(`QrPageTest`) that pins `/pair` and `/acp` as reachable with no Access session.

The bridge verifies the `Cf-Access-Jwt-Assertion` itself — RS256 against your
team's published signing keys, plus `alg`, `exp`, `nbf`, `iss` and `aud` — rather
than trusting the header, because its origin port is reachable without going
through the tunnel. Unconfigured, half-configured or unverifiable, `/qr` answers
403 and says which. The code on the page rotates every 30 seconds, and the page
stops minting after ten minutes so a forgotten tab is not a code generator.

Limitations worth knowing before you commit to it:

- It needs a Cloudflare Zero Trust account. Free up to 50 users, but it is a
  second Cloudflare product to configure beyond Tunnel.
- The bridge must be able to reach `https://<team>/cdn-cgi/access/certs`. If it
  cannot, `/qr` fails closed — `kiro-bridge pair` and the startup banner are
  unaffected. A cache that has gone stale is served for up to 24 hours rather
  than dropping the page during a transient Cloudflare blip.
- A short Access session lifetime can bounce you to a login mid-refresh.

**Verified end to end on 2026-09-04**, against this project's own bridge: an Access
application scoped to `bridge.bunnydeveloper.dev/qr` with a Google policy, the two
environment variables set on the VM, and a real Pixel 8a scanning the page and
pairing — after which the phone connected to `/acp` and the bridge logged the
client attaching. `POST /pair` was confirmed still reachable with no Access
session at the same time, which is the check that proves the application did not
swallow the whole hostname.

### Pairing a phone to a bridge that is already running

A pairing code is only valid inside the process that will redeem it, so minting
one used to mean restarting the bridge and dropping every attached client.
Instead:

```bash
sudo runuser -u bridge -- /opt/kiro-bridge/bin/bridge pair \
  --state-dir /home/bridge/.kiro-bridge
```

It prints a fresh code and a scannable QR of the advertised address, in *your*
terminal, and the bridge keeps serving throughout.

Run it as the user the bridge runs as. The control socket lives in that user's
state directory, so a bare `bridge pair` from your own SSH session looks in your
home directory and reports — accurately, but confusingly — that no bridge is
running there. The command says as much when it happens.

It talks to the bridge over a `0600` Unix domain socket rather than an HTTP
route, and that is a security decision rather than a stylistic one: behind the
tunnel every HTTP request arrives with `remoteHost == 127.0.0.1`, so an endpoint
guarded by a "loopback only" check would be reachable by the entire internet.
[AUTHENTICATION §4](AUTHENTICATION.md#4-auth-1-pairing-the-app-to-the-bridge)
records that, and a related consequence for rate limiting that is **not** fixed.

**One behaviour change worth knowing about:** a bridge now refuses to start if
another one is already running against the same `--state-dir`. `PairingService`
rewrites `devices.txt` wholesale from its own in-memory map, so two bridges
sharing a state directory silently delete each other's paired devices. Give a
second bridge its own `--state-dir`.

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

## 3. What this costs, in one table

Earlier drafts of this document said "$0" in a few places. That was wrong, and
the correction matters more than the amount: the VM needs an outbound internet
path to install anything at all, and nothing in the Always Free tier provides
one. `deploy.sh` closes that gap with an ephemeral external IPv4 — about
**$3/month** — rather than a Cloud NAT gateway at roughly ten times that. §6
covers what the address does and does not expose.

### Running cost of shape A (all-cloud: free-tier VM + Cloudflare Tunnel)

| Line item | Cost | Notes |
|---|---|---|
| One non-preemptible `e2-micro`, `us-west1`/`us-central1`/`us-east1`, 1 per month | **$0** | Always Free tier, not a trial credit ([Always Free usage limits](https://cloud.google.com/free/docs/compute-getting-started), checked 2026-09-02) |
| 30 GB standard persistent disk (`pd-standard`) | **$0** | Exactly the Always Free allowance; `deploy.sh` asks for 30 GB for this reason |
| Network egress from North America, first 1 GB/month | **$0** | Roughly $0.12/GB beyond that |
| IAP TCP forwarding (the on-demand SSH path) | **$0** | No charge for the forwarding itself |
| **Ephemeral external IPv4 on the VM** | **~$3/month** | ~$0.004/hour while attached to a running instance. **Not covered by the Always Free tier.** `deploy.sh` attaches one by default because the VM cannot otherwise reach the internet — see §6 |
| Cloudflare Tunnel | **$0** | Free plan; no bandwidth-based charges since the 2026 pricing change |
| TLS certificate + stable hostname | **$0** | Cloudflare terminates TLS at its edge; nothing to renew on the VM |
| Cloudflare Zero Trust Access (optional, to put a login in front of the hostname) | **$0** up to 50 users | Free-plan seat limit |
| A domain in your Cloudflare account | **~$10/year** | Or **$0** with an ephemeral `trycloudflare.com` quick tunnel, whose hostname changes on every restart |
| **Bottom line** | **~$3/month + ~$10/year** | Not $0 |

The alternative to the external IPv4 is a **Cloud NAT** gateway: roughly
$0.044/hour per gateway — about **$32/month** — plus about $0.045/GB
processed. That is ~10x the cost of an ephemeral external IP for the same
outcome here, so unless you already run Cloud NAT for other reasons, the
external IP is the cheaper fix and is what `deploy.sh` does by default. If
you *do* already have NAT on the subnet, `EXTERNAL_IP=none ./deploy.sh` skips
the address and its charge. `deploy.sh` creates no Cloud NAT gateway itself —
that stays your call, because it is a subnet-wide resource.

### If `e2-micro` turns out to be too small

ADR-005's A16 (concurrent-session memory) is still unverified, so this may
happen. Approximate on-demand `us-central1` list prices, for sizing
expectations rather than as a quote:

| Machine type | vCPU / RAM | Approx. cost |
|---|---|---|
| `e2-micro` | 2 shared-core / 1 GiB | $0 (Always Free, 1/month) |
| `e2-small` | 2 shared-core / 2 GiB | ~$13/month |
| `e2-medium` | 2 shared-core / 4 GiB | ~$27/month |

Moving off `e2-micro` leaves the free tier entirely — the free instance is
one specific machine type, not a discount applied to whatever you run.

### What the rejected options would have cost

- **Cloud Run** with `min-instances=1` and `--no-cpu-throttling` — what a
  long-lived WebSocket server actually requires — bills a kept-warm instance
  continuously at full vCPU and memory rates: roughly **$45–50/month**. See
  §4 for why the shape is wrong as well as the price.
- **Cloudflare Containers** requires a Workers Paid plan (a **$5/month**
  floor) plus per-second vCPU, memory, and disk billing on top; an always-on
  container lands in the tens of dollars per month. See §5 for the
  architectural argument, which stands independently of the price.

### How verified these numbers are

Unlike the rest of this document, **the figures in this section were not
re-fetched from vendor pricing pages** on the day it was written. The
`e2-micro`/disk/egress Always Free limits are the ones checked 2026-09-02 and
cited above. The external IPv4 rate, Cloud NAT rate, Cloud Run monthly
estimate, `e2-small`/`e2-medium` prices, and the Cloudflare Containers
pricing are **approximate and unchecked at the time of writing** — carried
over from a review discussion, not from a live page. The vendor pages are
listed under "Sources" and marked as such. Cloud pricing moves; treat
everything here as an order of magnitude, and price it yourself before
committing.

## 4. Option A: a free-tier Google Cloud VM — where the bridge runs

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

The VM itself is free; **the deployment as a whole is not** — see the table in
§3, and the outbound-internet gap in §6 that the ~$3/month external IPv4
address exists to close.

**Cloud Run was considered and rejected for this workload.** The bridge is a
long-lived WebSocket server, which needs `min-instances=1` on Cloud Run to
stay warm — and a kept-alive minimum instance is billed continuously for its
memory (and for CPU too, if `--no-cpu-throttling` is used to keep a
background thread like a WebSocket connection alive between requests,
without which Cloud Run throttles CPU to near zero between requests)
([Cloud Run: min-instances](https://docs.cloud.google.com/run/docs/configuring/min-instances),
[Cloud Run pricing](https://cloud.google.com/run/pricing), both checked
2026-09-02). Concretely, a single always-warm instance billed at full vCPU
and memory rates for a whole month is roughly **$45–50/month** (approximate,
not re-fetched — see §3's verification note), against an `e2-micro` that is
$0 inside its Always Free quota and an external IP that costs ~$3/month. For
an always-on relay like this one, the VM is the better fit; Cloud Run's real
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

## 5. Option B: Cloudflare — Tunnel, not compute — how the phone reaches it

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
  revisiting if Cloudflare's persistence story matures further. The price
  points the same way: Containers requires a Workers Paid plan (a **$5/month**
  floor) and then bills vCPU, memory, and disk per second on top, so an
  always-on container runs to tens of dollars a month
  ([Containers pricing](https://developers.cloudflare.com/containers/pricing/) —
  approximate, not re-fetched; see §3's verification note). The
  architectural argument above stands on its own regardless.

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

That makes the pairing genuinely useful, and it is **shape A** from the table
at the top: run the bridge on the free-tier GCE VM (Option A), bound to
loopback as it already defaults to, and run `cloudflared` **on that same VM**,
pointed at that loopback address. The result is a stable
`wss://your-hostname/acp` the app can always reach with nothing running on
your own computer, and with:

- **$0 for the VM itself** (Always Free `e2-micro`, 30 GB disk, first 1 GB of
  North America egress) — but **not $0 all-in**: the VM needs an outbound
  internet path, and the cheap way to give it one is an external IPv4 address
  at **~$3/month**, which the Always Free tier does not cover (§3, §6).
- **$0 TLS + hostname** (Cloudflare Tunnel, Free plan) — plus **~$10/year**
  for a domain in your Cloudflare account, or $0 if you accept an ephemeral
  `trycloudflare.com` quick tunnel whose hostname changes on every restart.
- **No inbound firewall port opened for the bridge at all** — `cloudflared`
  reaches it over loopback or a private Docker network, and reaches
  Cloudflare's edge outbound only. This stays true with the external IPv4
  address attached: nothing listens on it for the bridge, and the only
  ingress rule `deploy.sh` creates is `tcp:22` from the IAP range. Note the
  `default-allow-ssh` caveat in §6 for what the *project's* pre-existing
  rules may still permit on port 22.

All in: **~$3/month plus ~$10/year**. The full breakdown is in §3.

### Where `cloudflared` itself runs — this is the all-cloud/mixed fork

`cloudflared` has to run on a host that can reach the bridge over loopback or
a private network. **That host is the GCE VM in shape A, not your computer.**
This is worth stating flatly because "run a tunnel" often means "run a tunnel
on your laptop", and that is *not* what the recommended setup does:

- **Shape A (all-cloud).** `setup-tunnel.sh` runs *on the VM*, over the same
  IAP SSH session `deploy.sh` gives you, and `cloudflared` then runs there as
  a systemd service ([`cloudflared.service`](../tools/deploy/cloudflare/cloudflared.service),
  whose `Requires=kiro-bridge.service` says plainly which host it expects).
  `startup-script.sh` installs the `cloudflared` binary on the VM so this is
  the short path. Your computer is not involved once setup is done: the phone
  talks to Cloudflare's edge, and the edge talks to the VM. Shut your laptop —
  the bridge stays reachable.
- **Shape C (mixed, local + cloud).** The bridge and `cloudflared` both run on
  a machine you own — a laptop, a home server, a Raspberry Pi — and only TLS
  termination and the hostname are cloud-side. Same scripts, same
  `wss://your-hostname/acp`, and $0/month, but the bridge is reachable only
  while that machine is awake and online. Good for trying the app out; not an
  always-on answer.

The **one-time** exception in shape A is `cloudflared tunnel login`, which
needs a browser to authenticate against your Cloudflare account. On a
headless VM it prints a URL you open in your own browser; the credential it
writes then lives on the VM. Nothing on your machine keeps running.

### Run it

Shape A — on the VM (`gcloud compute ssh kiro-bridge --tunnel-through-iap`
first, then, in that session):

```bash
export HOSTNAME=bridge.example.com   # a hostname on a domain in your Cloudflare account
cd tools/deploy/cloudflare && ./setup-tunnel.sh
```

Shape C is the identical command, run on your own machine instead, with the
bridge running there too.

See [`tools/deploy/cloudflare/README.md`](../tools/deploy/cloudflare/README.md)
for prerequisites, the systemd-unit and docker-compose ways to keep
`cloudflared` running, and a zero-setup "quick tunnel" fallback for trying
this without owning a domain.

## 6. Known limitations, stated plainly

- **The VM needs an outbound internet path, and it is not free.**
  `startup-script.sh` installs everything over the network — Adoptium's GPG
  key and apt repo from `packages.adoptium.net` for the JRE, and
  `https://cli.kiro.dev/install` piped to bash for `kiro-cli` — and
  `cloudflared` needs outbound reach to Cloudflare's edge. A GCE instance
  with neither an external address nor a Cloud NAT gateway has no internet
  path at all and cannot boot into a working state.
  [`deploy.sh`](../tools/deploy/gcp/deploy.sh) therefore attaches an
  ephemeral external IPv4 (**~$3/month**, outside the Always Free tier);
  `EXTERNAL_IP=none ./deploy.sh` skips it for subnets that already have NAT.
  **The address is for outbound traffic** — the bridge stays bound to
  loopback and no rule opens its port. One caveat worth knowing: on the
  `default` VPC network, GCP's own pre-existing `default-allow-ssh` rule
  permits `tcp:22` from `0.0.0.0/0` to every instance with an external
  address, so *SSH* (not the bridge) becomes internet-reachable unless you
  narrow that project-wide rule yourself. `deploy.sh` does not touch it,
  because it affects your other VMs too. Unlike the cost figures, this one
  **was verified on 2026-09-03**, on a project that had nothing in it
  yet: enabling `compute.googleapis.com` auto-created the `default` network
  *and* four ingress rules along with it, `gcloud compute firewall-rules
  list` returning `default-allow-ssh  INGRESS  0.0.0.0/0  tcp:22` (plus
  `default-allow-icmp`, `default-allow-rdp`, `default-allow-internal`) with
  creation timestamps minutes old. So it is not a leftover from an earlier
  setup — GCP puts it there for you on a fresh project.
- **`e2-micro` is modest hardware.** It is the tested claim for *one*
  concurrent cloud session, not several — see §4's A16 note.
- **Cloudflare Tunnel needs `cloudflared` running somewhere.** It is not
  itself a hosting location for the bridge process; it's the networking layer
  in front of wherever the bridge actually runs. In shape A that "somewhere"
  is the GCE VM, so the setup stays all-cloud — see §5. Running `cloudflared`
  on your own laptop instead is shape C, and makes the whole thing only as
  available as that laptop.
- **The `cloudflared` install on the VM does work** — verified on a real VM
  on 2026-09-03: `startup-script.sh` downloaded the binary from Cloudflare's
  direct-download URL to `/usr/local/bin/cloudflared` (the path
  [`cloudflared.service`](../tools/deploy/cloudflare/cloudflared.service)
  hardcodes) and created the `cloudflared` system user. What is installed is
  a binary, not a configured tunnel; `setup-tunnel.sh` is still the
  unexercised part. The download stays deliberately non-fatal: if it ever
  fails, the VM still boots and the IAP/SSH path (shape B) still works.
- **`bridge/Dockerfile` is unbuilt and unverified in the environment that
  wrote it** — no Docker daemon was available. It was written against
  `kiro-cli`'s real installer output (its glibc requirements were read
  directly out of the installer's `install.sh`/`BUILD-INFO`) and against
  `:bridge:installDist` succeeding locally without an Android SDK present,
  but the image itself has not been built or run. Build and smoke-test it
  before depending on it.
- **`deploy.sh` has now been run for real; `setup-tunnel.sh` has not.** On
  2026-09-03 `deploy.sh` was executed against a real GCP project. It got as
  far as a running `e2-micro` with the firewall rule and the ephemeral
  external IPv4, then failed — and getting from there to a bridge that
  actually serves `ws://127.0.0.1:8765/acp` took **four** fixes, none of
  which line-by-line review had caught. All four are in the scripts now:
  1. `startup-script.sh` installed `kiro-cli` with `runuser -l bridge`. The
     `-l` starts a login shell, the `bridge` user's shell is deliberately
     `/usr/sbin/nologin`, so it died with "This account is currently not
     available" — and `set -e` took the rest of the startup script with it,
     so the systemd unit was never written.
  2. `deploy.sh` waited only for SSH to answer before running
     `systemctl enable --now kiro-bridge`. sshd is up long before the
     startup script has finished, so this raced it and failed with
     "Failed to enable unit: Unit file kiro-bridge.service does not exist."
     It now waits for the unit file itself, and dumps the startup log if it
     never appears.
  3. The systemd unit combined `ProtectSystem=strict` with
     `ReadWritePaths=/home/bridge`, which leaves `/tmp` **read-only** for the
     service. `BridgeConfig` defaults `workingDirectory` to
     `$java.io.tmpdir/kiro-bridge-workspace` and `CliSupervisor` `mkdirs()`
     it — that call just returns `false` on a read-only `/tmp`, and the
     subsequent spawn fails with `Cannot run program "kiro-cli" … error: 2`.
     That message reads as a missing binary; the binary was fine and present,
     and it was the *working directory* that did not exist. `PrivateTmp=true`
     fixes it by giving the service its own writable `/tmp`.
  4. `umask 077` set for writing `/etc/kiro-bridge.env` leaked into the next
     step, so the unit file was written `0600 root:root`. systemd reads it
     anyway, so nothing broke — but it is invisible to anyone debugging
     without `sudo`, which wasted time. The umask is reset now.
  With those in place the bridge starts, stays up, listens on loopback and
  prints its pairing banner — confirmed on a real VM. What is still
  unverified past that point is a phone actually completing a session.
  `setup-tunnel.sh` has now been run too, and it had a bug of its own:
  `cloudflared tunnel list -o json` pretty-prints, so the field arrives as
  `"id": "..."` with a space, and the script grepped for the space-less form.
  Under `set -euo pipefail` the failing grep killed the script **silently, one
  line after it had created a real tunnel**, with its own "could not resolve the
  tunnel ID" message unreachable. Fixed, and the whole path is now verified:
  `wss://bridge.bunnydeveloper.dev/acp` answers `101 Switching Protocols` from
  Cloudflare's edge and the bridge logs the connection attempt in the same
  second, with `cloudflared` running as a systemd unit on the VM and nothing
  running on the developer's machine.
- **QR pairing and `bridge pair` are verified locally, not on a phone
  (2026-09-04).** Against the real `installDist` binary on a workstation: a code
  minted over the control socket was exchanged for a real device token while the
  server stayed up — one process start, no restart — the previous code was
  retired, a second bridge on the same state directory was refused, and the
  refusal left the running bridge's socket intact. That last one was a genuine
  bug the unit tests missed and only running the binary caught. **What has not
  **that gap is now closed too**: on 2026-09-04 a real Pixel 8a scanned a QR from
  the Access-gated `/qr` page on the GCE VM, with `KIRO_BRIDGE_PUBLIC_URL` set,
  and paired — the phone then connected to `/acp`. The QR's polarity and module
  fidelity are pinned by tests as well, but the scan is what settles it.
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

Pricing pages behind §3's paid-resource figures — **linked for you to check,
not re-fetched while writing this**; the numbers in §3 came from a review
discussion and are approximate:

- [VPC pricing: external IP addresses](https://cloud.google.com/vpc/network-pricing#ipaddress) — unchecked at time of writing
- [Cloud NAT pricing](https://cloud.google.com/nat/pricing) — unchecked at time of writing
- [Compute Engine VM instance pricing (`e2-small`, `e2-medium`)](https://cloud.google.com/compute/vm-instance-pricing) — unchecked at time of writing
- [Cloud Run pricing](https://cloud.google.com/run/pricing) — the page was checked 2026-09-02 for *how* min-instances is billed; the ~$45–50/month monthly total was not recomputed from it
- [Cloudflare Containers pricing](https://developers.cloudflare.com/containers/pricing/) — unchecked at time of writing
- [Cloudflare Zero Trust plans](https://www.cloudflare.com/plans/zero-trust-services/) — unchecked at time of writing
