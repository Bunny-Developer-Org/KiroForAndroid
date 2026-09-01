# Kiro for Android

An **unofficial** Android client for [Kiro](https://kiro.dev) cloud sessions — start, steer, and approve agent work from your phone.

Kiro ships a native **iOS** app (TestFlight early access) and, as of this writing, no Android app. This project aims to close that gap.

> ### Status: planning. There is no code in this repository yet.
>
> This repo currently contains the **plan** — an architecture decision record set and a feature backlog written so that implementation can be picked up by multiple contributors (or agents) working in parallel. Start with [FEATURES.md](docs/FEATURES.md).

---

## Read this first: the constraint that shapes everything

Kiro's documentation describes cloud sessions thoroughly, and confirms that all four first-party surfaces (IDE, CLI, Web, Mobile) reach the agent harness over the **Agent Client Protocol** — JSON-RPC 2.0, with Web and Mobile using a WebSocket transport.

What it does **not** publish is anything a third party could build against directly:

- no documented endpoint for the cloud-session WebSocket,
- no documented API to create, list, or delete a cloud session,
- no OAuth client registration for third-party apps,
- and the CLI is not open source, so the protocol can't be learned from the client.

So this app **cannot** talk straight to Kiro's cloud, and we have chosen **not** to reverse-engineer a private API — that would produce something that demos well and breaks without warning, while holding credentials that let an agent write to the user's repositories.

**Instead:** the app is an ACP client that talks to a small **bridge** the user runs on a machine where `kiro-cli` is already installed and signed in. The bridge speaks only documented interfaces (`kiro-cli acp`, `--cloud`, `--repo`, `--resume-id`). Kiro credentials never leave the user's own machine.

```
Android app  ──WSS/ACP──►  bridge (user-hosted)  ──stdio/ACP──►  kiro-cli  ──►  Kiro cloud sandbox
```

The honest cost: **you need a machine running the bridge.** This is not a phone-only app, and it is not at parity with Kiro's iOS app. The full reasoning, the rejected alternatives, and the conditions that would change this are in [ADR-001](docs/adr/ADR-001-cloud-session-access.md).

## Signing in

The requirement is to sign in with your **Kiro account via OAuth in a web browser**, and that works without the app ever holding a Kiro credential.

The bridge runs `kiro-cli login --use-device-flow`, which performs the OAuth 2.0 Device Authorization Grant. It returns a verification URL and a user code; the app opens the URL in a Custom Tab and shows the code; you sign in to Kiro in a real browser; the CLI completes the exchange on the bridge host.

Because provider selection happens in the browser, this works with every provider Kiro supports — GitHub, Google, AWS Builder ID, IAM Identity Center, and org IdPs — without the app knowing which one you use. See [AUTHENTICATION.md](docs/AUTHENTICATION.md).

## Documents

| Document | What it covers |
|---|---|
| **[docs/FEATURES.md](docs/FEATURES.md)** | **The backlog.** 24 work items in 5 phases, with acceptance criteria, dependencies, and a parallelisation graph. Start here to contribute. |
| [docs/ACP-INTEGRATION.md](docs/ACP-INTEGRATION.md) | The protocol contract to implement — handshake, session lifecycle, streaming updates, extensions, reconnect/replay |
| [docs/AUTHENTICATION.md](docs/AUTHENTICATION.md) | Sign-in design: device flow relayed through the app, bridge pairing, token storage, auth state machine |
| [ADR-001](docs/adr/ADR-001-cloud-session-access.md) | **How the app reaches cloud sessions.** The constraint above, options considered, and the six assumptions that must be verified before building |
| [ADR-002](docs/adr/ADR-002-react-native-vs-native.md) | React Native vs. native Kotlin/Compose. Recommends native (high confidence) with explicit flip conditions |
| [ADR-003](docs/adr/ADR-003-tech-stack.md) | Stack, module layout, and the conventions that keep parallel work coherent |

## Planned scope

**Phase 3 target — the minimum that justifies the app:** sign in with your Kiro account, create a cloud session against one or more GitHub/GitLab repos with a model and autonomy level, watch the agent work in a live transcript, answer its approval requests from a notification, and get the pull request link when it's done.

Deliberately out of scope for now: scheduled automations (Web-only in Kiro), branch selection at attach time and session renaming (not supported in the cloud-session preview), and anything requiring local filesystem access (the workspace lives in the cloud sandbox).

## Contributing

Pick an item from [FEATURES.md](docs/FEATURES.md) and read *How to pick up an item* at the bottom of that file. Three things matter most:

1. **[ADR-001](docs/adr/ADR-001-cloud-session-access.md) is binding.** PRs adding reverse-engineered Kiro endpoints will be declined on principle, not on style.
2. **F-01 comes first.** Six architectural assumptions are still unverified. Work that depends on them should say so explicitly.
3. **`core/` stays Android-free**, and all UI goes through the `CloudSessionGateway` seam. CI is meant to enforce the former.

The three genuinely open questions right now: whether `kiro-cli acp` can attach to a *cloud* session (ADR-001 A1/A2), whether Kiro would sanction a third-party client at all (F-00), and what this project may legitimately be called given it uses Kiro's name (F-23).

---

**Not affiliated with, endorsed by, or supported by Kiro or Amazon.** "Kiro" is used here only to describe what this client connects to; naming and branding are an open question tracked in F-23. External facts are cited inline in each document and paraphrased from their sources.
