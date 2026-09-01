# ACP integration contract

The contract the Android client implements. Written so the protocol layer can be built and unit-tested against a fake transport before the bridge exists.

Source of truth for the documented parts: [Agent Client Protocol](https://kiro.dev/docs/cli/acp/) and [How Kiro works](https://kiro.dev/docs/how-kiro-works/). Anything this document adds beyond those pages is **our design** and is labelled as such.

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

The agent's response carries `agentCapabilities`. Two matter:

- `loadSession: true` — required for resuming a cloud session. If absent, resume is impossible and the app should say so rather than fail obscurely.
- `promptCapabilities.image: true` — images may be attached to prompts. On a phone this is a genuinely differentiating feature (photograph a whiteboard, attach a screenshot of a failure), so it should not be treated as an afterthought.

Store the negotiated `protocolVersion`; refuse to proceed on a major mismatch with a clear message.

---

## 3. Session lifecycle

| Operation | Method | Notes |
|---|---|---|
| Create | `session/new` | The editor example passes `cwd` and `mcpServers`. For a cloud session, repository binding is the meaningful input instead — how that is expressed is **unverified** (assumption A4 in ADR-001). Either the bridge creates via `kiro-cli --cloud --repo …` and we then `session/load`, or repos are attached via the `/repo` slash command through the commands extension. **F-01 must settle this.** |
| Resume | `session/load` | By session ID. The path from "Web/Mobile session" to "CLI" is `kiro-cli --resume-id <id>`, so IDs are portable across surfaces. |
| Prompt | `session/prompt` | `params.content` is an **array** of typed blocks (`{"type":"text","text":…}`), not a bare string. Images are additional blocks. |
| Cancel | `session/cancel` | Cancels the in-flight turn, not the session. |
| Change model | `session/set_model` | |
| Change agent/mode | `session/set_mode` | Maps to Kiro's agent configs |

Repositories are fixed at creation time and branches cannot be selected at attach time; the documented workaround is to ask the agent to check out or create a branch once running. The UI should set that expectation rather than offering a branch picker that cannot work.

---

## 4. Streaming updates

Delivered as `session/notification` notifications:

| Kind | Meaning | Client handling |
|---|---|---|
| `AgentMessageChunk` | Incremental agent output | Append to the in-flight message. **Coalesce** on a ~60–100 ms tick; never recompose per chunk. |
| `ToolCall` | A tool invocation begins (name, params, status) | Insert a collapsible transcript entry |
| `ToolCallUpdate` | Progress on a running tool | Update in place, keyed by tool-call id |
| `TurnEnd` | Turn complete | Seal the in-flight message; stop the "working" indicator; release any wake lock |

Rendering strategy is fixed by [ADR-003 §3](adr/ADR-003-tech-stack.md#3-two-conventions-that-are-load-bearing): the in-flight message renders **outside** the lazy list and is appended to it only at `TurnEnd`. Highlighting recomputed per token on a growing string is the specific failure mode to avoid.

An unknown update kind must render as a generic entry, never crash. `TurnEnd` may be missed if the socket drops mid-turn — treat turn completion as recoverable via reconnect state, not as a guaranteed event.

---

## 5. Kiro extensions

Namespaced per the ACP spec and **documented as experimental and subject to change**. Optional by design — a client may ignore them.

| Method | Type | Use here |
|---|---|---|
| `commands/execute` | request | Run a slash command — notably `/repo` for repository attachment |
| `commands/options` | request | Autocomplete for a partial command |
| `commands/available` | notification | Sent after session creation; drives which commands the UI offers |
| `mcp/oauth_request` | notification | Carries an OAuth URL when an MCP server needs auth — open in a Custom Tab, same as sign-in |
| `mcp/server_initialized` | notification | MCP tools now available |
| `compaction/status` | notification | Show a "compacting context" indicator instead of an unexplained stall |
| `clear/status` | notification | Progress while clearing history |
| `_session/terminate` | notification | Subagent session ended |

**Prefix is unresolved.** The ACP page uses `_kiro.dev/`; How Kiro works says `_kiro/`. Do not hard-code one: match on the method suffix, accept either prefix, and record what the live server actually sends (ADR-001 assumption A3).

Everything here is an enhancement. The app must remain fully usable if every extension is absent — that is what makes protocol drift survivable.

---

## 6. Permission requests

Approvals are the feature that makes a phone client worth having: the agent blocks, and the user unblocks it from wherever they are. Kiro documents that a request raised while no client is attached **waits and is presented to the next client that attaches**, which implies it is durable session state rather than a transient event.

Design requirements:

- Answer via the reply channel for an agent-initiated request; do not invent a side channel.
- On attach, **check for a pending approval immediately** and surface it before the transcript finishes replaying.
- Deliver via notification with inline allow/deny actions (F-16).
- The exact method name and payload shape are **unverified** (ADR-001 assumption A5) — F-01 must capture a real one.

---

## 7. Reconnect and replay — our design, not Kiro's

ACP does not document a resume-from-cursor mechanism, and a mobile client drops its socket constantly (backgrounding, network changes, Doze). So the bridge owns durability. The CLI already persists sessions as `<id>.json` plus a `<id>.jsonl` event log, which is the natural backing store.

Contract between app and bridge:

1. The bridge assigns a **monotonic sequence number** to every update it forwards, and appends it to a per-session log.
2. The app persists the highest sequence number it has rendered.
3. On reconnect the app sends `lastSeq`; the bridge replays everything after it, then resumes live streaming.
4. If the log has been truncated past `lastSeq`, the bridge says so explicitly and the app refetches the transcript from scratch rather than silently showing a hole.

Reconnect uses exponential backoff with jitter, and reconnects eagerly on a connectivity-regained callback rather than waiting out the backoff.

These control messages are **bridge-specific, not ACP**. Namespace them (e.g. `_bridge/…`) so they can never be mistaken for harness methods, and document them alongside the bridge in F-03.

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
- Capture **real** frame sequences during the F-01 spike and commit them as golden JSONL fixtures. The `TranscriptReducer` is then tested by replaying a fixture and asserting the resulting transcript — including the awkward cases: interleaved tool calls, a mid-turn disconnect, a permission request arriving during streaming.
- Every fixture is a regression test against protocol drift. If Kiro changes something, a fixture-based test tells you what and where.
