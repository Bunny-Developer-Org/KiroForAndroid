# ADR-001: How the app reaches Kiro cloud sessions

- **Status:** Proposed — blocks every other work item
- **Date:** 2026-09
- **Scope:** The transport and trust topology between the Android app and Kiro cloud sessions. Does not decide UI or runtime (see ADR-002, ADR-003).

---

## 1. The problem

The app's headline feature is "create a new Kiro cloud session from your phone." Before any of that can be built, one question has to be answered: **what does the app actually talk to?**

Research into Kiro's public documentation produced a clear and uncomfortable answer.

### What is publicly documented

| Fact | Source |
|---|---|
| Cloud sessions are the execution environment behind Kiro Web **and Mobile**; the IDE and CLI create and attach to the same sessions | [Cloud sessions](https://kiro.dev/docs/cloud-sessions/) |
| Every surface talks to the same agent harness over the **Agent Client Protocol** (ACP), which is JSON-RPC 2.0 | [How Kiro works](https://kiro.dev/docs/how-kiro-works/) |
| Local clients connect over **stdio**; **Web and Mobile connect to a sandboxed harness over WebSocket** | [How Kiro works](https://kiro.dev/docs/how-kiro-works/) |
| ACP methods the harness implements: `initialize`, `session/new`, `session/load`, `session/prompt`, `session/cancel`, `session/set_mode`, `session/set_model` | [ACP](https://kiro.dev/docs/cli/acp/) |
| Streaming update kinds: `AgentMessageChunk`, `ToolCall`, `ToolCallUpdate`, `TurnEnd` | [ACP](https://kiro.dev/docs/cli/acp/) |
| Kiro-specific extensions are namespaced (slash commands, MCP OAuth, compaction status) and marked **experimental and subject to change** | [ACP](https://kiro.dev/docs/cli/acp/) |
| The CLI creates cloud sessions with `kiro-cli --cloud [--repo owner/repo,...]`, and manages them with `chat --list-sessions`, `--resume-id`, `--delete-session` | [Cloud sessions](https://kiro.dev/docs/cloud-sessions/), [CLI commands](https://kiro.dev/docs/reference/cli-commands/) |
| `kiro-cli acp` exposes the whole agent as an ACP agent over stdio, JSON-RPC 2.0 | [ACP](https://kiro.dev/docs/cli/acp/) |
| Mobile capability table: create ✓, resume/steer ✓, autonomous ✓, scheduled automations ✗ | [Cloud sessions](https://kiro.dev/docs/cloud-sessions/) |
| Preview constraints: 10 concurrent sessions, `us-east-1` only, Pro plan or higher, no branch selection at attach time, Autopilot/Autonomous only (no Supervised), no renaming | [Cloud sessions](https://kiro.dev/docs/cloud-sessions/) |

### What is *not* documented — and this is the crux

- **No public endpoint.** The hostname, path, and framing of the WebSocket that Kiro Web and iOS use to reach a cloud session are not published.
- **No public session-management API.** There is no documented REST/GraphQL surface to create, list, or delete a cloud session. Every documented path to session CRUD goes through a first-party client.
- **No published OAuth client registration.** Kiro sign-in supports GitHub, Google, AWS Builder ID, IAM Identity Center, and org IdPs, but no `client_id`, authorization endpoint, or token endpoint is documented for third-party clients. The one redirect URI that appears in the docs — `https://app.kiro.dev/signin/oauth` — belongs to **Kiro Web**, not to us.
- **The CLI is not open source.** `kirodotdev/Kiro` is an issue tracker, not source. (`KiroCrew` is open source but is a different product.) So the protocol cannot be learned by reading the client.
- **Extensions are explicitly unstable.** The docs warn the `_kiro` extension methods may change without notice.

There is also a **documentation inconsistency worth resolving before implementation**: [How Kiro works](https://kiro.dev/docs/how-kiro-works/) describes the extension namespace as `_kiro/`, while [the ACP page](https://kiro.dev/docs/cli/acp/) uses `_kiro.dev/`. Both cannot be right. Treat the exact prefix as **unverified** and discover it at runtime (see F-01).

### Consequence

> **The app cannot be built directly against Kiro's cloud session API, because from a third party's perspective that API does not exist yet.**

Any code written today that POSTs to a guessed `app.kiro.dev` endpoint would be (a) reverse-engineered from a private surface, (b) unversioned and breakable at any deploy, and (c) plausibly a terms-of-service problem. Shipping that would produce an app that *looks* functional in a demo and is unmaintainable in reality. That is the worst available outcome, and this ADR exists to rule it out explicitly.

---

## 2. Options

### Option A — Direct to Kiro's private cloud API

Reverse-engineer the Web/iOS client's endpoint, auth, and framing; speak it from Android.

- **Pro:** the ideal end-state UX. No user-side infrastructure. True parity with the iOS app.
- **Con:** requires reverse-engineering an undocumented surface; no compatibility contract; breaks on any server deploy; likely violates terms of service; the extension methods are declared unstable even for documented clients.
- **Verdict:** **Rejected for now.** Not on technical grounds alone — on the grounds that we would be building on a contract that nobody has promised us. Revisit if Kiro publishes an API (see Option C).

### Option B — Self-hosted ACP bridge fronting `kiro-cli`

The user runs a small bridge process on a machine they control (home server, workstation, cheap VPS) where `kiro-cli` is installed and signed in. The bridge:

1. spawns `kiro-cli acp` (documented: ACP agent, JSON-RPC 2.0 over stdio), and/or drives `kiro-cli --cloud --repo …` to create cloud sessions;
2. exposes that JSON-RPC stream over an authenticated WebSocket;
3. the Android app is an ACP **client** on the other end.

Because the harness is a standalone process and the boundary is standard ACP, this is architecturally the same move JetBrains IDEs and Zed already make — Kiro documents that use case. The bridge just changes the transport from stdio to WebSocket, which is the same substitution Kiro itself makes for Web and Mobile.

- **Pro:** uses **only documented interfaces** — `kiro-cli acp`, `--cloud`, `--repo`, `--resume-id`, and the published ACP method set. Works today. No reverse engineering. The user's Kiro credentials never leave a machine they own. Degrades honestly: if the CLI changes, the failure is visible and local.
- **Con:** requires the user to run infrastructure — a real adoption barrier, and it makes the app useless to someone with only a phone. The bridge host must stay online. Adds a hop and a second thing to secure.
- **Verdict:** **Recommended as the first shipping backend.** It is the only option that is fully legitimate and fully functional today.

### Option C — Official API access

Ask Kiro for a documented third-party API, or for the app to be sanctioned.

- **Pro:** the only path to the phone-only experience Option A imagines, with a contract behind it.
- **Con:** not in our control; unknown timeline; may never happen.
- **Verdict:** **Pursue in parallel.** Cheap to do, and it is the thing that would change the architecture most. Concretely: open a request on `kirodotdev/Kiro` and ask on the Kiro Discord. Track as F-00.

### Option D — Do nothing / wait for official Android

Kiro's docs say iOS with "documentation coming soon"; nothing published commits to Android.

- **Verdict:** Rejected. An unofficial client has obvious value in the gap, and the gap has no announced end date.

---

## 3. Decision

**Build the app against an internal gateway abstraction, and ship Option B as the first and only backend implementation.**

Two rules follow, and they are the whole point of this ADR:

1. **The app never hard-codes a backend.** All session operations go through one interface — call it `CloudSessionGateway` — with `createSession`, `listSessions`, `loadSession`, `prompt`, `cancel`, `setModel`, `respondToPermission`, and a `Flow` of session updates. `BridgeGateway` implements it. If Option A or C ever becomes viable, it is a new implementation of the same interface, not a rewrite.
2. **No reverse-engineered endpoints land in this repo.** A PR that adds a guessed `app.kiro.dev` API call gets rejected on principle, not on style. If someone wants that, it belongs in a fork.

### Topology

```
┌─────────────────────┐         ┌──────────────────────────┐        ┌────────────────────┐
│  Android app        │   WSS   │  Bridge (user-hosted)    │ stdio  │  kiro-cli          │
│  ACP client         │◄───────►│  paired, token-auth'd    │◄──────►│  (signed in)       │
│  Compose UI         │  ACP /  │  spawns + supervises CLI │  ACP   │                    │
└─────────────────────┘ JSON-RPC└──────────────────────────┘        └─────────┬──────────┘
                                                                              │ Kiro's own
                                                                              │ protocol
                                                                              ▼
                                                                   ┌────────────────────┐
                                                                   │ Kiro cloud sandbox │
                                                                   │  agent harness     │
                                                                   │  (clones repos,    │
                                                                   │   opens PRs)       │
                                                                   └────────────────────┘
```

The app is an ACP client. The bridge is a transport adapter. `kiro-cli` owns everything about Kiro's cloud protocol, including auth — which is exactly why we don't have to.

### How this interacts with "sign in with your Kiro account via a web link"

The two requirements fit together better than they first appear, because there are **two distinct authentications** and only one of them is Kiro's:

- **App ↔ Bridge:** device pairing. Our own concern, our own credential. Not OAuth.
- **Bridge host ↔ Kiro:** the user's real Kiro account, via `kiro-cli login`.

The second one can be driven *from the phone* without the app ever holding Kiro's OAuth secrets. `kiro-cli login --use-device-flow` implements the OAuth 2.0 Device Authorization Grant: it returns a verification URL and a user code, and the user completes sign-in in a browser. The bridge relays that URL to the app; the app opens it in a Custom Tab; the user signs in to their Kiro account on the web; the CLI polls and completes.

That is literally "log in with your Kiro account using OAuth via a web link," and it is satisfied using a documented CLI flag. Full detail, including the direct auth-code + PKCE variant kept in reserve for Option A/C, is in [AUTHENTICATION.md](../AUTHENTICATION.md).

---

## 4. What this costs us, stated plainly

This decision trades reach for legitimacy. The honest consequences:

- The app is **not** usable by someone who only has a phone. It requires a machine running the bridge. That is a significant product limitation and should be stated on the first screen of onboarding, not buried.
- We are **not** at parity with Kiro's iOS app and cannot be until Option C lands.
- Latency gains a hop, and the bridge is a new failure mode to surface well in the UI.

The alternative — a phone-only app built on a guessed private API — buys reach with a foundation that can break without warning and shouldn't be published. Given this is a third-party client holding credentials that let an agent write to the user's repositories, the conservative call is the right one.

---

## 5. Assumptions to verify before F-03 starts

These are load-bearing and currently **unverified**. Each is cheap to check against a real `kiro-cli` install, and each is a genuine risk to the plan.

| # | Assumption | Risk if wrong |
|---|---|---|
| A1 | `kiro-cli acp` can attach to a **cloud** session, not only local ones | High — the bridge would have to drive the interactive TUI instead, which is far worse. Mitigation: bridge creates sessions via `--cloud` and attaches via `--resume-id`. |
| A2 | A cloud session created by `--cloud` is reachable through the ACP `session/load` method by ID | High — same as A1. |
| A3 | The `_kiro` extension prefix is `_kiro.dev/` (per the ACP page) rather than `_kiro/` (per How Kiro works) | Low — discoverable at runtime; handle both. |
| A4 | Repository selection is reachable programmatically (via `--repo` at creation, or the `/repo` slash command through `_kiro…/commands/execute`) | Medium — the repo picker is core to session creation. |
| A5 | Permission/approval requests arrive as server-initiated ACP requests the client can answer | High — approvals are a headline feature. Docs say a waiting request is presented to the next client that attaches, which implies yes. |
| A6 | `kiro-cli login --use-device-flow` can be driven non-interactively enough to scrape the verification URI and code | Medium — otherwise sign-in is a one-time manual step on the bridge host, which is acceptable but worse UX. |

**F-01 is a spike whose only job is to answer A1–A6.** Nothing downstream should be estimated until it reports.

---

## 6. Consequences

- Every feature in [FEATURES.md](../FEATURES.md) is written against `CloudSessionGateway`, not against a network client.
- The bridge is a first-class deliverable of this project, not a dev convenience.
- Onboarding must explain the bridge requirement honestly and early.
- If Option C lands, the migration is one new gateway implementation plus an onboarding change — by design.

---

*All external facts are cited inline and paraphrased.*
