# KiroForAndroid

An unofficial Android client for [Kiro](https://kiro.dev) **cloud sessions** — agent runs that live in a managed cloud sandbox and keep working after the client disconnects. Kiro ships an iOS app and no Android one, publishes no third-party API, and has three open feature requests asking for exactly this that nobody has committed to (tracked in [F-00](docs/FEATURES.md)). That gap is the whole reason this project exists.

## Status, at a glance

Of the 28 items in [docs/FEATURES.md](docs/FEATURES.md): **8 done, 6 partial, the rest not started.** Done: project scaffold/CI, the ACP protocol layer, the session gateway, credential storage, session list (delete/pin), the prompt composer, and the two new-session defects reported from a phone on 2026-09-04 (missing model picker; a dropped socket nothing noticed). Partial: bridge pairing (multi-bridge list works, QR scan doesn't), the new-session flow, transcript rendering, permission/approval UI, reconnect/replay hardening, and the 2026-09-03 on-device defect sweep (5 of 6, the sixth blocked upstream).

**The one caveat that matters more than any of those markers:** everything above was built and verified against `FakeGateway` or a local `kiro-cli` session. **Nothing has yet been exercised against a real, paid cloud-session creation** — that costs credits, and none have been spent. Session creation, the approval round-trip, and reconnect-mid-turn replay are the three biggest remaining unknowns, and they're exactly the parts a phone client can't fake its way past.

## The thing to understand first

**This app cannot reach Kiro on its own, and no version of it ever will without Kiro's cooperation.**

Kiro publishes no API, no OAuth client registration, and no endpoint for third-party clients. Every documented path to a cloud session goes through a first-party client. [ADR-001](docs/adr/ADR-001-cloud-session-access.md) explains why reverse-engineering the private one was rejected on principle rather than on difficulty.

So the app talks to a **bridge**: a small program you run yourself, on a machine where `kiro-cli` is installed and signed in. The bridge supervises `kiro-cli acp` and relays it to your phone over an authenticated WebSocket.

What the bridge host actually needs turns out to be much less than it sounds:

| | |
|---|---|
| A copy of your code | **No.** Kiro clones repositories inside its own sandbox. |
| Git credentials | **No.** The Kiro Agent app pushes and opens PRs, server-side. |
| Developer-grade hardware | **No.** `kiro-cli` runs on Linux `aarch64`; a Raspberry Pi qualifies. |
| The `kiro-cli` binary, a signed-in Pro account, outbound HTTPS | **Yes.** That is the whole list. |
| Enough uptime to be awake when you pick up your phone | **Yes**, and this is the real cost. |

That last row is the honest catch: **a sleeping bridge is a silent app.** Notifications are sent by the bridge, so if it lives on a laptop that closes at night, approval prompts do not reach you until it wakes. [ADR-005](docs/adr/ADR-005-bridge-hosting-and-availability.md) treats that as a documented limitation with a designed degradation path, not a bug to chase.

## How the pieces fit

Four processes, two separate authentications, and one seam the whole app is written against.

```
  YOUR PHONE                                              app/ · core/
┌──────────────────────────────────────────────────────────────────────┐
│  Compose UI  ──►  ViewModels  ──►  CloudSessionGateway (core/)       │
│  onboarding · sessions             the one seam every feature codes  │
│  create · transcript               against                           │
│                                            │                         │
│                        ┌───────────────────┴───────────────┐         │
│                  BridgeGateway (core/)                FakeGateway    │
│                        │  live, over ACP               offline; every│
│                        ▼                               screen still  │
│                  AcpClient (core/)                     renders       │
│                        │  request/response correlation,              │
│                        ▼  and agent→client requests                  │
│                  WebSocketAcpTransport (app/)                        │
│                                                                      │
│  SessionConnectionService (foreground, dataSync) holds the socket;   │
│  Backoff + ConnectivityObserver reconnect.  KeystoreTokenStore keeps │
│  the pairing token — nothing belonging to Kiro is ever on the phone. │
└──────────────────────────────────────────────────────────────────────┘
        │  ▲                                                      ▲
        │  │  POST /pair   Auth-1: a pairing token we issue       ┊
        │  │  WSS  /acp    ACP JSON-RPC 2.0, relayed verbatim     ┊ FCM push
        │  │  Loopback by default; a non-loopback bind without    ┊ (F-16, not
        ▼  │  TLS is refused outright, not warned about.          ┊  built yet)
┌──────────────────────────────────────────────────────────────────────┐
│  BRIDGE HOST — a machine you run                              bridge/│
│                                                                      │
│  BridgeServer    Ktor CIO + WebSockets. Two routes, and no more.     │
│  PairingService  Single-use codes, hashed device tokens, revocation. │
│  SessionLog      Bounded per-session replay, keyed by the agent's    │
│                  own messageId — or an explicit "that point is gone".│
│  CliSupervisor   Supervises one CLI process; moves line-delimited    │
│                  JSON-RPC both ways.  `_bridge/*` never reaches it.  │
└──────────────────────────────────────────────────────────────────────┘
                        │  stdio, ACP JSON-RPC
┌──────────────────────────────────────────────────────────────────────┐
│  kiro-cli acp --agent-engine v3 --auth-method cli                    │
│  Those flags are not tuning: the default engine cannot see cloud     │
│  sessions at all.  Owns Auth-2 — the user's real Kiro account and    │
│  its own token store.  Signed in by `kiro-cli login` (device flow,   │
│  relayed through the app — F-08) or by KIRO_API_KEY.                 │
└──────────────────────────────────────────────────────────────────────┘
                        │  Kiro's own protocol. Private, undocumented,
                        ▼  and we never speak it — that is ADR-001.
┌──────────────────────────────────────────────────────────────────────┐
│  KIRO CLOUD — agent service + sandbox                                │
│  Runs the turn, clones the bound repositories, commits, opens PRs.   │
│  Does more than this project assumed, too: multi-client fan-out, and │
│  permissions correlated by toolCallId, so an approval can be answered│
│  on a connection other than the one that asked for it.               │
└──────────────────────────────────────────────────────────────────────┘
                        │  the Kiro account's own source-provider
                        ▼  connection — not the bridge host's
                GitHub / other providers (`_kiro/sourceProviders/*`)
```

Three things the picture is arguing, beyond naming the parts:

- **`CloudSessionGateway` is the only backend abstraction in the app.** Screens never see a socket. If Kiro ever publishes an API, that is a second implementation of that one interface behind the same seam, not a rewrite — which is the actual decision recorded in [ADR-001 §3](docs/adr/ADR-001-cloud-session-access.md#3-decision).
- **The two authentications are separate, and only the lower one is Kiro's.** The pairing token is ours and protects one WebSocket; the Kiro credential never leaves the bridge host's `kiro-cli` store. A compromised phone gets you a bridge, not an account. [AUTHENTICATION §1](docs/AUTHENTICATION.md#1-there-are-two-separate-authentications).
- **The bottom two boxes are why the bridge host needs so little.** The clone, the commit and the PR all happen in Kiro's sandbox against the *account's* provider connection, so the box in the middle never holds your code or your git credentials.

Two things the drawing flattens. An always-on bridge adds a hop on the top edge: the phone connects to `wss://your-hostname/acp`, Cloudflare's edge terminates TLS, and `cloudflared` on the host forwards to a bridge that stays bound to loopback — the setup [HOSTING.md](docs/HOSTING.md) covers. And the bottom two boxes are drawn from captured protocol frames and Kiro's docs, not from a cloud session this project has created: as above, no paid session has been run yet.

## Layout

```
core/     Pure Kotlin/JVM. ACP client, JSON-RPC framing, session state, transcript
          reducer. No android.* imports — CI fails the build if one appears.
app/      The Android client. Compose, Material 3.
bridge/   The host-side relay. Kotlin/JVM, ships as a container image.
tools/    The ACP probe that produced the protocol findings and golden fixtures.
```

## Building

```bash
export JAVA_HOME=/path/to/jdk-21   # AGP rejects JDK 22+
export ANDROID_HOME=/path/to/android-sdk
./gradlew build
```

## Running the bridge

```bash
./gradlew :bridge:installDist && ./bridge/build/install/bridge/bin/bridge
```

It prints a pairing code. Enter that and the address in the app.

By default it binds `127.0.0.1`, and it **refuses to start** on any other address without TLS — a non-loopback bind puts the pairing handshake and every device token on your network in the clear.

To provision it without an interactive login, set `KIRO_API_KEY`. One caveat worth knowing: that variable overrides whatever account the CLI is signed in as, and there is no flag to suppress it.

Want it always on instead of on your laptop? [docs/HOSTING.md](docs/HOSTING.md) covers a fully cloud-hosted setup — the bridge *and* the Cloudflare tunnel both on a free-tier Google Cloud VM, nothing left running on your own machine — for about $3/month, not $0. Scripts under [tools/deploy/](tools/deploy/).

## Documents — read the ADRs, not just this file

The [ADRs](docs/adr/) are this project's real accumulated knowledge, not design notes written in advance of the work. Several record findings **verified against a real `kiro-cli` and a real Kiro account** — protocol behavior the published docs get wrong, assumptions confirmed or refuted with captured traffic, not guessed. If you want to understand *why* the app is shaped the way it is — why there's a bridge at all, why native over React Native, how a repository gets picked with no API to list them — the ADRs are where that reasoning actually lives.

Read in this order:

| | |
|---|---|
| [ADR-001](docs/adr/ADR-001-cloud-session-access.md) | Why there is a bridge at all. Constrains everything else. |
| [PROTOCOL-FINDINGS](docs/PROTOCOL-FINDINGS.md) | What a real `kiro-cli` actually does, versus what the docs say. Several published details are wrong. |
| [ADR-002](docs/adr/ADR-002-react-native-vs-native.md) · [ADR-003](docs/adr/ADR-003-tech-stack.md) | Native Kotlin over React Native, and the stack that follows. |
| [ADR-004](docs/adr/ADR-004-work-repo-selection.md) · [ADR-005](docs/adr/ADR-005-bridge-hosting-and-availability.md) | How repositories are bound; where the bridge runs and what happens when it does not. |
| [ACP-INTEGRATION](docs/ACP-INTEGRATION.md) · [AUTHENTICATION](docs/AUTHENTICATION.md) | The protocol and sign-in contracts. |
| [VISUAL-LANGUAGE](docs/VISUAL-LANGUAGE.md) | So ten screens look like one app. |
| [PRIOR-ART](docs/PRIOR-ART.md) | What other unofficial Kiro clients got right and wrong, and what this project borrowed or deliberately didn't. |
| [FEATURES](docs/FEATURES.md) | The backlog, with per-item status — the freshest source of truth for what's done. |

## License

MIT. See [LICENSE](LICENSE).

## Unaffiliated

Not affiliated with, endorsed by, or supported by Kiro or Amazon. Naming and distribution are unresolved — see F-23.
