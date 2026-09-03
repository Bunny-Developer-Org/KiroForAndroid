# Cloudflare Tunnel for the bridge

This directory sets up the **networking and TLS layer**, not compute.
Cloudflare has no product that runs an always-on process able to spawn
`kiro-cli` as a child and hold long-lived WebSocket connections for free —
see [docs/HOSTING.md](../../../docs/HOSTING.md) for why Workers and
Containers don't fit this shape. What Cloudflare is genuinely good for here,
and free, is [Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/):
TLS termination and a stable hostname for a bridge that runs somewhere else.

## Run this on the VM, not on your laptop

**`setup-tunnel.sh` and `cloudflared` are meant to run on the same host as
the bridge — which, in the recommended setup, is the GCE VM from
[`tools/deploy/gcp/`](../gcp/), not your own computer.** `cloudflared` only
ever makes outbound connections, so it is perfectly happy on that VM, and
[`cloudflared.service`](cloudflared.service) exists precisely to keep it
running there (note its `Requires=kiro-bridge.service`). The GCE startup
script already installs the `cloudflared` binary on the VM for this reason.
That combination — docs/HOSTING.md calls it **shape A** — is fully
cloud-hosted: once it is set up, nothing runs on your machine, and the phone
reaches the bridge whether your laptop is on or not.

Running the bridge *and* `cloudflared` on a machine you own is a legitimate
but different thing — **shape C, mixed local + cloud**. It costs $0/month
instead of ~$3, and the bridge is reachable exactly as long as that machine
is awake. Use it to try the app out, not as an always-on setup.

The one step that touches your own browser either way is the one-time
`cloudflared tunnel login` below.

**Run for real on 2026-09-03**, against a live Cloudflare account and a real
zone, and fixed once as a result: the tunnel-ID lookup parsed `-o json` output
that does not exist (cloudflared pretty-prints, so the space-less `"id":"..."`
pattern matched nothing), and under `set -euo pipefail` that killed the script
silently right after it had created a tunnel. The full path now works end to
end - `login`, `create`, `route dns`, config, systemd unit, and a WebSocket
handshake from the public internet reaching a loopback-bound bridge.

## Prerequisites

- `cloudflared` installed on the host you run this from
  ([downloads](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/)).
  On the GCE VM, `tools/deploy/gcp/startup-script.sh` has already done this
  (unverified — see its comment); on your own machine for shape C, install it
  yourself.
- A domain added to your Cloudflare account — the **Free** plan is enough.
  No domain? See "No domain yet" below for a zero-setup fallback.
- The bridge already running on the host where you run this — the GCE VM for
  shape A — reachable at `localhost:8765` from wherever `cloudflared` runs
  (same host, or the same docker-compose network)

No secret is generated or chosen by you here: `cloudflared tunnel login`
opens a browser against your own account — on a headless VM it prints a URL
for you to open in your own browser instead, and the credential it writes
stays on the VM — and `tunnel create` generates its own credentials file.
The only secret in this whole hosting setup remains `KIRO_API_KEY`, on the
bridge side.

## Run it

For shape A, SSH to the VM first — `gcloud compute ssh kiro-bridge
--tunnel-through-iap` — and run this there. For shape C, run it on the
machine where the bridge is running.

```bash
export HOSTNAME=bridge.example.com   # a hostname on your Cloudflare domain
./setup-tunnel.sh
```

This logs in (once), creates a tunnel named `kiro-bridge`, points
`$HOSTNAME` at it, and writes `~/.cloudflared/config.yml`. It prints the
final `wss://` URL to give the app.

Then run `cloudflared` persistently, either way:

- **systemd** — copy [`cloudflared.service`](cloudflared.service) to
  `/etc/systemd/system/` and `systemctl enable --now cloudflared`. This is
  the shape A path; the GCE startup script has already created the
  `cloudflared` user and put the binary at `/usr/local/bin/cloudflared`,
  which is the path the unit expects.
- **docker-compose** — [`docker-compose.yml`](docker-compose.yml) runs the
  bridge and `cloudflared` as two containers on one network, so `cloudflared`
  reaches the bridge by service name and nothing is published to the host at
  all.

## No domain yet: quick tunnels

`cloudflared tunnel --url http://localhost:8765` (no `login`/`create`
needed) gets you a random `*.trycloudflare.com` hostname immediately, with
the same free TLS termination. It is genuinely useful for trying the app
against a bridge on a laptop for ten minutes; it is not a hosting
recommendation — the hostname is not stable across restarts and Cloudflare
documents quick tunnels as unsuited to production use.

## Known limits

- `cloudflared` is itself a process that must be kept running somewhere —
  this directory does not remove that requirement, it makes the "somewhere"
  free and TLS-terminated instead of a self-managed certificate.
- The tunnel token / credentials file (`~/.cloudflared/<id>.json`) is a real
  credential: anyone holding it can route traffic through your tunnel. Treat
  it like `KIRO_API_KEY` — file permissions, not shared storage.
- `route dns` requires the hostname's zone to actually be on your Cloudflare
  account. There's no way around that short of the quick-tunnel fallback
  above.
