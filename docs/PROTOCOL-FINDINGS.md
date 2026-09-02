# F-01 · Protocol spike findings

**Run:** 2026-09-02 · `kiro-cli 2.19.2` on Linux · KAS (Kiro Agent Server) `0.52.1` · account signed in via Google social login.

This is the written report [F-01](FEATURES.md#f-01--protocol-spike-verify-assumptions-capture-golden-fixtures) asks for. It answers the six assumptions in [ADR-001 §5](adr/ADR-001-cloud-session-access.md#5-assumptions--resolved-by-f-01-on-2026-09-02), records what the plan got wrong, and says what each correction implies.

**Headline: the architecture survives, and it is cheaper than we costed it.** `kiro-cli acp` does reach cloud sessions — five of the six assumptions are verified. The one real correction is to sign-in (A6), which is *more* interactive than assumed, not less. The larger surprise is how much of what F-03 was scoped to build already exists inside KAS.

---

## 1. How the answers were obtained

Everything below came from driving the documented `kiro-cli acp` interface over stdio with a small JSON-RPC probe, and from reading the type declarations KAS ships alongside its own bundle on this machine. Both are local surfaces of software the user installed.

No request was made to an undocumented Kiro endpoint, and nothing here required guessing a wire format: **the agent describes its own extension surface in the `initialize` response**, which is the sanctioned discovery path. [ADR-001 §3 rule 2](adr/ADR-001-cloud-session-access.md#3-decision) is intact.

The probe is committed at [`tools/acp-probe/`](../tools/acp-probe/) so any finding here can be re-derived, and the fixtures regenerated, in one command.

---

## 2. The one thing the plan missed entirely: `--agent-engine v3`

The plan assumed `kiro-cli acp` is a single agent surface. It is not. It has three engines, and **the default is not the one that speaks cloud**:

```bash
kiro-cli acp --agent-engine v3 --auth-method cli
```

| | default (`v2`) | `v3` (KAS) |
|---|---|---|
| `agentInfo` | `Kiro CLI Agent 2.19.2` | KAS 0.52.1 |
| `loadSession` | ✓ | ✓ |
| Extension surface advertised | none | 24 methods, named explicitly |
| `sessionSources` | — | `["local", "remote"]` |
| `executionTargets` | — | `["local", "cloud-sandbox"]` |
| Cloud sessions reachable | **no** | **yes** |

A client that opens `kiro-cli acp` with default flags gets a local-only agent and would conclude — as the plan nearly did — that cloud sessions are unreachable over ACP. **Every downstream item must pass `--agent-engine v3 --auth-method cli`.**

**This is not a hypothetical failure mode.** [`ajitnk-lab/kiro-acp-telegram-bot`](https://github.com/ajitnk-lab/kiro-acp-telegram-bot) is a working, published ACP client for `kiro-cli` — and it spawns `kiro-cli acp --trust-all-tools` with no engine flag. Its author built and shipped an entire product on the v2 surface without ever discovering the cloud path existed. That is independent external confirmation that the default is a trap a competent developer walks straight into, and it is the strongest argument for stating these flags first in every downstream brief. See [PRIOR-ART.md §1](PRIOR-ART.md#1-ajitnk-labkiro-acp-telegram-bot--stdio-acp-local-engine).

Two flag interactions worth knowing: `--model` is rejected with `--agent-engine=v3` (set the model over ACP instead), and `--auth-method cli` keeps token resolution inside the CLI process, which is what lets the bridge stay credential-free.

---

## 3. Verdicts

### A1 — `kiro-cli acp` can attach to a cloud session · **VERIFIED**

`session/load` against a session whose `executionTarget` is `{kind: "cloud-sandbox"}` succeeded and replayed **991 `session/update` notifications** — the session's full history. The sandbox instance was `suspended` at the time and the load drove it back up over the relay link.

The mitigation ADR-001 held in reserve — bridge shells out to `--cloud` and re-attaches by `--resume-id` — is **not needed**. The whole lifecycle is in-protocol.

### A2 — a `--cloud` session is reachable by ID through `session/load` · **VERIFIED**

Both halves hold, and the dispatch mechanism is the thing the plan didn't have:

**Requests carry their store and scope in `params._meta.kiro`.**

```jsonc
// session/list — the cloud roster
{ "_meta": { "kiro": { "sessionSource": "remote", "listScope": "user" } } }

// session/load — a specific cloud session
{ "sessionId": "…", "cwd": "…", "mcpServers": [],
  "_meta": { "kiro": { "sessionSource": "remote" } } }

// session/new — create in the cloud
{ "cwd": "…", "mcpServers": [],
  "_meta": { "kiro": { "executionTarget": { "kind": "cloud-sandbox" } } } }
```

- `sessionSource`: `"local" | "remote" | "all"` (`"all"` is valid only on `session/list`)
- `listScope`: `"workspace" | "user" | "both"` — gated on the agent advertising the `user` scope
- `executionTarget`: `{kind: "local"} | {kind: "cloud-sandbox"}`

These are a **closed union, strictly parsed**: an unrecognised value is an `InvalidParamsError`, never a silent fallback to the default. And `sessionSource` and `executionTarget` are two spellings of one decision — naming both with values that disagree is rejected before any session exists. Send one or the other, not a conflicting pair.

Listed cloud sessions come back with everything a session list screen needs:

```jsonc
{ "sessionId": "…", "cwd": "", "title": "…", "updatedAt": "…",
  "_meta": { "kiro": {
    "source": "remote",
    "executionTarget": { "kind": "cloud-sandbox" },
    "status": "idle",                    // idle | in_progress
    "instanceStatus": "suspended",       // sandbox VM lifecycle
    "repositories": [ { "providerType": "GITHUB", "name": "owner/repo", "url": "…" } ],
    "agentMode": "vibe", "createdAt": "…" } } }
```

`status` (is the agent working) and `instanceStatus` (is the VM up) are **separate** — the session-list UI in [FEATURES.md](FEATURES.md) should show both, since "idle but suspended" and "idle and warm" mean different wait times when you tap in.

### A3 — the extension prefix · **VERIFIED — it is `_kiro/`**

The ACP documentation page's `_kiro.dev/` spelling is wrong; [How Kiro works](https://kiro.dev/docs/how-kiro-works/) is right. Better still, **the prefix does not have to be guessed or hard-coded**: the `initialize` response enumerates the agent's extension methods, and the namespace is itself configurable in KAS via a `KIRO_EXTENSION_NAMESPACE` environment variable.

F-04's instruction to accept either spelling stands as cheap insurance, but the client should **derive** the prefix from the handshake rather than matching two constants.

### A4 — repositories are bindable programmatically · **VERIFIED, and better than assumed**

The plan expected to bind repos with a `--repo` flag at creation, or by driving a `/repo` slash command. Neither is necessary. There is a first-class catalog:

```jsonc
// _kiro/sourceProviders/list
{ "providers": [ { "providerType": "GITHUB", "displayName": "GitHub", "connectionStatus": "connected" },
                 { "providerType": "GITLAB", "displayName": "GitLab", "connectionStatus": "not_connected" } ] }

// _kiro/sourceProviders/listResources { "providerType": "GITHUB" }
{ "resources": [ { "providerType": "GITHUB", "name": "owner/repo",
                   "url": "https://github.com/owner/repo",
                   "visibility": "private", "defaultBranch": "main" } ] }
```

That is a complete repo picker — searchable, with visibility and default branch — and `connectionStatus` tells the app when to show "connect GitLab first" instead of an empty list. F-11 (repo picker) gets materially easier.

Note `defaultBranch` is returned even though cloud sessions don't support branch selection at attach time. Display it; don't offer to change it.

### A5 — permission requests are answerable ACP requests · **VERIFIED** (on a local session)

Captured live. The agent sends a server-initiated request; the client answers with a plain JSON-RPC response:

```jsonc
// agent -> client
{ "jsonrpc": "2.0", "id": 1, "method": "session/request_permission",
  "params": { "sessionId": "…",
    "toolCall": { "toolCallId": "run_command_…", "status": "pending", "title": "id -un" },
    "options": [ { "optionId": "accept",        "name": "Allow",       "kind": "allow_once" },
                 { "optionId": "always-accept", "name": "Always allow","kind": "allow_always" },
                 { "optionId": "reject",        "name": "Deny",        "kind": "reject_once" },
                 { "optionId": "always-reject", "name": "Always deny", "kind": "reject_always" } ],
    "_meta": { "kiro": { "toolId": "run_command", "command": "id -un",
      "consent": { "capability": "shell", "resource": "id -un",
                   "askType": "implicit", "workspaceRoot": "…" },
      "consentRound": 1 } } }

// client -> agent
{ "jsonrpc": "2.0", "id": 1, "result": { "outcome": { "outcome": "selected", "optionId": "reject" } } }
```

**Do not hard-code the four options** — render `options[]` as sent, keyed by `kind`. `_meta.kiro.consent` gives the app what a notification needs to be readable without opening the session: the capability (`shell`), the concrete resource, and whether the ask was implicit or explicit.

**Caveat, stated plainly:** this was captured on a *local* session. The user chose not to spend credits verifying it against a live cloud sandbox, so the cloud path is argued, not observed. The argument is strong — the relay makes a cloud session's requests arrive on the same connection, and KAS explicitly re-sends pending permissions to a client that attaches later (see §4) — but F-03 should confirm it on its first cloud turn and update this line.

### A6 — `login --use-device-flow` is scriptable · **PARTIALLY REFUTED**

Two findings, and the second one changes the design.

**The output is parseable.** On stdout, after provider selection:

```
To sign in with Google, visit:
  https://app.kiro.dev/account/device?user_code=XXXX-XXXX&login_provider=Google
And confirm the code: XXXX-XXXX
```

Both the verification URI and the user code are extractable, and the URI already carries the code as a query parameter — so the app can open a Custom Tab that pre-fills it. Progress then renders as a spinner until the exchange completes.

**But `--use-device-flow` is not non-interactive.** Before printing any of that, the CLI opens a **TUI select menu** on the pty:

```
? Select login method ›
❯ Use with Builder ID
  Use with Google
  Use with GitHub
  Use with Your Organization
```

There is no flag to preselect a provider. `--license free|pro` narrows the *account class*, not the provider. So the bridge cannot simply spawn the process and read stdout — it must allocate a **pty** and send an arrow-key/Enter sequence.

Two further behaviours the bridge must handle:

- **`login` refuses while already signed in**, exiting non-zero with `error: Already logged in, please logout with kiro-cli logout first`. Re-authentication requires an explicit `kiro-cli logout` first — which is destructive, so it must be a deliberate user action in the app, never an automatic retry.
- **`whoami --format json` is the auth-state probe.** Signed in it returns `{"accountType": "SocialGoogle", "email": "…"}`; signed out, `{"account":null}`. It exposes **no plan or entitlement field** — so the app cannot pre-check Pro eligibility and must surface whatever error a cloud create returns instead.

**Implication for [AUTHENTICATION.md](AUTHENTICATION.md):** the sign-in design changes shape but not spirit. The app still never holds a Kiro credential, and the user still completes OAuth in a real browser. What changes is that **provider selection moves into the app** — it presents the four providers natively and tells the bridge which to drive — rather than happening in the browser as the plan assumed. Arguably better UX; definitely more bridge code.

---

## 4. The larger finding: KAS already solves most of F-03

F-03 was scoped as a `L` (1–2 weeks) partly because it was to build reconnect, replay, and cross-connection permission correlation from scratch. KAS ships those. Its own type declarations describe:

- **A WebSocket ACP transport** (`wsStream`) — "each WS text frame is a complete JSON-RPC message". The stdio→WebSocket substitution ADR-001 proposed is one KAS already makes internally.
- **A multiplexing stream** that routes *many* WebSocket clients through one agent, with per-session subscriber sets so several clients can watch one session and stay in sync — phone plus desktop, which the app wants anyway.
- **Permission correlation by `toolCallId`, not JSON-RPC id.** A client that did not receive the original request can still answer it, by sending `_kiro/permission/respond` with `{toolCallId, optionId, sessionId?}`. This is precisely the mobile case: a notification arrives, the socket has since dropped, the user taps Allow on a fresh connection.
- **Pending-permission re-send on attach.** When a client loads a session with a permission still outstanding, KAS re-sends it. This is the mechanism behind the documented "a waiting request is presented to the next client that attaches" — now confirmed as a real code path, and it is what makes A5's cloud case credible.
- **`_kiro/userInput` / `_kiro/userInput/respond`** — a second, distinct human-in-the-loop channel the plan did not know about. The agent can ask a free-text question mid-turn, not just request tool approval. The app needs UI for it.

**What this means for F-03:** its job shrinks from "build a session-multiplexing ACP relay" to "authenticate, terminate TLS, supervise the CLI process, and expose what KAS already does". The bridge still owns pairing, device tokens, and transport security — none of that is KAS's job — but the hard protocol plumbing is not ours to write.

It also **weakens the case for inventing our own `_bridge/…` sequence-number replay protocol** ([ACP-INTEGRATION §7](ACP-INTEGRATION.md#7-reconnect-and-replay--our-design-not-kiros)). Every update already carries `_meta.kiro.messageId` and `timestamp`, and `session/load` replays history in order. F-03 should try resume-by-`messageId` before designing a parallel numbering scheme.

**Caveat on this section:** unlike §3, these findings come from reading KAS's shipped type declarations rather than from observed traffic. They describe intent accurately but were not exercised end-to-end. Treat them as a strong prior for F-03's design, and verify each before depending on it.

---

## 4b. A18 — `KIRO_API_KEY` authenticates the ACP surface · **VERIFIED**

**Run:** 2026-09-02 (second session) · same `kiro-cli 2.19.2` / KAS 0.52.1 host.

KAS announces its selected auth mode on stderr at startup, which turns A18 from an inference into a one-line observation. Two runs of [`scripts/init-only.json`](../tools/acp-probe/scripts/init-only.json), identical except for the environment:

| Run | KAS stderr |
|---|---|
| `kiro-cli acp --agent-engine v3 --auth-method cli` | `[INFO] Auth: --auth=acp-callback (host-mediated refresh via _kiro/auth/getAccessToken)` |
| same, with `KIRO_API_KEY` set | `[INFO] Auth: KIRO_API_KEY env var (api_key)` |

**(a) Yes.** The `acp` surface authenticates from `KIRO_API_KEY`. Note what the table also shows: **the env var takes precedence over the credential store even though `--auth-method cli` was passed explicitly.** `--auth-method` advertises exactly one possible value (`cli` — "Resolve access tokens for the v3 engine from the Kiro CLI credential store"), so there is no flag that *selects* API-key auth and none that *suppresses* it. Presence of the variable decides.

**(b) Yes, as far as an invalid key can prove it.** `acp.remote_sessions.enabled {"endpoint":"https://app.kiro.dev"}` is logged under *both* modes, and a `session/list` with `sessionSource: "remote"` under a deliberately bogus key reached the service and was rejected *by it*:

```json
{"code":-32000,
 "message":"Authentication required or access denied. (Request ID: …)",
 "data":{"errorType":"UnauthorizedException","retryErrorType":"CLIENT_ERROR",
         "faultKind":"serviceRejection","requestId":"…"}}
```

A server-issued `requestId` and `faultKind: "serviceRejection"` mean the key was carried to `app.kiro.dev` and refused there — not that auth was unconfigured locally. The cloud path is wired to the api_key mode. The single unproven step is that a *valid* key returns a session list, which needs a real key and a Pro plan.

**Method caveat, stated plainly:** this was *not* run in a container with an empty `~/.kiro`, as F-01's brief specifies. That control was aimed at ruling out a cached-login fallback, and the stderr line rules it out more directly — mode selection is announced, and the remote call failed under the key rather than silently succeeding under the stored Google login. Someone with a real key should still do the container run before the bridge depends on this.

### Two consequences

**1. The pty is now optional, not mandatory.** [A6](#a6--login---use-device-flow-is-scriptable--partially-refuted) forces F-03 and F-08 to drive a provider-picker TUI over a pty. An API-key-provisioned bridge skips that path entirely: paste one key, no `kiro-cli login`, no TUI. F-03 should implement API-key provisioning **first** — it is a few lines — and treat pty login as the second, harder path it still owes F-08. This does not remove F-08: the key authenticates the *bridge host*, and the product requirement is that the *user* signs in from their phone through a browser. It removes pty login from the bridge's critical path, not from the backlog.

**2. The `acp-callback` mode is confirmed as the default, which sharpens a question [AUTHENTICATION §7](AUTHENTICATION.md#7-open-questions) already had open.** That document spotted the `Auth: --auth=acp-callback (host-mediated refresh via _kiro/auth/getAccessToken)` line and inferred that refresh is delegated to whoever owns the token. The A18 runs confirm it is the default and show the alternative it is being selected *against*. The practical consequence for F-03: KAS does not read the credential store itself, it **calls back into the host process** for tokens and refreshes — so any bridge that considers speaking to KAS directly instead of supervising `kiro-cli` would inherit the obligation to serve `_kiro/auth/getAccessToken` itself. Don't; supervise the CLI, as [ADR-001 §3](adr/ADR-001-cloud-session-access.md) already requires. It also narrows open question 3 there: under an API key there is no refresh at all, so `TokenExpired` is a question about the OAuth path only.

**3. Security note for F-03/F-06.** Because presence of the variable silently overrides the credential store, a bridge that forwards its own environment into the CLI child process can change which account a session runs as without anything in the UI reflecting it. F-03 must construct the child environment explicitly rather than inheriting `process.env` wholesale.

---

## 5. Corrections to the documented protocol

The [ACP page](https://kiro.dev/docs/cli/acp/) names four streaming update kinds. The real stream is larger and differently spelled.

**Update kinds observed** (`session/update` → `update.sessionUpdate`), all `snake_case`:

| Kind | Notes |
|---|---|
| `agent_message_chunk` | streamed text; `_meta.kiro.replayId` groups chunks of one message |
| `user_message_chunk` | the user's own turns, replayed on load |
| `tool_call` | `toolCallId`, `title`, `kind` (`execute`, `other`, …), `status`, `rawInput` |
| `tool_call_update` | status transitions and `content[]` results |
| `session_info_update` | **the workhorse** — see below |
| `config_option_update` | mode/model pickers, with current value and options |
| `available_commands_update` | slash commands, including steering documents |

**There is no `TurnEnd` update kind.** Turn boundaries arrive as `session_info_update` carrying `_meta.kiro.kind`:

- `turn_start`
- `turn_end` with `stopReason` (`"end_turn"`), mirrored by the `session/prompt` response
- `context_usage` — percentage plus a token breakdown by category
- `focus_update` — the agent retitling the session mid-turn
- `user_message_id_assigned`
- `pendingInteraction` / `interactionResolved` — **an approval is outstanding / was answered**, including which option won. This lets the app render a "waiting on you" state, and correctly clear it when *another* client answers first.
- `promptTurnSummaries` — per-turn credit spend and tools used. A cost display is nearly free; worth an item in FEATURES.md.
- `displayError` — user-facing errors (e.g. an MCP server needing authorization) delivered in-band.

**Extension notifications are a separate set from `extensionMethods`.** The handshake's `extensionMethods` array lists only *client→agent* methods. These agent→client notifications were observed and appear in none of it: `_kiro/sessions/changed` (roster upserts/deletes with status transitions — drives a live session list), `_kiro/mcp/status`, `_kiro/governance/state`, `_kiro/tools/didChange`, `_kiro/powers/items_changed`, `_kiro/steering/documents_changed`, `_kiro/progressive_context/items_changed`, `_kiro/customAgent/config_error`.

F-04's "unknown method is logged and dropped without terminating the session" requirement is therefore **load-bearing, not defensive polish** — this list is open-ended and undocumented.

**Also present and undocumented:** `session/list` and session `fork` as declared ACP capabilities, checkpoints, a workflow subsystem (14 `_kiro/workflow/*` methods), `_kiro/session/compact`, `_kiro/session/export`, `_kiro/session/history`, and `_kiro/session/delete`.

**Agent modes** available on a new session: `vibe` (Default), `spec`, `quick-spec`, `bug-fix`, `plan`, `autonomous`. The "autonomy level" in FEATURES.md maps onto these, not onto a separate axis.

---

## 6. Fixtures

Under [`core/src/test/resources/fixtures/`](../core/src/test/resources/fixtures/). Each file is JSONL: a header line naming the fixture, then one `{dir, frame}` object per wire frame. Repository names, paths, the account email, MCP server identities and message text are redacted; see that directory's README.

| File | What it pins |
|---|---|
| `initialize-v3.jsonl` | the handshake and the full `_meta.kiro` capability block |
| `session-list-remote.jsonl` | cloud sessions listed over ACP, with repos and dual status |
| `source-providers.jsonl` | the repository catalog behind session creation |
| `session-load-remote-head.jsonl` | attaching to a cloud sandbox, and the head of its replay |
| `prompt-turn-with-permission.jsonl` | a complete turn: tool call → permission → answer → stream → turn end |

Regenerate with [`tools/acp-probe/`](../tools/acp-probe/).

---

## 7. What this changes in the backlog

| Item | Change |
|---|---|
| **F-03** (bridge) | Scope shrinks. Do not build multiplexing, permission correlation, or a bespoke replay protocol before checking what KAS gives you. Must drive login over a **pty**, not a pipe. |
| **F-04** (ACP layer) | Derive the extension prefix from `initialize`. Handle the full `session_info_update` `_meta.kiro.kind` set, not just four update kinds. Unknown-method tolerance is required. |
| **F-05** (gateway) | Add `listSessions(source, scope)`, and surface `status` and `instanceStatus` separately. Add a user-input channel alongside permissions. |
| **F-08** (sign-in) | Provider picker moves into the app. Bridge needs a pty driver. `logout` must be an explicit user action. |
| **F-11** (repo picker) | Easier — a real catalog exists, with visibility and default branch. |
| **New** | Per-turn credit display (`promptTurnSummaries`) is nearly free. `_kiro/userInput` needs UI. |

Nothing here refutes [ADR-001](adr/ADR-001-cloud-session-access.md)'s decision. The bridge topology holds, it is reachable entirely through documented CLI entry points, and the app can be built against it today.
