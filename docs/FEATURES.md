# Feature backlog

Work items for **KiroForAndroid** — an Android client for [Kiro](https://kiro.dev) cloud sessions.

Each item is scoped to be picked up independently by one agent. Read [§ How to pick up an item](#how-to-pick-up-an-item) before starting, and read [ADR-001](adr/ADR-001-cloud-session-access.md) first — it constrains every item here. [ADR-004](adr/ADR-004-work-repo-selection.md) (how a work repository is chosen) and [ADR-005](adr/ADR-005-bridge-hosting-and-availability.md) (where the bridge runs, and what the app does when it is unreachable) refine it and touch F-01, F-03, F-07, F-11, F-15 and F-16.

**Legend** — Size: `S` <1d · `M` 1–3d · `L` 1–2w · `XL` 2w+. `∥` = safe to run in parallel with its phase-mates.

---

## Phase 0 — Unblock (nothing downstream is trustworthy until this is done)

### F-00 · Pursue official third-party API access · `S` ∥
**Not a code task.** ADR-001 rejects reverse-engineering Kiro's private cloud API, which leaves the bridge topology as the only legitimate option. Official access would remove that constraint entirely, so it is worth asking early even though the answer may be no or silence.

- **Prior art, and it is discouraging:** [kirodotdev/Kiro#9460](https://github.com/kirodotdev/Kiro/issues/9460) already asks for remote/mobile browser access to a running session — very nearly this project's brief — and is **open with no maintainer reply**. Add to it rather than filing a duplicate, and treat the silence as data for Option C's timeline.
- **Do:** open (or join) a request on [`kirodotdev/Kiro`](https://github.com/kirodotdev/Kiro) asking about third-party client / API access for cloud sessions; ask on the [Kiro Discord](https://discord.gg/kirodotdev); check whether an Android app is on the public roadmap.
- **Done when:** the outcome is recorded in ADR-001 §2 Option C with a date, whatever the outcome. "No reply after N weeks" is a valid, recorded result.
- **Depends on:** nothing.

### F-01 · Protocol spike: verify assumptions, capture golden fixtures · `M` · ✅ **A1–A6 DONE 2026-09-02** · A7–A17 open
**Was the highest-value item in this backlog**, and it paid — for the six assumptions ADR-001 §5 originally listed. Report: **[PROTOCOL-FINDINGS.md](PROTOCOL-FINDINGS.md)**. Verdicts are in [ADR-001 §5](adr/ADR-001-cloud-session-access.md#5-assumptions--resolved-by-f-01-on-2026-09-02); fixtures are in [`core/src/test/resources/fixtures/`](../core/src/test/resources/fixtures/); the probe that produced them is [`tools/acp-probe/`](../tools/acp-probe/).

**Outcome in one line:** the architecture holds. A1, A2, A4, A5 verified; A3 refuted harmlessly (the prefix is `_kiro/`, and it is discoverable from the handshake); A6 partially refuted (sign-in is *more* interactive than assumed).

**Read PROTOCOL-FINDINGS before starting anything below.** Three of its findings change work already scoped here:

1. **`kiro-cli acp` needs `--agent-engine v3 --auth-method cli`.** The default engine cannot see cloud sessions at all — a client using default flags would wrongly conclude the whole approach is dead.
2. **KAS already implements much of F-03** — WebSocket ACP transport, multi-client multiplexing, permission correlation across reconnects, pending-permission re-send on attach.
3. **The documented streaming update list is wrong and incomplete.** Real kinds are `snake_case`, there is no `TurnEnd` kind, and a large set of `_kiro/*` notifications is undocumented entirely.

**Left open from the original six:** A5 was verified on a *local* session only — the cloud path is argued from KAS's design, not observed. F-03 confirms it on its first cloud turn.

**Extended, not yet run:** written in parallel with the spike above and merged afterward, [ADR-004 §7](adr/ADR-004-work-repo-selection.md#7-assumptions-to-verify--extends-adr-001-5-same-numbering) adds **A7–A12** (repository binding and enumeration — supersede A4) and [ADR-005 §7](adr/ADR-005-bridge-hosting-and-availability.md#7-assumptions-to-verify--extends-adr-001-5-and-adr-004-7) adds **A13–A17**. These were *not* exercised by the run above and remain open. One is already partly answered by PROTOCOL-FINDINGS without anyone having asked it that way: **A9** speculates that `commands/options` might expose `/repo` candidates, but the spike found something better already live — `_kiro/sourceProviders/list` and `/listResources` return the full repository catalog directly, no slash command needed (see [PROTOCOL-FINDINGS §3 A4](PROTOCOL-FINDINGS.md#a4--repositories-are-bindable-programmatically--verified-and-better-than-assumed)). ADR-004's Option B should be read in that light before anyone builds a `commands/options`-based catalog. Still genuinely open: **A8** (can repos be bound at creation with no forced TUI step?), and all of A13–A17 (multi-bridge fungibility, permission durability on a cloud session, headless credential survival, concurrent-session memory, on-device feasibility).

- **Do, for [ADR-004](adr/ADR-004-work-repo-selection.md) (A7, A8, A10–A12):** capture `--repo`'s syntax verbatim from `--help`; confirm a session can be created with repos bound non-interactively, with no forced `/repo` step; run that creation from an empty `/tmp` directory to confirm no working-directory or checkout dependency; confirm bindings are readable back from a loaded session; capture the error frame for a repository the account cannot reach.
- **Do, for [ADR-005](adr/ADR-005-bridge-hosting-and-availability.md) (A13–A16):** attach to one session from **two** hosts signed in as the same account; leave a permission request unanswered with no client attached on a **cloud** session, then reattach and check it is re-presented; leave a headless host idle overnight and confirm the CLI is still signed in; measure memory for several concurrent supervised sessions.
- **Done when:** each remaining assumption gets a verified/refuted verdict written back into the ADR that owns it — A7–A12 in ADR-004 §7, A13–A16 in ADR-005 §7. (A17 is F-24's job, not this one's.)
- **Depends on:** access to `kiro-cli` + a Pro account — already established; reuse the probe in [`tools/acp-probe/`](../tools/acp-probe/) rather than starting over.

### F-24 · Spike: can `kiro-cli` run on the device? · `S` ∥
Timeboxed, non-blocking, high upside. [ADR-005 §4 Option E](adr/ADR-005-bridge-hosting-and-availability.md#option-e--the-bridge-on-the-phone) notes that `kiro-cli` ships for Linux `aarch64`, and Termux with a `proot-distro` userland is Linux `aarch64`. If it runs there, the bridge requirement — ADR-001's single largest cost — disappears without needing official API access.

- **Do:** install `kiro-cli` under Termux + `proot-distro`; sign in; create a cloud session; leave it running while the screen is off. Stop at the first hard blocker.
- **Done when:** ADR-005 assumption A17 is answered yes or no in writing, with the blocker named. A one-paragraph "no, because X" is a complete and valuable result.
- **Scope out:** productising it. Even a success changes an ADR before it changes any code — Doze, battery, and holding a signed-in Kiro account on a phone are separate questions.
- **Depends on:** nothing.

---

## Phase 1 — Foundation

### F-02 · Project scaffold, modules, CI · `M`
- **Do:** create the three-module Gradle project exactly as specified in [ADR-003 §2](adr/ADR-003-tech-stack.md#2-module-layout) (`app/`, `core/`, `bridge/`), with the version catalog from ADR-003 §1. Add CI: build, unit tests, ktlint/detekt, and **a check that fails if `core/` contains any `android.*` or `androidx.*` import** (ADR-003's one hard rule — enforce it mechanically). Pin `JAVA_HOME` to JDK 17 or 21 in CI; AGP rejects JDK 22+.
- **Done when:** `./gradlew build` is green from a clean clone, CI runs on PRs, and the core-purity check demonstrably fails when someone adds an Android import.
- **Also:** this item **fixes the DI pattern** for the project (manual `ServiceLocator` or Hilt). Whatever it picks, record it in ADR-003 §1 so later items don't diverge.
- **Depends on:** nothing. Start immediately, in parallel with F-01.

### F-03 · Bridge service (MVP) · `L`
The host-side process from ADR-001. Not a dev convenience — it is the product's backend. [ADR-005](adr/ADR-005-bridge-hosting-and-availability.md) decides its shape: it is a **thin relay** — no checkout, no git credentials, no meaningful working directory (pin one anyway so stray *local* sessions never enter our list, and filter on the `environment` column) — and it ships as a **multi-arch container image** with a safe-by-default network posture.

- **Start by reading [PROTOCOL-FINDINGS §4](PROTOCOL-FINDINGS.md#4-the-larger-finding-kas-already-solves-most-of-f-3).** This item was sized `L` partly to build reconnect, replay, and cross-connection permission correlation from scratch. KAS ships all three. **Do not build them before establishing what you can reuse** — the honest first task here is a day of reading, not a week of coding.
- **Do:** a process that runs where `kiro-cli` is installed and: spawns/supervises `kiro-cli acp --agent-engine v3 --auth-method cli` (the flags are mandatory — see PROTOCOL-FINDINGS §2); exposes JSON-RPC over an authenticated WebSocket; issues single-use pairing codes and long-lived revocable device tokens; and preserves ordered replay across reconnects.
- **Replay: try `messageId` before inventing sequence numbers.** Every update already carries `_meta.kiro.messageId` and `timestamp`, and `session/load` replays history in order. The `_bridge/…` scheme in [ACP-INTEGRATION §7](ACP-INTEGRATION.md#7-reconnect-and-replay--our-design-not-kiros) is our design, not Kiro's, and may now be redundant. Record which way you went and why.
- **Login needs a pty, not a pipe.** `kiro-cli login --use-device-flow` opens an interactive provider-picker TUI before printing anything parseable (PROTOCOL-FINDINGS A6). Budget for it.
- **Confirm A5 on your first cloud turn** and update ADR-001 §5.
- **Security requirements** (from [AUTHENTICATION §4](AUTHENTICATION.md#4-auth-1-pairing-the-app-to-the-bridge), all mandatory): binds `127.0.0.1` by default with explicit opt-in to `0.0.0.0`; TLS required for non-loopback; pairing codes single-use and ~5 min TTL; rate-limited pairing; revocable device list.
- **Done when:** a WebSocket client can pair, create a cloud session, send a prompt, receive streamed updates, disconnect mid-turn, reconnect with `lastSeq`, and receive exactly the missed updates with no gap and no duplication.
- **Decide and record:** bridge language (Kotlin/JVM vs Node/TS) per [ADR-003 §2](adr/ADR-003-tech-stack.md#bridge-language--open-decision). Write the outcome back into that ADR.
- **Depends on:** F-01 (needs verified CLI behaviour).

### F-04 · ACP protocol layer in `core/` · `M` ∥
- **Do:** JSON-RPC 2.0 codec (request / response / notification / server-initiated request), `AcpTransport` interface, `AcpClient` handling correlation, timeouts, and cancellation. Implement the handshake from [ACP-INTEGRATION §2](ACP-INTEGRATION.md#2-handshake) — including advertising `fs` and `terminal` as **false**, which is intentional and differs from Kiro's editor example. `ignoreUnknownKeys = true` throughout.
- **Derive the extension prefix from `initialize`**, don't match constants. The handshake enumerates the agent's extension methods and the namespace is server-configurable. (`_kiro/` is what 2.19.2 sends.)
- **Model the real update set, not the documented one.** Most session state arrives as `session_info_update` discriminated by `_meta.kiro.kind` — `turn_start`, `turn_end`, `context_usage`, `focus_update`, `pendingInteraction`, `interactionResolved`, `promptTurnSummaries`, `displayError`. There is no `TurnEnd` update kind. See [PROTOCOL-FINDINGS §5](PROTOCOL-FINDINGS.md#5-corrections-to-the-documented-protocol).
- **Done when:** unit tests pass against a fake transport and against F-01's golden fixtures in [`core/src/test/resources/fixtures/`](../core/src/test/resources/fixtures/); an unknown method or malformed frame is logged and dropped without terminating the session (test this explicitly — F-01 found a large undocumented `_kiro/*` notification set, so this is load-bearing, not polish).
- **Depends on:** F-02. Fixtures are ready.

### F-05 · `CloudSessionGateway` + `BridgeGateway` · `M`
- **Do:** the interface every feature codes against — `createSession`, `listSessions`, `loadSession`, `prompt`, `cancel`, `setModel`, `setMode`, `respondToPermission`, `respondToUserInput`, and `updates: Flow<SessionUpdate>`. Plus `BridgeGateway`, implementing it over F-04.
- **Shapes fixed by F-01:** `listSessions` takes a source (`local`/`remote`/`all`) and a scope (`workspace`/`user`/`both`); a session carries **two** statuses — `status` (is the agent working) and `instanceStatus` (is the sandbox VM up) — and they must stay distinct all the way to the UI. `respondToUserInput` exists because `_kiro/userInput` is a second human-in-the-loop channel alongside permissions.
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
- **Onboarding must state honestly that a bridge host is required** (ADR-001 §4) — on the first screen, not buried in a help page. Follow the five-step order in [ADR-005 §5.4](adr/ADR-005-bridge-hosting-and-availability.md#54-onboarding-tells-the-truth-in-this-order): state the requirement → recommend an always-on host and name what a workstation-only bridge costs → pair → sign in to Kiro → **connect a source provider** in Kiro's own settings via Custom Tab. Skipping the last step leaves F-11's repository picker empty with nothing to explain why.
- **Pair with a *list* of bridges, not one** ([ADR-005 §5.2](adr/ADR-005-bridge-hosting-and-availability.md#52-multiple-bridges-are-a-supported-configuration-not-an-accident)). Sessions live in the Kiro account, so any bridge signed in as the same account can reach them; store bridges with a per-bridge last-seen.
- **Depends on:** F-03, F-06.

### F-08 · Kiro sign-in via device flow, relayed through the app · `M`
**This is the user-facing "sign in with your Kiro account via a web link" feature.**

- **Do:** implement the relay in [AUTHENTICATION §3](AUTHENTICATION.md#3-primary-flow--device-authorization-relayed-through-the-app). App asks the bridge to start sign-in **and names the provider**; bridge drives `kiro-cli login --use-device-flow` over a **pty**, answers its provider-picker TUI, and returns the verification URI + user code; app opens the URI in a **Custom Tab** and displays the code prominently with copy-to-clipboard; bridge polls; app reflects completion.
- **Corrected by F-01 (A6):** provider selection happens in the *CLI*, not the browser, so the app must present the four providers itself — Builder ID, Google, GitHub, Your Organization. The verification URI already carries the user code as a query parameter, so the Custom Tab can arrive pre-filled. And `login` **refuses while already signed in** — re-auth requires an explicit `kiro-cli logout`, which must be a deliberate user action, never an automatic retry.
- **Hard requirements:** Custom Tabs only — **never a WebView** ([RFC 8252 §8.12](https://datatracker.ietf.org/doc/html/rfc8252#section-8.12)). Handle `authorization_pending`, `slow_down`, `access_denied`, `expired_token`. Probe `kiro-cli whoami` first and skip the flow if already signed in. The user code must stay visible when returning to the foreground.
- **Done when:** a signed-out bridge host can be brought to signed-in entirely from the phone, and no Kiro credential is ever stored in the app.
- **Depends on:** F-03, F-07. Output format and its interactive prelude are pinned by [PROTOCOL-FINDINGS A6](PROTOCOL-FINDINGS.md#a6--login---use-device-flow-is-scriptable--partially-refuted).

### F-09 · Auth & entitlement state machine · `S`
- **Do:** the seven-state machine in [AUTHENTICATION §6](AUTHENTICATION.md#6-session-and-error-states-the-ui-must-handle), driving navigation.
- **Do not skip `NotEntitled`.** Cloud sessions require Pro or higher, and Identity Center orgs additionally require an admin to enable the preview. Without this state, an unentitled user gets an opaque failure at session creation and no idea why.
- **You cannot pre-check entitlement.** F-01 found `whoami --format json` returns only `accountType` and `email` (and `{"account":null}` when signed out) — no plan or entitlement field. So `NotEntitled` has to be derived from the error a cloud create actually returns, not from a capability probe.
- **Done when:** every state has a distinct screen or banner and a stated next action; state survives process death.
- **Depends on:** F-07, F-08.

---

## Phase 3 — Create and run a cloud session

### F-10 · Session list, resume, delete · `M`
- **Do:** list cloud sessions with live status, ordered by recent activity; resume into F-12; delete with confirmation; pin.
- **Show both statuses.** A session carries `status` (`idle`/`in_progress`) and `instanceStatus` (the sandbox VM's own lifecycle, e.g. `suspended`). "Idle but suspended" and "idle and warm" mean different wait times when you tap in, and only one of them is instant.
- **The roster pushes itself.** `_kiro/sessions/changed` delivers upserts and deletes with status transitions, so the list can be live without polling.
- **Note:** renaming is not supported in the cloud-session preview — don't build the affordance.
- **Done when:** the list reflects real status, survives rotation and process death, and handles the empty state as an invitation to create a session rather than a blank screen.
- **Depends on:** F-05. Can be built against `FakeGateway` before F-03 lands.

### F-11 · New Cloud Session flow · `L`
**The headline feature.** The whole reason the app exists.

- **Do:** a create flow with — repository multi-select from the user's connected GitHub/GitLab account (removable pills, matching how other Kiro surfaces present bound repos); model selection; autonomy level (**Autopilot** or **Autonomous** only — Supervised does not exist for cloud sessions); first-prompt composer; submit, provision, and land in the live transcript.
- **Constraints to honour rather than paper over:** repositories are fixed at creation time; **branches cannot be selected** — set the expectation in the UI and mention that the agent can be asked to create a branch once running; the preview caps concurrent sessions at 10, so handle that failure specifically.
- **Done when:** a session can be created from the phone with 1..n repos and a first prompt, the composer is locked during provisioning with visible progress, and every documented failure mode has its own message.
- **Easier than scoped:** F-01 found a first-class repository catalog already live — `_kiro/sourceProviders/list` gives providers with a `connectionStatus` (so "connect GitLab first" is renderable instead of an empty list), and `/listResources` gives repos with `visibility` and `defaultBranch`. Show the default branch; don't offer to change it.
- **The picker is specified in [ADR-004 §5](adr/ADR-004-work-repo-selection.md#5-decision):** layered `RepoCatalog` implementations, falling through from richest to always-available — `_kiro/sourceProviders/listResources` (the catalog above, superseding ADR-004's `commands/options` proposal — use this layer first), most-recently-used repos derived from existing sessions, and manual `owner/repo` entry, which is a **permanent affordance, never a failure state**. Validation is shape-only; a server rejection most likely means the Kiro Agent App is not installed for that repository, and the error should say so and offer a Custom Tab into Kiro's settings.
- **Depends on:** F-05, F-09. Whether repos can be bound at creation with no forced interactive step is still open (ADR-004 A8) — see F-01.

### F-12 · Transcript rendering + streaming · `L`
- **Do:** the live conversation view — user messages, agent messages, collapsible tool-call entries with status, and the update kinds from [ACP-INTEGRATION §4](ACP-INTEGRATION.md#4-streaming-updates). There are **seven**, not four, and turn boundaries are not their own kind — see [PROTOCOL-FINDINGS §5](PROTOCOL-FINDINGS.md#5-corrections-to-the-documented-protocol).
- **Performance design is a requirement, not an optimisation** ([ADR-003 §3](adr/ADR-003-tech-stack.md#3-two-conventions-that-are-load-bearing)): coalesce chunks on a ~60–100 ms tick; render the in-flight message **outside** the lazy list and append it only at `TurnEnd`; never recompute highlighting per token on a growing string.
- **Done when:** a 500+ entry transcript scrolls smoothly on a mid-range device; an unknown update kind renders as a generic entry; `compaction/status` shows an indicator instead of an unexplained stall. Note the real replay volume: F-01's cloud session replayed **991 updates** on a single `session/load`, so replay performance is on the critical path, not a tail case.
- **Depends on:** F-05. Build against fixtures from F-01.

### F-13 · Prompt composer · `M`
- **Do:** send prompts into a live session; attach images (the harness advertises `promptCapabilities.image` — on a phone this is a real differentiator: photograph a whiteboard, attach a failure screenshot); cancel an in-flight turn; queue a message while the agent is working to steer without cancelling.
- **Done when:** `session/prompt` content is sent as a typed block **array**; cancel takes effect promptly; images round-trip.
- **Depends on:** F-12.

### F-14 · Permission / approval UI · `M`
- **Do:** surface agent-initiated permission requests and answer them. On attach, **check for a pending approval before the transcript finishes replaying** — Kiro holds a request raised while no client was attached and presents it to the next client, so this is durable state, not a transient event.
- **Render `options[]` as sent.** F-01 observed four (`allow_once`, `allow_always`, `reject_once`, `reject_always`) but the list is agent-supplied — key off `kind`, don't hard-code. `_meta.kiro.consent` gives the capability, the concrete resource, and whether the ask was implicit — enough to make a notification readable without opening the session.
- **`pendingInteraction` / `interactionResolved` arrive in the stream.** Use them to render the waiting state, and to clear it when *another* client answers first.
- **Also build `_kiro/userInput`.** It is a second, distinct channel: the agent asking a free-text question mid-turn. Undocumented, and the plan did not know about it.
- **Done when:** an approval can be granted or denied from the phone and the agent proceeds; a pending approval is never silently buried below the scroll; a free-text agent question can be answered or dismissed.
- **Depends on:** F-12. Payload shape is pinned by [`prompt-turn-with-permission.jsonl`](../core/src/test/resources/fixtures/prompt-turn-with-permission.jsonl).

### F-15 · Connection lifecycle: foreground service, reconnect, replay · `L`
The item that decides whether the app is trustworthy. A session that dies when the phone locks is a broken client regardless of how good the UI looks.

- **Do:** a `dataSync` foreground service for active turns; exponential backoff with jitter; eager reconnect on connectivity-regained; a replay protocol — **check F-03's decision first**, since `_meta.kiro.messageId` may make the `lastSeq` scheme in [ACP-INTEGRATION §7](ACP-INTEGRATION.md#7-reconnect-and-replay--our-design-not-kiros) unnecessary; explicit handling of Android 15's 6h/24h `dataSync` cap including `onTimeout()`; Doze-aware behaviour.
- **Answering an approval does not require the original connection.** KAS correlates permissions by `toolCallId` (`_kiro/permission/respond`), which is exactly the mobile case: notification arrives, socket has since dropped, user taps Allow on a fresh one.
- **Also do — the degradation contract** from [ADR-005 §5.3](adr/ADR-005-bridge-hosting-and-availability.md#53-the-degradation-contract), which is acceptance criteria, not polish: render the session list from cache with a visible "last synced"; name the state ("bridge unreachable — last seen 3h ago", and say the machine is probably asleep when the only bridge is a workstation); disable create **with a reason**; select between paired bridges and refetch the transcript when the chosen bridge has no replay log for a session. **Never queue prompts for later delivery** — a prompt composed hours ago against unseen state, delivered unattended to an autonomous agent with repository write access, is the one failure mode worth designing out. Hold the draft; send it when the user is present.
- **Done when:** backgrounding the app, locking the phone, flipping wifi→cellular, and killing the socket mid-turn all resume with **no gap and no duplicate entries**; log truncation past `lastSeq` triggers an honest full refetch rather than a silent hole; and every bridge-unreachable path shows a named state rather than a spinner.
- **Depends on:** F-03, F-12.

### F-16 · Push notifications · `M`
**Delivery depends on a reachable bridge** — pushes are sent by it. A sleeping workstation bridge is a silent app; that is a documented limitation ([ADR-005 §3](adr/ADR-005-bridge-hosting-and-availability.md#3-the-availability-question)), not a bug to chase, and it is the main reason an always-on host is the recommended setup.
- **Do:** FCM for *turn finished* and *approval needed*, with inline allow/deny actions on the approval notification. Bridge sends; app receives, wakes, and answers.
- **Done when:** an approval can be answered from the notification shade with the app backgrounded; notification channels are separated so users can silence turn-completion without silencing approvals.
- **Depends on:** F-03, F-14, F-15.

---

## Phase 4 — Make it good

### F-17 · Code block and diff rendering · `L`
[ADR-002](adr/ADR-002-react-native-vs-native.md) identifies this as the **one area where the native choice is genuinely weaker** than React Native, and explicitly asks that it be budgeted as a named work item rather than absorbed as an afterthought. Treat that as the brief.

- **Do:** evaluate a Compose syntax highlighter vs. a contained WebView for diffs only; implement code blocks with language detection, horizontal scroll, and copy; implement unified/split diff viewing.
- **Done when:** the languages in the project's own test repos render correctly, and the approach + rejected alternatives are recorded in a short **ADR-006** (004 and 005 are taken).
- **Depends on:** F-12.

### F-18 · Result and PR surfacing · `S`
Cloud sessions deliver work through the source provider, usually as a pull request. Surface the PR link prominently on turn completion and in the session list; deep-link out to GitHub/GitLab.
**Depends on:** F-12.

### F-19 · Slash commands and MCP OAuth relay · `M`
Expose available slash commands from the `commands/available` notification with autocomplete via `commands/options`. Relay `mcp/oauth_request` URLs into a Custom Tab — the same browser-based pattern as F-08. Degrade silently if the extensions are absent.
**Depends on:** F-04, F-12.

### F-19b · Per-turn cost display · `S` ∥
*Added by F-01.* Every turn ends with a `promptTurnSummaries` payload carrying credit spend and the tools used. Surface it — a running per-session cost is genuinely useful on a metered plan, and it is close to free given the data already arrives.
**Depends on:** F-12.

### F-20 · Settings, logout, diagnostics · `S`
Bridge management (re-pair, forget), Kiro sign-out, cached-transcript clearing, a log viewer for support, version info. Logout wipes tokens (F-06) and cached transcripts. **Kiro sign-out is destructive** — it runs `kiro-cli logout` on the bridge host, ending cloud access for every client, so confirm it explicitly.
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
2. **Read [PROTOCOL-FINDINGS.md](PROTOCOL-FINDINGS.md).** F-01 has reported. It corrects the documented protocol in several places and shrinks at least one item's scope; starting from the docs alone will send you down a path F-01 already closed.
3. **Respect the seams.** UI items talk to `CloudSessionGateway` (F-05), never to a transport. `core/` takes no Android dependency — CI enforces this.
4. **Use the paths in [ADR-003 §2](adr/ADR-003-tech-stack.md#2-module-layout).** If you need a new package, say so in the PR.
5. **Definition of done:** acceptance criteria met · unit tests for `core/` logic · no new lint/detekt warnings · docs updated if a decision changed · tolerant parsing preserved.
6. **If an assumption proves false, stop and report it** rather than working around it locally. ADR-001 §5 exists so that a refuted assumption updates the plan instead of quietly becoming one feature's private hack.

### Parallelisation

```
F-00 ─┐                                    (independent, non-code)
F-24 ─┤                                    (independent spike, ADR-005 A17)
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

- **Start now, in parallel:** F-00, F-02, F-24, and F-01's A7–A17 follow-up. (A1–A6 are done — their fixtures and findings are ready for F-03/F-04.)
- **Widest parallel band:** after F-05, the UI items (F-10, F-12, F-17, F-18) can all proceed against `FakeGateway` while F-03 and F-15 handle the hard infrastructure.
- **Single-threaded chain:** F-03 → F-07 → F-08 → F-09. Auth is sequential by nature; don't split it across agents.
- **Highest-risk items:** F-15 (hardest to get right) and F-03 (most security-sensitive). F-01 was the highest-risk item and has now reported without invalidating the plan.
