# Kiro for Android

An **unofficial** Android client for [Kiro](https://kiro.dev) cloud sessions — start, steer, and approve agent work from your phone.

Kiro ships a native **iOS** app (TestFlight early access) and, as of this writing, no Android app. This project aims to close that gap.

> ### Status: planning, with the protocol verified. No app code yet.
>
> This repo contains the **plan** — an architecture decision record set and a feature backlog written so that implementation can be picked up by multiple contributors (or agents) working in parallel — plus the output of the one spike that had to come first.
>
> **[F-01 has reported.](docs/PROTOCOL-FINDINGS.md)** The six load-bearing assumptions were checked against a real `kiro-cli` on 2026-09-02: four verified, one refuted harmlessly, one partially refuted. **The architecture holds, and `kiro-cli acp` does reach cloud sessions.** Real JSON-RPC frames are committed as [test fixtures](core/src/test/resources/fixtures/).
>
> Start with [FEATURES.md](docs/FEATURES.md), and read [PROTOCOL-FINDINGS.md](docs/PROTOCOL-FINDINGS.md) before picking up anything in Phase 1+.

---

## Read this first: the constraint that shapes everything

Kiro's documentation describes cloud sessions thoroughly, and confirms that all four first-party surfaces (IDE, CLI, Web, Mobile) reach the agent harness over the **Agent Client Protocol** — JSON-RPC 2.0, with Web and Mobile using a WebSocket transport.

What it does **not** publish is anything a third party could build against directly:

- no documented endpoint for the cloud-session WebSocket,
- no documented API to create, list, or delete a cloud session,
- no OAuth client registration for third-party apps,
- and the CLI is not open source, so the protocol can't be learned from the client.

So this app **cannot** talk straight to Kiro's cloud, and we have chosen **not** to reverse-engineer a private API — that would produce something that demos well and breaks without warning, while holding credentials that let an agent write to the user's repositories.

**Instead:** the app is an ACP client that talks to a small **bridge** the user runs on a machine where `kiro-cli` is already installed and signed in. The bridge speaks only documented interfaces. Kiro credentials never leave the user's own machine.

F-01 confirmed this works, and simplified it: the bridge does not need to shell out to `--cloud` and re-attach by `--resume-id`. `kiro-cli acp --agent-engine v3 --auth-method cli` lists, creates, loads and drives cloud sessions entirely in-protocol. It also found that a naive `kiro-cli acp` with default flags is local-only — which would make the whole approach look impossible if you didn't know.

```
Android app  ──WSS/ACP──►  bridge (user-hosted)  ──stdio/ACP──►  kiro-cli  ──►  Kiro cloud sandbox
```

The honest cost: **you need a machine running the bridge.** This is not a phone-only app, and it is not at parity with Kiro's iOS app. The full reasoning, the rejected alternatives, and the conditions that would change this are in [ADR-001](docs/adr/ADR-001-cloud-session-access.md).

## Signing in

The requirement is to sign in with your **Kiro account via OAuth in a web browser**, and that works without the app ever holding a Kiro credential.

The bridge runs `kiro-cli login --use-device-flow`, which performs the OAuth 2.0 Device Authorization Grant. It returns a verification URL and a user code; the app opens the URL in a Custom Tab; you sign in to Kiro in a real browser; the CLI completes the exchange on the bridge host.

One correction from F-01: the CLI asks **which provider** before it prints anything, on an interactive prompt with no flag to skip it. So the app shows the four choices itself — Builder ID, Google, GitHub, or your organization — and the bridge drives the CLI over a pty. Slightly more work, and arguably a better first screen. See [AUTHENTICATION.md](docs/AUTHENTICATION.md).

## Documents

| Document | What it covers |
|---|---|
| **[docs/FEATURES.md](docs/FEATURES.md)** | **The backlog.** 25 work items in 5 phases, with acceptance criteria, dependencies, and a parallelisation graph. Start here to contribute. |
| **[docs/PROTOCOL-FINDINGS.md](docs/PROTOCOL-FINDINGS.md)** | **What the protocol actually does**, from frames captured off a real `kiro-cli`. Corrects the published docs in several places. Read before writing protocol code. |
| [docs/ACP-INTEGRATION.md](docs/ACP-INTEGRATION.md) | The protocol contract to implement — handshake, session lifecycle, streaming updates, extensions, reconnect/replay |
| [docs/AUTHENTICATION.md](docs/AUTHENTICATION.md) | Sign-in design: device flow relayed through the app, bridge pairing, token storage, auth state machine |
| [docs/VISUAL-LANGUAGE.md](docs/VISUAL-LANGUAGE.md) | **The look.** Colour tokens, type scale, shape, motion and per-screen hints, steered onto Kiro Crew's aesthetic rather than Kiro IDE's or Web's |
| [ADR-001](docs/adr/ADR-001-cloud-session-access.md) | **How the app reaches cloud sessions.** The constraint above, options considered, and the six assumptions that must be verified before building |
| [ADR-002](docs/adr/ADR-002-react-native-vs-native.md) | React Native vs. native Kotlin/Compose. Recommends native (high confidence) with explicit flip conditions |
| [ADR-003](docs/adr/ADR-003-tech-stack.md) | Stack, module layout, and the conventions that keep parallel work coherent |

## Planned scope

**Phase 3 target — the minimum that justifies the app:** sign in with your Kiro account, create a cloud session against one or more GitHub/GitLab repos with a model and autonomy level, watch the agent work in a live transcript, answer its approval requests from a notification, and get the pull request link when it's done.

Every one of those now has a verified mechanism behind it — session listing and creation, the repository catalog, streaming, and approvals were all exercised against a real CLI.

Deliberately out of scope for now: scheduled automations (Web-only in Kiro), branch selection at attach time and session renaming (not supported in the cloud-session preview), and anything requiring local filesystem access (the workspace lives in the cloud sandbox).

## Contributing

Pick an item from [FEATURES.md](docs/FEATURES.md) and read *How to pick up an item* at the bottom of that file. Three things matter most:

1. **[ADR-001](docs/adr/ADR-001-cloud-session-access.md) is binding.** PRs adding reverse-engineered Kiro endpoints will be declined on principle, not on style.
2. **[PROTOCOL-FINDINGS.md](docs/PROTOCOL-FINDINGS.md) supersedes the published protocol docs** wherever they disagree. It was written from captured frames; several things Kiro documents are wrong or incomplete.
3. **`core/` stays Android-free**, and all UI goes through the `CloudSessionGateway` seam. CI is meant to enforce the former.

The genuinely open questions right now: whether Kiro would sanction a third-party client at all (F-00), what this project may legitimately be called given it uses Kiro's name (F-23), and how much of the bridge (F-03) is left once you subtract what KAS already does.

---

**Not affiliated with, endorsed by, or supported by Kiro or Amazon.** "Kiro" is used here only to describe what this client connects to; naming and branding are an open question tracked in F-23. External facts are cited inline in each document and paraphrased from their sources.
