# ACP integration contract

The contract the Android client implements. Written so the protocol layer can be built and unit-tested against a fake transport before the bridge exists.

Source of truth: **[PROTOCOL-FINDINGS.md](PROTOCOL-FINDINGS.md)** — frames captured from `kiro-cli 2.19.2` (KAS 0.52.1) by the F-01 spike — then [Agent Client Protocol](https://kiro.dev/docs/cli/acp/) and [How Kiro works](https://kiro.dev/docs/how-kiro-works/) for the parts F-01 did not exercise. **Where the published docs and the captured frames disagree, the frames win**, and several sections below were rewritten on that basis. Anything this document adds beyond both is **our design** and is labelled as such.

> **Before anything else: the agent must be started as `kiro-cli acp --agent-engine v3 --auth-method cli`.** The default engine is local-only and cannot see cloud sessions at all. See [PROTOCOL-FINDINGS §2](PROTOCOL-FINDINGS.md#2-the-one-thing-the-plan-missed-entirely---agent-engine-v3).

---

## 1. Shape

ACP is **JSON-RPC 2.0**. Kiro's harness is a standalone process; local clients reach it over stdio, and Web/Mobile reach it over a WebSocket. We are a WebSocket client (via the bridge — [ADR-001](adr/ADR-001-cloud-session-access.md)).

Three message kinds, and the client must handle all three directions:

- **Client → agent requests** (`initialize`, `session/prompt`, …) expecting a matching `id`.
- **Agent → client notifications** (`session/notification`, the `_kiro…` events) with no `id`.
- **Agent → client requests.** Permission prompts arrive this way and the client **must reply**. A client written as request/response-only will hang the agent the first time it asks to run a command.

---

## 2. Handshake

```json
{ "jsonrpc": "2.0", "id": 0, "method": "initialize",
  "params": {
    "protocolVersion": 1,
    "clientCapabilities": { "fs": { "readTextFile": false, "writeTextFile": false }, "terminal": false },
    "clientInfo": { "name": "KiroForAndroid", "version": "0.1.0" } } }
```

**Advertise `fs` and `terminal` as `false`.** This differs from the editor example in Kiro's docs, deliberately: in a cloud session the filesystem and shell live *in the sandbox*, and a phone has neither the user's checkout nor a terminal. Claiming those capabilities would invite the agent to route operations to a client that cannot service them.

The agent's response carries `agentCapabilities`, and on the v3 engine a `_meta.kiro` block that is **the client's runtime configuration source**. Do not hard-code what it tells you:

- `loadSession: true` — required for resuming a cloud session. If absent, resume is impossible and the app should say so rather than fail obscurely.
- `promptCapabilities.image: true` — images may be attached to prompts. On a phone this is a genuinely differentiating feature (photograph a whiteboard, attach a screenshot of a failure), so it should not be treated as an afterthought.
- `sessionCapabilities.list` — `session/list` is supported (see §3).
- `_meta.kiro.extensionMethods[]` — the agent's **client→agent** extension methods, spelled out. Derive the namespace prefix from this array rather than matching a constant; the namespace is server-configurable. Note this list does **not** cover agent→client extension *notifications*, which are undocumented and open-ended (§5).
- `_meta.kiro.sessionSources` / `sessionListScopes` / `executionTargets` — which stores, list breadths and placements this agent will serve. `executionTargets` containing `cloud-sandbox` is the runtime signal that cloud sessions are available; if it is absent, the app is talking to a local-only agent and should say so plainly.

Store the negotiated `protocolVersion`; refuse to proceed on a major mismatch with a clear message.

A verbatim handshake is committed at [`initialize-v3.jsonl`](../core/src/test/resources/fixtures/initialize-v3.jsonl).

### `authMethods` does **not** mean "not signed in"

The `initialize` result may carry a non-empty `authMethods` array — e.g. `[{"id":"kiro-login","name":"Kiro Login","description":"Run 'kiro-cli login' in terminal to authenticate."}]` — **even when the CLI is fully authenticated**. Do not read it as an auth-state signal, and do not gate the session list or session creation on it being empty.

This is a real trap with a real casualty: JetBrains' ACP client treated a non-empty array as unauthenticated, rendered a login button, and then called an `authenticate` method `kiro-cli` does not implement ([kirodotdev/Kiro#6603](https://github.com/kirodotdev/Kiro/issues/6603)). The thread is worth reading in full — a Kiro engineer notes the [ACP schema](https://agentclientprotocol.com/protocol/schema#param-auth-methods) is genuinely ambiguous about whether the field's presence implies login is required, and resolved it on 2026-04-01 with *"for now we'll just rescind authMethods when user is logged in."* JetBrains fixed their side in parallel.

So the field's behaviour **has changed across CLI versions and may change again**, in both directions. Consequences for us:

- **Derive auth state on the bridge**, from `kiro-cli whoami` (or a `login` attempt refusing because a session already exists — see [PROTOCOL-FINDINGS A6](PROTOCOL-FINDINGS.md#a6--login---use-device-flow-is-scriptable--partially-refuted)), and send it to the app as an explicit field. Never infer it from the handshake.
- **Tolerate both shapes.** Present *and* absent must both parse and both proceed. Fixtures should cover both; the committed `initialize-v3.jsonl` is only one of them.
- If the app ever surfaces "sign in" from the handshake alone, it will show a sign-in screen to an already-signed-in user — the exact JetBrains bug, on a smaller screen.

---

## 3. Session lifecycle

| Operation | Method | Notes |
|---|---|---|
| List | `session/list` | Returns the session roster. **Cloud sessions require dispatch metadata** — see below. |
| Create | `session/new` | Takes `cwd` and `mcpServers`; placement comes from dispatch metadata. |
| Resume | `session/load` | By session ID, plus dispatch metadata naming the store. Replays the session's full history as `session/update` notifications. |
| Prompt | `session/prompt` | `params.prompt` is an **array** of typed blocks (`{"type":"text","text":…}`), not a bare string. Images are additional blocks. Resolves with `{"stopReason": …}` when the turn ends. |
| Cancel | `session/cancel` | Cancels the in-flight turn, not the session. |
| Change model | `session/set_model` | |
| Change agent/mode | `session/set_mode` | Maps to Kiro's agent configs. Available modes are returned by `session/new` as `modes.availableModes` — observed: `vibe` (Default), `spec`, `quick-spec`, `bug-fix`, `plan`, `autonomous`. |

### Dispatch metadata — how a call reaches the cloud

This is the mechanism the plan was missing, and every cloud operation depends on it. Requests select their **store** and **placement** through `params._meta.kiro`:

| Field | Values | Applies to |
|---|---|---|
| `sessionSource` | `"local"` · `"remote"` · `"all"` | any; `"all"` only on `session/list` |
| `listScope` | `"workspace"` · `"user"` · `"both"` | `session/list`; non-`workspace` requires the agent to advertise it |
| `executionTarget` | `{"kind":"local"}` · `{"kind":"cloud-sandbox"}` | `session/new` |

```jsonc
// the cloud roster
{"method":"session/list","params":{"_meta":{"kiro":{"sessionSource":"remote","listScope":"user"}}}}

// attach to a cloud session
{"method":"session/load","params":{"sessionId":"…","cwd":"…","mcpServers":[],
  "_meta":{"kiro":{"sessionSource":"remote"}}}}

// create one
{"method":"session/new","params":{"cwd":"…","mcpServers":[],
  "_meta":{"kiro":{"executionTarget":{"kind":"cloud-sandbox"}}}}}
```

Two rules the agent enforces, both as `InvalidParamsError`:

- **The union is closed.** An unrecognised `sessionSource`, `listScope` or `executionTarget` is an error, never a silent fallback to the default. Send only values the handshake advertised.
- **`sessionSource` and `executionTarget` are two spellings of one decision.** Naming both with values that disagree is rejected. Send one.

Omitting the metadata entirely means `sessionSource: "local"` — which is why a naive client sees only local sessions and concludes cloud is unreachable.

### Session records

A listed session carries two independent statuses, and the UI must keep them distinct:

- `_meta.kiro.status` — `idle` / `in_progress`: is the **agent** working?
- `_meta.kiro.instanceStatus` — e.g. `suspended`: is the **sandbox VM** up?

Also present: `repositories[]` (`providerType`, `name`, `url`), `executionTarget`, `agentMode`, `createdAt`.

### Repositories

Bound at creation from a first-class catalog, not a flag or a slash command:

- `_kiro/sourceProviders/list` → providers with `connectionStatus` (`connected` / `not_connected`)
- `_kiro/sourceProviders/listResources` `{providerType}` → repos with `name`, `url`, `visibility`, `defaultBranch`

Repositories are fixed at creation time and branches cannot be selected at attach time; the documented workaround is to ask the agent to check out or create a branch once running. Show `defaultBranch` as information; do not offer a branch picker that cannot work.

---

## 4. Streaming updates

Delivered as **`session/update`** notifications — not `session/notification` — discriminated by `params.update.sessionUpdate`. The kinds are `snake_case`, and there are seven, not the four the ACP page lists:

| Kind | Meaning | Client handling |
|---|---|---|
| `agent_message_chunk` | Incremental agent output | Append to the in-flight message. **Coalesce** on a ~60–100 ms tick; never recompose per chunk. `_meta.kiro.replayId` groups the chunks of one message. |
| `user_message_chunk` | The user's own turns | Mostly seen during replay |
| `tool_call` | A tool invocation begins | Insert a collapsible entry. Carries `toolCallId`, `title`, `kind` (`execute`, `other`, …), `status`, `rawInput` |
| `tool_call_update` | Progress or result | Update in place, keyed by `toolCallId`. Carries `status` and `content[]` |
| `session_info_update` | **Everything else** — see below | Discriminate on `_meta.kiro.kind` |
| `config_option_update` | Mode/model pickers with current value and options | Drives the session settings UI |
| `available_commands_update` | Slash commands, including steering documents | Drives composer autocomplete |

### `session_info_update` is the workhorse

Turn boundaries and session state do **not** have their own update kinds. **There is no `TurnEnd`.** They arrive here, keyed by `_meta.kiro.kind`:

| `kind` | Meaning |
|---|---|
| `turn_start` | Turn begins — start the working indicator |
| `turn_end` | Turn complete, with `stopReason` (e.g. `end_turn`). Mirrored by the `session/prompt` response |
| `context_usage` | Percentage plus a token breakdown by category |
| `focus_update` | The agent retitling the session mid-turn |
| `user_message_id_assigned` | Correlates a sent prompt with its server-side id |
| `pending_interaction` | **An approval is outstanding** — render the waiting state |
| `interaction_resolved` | It was answered, and with which option — clear the state even when *another* client answered |
| `turn_completion` | Per-turn credit spend and tools used (F-19b), in a `promptTurnSummaries` array |
| `display_error` | A user-facing error delivered in-band, e.g. an MCP server needing authorization |

> **Corrected 2026-09-02 by F-04.** The last four were previously listed here — and in [PROTOCOL-FINDINGS §5](PROTOCOL-FINDINGS.md#5-corrections-to-the-documented-protocol) — in camelCase. The committed fixture disagrees: 2.19.2 sends `snake_case` for **every** `_meta.kiro.kind`, and `promptTurnSummaries` is not a kind at all but a *field* inside `turn_completion`.
>
> This mattered more than a spelling usually does. A client written from the prose would have matched none of those four, so an approval would never have rendered — the app's most important interaction failing silently while the transcript streamed normally around it. The parser accepts both spellings, so this correction is not urgent; finding it in a fixture rather than a bug report is what the fixtures are for.

Rendering strategy is fixed by [ADR-003 §3](adr/ADR-003-tech-stack.md#3-two-conventions-that-are-load-bearing): the in-flight message renders **outside** the lazy list and is appended to it only at turn end. Highlighting recomputed per token on a growing string is the specific failure mode to avoid.

An unknown update kind — or an unknown `_meta.kiro.kind` — must render as a generic entry, never crash. `turn_end` may be missed if the socket drops mid-turn, so treat turn completion as recoverable via reconnect state, not as a guaranteed event.

**Replay volume is not a tail case.** A single `session/load` on a real cloud session replayed **991 updates**. Fixture: [`session-load-remote-head.jsonl`](../core/src/test/resources/fixtures/session-load-remote-head.jsonl).

---

## 5. Kiro extensions

Namespaced `_kiro/` and **documented as experimental and subject to change**. Optional by design — a client may ignore them. **Derive the prefix from `initialize`** rather than hard-coding it; the namespace is server-configurable.

### Client → agent (enumerated by the handshake)

The handshake's `_meta.kiro.extensionMethods` array is authoritative. The ones this app needs:

| Method | Use here |
|---|---|
| `_kiro/sourceProviders/list` | Providers and their connection status — the repo picker's first screen |
| `_kiro/sourceProviders/listResources` | The user's repositories, with visibility and default branch |
| `_kiro/session/history` · `/compact` · `/export` · `/context` | Transcript and context management |

Also advertised, and out of scope for now: `_kiro/knowledge`, `_kiro/codeIntelligence`, `_kiro/config/template`, and fourteen `_kiro/workflow/*` methods.

### Agent → client (undocumented, and **not** in `extensionMethods`)

`extensionMethods` covers only the client→agent direction. These notifications were observed live and appear in no published list — which is exactly why §8's tolerance requirement is load-bearing:

| Notification | Use here |
|---|---|
| `_kiro/sessions/changed` | Roster upserts/deletes with status transitions — **drives a live session list without polling** |
| `_kiro/mcp/status` | Per-server state; carries `authorizationUrl` and `failedAuthorization` when a server needs OAuth — open in a Custom Tab, same pattern as sign-in |
| `_kiro/governance/state` | Enterprise flags and feature gates |
| `_kiro/tools/didChange` | Available tool tags |
| `_kiro/steering/documents_changed` | Steering documents, which surface as slash commands |
| `_kiro/powers/items_changed` · `_kiro/progressive_context/items_changed` · `_kiro/customAgent/config_error` | Observed; not needed yet |

Treat this list as incomplete. It is a snapshot of one CLI version, not a contract.

Everything here is an enhancement. The app must remain fully usable if every extension is absent — that is what makes protocol drift survivable.

---

## 6. Permission requests

Approvals are the feature that makes a phone client worth having: the agent blocks, and the user unblocks it from wherever they are. Kiro documents that a request raised while no client is attached **waits and is presented to the next client that attaches**, which implies it is durable session state rather than a transient event.

F-01 confirmed this is a real code path, not just documented intent: KAS re-sends outstanding permission requests to a client that attaches later.

The agent sends a server-initiated request; the client answers with a plain JSON-RPC response:

```jsonc
// agent -> client
{"jsonrpc":"2.0","id":1,"method":"session/request_permission",
 "params":{"sessionId":"…",
   "toolCall":{"toolCallId":"run_command_…","status":"pending","title":"id -un"},
   "options":[{"optionId":"accept","name":"Allow","kind":"allow_once"},
              {"optionId":"always-accept","name":"Always allow","kind":"allow_always"},
              {"optionId":"reject","name":"Deny","kind":"reject_once"},
              {"optionId":"always-reject","name":"Always deny","kind":"reject_always"}],
   "_meta":{"kiro":{"toolId":"run_command","command":"id -un",
     "consent":{"capability":"shell","resource":"id -un","askType":"implicit","workspaceRoot":"…"},
     "consentRound":1}}}}

// client -> agent
{"jsonrpc":"2.0","id":1,"result":{"outcome":{"outcome":"selected","optionId":"reject"}}}
```

Design requirements:

- **Render `options[]` as sent**, keyed by `kind`. The four above are what 2.19.2 offers; the list is agent-supplied and must not be hard-coded.
- `_meta.kiro.consent` gives the capability, the concrete resource, and whether the ask was implicit — enough for a notification to be readable without opening the session.
- On attach, **check for a pending approval immediately** and surface it before the transcript finishes replaying. `pending_interaction` / `interaction_resolved` (§4) also carry this in-stream, including when another client answers first.
- Deliver via notification with inline allow/deny actions (F-16).
- **Answering does not require the connection that asked.** KAS correlates by `toolCallId` via `_kiro/permission/respond` `{toolCallId, optionId, sessionId?}` — which is precisely the mobile case: a notification arrives, the socket has since dropped, the user taps Allow on a fresh one.
- **There is a second channel: `_kiro/userInput`.** The agent can ask a free-text question mid-turn, answered with `_kiro/userInput/respond` `{toolCallId, action: "answered"|"dismissed", answer?}`. Undocumented, and it needs its own UI.

Fixture: [`prompt-turn-with-permission.jsonl`](../core/src/test/resources/fixtures/prompt-turn-with-permission.jsonl).

**Verified on a local session only.** The cloud path follows from the same relay and re-send machinery, but has not been observed — F-03 confirms it on its first cloud turn.

---

## 7. Reconnect and replay — our design, not Kiro's

> **Re-opened and decided (F-03, 2026-09-02): resume by `messageId`. The sequence-number scheme below is not implemented.**
>
> The deciding measurement: **all 57 updates in the captured cloud replay carry `_meta.kiro.messageId`** — every kind, not just turn boundaries. A parallel numbering scheme would have been a second identifier for something the agent already identifies uniquely, and a second thing to keep correct.
>
> What the id alone does *not* give is **ordering and retention**, and that part of the design below survives: the bridge keeps a bounded, ordered, per-session log and answers `_bridge/resume {sessionId, afterMessageId}` from it. The contract that matters is unchanged — when the requested point has been evicted, or belongs to a session this bridge never saw, it answers `{"truncated": true}` and the app refetches the transcript rather than rendering a hole.
>
> "A session this bridge never saw" is not an error case. Sessions live in the Kiro account and bridges are fungible, so switching bridges mid-session lands here by design.

ACP does not document a resume-from-cursor mechanism, and a mobile client drops its socket constantly (backgrounding, network changes, Doze). So the bridge owns durability. The CLI already persists sessions as `<id>.json` plus a `<id>.jsonl` event log, which is the natural backing store.

**Shipped contract between app and bridge, as implemented (F-03, 2026-09-02):**

1. The bridge keeps a bounded, ordered, per-session log (`SessionLog`, `bridge/src/main/kotlin/dev/kiro/bridge/SessionLog.kt`) keyed by each update's own `_meta.kiro.messageId` — **no bridge-invented sequence number.**
2. On reconnect, a client may call `_bridge/resume {sessionId, afterMessageId}`; the bridge replays everything after that `messageId` from its log (`BridgeServer.kt`, `METHOD_RESUME`).
3. If the requested point has been evicted from the log, or belongs to a session this bridge never saw (bridges are fungible — see the callout above), the bridge answers `{"truncated": true}` and the caller is expected to refetch the transcript via a full `session/load` rather than render a hole.
4. **`_bridge/resume` is implemented bridge-side but not yet called app-side.** As of 2026-09-02 the Android client always performs a full `session/load` on reconnect (see `loadSession()` in `core/src/main/kotlin/dev/kiro/core/session/CloudSessionGateway.kt`); wiring the app to call `_bridge/resume` for incremental replay is open, tracked under F-15.

Reconnect uses exponential backoff with jitter, and reconnects eagerly on a connectivity-regained callback rather than waiting out the backoff — both wired into the live reconnect loop as of the F-15 2026-09-02 round.

These control messages are **bridge-specific, not ACP**. They are namespaced `_bridge/…` (not `_kiro/…`) so they can never be mistaken for harness methods.

---

## 8. Error handling

- Standard JSON-RPC error codes; surface `message` to the user, log `data`.
- Requests time out. A prompt request may legitimately be outstanding for a long time — tie the timeout to *stream silence*, not to overall turn duration, or long legitimate turns get killed.
- `ignoreUnknownKeys = true` on every deserializer.
- Never let a malformed frame terminate the session; drop it, log it, count it. Track parse-failure and unknown-method rates as a first-class metric — [ADR-002 §5](adr/ADR-002-react-native-vs-native.md#5-recommendation) makes that number the trigger for revisiting the runtime decision.

---

## 9. Testability

The protocol layer lives in `core/` with no Android dependencies (ADR-003 §2), so:

- `AcpTransport` is an interface; tests use an in-memory fake and assert on the frames produced.
- **The fixtures exist.** F-01 committed five under [`core/src/test/resources/fixtures/`](../core/src/test/resources/fixtures/), covering the handshake, cloud session listing, the repository catalog, a cloud attach with replay, and a complete turn including a permission request. The `TranscriptReducer` is tested by replaying one and asserting the resulting transcript — including the awkward cases: interleaved tool calls, a mid-turn disconnect, a permission request arriving during streaming.
- Every fixture is a regression test against protocol drift. If Kiro changes something, a fixture-based test tells you what and where.
