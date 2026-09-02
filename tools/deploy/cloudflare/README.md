# Cloudflare Tunnel for the bridge

This directory sets up the **networking and TLS layer**, not compute.
Cloudflare has no product that runs an always-on process able to spawn
`kiro-cli` as a child and hold long-lived WebSocket connections for free —
see [docs/HOSTING.md](../../../docs/HOSTING.md) for why Workers and
Containers don't fit this shape. What Cloudflare is genuinely good for here,
and free, is [Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/):
TLS termination and a stable hostname for a bridge that runs somewhere else
— its own machine, a Raspberry Pi, or the GCE VM in
[`tools/deploy/gcp/`](../gcp/).

**Not run against a real account while writing this.** `cloudflared` was
available locally, which made it possible to check every flag below against
real `--help` output, but `login`/`create`/`route dns` were never actually
invoked against a Cloudflare account.

## Prerequisites

- `cloudflared` installed ([downloads](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/))
- A domain added to your Cloudflare account — the **Free** plan is enough.
  No domain? See "No domain yet" below for a zero-setup fallback.
- The bridge already running somewhere, reachable at `localhost:8765` from
  wherever `cloudflared` runs (same host, or the same docker-compose network)

No secret is generated or chosen by you here: `cloudflared tunnel login`
opens a browser against your own account, and `tunnel create` generates its
own credentials file. The only secret in this whole hosting setup remains
`KIRO_API_KEY`, on the bridge side.

## Run it

```bash
export HOSTNAME=bridge.example.com   # a hostname on your Cloudflare domain
./setup-tunnel.sh
```

This logs in (once), creates a tunnel named `kiro-bridge`, points
`$HOSTNAME` at it, and writes `~/.cloudflared/config.yml`. It prints the
final `wss://` URL to give the app.

Then run `cloudflared` persistently, either way:

- **systemd** — copy [`cloudflared.service`](cloudflared.service) to
  `/etc/systemd/system/`, create a `cloudflared` system user, and
  `systemctl enable --now cloudflared`. Good fit for the GCE VM path.
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
