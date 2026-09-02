# ADR-001: How the app reaches Kiro cloud sessions

- **Status:** Accepted — the six assumptions in §5 were verified against a real `kiro-cli` by [F-01](../FEATURES.md#f-01--protocol-spike-verify-assumptions-capture-golden-fixtures) on 2026-09-02. The decision stands; see [PROTOCOL-FINDINGS.md](../PROTOCOL-FINDINGS.md) for what changed underneath it.
- **Date:** 2026-09 (assumptions resolved 2026-09-02)
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
| Streaming update kinds: `AgentMessageChunk`, `ToolCall`, `ToolCallUpdate`, `TurnEnd` — *this list is incomplete and the spellings are wrong; see [PROTOCOL-FINDINGS §5](../PROTOCOL-FINDINGS.md#5-corrections-to-the-documented-protocol)* | [ACP](https://kiro.dev/docs/cli/acp/) |
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

There is also a **documentation inconsistency**: [How Kiro works](https://kiro.dev/docs/how-kiro-works/) describes the extension namespace as `_kiro/`, while [the ACP page](https://kiro.dev/docs/cli/acp/) uses `_kiro.dev/`. F-01 settled it — the live prefix is **`_kiro/`** — but the client should still derive it from the `initialize` handshake, which enumerates the agent's extension methods, rather than hard-code either spelling.

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

The user runs a small bridge process on a machine they control (home server, workstation, cheap VPS) where `kiro-cli` is installed and signed in. It does **not** need a checkout of the user's code or any git credentials — see [ADR-004 §2](ADR-004-work-repo-selection.md#2-what-we-found-how-kiro-cli-actually-selects-the-work-repo) — and where it should run is decided in [ADR-005](ADR-005-bridge-hosting-and-availability.md). The bridge:

1. spawns `kiro-cli acp --agent-engine v3 --auth-method cli` (documented: ACP agent, JSON-RPC 2.0 over stdio) and creates cloud sessions in-protocol — F-01 confirmed the `--cloud`/`--resume-id` shell-out is not needed;
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
- **Verdict:** **Pursue in parallel.** Cheap to do, and it is the thing that would change the architecture most. Track as F-00.

**Status, surveyed 2026-09-02.** Three requests for something close to this are open on `kirodotdev/Kiro` and none has been committed to: [#6099](https://github.com/kirodotdev/Kiro/issues/6099) (IDE remote control, 19 reactions, labelled `keep-open`), [#7993](https://github.com/kirodotdev/Kiro/issues/7993) (remote **CLI** sessions from web/mobile — closest to our brief, labelled `pending-maintainer-response`), and [#9460](https://github.com/kirodotdev/Kiro/issues/9460) (remote access to a running IDE session), where a maintainer replied on **2026-07-16** that it was added to the backlog for future consideration.

Two things to read out of that. The maintainer reply is a **weaker signal than a commitment but a stronger one than silence**, which is what F-00 previously recorded — this option is not dead. But all three requests are about reaching a **local** session remotely; *none* asks for third-party API access to **cloud** sessions, which is what would actually retire Option B. That gap is F-00's real job.

**Which one to join is not a coin flip between the three.** #9460 is explicitly about the **IDE** session and explicitly says it does *not* want the `kiro-cli` + ACP path — joining it would argue for the wrong surface. #7993 asks for remote **CLI** sessions from web/mobile, which is our brief exactly; that is the one to join. #6099 (IDE, highest reaction count) is worth an upvote for general pressure but is, like #9460, an IDE-surface ask. See F-00 for the up-to-date read.

Note also that all three are periodically flagged as duplicates of each other by Kiro's automation and rescued by their authors, so demand is split three ways and never accumulates into a single number.

### Option D — Do nothing / wait for official Android

Kiro's docs say iOS with "documentation coming soon"; nothing published commits to Android.

- **Verdict:** Rejected. An unofficial client has obvious value in the gap, and the gap has no announced end date.

---

## 3. Decision

**Build the app against an internal gateway abstraction, and ship Option B as the first and only backend implementation.**

Two rules follow, and they are the whole point of this ADR:

1. **The app never hard-codes a backend.** All session operations go through one interface — call it `CloudSessionGateway` — with `createSession`, `listSessions`, `loadSession`, `prompt`, `cancel`, `setModel`, `respondToPermission`, and a `Flow` of session updates. (The shipped interface has grown beyond this list — `respondToUserInput` and flows for `userInputRequests`/roster changes were added as F-01 and later work surfaced channels this ADR didn't anticipate; see [F-05](../FEATURES.md) and [F-14](../FEATURES.md) — but the shape of the rule, not the exact method list, is what this ADR fixes.) `BridgeGateway` implements it. If Option A or C ever becomes viable, it is a new implementation of the same interface, not a rewrite.
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

## 5. Assumptions — **resolved by F-01 on 2026-09-02**

These were load-bearing and unverified when this ADR was written. They have since been checked against a real `kiro-cli 2.19.2` install (KAS 0.52.1). Full report, captured frames and implications: [PROTOCOL-FINDINGS.md](../PROTOCOL-FINDINGS.md).

| # | Assumption | Verdict |
|---|---|---|
| A1 | `kiro-cli acp` can attach to a **cloud** session, not only local ones | **Verified.** `session/load` on a `cloud-sandbox` session succeeded and replayed 991 updates. The `--resume-id` fallback is not needed. |
| A2 | A cloud session is reachable through `session/load` by ID | **Verified.** Store and placement are selected per-request via `params._meta.kiro.{sessionSource, listScope, executionTarget}`. Cloud sessions are also *listable* over ACP, with repositories and status. |
| A3 | The extension prefix is `_kiro.dev/` rather than `_kiro/` | **Refuted, harmlessly.** It is `_kiro/`; the ACP docs page is wrong. Better: `initialize` enumerates the agent's extension methods, so the client should derive the prefix rather than hard-code either spelling. |
| A4 | Repository selection is reachable programmatically | **Verified for enumeration**, and better than assumed — `_kiro/sourceProviders/list` and `/listResources` return a full repo catalog with visibility and default branch, no `--repo` flag or slash command needed. But this conflated two questions with different answers: enumerating repos is solved; whether a session can be *created* bound to one **non-interactively** is not. **Superseded by [ADR-004 §7](ADR-004-work-repo-selection.md#7-assumptions-to-verify--extends-adr-001-5-same-numbering) (A7–A12)** for the part that remains open (A8). |
| A5 | Permission requests arrive as answerable server-initiated ACP requests | **Verified on a local session.** `session/request_permission` carries the options and a `_meta.kiro.consent` description; a plain JSON-RPC response resolves it. **Not yet observed on a cloud session** — F-03 must confirm on its first cloud turn, and [ADR-005 §7](ADR-005-bridge-hosting-and-availability.md#7-assumptions-to-verify--extends-adr-001-5-and-adr-004-7) A14 extends this to durability across reattach on a cloud session specifically. |
| A6 | `login --use-device-flow` can be driven non-interactively | **Partially refuted.** The verification URI and user code *are* printed parseably, but the CLI first shows an interactive provider-picker TUI with no flag to preselect. The bridge must drive it over a **pty**, and provider choice moves into the app. `login` also refuses while signed in, so re-auth needs an explicit `logout`. Consistent with independent evidence: `kiro-cli` 2.18.1 was reported to fail non-interactive use with `Failed to open browser for authentication. Please try again with: kiro-cli login --use-device-flow` on a headless host ([kirodotdev/Kiro#10885](https://github.com/kirodotdev/Kiro/issues/10885)) — this spike's pty-driven capture is what makes the flag usable from a bridge despite that. |

**One correction that is not an assumption, and matters more than any of them:** the default `kiro-cli acp` engine cannot reach cloud sessions at all. Every client must pass `--agent-engine v3 --auth-method cli`. See PROTOCOL-FINDINGS §2.

**A second:** much of what §3's topology assumed the bridge would build — WebSocket ACP transport, multi-client multiplexing, permission correlation across reconnects, pending-permission re-send on attach — already exists inside KAS. F-03's scope shrinks accordingly. See PROTOCOL-FINDINGS §4.

Two later ADRs extend this list rather than starting their own: [ADR-004 §7](ADR-004-work-repo-selection.md#7-assumptions-to-verify--extends-adr-001-5-same-numbering) adds **A7–A12** (repository binding and enumeration) and [ADR-005 §7](ADR-005-bridge-hosting-and-availability.md#7-assumptions-to-verify--extends-adr-001-5-and-adr-004-7) adds **A13–A18** (bridge fungibility, durable approvals, headless CLI residency, and — added 2026-09-02 — whether `KIRO_API_KEY` authenticates an `acp` session at all). The numbering is global on purpose — there is one list of things we do not yet know.

---

## 6. Consequences

- Every feature in [FEATURES.md](../FEATURES.md) is written against `CloudSessionGateway`, not against a network client.
- The bridge is a first-class deliverable of this project, not a dev convenience.
- Onboarding must explain the bridge requirement honestly and early.
- If Option C lands, the migration is one new gateway implementation plus an onboarding change — by design.
- Two follow-on ADRs take up what this one deferred: [ADR-004](ADR-004-work-repo-selection.md) on how a work repository is chosen, and [ADR-005](ADR-005-bridge-hosting-and-availability.md) on where the bridge runs and what the app does when it is unreachable.

---

*All external facts are cited inline and paraphrased.*
