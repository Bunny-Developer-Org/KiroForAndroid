# Feature backlog

Work items for **KiroForAndroid** — an Android client for [Kiro](https://kiro.dev) cloud sessions.

Each item is scoped to be picked up independently by one agent. Read [§ How to pick up an item](#how-to-pick-up-an-item) before starting, and read [ADR-001](adr/ADR-001-cloud-session-access.md) first — it constrains every item here.

**Legend** — Size: `S` <1d · `M` 1–3d · `L` 1–2w · `XL` 2w+. `∥` = safe to run in parallel with its phase-mates.

---

## Phase 0 — Unblock (nothing downstream is trustworthy until this is done)

### F-00 · Pursue official third-party API access · `S` ∥
**Not a code task.** ADR-001 rejects reverse-engineering Kiro's private cloud API, which leaves the bridge topology as the only legitimate option. Official access would remove that constraint entirely, so it is worth asking early even though the answer may be no or silence.

- **Do:** open a feature request on [`kirodotdev/Kiro`](https://github.com/kirodotdev/Kiro) asking about third-party client / API access for cloud sessions; ask on the [Kiro Discord](https://discord.gg/kirodotdev); check whether an Android app is on the public roadmap.
- **Done when:** the outcome is recorded in ADR-001 §2 Option C with a date, whatever the outcome. "No reply after N weeks" is a valid, recorded result.
- **Depends on:** nothing.

### F-01 · Protocol spike: verify assumptions, capture golden fixtures · `M`
**The highest-value item in this backlog.** ADR-001 §5 lists six load-bearing assumptions (A1–A6) that are currently unverified. If A1/A2/A5 are wrong, the architecture changes. Estimates for Phase 1+ are not credible until this reports.

- **Do:** on a machine with a real `kiro-cli` and a Pro account — create a cloud session with `--cloud --repo`; try attaching via `kiro-cli acp` and `session/load`; capture the raw JSON-RPC frames for a full turn including at least one tool call and one permission prompt; determine how repositories are bound programmatically; determine the real `_kiro` extension prefix; test whether `login --use-device-flow` output can be parsed for the verification URI and code; check what `whoami --format json` exposes about entitlement.
- **Done when:**
  - ADR-001 §5 is updated with a verified/refuted verdict per assumption.
  - Real frame sequences are committed as JSONL fixtures under `core/src/test/resources/fixtures/` (redact tokens, repo names, and paths).
  - A written note names any assumption that was refuted and what it implies.
- **Scope out:** building anything. This is a research spike; its deliverables are fixtures and answers.
- **Depends on:** access to `kiro-cli` + a Pro account. **This is a hard prerequisite — flag it immediately if unavailable, do not proceed by guessing.**

---

## Phase 1 — Foundation

### F-02 · Project scaffold, modules, CI · `M`
- **Do:** create the three-module Gradle project exactly as specified in [ADR-003 §2](adr/ADR-003-tech-stack.md#2-module-layout) (`app/`, `core/`, `bridge/`), with the version catalog from ADR-003 §1. Add CI: build, unit tests, ktlint/detekt, and **a check that fails if `core/` contains any `android.*` or `androidx.*` import** (ADR-003's one hard rule — enforce it mechanically). Pin `JAVA_HOME` to JDK 17 or 21 in CI; AGP rejects JDK 22+.
- **Done when:** `./gradlew build` is green from a clean clone, CI runs on PRs, and the core-purity check demonstrably fails when someone adds an Android import.
- **Also:** this item **fixes the DI pattern** for the project (manual `ServiceLocator` or Hilt). Whatever it picks, record it in ADR-003 §1 so later items don't diverge.
- **Depends on:** nothing. Start immediately, in parallel with F-01.

### F-03 · Bridge service (MVP) · `L`
The host-side process from ADR-001. Not a dev convenience — it is the product's backend.

- **Do:** a process that runs where `kiro-cli` is installed and: spawns/supervises the CLI as an ACP agent; exposes JSON-RPC over an authenticated WebSocket; issues single-use pairing codes and long-lived revocable device tokens; assigns monotonic sequence numbers and maintains a per-session replay log; implements the `_bridge/…` control messages from [ACP-INTEGRATION §7](ACP-INTEGRATION.md#7-reconnect-and-replay--our-design-not-kiros).
- **Security requirements** (from [AUTHENTICATION §4](AUTHENTICATION.md#4-auth-1-pairing-the-app-to-the-bridge), all mandatory): binds `127.0.0.1` by default with explicit opt-in to `0.0.0.0`; TLS required for non-loopback; pairing codes single-use and ~5 min TTL; rate-limited pairing; revocable device list.
- **Done when:** a WebSocket client can pair, create a cloud session, send a prompt, receive streamed updates, disconnect mid-turn, reconnect with `lastSeq`, and receive exactly the missed updates with no gap and no duplication.
- **Decide and record:** bridge language (Kotlin/JVM vs Node/TS) per [ADR-003 §2](adr/ADR-003-tech-stack.md#bridge-language--open-decision). Write the outcome back into that ADR.
- **Depends on:** F-01 (needs verified CLI behaviour).

### F-04 · ACP protocol layer in `core/` · `M` ∥
- **Do:** JSON-RPC 2.0 codec (request / response / notification / server-initiated request), `AcpTransport` interface, `AcpClient` handling correlation, timeouts, and cancellation. Implement the handshake from [ACP-INTEGRATION §2](ACP-INTEGRATION.md#2-handshake) — including advertising `fs` and `terminal` as **false**, which is intentional and differs from Kiro's editor example. Accept **either** `_kiro/` or `_kiro.dev/` prefixes. `ignoreUnknownKeys = true` throughout.
- **Done when:** unit tests pass against a fake transport and against F-01's golden fixtures; an unknown method or malformed frame is logged and dropped without terminating the session (test this explicitly).
- **Depends on:** F-02. Benefits from F-01's fixtures but can start against the documented method set.

### F-05 · `CloudSessionGateway` + `BridgeGateway` · `M`
- **Do:** the interface every feature codes against — `createSession`, `listSessions`, `loadSession`, `prompt`, `cancel`, `setModel`, `setMode`, `respondToPermission`, and `updates: Flow<SessionUpdate>`. Plus `BridgeGateway`, implementing it over F-04.
- **Why it matters:** this is the seam that makes ADR-001's decision reversible. If official API access ever lands, it is a second implementation, not a rewrite.
- **Done when:** no UI code anywhere references a transport, a socket, or the bridge directly; a `FakeGateway` exists so UI items can be built and tested without a bridge.
- **Depends on:** F-04.

### F-06 · Secure credential storage · `S`
- **Do:** `TokenStore` interface in `core/`, implemented in `app/` with **AndroidKeyStore + DataStore**. Do **not** use `androidx.security:security-crypto` — it is deprecated and its own guidance points at AndroidKeyStore.
- **Done when:** device tokens survive restart, are absent from cloud backup and device transfer (`dataExtractionRules`), are wiped on logout, and are never written to logs.
- **Depends on:** F-02.

---

## Phase 2 — Sign in (the OAuth-via-web-link requirement)

Read [AUTHENTICATION.md](AUTHENTICATION.md) in full before starting any of these. The two-authentication split (app↔bridge vs bridge↔Kiro) is the thing to get right.

### F-07 · Bridge pairing UX · `M`
- **Do:** onboarding that pairs the app to a bridge — QR scan (bridge prints a QR of `wss://host:port` + pairing code) plus manual entry fallback. Clear, non-generic error states for unreachable host, bad code, expired code, TLS failure.
- **Done when:** a user can pair by scanning, the token persists via F-06, and a wrong/expired code produces a message that says what to do next.
- **Onboarding must state honestly that a bridge host is required** (ADR-001 §4) — on the first screen, not buried in a help page.
- **Depends on:** F-03, F-06.

### F-08 · Kiro sign-in via device flow, relayed through the app · `M`
**This is the user-facing "sign in with your Kiro account via a web link" feature.**

- **Do:** implement the relay in [AUTHENTICATION §3](AUTHENTICATION.md#3-primary-flow--device-authorization-relayed-through-the-app). App asks the bridge to start sign-in; bridge drives `kiro-cli login --use-device-flow` and returns the verification URI + user code; app opens the URI in a **Custom Tab** and displays the code prominently with copy-to-clipboard; bridge polls; app reflects completion.
- **Hard requirements:** Custom Tabs only — **never a WebView** ([RFC 8252 §8.12](https://datatracker.ietf.org/doc/html/rfc8252#section-8.12)). Handle `authorization_pending`, `slow_down`, `access_denied`, `expired_token`. Probe `kiro-cli whoami` first and skip the flow if already signed in. The user code must stay visible when returning to the foreground.
- **Done when:** a signed-out bridge host can be brought to signed-in entirely from the phone, and no Kiro credential is ever stored in the app.
- **Depends on:** F-03, F-07. Informed by F-01 (question 1: is the CLI output parseable?).

### F-09 · Auth & entitlement state machine · `S`
- **Do:** the seven-state machine in [AUTHENTICATION §6](AUTHENTICATION.md#6-session-and-error-states-the-ui-must-handle), driving navigation.
- **Do not skip `NotEntitled`.** Cloud sessions require Pro or higher, and Identity Center orgs additionally require an admin to enable the preview. Without this state, an unentitled user gets an opaque failure at session creation and no idea why.
- **Done when:** every state has a distinct screen or banner and a stated next action; state survives process death.
- **Depends on:** F-07, F-08.

---

## Phase 3 — Create and run a cloud session

### F-10 · Session list, resume, delete · `M`
- **Do:** list cloud sessions with live status, ordered by recent activity; resume into F-12; delete with confirmation; pin.
- **Note:** renaming is not supported in the cloud-session preview — don't build the affordance.
- **Done when:** the list reflects real status, survives rotation and process death, and handles the empty state as an invitation to create a session rather than a blank screen.
- **Depends on:** F-05. Can be built against `FakeGateway` before F-03 lands.

### F-11 · New Cloud Session flow · `L`
**The headline feature.** The whole reason the app exists.

- **Do:** a create flow with — repository multi-select from the user's connected GitHub/GitLab account (removable pills, matching how other Kiro surfaces present bound repos); model selection; autonomy level (**Autopilot** or **Autonomous** only — Supervised does not exist for cloud sessions); first-prompt composer; submit, provision, and land in the live transcript.
- **Constraints to honour rather than paper over:** repositories are fixed at creation time; **branches cannot be selected** — set the expectation in the UI and mention that the agent can be asked to create a branch once running; the preview caps concurrent sessions at 10, so handle that failure specifically.
- **Done when:** a session can be created from the phone with 1..n repos and a first prompt, the composer is locked during provisioning with visible progress, and every documented failure mode has its own message.
- **Depends on:** F-05, F-09. Repository listing mechanism comes from F-01 (assumption A4).

### F-12 · Transcript rendering + streaming · `L`
- **Do:** the live conversation view — user messages, agent messages, collapsible tool-call entries with status, and the four update kinds from [ACP-INTEGRATION §4](ACP-INTEGRATION.md#4-streaming-updates).
- **Performance design is a requirement, not an optimisation** ([ADR-003 §3](adr/ADR-003-tech-stack.md#3-two-conventions-that-are-load-bearing)): coalesce chunks on a ~60–100 ms tick; render the in-flight message **outside** the lazy list and append it only at `TurnEnd`; never recompute highlighting per token on a growing string.
- **Done when:** a 500+ entry transcript scrolls smoothly on a mid-range device; an unknown update kind renders as a generic entry; `compaction/status` shows an indicator instead of an unexplained stall.
- **Depends on:** F-05. Build against fixtures from F-01.

### F-13 · Prompt composer · `M`
- **Do:** send prompts into a live session; attach images (the harness advertises `promptCapabilities.image` — on a phone this is a real differentiator: photograph a whiteboard, attach a failure screenshot); cancel an in-flight turn; queue a message while the agent is working to steer without cancelling.
- **Done when:** `session/prompt` content is sent as a typed block **array**; cancel takes effect promptly; images round-trip.
- **Depends on:** F-12.

### F-14 · Permission / approval UI · `M`
- **Do:** surface agent-initiated permission requests and answer them. On attach, **check for a pending approval before the transcript finishes replaying** — Kiro holds a request raised while no client was attached and presents it to the next client, so this is durable state, not a transient event.
- **Done when:** an approval can be granted or denied from the phone and the agent proceeds; a pending approval is never silently buried below the scroll.
- **Depends on:** F-12. Payload shape comes from F-01 (assumption A5).

### F-15 · Connection lifecycle: foreground service, reconnect, replay · `L`
The item that decides whether the app is trustworthy. A session that dies when the phone locks is a broken client regardless of how good the UI looks.

- **Do:** a `dataSync` foreground service for active turns; exponential backoff with jitter; eager reconnect on connectivity-regained; the `lastSeq` replay protocol from [ACP-INTEGRATION §7](ACP-INTEGRATION.md#7-reconnect-and-replay--our-design-not-kiros); explicit handling of Android 15's 6h/24h `dataSync` cap including `onTimeout()`; Doze-aware behaviour.
- **Done when:** backgrounding the app, locking the phone, flipping wifi→cellular, and killing the socket mid-turn all resume with **no gap and no duplicate entries**; log truncation past `lastSeq` triggers an honest full refetch rather than a silent hole.
- **Depends on:** F-03, F-12.

### F-16 · Push notifications · `M`
- **Do:** FCM for *turn finished* and *approval needed*, with inline allow/deny actions on the approval notification. Bridge sends; app receives, wakes, and answers.
- **Done when:** an approval can be answered from the notification shade with the app backgrounded; notification channels are separated so users can silence turn-completion without silencing approvals.
- **Depends on:** F-03, F-14, F-15.

---

## Phase 4 — Make it good

### F-17 · Code block and diff rendering · `L`
[ADR-002](adr/ADR-002-react-native-vs-native.md) identifies this as the **one area where the native choice is genuinely weaker** than React Native, and explicitly asks that it be budgeted as a named work item rather than absorbed as an afterthought. Treat that as the brief.

- **Do:** evaluate a Compose syntax highlighter vs. a contained WebView for diffs only; implement code blocks with language detection, horizontal scroll, and copy; implement unified/split diff viewing.
- **Done when:** the languages in the project's own test repos render correctly, and the approach + rejected alternatives are recorded in a short ADR-004.
- **Depends on:** F-12.

### F-18 · Result and PR surfacing · `S`
Cloud sessions deliver work through the source provider, usually as a pull request. Surface the PR link prominently on turn completion and in the session list; deep-link out to GitHub/GitLab.
**Depends on:** F-12.

### F-19 · Slash commands and MCP OAuth relay · `M`
Expose available slash commands from the `commands/available` notification with autocomplete via `commands/options`. Relay `mcp/oauth_request` URLs into a Custom Tab — the same browser-based pattern as F-08. Degrade silently if the extensions are absent.
**Depends on:** F-04, F-12.

### F-20 · Settings, logout, diagnostics · `S`
Bridge management (re-pair, forget), Kiro sign-out, cached-transcript clearing, a log viewer for support, version info. Logout wipes tokens (F-06) and cached transcripts.
**Depends on:** F-06, F-09.

### F-21 · Accessibility, theming, large screens · `M`
Dark theme, dynamic colour, TalkBack labels on tool-call entries and approval actions, font scaling to 200% without clipping, tablet/foldable layouts. **Approval dialogs must be fully operable with TalkBack** — it is the highest-stakes interaction in the app.
**Depends on:** F-11, F-12, F-14.

### F-22 · Observability and protocol-drift metrics · `S`
Track unknown-method and parse-failure rates. [ADR-002 §5](adr/ADR-002-react-native-vs-native.md#5-recommendation) makes this number the explicit trigger for revisiting the runtime decision — so it must actually be measured, not assumed. Local/opt-in only; never log prompt or repository content.
**Depends on:** F-04.

### F-23 · Release engineering and distribution · `M`
Signing, versioning, reproducible builds, and a distribution decision. **Note:** an unofficial client using Kiro's name and marks needs a real answer on naming, branding, and Play Store policy before publishing. Resolve alongside F-00.
**Depends on:** a shippable Phase 3.

---

## How to pick up an item

1. **Read [ADR-001](adr/ADR-001-cloud-session-access.md) first.** It rules out an entire (tempting) approach. A PR that adds a reverse-engineered `app.kiro.dev` endpoint is rejected on principle.
2. **Check the phase.** Phase 0 gates real confidence in everything else. If you take a Phase 2+ item before F-01 reports, state which unverified assumptions you are relying on.
3. **Respect the seams.** UI items talk to `CloudSessionGateway` (F-05), never to a transport. `core/` takes no Android dependency — CI enforces this.
4. **Use the paths in [ADR-003 §2](adr/ADR-003-tech-stack.md#2-module-layout).** If you need a new package, say so in the PR.
5. **Definition of done:** acceptance criteria met · unit tests for `core/` logic · no new lint/detekt warnings · docs updated if a decision changed · tolerant parsing preserved.
6. **If an assumption proves false, stop and report it** rather than working around it locally. ADR-001 §5 exists so that a refuted assumption updates the plan instead of quietly becoming one feature's private hack.

### Parallelisation

```
F-00 ─┐                                    (independent, non-code)
F-01 ─┴─► F-03 ─┬────────────────► F-07 ─► F-08 ─► F-09 ─┐
                │                                         │
F-02 ─► F-04 ─► F-05 ─┬─► F-10 ────────────────────────► F-11
        │             ├─► F-12 ─┬─► F-13                  │
        │             │         ├─► F-14 ─┐               │
        │             │         ├─► F-17  │               │
        │             │         └─► F-18  │               │
        │             └─► F-15 ───────────┴─► F-16        │
        └─► F-22                                          │
                                            F-19/20/21/23 ┘
```

- **Start now, in parallel:** F-00, F-01, F-02.
- **Widest parallel band:** after F-05, the UI items (F-10, F-12, F-17, F-18) can all proceed against `FakeGateway` while F-03 and F-15 handle the hard infrastructure.
- **Single-threaded chain:** F-03 → F-07 → F-08 → F-09. Auth is sequential by nature; don't split it across agents.
- **Highest-risk items:** F-01 (may invalidate the plan), F-15 (hardest to get right), F-03 (most security-sensitive).
