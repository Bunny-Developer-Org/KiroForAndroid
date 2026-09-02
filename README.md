# KiroForAndroid

An unofficial Android client for [Kiro](https://kiro.dev) **cloud sessions** — agent runs that live in a managed cloud sandbox and keep working after the client disconnects. Kiro ships an iOS app and no Android one, publishes no third-party API, and has three open feature requests asking for exactly this that nobody has committed to (tracked in [F-00](docs/FEATURES.md)). That gap is the whole reason this project exists.

## Status, at a glance

Of the 26 items in [docs/FEATURES.md](docs/FEATURES.md): **7 done, 5 partial, the rest not started.** Done: project scaffold/CI, the ACP protocol layer, the session gateway, credential storage, session list (delete/pin), the prompt composer. Partial: bridge pairing (multi-bridge list works, QR scan doesn't), the new-session flow, transcript rendering, permission/approval UI, and reconnect/replay hardening.

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
